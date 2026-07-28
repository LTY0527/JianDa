import json

from app.models import SourceSegment, TextRequest


PROMPT_VERSION = "v1.1"
SCHEMA_VERSION = "1.1"

SYSTEM_PROMPT = """你是公共服务材料事实提取器。只输出合法 JSON，不输出解释。
所有事实必须来自本次原文；未出现则返回空数组或省略扁平字段，严禁推测。
每个结构化条目的 source_quote 必须是对应 segment 中逐字连续原文。
日期、时间、金额、电话、相对期限、否定条件和更正信息必须保持原文精度。"""


def _segments(request: TextRequest) -> str:
    segments = request.segments or [
        SourceSegment(segment_id=1, page_no=1, text=request.text)
    ]
    return "\n\n".join(
        f"[P{item.page_no} S{item.segment_id}]\n{item.text}" for item in segments
    )


def build_task_prompt(request: TextRequest, prompt_version: str) -> str:
    first_segment = (
        request.segments[0]
        if request.segments
        else SourceSegment(segment_id=1, page_no=1, text=request.text)
    )
    shape = {
        "prompt_version": prompt_version,
        "fields": [
            {
                "field_type": "LOCATION",
                "label": "办理地点",
                "value": "原文值",
                "source_quote": "连续原文",
                "page_no": first_segment.page_no,
                "segment_id": first_segment.segment_id,
                "confidence": 0.95,
                "needs_human_review": False,
            }
        ],
        "sessions": [{
            "date": "原文日期", "time": "原文时间", "location": "原文地点",
            "source_quote": "连续原文", "page_no": first_segment.page_no,
            "segment_id": first_segment.segment_id, "needs_human_review": False,
        }],
        "audience_rules": {
            "audience": [{
                "value": "原文人群", "source_quote": "连续原文",
                "page_no": first_segment.page_no,
                "segment_id": first_segment.segment_id,
                "needs_human_review": False,
            }],
            "conditions": [{
                "value": "原文条件", "source_quote": "连续原文",
                "page_no": first_segment.page_no,
                "segment_id": first_segment.segment_id,
                "needs_human_review": False,
            }],
        },
        "service_schedule": {
            "service_windows": [{
                "days": ["星期"], "dates": ["日期"], "time_ranges": ["时间段"],
                "location": None, "unavailable_note": None,
                "source_quote": "连续原文", "page_no": first_segment.page_no,
                "segment_id": first_segment.segment_id,
                "needs_human_review": False,
            }],
            "closure_rules": [{
                "value": "停办规则", "source_quote": "连续原文",
                "page_no": first_segment.page_no,
                "segment_id": first_segment.segment_id,
                "needs_human_review": False,
            }],
        },
        "conditional_materials": [{
            "applicable_to": "适用人群", "required": ["必需材料"],
            "optional": ["可选材料"], "source_quote": "连续原文",
            "page_no": first_segment.page_no,
            "segment_id": first_segment.segment_id,
            "needs_human_review": False,
        }],
        "fees": [{
            "fee_type": "费用类型", "amount": None, "rule": None,
            "payment_methods": ["支付方式"], "source_quote": "连续原文",
            "page_no": first_segment.page_no,
            "segment_id": first_segment.segment_id,
            "needs_human_review": False,
        }],
        "result_delivery": [{
            "method": "领取方式", "optional": False, "available_after": None,
            "location": None, "fee_rule": None, "source_quote": "连续原文",
            "page_no": first_segment.page_no,
            "segment_id": first_segment.segment_id,
            "needs_human_review": False,
        }],
        "deadline_rules": [{
            "rule_type": "RELATIVE_PERIOD", "value": "原文期限",
            "channel": None, "source_quote": "连续原文",
            "page_no": first_segment.page_no,
            "segment_id": first_segment.segment_id,
            "needs_human_review": False,
        }],
        "amendments": [{
            "original_information": "原信息", "corrected_information": "更正信息",
            "effective_priority": "生效优先级", "supersedes": ["被替代内容"],
            "source_quote": "连续原文", "page_no": first_segment.page_no,
            "segment_id": first_segment.segment_id,
            "needs_human_review": True,
        }],
    }
    return f"""提取公共服务事实。prompt_version 必须为 {prompt_version}。
document_type={request.document_type}
title={request.title}
source_name={request.source_name or "未提供"}

JSON 键结构示例（每个数组中的对象仅表示键和类型，不是材料事实；不得照抄示例值。
原文没有对应事实时，该数组必须返回 []）：
{json.dumps(shape, ensure_ascii=False, separators=(",", ":"))}

规则：
1. fields 保持 v1 字段兼容：TARGET_AUDIENCE、ELIGIBILITY、START_DATE、END_DATE、
EVENT_DATE、SERVICE_TIME、LOCATION、CONTACT、FEE、MATERIAL、WARNING、RESULT_TIME。
2. audience_rules 分开人群与办理/健康条件，内容不得重复。
3. service_windows 将同一原文中的星期或日期、时间段、地点和不开放说明作为一组；
closure_rules 单独保存节假日、停办等规则。不得按数组下标配对。
4. conditional_materials 按适用人群区分 required 和 optional。“可携带”“自愿携带”只能进入 optional。
5. fees 分开 fee_type、已知 amount、未知金额 rule 和 payment_methods；原文无金额时 amount=null。
6. result_delivery 区分窗口领取、邮寄等方式；自愿邮寄 optional=true，邮费规则原样保存。
7. deadline_rules.rule_type 只允许 FIXED_DATE、RELATIVE_PERIOD、CAPACITY_LIMIT、
NO_FIXED_DATE、CHANNEL_SPECIFIC。相对期限不得换算成日历日期，多渠道分别保存。
8. amendments 保存原信息、更正信息、生效优先级和被替代内容；后页明确更正优先，
仍保留双方原文并设置 needs_human_review。
9. sessions 仅用于明确成组的活动场次。多地点逐场保存；原文说无需材料时不得生成身份证要求。
10. 所有结构条目都必须包含 source_quote、page_no、segment_id、needs_human_review。
11. 同类信息逐条覆盖，不限制数量；不得添加预约、线上办理、加急、固定截止日期等原文没有的内容。

原文：
{_segments(request)}
"""
