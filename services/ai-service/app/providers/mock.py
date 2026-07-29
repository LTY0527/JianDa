import re

from app.document_structure import (
    build_document_outline,
    build_type_specific_facts,
    detect_document_kind,
    split_document_sections,
)
from app.models import (
    AnalyzeResult,
    ExtractedField,
    SourceSegment,
    StepCard,
    TextRequest,
)
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
        document_kind = detect_document_kind(
            request.title, request.text, request.source_name, request.content_kind
        )
        sections = split_document_sections(
            request.segments
            or [SourceSegment(segment_id=1, page_no=1, text=request.text)]
        )
        outline = build_document_outline(sections)
        type_facts = build_type_specific_facts(document_kind, sections)
        return AnalyzeResult(
            fields=fields,
            summary=summary,
            plain_text=plain_text,
            steps=steps,
            term_explanations={},
            warnings=warnings,
            audio_script="。".join(audio_parts),
            document_kind=document_kind,
            document_outline=outline,
            section_summaries=outline,
            standard_sections=(
                type_facts if document_kind == "STANDARD_SPECIFICATION" else []
            ),
            policy_sections=(
                type_facts if document_kind == "POLICY_DOCUMENT" else []
            ),
            health_guidance=(
                type_facts if document_kind == "HEALTH_EDUCATION" else []
            ),
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
        sentences = [
            item.strip()
            for item in re.findall(r"[^。！？\r\n]+[。！？]?", request.text)
            if item.strip()
        ]
        fields: list[ExtractedField] = []
        warning_keywords = (
            "不可",
            "不要",
            "请勿",
            "避免",
            "不会",
            "谨防",
            "及时就医",
            "及时报警",
        )
        warning_sentences = [
            sentence
            for sentence in sentences
            if any(keyword in sentence for keyword in warning_keywords)
        ]
        for sentence in warning_sentences:
            fields.append(
                self._field(
                    "WARNING",
                    "风险提示",
                    sentence.rstrip("。！？"),
                    sentence,
                    0.96,
                )
            )

        if not fields:
            service_sentence = next(
                (
                    sentence
                    for sentence in sentences
                    if any(
                        keyword in sentence
                        for keyword in ("开放", "服务时间", "办理时间", "延时服务")
                    )
                ),
                None,
            )
            if service_sentence:
                fields.append(
                    self._field(
                        "SERVICE_TIME",
                        "服务时间",
                        service_sentence.rstrip("。！？"),
                        service_sentence,
                        0.94,
                    )
                )

        summary = [sentence.rstrip("。！？") + "。" for sentence in sentences[:3]]
        if not summary:
            summary = ["暂未从材料正文中识别出可展示内容，请人工核对原文。"]
        warnings = [field.value for field in fields if field.field_type == "WARNING"]
        plain_text = request.text.strip() or "待人工填写。"
        return AnalyzeResult(
            fields=fields,
            summary=summary,
            plain_text=plain_text,
            steps=[],
            term_explanations={},
            warnings=warnings,
            audio_script="".join(summary),
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
