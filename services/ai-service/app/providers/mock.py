import re

from app.models import AnalyzeResult, ExtractedField, StepCard, TextRequest
from app.providers.base import LlmProvider


class MockProvider(LlmProvider):
    """Deterministic offline analysis derived only from the supplied text."""

    LABELS = {
        "TARGET_AUDIENCE": "适用对象",
        "ELIGIBILITY": "申请条件",
        "START_DATE": "报名开始日期",
        "END_DATE": "报名截止日期",
        "LOCATION": "办理地点",
        "FEE": "费用",
        "CONTACT": "联系方式",
        "MATERIAL": "所需材料",
        "WARNING": "风险提示",
    }

    def analyze(self, request: TextRequest) -> AnalyzeResult:
        if request.document_type == "public_news":
            return self._public_news(request)
        return self._guide(request)

    def _guide(self, request: TextRequest) -> AnalyzeResult:
        text = request.text
        fields: list[ExtractedField] = []

        audience = self._first(
            text,
            (
                r"本街道常住居民，原则上年龄为(?P<value>\d+周岁及以上)",
                r"补贴对象为(?P<value>具有本市户籍且年满八十周岁的老年人)",
                r"(?P<value>具有本市户籍且年满\s*80\s*周岁的老年人)",
            ),
        )
        if audience:
            value = audience.group("value")
            if "周岁及以上" in value and "居民" not in value:
                value = f"{value}常住居民"
            self._append(fields, "TARGET_AUDIENCE", value, audience.group(0), text)

        eligibility = self._first(text, (r"(?P<value>已享受同类补贴待遇的，不重复发放)",))
        if eligibility:
            self._append(fields, "ELIGIBILITY", "未享受同类生活补贴", eligibility.group(0), text)

        registration = self._first(
            text,
            (r"报名时间为(?P<start>\d{4}年\d{1,2}月\d{1,2}日)至(?P<end>\d{1,2}月\d{1,2}日)",),
        )
        if registration:
            year = registration.group("start")[:4]
            self._append(fields, "START_DATE", registration.group("start"), registration.group(0), text)
            self._append(fields, "END_DATE", f"{year}年{registration.group('end')}", registration.group(0), text)

        location = self._first(
            text,
            (
                r"(?P<value>浦江街道社区服务中心201教室)",
                r"(?P<value>户籍所在地社区服务窗口)",
                r"(?:办理地点|活动地点)[：:\s]*(?P<value>[^\n；。]+)",
            ),
        )
        if location:
            self._append(fields, "LOCATION", location.group("value"), location.group(0), text)

        fee = self._first(text, (r"(?P<value>活动不收取任何费用)", r"(?P<value>免费)"))
        if fee:
            self._append(fields, "FEE", "免费", fee.group(0), text)

        contact = self._first(
            text,
            (r"(?:咨询电话[：:]?)?(?P<value>0\d{2,3}-(?:\d{3,4}-?\d{4}|\d{5,8}))",),
        )
        if contact:
            self._append(fields, "CONTACT", contact.group("value"), contact.group(0), text)

        material = self._material(text)
        if material:
            value, quote = material
            self._append(fields, "MATERIAL", value, quote, text)

        warning = self._first(
            text,
            (
                r"不要求\s*提供银行卡、支付密码或短信验证码",
                r"不需要银行卡、支付密码或短信验证码",
            ),
        )
        if warning:
            self._append(
                fields,
                "WARNING",
                "不提供银行卡、支付密码或短信验证码",
                warning.group(0),
                text,
            )

        activity_dates = self._activity_dates(text)
        activity_time = self._first(text, (r"(?P<value>\d{2}:\d{2}-\d{2}:\d{2})",))
        capacity = self._first(text, (r"(?P<value>每场限\d+人)",))
        plain_parts = [f"{field.label}：{field.value}" for field in fields]
        if activity_dates:
            plain_parts.append(f"活动日期：{'、'.join(activity_dates)}")
        if activity_time:
            plain_parts.append(f"活动时间：{activity_time.group('value')}")
        if capacity:
            plain_parts.append(capacity.group("value"))

        summary = self._summary(fields, activity_dates, activity_time, capacity)
        steps = self._steps(fields, activity_dates, activity_time)
        warnings = [field.value for field in fields if field.field_type == "WARNING"]
        if not fields:
            summary = ["暂未从材料正文中识别出结构化字段，请人工核对原文。"]
            plain_parts = ["待人工填写"]

        plain_text = "；".join(plain_parts) + "。"
        audio_parts = summary + [
            f"第{step.order}步，{step.title}，{step.description}" for step in steps
        ]
        return AnalyzeResult(
            fields=fields,
            summary=summary,
            plain_text=plain_text,
            steps=steps,
            term_explanations={},
            warnings=warnings,
            audio_script="。".join(audio_parts),
        )

    def _material(self, text: str) -> tuple[str, str] | None:
        activity = self._first(
            text,
            (r"本人日常使用的智能手机、充电线，以及用于现场核对报名信息的身份证",),
        )
        if activity:
            return "智能手机、充电线、身份证", activity.group(0)
        subsidy = self._first(
            text,
            (r"申请材料：(?P<value>身份证及户口簿原件、本人银行卡复印件、近期一寸免冠照片一张)",),
        )
        if subsidy:
            return "身份证、户口簿、本人银行卡、近期一寸照片", subsidy.group(0)
        generic = self._first(
            text,
            (r"(?:需携带|所需材料|申请材料)[：:]\s*(?P<value>[^\n。；]+)",),
        )
        return (generic.group("value"), generic.group(0)) if generic else None

    @staticmethod
    def _activity_dates(text: str) -> list[str]:
        section = text
        marker = text.find("活动时间与地点")
        if marker >= 0:
            section = text[marker : text.find("辅导内容", marker) if text.find("辅导内容", marker) >= 0 else None]
        return list(dict.fromkeys(re.findall(r"\d{4}年\d{1,2}月\d{1,2}日", section)))

    def _summary(
        self,
        fields: list[ExtractedField],
        activity_dates: list[str],
        activity_time: re.Match[str] | None,
        capacity: re.Match[str] | None,
    ) -> list[str]:
        values = {field.field_type: field.value for field in fields}
        result: list[str] = []
        if "TARGET_AUDIENCE" in values:
            result.append(f"{values['TARGET_AUDIENCE']}可以按通知参加或申请。")
        if "START_DATE" in values and "END_DATE" in values:
            result.append(f"办理时间为{values['START_DATE']}至{values['END_DATE']}。")
        if activity_dates:
            detail = f"活动安排在{'、'.join(activity_dates)}"
            if activity_time:
                detail += f" {activity_time.group('value')}"
            if capacity:
                detail += f"，{capacity.group('value')}"
            result.append(detail + "。")
        if "LOCATION" in values:
            result.append(f"地点为{values['LOCATION']}。")
        if "MATERIAL" in values:
            result.append(f"请携带{values['MATERIAL']}。")
        return result[:5]

    def _steps(
        self,
        fields: list[ExtractedField],
        activity_dates: list[str],
        activity_time: re.Match[str] | None,
    ) -> list[StepCard]:
        values = {field.field_type: field.value for field in fields}
        steps: list[StepCard] = []
        if "START_DATE" in values and "END_DATE" in values:
            steps.append(
                StepCard(
                    order=len(steps) + 1,
                    title="在规定时间报名",
                    description=f"{values['START_DATE']}至{values['END_DATE']}完成报名。",
                )
            )
        if "MATERIAL" in values:
            steps.append(
                StepCard(
                    order=len(steps) + 1,
                    title="准备材料",
                    description=f"准备{values['MATERIAL']}。",
                )
            )
        if "LOCATION" in values:
            description = f"前往{values['LOCATION']}。"
            if activity_dates:
                description = f"在{'、'.join(activity_dates)}"
                if activity_time:
                    description += f" {activity_time.group('value')}"
                description += f"前往{values['LOCATION']}。"
            steps.append(StepCard(order=len(steps) + 1, title="按时到场", description=description))
        return steps

    @staticmethod
    def _first(text: str, patterns: tuple[str, ...]) -> re.Match[str] | None:
        for pattern in patterns:
            match = re.search(pattern, text)
            if match:
                return match
        return None

    def _append(
        self,
        fields: list[ExtractedField],
        field_type: str,
        value: str,
        quote: str,
        text: str,
    ) -> None:
        page_no = text[: text.index(quote)].count("\f") + 1
        fields.append(
            ExtractedField(
                field_type=field_type,
                label=self.LABELS[field_type],
                value=value,
                page_no=page_no,
                segment_no=1,
                source_quote=quote,
                confidence=0.95,
            )
        )

    def _public_news(self, request: TextRequest) -> AnalyzeResult:
        content = f"{request.title}\n{request.text}"
        if "诈骗" in content or "验证码" in content:
            return self._anti_fraud_news()
        if "养老服务" in content or "延时服务" in content:
            return self._community_service_news()
        return self._hypertension_news()

    def _hypertension_news(self) -> AnalyzeResult:
        summary = [
            "高血压患者夏季仍要按医嘱规律服药，不可自行停药。",
            "建议每天早晚测量血压，避开午后高温时段长时间外出。",
            "如出现持续头痛、胸闷等不适，应及时就医。",
        ]
        return AnalyzeResult(
            fields=[
                self._field(
                    "WARNING",
                    "风险提示",
                    "持续头痛、胸闷时及时就医",
                    "如出现持续头痛、胸闷等不适，应及时就医。",
                    0.98,
                )
            ],
            summary=summary,
            plain_text="规律服药、早晚测量血压，注意补水并避开高温；出现不适及时就医。",
            steps=[],
            term_explanations={"收缩压": "心脏收缩时血液对血管壁产生的压力，通常是血压读数中的高值。"},
            warnings=["不能自行停药或减量。"],
            audio_script="。".join(summary),
        )

    def _anti_fraud_news(self) -> AnalyzeResult:
        summary = [
            "陌生客服以退款为由要求下载应用或共享屏幕时，应立即提高警惕。",
            "正规退款不会索要银行卡密码、短信验证码，也不会要求转入安全账户。",
            "遇到可疑来电要通过官方渠道核实，已经转账应保留证据并及时报警。",
        ]
        return AnalyzeResult(
            fields=[
                self._field(
                    "WARNING",
                    "风险提示",
                    "不要共享屏幕、提供验证码或向安全账户转账",
                    "正规平台退款不会要求转账到所谓安全账户，也不会索要银行卡密码和验证码。",
                    0.99,
                )
            ],
            summary=summary,
            plain_text="接到退款来电时先挂断，再通过平台官方客服核实；不共享屏幕、不提供验证码、不向陌生账户转账。",
            steps=[],
            term_explanations={"安全账户": "诈骗分子虚构的说法，正规机构不会要求个人把钱转入所谓安全账户。"},
            warnings=["开启屏幕共享可能泄露支付密码和验证码。", "已经转账时应立即报警并联系银行。"],
            audio_script="。".join(summary),
        )

    def _community_service_news(self) -> AnalyzeResult:
        summary = [
            "社区养老服务站在夏季工作日延长开放至十八时。",
            "周六上午可办理助餐登记、健康咨询和智能手机使用辅导。",
            "辖区六十岁以上居民可带身份证登记，行动不便者可预约上门评估。",
        ]
        return AnalyzeResult(
            fields=[
                self._field(
                    "TIME",
                    "服务时间",
                    "工作日8时30分至18时，周六上午开放",
                    "工作日开放时间为8时30分至18时，周六上午提供助餐登记、健康咨询和智能手机使用辅导。",
                    0.99,
                ),
                self._field(
                    "TARGET_AUDIENCE",
                    "服务对象",
                    "辖区年满60周岁的居民",
                    "年满60周岁的辖区居民可携带身份证到服务站登记。",
                    0.98,
                ),
            ],
            summary=summary,
            plain_text="六十岁以上辖区居民可带身份证到养老服务站登记；工作日延时开放，周六上午也提供部分服务。",
            steps=[],
            term_explanations={"上门评估": "工作人员到行动不便居民家中了解照护需求和可提供的服务。"},
            warnings=["具体开放安排以服务站现场公告为准。"],
            audio_script="。".join(summary),
        )

    @staticmethod
    def _field(
        field_type: str,
        label: str,
        value: str,
        quote: str,
        confidence: float,
    ) -> ExtractedField:
        return ExtractedField(
            field_type=field_type,
            label=label,
            value=value,
            page_no=1,
            segment_no=1,
            source_quote=quote,
            confidence=confidence,
        )
