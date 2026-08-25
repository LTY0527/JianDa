from __future__ import annotations

from collections import Counter
from dataclasses import dataclass
from pathlib import Path
import math
import os
import re

import fitz

from app.models import ExtractTextResult, PageExtractionQuality, Segment


ALLOWED_SUFFIXES = {".pdf", ".png", ".jpg", ".jpeg"}
OCR_LANGUAGE = os.getenv("OCR_LANGUAGE", "chi_sim+eng").strip() or "chi_sim+eng"


class OcrUnavailableError(RuntimeError):
    """Raised when a low-quality page needs OCR but local OCR is unavailable."""


@dataclass(frozen=True)
class _QualityScore:
    label: str
    score: float
    text_char_count: int
    valid_chinese_ratio: float
    replacement_char_ratio: float
    whitespace_ratio: float
    image_count: int
    image_area_ratio: float
    text_block_count: int
    suspicious_garbage_ratio: float


def _page_image_metrics(page: fitz.Page) -> tuple[int, float]:
    try:
        images = page.get_image_info()
    except RuntimeError:
        return 0, 0.0
    page_area = max(1.0, page.rect.width * page.rect.height)
    covered = 0.0
    for image in images:
        bbox = image.get("bbox") if isinstance(image, dict) else None
        if not bbox or len(bbox) != 4:
            continue
        covered += max(0.0, float(bbox[2]) - float(bbox[0])) * max(
            0.0, float(bbox[3]) - float(bbox[1])
        )
    return len(images), round(min(1.0, covered / page_area), 4)


def _quality_score(page: fitz.Page, text: str) -> _QualityScore:
    total = max(1, len(text))
    nonspace = [char for char in text if not char.isspace()]
    denominator = max(1, len(nonspace))
    chinese = sum("\u4e00" <= char <= "\u9fff" for char in nonspace)
    valid = sum(
        ("\u4e00" <= char <= "\u9fff") or char.isalnum()
        or char in "，。；：！？、（）()《》【】[]-—_/:.%+"
        for char in nonspace
    )
    replacement = sum(char in {"�", "□", "\ufffd"} for char in nonspace)
    suspicious = sum(
        ord(char) < 32 or (not char.isprintable() and not char.isspace())
        for char in text
    ) + replacement
    image_count, image_area_ratio = _page_image_metrics(page)
    try:
        block_count = len(page.get_text("blocks"))
    except RuntimeError:
        block_count = 0
    char_count = len(text.strip())
    valid_ratio = valid / denominator
    chinese_ratio = chinese / denominator
    replacement_ratio = replacement / denominator
    whitespace_ratio = sum(char.isspace() for char in text) / total
    garbage_ratio = suspicious / denominator
    dominant_image_with_thin_text = image_area_ratio >= 0.35 and char_count < 80
    if (
        char_count >= 12 and valid_ratio >= 0.65
        and replacement_ratio <= 0.02 and garbage_ratio <= 0.08
        and not dominant_image_with_thin_text
    ):
        label = "GOOD"
    elif (
        char_count >= 5 and valid_ratio >= 0.4
        and replacement_ratio <= 0.1 and garbage_ratio <= 0.2
    ):
        label = "UNCERTAIN"
    else:
        label = "POOR"
    score = (
        min(1.0, char_count / 120) * 0.25 + valid_ratio * 0.4
        + chinese_ratio * 0.15 - replacement_ratio * 0.5
        - garbage_ratio * 0.4 - (0.18 if dominant_image_with_thin_text else 0)
    )
    return _QualityScore(
        label=label,
        score=score,
        text_char_count=char_count,
        valid_chinese_ratio=round(chinese_ratio, 4),
        replacement_char_ratio=round(replacement_ratio, 4),
        whitespace_ratio=round(whitespace_ratio, 4),
        image_count=image_count,
        image_area_ratio=image_area_ratio,
        text_block_count=block_count,
        suspicious_garbage_ratio=round(garbage_ratio, 4),
    )


def _ocr_page_text(page: fitz.Page) -> str:
    try:
        text_page = page.get_textpage_ocr(
            language=OCR_LANGUAGE,
            dpi=260,
            full=True,
        )
        return page.get_text("text", textpage=text_page).strip()
    except (RuntimeError, ValueError) as exc:
        raise OcrUnavailableError(
            "当前页面需要本地 OCR，但 OCR 引擎或中文语言包不可用；"
            "请安装 Tesseract 5 和 chi_sim 语言包后重试"
        ) from exc


def _clean_page_text(text: str) -> str:
    cleaned = text.replace("\x00", "").replace("\ufeff", "")
    cleaned = re.sub(r"```(?:json)?|```", "", cleaned, flags=re.IGNORECASE)
    cleaned = re.sub(r"(?m)^\s{0,3}#{1,6}\s*", "", cleaned)
    cleaned = cleaned.replace("**", "").replace("__", "")
    lines: list[str] = []
    for raw_line in cleaned.splitlines():
        line = re.sub(r"[\t\u00a0 ]+", " ", raw_line).strip()
        if not line:
            continue
        bad = sum(char in {"�", "□", "\ufffd"} for char in line)
        if bad / max(1, len(line)) > 0.25:
            continue
        lines.append(line)
    return "\n".join(lines).strip()


def _remove_repeated_headers_and_footers(pages: list[str]) -> list[str]:
    if len(pages) < 3:
        return pages
    split_pages = [[line for line in page.splitlines() if line] for page in pages]
    edge_lines = Counter(
        line for lines in split_pages for line in (lines[:1] + lines[-1:])
        if 1 < len(line) < 100
    )
    threshold = max(2, math.ceil(len(pages) * 0.6))
    repeated = {line for line, count in edge_lines.items() if count >= threshold}
    if not repeated:
        return pages
    return ["\n".join(line for line in lines if line not in repeated).strip()
            for lines in split_pages]


def _quality_model(
    page_no: int,
    quality: _QualityScore,
    selected_source: str,
    *,
    ocr_attempted: bool,
    ocr_error: str | None = None,
) -> PageExtractionQuality:
    return PageExtractionQuality(
        page_no=page_no,
        quality=quality.label,
        score=round(max(0.0, min(1.0, quality.score)), 4),
        selected_source=selected_source,
        text_char_count=quality.text_char_count,
        valid_chinese_ratio=quality.valid_chinese_ratio,
        replacement_char_ratio=quality.replacement_char_ratio,
        whitespace_ratio=quality.whitespace_ratio,
        image_count=quality.image_count,
        image_area_ratio=quality.image_area_ratio,
        text_block_count=quality.text_block_count,
        suspicious_garbage_ratio=quality.suspicious_garbage_ratio,
        ocr_attempted=ocr_attempted,
        ocr_error=ocr_error,
        needs_human_review=quality.label != "GOOD",
    )


def extract_file(path: Path) -> ExtractTextResult:
    suffix = path.suffix.lower()
    if suffix not in ALLOWED_SUFFIXES:
        raise ValueError("仅支持 PDF、PNG、JPG 文件")

    raw_pages: list[str] = []
    selected_pages: list[str] = []
    page_quality: list[PageExtractionQuality] = []
    ocr_page_count = 0
    with fitz.open(path) as document:
        for page_index, page in enumerate(document):
            raw_text = page.get_text("text").strip()
            text_quality = _quality_score(page, raw_text)
            selected_text = raw_text
            selected_quality = text_quality
            selected_source = "TEXT_LAYER"
            ocr_attempted = False
            ocr_error: str | None = None
            should_ocr = suffix != ".pdf" or text_quality.label != "GOOD"
            if should_ocr:
                ocr_attempted = True
                try:
                    ocr_text = _ocr_page_text(page)
                except OcrUnavailableError as exc:
                    ocr_error = str(exc)
                    ocr_text = ""
                    if text_quality.label != "UNCERTAIN" or not raw_text.strip():
                        raise
                if ocr_text:
                    ocr_quality = _quality_score(page, ocr_text)
                    if ocr_quality.label != "POOR" and (
                        text_quality.label == "POOR"
                        or ocr_quality.score > text_quality.score
                    ):
                        selected_text = ocr_text
                        selected_quality = ocr_quality
                        selected_source = "OCR"
                        ocr_page_count += 1
            raw_pages.append(raw_text)
            selected_pages.append(
                _clean_page_text(selected_text)
                if selected_quality.label != "POOR" else ""
            )
            page_quality.append(_quality_model(
                page_index + 1,
                selected_quality,
                selected_source,
                ocr_attempted=ocr_attempted,
                ocr_error=ocr_error,
            ))
        page_count = document.page_count

    selected_pages = _remove_repeated_headers_and_footers(selected_pages)
    segments: list[Segment] = []
    full_text: list[str] = []
    offset = 0
    for page_index, text in enumerate(selected_pages):
        if not text:
            continue
        segments.append(Segment(
            page_no=page_index + 1,
            segment_no=1,
            text=text,
            raw_text=raw_pages[page_index],
            start_offset=offset,
            end_offset=offset + len(text),
        ))
        full_text.append(text)
        offset += len(text) + 1
    if not full_text:
        raise ValueError(
            "文件未提取到可读文本；请检查图像清晰度、页面方向或 OCR 语言包"
        )
    extraction_method = (
        "pymupdf+ocr" if suffix == ".pdf" and 0 < ocr_page_count < page_count
        else "ocr" if ocr_page_count or suffix != ".pdf"
        else "pymupdf"
    )
    return ExtractTextResult(
        text="\n".join(full_text),
        raw_text="\n".join(raw_pages),
        page_count=page_count,
        segments=segments,
        extraction_method=extraction_method,
        ocr_page_count=ocr_page_count,
        quality_pages=page_quality,
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
