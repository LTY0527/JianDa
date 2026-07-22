import os
import tempfile
from pathlib import Path

from fastapi import FastAPI, File, HTTPException, UploadFile

from app.extraction import ALLOWED_SUFFIXES, extract_file
from app.models import AnalyzeResult, ExtractTextResult, TextRequest
from app.providers import ExternalLlmProvider, LlmProvider, MockProvider

app = FastAPI(title="简达 AI 服务", version="0.1.0")


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

