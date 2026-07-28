import os
import logging
import tempfile
from pathlib import Path

from fastapi import FastAPI, File, HTTPException, UploadFile

from app.extraction import ALLOWED_SUFFIXES, extract_file
from app.metadata import detect_metadata
from app.models import AnalyzeResult, ExtractTextResult, MetadataPreview, TextRequest
from app.providers import ExternalLlmProvider, LlmProvider, MockProvider

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
async def metadata_preview(file: UploadFile = File(...)) -> MetadataPreview:
    suffix = Path(file.filename or "").suffix.lower()
    if suffix not in ALLOWED_SUFFIXES:
        raise HTTPException(status_code=400, detail="仅支持 PDF、PNG、JPG 文件")
    with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as temp:
        temp.write(await file.read())
        path = Path(temp.name)
    try:
        preview, preview_text = detect_metadata(path, file.filename or "")
        if (
            preview.authority_status != "DOCUMENT_EVIDENCE"
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
    except (RuntimeError, NotImplementedError) as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc


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
