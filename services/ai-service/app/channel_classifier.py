from __future__ import annotations

from dataclasses import dataclass

from app.models import PublishChannel, TextRequest


@dataclass(frozen=True)
class ChannelSuggestion:
    channel: PublishChannel
    confidence: float
    reason: str


_CONTENT_KIND_CHANNELS: dict[str, PublishChannel] = {
    "HEALTH_EDUCATION": "HEALTH",
    "ANTI_FRAUD": "FRAUD",
    "ELDERLY_SERVICE": "ELDERLY",
    "ACTIVITY_NOTICE": "ACTIVITY",
    "SERVICE_GUIDE": "SERVICES",
    "SERVICE_NOTICE": "SERVICES",
    "COMMUNITY_SERVICE": "COMMUNITY",
}

_KEYWORDS: dict[PublishChannel, tuple[str, ...]] = {
    "HEALTH": (
        "健康", "医疗", "医院", "门诊", "体检", "疫苗", "接种", "流感",
        "疾病", "就医", "卫生", "营养", "康复", "用药",
    ),
    "ELDERLY": (
        "养老", "老年", "老龄", "银龄", "高龄", "长者", "助老", "退休",
        "护理", "适老化",
    ),
    "MEALS": (
        "助餐", "用餐", "供餐", "餐卡", "食堂", "长者食堂", "送餐", "膳食",
    ),
    "SERVICES": (
        "办理", "申请", "换领", "补办", "预约", "登记", "证件", "材料清单",
        "服务指南", "受理", "窗口", "资格", "申领",
    ),
    "FRAUD": (
        "反诈", "诈骗", "骗局", "防骗", "电信网络诈骗", "可疑来电", "转账",
        "验证码", "资金安全",
    ),
    "ACTIVITY": (
        "活动", "报名", "讲座", "课堂", "课程", "培训", "辅导", "展览",
        "演出", "招募", "志愿者",
    ),
    "COMMUNITY": (
        "社区", "街道", "居委", "村委", "小区", "社区服务", "便民", "镇",
        "邻里", "公告", "通知",
    ),
}


def suggest_publish_channel(request: TextRequest) -> ChannelSuggestion:
    """Return a deterministic, reviewable channel suggestion for any provider.

    This classification is deliberately independent from the generative model so a
    malformed model response cannot silently erase the publishing recommendation.
    It uses only the current document metadata and extracted source text.
    """

    title = request.title.strip()
    body = request.text.strip()
    searchable = f"{title}\n{body}"
    scores: dict[PublishChannel, int] = {channel: 0 for channel in _KEYWORDS}
    matched: dict[PublishChannel, list[str]] = {channel: [] for channel in _KEYWORDS}

    mapped = _CONTENT_KIND_CHANNELS.get(request.content_kind or "")
    if mapped:
        scores[mapped] += 4

    for channel, words in _KEYWORDS.items():
        for word in words:
            if word not in searchable:
                continue
            matched[channel].append(word)
            scores[channel] += 3 if word in title else 1

    # A direct meal-service signal is narrower than the surrounding elderly or
    # community context and should not be swallowed by those broader channels.
    if matched["MEALS"]:
        scores["MEALS"] += 3

    # Meal services and anti-fraud notices are more specific than their common
    # elderly/community/activity vocabulary, so a direct match wins ties.
    specificity: tuple[PublishChannel, ...] = (
        "MEALS", "FRAUD", "HEALTH", "ELDERLY", "ACTIVITY", "SERVICES", "COMMUNITY"
    )
    best = max(specificity, key=lambda channel: (scores[channel], -specificity.index(channel)))
    best_score = scores[best]
    runner_up = max((score for channel, score in scores.items() if channel != best), default=0)

    if best_score == 0:
        return ChannelSuggestion(
            channel="COMMUNITY",
            confidence=0.35,
            reason="未识别到明确频道特征，建议由管理员根据原文确认",
        )

    evidence = list(dict.fromkeys(matched[best]))[:4]
    if mapped == best:
        basis = f"内容类型 {request.content_kind}"
        if evidence:
            basis += f"，并匹配“{'、'.join(evidence)}”"
    else:
        basis = f"标题或正文匹配“{'、'.join(evidence)}”"
    margin = max(0, best_score - runner_up)
    confidence = min(0.95, 0.58 + min(best_score, 10) * 0.025 + min(margin, 5) * 0.025)
    return ChannelSuggestion(
        channel=best,
        confidence=round(confidence, 2),
        reason=f"{basis}，建议归入{_channel_label(best)}；发布前可人工调整",
    )


def _channel_label(channel: PublishChannel) -> str:
    return {
        "HEALTH": "健康",
        "ELDERLY": "养老",
        "MEALS": "助餐",
        "SERVICES": "办事",
        "FRAUD": "防诈",
        "ACTIVITY": "活动",
        "COMMUNITY": "社区",
    }[channel]
