import json

from app.models import SourceSegment, TextRequest


SYSTEM_PROMPT = """你是权威公共信息文章事实提取器。只输出合法 JSON。
所有事实必须来自本次文章正文，source_quote 必须是对应段落中的逐字连续原文。
新闻统计、总投入和总受益人数不得改写为个人可领取金额；健康文章不得生成诊断或药物调整建议。
原文没有报名或办理入口时不得生成。"""


def build_task_prompt(request: TextRequest, prompt_version: str) -> str:
    segments = request.segments or [
        SourceSegment(segment_id=1, page_no=1, text=request.text)
    ]
    source = "\n\n".join(
        f"[P{item.page_no} S{item.segment_id}]\n{item.text}" for item in segments
    )
    first = segments[0]
    shape = {
        "prompt_version": prompt_version,
        "fields": [{
            "field_type": "WARNING",
            "label": "重要提醒",
            "value": "逐字事实的通俗值",
            "source_quote": "连续原文",
            "page_no": first.page_no,
            "segment_id": first.segment_id,
            "confidence": 0.95,
            "needs_human_review": False,
        }],
        "sessions": [],
        "audience_rules": {"audience": [], "conditions": []},
        "service_schedule": {"service_windows": [], "closure_rules": []},
        "conditional_materials": [],
        "fees": [],
        "result_delivery": [],
        "deadline_rules": [],
        "amendments": [],
    }
    return f"""提取文章中可追溯的重要事实。prompt_version 必须为 {prompt_version}。
content_kind={request.content_kind or "GENERAL_NEWS"}
title={request.title}
source_name={request.source_name or "未提供"}

输出结构：
{json.dumps(shape, ensure_ascii=False, separators=(",", ":"))}

fields 仅使用兼容字段：
- TARGET_AUDIENCE：明确涉及的人群；
- ELIGIBILITY：明确条件或政策范围；
- START_DATE/END_DATE/EVENT_DATE/SERVICE_TIME：原文明确时间；
- LOCATION/CONTACT/FEE/MATERIAL/RESULT_TIME：原文明确信息；
- WARNING：健康风险、反诈信号、禁止事项和来源限定。

规则：
1. HEALTH_EDUCATION：只提取健康知识、建议、不建议事项、警惕症状和就医时机；不得诊断、推荐处方药、改变药量或替代医生意见。
2. POLICY_NEWS：区分政策事实与新闻统计；不得把总金额、总受益人数写成个人可申领金额。
3. ANTI_FRAUD：提取骗局话术、危险信号、不要做什么和官方求助渠道。
4. COMMUNITY_SERVICE：没有报名方法时不得生成 CONTACT、步骤或报名入口。
5. GENERAL_NEWS：只保留原文明确且与普通用户有关的事实。
6. 原文没有的结构必须返回空数组；每条必须引用真实 segment_id。

文章正文：
{source}
"""
