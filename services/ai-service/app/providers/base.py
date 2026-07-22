from abc import ABC, abstractmethod

from app.models import AnalyzeResult, TextRequest


class LlmProvider(ABC):
    """所有模型实现都必须提供相同、可替换的分析入口。"""

    @abstractmethod
    def analyze(self, request: TextRequest) -> AnalyzeResult:
        raise NotImplementedError

