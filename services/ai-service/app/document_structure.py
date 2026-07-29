import re
from dataclasses import dataclass

from app.models import DocumentOutlineItem, SourceSegment, TypeSpecificFact


DOCUMENT_KINDS = (
    "SERVICE_GUIDE",
    "ACTIVITY_NOTICE",
    "POLICY_DOCUMENT",
    "STANDARD_SPECIFICATION",
    "HEALTH_EDUCATION",
    "ANTI_FRAUD",
    "ELDERLY_SERVICE",
    "NEWS_ARTICLE",
    "GENERAL_PUBLIC_SERVICE",
)

_KIND_SIGNALS: dict[str, tuple[str, ...]] = {
    "STANDARD_SPECIFICATION": (
        "标准编号", "本标准规定", "规范性引用文件", "术语和定义",
        "基本要求", "服务质量评价", "附录",
    ),
    "POLICY_DOCUMENT": (
        "指导意见", "实施意见", "政策措施", "职责分工", "监督管理",
        "本通知自", "各有关单位",
    ),
    "HEALTH_EDUCATION": (
        "健康提示", "危险因素", "预防", "症状", "及时就医",
        "健康教育", "不适症状",
    ),
    "ANTI_FRAUD": (
        "诈骗", "反诈", "验证码", "转账", "陌生链接", "报警",
    ),
    "ACTIVITY_NOTICE": (
        "活动时间", "活动地点", "报名时间", "报名方式", "每场限",
        "活动安排",
    ),
    "SERVICE_GUIDE": (
        "办理条件", "申请材料", "办理流程", "办理地点", "受理时间",
        "申请人",
    ),
    "ELDERLY_SERVICE": (
        "养老服务", "老年人", "助餐", "养老机构", "居家养老",
    ),
    "NEWS_ARTICLE": (
        "记者", "新华社", "本报讯", "日电", "消息",
    ),
}

_HEADING = re.compile(
    r"^(?:第[一二三四五六七八九十百]+[章节]|"
    r"[一二三四五六七八九十]+[、.]|"
    r"\d+(?:\.\d+){0,3}\s+|"
    r"(?:范围|术语和定义|规范性引用文件|基本要求|服务内容|"
    r"服务流程|人员要求|场所要求|风险管理|质量评价|附录))"
)


@dataclass(frozen=True)
class SectionChunk:
    title: str
    text: str
    page_no: int
    segment_ids: tuple[int, ...]


def detect_document_kind(
    title: str,
    text: str,
    source_name: str = "",
    declared_kind: str | None = None,
) -> str:
    if declared_kind in DOCUMENT_KINDS:
        return declared_kind
    corpus = f"{title}\n{source_name}\n{text}"
    scores = {
        kind: sum(corpus.count(signal) for signal in signals)
        for kind, signals in _KIND_SIGNALS.items()
    }
    # A title alone must not decide the type: require at least one matching
    # signal in the body unless the caller supplied a reviewed kind.
    body_scores = {
        kind: sum(text.count(signal) for signal in signals)
        for kind, signals in _KIND_SIGNALS.items()
    }
    body_signal_variety = {
        kind: sum(1 for signal in signals if signal in text)
        for kind, signals in _KIND_SIGNALS.items()
    }
    candidates = [
        (
            body_signal_variety[kind] * 8
            + body_scores[kind] * 2
            + scores[kind],
            kind,
        )
        for kind in scores
        if body_scores[kind] > 0
    ]
    if not candidates:
        return "GENERAL_PUBLIC_SERVICE"
    return max(candidates)[1]


def split_document_sections(
    segments: list[SourceSegment],
    *,
    max_chars: int = 6000,
) -> list[SectionChunk]:
    source = segments or [SourceSegment(segment_id=1, page_no=1, text="")]
    sections: list[SectionChunk] = []
    current_title = "正文"
    current_parts: list[str] = []
    current_ids: list[int] = []
    current_page = source[0].page_no

    def flush() -> None:
        nonlocal current_parts, current_ids, current_page
        text = "\n".join(part for part in current_parts if part).strip()
        if not text:
            return
        for index, part in enumerate(_bounded_parts(text, max_chars)):
            suffix = f"（{index + 1}）" if len(text) > max_chars else ""
            sections.append(
                SectionChunk(
                    title=f"{current_title}{suffix}",
                    text=part,
                    page_no=current_page,
                    segment_ids=tuple(dict.fromkeys(current_ids)),
                )
            )
        current_parts = []
        current_ids = []

    for segment in source:
        paragraphs = [item.strip() for item in re.split(r"\r?\n+", segment.text) if item.strip()]
        for paragraph in paragraphs:
            if _is_heading(paragraph):
                flush()
                current_title = paragraph[:80]
                current_page = segment.page_no
            current_parts.append(paragraph)
            current_ids.append(segment.segment_id)
    flush()
    return sections


def build_document_outline(sections: list[SectionChunk]) -> list[DocumentOutlineItem]:
    return [
        DocumentOutlineItem(
            title=section.title,
            page_no=section.page_no,
            segment_ids=list(section.segment_ids),
            summary=section.text[:120],
        )
        for section in sections
    ]


def build_type_specific_facts(
    document_kind: str,
    sections: list[SectionChunk],
) -> list[TypeSpecificFact]:
    labels = {
        "STANDARD_SPECIFICATION": (
            "范围", "术语", "基本要求", "服务内容", "服务流程",
            "人员要求", "场所要求", "风险", "质量", "附录",
        ),
        "POLICY_DOCUMENT": (
            "政策目标", "适用范围", "核心措施", "职责分工",
            "实施时间", "监督",
        ),
        "HEALTH_EDUCATION": (
            "适用人群", "主要结论", "危险因素", "建议",
            "异常信号", "及时就医", "常见误区",
        ),
    }.get(document_kind, ())
    result: list[TypeSpecificFact] = []
    for section in sections:
        label = next(
            (candidate for candidate in labels if candidate in section.title),
            None,
        )
        if not label or not section.segment_ids:
            continue
        quote = section.text[: min(240, len(section.text))]
        result.append(
            TypeSpecificFact(
                label=label,
                value=quote,
                source_quote=quote,
                page_no=section.page_no,
                segment_id=section.segment_ids[0],
                confidence=0.9,
                needs_human_review=False,
            )
        )
    return result


def _is_heading(text: str) -> bool:
    return len(text) <= 80 and bool(_HEADING.match(text))


def _bounded_parts(text: str, max_chars: int) -> list[str]:
    if len(text) <= max_chars:
        return [text]
    sentences = [item for item in re.split(r"(?<=[。！？；\n])", text) if item]
    parts: list[str] = []
    current = ""
    for sentence in sentences:
        if current and len(current) + len(sentence) > max_chars:
            parts.append(current.strip())
            current = ""
        if len(sentence) > max_chars:
            if current:
                parts.append(current.strip())
                current = ""
            for start in range(0, len(sentence), max_chars):
                parts.append(sentence[start : start + max_chars].strip())
        else:
            current += sentence
    if current.strip():
        parts.append(current.strip())
    return [part for part in parts if part]
