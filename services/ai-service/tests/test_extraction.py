from pathlib import Path

import pytest

from app import extraction


class _Page:
    def __init__(self, text: str = ""):
        self.text = text

    def get_text(self, kind: str, **kwargs):
        return self.text


class _Document:
    def __init__(self, page: _Page):
        self.page = page
        self.page_count = 1

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, traceback):
        return False

    def __iter__(self):
        return iter([self.page])


def _score(label: str, score: float, chars: int = 20):
    return extraction._QualityScore(
        label=label,
        score=score,
        text_char_count=chars,
        valid_chinese_ratio=0.8,
        replacement_char_ratio=0,
        whitespace_ratio=0,
        image_count=1,
        image_area_ratio=0.8,
        text_block_count=1,
        suspicious_garbage_ratio=0,
    )


def test_jpeg_routes_through_ocr_and_exposes_quality_score(monkeypatch, tmp_path):
    path = tmp_path / "scan.jpeg"
    path.write_bytes(b"jpeg fixture")
    page = _Page()
    monkeypatch.setattr(extraction.fitz, "open", lambda value: _Document(page))
    monkeypatch.setattr(extraction, "_ocr_page_text", lambda value: "这是清晰的图片识别正文内容")
    scores = iter([_score("POOR", 0.05, 0), _score("GOOD", 0.91)])
    monkeypatch.setattr(extraction, "_quality_score", lambda page, text: next(scores))

    result = extraction.extract_file(path)

    assert result.text == "这是清晰的图片识别正文内容"
    assert result.extraction_method == "ocr"
    assert result.ocr_page_count == 1
    assert result.quality_pages[0].score == 0.91
    assert result.quality_pages[0].ocr_attempted is True
    assert result.quality_pages[0].needs_human_review is False


def test_uncertain_text_safely_falls_back_when_ocr_is_unavailable(monkeypatch, tmp_path):
    path = tmp_path / "uncertain.pdf"
    path.write_bytes(b"pdf fixture")
    page = _Page("仍可供人工复核的文本")
    monkeypatch.setattr(extraction.fitz, "open", lambda value: _Document(page))
    monkeypatch.setattr(extraction, "_quality_score", lambda page, text: _score("UNCERTAIN", 0.52))
    monkeypatch.setattr(
        extraction,
        "_ocr_page_text",
        lambda value: (_ for _ in ()).throw(extraction.OcrUnavailableError("OCR unavailable")),
    )

    result = extraction.extract_file(path)

    quality = result.quality_pages[0]
    assert result.text == "仍可供人工复核的文本"
    assert quality.quality == "UNCERTAIN"
    assert quality.ocr_attempted is True
    assert quality.ocr_error == "OCR unavailable"
    assert quality.needs_human_review is True


def test_poor_ocr_is_not_returned_as_success(monkeypatch, tmp_path):
    path = tmp_path / "low-quality.png"
    path.write_bytes(b"png fixture")
    page = _Page("□□")
    monkeypatch.setattr(extraction.fitz, "open", lambda value: _Document(page))
    monkeypatch.setattr(extraction, "_ocr_page_text", lambda value: "���garbage")
    scores = iter([_score("POOR", -0.2, 2), _score("POOR", 0.08, 10)])
    monkeypatch.setattr(extraction, "_quality_score", lambda page, text: next(scores))

    with pytest.raises(ValueError, match="未提取到可读文本"):
        extraction.extract_file(path)
