import json

from app.models import FactField, FactExtractionResponse, TextRequest


PROMPT_VERSION = "v1.1"

SYSTEM_PROMPT = """你是公共服务材料通俗化编辑。只能使用已验证事实。
输出合法 JSON，不输出解释。步骤不得重复场次，不得增加预约、线上办理、加急或资格承诺。"""


def build_task_prompt(
    request: TextRequest,
    facts: list[FactField],
    structured: FactExtractionResponse,
    prompt_version: str,
) -> str:
    verified = {
        "fields": [item.model_dump() for item in facts],
        "sessions": [item.model_dump() for item in structured.sessions],
        "audience_rules": structured.audience_rules.model_dump(),
        "service_schedule": structured.service_schedule.model_dump(),
        "conditional_materials": [
            item.model_dump() for item in structured.conditional_materials
        ],
        "fees": [item.model_dump() for item in structured.fees],
        "result_delivery": [item.model_dump() for item in structured.result_delivery],
        "deadline_rules": [item.model_dump() for item in structured.deadline_rules],
        "amendments": [item.model_dump() for item in structured.amendments],
    }
    output = {
        "prompt_version": prompt_version,
        "summary": ["谁能办或受影响。", "何时何地办理。", "材料、费用或领取方式。"],
        "plain_text": "通俗说明",
        "steps": [{"order": 1, "title": "核对条件", "description": "仅用已验证事实。"}],
        "warnings": [],
        "term_explanations": {},
        "audio_script": "适合朗读的短句。",
    }
    return f"""prompt_version 必须为 {prompt_version}。
按事实生成摘要、说明和可执行步骤。办理步骤应依次覆盖：确认对象/条件、按人群准备材料、
查看开放时段、遵守截止规则、费用支付、领取或邮寄；只输出实际存在的环节。
多个场次或分时窗口不得压平或错配。相对期限保持相对表达。可选材料不得写成必需。
更正信息以 amendments 中的更正项优先，同时明确它替代的旧信息。

已验证事实：
{json.dumps(verified, ensure_ascii=False, separators=(",", ":"))}

输出结构：
{json.dumps(output, ensure_ascii=False, separators=(",", ":"))}
"""
