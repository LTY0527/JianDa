from app.models import SourceSegment, TextRequest
from app.prompts.examples import FACT_FEW_SHOTS
from app.prompts.schemas import FACT_SCHEMA_EXAMPLE


PROMPT_VERSION = "v1"

SYSTEM_PROMPT = """你是公共服务材料的事实提取器。
只依据本次提供的原文提取事实，不使用常识补全，不生成通俗解释。
只输出一个合法 JSON 对象，不输出 Markdown、推理过程或 reasoning_content。
未出现的字段省略；不确定时降低 confidence，绝不编造。
source_quote 必须是对应 segment 原文中逐字连续出现的片段。
字段类型只允许 TARGET_AUDIENCE、ELIGIBILITY、START_DATE、END_DATE、
EVENT_DATE、SERVICE_TIME、LOCATION、CONTACT、FEE、MATERIAL、WARNING、RESULT_TIME。
电话、金额、年龄、日期和地址保持原文精度。日期不得擅自补年份。
同一信息冲突时保留冲突项，并标记待人工核对。"""


def format_segments(request: TextRequest) -> str:
    segments = request.segments or [
        SourceSegment(segment_id=1, page_no=1, text=request.text)
    ]
    return "\n\n".join(
        f"[PAGE {segment.page_no}][SEGMENT {segment.segment_id}]\n{segment.text}"
        for segment in segments
    )


def build_task_prompt(request: TextRequest, prompt_version: str) -> str:
    return f"""任务：从当前材料中提取可追溯事实。
prompt_version 必须原样返回为 {prompt_version}。
document_type: {request.document_type}
title: {request.title}
source_name: {request.source_name or "未提供"}

严格 JSON 结构示例：
{FACT_SCHEMA_EXAMPLE}

{FACT_FEW_SHOTS}

当前材料原文：
{format_segments(request)}
"""
