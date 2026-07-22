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

    document = fitz.open(path)
    segments: list[Segment] = []
    full_text: list[str] = []
    offset = 0
    for page_index, page in enumerate(document):
        blocks = [block[4].strip() for block in page.get_text("blocks") if block[4].strip()]
        for segment_index, text in enumerate(blocks, start=1):
            segments.append(Segment(page_no=page_index + 1, segment_no=segment_index, text=text,
                                    start_offset=offset, end_offset=offset + len(text)))
            full_text.append(text)
            offset += len(text) + 1
    return ExtractTextResult(text="\n".join(full_text), page_count=document.page_count,
                             segments=segments, extraction_method="pymupdf")

