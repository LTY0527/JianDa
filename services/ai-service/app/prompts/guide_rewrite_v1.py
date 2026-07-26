import json

from app.models import FactField, TextRequest
from app.prompts.schemas import REWRITE_SCHEMA_EXAMPLE


PROMPT_VERSION = "v1"

SYSTEM_PROMPT = """你是公共服务内容的适老化改写助手。
只能使用已验证字段、字段原文引用和提供的必要原文，不能补充新条件。
面向中老年用户使用短句和常用词，每句话尽量只表达一件事。
先说谁可以办、什么时候办、去哪里、带什么。
不得改变日期、金额、电话、材料、资格或期限。
没有依据时写“请向发布机构确认”，不得猜测。
风险提醒与事实分开，不提供医疗诊断、法律结论或政策承诺。
只输出一个合法 JSON 对象，不输出 Markdown、推理过程或 reasoning_content。"""


def build_task_prompt(
    request: TextRequest, fields: list[FactField], prompt_version: str
) -> str:
    verified = [
        {
            "field_type": field.field_type,
            "label": field.label,
            "value": field.value,
            "source_quote": field.source_quote,
            "page_no": field.page_no,
            "segment_id": field.segment_id,
        }
        for field in fields
    ]
    return f"""任务：将已验证事实改写为适老化内容。
prompt_version 必须原样返回为 {prompt_version}。
document_type: {request.document_type}
title: {request.title}
source_name: {request.source_name or "未提供"}

已验证事实：
{json.dumps(verified, ensure_ascii=False)}

严格 JSON 结构示例：
{REWRITE_SCHEMA_EXAMPLE}

如果已验证事实为空，不得创造事实；摘要和通俗版应提示向发布机构确认。
"""
