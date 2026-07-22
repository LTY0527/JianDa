import os

from app.models import AnalyzeResult, TextRequest
from app.providers.base import LlmProvider


class ExternalLlmProvider(LlmProvider):
    """真实模型扩展点；没有环境变量时明确失败，不回退或伪造结果。"""

    def __init__(self) -> None:
        self.api_key = os.getenv("EXTERNAL_LLM_API_KEY")

    def analyze(self, request: TextRequest) -> AnalyzeResult:
        if not self.api_key:
            raise RuntimeError("未配置 EXTERNAL_LLM_API_KEY，无法使用外部模型")
        raise NotImplementedError("外部模型调用由部署方按合规要求接入")

