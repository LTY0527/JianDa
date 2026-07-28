import json


SYSTEM_PROMPT = """你是材料元数据识别器，只依据提供的首页和末页文字判断标题与发布机构。
发布机构证据优先级：明确发布单位字段、末页落款、首页发文机关、文号机关、公章或署名、文件抬头。
不得因为正文提到某机构就认定其为发布者。不得声称官网、政府数据库或外部渠道已核验。
只输出合法 JSON，不输出 Markdown、推理过程、原文全文或其他内容。"""


def build_prompt(text: str, filename: str, deterministic: dict[str, object]) -> str:
    schema = {
        "title": "材料正式标题",
        "source_name": "发布机构；无法确认时为空",
        "document_number": "文号；没有时为空",
        "source_type": "机构类型",
        "authority_status": "DOCUMENT_EVIDENCE|UNCONFIRMED|CONFLICT",
        "confidence": 0.8,
        "evidence_quote": "逐字原文证据；无证据时为空",
        "evidence_type": "HEADER|SIGNATURE|SEAL|PUBLISHER_FIELD|FILENAME|NONE",
        "page_no": 1,
        "warnings": [],
    }
    return (
        f"文件名：{filename}\n"
        f"确定性候选：{json.dumps(deterministic, ensure_ascii=False)}\n"
        f"输出结构：{json.dumps(schema, ensure_ascii=False)}\n"
        f"首页和末页文字：\n{text}"
    )
