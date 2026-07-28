FACT_SCHEMA_EXAMPLE = """{
  "prompt_version": "v1",
  "fields": [
    {
      "field_type": "LOCATION",
      "label": "办理地点",
      "value": "青松社区服务站二楼",
      "source_quote": "办理地点为青松社区服务站二楼",
      "page_no": 1,
      "segment_id": 101,
      "confidence": 0.96
    }
  ],
  "sessions": [
    {
      "date": "2026年9月12日",
      "time": "08:00-11:30",
      "location": "青松社区卫生服务中心预防接种门诊",
      "source_quote": "2026年9月12日 08:00-11:30 青松社区卫生服务中心预防接种门诊",
      "page_no": 1,
      "segment_id": 101,
      "needs_human_review": false
    }
  ]
}"""

REWRITE_SCHEMA_EXAMPLE = """{
  "prompt_version": "v1",
  "summary": ["谁可以办理。", "什么时候、去哪里办理。", "需要携带什么。"],
  "plain_text": "只使用已验证事实写成的通俗说明。",
  "steps": [{"order": 1, "title": "确认条件", "description": "按原文核对条件。"}],
  "warnings": ["只放原文中确实存在的风险提醒。"],
  "term_explanations": {"示例术语": "只在原文语境内解释。"},
  "audio_script": "适合直接朗读的短句。"
}"""
