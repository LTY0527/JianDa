from types import SimpleNamespace

from app import main


def test_runtime_capabilities_reports_external_llm_and_required_ocr(monkeypatch):
    monkeypatch.setenv("LLM_PROVIDER", "external")
    monkeypatch.setenv("EXTERNAL_LLM_API_KEY", "configured-for-test")
    monkeypatch.setenv("EXTERNAL_LLM_MODEL", "deepseek-test")
    monkeypatch.setattr(main.shutil, "which", lambda name: "/usr/bin/tesseract")
    monkeypatch.setattr(
        main.subprocess,
        "run",
        lambda *args, **kwargs: SimpleNamespace(
            stdout="List of available languages in /tmp (2):\nchi_sim\neng\n"
        ),
    )

    result = main.runtime_capabilities()

    assert result["service"]["status"] == "ready"
    assert result["llm"] == {
        "status": "ready",
        "provider": "external",
        "model": "deepseek-test",
    }
    assert result["ocr"]["status"] == "ready"
    assert result["ocr"]["required_languages"] == ["chi_sim", "eng"]
    assert result["webCollector"]["status"] == "ready"


def test_runtime_capabilities_degrades_without_secret_or_ocr(monkeypatch):
    monkeypatch.setenv("LLM_PROVIDER", "external")
    monkeypatch.delenv("EXTERNAL_LLM_API_KEY", raising=False)
    monkeypatch.setattr(main.shutil, "which", lambda name: None)

    result = main.runtime_capabilities()

    assert result["llm"]["status"] == "degraded"
    assert result["ocr"]["status"] == "degraded"
    assert result["ocr"]["engine"] == "unavailable"
    assert "api_key" not in str(result).lower()
