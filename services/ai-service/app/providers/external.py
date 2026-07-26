import json
import logging
import os
import time
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

import httpx
from pydantic import ValidationError

from app.models import (
    AnalyzeResult,
    ExtractedField,
    FactExtractionResponse,
    FactField,
    RewriteResponse,
    SourceSegment,
    TextRequest,
)
from app.prompts import guide_extract_v1, guide_rewrite_v1
from app.providers.base import LlmProvider


LOGGER = logging.getLogger(__name__)


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
            fact_json = self._completion(
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
            facts = self._validate_facts(fact_json, request)

            LOGGER.info(
                "external_llm stage=accessible_rewrite prompt_version=%s",
                self.settings.prompt_version,
            )
            rewrite_json = self._completion(
                active_client,
                [
                    {"role": "system", "content": guide_rewrite_v1.SYSTEM_PROMPT},
                    {
                        "role": "user",
                        "content": guide_rewrite_v1.build_task_prompt(
                            request, facts, self.settings.prompt_version
                        ),
                    },
                ],
                stage="accessible_rewrite",
            )
            rewrite = self._validate_rewrite(rewrite_json)
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

    def _completion(
        self,
        client: httpx.Client,
        messages: list[dict[str, str]],
        stage: str,
    ) -> dict[str, Any]:
        request_body = {
            "model": self.settings.model,
            "messages": messages,
            "stream": False,
            "response_format": {"type": "json_object"},
            "max_tokens": self.settings.max_tokens,
            "thinking": {"type": self.settings.thinking},
        }
        attempts = self.settings.max_retries + 1
        for attempt in range(attempts):
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
                if attempt + 1 < attempts:
                    self._backoff(attempt)
                    continue
                raise ExternalProviderError(
                    f"外部模型{stage}请求超时或连接失败"
                ) from exc

            if response.status_code in (401, 403):
                raise ExternalProviderError(
                    f"外部模型鉴权失败（HTTP {response.status_code}）"
                )
            if response.status_code == 429 or response.status_code >= 500:
                if attempt + 1 < attempts:
                    self._backoff(attempt)
                    continue
                raise ExternalProviderError(
                    f"外部模型暂时不可用（HTTP {response.status_code}）"
                )
            if not 200 <= response.status_code < 300:
                raise ExternalProviderError(
                    f"外部模型请求失败（HTTP {response.status_code}）"
                )
            try:
                envelope = response.json()
            except ValueError as exc:
                raise ExternalProviderError(
                    f"外部模型{stage}响应不是合法 JSON"
                ) from exc
            if not isinstance(envelope, dict):
                raise ExternalProviderError(
                    f"外部模型{stage}响应必须是 JSON 对象"
                )
            choices = envelope.get("choices")
            if not isinstance(choices, list) or not choices:
                raise ExternalProviderError(
                    f"外部模型{stage}响应缺少 choices"
                )
            choice = choices[0]
            if not isinstance(choice, dict):
                raise ExternalProviderError(
                    f"外部模型{stage}响应 choices 格式错误"
                )
            if choice.get("finish_reason") == "length":
                raise ExternalProviderError(
                    f"外部模型{stage}输出因长度限制被截断"
                )
            message = choice.get("message")
            if not isinstance(message, dict):
                raise ExternalProviderError(
                    f"外部模型{stage}响应缺少 message"
                )
            content = message.get("content")
            if not isinstance(content, str) or not content.strip():
                if attempt + 1 < attempts:
                    self._backoff(attempt)
                    continue
                raise ExternalProviderError(
                    f"外部模型{stage}返回空 content"
                )
            try:
                parsed = json.loads(content)
            except json.JSONDecodeError as exc:
                raise ExternalProviderError(
                    f"外部模型{stage}content 不是合法 JSON"
                ) from exc
            if not isinstance(parsed, dict):
                raise ExternalProviderError(
                    f"外部模型{stage}content 必须是 JSON 对象"
                )
            return parsed
        raise ExternalProviderError(f"外部模型{stage}请求失败")

    def _validate_facts(
        self, payload: dict[str, Any], request: TextRequest
    ) -> list[FactField]:
        try:
            response = FactExtractionResponse.model_validate(payload)
        except ValidationError as exc:
            raise ExternalProviderError(
                "外部模型事实提取结果不符合 JSON Schema"
            ) from exc
        self._check_prompt_version(response.prompt_version)
        segments = request.segments or [
            SourceSegment(segment_id=1, page_no=1, text=request.text)
        ]
        by_id = {segment.segment_id: segment for segment in segments}
        for field in response.fields:
            segment = by_id.get(field.segment_id)
            if (
                segment is None
                or segment.page_no != field.page_no
                or field.source_quote not in segment.text
            ):
                raise ExternalProviderError(
                    "外部模型字段原文引用无法追溯，结果已拒绝"
                )
        return response.fields

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

    def _check_prompt_version(self, actual: str) -> None:
        if actual != self.settings.prompt_version:
            raise ExternalProviderError(
                "外部模型返回的 prompt_version 与请求不一致"
            )

    def _backoff(self, attempt: int) -> None:
        self.sleep(min(0.2 * (2**attempt), 1.0))
