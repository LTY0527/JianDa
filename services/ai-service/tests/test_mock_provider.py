from pathlib import Path

import fitz
import pytest
from fastapi.testclient import TestClient

import app.extraction as extraction
from app.extraction import OcrUnavailableError, extract_file, render_pdf_first_page
from app.main import app
from app.providers.external import ExternalProviderError
from app.models import MetadataPreview, TextRequest
from app.providers.mock import MockProvider


SUBSIDY_TEXT = """补贴对象为具有本市户籍且年满八十周岁的老年人。
已享受同类补贴待遇的，不重复发放。
申请材料：身份证及户口簿原件、本人银行卡复印件、近期一寸免冠照片一张。
请申请人至户籍所在地社区服务窗口提出申请。咨询电话：021-12345。"""

SILVER_ACTIVITY_TEXT = """2026年“银龄数字生活”公益辅导活动报名通知
一、活动对象
本街道常住居民，原则上年龄为55周岁及以上；能够自行携带并基本操作智能手机。每场限30人，额满即止。
二、活动时间与地点
第一场
2026年8月20日（周四）
09:00-11:00
浦江街道社区服务中心201教室
第二场
2026年8月22日（周六）
09:00-11:00
浦江街道社区服务中心201教室
四、报名方式
报名时间为2026年8月1日至8月15日。居民可携带本人身份证到社区服务中心一楼综合窗口报名，也可拨打
咨询电话021-5688-1026登记。
五、需携带物品
本人日常使用的智能手机、充电线，以及用于现场核对报名信息的身份证。活动不收取任何费用，不要求
提供银行卡、支付密码或短信验证码。"""


def fields_by_type(text: str) -> dict[str, object]:
    result = MockProvider().analyze(TextRequest(title="测试材料", text=text))
    return {field.field_type: field for field in result.fields}


def test_mock_result_is_stable_and_keeps_subsidy_example() -> None:
    provider = MockProvider()
    request = TextRequest(title="老年补贴申请指南", text=SUBSIDY_TEXT)
    first = provider.analyze(request)
    second = provider.analyze(request)
    assert first == second
    assert {field.field_type for field in first.fields} >= {
        "TARGET_AUDIENCE",
        "ELIGIBILITY",
        "MATERIAL",
        "LOCATION",
        "CONTACT",
    }


def test_silver_activity_uses_supplied_text_and_extracts_expected_fields() -> None:
    result = MockProvider().analyze(
        TextRequest(title="银龄数字生活公益辅导活动", text=SILVER_ACTIVITY_TEXT)
    )
    fields = {field.field_type: field for field in result.fields}
    assert fields["TARGET_AUDIENCE"].value == "55周岁及以上常住居民"
    assert fields["START_DATE"].value == "2026年8月1日"
    assert fields["END_DATE"].value == "2026年8月15日"
    assert fields["LOCATION"].value == "浦江街道社区服务中心201教室"
    assert fields["FEE"].value == "免费"
    assert fields["CONTACT"].value == "021-5688-1026"
    assert fields["MATERIAL"].value == "智能手机、充电线、身份证"
    assert fields["WARNING"].value == "不提供银行卡、支付密码或短信验证码"
    assert "2026年8月20日、2026年8月22日" in result.plain_text
    assert "09:00-11:00" in result.plain_text
    assert all(field.source_quote in SILVER_ACTIVITY_TEXT for field in result.fields)


def test_two_different_documents_do_not_return_identical_fields() -> None:
    subsidy = MockProvider().analyze(TextRequest(title="补贴", text=SUBSIDY_TEXT))
    activity = MockProvider().analyze(TextRequest(title="活动", text=SILVER_ACTIVITY_TEXT))
    assert subsidy.fields != activity.fields
    assert subsidy.plain_text != activity.plain_text


def test_unknown_content_is_not_fabricated() -> None:
    result = MockProvider().analyze(TextRequest(title="未知材料", text="这是一段没有结构化事项的普通正文。"))
    assert result.fields == []
    assert result.plain_text == "待人工填写。"
    assert "80" not in result.plain_text
    assert "银行卡" not in result.plain_text


def test_pdf_extraction_saves_one_traceable_segment_per_page() -> None:
    pdf_path = Path(__file__).with_name("_generated-two-pages.pdf")
    try:
        document = fitz.open()
        for text in ("first page source", "second page source"):
            page = document.new_page()
            page.insert_text((72, 72), text)
        document.save(pdf_path)
        document.close()

        result = extract_file(pdf_path)
        assert result.page_count == 2
        assert [(item.page_no, item.segment_no) for item in result.segments] == [(1, 1), (2, 1)]
        assert result.segments[0].text == "first page source"
        assert result.segments[1].text == "second page source"
        assert result.text == "first page source\nsecond page source"
    finally:
        pdf_path.unlink(missing_ok=True)


def _insert_scanned_page(document: fitz.Document) -> None:
    page = document.new_page(width=595, height=842)
    pixmap = fitz.Pixmap(fitz.csRGB, fitz.IRect(0, 0, 400, 200), False)
    pixmap.clear_with(255)
    page.insert_image(fitz.Rect(72, 72, 472, 272), stream=pixmap.tobytes("png"))


def test_scanned_pdf_uses_page_aware_local_ocr(monkeypatch) -> None:
    pdf_path = Path(__file__).with_name("_generated-scanned.pdf")
    try:
        document = fitz.open()
        _insert_scanned_page(document)
        document.save(pdf_path)
        document.close()
        monkeypatch.setattr(
            extraction,
            "_ocr_page_text",
            lambda page: "大场镇社区服务扫描通知",
        )

        result = extract_file(pdf_path)

        assert result.extraction_method == "ocr"
        assert result.page_count == 1
        assert result.text == "大场镇社区服务扫描通知"
        assert result.segments[0].page_no == 1
        assert result.segments[0].text == result.text
    finally:
        pdf_path.unlink(missing_ok=True)


def test_mixed_pdf_only_ocr_scanned_page(monkeypatch) -> None:
    pdf_path = Path(__file__).with_name("_generated-mixed.pdf")
    try:
        document = fitz.open()
        page = document.new_page()
        page.insert_text((72, 72), "digital first page")
        _insert_scanned_page(document)
        document.save(pdf_path)
        document.close()
        calls: list[int] = []

        def fake_ocr(page: fitz.Page) -> str:
            calls.append(page.number + 1)
            return "第二页扫描正文"

        monkeypatch.setattr(extraction, "_ocr_page_text", fake_ocr)
        result = extract_file(pdf_path)

        assert calls == [2]
        assert result.extraction_method == "pymupdf+ocr"
        assert [item.page_no for item in result.segments] == [1, 2]
        assert result.text == "digital first page\n第二页扫描正文"
    finally:
        pdf_path.unlink(missing_ok=True)


def test_scanned_pdf_reports_local_ocr_failure(monkeypatch) -> None:
    pdf_path = Path(__file__).with_name("_generated-ocr-failure.pdf")
    try:
        document = fitz.open()
        _insert_scanned_page(document)
        document.save(pdf_path)
        document.close()

        def fail_ocr(page: fitz.Page) -> str:
            raise OcrUnavailableError("扫描页需要本地 OCR，但 OCR 引擎不可用")

        monkeypatch.setattr(extraction, "_ocr_page_text", fail_ocr)
        with pytest.raises(OcrUnavailableError, match="OCR 引擎不可用"):
            extract_file(pdf_path)
    finally:
        pdf_path.unlink(missing_ok=True)


def test_pdf_first_page_cover_is_real_png() -> None:
    pdf_path = Path(__file__).with_name("_generated-cover.pdf")
    try:
        document = fitz.open()
        page = document.new_page(width=595, height=842)
        page.insert_text((72, 100), "JianDa public service document")
        document.save(pdf_path)
        document.close()

        image = render_pdf_first_page(pdf_path, target_width=900)
        assert image.startswith(b"\x89PNG\r\n\x1a\n")
        assert len(image) > 1000
    finally:
        pdf_path.unlink(missing_ok=True)


def test_metadata_preview_defaults_to_no_llm(monkeypatch) -> None:
    monkeypatch.setenv("LLM_PROVIDER", "external")
    monkeypatch.setattr("app.main.detect_metadata", lambda path, filename: (
        MetadataPreview(
            title="待确认材料", source_name="", document_number="", source_type="",
            authority_status="UNCONFIRMED", confidence=0.3, evidence_quote="",
            evidence_type="FILENAME", page_no=1, warnings=[]
        ),
        "需要智能补充的正文",
    ))

    class ForbiddenExternal:
        def preview_metadata(self, *args, **kwargs):
            raise AssertionError("default metadata preview must not call external LLM")

    monkeypatch.setattr("app.main.ExternalLlmProvider", ForbiddenExternal)
    response = TestClient(app).post(
        "/internal/metadata-preview",
        files={"file": ("material.pdf", b"%PDF", "application/pdf")},
    )
    assert response.status_code == 200
    assert response.json()["authority_status"] == "UNCONFIRMED"


def test_health_and_unknown_analyze() -> None:
    client = TestClient(app)
    assert client.get("/health").json()["status"] == "ok"
    response = client.post(
        "/internal/analyze",
        json={"title": "指南", "text": "正文", "document_type": "guide"},
    )
    assert response.status_code == 200
    assert response.json()["fields"] == []
    assert response.json()["plain_text"] == "待人工填写。"


def test_analyze_returns_structured_safe_provider_error(monkeypatch) -> None:
    class FailedProvider:
        def analyze(self, request):
            raise ExternalProviderError(
                "缺少必填字段：quick_summary",
                error_code="LLM_SCHEMA_VALIDATION_FAILED",
                stage="accessible_rewrite",
                schema_version="web-v1.1",
                json_path="$.quick_summary",
                keyword="required",
                response_fingerprint="0123456789abcdef",
                request_id="req-safe",
                retryable=True,
            )

    monkeypatch.setattr("app.main.get_provider", lambda: FailedProvider())
    response = TestClient(app).post(
        "/internal/analyze",
        json={"title": "资讯", "text": "短测试正文", "document_type": "public_news"},
    )
    assert response.status_code == 503
    detail = response.json()["detail"]
    assert detail["error_code"] == "LLM_SCHEMA_VALIDATION_FAILED"
    assert detail["json_path"] == "$.quick_summary"
    assert detail["request_id"] == "req-safe"
    assert detail["retryable"] is True
    assert "短测试正文" not in response.text


def test_public_news_has_warning() -> None:
    result = MockProvider().analyze(
        TextRequest(
            title="健康提示",
            text="按医嘱规律服药，不可自行停药或减量。",
            document_type="public_news",
        )
    )
    assert result.steps == []
    assert result.warnings == ["按医嘱规律服药，不可自行停药或减量"]
    assert result.fields[0].source_quote == "按医嘱规律服药，不可自行停药或减量。"


def test_public_news_matches_fixture_topic_and_stays_stable() -> None:
    provider = MockProvider()
    anti_fraud = TextRequest(
        title="警惕退款诈骗", text="不要提供验证码", document_type="public_news"
    )
    community = TextRequest(
        title="社区养老服务安排", text="提供延时服务", document_type="public_news"
    )
    assert provider.analyze(anti_fraud) == provider.analyze(anti_fraud)
    assert provider.analyze(anti_fraud).fields[0].source_quote == "不要提供验证码"
    assert provider.analyze(community).fields[0].field_type == "SERVICE_TIME"
