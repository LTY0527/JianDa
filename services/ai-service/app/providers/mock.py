from app.models import AnalyzeResult, ExtractedField, StepCard, TextRequest
from app.providers.base import LlmProvider


class MockProvider(LlmProvider):
    """稳定的离线结果，确保自动化测试和课程答辩每次一致。"""

    def analyze(self, request: TextRequest) -> AnalyzeResult:
        if request.document_type == "public_news":
            return self._public_news(request)
        return self._elderly_subsidy()

    def _elderly_subsidy(self) -> AnalyzeResult:
        fields = [
            self._field("TARGET_AUDIENCE", "适用对象", "具有本市户籍、年满 80 周岁的老年人", 1, 1,
                        "补贴对象为具有本市户籍且年满八十周岁的老年人。", 0.98),
            self._field("ELIGIBILITY", "申请条件", "当前未享受同类生活补贴", 1, 1,
                        "已享受同类补贴待遇的，不重复发放。", 0.91),
            self._field("MATERIAL", "所需材料", "身份证、户口簿、本人银行卡、近期一寸照片", 2, 1,
                        "申请材料：身份证及户口簿原件、本人银行卡复印件、近期一寸免冠照片一张。", 0.97),
            self._field("LOCATION", "办理地点", "户籍所在地社区服务窗口", 2, 2,
                        "请申请人至户籍所在地社区服务窗口提出申请。", 0.96),
            self._field("CONTACT", "联系方式", "021-12345（工作日 9:00—17:00）", 3, 1,
                        "咨询电话：021-12345，工作日 9:00—17:00。", 0.99),
        ]
        summary = [
            "本市户籍、年满 80 周岁的老人，可以申请这项生活补贴。",
            "准备身份证、户口簿、本人银行卡和一寸照片，到户籍所在地社区办理。",
            "工作人员一般在 10 个工作日内完成审核，通过后补贴发到银行卡。",
        ]
        steps = [
            StepCard(order=1, title="准备材料", description="带好身份证、户口簿、银行卡复印件和一寸照片。"),
            StepCard(order=2, title="到社区申请", description="前往户籍所在地社区服务窗口，领取申请表。"),
            StepCard(order=3, title="填写并提交", description="如实填写信息，交给窗口工作人员核对。"),
            StepCard(order=4, title="等待审核", description="一般 10 个工作日内完成，请保持电话畅通。"),
            StepCard(order=5, title="查询结果", description="审核通过后，补贴按规定发到本人银行卡。"),
        ]
        return AnalyzeResult(
            fields=fields, summary=summary,
            plain_text="年满 80 周岁并具有本市户籍的老人，可带齐身份证、户口簿、银行卡和照片，到社区窗口申请生活补贴。",
            steps=steps,
            term_explanations={"同类生活补贴": "用途和对象相近、按规定不能重复领取的政府补助。"},
            warnings=["已领取同类补贴的申请人不能重复申领。", "具体办理要求以服务窗口最新规定为准。"],
            audio_script="。".join(summary + [f"第{step.order}步，{step.title}，{step.description}" for step in steps]),
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
            fields=[self._field("WARNING", "风险提示", "持续头痛、胸闷时及时就医", 1, 1,
                                "如出现持续头痛、胸闷等不适，应及时就医。", 0.98)],
            summary=summary,
            plain_text="规律服药、早晚测量血压，注意补水并避开高温；出现不适及时就医。",
            steps=[], term_explanations={"收缩压": "心脏收缩时血液对血管壁产生的压力，通常是血压读数中的高值。"},
            warnings=["不能自行停药或减量。"], audio_script="。".join(summary),
        )

    def _anti_fraud_news(self) -> AnalyzeResult:
        summary = [
            "陌生客服以退款为由要求下载应用或共享屏幕时，应立即提高警惕。",
            "正规退款不会索要银行卡密码、短信验证码，也不会要求转入安全账户。",
            "遇到可疑来电要通过官方渠道核实，已经转账应保留证据并及时报警。",
        ]
        return AnalyzeResult(
            fields=[self._field("WARNING", "风险提示", "不要共享屏幕、提供验证码或向安全账户转账", 1, 1,
                                "正规平台退款不会要求转账到所谓安全账户，也不会索要银行卡密码和验证码。", 0.99)],
            summary=summary, plain_text="接到退款来电时先挂断，再通过平台官方客服核实；不共享屏幕、不提供验证码、不向陌生账户转账。",
            steps=[], term_explanations={"安全账户": "诈骗分子虚构的说法，正规机构不会要求个人把钱转入所谓安全账户。"},
            warnings=["开启屏幕共享可能泄露支付密码和验证码。", "已经转账时应立即报警并联系银行。"], audio_script="。".join(summary),
        )

    def _community_service_news(self) -> AnalyzeResult:
        summary = [
            "社区养老服务站在夏季工作日延长开放至十八时。",
            "周六上午可办理助餐登记、健康咨询和智能手机使用辅导。",
            "辖区六十岁以上居民可带身份证登记，行动不便者可预约上门评估。",
        ]
        return AnalyzeResult(
            fields=[
                self._field("TIME", "服务时间", "工作日8时30分至18时，周六上午开放", 1, 1,
                            "工作日开放时间为8时30分至18时，周六上午提供助餐登记、健康咨询和智能手机使用辅导。", 0.99),
                self._field("TARGET_AUDIENCE", "服务对象", "辖区年满60周岁的居民", 1, 1,
                            "年满60周岁的辖区居民可携带身份证到服务站登记。", 0.98),
            ], summary=summary,
            plain_text="六十岁以上辖区居民可带身份证到养老服务站登记；工作日延时开放，周六上午也提供部分服务。",
            steps=[], term_explanations={"上门评估": "工作人员到行动不便居民家中了解照护需求和可提供的服务。"},
            warnings=["具体开放安排以服务站现场公告为准。"], audio_script="。".join(summary),
        )
    @staticmethod
    def _field(field_type: str, label: str, value: str, page: int, segment: int,
               quote: str, confidence: float) -> ExtractedField:
        return ExtractedField(field_type=field_type, label=label, value=value, page_no=page,
                              segment_no=segment, source_quote=quote, confidence=confidence)
