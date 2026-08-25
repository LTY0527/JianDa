from app.document_structure import (
    build_type_specific_facts,
    detect_document_kind,
    split_document_sections,
)
from app.models import SourceSegment, TextRequest
from app.providers.external import ExternalLlmProvider, ExternalSettings
from app.providers.mock import MockProvider


STANDARD_TEXT = """养老服务标准
1 范围
本标准规定了社区养老服务的基本要求。
2 术语和定义
社区养老服务是面向老年人的公共服务。
3 基本要求
服务人员应当接受培训。
4 服务内容
服务包括助餐、探访和健康宣传。
5 质量评价
机构应当定期开展服务质量评价。"""


def segments(text: str) -> list[SourceSegment]:
    return [SourceSegment(segment_id=31, page_no=1, text=text)]


def test_document_kind_uses_body_structure_not_title_only() -> None:
    assert (
        detect_document_kind("养老服务标准", "这是一段普通公共服务说明。")
        == "GENERAL_PUBLIC_SERVICE"
    )
    assert (
        detect_document_kind("社区服务资料", STANDARD_TEXT)
        == "STANDARD_SPECIFICATION"
    )


def test_long_section_split_is_bounded_and_keeps_trace_ids() -> None:
    sentence = "服务人员应当核对记录并保护个人信息。"
    text = "1 基本要求\n" + sentence * 800
    result = split_document_sections(segments(text), max_chars=1200)
    assert len(result) > 1
    assert all(len(section.text) <= 1200 for section in result)
    assert all(section.segment_ids == (31,) for section in result)
    assert "".join(section.text for section in result).replace("\n", "") == (
        "1 基本要求\n" + sentence * 800
    ).replace("\n", "")


def test_standard_specific_facts_are_traceable_to_supplied_text() -> None:
    sections = split_document_sections(segments(STANDARD_TEXT))
    facts = build_type_specific_facts("STANDARD_SPECIFICATION", sections)
    assert {item.label for item in facts} >= {"范围", "术语", "基本要求", "服务内容", "质量"}
    assert all(item.source_quote in STANDARD_TEXT for item in facts)
    assert all(item.page_no == 1 and item.segment_id == 31 for item in facts)


def test_mock_provider_returns_document_kind_outline_and_standard_modules() -> None:
    result = MockProvider().analyze(
        TextRequest(
            title="社区养老服务资料",
            text=STANDARD_TEXT,
            segments=segments(STANDARD_TEXT),
        )
    )
    assert result.document_kind == "STANDARD_SPECIFICATION"
    assert len(result.document_outline) >= 5
    assert len(result.standard_sections) >= 5
    assert result.policy_sections == []
    assert all(
        item.source_quote in STANDARD_TEXT for item in result.standard_sections
    )


def test_external_long_document_uses_bounded_section_requests(monkeypatch) -> None:
    monkeypatch.setenv("LLM_LONG_DOCUMENT_THRESHOLD_CHARS", "4000")
    monkeypatch.setenv("LLM_SECTION_CHUNK_CHARS", "3000")
    monkeypatch.setenv("LLM_MAX_SECTION_CHUNKS", "3")
    provider = ExternalLlmProvider(
        settings=ExternalSettings(
            base_url="https://example.invalid",
            api_key="test-only",
            model="test-model",
            timeout_seconds=1,
            max_retries=0,
            max_tokens=6000,
            thinking="disabled",
            prompt_version="v1.1",
        )
    )
    source_segments = [
        SourceSegment(
            segment_id=index,
            page_no=index,
            text=f"{index} 服务内容\n" + ("服务要求。" * 900),
        )
        for index in range(1, 6)
    ]
    request = TextRequest(
        title="长篇养老服务标准",
        text="\n".join(item.text for item in source_segments),
        segments=source_segments,
    )
    chunks = provider._fact_chunk_requests(request)
    assert len(chunks) == 3
    assert {segment.segment_id for chunk in chunks for segment in chunk.segments} == {
        1, 2, 3, 4, 5
    }
    assert all(chunk.text for chunk in chunks)
