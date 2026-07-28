import asyncio
import hashlib
import json
import logging
import os
import re
import time
import threading
from collections import Counter
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

import httpx
from pydantic import ValidationError

from app.models import (
    AnalyzeResult,
    Amendment,
    AudienceItem,
    AudienceRules,
    ClosureRule,
    ConditionalMaterial,
    DeadlineRule,
    ExtractedField,
    FactExtractionResponse,
    FactField,
    FeeRule,
    MetadataPreview,
    ProcessingMetrics,
    ResultDelivery,
    RewriteResponse,
    ServiceSchedule,
    ServiceSession,
    ServiceWindow,
    SourceSegment,
    StepCard,
    TextRequest,
)
from app.prompts import (
    guide_extract_v1,
    guide_extract_v1_1,
    guide_rewrite_v1,
    guide_rewrite_v1_1,
    metadata_extract_v1,
    web_article_extract_v1,
    web_article_rewrite_v1,
)
from app.providers.base import LlmProvider


LOGGER = logging.getLogger("uvicorn.error")
SCHEMA_VERSION = "1.1"
_RESULT_CACHE: dict[str, AnalyzeResult] = {}
_ASYNC_LOOP = asyncio.new_event_loop()
_ASYNC_THREAD = threading.Thread(target=_ASYNC_LOOP.run_forever, daemon=True)
_ASYNC_THREAD.start()


class _AsyncClientAdapter:
    """Sync provider bridge backed by one process-level AsyncClient event loop."""

    def __init__(self, timeout: float) -> None:
        async def create() -> httpx.AsyncClient:
            return httpx.AsyncClient(
                timeout=timeout,
                limits=httpx.Limits(
                    max_connections=20,
                    max_keepalive_connections=10,
                    keepalive_expiry=30,
                ),
            )

        self.client = asyncio.run_coroutine_threadsafe(create(), _ASYNC_LOOP).result()

    @property
    def is_closed(self) -> bool:
        return self.client.is_closed

    def post(self, *args: Any, **kwargs: Any) -> httpx.Response:
        return asyncio.run_coroutine_threadsafe(
            self.client.post(*args, **kwargs), _ASYNC_LOOP
        ).result()


_SHARED_CLIENTS: dict[tuple[str, float], _AsyncClientAdapter] = {}


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
        if prompt_version not in {"v1", "v1.1"}:
            raise ExternalProviderError(
                "外部模型配置错误：JIANDA_PROMPT_VERSION 仅支持 v1 或 v1.1"
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

    def _shared_client(self) -> _AsyncClientAdapter:
        key = (self.settings.base_url, self.settings.timeout_seconds)
        client = _SHARED_CLIENTS.get(key)
        if client is None or client.is_closed:
            client = _AsyncClientAdapter(self.settings.timeout_seconds)
            _SHARED_CLIENTS[key] = client
        return client

    def _cache_key(self, request: TextRequest) -> str:
        content_hash = request.content_sha256 or hashlib.sha256(
            request.text.encode("utf-8")
        ).hexdigest()
        value = "|".join(
            [
                content_hash,
                self.settings.model,
                self._prompt_version(request),
                SCHEMA_VERSION,
                request.document_type,
                request.content_kind or "",
            ]
        )
        return hashlib.sha256(value.encode("utf-8")).hexdigest()

    def _dynamic_fact_max_tokens(self, request: TextRequest) -> int:
        if self.settings.prompt_version == "v1.1":
            # v1.1 returns both the compatible flat fields and the richer
            # public-service structures. Short, information-dense notices can
            # therefore need substantially more output than their input size
            # suggests. Keep the deployment-wide cap, but avoid truncating
            # valid structured JSON merely because the source document is short.
            return min(self.settings.max_tokens, max(4200, len(request.text) * 5))
        return min(self.settings.max_tokens, max(1800, len(request.text) * 3))

    def _dynamic_rewrite_max_tokens(self, request: TextRequest) -> int:
        return min(self.settings.max_tokens, max(1400, len(request.text) * 2))

    def analyze(self, request: TextRequest) -> AnalyzeResult:
        total_started = time.perf_counter()
        active_prompt_version = self._prompt_version(request)
        cache_key = self._cache_key(request) if request.content_sha256 else ""
        if cache_key and cache_key in _RESULT_CACHE:
            cached = _RESULT_CACHE[cache_key].model_copy(deep=True)
            cached.metrics = cached.metrics.model_copy(
                update={
                    "cache_hit": True,
                    "fact_extract_ms": 0,
                    "trace_validation_ms": 0,
                    "accessible_rewrite_ms": 0,
                    "total_ms": self._elapsed_ms(total_started),
                    "prompt_tokens": 0,
                    "completion_tokens": 0,
                    "total_tokens": 0,
                }
            )
            self._log_business_audit(request, cached.metrics)
            return cached

        active_client = self.client or self._shared_client()
        is_web_article = request.document_type == "public_news"
        is_service_notice = (
            is_web_article and request.content_kind == "SERVICE_NOTICE"
        )
        use_news_prompts = is_web_article and not is_service_notice
        fact_prompt = (
            web_article_extract_v1
            if use_news_prompts
            else (
                guide_extract_v1_1
                if self.settings.prompt_version == "v1.1" or is_service_notice
                else guide_extract_v1
            )
        )
        rewrite_prompt = (
            web_article_rewrite_v1
            if use_news_prompts
            else (
                guide_rewrite_v1_1
                if self.settings.prompt_version == "v1.1" or is_service_notice
                else guide_rewrite_v1
            )
        )
        LOGGER.info(
            "external_llm stage=fact_extract prompt_version=%s",
            self.settings.prompt_version,
        )
        fact_result = self._completion(
            active_client,
            [
                {"role": "system", "content": fact_prompt.SYSTEM_PROMPT},
                {
                    "role": "user",
                    "content": fact_prompt.build_task_prompt(
                        request, active_prompt_version
                    ),
                },
            ],
            stage="fact_extract",
            max_tokens=self._dynamic_fact_max_tokens(request),
        )
        trace_started = time.perf_counter()
        model_facts = self._validate_facts(
            fact_result.payload, request, active_prompt_version
        )
        facts = self._complete_explicit_temporal_facts(model_facts, request)
        facts = self._split_duplicate_audience_eligibility(facts)
        model_sessions = self._validate_sessions(
            fact_result.payload.get("sessions"), request
        )
        sessions = self._complete_explicit_sessions(model_sessions, request)
        structured = self._validate_structured(
            fact_result.payload, model_facts, sessions, request
        )
        structured = self._complete_common_structures(structured, request)
        trace_validation_ms = self._elapsed_ms(trace_started)
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
        rewrite_user = (
            rewrite_prompt.build_task_prompt(
                request, facts, structured, active_prompt_version
            )
            if (
                self.settings.prompt_version == "v1.1"
                or is_service_notice
                or use_news_prompts
            )
            else rewrite_prompt.build_task_prompt(
                request, facts, sessions, active_prompt_version
            )
        )
        rewrite_result = self._completion(
            active_client,
            [
                {"role": "system", "content": rewrite_prompt.SYSTEM_PROMPT},
                {"role": "user", "content": rewrite_user},
            ],
            stage="accessible_rewrite",
            max_tokens=self._dynamic_rewrite_max_tokens(request),
        )
        rewrite = self._validate_rewrite(
            rewrite_result.payload, request, active_prompt_version
        )
        rewrite = self._rewrite_with_sessions(rewrite, facts, sessions)
        metrics = ProcessingMetrics(
            schema_version=SCHEMA_VERSION,
            cache_hit=False,
            fact_extract_ms=fact_result.elapsed_ms,
            trace_validation_ms=trace_validation_ms,
            accessible_rewrite_ms=rewrite_result.elapsed_ms,
            total_ms=self._elapsed_ms(total_started),
            prompt_tokens=fact_result.prompt_tokens + rewrite_result.prompt_tokens,
            completion_tokens=fact_result.completion_tokens
            + rewrite_result.completion_tokens,
            total_tokens=fact_result.total_tokens + rewrite_result.total_tokens,
            source_char_count=len(request.text),
            accessible_char_count=len(
                rewrite.plain_text + "".join(rewrite.quick_summary or rewrite.summary)
            ),
            summary_compression_ratio=round(
                len("".join(rewrite.quick_summary or rewrite.summary))
                / max(1, len(request.text)),
                4,
            ),
            key_fact_count=len(rewrite.key_facts),
            action_item_count=len(rewrite.action_checklist),
            trace_pass_rate=1.0 if (
                rewrite.key_facts or rewrite.action_checklist or rewrite.faq
            ) else 0.0,
            markdown_residue_count=len(
                re.findall(r"#{1,6}\s|\*\*|```|<[^>]+>", rewrite.plain_text)
            ),
        )
        result = AnalyzeResult(
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
            audience_rules=structured.audience_rules,
            service_schedule=structured.service_schedule,
            conditional_materials=structured.conditional_materials,
            fees=structured.fees,
            result_delivery=structured.result_delivery,
            deadline_rules=structured.deadline_rules,
            amendments=structured.amendments,
            quick_summary=rewrite.quick_summary or rewrite.summary,
            why_it_matters=rewrite.why_it_matters,
            action_checklist=rewrite.action_checklist,
            key_facts=rewrite.key_facts,
            common_mistakes=rewrite.common_mistakes,
            faq=rewrite.faq,
            scope=rewrite.scope,
            uncertainties=rewrite.uncertainties,
            metrics=metrics,
        )
        if cache_key:
            _RESULT_CACHE[cache_key] = result.model_copy(deep=True)
        self._log_business_audit(request, metrics)
        if request.document_type == "public_news":
            LOGGER.info(
                "provider=external model=%s prompt_version=%s content_kind=%s "
                "http_status=200 schema_valid=true trace_valid=true "
                "rejected_field_count=0 document_id=%s processing_job_id=%s "
                "prompt_tokens=%s completion_tokens=%s total_tokens=%s elapsed_ms=%s",
                self.settings.model,
                active_prompt_version,
                request.content_kind or "GENERAL_NEWS",
                request.document_id or 0,
                request.processing_job_id or 0,
                metrics.prompt_tokens,
                metrics.completion_tokens,
                metrics.total_tokens,
                metrics.total_ms,
            )
        return result

    def preview_metadata(
        self,
        text: str,
        filename: str,
        deterministic: MetadataPreview,
    ) -> MetadataPreview:
        active_client = self.client or self._shared_client()
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
        self,
        payload: dict[str, Any],
        request: TextRequest,
        prompt_version: str | None = None,
    ) -> list[FactField]:
        raw_fields = payload.get("fields")
        raw_field_count = len(raw_fields) if isinstance(raw_fields, list) else 0
        reasons: dict[str, int] = {}
        if payload.get("prompt_version") != (
            prompt_version or self.settings.prompt_version
        ):
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
            safe_reasons = ",".join(
                f"{name}={count}" for name, count in sorted(reasons.items())
            ) or "unknown=1"
            raise ExternalProviderError(
                f"模型未生成可追溯的关键字段（{safe_reasons}）"
            )
        return trace_valid

    def _validate_rewrite(
        self,
        payload: dict[str, Any],
        request: TextRequest | None = None,
        prompt_version: str | None = None,
    ) -> RewriteResponse:
        try:
            response = RewriteResponse.model_validate(payload)
        except ValidationError as exc:
            raise ExternalProviderError(
                "外部模型适老化结果不符合 JSON Schema"
            ) from exc
        self._check_prompt_version(response.prompt_version, prompt_version)
        if request is None:
            return response
        return response.model_copy(update={
            "action_checklist": self._validate_rewrite_traces(
                response.action_checklist, request
            ),
            "key_facts": self._validate_rewrite_traces(
                response.key_facts, request
            ),
            "faq": self._validate_rewrite_traces(response.faq, request),
        })

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

    def _validate_structured(
        self,
        payload: dict[str, Any],
        facts: list[FactField],
        sessions: list[ServiceSession],
        request: TextRequest,
    ) -> FactExtractionResponse:
        if self.settings.prompt_version == "v1":
            return FactExtractionResponse(
                prompt_version="v1", fields=facts, sessions=sessions
            )

        audience_payload = payload.get("audience_rules")
        audience_payload = (
            audience_payload if isinstance(audience_payload, dict) else {}
        )
        schedule_payload = payload.get("service_schedule")
        schedule_payload = (
            schedule_payload if isinstance(schedule_payload, dict) else {}
        )
        raw_groups = (
            (audience_payload.get("audience"), AudienceItem),
            (audience_payload.get("conditions"), AudienceItem),
            (schedule_payload.get("service_windows"), ServiceWindow),
            (schedule_payload.get("closure_rules"), ClosureRule),
            (payload.get("conditional_materials"), ConditionalMaterial),
            (payload.get("fees"), FeeRule),
            (payload.get("result_delivery"), ResultDelivery),
            (payload.get("deadline_rules"), DeadlineRule),
            (payload.get("amendments"), Amendment),
        )
        validated_groups = [
            self._validate_structured_items(raw, model, request)
            for raw, model in raw_groups
        ]
        raw_count = sum(
            len(raw) for raw, _ in raw_groups if isinstance(raw, list)
        )
        valid_count = sum(len(items) for items in validated_groups)
        LOGGER.info(
            "provider=external prompt_version=%s stage=structured_validation "
            "raw_count=%s valid_count=%s rejected_count=%s",
            self.settings.prompt_version,
            raw_count,
            valid_count,
            raw_count - valid_count,
        )
        if raw_count and not valid_count:
            raise ExternalProviderError(
                "外部模型通用结构结果不符合 JSON Schema"
            )

        (
            audiences,
            conditions,
            service_windows,
            closure_rules,
            conditional_materials,
            fees,
            result_delivery,
            deadline_rules,
            amendments,
        ) = validated_groups
        return FactExtractionResponse(
            prompt_version=self.settings.prompt_version,
            fields=facts,
            sessions=sessions,
            audience_rules=AudienceRules(
                audience=audiences, conditions=conditions
            ),
            service_schedule=ServiceSchedule(
                service_windows=service_windows,
                closure_rules=closure_rules,
            ),
            conditional_materials=conditional_materials,
            fees=fees,
            result_delivery=result_delivery,
            deadline_rules=deadline_rules,
            amendments=amendments,
        )

    def _validate_structured_items(
        self,
        raw_items: Any,
        model: type[Any],
        request: TextRequest,
    ) -> list[Any]:
        if not isinstance(raw_items, list):
            return []
        valid: list[Any] = []
        list_fields = {
            "days",
            "dates",
            "time_ranges",
            "required",
            "optional",
            "payment_methods",
            "supersedes",
        }
        for raw in raw_items:
            if not isinstance(raw, dict):
                continue
            candidate = {
                key: value
                for key, value in raw.items()
                if key in model.model_fields
            }
            for name in list_fields.intersection(candidate):
                if isinstance(candidate[name], str):
                    candidate[name] = [candidate[name]]
            if model is FeeRule and isinstance(
                candidate.get("amount"), (int, float)
            ):
                candidate["amount"] = str(candidate["amount"])
            try:
                item = model.model_validate(candidate)
                valid.append(self._validated_trace_item(item, request))
            except (ValidationError, ExternalProviderError):
                continue
        return valid

    def _validated_trace_item(self, item: Any, request: TextRequest) -> Any:
        segments = request.segments or [
            SourceSegment(segment_id=1, page_no=1, text=request.text)
        ]
        segment = next(
            (entry for entry in segments if entry.segment_id == item.segment_id),
            None,
        )
        if segment is None or segment.page_no != item.page_no:
            raise ExternalProviderError("结构化事实的页码或段落无法追溯")
        quote = self._locate_source_quote(segment.text, item.source_quote)
        if quote is None:
            raise ExternalProviderError("结构化事实的原文引用无法追溯")
        payload = item.model_dump(
            exclude={
                "source_quote",
                "page_no",
                "segment_id",
                "needs_human_review",
            }
        )
        value_numbers = self._number_tokens(
            json.dumps(payload, ensure_ascii=False)
        )
        quote_numbers = self._number_tokens(quote)
        if any(quote_numbers[number] < count for number, count in value_numbers.items()):
            raise ExternalProviderError("结构化事实中的数字与原文不一致")
        return item.model_copy(update={"source_quote": quote})

    def _complete_common_structures(
        self,
        structured: FactExtractionResponse,
        request: TextRequest,
    ) -> FactExtractionResponse:
        """Complete explicit tables and fee clauses without inventing facts."""
        windows = list(structured.service_schedule.service_windows)
        fees = list(structured.fees)
        segments = request.segments or [
            SourceSegment(segment_id=1, page_no=1, text=request.text)
        ]
        day = (
            r"(?:周|星期)[一二三四五六日天]"
            r"(?:[、，,](?:周|星期)[一二三四五六日天])*"
        )
        time_range = r"\d{1,2}:\d{2}\s*[-—至]\s*\d{1,2}:\d{2}"
        window_pattern = re.compile(
            rf"(?P<quote>(?P<days>{day})\s*\r?\n\s*"
            rf"(?P<first>{time_range})\s*\r?\n\s*"
            rf"(?P<second>{time_range}|不开放))"
        )
        fee_paragraph_pattern = re.compile(
            r"(?P<quote>(?P<body>[^\r\n。]{1,160}"
            r"(?:\d+(?:\.\d+)?元|按[^；。\r\n]{1,50}收取)"
            r"[^\r\n。]*)(?:。(?P<payment>支持[^。\r\n]+支付))?。?)"
        )
        for segment in segments:
            for match in window_pattern.finditer(segment.text):
                days = re.split(r"[、，,]", match.group("days"))
                first = self._normalize_time_range(match.group("first"))
                second = match.group("second")
                ranges = [first]
                unavailable_note = None
                if second == "不开放":
                    unavailable_note = "下午不开放"
                else:
                    ranges.append(self._normalize_time_range(second))
                if any(
                    item.days == days
                    and item.time_ranges == ranges
                    and item.unavailable_note == unavailable_note
                    for item in windows
                ):
                    continue
                windows.append(
                    ServiceWindow(
                        days=days,
                        dates=[],
                        time_ranges=ranges,
                        location=None,
                        unavailable_note=unavailable_note,
                        source_quote=match.group("quote"),
                        page_no=segment.page_no,
                        segment_id=segment.segment_id,
                        needs_human_review=False,
                    )
                )

            for match in fee_paragraph_pattern.finditer(segment.text):
                body = match.group("body")
                payment_text = match.group("payment") or ""
                payment_methods = [
                    item.strip()
                    for item in re.split(
                        r"[、，,]|及",
                        payment_text.removeprefix("支持"),
                    )
                    if item.strip()
                ]
                for clause in re.split(r"[；;]", body):
                    amount_match = re.fullmatch(
                        r"\s*(?P<type>.+?)"
                        r"(?P<amount>每(?:证|次|人)?\s*\d+(?:\.\d+)?元"
                        r"|\d+(?:\.\d+)?元(?:/[^；，。]+)?)\s*",
                        clause,
                    )
                    rule_match = re.fullmatch(
                        r"\s*(?P<type>.+?)按(?P<rule>.+?收取)\s*",
                        clause,
                    )
                    if amount_match:
                        fee_type = amount_match.group("type").strip()
                        amount = amount_match.group("amount").replace(" ", "")
                        rule = None
                    elif rule_match:
                        fee_type = rule_match.group("type").strip()
                        amount = None
                        rule = f"按{rule_match.group('rule').strip()}"
                    else:
                        continue
                    if any(item.fee_type == fee_type for item in fees):
                        continue
                    fees.append(
                        FeeRule(
                            fee_type=fee_type,
                            amount=amount,
                            rule=rule,
                            payment_methods=payment_methods,
                            source_quote=match.group("quote"),
                            page_no=segment.page_no,
                            segment_id=segment.segment_id,
                            needs_human_review=False,
                        )
                    )

        return structured.model_copy(
            update={
                "service_schedule": structured.service_schedule.model_copy(
                    update={"service_windows": self._merge_service_windows(windows)}
                ),
                "fees": self._merge_fees(fees),
            }
        )

    @staticmethod
    def _merge_service_windows(
        windows: list[ServiceWindow],
    ) -> list[ServiceWindow]:
        merged: dict[tuple[tuple[str, ...], tuple[str, ...], tuple[str, ...]], ServiceWindow] = {}
        for item in windows:
            key = (
                tuple(sorted(item.days)),
                tuple(sorted(item.dates)),
                tuple(item.time_ranges),
            )
            current = merged.get(key)
            if current is None:
                merged[key] = item
                continue
            unavailable = max(
                (value for value in (current.unavailable_note, item.unavailable_note) if value),
                key=len,
                default=None,
            )
            quote = max((current.source_quote, item.source_quote), key=len)
            merged[key] = current.model_copy(
                update={
                    "location": current.location or item.location,
                    "unavailable_note": unavailable,
                    "source_quote": quote,
                    "needs_human_review": (
                        current.needs_human_review or item.needs_human_review
                    ),
                }
            )
        return list(merged.values())

    @staticmethod
    def _merge_fees(fees: list[FeeRule]) -> list[FeeRule]:
        merged: dict[str, FeeRule] = {}
        for item in fees:
            key = re.sub(
                r"(?:工本费|服务费|费用|费)$",
                "",
                re.sub(r"\s+", "", item.fee_type),
            )
            current = merged.get(key)
            if current is None:
                merged[key] = item
                continue
            amount = max(
                (value for value in (current.amount, item.amount) if value),
                key=lambda value: (
                    "元" in value,
                    "每" in value or "/" in value,
                    len(value),
                ),
                default=None,
            )
            rule = max(
                (value for value in (current.rule, item.rule) if value),
                key=len,
                default=None,
            )
            methods = list(
                dict.fromkeys(current.payment_methods + item.payment_methods)
            )
            merged[key] = current.model_copy(
                update={
                    "fee_type": min(
                        (current.fee_type, item.fee_type), key=len
                    ),
                    "amount": amount,
                    "rule": rule,
                    "payment_methods": methods,
                    "source_quote": max(
                        (current.source_quote, item.source_quote), key=len
                    ),
                    "needs_human_review": (
                        current.needs_human_review or item.needs_human_review
                    ),
                }
            )
        return list(merged.values())

    @staticmethod
    def _normalize_time_range(value: str) -> str:
        return re.sub(r"\s+", "", value).replace("—", "-").replace("至", "-")

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

    def _check_prompt_version(
        self, actual: str, expected: str | None = None
    ) -> None:
        if actual != (expected or self.settings.prompt_version):
            raise ExternalProviderError(
                "外部模型返回的 prompt_version 与请求不一致"
            )

    def _prompt_version(self, request: TextRequest) -> str:
        return request.prompt_version or self.settings.prompt_version

    def _validate_rewrite_traces(
        self, items: list[Any], request: TextRequest
    ) -> list[Any]:
        segments = request.segments or [
            SourceSegment(segment_id=1, page_no=1, text=request.text)
        ]
        by_id = {segment.segment_id: segment for segment in segments}
        valid: list[Any] = []
        for item in items:
            segment_id = getattr(item, "segment_id", None)
            segment = by_id.get(segment_id) if segment_id is not None else next(
                (
                    candidate
                    for candidate in segments
                    if self._locate_source_quote(
                        candidate.text, item.source_quote
                    ) is not None
                ),
                None,
            )
            if segment is None:
                continue
            quote = self._locate_source_quote(segment.text, item.source_quote)
            if quote is not None:
                valid.append(item.model_copy(update={"source_quote": quote}))
        return valid

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

    def _log_business_audit(
        self, request: TextRequest, metrics: ProcessingMetrics
    ) -> None:
        LOGGER.info(
            "provider=external model=%s prompt_version=%s schema_version=%s "
            "document_id=%s processing_job_id=%s trace_id=%s cache_hit=%s "
            "text_extract_ms=%s fact_extract_ms=%s trace_validation_ms=%s "
            "accessible_rewrite_ms=%s persistence_ms=%s total_ms=%s "
            "prompt_tokens=%s completion_tokens=%s total_tokens=%s",
            self.settings.model,
            self.settings.prompt_version,
            metrics.schema_version,
            request.document_id or 0,
            request.processing_job_id or 0,
            self._safe_identifier(request.trace_id),
            str(metrics.cache_hit).lower(),
            metrics.text_extract_ms,
            metrics.fact_extract_ms,
            metrics.trace_validation_ms,
            metrics.accessible_rewrite_ms,
            metrics.persistence_ms,
            metrics.total_ms,
            metrics.prompt_tokens,
            metrics.completion_tokens,
            metrics.total_tokens,
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
