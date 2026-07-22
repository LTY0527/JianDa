from fastapi.testclient import TestClient

from app.main import app
from app.models import TextRequest
from app.providers.mock import MockProvider


def test_mock_result_is_stable() -> None:
    provider = MockProvider()
    request = TextRequest(title="老年补贴申请指南", text="示例原文")
    first = provider.analyze(request)
    second = provider.analyze(request)
    assert first == second
    assert len(first.fields) == 5
    assert len(first.steps) == 5
    assert first.fields[0].page_no == 1
    assert first.fields[0].source_quote


def test_health_and_analyze() -> None:
    client = TestClient(app)
    assert client.get("/health").json()["status"] == "ok"
    response = client.post("/internal/analyze", json={"title": "指南", "text": "正文", "document_type": "guide"})
    assert response.status_code == 200
    assert response.json()["summary"][0].startswith("本市户籍")


def test_public_news_has_warning() -> None:
    result = MockProvider().analyze(TextRequest(title="健康提示", text="正文", document_type="public_news"))
    assert result.steps == []
    assert result.warnings == ["不能自行停药或减量。"]

