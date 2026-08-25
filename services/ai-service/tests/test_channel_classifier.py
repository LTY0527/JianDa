from app.channel_classifier import suggest_publish_channel
from app.models import TextRequest


def request(title: str, text: str, content_kind: str | None = None) -> TextRequest:
    return TextRequest(title=title, text=text, content_kind=content_kind)


def test_meal_channel_wins_over_general_elderly_vocabulary() -> None:
    suggestion = suggest_publish_channel(request(
        "养老助餐卡年度核验说明",
        "老年居民可到社区食堂办理助餐卡核验。",
        "ELDERLY_SERVICE",
    ))

    assert suggestion.channel == "MEALS"
    assert suggestion.confidence >= 0.58
    assert "助餐" in suggestion.reason


def test_health_and_fraud_documents_receive_distinct_suggestions() -> None:
    health = suggest_publish_channel(request(
        "社区流感疫苗接种登记说明",
        "居民可登记流感疫苗接种，接种前请咨询医务人员。",
        "HEALTH_EDUCATION",
    ))
    fraud = suggest_publish_channel(request(
        "反诈咨询站巡回安排",
        "谨防电信网络诈骗，不要透露短信验证码。",
        "ANTI_FRAUD",
    ))

    assert health.channel == "HEALTH"
    assert fraud.channel == "FRAUD"
    assert health.channel != fraud.channel


def test_unknown_document_is_low_confidence_and_requires_human_review() -> None:
    suggestion = suggest_publish_channel(request("情况说明", "这是正文。"))

    assert suggestion.channel == "COMMUNITY"
    assert suggestion.confidence < 0.5
    assert "管理员" in suggestion.reason


def test_suggestion_uses_only_current_document_text() -> None:
    activity = suggest_publish_channel(request(
        "银龄数字生活公益辅导活动报名通知",
        "本次课程面向居民，报名后参加现场辅导。",
    ))
    service = suggest_publish_channel(request(
        "身份证到期换领分时办理提示",
        "请携带证件到窗口办理换领。",
    ))

    assert activity.channel == "ACTIVITY"
    assert service.channel == "SERVICES"
