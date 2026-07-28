import json
import logging
import os
import re
import time
from collections import Counter
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

import httpx
from pydantic import ValidationError

from app.models import (
    AnalyzeResult,
    ExtractedField,
    FactField,
    MetadataPreview,
    RewriteResponse,
    ServiceSession,
    SourceSegment,
    StepCard,
    TextRequest,
)
from app.prompts import guide_extract_v1, guide_rewrite_v1, metadata_extract_v1
from app.providers.base import LlmProvider


LOGGER = logging.getLogger("uvicorn.error")


def _deduplicate_warnings(warnings: list[str]) -> list[str]:
    result: list[str] = []
    keys: list[str] = []
    for warning in warnings:
        for clause in re.split(r"[。；;]+", warning):
            display = clause.strip(" ，,。；;")
            if not display:
                continue
            display = display.replace("无需", "不需要")
            key = re.sub(r"[\s，,。；;！!]", "", display)
            key = key.replace("无需", "不需要").replace("向您", "").replace("您", "")
            if key in keys:
                index = keys.index(key)
                if "不需要" in display and "无需" in result[index]:
                    result[index] = display + "。"
                continue
            result.append(display + "。")
            keys.append(key)
    return result


class ExternalProviderError(RuntimeError):
    """Safe external-provider failure that never contains credentials."""


@dataclass(frozen=True)
class ExternalSettings:
    base_url: str
    api_key: str
    model: str
    timeout_seconds: float
    max_retries: int
    max_tokens: int
    thinking: str
    prompt_version: str

    @classmethod
    def from_environment(cls) -> "ExternalSettings":
        api_key = os.getenv("EXTERNAL_LLM_API_KEY", "").strip()
        if not api_key:
            raise ExternalProviderError(
                "外部模型配置错误：缺少 EXTERNAL_LLM_API_KEY"
            )
        base_url = os.getenv(
            "EXTERNAL_LLM_BASE_URL", "https://api.deepseek.com"
        ).strip()
        model = os.getenv(
            "EXTERNAL_LLM_MODEL", "deepseek-v4-flash"
        ).strip()
        if not base_url or not model:
            raise ExternalProviderError(
                "外部模型配置错误：BASE_URL 和 MODEL 不能为空"
            )
        if model in {"deepseek-chat", "deepseek-reasoner"}:
            raise ExternalProviderError(
                "外部模型配置错误：请使用 deepseek-v4-flash、deepseek-v4-pro 或其他受支持模型"
            )
        try:
            timeout_seconds = float(
                os.getenv("EXTERNAL_LLM_TIMEOUT_SECONDS", "60")
            )
            max_retries = int(os.getenv("EXTERNAL_LLM_MAX_RETRIES", "2"))
            max_tokens = int(os.getenv("EXTERNAL_LLM_MAX_TOKENS", "6000"))
        except ValueError as exc:
            raise ExternalProviderError(
                "外部模型配置错误：超时、重试次数和 token 数必须是数字"
            ) from exc
        if timeout_seconds <= 0 or max_retries < 0 or max_tokens <= 0:
            raise ExternalProviderError(
                "外部模型配置错误：超时和 token 数必须大于 0，重试次数不能小于 0"
            )
        thinking = os.getenv(
            "EXTERNAL_LLM_THINKING", "disabled"
        ).strip() or "disabled"
        prompt_version = os.getenv(
            "JIANDA_PROMPT_VERSION", "v1"
        ).strip() or "v1"
        if thinking not in {"disabled", "enabled"}:
            raise ExternalProviderError(
                "外部模型配置错误：EXTERNAL_LLM_THINKING 只能是 disabled 或 enabled"
            )
        if prompt_version != "v1":
            raise ExternalProviderError(
                "外部模型配置错误：当前仅支持 JIANDA_PROMPT_VERSION=v1"
            )
        return cls(
            base_url=base_url,
            api_key=api_key,
            model=model,
            timeout_seconds=timeout_seconds,
            max_retries=max_retries,
            max_tokens=max_tokens,
            thinking=thinking,
            prompt_version=prompt_version,
        )


@dataclass(frozen=True)
class CompletionResult:
    payload: dict[str, Any]
    request_id: str
    finish_reason: str
    prompt_tokens: int
    completion_tokens: int
    total_tokens: int
    elapsed_ms: int
    retry_count: int


class ExternalLlmProvider(LlmProvider):
    """Two-stage OpenAI-compatible provider with strict source tracing."""

    def __init__(
        self,
        settings: ExternalSettings | None = None,
        client: httpx.Client | None = None,
        sleep: Callable[[float], None] = time.sleep,
    ) -> None:
        self.settings = settings or ExternalSettings.from_environment()
        self.endpoint = self._completion_endpoint(self.settings.base_url)
        self.client = client
        self.sleep = sleep

    @staticmethod
    def _completion_endpoint(base_url: str) -> str:
        normalized = base_url.rstrip("/")
        if normalized.endswith("/chat/completions"):
            return normalized
        return f"{normalized}/chat/completions"

    def analyze(self, request: TextRequest) -> AnalyzeResult:
        active_client = self.client or httpx.Client(
            timeout=self.settings.timeout_seconds
        )
        try:
            LOGGER.info(
                "external_llm stage=fact_extract prompt_version=%s",
                self.settings.prompt_version,
            )
            fact_result = self._completion(
                active_client,
                [
                    {"role": "system", "content": guide_extract_v1.SYSTEM_PROMPT},
                    {
                        "role": "user",
                        "content": guide_extract_v1.build_task_prompt(
                            request, self.settings.prompt_version
                        ),
                    },
                ],
                stage="fact_extract",
            )
            model_facts = self._validate_facts(fact_result.payload, request)
            facts = self._complete_explicit_temporal_facts(
                model_facts, request
            )
            facts = self._split_duplicate_audience_eligibility(facts)
            model_sessions = self._validate_sessions(
                fact_result.payload.get("sessions"), request
            )
            sessions = self._complete_explicit_sessions(
                model_sessions, request
            )
            LOGGER.info(
                "provider=external model=%s prompt_version=%s "
                "stage=fact_completion model_field_count=%s "
                "source_rule_added_count=%s final_field_count=%s",
                self.settings.model,
                self.settings.prompt_version,
                len(model_facts),
                len(facts) - len(model_facts),
                len(facts),
            )

            LOGGER.info(
                "external_llm stage=accessible_rewrite prompt_version=%s",
                self.settings.prompt_version,
            )
            rewrite_result = self._completion(
                active_client,
                [
                    {"role": "system", "content": guide_rewrite_v1.SYSTEM_PROMPT},
                    {
                        "role": "user",
                        "content": guide_rewrite_v1.build_task_prompt(
                            request,
                            facts,
                            sessions,
                            self.settings.prompt_version,
                        ),
                    },
                ],
                stage="accessible_rewrite",
            )
            rewrite = self._validate_rewrite(rewrite_result.payload)
            rewrite = self._rewrite_with_sessions(rewrite, facts, sessions)
            return AnalyzeResult(
                fields=[
                    ExtractedField(
                        field_type=field.field_type,
                        label=field.label,
                        value=field.value,
                        page_no=field.page_no,
                        segment_no=1,
                        segment_id=field.segment_id,
                        source_quote=field.source_quote,
                        confidence=field.confidence,
                    )
                    for field in facts
                ],
                sessions=sessions,
                summary=rewrite.summary,
                plain_text=rewrite.plain_text,
                steps=rewrite.steps,
                warnings=rewrite.warnings,
                term_explanations=rewrite.term_explanations,
                audio_script=rewrite.audio_script,
            )
        finally:
            if self.client is None:
                active_client.close()

    def preview_metadata(
        self,
        text: str,
        filename: str,
        deterministic: MetadataPreview,
    ) -> MetadataPreview:
        active_client = self.client or httpx.Client(
            timeout=self.settings.timeout_seconds
        )
        try:
            result = self._completion(
                active_client,
                [
                    {"role": "system", "content": metadata_extract_v1.SYSTEM_PROMPT},
                    {
                        "role": "user",
                        "content": metadata_extract_v1.build_prompt(
                            text, filename, deterministic.model_dump()
                        ),
                    },
                ],
                stage="metadata_preview",
                max_tokens=800,
            )
            try:
                preview = MetadataPreview.model_validate(result.payload)
            except ValidationError as exc:
                raise ExternalProviderError("外部模型元数据结果不符合 JSON Schema") from exc
            if (
                preview.authority_status == "DOCUMENT_EVIDENCE"
                and (
                    not preview.evidence_quote
                    or preview.evidence_quote not in text
                    or preview.source_name not in preview.evidence_quote
                )
            ):
                raise ExternalProviderError("外部模型元数据发布机构证据无法追溯")
            return preview
        finally:
            if self.client is None:
                active_client.close()

    def _completion(
        self,
        client: httpx.Client,
        messages: list[dict[str, str]],
        stage: str,
        max_tokens: int | None = None,
    ) -> CompletionResult:
        request_body = {
            "model": self.settings.model,
            "messages": messages,
            "stream": False,
            "response_format": {"type": "json_object"},
            "max_tokens": max_tokens or self.settings.max_tokens,
            "thinking": {"type": self.settings.thinking},
        }
        attempts = self.settings.max_retries + 1
        for attempt in range(attempts):
            started = time.perf_counter()
            try:
                response = client.post(
                    self.endpoint,
                    headers={
                        "Authorization": f"Bearer {self.settings.api_key}",
                        "Content-Type": "application/json",
                    },
                    json=request_body,
                )
            except (httpx.TimeoutException, httpx.NetworkError) as exc:
                self._log_http_audit(
                    stage=stage,
                    http_status=0,
                    request_id="none",
                    finish_reason="network_error",
                    elapsed_ms=self._elapsed_ms(started),
                    retry_count=attempt,
                )
                if attempt + 1 < attempts:
                    self._backoff(attempt)
                    continue
                raise ExternalProviderError(
                    f"外部模型{stage}请求超时或连接失败"
                ) from exc

            elapsed_ms = self._elapsed_ms(started)
            header_request_id = self._safe_identifier(
                response.headers.get("x-request-id")
                or response.headers.get("request-id")
                or ""
            )
            if response.status_code in (401, 403):
                self._log_http_audit(
                    stage, response.status_code, header_request_id,
                    "authentication_error", elapsed_ms, attempt
                )
                raise ExternalProviderError(
                    f"外部模型鉴权失败（HTTP {response.status_code}）"
                )
            if response.status_code == 429 or response.status_code >= 500:
                self._log_http_audit(
                    stage, response.status_code, header_request_id,
                    "transient_http_error", elapsed_ms, attempt
                )
                if attempt + 1 < attempts:
                    self._backoff(attempt)
                    continue
                raise ExternalProviderError(
                    f"外部模型暂时不可用（HTTP {response.status_code}）"
                )
            if not 200 <= response.status_code < 300:
                self._log_http_audit(
                    stage, response.status_code, header_request_id,
                    "http_error", elapsed_ms, attempt
                )
                raise ExternalProviderError(
                    f"外部模型请求失败（HTTP {response.status_code}）"
                )
            try:
                envelope = response.json()
            except ValueError as exc:
                self._log_http_audit(
                    stage, response.status_code, header_request_id,
                    "invalid_envelope_json", elapsed_ms, attempt
                )
                raise ExternalProviderError(
                    f"外部模型{stage}响应不是合法 JSON"
                ) from exc
            if not isinstance(envelope, dict):
                self._log_http_audit(
                    stage, response.status_code, header_request_id,
                    "invalid_envelope_type", elapsed_ms, attempt
                )
                raise ExternalProviderError(
                    f"外部模型{stage}响应必须是 JSON 对象"
                )
            choices = envelope.get("choices")
            request_id = self._safe_identifier(
                str(envelope.get("id") or header_request_id)
            )
            usage = envelope.get("usage")
            usage = usage if isinstance(usage, dict) else {}
            if not isinstance(choices, list) or not choices:
                self._log_http_audit(
                    stage, response.status_code, request_id,
                    "missing_choices", elapsed_ms, attempt, usage
                )
                raise ExternalProviderError(
                    f"外部模型{stage}响应缺少 choices"
                )
            choice = choices[0]
            if not isinstance(choice, dict):
                self._log_http_audit(
                    stage, response.status_code, request_id,
                    "invalid_choice", elapsed_ms, attempt, usage
                )
                raise ExternalProviderError(
                    f"外部模型{stage}响应 choices 格式错误"
                )
            finish_reason = str(choice.get("finish_reason") or "unknown")
            if finish_reason == "length":
                self._log_http_audit(
                    stage, response.status_code, request_id,
                    finish_reason, elapsed_ms, attempt, usage
                )
                raise ExternalProviderError(
                    f"外部模型{stage}输出因长度限制被截断"
                )
            message = choice.get("message")
            if not isinstance(message, dict):
                self._log_http_audit(
                    stage, response.status_code, request_id,
                    "missing_message", elapsed_ms, attempt, usage
                )
                raise ExternalProviderError(
                    f"外部模型{stage}响应缺少 message"
                )
            content = message.get("content")
            if not isinstance(content, str) or not content.strip():
                self._log_http_audit(
                    stage, response.status_code, request_id,
                    "empty_content", elapsed_ms, attempt, usage
                )
                if attempt + 1 < attempts:
                    self._backoff(attempt)
                    continue
                raise ExternalProviderError(
                    f"外部模型{stage}返回空 content"
                )
            try:
                parsed = json.loads(content)
            except json.JSONDecodeError as exc:
                self._log_http_audit(
                    stage, response.status_code, request_id,
                    "invalid_content_json", elapsed_ms, attempt, usage
                )
                raise ExternalProviderError(
                    f"外部模型{stage}content 不是合法 JSON"
                ) from exc
            if not isinstance(parsed, dict):
                self._log_http_audit(
                    stage, response.status_code, request_id,
                    "invalid_content_type", elapsed_ms, attempt, usage
                )
                raise ExternalProviderError(
                    f"外部模型{stage}content 必须是 JSON 对象"
                )
            self._log_http_audit(
                stage, response.status_code, request_id,
                finish_reason, elapsed_ms, attempt, usage
            )
            return CompletionResult(
                payload=parsed,
                request_id=request_id,
                finish_reason=finish_reason,
                prompt_tokens=self._safe_int(usage.get("prompt_tokens")),
                completion_tokens=self._safe_int(usage.get("completion_tokens")),
                total_tokens=self._safe_int(usage.get("total_tokens")),
                elapsed_ms=elapsed_ms,
                retry_count=attempt,
            )
        raise ExternalProviderError(f"外部模型{stage}请求失败")

    def _validate_facts(
        self, payload: dict[str, Any], request: TextRequest
    ) -> list[FactField]:
        raw_fields = payload.get("fields")
        raw_field_count = len(raw_fields) if isinstance(raw_fields, list) else 0
        reasons: dict[str, int] = {}
        if payload.get("prompt_version") != self.settings.prompt_version:
            reasons["prompt_version_mismatch"] = 1
            self._log_fact_audit(raw_field_count, raw_field_count, 0, 0, reasons)
            raise ExternalProviderError(
                "外部模型事实提取结果不符合 JSON Schema"
            )
        if not isinstance(raw_fields, list):
            reasons["fields_not_array"] = 1
            self._log_fact_audit(raw_field_count, 0, 0, 0, reasons)
            raise ExternalProviderError(
                "外部模型事实提取结果不符合 JSON Schema"
            )

        schema_valid: list[FactField] = []
        for item in raw_fields:
            try:
                schema_valid.append(FactField.model_validate(item))
            except ValidationError:
                reasons["schema_invalid"] = reasons.get("schema_invalid", 0) + 1

        segments = request.segments or [
            SourceSegment(segment_id=1, page_no=1, text=request.text)
        ]
        by_id = {segment.segment_id: segment for segment in segments}
        trace_valid: list[FactField] = []
        for field in schema_valid:
            segment = by_id.get(field.segment_id)
            if segment is None:
                reasons["segment_not_found"] = reasons.get("segment_not_found", 0) + 1
                continue
            if segment.page_no != field.page_no:
                reasons["page_mismatch"] = reasons.get("page_mismatch", 0) + 1
                continue
            source_quote = self._locate_source_quote(
                segment.text, field.source_quote
            )
            if source_quote is None:
                reasons["source_quote_not_found"] = (
                    reasons.get("source_quote_not_found", 0) + 1
                )
                continue
            value_numbers = self._number_tokens(field.value)
            quote_numbers = self._number_tokens(source_quote)
            if any(
                quote_numbers[number] < count
                for number, count in value_numbers.items()
            ):
                reasons["numeric_mismatch"] = reasons.get("numeric_mismatch", 0) + 1
                continue
            value = field.value
            if (
                field.field_type
                in {"START_DATE", "END_DATE", "EVENT_DATE", "SERVICE_TIME"}
                and any(
                    value_numbers[number] < count
                    for number, count in quote_numbers.items()
                )
            ):
                value = source_quote
            trace_valid.append(
                field.model_copy(
                    update={"value": value, "source_quote": source_quote}
                )
            )

        self._log_fact_audit(
            raw_field_count,
            len(raw_fields),
            len(schema_valid),
            len(trace_valid),
            reasons,
        )
        if not trace_valid:
            raise ExternalProviderError("模型未生成可追溯的关键字段")
        return trace_valid

    def _validate_rewrite(
        self, payload: dict[str, Any]
    ) -> RewriteResponse:
        try:
            response = RewriteResponse.model_validate(payload)
        except ValidationError as exc:
            raise ExternalProviderError(
                "外部模型适老化结果不符合 JSON Schema"
            ) from exc
        self._check_prompt_version(response.prompt_version)
        return response

    def _complete_explicit_temporal_facts(
        self, facts: list[FactField], request: TextRequest
    ) -> list[FactField]:
        completed = list(facts)
        segments = request.segments or [
            SourceSegment(segment_id=1, page_no=1, text=request.text)
        ]
        date_token = r"(?:\d{4}年)?\d{1,2}月\d{1,2}日"
        adjustment_pattern = re.compile(
            rf"原预约日期为\s*(?P<from>{date_token})\s*的[，,]?\s*"
            rf"(?:顺延|调整|改期|变更)(?:至|为)\s*(?P<to>{date_token})"
        )
        deadline_pattern = re.compile(
            rf"(?P<value>{date_token}\s*\d{{1,2}}:\d{{2}}\s*"
            r"(?:以前|之前|前|截止))"
        )

        for segment in segments:
            for match in adjustment_pattern.finditer(segment.text):
                original_date = match.group("from")
                adjusted_date = match.group("to")
                if self._has_temporal_pair(
                    completed, "EVENT_DATE", original_date, adjusted_date
                ):
                    continue
                completed.append(
                    FactField(
                        field_type="EVENT_DATE",
                        label="原预约与调整日期",
                        value=f"{original_date} → {adjusted_date}",
                        source_quote=match.group(0),
                        page_no=segment.page_no,
                        segment_id=segment.segment_id,
                        confidence=0.99,
                    )
                )

            for match in deadline_pattern.finditer(segment.text):
                value = re.sub(r"\s+", "", match.group("value"))
                if self._has_temporal_value(completed, "END_DATE", value):
                    continue
                completed.append(
                    FactField(
                        field_type="END_DATE",
                        label="确认截止",
                        value=value,
                        source_quote=match.group("value"),
                        page_no=segment.page_no,
                        segment_id=segment.segment_id,
                        confidence=0.99,
                    )
                )
        return completed

    def _validate_sessions(
        self, payload: Any, request: TextRequest
    ) -> list[ServiceSession]:
        if payload is None:
            return []
        if not isinstance(payload, list):
            return []
        segments = request.segments or [
            SourceSegment(segment_id=1, page_no=1, text=request.text)
        ]
        by_id = {segment.segment_id: segment for segment in segments}
        valid: list[ServiceSession] = []
        for item in payload:
            try:
                session = ServiceSession.model_validate(item)
            except ValidationError:
                continue
            segment = by_id.get(session.segment_id)
            if segment is None or segment.page_no != session.page_no:
                continue
            quote = self._locate_source_quote(
                segment.text, session.source_quote
            )
            if quote is None or not all(
                re.sub(r"\s+", "", value)
                in re.sub(r"\s+", "", quote)
                for value in (session.date, session.time, session.location)
            ):
                continue
            valid.append(
                session.model_copy(update={"source_quote": quote})
            )
        return valid

    def _complete_explicit_sessions(
        self, sessions: list[ServiceSession], request: TextRequest
    ) -> list[ServiceSession]:
        completed = list(sessions)
        segments = request.segments or [
            SourceSegment(segment_id=1, page_no=1, text=request.text)
        ]
        pattern = re.compile(
            r"(?P<date>\d{4}年\d{1,2}月\d{1,2}日)\s*"
            r"(?P<time>\d{2}:\d{2}\s*[-—至]\s*\d{2}:\d{2})\s*"
            r"(?P<location>[^\r\n。；]{2,80}(?:门诊|窗口|服务台|中心|地点))"
        )
        for segment in segments:
            for match in pattern.finditer(segment.text):
                date = re.sub(r"\s+", "", match.group("date"))
                time_value = re.sub(r"\s+", "", match.group("time")).replace("—", "-")
                location = match.group("location").strip()
                if any(
                    item.date == date
                    and item.time == time_value
                    and item.location == location
                    for item in completed
                ):
                    continue
                completed.append(
                    ServiceSession(
                        date=date,
                        time=time_value,
                        location=location,
                        source_quote=match.group(0),
                        page_no=segment.page_no,
                        segment_id=segment.segment_id,
                        needs_human_review=False,
                    )
                )
        return completed

    @staticmethod
    def _split_duplicate_audience_eligibility(
        fields: list[FactField],
    ) -> list[FactField]:
        audience = next(
            (field for field in fields if field.field_type == "TARGET_AUDIENCE"),
            None,
        )
        eligibility = next(
            (field for field in fields if field.field_type == "ELIGIBILITY"),
            None,
        )
        if (
            audience is None
            or eligibility is None
            or re.sub(r"[\s，,。；;]", "", audience.value)
            != re.sub(r"[\s，,。；;]", "", eligibility.value)
            or audience.source_quote != eligibility.source_quote
        ):
            return fields
        match = re.match(
            r"(?P<audience>.+?年满\d+周岁)[、，,](?P<eligibility>目前.+?)(?:者)?$",
            audience.value,
        )
        if not match:
            return fields
        return [
            field.model_copy(
                update={
                    "value": (
                        match.group("audience")
                        if field.field_type == "TARGET_AUDIENCE"
                        else match.group("eligibility").removesuffix("者")
                    )
                }
            )
            if field is audience or field is eligibility
            else field
            for field in fields
        ]

    @staticmethod
    def _rewrite_with_sessions(
        rewrite: RewriteResponse,
        fields: list[FactField],
        sessions: list[ServiceSession],
    ) -> RewriteResponse:
        if not sessions:
            return rewrite
        values: dict[str, str] = {}
        for field in fields:
            values.setdefault(field.field_type, field.value)
        audience = values.get("TARGET_AUDIENCE", "")
        eligibility = values.get("ELIGIBILITY", "")
        first = "，且".join(
            part for part in (audience, eligibility) if part
        )
        if first:
            first += "，可以登记。"
        schedule = "；".join(
            f"{session.date}接种时间为{session.time}"
            + (
                f"，地点为{session.location}"
                if len({item.location for item in sessions}) > 1
                else ""
            )
            for session in sessions
        ) + "。"
        material = values.get("MATERIAL", "")
        location = sessions[0].location
        contact = values.get("CONTACT", "")
        third_parts = []
        if material:
            third_parts.append(f"请带{material}")
        if location:
            third_parts.append(f"到{location}")
        if contact:
            third_parts.append(f"咨询电话{contact}")
        third = "；".join(third_parts) + "。"
        summary = [item for item in (first, schedule, third) if item]
        steps = [
            StepCard(
                order=index + 1,
                title=f"{session.date}场次",
                description=(
                    f"{session.date} {session.time}，"
                    f"地点：{session.location}。"
                ),
            )
            for index, session in enumerate(sessions)
        ]
        warnings = _deduplicate_warnings(rewrite.warnings)
        terms = dict(rewrite.term_explanations)
        if any("预防接种门诊" in session.location for session in sessions):
            terms["预防接种门诊"] = (
                "社区卫生服务机构中负责疫苗登记、接种前询问、"
                "健康检查和疫苗接种的服务区域。"
            )
        plain_text = "".join(summary)
        return rewrite.model_copy(
            update={
                "summary": summary,
                "plain_text": plain_text,
                "steps": steps,
                "warnings": warnings,
                "term_explanations": terms,
                "audio_script": plain_text + "".join(warnings),
            }
        )

    @staticmethod
    def _has_temporal_pair(
        fields: list[FactField],
        field_type: str,
        first: str,
        second: str,
    ) -> bool:
        return any(
            field.field_type == field_type
            and first in field.value
            and second in field.value
            for field in fields
        )

    @staticmethod
    def _has_temporal_value(
        fields: list[FactField], field_type: str, value: str
    ) -> bool:
        normalized_value = re.sub(r"\s+", "", value)
        return any(
            field.field_type == field_type
            and normalized_value in re.sub(r"\s+", "", field.value)
            for field in fields
        )

    def _check_prompt_version(self, actual: str) -> None:
        if actual != self.settings.prompt_version:
            raise ExternalProviderError(
                "外部模型返回的 prompt_version 与请求不一致"
            )

    def _backoff(self, attempt: int) -> None:
        self.sleep(min(0.2 * (2**attempt), 1.0))

    @staticmethod
    def _locate_source_quote(source: str, quote: str) -> str | None:
        if quote in source:
            return quote
        normalized_source: list[str] = []
        source_indexes: list[int] = []
        for index, character in enumerate(source):
            if not character.isspace():
                normalized_source.append(character)
                source_indexes.append(index)
        normalized_quote = "".join(
            character for character in quote if not character.isspace()
        )
        if not normalized_quote:
            return None
        start = "".join(normalized_source).find(normalized_quote)
        if start < 0:
            return None
        original_start = source_indexes[start]
        original_end = source_indexes[start + len(normalized_quote) - 1] + 1
        return source[original_start:original_end]

    @staticmethod
    def _number_tokens(value: str) -> Counter[str]:
        return Counter(re.findall(r"\d+", re.sub(r"\s+", "", value)))

    def _log_http_audit(
        self,
        stage: str,
        http_status: int,
        request_id: str,
        finish_reason: str,
        elapsed_ms: int,
        retry_count: int,
        usage: dict[str, Any] | None = None,
    ) -> None:
        usage = usage or {}
        LOGGER.info(
            "provider=external model=%s prompt_version=%s stage=%s "
            "http_status=%s request_id=%s finish_reason=%s "
            "prompt_tokens=%s completion_tokens=%s total_tokens=%s "
            "elapsed_ms=%s retry_count=%s",
            self.settings.model,
            self.settings.prompt_version,
            stage,
            http_status,
            request_id or "none",
            finish_reason,
            self._safe_int(usage.get("prompt_tokens")),
            self._safe_int(usage.get("completion_tokens")),
            self._safe_int(usage.get("total_tokens")),
            elapsed_ms,
            retry_count,
        )

    def _log_fact_audit(
        self,
        raw_field_count: int,
        parsed_field_count: int,
        schema_valid_field_count: int,
        trace_valid_field_count: int,
        reasons: dict[str, int],
    ) -> None:
        rejected_field_count = max(
            parsed_field_count - trace_valid_field_count,
            sum(reasons.values()),
        )
        rejected_reason = ",".join(
            f"{reason}:{count}" for reason, count in sorted(reasons.items())
        ) or "none"
        LOGGER.info(
            "provider=external model=%s prompt_version=%s stage=fact_validation "
            "raw_field_count=%s parsed_field_count=%s "
            "schema_valid_field_count=%s trace_valid_field_count=%s "
            "rejected_field_count=%s rejected_reason=%s",
            self.settings.model,
            self.settings.prompt_version,
            raw_field_count,
            parsed_field_count,
            schema_valid_field_count,
            trace_valid_field_count,
            rejected_field_count,
            rejected_reason,
        )

    @staticmethod
    def _safe_identifier(value: str) -> str:
        sanitized = re.sub(r"[^A-Za-z0-9._:-]", "_", value)[:96]
        return sanitized or "none"

    @staticmethod
    def _safe_int(value: Any) -> int:
        try:
            return int(value)
        except (TypeError, ValueError):
            return 0

    @staticmethod
    def _elapsed_ms(started: float) -> int:
        return max(0, round((time.perf_counter() - started) * 1000))
