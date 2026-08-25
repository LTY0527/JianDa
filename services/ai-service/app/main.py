import os
import logging
import shutil
import subprocess
import tempfile
from pathlib import Path

import httpx
from fastapi import FastAPI, File, HTTPException, Query, UploadFile, Response

from app.extraction import (
    ALLOWED_SUFFIXES,
    OCR_LANGUAGE,
    OcrUnavailableError,
    extract_file,
    render_pdf_first_page,
)
from app.metadata import detect_metadata
from app.models import (
    AnalyzeResult,
    ArticleDiscoveryRequest,
    ArticleDiscoveryResponse,
    AssistantAnswerRequest,
    AssistantAnswerResponse,
    AssistantStatusResponse,
    GeneralAssistantRequest,
    GeneralAssistantResponse,
    ExtractTextResult,
    MetadataPreview,
    RewriteOnlyRequest,
    TextRequest,
    WebArticlePreview,
    WebArticleRequest,
)
from app.channel_classifier import suggest_publish_channel
from app.providers import ExternalLlmProvider, LlmProvider, MockProvider
from app.providers.external import ExternalProviderError
from app.article_discovery import DiscoveryFailure, DiscoverySource, discover_articles
from app.web_ingest import preview_web_article, download_validated_image

app = FastAPI(title="简达 AI 服务", version="0.1.0")


class _SkipHealthAccessLog(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        args = record.args
        return not (
            isinstance(args, tuple)
            and len(args) >= 3
            and str(args[2]).split("?", 1)[0] == "/health"
        )


logging.getLogger("uvicorn.access").addFilter(_SkipHealthAccessLog())


def get_provider() -> LlmProvider:
    return ExternalLlmProvider() if os.getenv("LLM_PROVIDER", "mock").lower() == "external" else MockProvider()


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "provider": os.getenv("LLM_PROVIDER", "mock")}


@app.get("/internal/runtime-capabilities")
def runtime_capabilities() -> dict[str, object]:
    tesseract = shutil.which("tesseract")
    ocr_languages: list[str] = []
    if tesseract:
        try:
            completed = subprocess.run(
                [tesseract, "--list-langs"], capture_output=True, text=True,
                timeout=3, check=False,
            )
            ocr_languages = [
                line.strip() for line in completed.stdout.splitlines()[1:] if line.strip()
            ]
        except (OSError, subprocess.SubprocessError):
            ocr_languages = []
    required_languages = [item for item in OCR_LANGUAGE.split("+") if item]
    ocr_ready = bool(tesseract) and all(
        language in ocr_languages for language in required_languages
    )
    provider = os.getenv("LLM_PROVIDER", "mock").lower()
    llm_ready = provider == "mock" or bool(os.getenv("EXTERNAL_LLM_API_KEY", "").strip())
    return {
        "service": {"status": "ready"},
        "llm": {
            "status": "ready" if llm_ready else "degraded",
            "provider": provider,
            "model": os.getenv("EXTERNAL_LLM_MODEL", "") if provider == "external" else "mock",
        },
        "ocr": {
            "status": "ready" if ocr_ready else "degraded",
            "engine": "tesseract" if tesseract else "unavailable",
            "required_languages": required_languages,
            "available_languages": ocr_languages,
        },
        "webCollector": {"status": "ready"},
    }


@app.post("/internal/extract-text", response_model=ExtractTextResult)
async def extract_text(file: UploadFile = File(...)) -> ExtractTextResult:
    suffix = Path(file.filename or "").suffix.lower()
    if suffix not in ALLOWED_SUFFIXES:
        raise HTTPException(status_code=400, detail="仅支持 PDF、PNG、JPG 文件")
    with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as temp:
        temp.write(await file.read())
        path = Path(temp.name)
    try:
        try:
            return extract_file(path)
        except OcrUnavailableError as exception:
            raise HTTPException(status_code=503, detail=str(exception)) from exception
        except (ValueError, RuntimeError) as exception:
            raise HTTPException(status_code=422, detail=str(exception)) from exception
    finally:
        path.unlink(missing_ok=True)


@app.post("/internal/pdf-first-page")
async def pdf_first_page(file: UploadFile = File(...)) -> Response:
    if Path(file.filename or "").suffix.lower() != ".pdf":
        raise HTTPException(status_code=400, detail="仅支持 PDF 文件")
    with tempfile.NamedTemporaryFile(suffix=".pdf", delete=False) as temp:
        temp.write(await file.read())
        path = Path(temp.name)
    try:
        return Response(
            content=render_pdf_first_page(path),
            media_type="image/png",
            headers={"Cache-Control": "no-store"},
        )
    except (ValueError, RuntimeError) as exception:
        raise HTTPException(status_code=422, detail=str(exception)) from exception
    finally:
        path.unlink(missing_ok=True)


@app.post("/internal/image-cache")
async def image_cache(request: WebArticleRequest) -> Response:
    try:
        data, content_type, width, height = await download_validated_image(str(request.url))
        return Response(
            content=data,
            media_type=content_type,
            headers={
                "Cache-Control": "no-store",
                "X-Image-Width": str(width),
                "X-Image-Height": str(height),
            },
        )
    except (ValueError, httpx.HTTPError) as exception:
        raise HTTPException(status_code=422, detail=str(exception)) from exception


@app.post("/internal/metadata-preview", response_model=MetadataPreview)
async def metadata_preview(
    file: UploadFile = File(...), no_llm: bool = Query(True)
) -> MetadataPreview:
    suffix = Path(file.filename or "").suffix.lower()
    if suffix not in ALLOWED_SUFFIXES:
        raise HTTPException(status_code=400, detail="仅支持 PDF、PNG、JPG 文件")
    with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as temp:
        temp.write(await file.read())
        path = Path(temp.name)
    try:
        preview, preview_text = detect_metadata(path, file.filename or "")
        if (
            not no_llm
            and preview.authority_status != "DOCUMENT_EVIDENCE"
            and os.getenv("LLM_PROVIDER", "mock").lower() == "external"
            and preview_text.strip()
        ):
            try:
                preview = ExternalLlmProvider().preview_metadata(
                    preview_text, file.filename or "", preview
                )
            except RuntimeError:
                preview = preview.model_copy(
                    update={
                        "warnings": preview.warnings
                        + ["智能补充识别未完成，请人工确认标题和来源。"]
                    }
                )
        return preview
    finally:
        path.unlink(missing_ok=True)


@app.post("/internal/analyze", response_model=AnalyzeResult)
def analyze(request: TextRequest) -> AnalyzeResult:
    try:
        return _with_channel_suggestion(get_provider().analyze(request), request)
    except ExternalProviderError as exc:
        raise HTTPException(status_code=503, detail=exc.safe_detail()) from exc
    except (RuntimeError, NotImplementedError) as exc:
        logging.getLogger("uvicorn.error").exception("Unexpected AI analysis failure")
        raise HTTPException(
            status_code=503,
            detail={
                "error_code": "AI_SERVICE_UNAVAILABLE",
                "message": "AI 服务暂时不可用",
                "retryable": True,
            },
        ) from exc


@app.post("/internal/rewrite", response_model=AnalyzeResult)
def rewrite(request: RewriteOnlyRequest) -> AnalyzeResult:
    try:
        provider = get_provider()
        if not isinstance(provider, ExternalLlmProvider):
            raise ExternalProviderError("仅外部模型支持阶段恢复")
        checkpoint = request.fact_checkpoint.get("facts", request.fact_checkpoint)
        if not isinstance(checkpoint, dict):
            raise ExternalProviderError("事实检查点格式错误")
        return _with_channel_suggestion(
            provider.rewrite_from_checkpoint(request, checkpoint), request
        )
    except ExternalProviderError as exc:
        raise HTTPException(status_code=503, detail=exc.safe_detail()) from exc


def _with_channel_suggestion(
    result: AnalyzeResult, request: TextRequest
) -> AnalyzeResult:
    suggestion = suggest_publish_channel(request)
    return result.model_copy(
        update={
            "suggested_publish_channel": suggestion.channel,
            "channel_confidence": suggestion.confidence,
            "channel_reason": suggestion.reason,
        }
    )


@app.post("/internal/simplify")
def simplify(request: TextRequest) -> dict[str, object]:
    result = analyze(request)
    return {"summary": result.summary, "plain_text": result.plain_text, "audio_script": result.audio_script}


@app.post("/internal/generate-steps")
def generate_steps(request: TextRequest) -> dict[str, object]:
    return {"steps": analyze(request).steps}


@app.post("/internal/trace-fields")
def trace_fields(request: TextRequest) -> dict[str, object]:
    return {"fields": analyze(request).fields}


@app.get("/internal/assistant/status", response_model=AssistantStatusResponse)
def assistant_status() -> AssistantStatusResponse:
    enabled = os.getenv("ASSISTANT_EXTERNAL_ENABLED", "false").lower() == "true"
    configured = bool(os.getenv("EXTERNAL_LLM_API_KEY", "").strip())
    status = "disabled" if not enabled else "ready" if configured else "degraded"
    return AssistantStatusResponse(
        status=status,
        external_enabled=enabled,
        provider_configured=configured,
    )


@app.post("/internal/assistant/answer", response_model=AssistantAnswerResponse)
def assistant_answer(request: AssistantAnswerRequest) -> AssistantAnswerResponse:
    if os.getenv("ASSISTANT_EXTERNAL_ENABLED", "false").lower() != "true":
        raise HTTPException(
            status_code=503,
            detail={
                "error_code": "ASSISTANT_EXTERNAL_DISABLED",
                "message": "助手外部模型未启用",
                "retryable": False,
            },
        )
    try:
        return ExternalLlmProvider().answer_assistant(request)
    except ExternalProviderError as exc:
        raise HTTPException(status_code=503, detail=exc.safe_detail()) from exc


@app.post(
    "/internal/assistant/general-answer",
    response_model=GeneralAssistantResponse,
)
def assistant_general_answer(
    request: GeneralAssistantRequest,
) -> GeneralAssistantResponse:
    if os.getenv("ASSISTANT_EXTERNAL_ENABLED", "false").lower() != "true":
        raise HTTPException(
            status_code=503,
            detail={
                "error_code": "ASSISTANT_EXTERNAL_DISABLED",
                "message": "助手外部模型未启用",
                "retryable": False,
            },
        )
    try:
        return ExternalLlmProvider().answer_general_assistant(request)
    except ExternalProviderError as exc:
        raise HTTPException(status_code=503, detail=exc.safe_detail()) from exc


@app.post("/internal/article-discovery", response_model=ArticleDiscoveryResponse)
async def article_discovery(request: ArticleDiscoveryRequest) -> ArticleDiscoveryResponse:
    try:
        result = await discover_articles(
            DiscoverySource(request.source_id, request.source_url, request.rate_limit_seconds),
            request.entry_url,
            request.method,
        )
        return ArticleDiscoveryResponse(
            candidates=[candidate.__dict__ for candidate in result.candidates],
            errors=result.errors,
            filtered_navigation_count=result.filtered_navigation_count,
        )
    except DiscoveryFailure as exc:
        raise HTTPException(status_code=exc.status_code, detail=exc.detail()) from exc
    except PermissionError as exc:
        raise HTTPException(status_code=403, detail={
            "error_code": "ROBOTS_DENIED", "message": str(exc),
            "retryable": False, "stage": "DISCOVERY",
        }) from exc
    except (ValueError, OSError) as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        logging.getLogger("uvicorn.error").warning(
            "article_discovery_failed type=%s", type(exc).__name__
        )
        raise HTTPException(status_code=502, detail="文章发现入口暂时无法访问或解析") from exc


@app.post("/internal/web-ingest/preview", response_model=WebArticlePreview)
async def web_ingest_preview(request: WebArticleRequest) -> WebArticlePreview:
    try:
        return await preview_web_article(
            request.url,
            allow_image_candidates=(
                request.allow_image_candidates or request.allow_image_download
            ),
        )
    except PermissionError as exc:
        raise HTTPException(status_code=403, detail=str(exc)) from exc
    except (ValueError, OSError) as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        logging.getLogger("uvicorn.error").warning(
            "web_ingest_failed type=%s", type(exc).__name__
        )
        raise HTTPException(status_code=502, detail="网页暂时无法访问或解析") from exc
