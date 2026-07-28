import re
from pathlib import Path

from app.extraction import extract_file
from app.models import MetadataPreview


TITLE_WORDS = (
    "通知",
    "告知",
    "说明",
    "指南",
    "办法",
    "公告",
    "方案",
    "细则",
)
ORG_PATTERN = re.compile(
    r"^(?!.*(?:预防接种门诊|服务窗口|综合服务台)$).{2,50}"
    r"(?:人民医院|医院|社区卫生服务中心|人民政府|委员会|管理局|服务中心)$"
)
DOCUMENT_NUMBER_PATTERN = re.compile(
    r"[\u4e00-\u9fff]{1,12}〔\d{4}〕\d{1,4}号"
)


def clean_filename_title(filename: str) -> str:
    value = Path(filename).stem
    value = re.sub(r"^简达[_\-\s]*", "", value)
    value = re.sub(r"^模拟材料\s*\d*[_\-\s]*", "", value)
    value = re.sub(r"[_\-—\s]+", " ", value).strip()
    return value


def detect_metadata(path: Path, filename: str) -> tuple[MetadataPreview, str]:
    extracted = extract_file(path)
    selected = [
        segment
        for segment in extracted.segments
        if segment.page_no in {1, extracted.page_count}
    ]
    preview_text = "\n".join(segment.text for segment in selected)
    page_texts = {segment.page_no: segment.text for segment in selected}
    return (
        detect_metadata_text(
            preview_text, filename, extracted.page_count, page_texts
        ),
        preview_text,
    )


def detect_metadata_text(
    preview_text: str,
    filename: str,
    page_count: int = 1,
    page_texts: dict[int, str] | None = None,
) -> MetadataPreview:
    lines = [
        line.strip()
        for line in preview_text.splitlines()
        if line.strip() and not line.startswith("第 ")
    ]
    filename_title = clean_filename_title(filename)
    document_number = next(
        (
            match.group(0)
            for line in lines
            if (match := DOCUMENT_NUMBER_PATTERN.search(line))
        ),
        "",
    )
    title_candidates = [
        line
        for line in lines
        if 4 <= len(line) <= 80
        and any(word in line for word in TITLE_WORDS)
        and "模拟材料" not in line
    ]
    title = title_candidates[0] if title_candidates else filename_title
    title_index = lines.index(title) if title in lines else len(lines)

    publisher_lines = [
        (index, line)
        for index, line in enumerate(lines)
        if ORG_PATTERN.fullmatch(line)
    ]
    preferred = [
        item for item in publisher_lines if item[0] <= title_index + 2
    ] or publisher_lines
    unique_sources = list(dict.fromkeys(line for _, line in preferred))
    if len(unique_sources) == 1:
        source_name = unique_sources[0]
        authority = "DOCUMENT_EVIDENCE"
        confidence = 0.96
        source_index = next(index for index, line in preferred if line == source_name)
        evidence_type = "HEADER" if source_index <= title_index else "SIGNATURE"
        warnings: list[str] = []
    elif len(unique_sources) > 1:
        source_name = ""
        authority = "CONFLICT"
        confidence = 0.45
        evidence_type = "NONE"
        warnings = ["材料中出现多个发布机构候选，请人工确认内容来源。"]
    else:
        source_name = ""
        authority = "UNCONFIRMED"
        confidence = 0.3 if title else 0.1
        evidence_type = "FILENAME" if filename_title else "NONE"
        warnings = ["材料内部未找到明确发布机构证据，请人工填写内容来源。"]

    source_type = classify_source_type(source_name)
    evidence_pages = page_texts or {1: preview_text}
    page_no = next(
        (
            number
            for number, text in evidence_pages.items()
            if source_name and source_name in text
        ),
        1,
    )
    return MetadataPreview(
        title=title,
        source_name=source_name,
        document_number=document_number,
        source_type=source_type,
        authority_status=authority,
        confidence=confidence,
        evidence_quote=source_name,
        evidence_type=evidence_type,
        page_no=min(page_no, max(page_count, 1)),
        warnings=warnings,
    )


def classify_source_type(source_name: str) -> str:
    if "社区卫生服务中心" in source_name:
        return "基层医疗卫生机构"
    if "医院" in source_name:
        return "医疗机构"
    if "政府" in source_name or "委员会" in source_name or "管理局" in source_name:
        return "政府部门"
    return ""
