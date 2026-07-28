import json

from app.models import FactExtractionResponse, FactField, TextRequest


SYSTEM_PROMPT = """你是适老化权威资讯编辑。只能使用已验证事实，输出合法 JSON。
保持来源限定，不得把资讯冒充平台原创。健康内容不作诊断、不替代医嘱；政策新闻不制造个人申领资格。"""


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
        "plain_text": "按小标题组织的适老化正文",
        "steps": [],
        "warnings": [],
        "term_explanations": {},
        "audio_script": "适合朗读的短句",
    }
    return f"""根据已验证事实生成适老化资讯。prompt_version 必须为 {prompt_version}。
content_kind={request.content_kind or "GENERAL_NEWS"}

已验证事实：
{json.dumps(verified, ensure_ascii=False, separators=(",", ":"))}

输出结构：
{json.dumps(output, ensure_ascii=False, separators=(",", ":"))}

要求：
1. summary 恰好三句，分别说明“发生了什么、与谁有关、普通用户要做什么或无需做什么”。
2. plain_text 使用清晰小标题组织：与我有什么关系、重点信息、应该怎么做、重要提醒。
3. HEALTH_EDUCATION 还要说明不适症状、何时建议就医，并以“内容仅供健康科普提示，不能替代医生诊疗”结尾。
4. POLICY_NEWS 要说明全国/地方范围、是否需要个人办理、重要时间；新闻统计不得改写为个人金额。
5. ANTI_FRAUD 要说明危险信号、不要做什么、遇到后如何处理。
6. COMMUNITY_SERVICE 原文没有参加方式时明确写“原文未提供报名方式”，steps 返回 []。
7. steps 只用于原文确有明确行动顺序时，禁止补造入口、电话、日期、资格和承诺。
8. warnings 去重；audio_script 必须适合慢速朗读。
"""
