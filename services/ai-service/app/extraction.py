from pathlib import Path
import os

import fitz

from app.models import ExtractTextResult, Segment


ALLOWED_SUFFIXES = {".pdf", ".png", ".jpg", ".jpeg"}
OCR_LANGUAGE = os.getenv("OCR_LANGUAGE", "chi_sim+eng").strip() or "chi_sim+eng"


class OcrUnavailableError(RuntimeError):
    """Raised when a scanned page needs OCR but local OCR is unavailable."""


def _page_needs_ocr(page: fitz.Page, text: str) -> bool:
    if text.strip():
        return False
    try:
        return bool(page.get_image_info())
    except RuntimeError:
        return False


def _ocr_page_text(page: fitz.Page) -> str:
    try:
        text_page = page.get_textpage_ocr(
            language=OCR_LANGUAGE,
            dpi=220,
            full=True,
        )
        return page.get_text("text", textpage=text_page).strip()
    except (RuntimeError, ValueError) as exc:
        raise OcrUnavailableError(
            "扫描页需要本地 OCR，但 OCR 引擎或中文语言包不可用；"
            "请安装 Tesseract 5 和 chi_sim 语言包后重试"
        ) from exc


def extract_file(path: Path) -> ExtractTextResult:
    suffix = path.suffix.lower()
    if suffix not in ALLOWED_SUFFIXES:
        raise ValueError("仅支持 PDF、PNG、JPG 文件")
    if suffix != ".pdf":
        return ExtractTextResult(text="", page_count=1, segments=[], extraction_method="manual_required")

    segments: list[Segment] = []
    full_text: list[str] = []
    offset = 0
    ocr_page_count = 0
    with fitz.open(path) as document:
        for page_index, page in enumerate(document):
            # A page is the durable trace unit. PDF layout engines often split one
            # sentence into multiple visual blocks, so block-level segments can
            # make an otherwise exact source quote impossible to trace.
            text = page.get_text("text").strip()
            if _page_needs_ocr(page, text):
                text = _ocr_page_text(page)
                ocr_page_count += 1
            if text:
                segments.append(
                    Segment(
                        page_no=page_index + 1,
                        segment_no=1,
                        text=text,
                        start_offset=offset,
                        end_offset=offset + len(text),
                    )
                )
                full_text.append(text)
                offset += len(text) + 1
        page_count = document.page_count
    if not full_text:
        raise ValueError(
            "PDF 未提取到可读文本；文件可能是空白扫描件或 OCR 未能识别，请检查原文件后重试"
        )
    extraction_method = (
        "pymupdf+ocr"
        if ocr_page_count and ocr_page_count < page_count
        else "ocr"
        if ocr_page_count
        else "pymupdf"
    )
    return ExtractTextResult(
        text="\n".join(full_text),
        page_count=page_count,
        segments=segments,
        extraction_method=extraction_method,
    )


def render_pdf_first_page(path: Path, target_width: int = 1600) -> bytes:
    if path.suffix.lower() != ".pdf":
        raise ValueError("仅支持 PDF 文件")
    with fitz.open(path) as document:
        if document.page_count < 1:
            raise ValueError("PDF 没有可渲染页面")
        page = document.load_page(0)
        scale = max(1.0, target_width / max(1.0, page.rect.width))
        pixmap = page.get_pixmap(matrix=fitz.Matrix(scale, scale), alpha=False)
        return pixmap.tobytes("png")
