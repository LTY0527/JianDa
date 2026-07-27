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

提取前必须逐项检查以下事实，不得因为已经找到若干字段就提前停止：
1. 谁受到通知影响、谁可以办理或参加（TARGET_AUDIENCE、ELIGIBILITY）。
2. 开始、截止、活动、调整前后日期和服务时间（START_DATE、END_DATE、EVENT_DATE、SERVICE_TIME、RESULT_TIME）。
3. 地点、电话、费用、所需证件或材料、风险提示（LOCATION、CONTACT、FEE、MATERIAL、WARNING）。
4. 原文存在多组“原日期调整为新日期”时，每一组分别输出一个 EVENT_DATE；value 同时保留原日期和新日期。
5. “持某证件到某处办理”中的证件属于 MATERIAL，受理时段属于 SERVICE_TIME。
6. 原文未出现费用时不得输出 FEE；不得为了凑齐清单编造字段。
7. 不限制 fields 数量。同类事实出现多次时逐条提取，尤其不能遗漏最后一组日期或跨换行的句子。
8. TARGET_AUDIENCE 应检查“已取得预约号的人”“受本次调整影响的人”等通知对象，即使句子中存在换行。
   TARGET_AUDIENCE 必须是患者、居民、申请人等人群，不得把科室、机构、门诊类型或服务项目当成人群。
9. SERVICE_TIME 只提取原文明示的具体日期、星期或时间段；“时段保持不变”不是可执行的服务时间，不输出。
10. value 中的所有年份、日期、时间、电话和金额必须逐字有原文依据。不得根据标题年份为未写年份的日期补年份。

输出 JSON 前再次自检：每一组调整前后日期是否都已单独输出；每一个明确时间段、办理材料、地点、电话、
通知对象和风险提示是否都已覆盖；是否误加了原文没有的费用或年份。

{FACT_FEW_SHOTS}

当前材料原文：
{format_segments(request)}
"""
