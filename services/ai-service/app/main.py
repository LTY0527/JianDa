import os
import logging
import tempfile
from pathlib import Path

from fastapi import FastAPI, File, HTTPException, Query, UploadFile

from app.extraction import ALLOWED_SUFFIXES, extract_file
from app.metadata import detect_metadata
from app.models import (
    AnalyzeResult,
    ArticleDiscoveryRequest,
    ArticleDiscoveryResponse,
    AssistantAnswerRequest,
    AssistantAnswerResponse,
    ExtractTextResult,
    MetadataPreview,
    RewriteOnlyRequest,
    TextRequest,
    WebArticlePreview,
    WebArticleRequest,
)
from app.providers import ExternalLlmProvider, LlmProvider, MockProvider
from app.providers.external import ExternalProviderError
from app.article_discovery import DiscoverySource, discover_articles
from app.web_ingest import preview_web_article

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


@app.post("/internal/extract-text", response_model=ExtractTextResult)
async def extract_text(file: UploadFile = File(...)) -> ExtractTextResult:
    suffix = Path(file.filename or "").suffix.lower()
    if suffix not in ALLOWED_SUFFIXES:
        raise HTTPException(status_code=400, detail="仅支持 PDF、PNG、JPG 文件")
    with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as temp:
        temp.write(await file.read())
        path = Path(temp.name)
    try:
        return extract_file(path)
    finally:
        path.unlink(missing_ok=True)


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
        return get_provider().analyze(request)
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
        return provider.rewrite_from_checkpoint(request, checkpoint)
    except ExternalProviderError as exc:
        raise HTTPException(status_code=503, detail=exc.safe_detail()) from exc


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
        )
    except PermissionError as exc:
        raise HTTPException(status_code=403, detail=str(exc)) from exc
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
            allow_image_download=request.allow_image_download,
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
