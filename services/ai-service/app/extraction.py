from pathlib import Path

import fitz

from app.models import ExtractTextResult, Segment


ALLOWED_SUFFIXES = {".pdf", ".png", ".jpg", ".jpeg"}


def extract_file(path: Path) -> ExtractTextResult:
    suffix = path.suffix.lower()
    if suffix not in ALLOWED_SUFFIXES:
        raise ValueError("仅支持 PDF、PNG、JPG 文件")
    if suffix != ".pdf":
        return ExtractTextResult(text="", page_count=1, segments=[], extraction_method="manual_required")

    segments: list[Segment] = []
    full_text: list[str] = []
    offset = 0
    with fitz.open(path) as document:
        for page_index, page in enumerate(document):
            # A page is the durable trace unit. PDF layout engines often split one
            # sentence into multiple visual blocks, so block-level segments can
            # make an otherwise exact source quote impossible to trace.
            text = page.get_text("text").strip()
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
    return ExtractTextResult(
        text="\n".join(full_text),
        page_count=page_count,
        segments=segments,
        extraction_method="pymupdf",
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
