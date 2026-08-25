import json

from app.models import FactExtractionResponse, FactField, TextRequest


SYSTEM_PROMPT = """你是适老化权威资讯编辑。只能使用已经验证且可追溯的事实，输出合法 JSON。
保持来源限定，不得把资讯冒充平台原创。健康内容不作诊断、不替代医嘱；政策新闻不得制造个人申领资格。
每个行动、关键事实和问答必须带有本次原文中的连续 source_quote，并指向真实 segment_id。
action_checklist.priority 只能是“立即”、“近期”或“了解即可”；
scope.national_or_local 只能是“全国”、“地方”、“具体机构”或“原文未说明”。"""


def build_task_prompt(
    request: TextRequest,
    facts: list[FactField],
    structured: FactExtractionResponse,
    prompt_version: str,
) -> str:
    verified = [item.model_dump() for item in facts]
    output = {
        "prompt_version": prompt_version,
        "summary": ["三句话看懂之一", "三句话看懂之二", "三句话看懂之三"],
        "quick_summary": ["三句话看懂之一", "三句话看懂之二", "三句话看懂之三"],
        "why_it_matters": ["这件事与普通用户或老年用户的关系"],
        "action_checklist": [{
            "action": "可以采取的行动",
            "priority": "立即",
            "source_quote": "连续原文",
            "segment_id": 1,
        }],
        "key_facts": [{
            "label": "关键数字或事实",
            "value": "原文明确的值",
            "source_quote": "连续原文",
            "segment_id": 1,
        }],
        "common_mistakes": ["容易误解或做错的地方"],
        "faq": [{
            "question": "常见问题",
            "answer": "只依据原文的回答",
            "source_quote": "连续原文",
            "segment_id": 1,
        }],
        "terms": {},
        "scope": {
            "national_or_local": "原文未说明",
            "applicable_region": None,
            "needs_personal_action": None,
        },
        "uncertainties": ["原文没有明确、需要人工确认的内容"],
        "plain_text": "按小标题组织的适老化正文",
        "steps": [],
        "warnings": [],
        "term_explanations": {},
        "audio_script": "适合朗读的短句",
    }
    segments = [
        {
            "segment_id": item.segment_id,
            "page_no": item.page_no,
            "text": item.text,
        }
        for item in request.segments
    ]
    return f"""根据已验证事实生成适老化资讯。prompt_version 必须为 {prompt_version}。
content_kind={request.content_kind or "GENERAL_NEWS"}

已验证事实：
{json.dumps(verified, ensure_ascii=False, separators=(",", ":"))}

可引用原文段落：
{json.dumps(segments, ensure_ascii=False, separators=(",", ":"))}

输出结构：
{json.dumps(output, ensure_ascii=False, separators=(",", ":"))}

要求：
1. summary 和 quick_summary 恰好三句，分别说明发生了什么、与谁有关、用户要做什么或无需做什么。
2. why_it_matters 只写原文能够支持的关系，不得推断个人身份、资格或待遇。
3. action_checklist、key_facts、faq 每项必须引用真实 segment_id 和逐字连续 source_quote；原文没有行动时 action_checklist 返回 []。
4. common_mistakes 只能来自原文中的否定、限制或纠错信息；没有则返回 []。
5. scope 区分全国、地方、具体机构；无法判断时使用“原文未说明”。uncertainties 记录原文未明确的关键点。
6. plain_text 使用清晰小标题组织：与我有什么关系、重点信息、应该怎么做、重要提醒，不得包含 Markdown 标记。
7. HEALTH_EDUCATION 只给生活建议、需注意人群和建议就医情形，不得诊断、推荐处方药或承诺疗效。
8. POLICY_NEWS 说明全国或地方范围、影响人群、是否需要个人申请及关键日期；总体数据不得写成个人待遇。
9. ANTI_FRAUD 说明骗局类型、话术、危险信号、不要做什么和正确应对；不得虚构电话。
10. COMMUNITY_SERVICE 说明服务内容、地区、对象和参与方式；原文没有报名方式时不得补写。
11. steps 只用于原文确有明确行动顺序时；不得补造入口、电话、日期、资格和承诺。
12. warnings 去重；audio_script 使用适合慢速朗读的短句。"""
