import json
import logging
import threading
import time
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import httpx
import pytest

from app.models import SourceSegment, TextRequest
from app.main import get_provider
from app.providers import MockProvider
from app.providers.external import (
    ExternalLlmProvider,
    ExternalProviderError,
    ExternalSettings,
)


TEST_KEY = "unit-test-secret-value"


def completion(content, *, finish_reason="stop"):
    return {
        "choices": [
            {
                "finish_reason": finish_reason,
                "message": {"content": content},
            }
        ]
    }


def facts(fields=None):
    return {
        "prompt_version": "v1",
        "fields": fields
        if fields is not None
        else [
            {
                "field_type": "LOCATION",
                "label": "地点",
                "value": "青松社区服务站",
                "source_quote": "地点：青松社区服务站",
                "page_no": 1,
                "segment_id": 101,
                "confidence": 0.98,
            }
        ],
    }


def rewrite(plain_text="请到青松社区服务站办理。"):
    return {
        "prompt_version": "v1",
        "summary": ["请先核对条件。", "请按原文时间办理。", "请带齐原文列出的材料。"],
        "plain_text": plain_text,
        "steps": [{"order": 1, "title": "核对信息", "description": "按原文核对。"}],
        "warnings": [],
        "term_explanations": {},
        "audio_script": plain_text,
    }


class QueueServer:
    def __init__(self, responses):
        self.responses = deque(responses)
        self.requests = []
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                length = int(self.headers.get("Content-Length", "0"))
                body = self.rfile.read(length)
                owner.requests.append(
                    {
                        "path": self.path,
                        "authorization": self.headers.get("Authorization"),
                        "json": json.loads(body),
                    }
                )
                status, payload, delay = owner.responses.popleft()
                if delay:
                    time.sleep(delay)
                encoded = json.dumps(payload).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(encoded)))
                self.end_headers()
                try:
                    self.wfile.write(encoded)
                except (BrokenPipeError, ConnectionAbortedError, ConnectionResetError):
                    pass

            def log_message(self, format, *args):
                return

        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(
            target=lambda: self.httpd.serve_forever(poll_interval=0.01),
            daemon=True,
        )

    @property
    def url(self):
        host, port = self.httpd.server_address
        return f"http://{host}:{port}"

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(timeout=2)


def settings(base_url, *, retries=2, timeout=5):
    return ExternalSettings(
        base_url=base_url,
        api_key=TEST_KEY,
        model="deepseek-v4-flash",
        timeout_seconds=timeout,
        max_retries=retries,
        max_tokens=6000,
        thinking="disabled",
        prompt_version="v1",
    )


def request(text="地点：青松社区服务站", *, segment_id=101, page_no=1):
    return TextRequest(
        title="测试材料",
        text=text,
        source_name="测试机构",
        segments=[
            SourceSegment(segment_id=segment_id, page_no=page_no, text=text)
        ],
    )


def response(status, payload, delay=0):
    return status, payload, delay


def json_completion(payload):
    return completion(json.dumps(payload, ensure_ascii=False))


def test_normal_two_stage_request_and_endpoint_contract():
    with QueueServer(
        [
            response(200, json_completion(facts())),
            response(200, json_completion(rewrite())),
        ]
    ) as server:
        provider = ExternalLlmProvider(settings(f"{server.url}/"), sleep=lambda _: None)
        result = provider.analyze(request())

    assert result.fields[0].value == "青松社区服务站"
    assert len(server.requests) == 2
    for sent in server.requests:
        assert sent["path"] == "/chat/completions"
        assert sent["authorization"] == f"Bearer {TEST_KEY}"
        assert sent["json"]["model"] == "deepseek-v4-flash"
        assert sent["json"]["stream"] is False
        assert sent["json"]["response_format"] == {"type": "json_object"}
        assert sent["json"]["thinking"] == {"type": "disabled"}
        prompt = " ".join(message["content"] for message in sent["json"]["messages"])
        assert "JSON" in prompt
    assert "[PAGE 1][SEGMENT 101]" in server.requests[0]["json"]["messages"][1]["content"]


def test_base_url_already_contains_completion_path_is_not_duplicated():
    with QueueServer(
        [
            response(200, json_completion(facts())),
            response(200, json_completion(rewrite())),
        ]
    ) as server:
        provider = ExternalLlmProvider(
            settings(f"{server.url}/chat/completions"), sleep=lambda _: None
        )
        provider.analyze(request())
    assert [item["path"] for item in server.requests] == [
        "/chat/completions",
        "/chat/completions",
    ]


@pytest.mark.parametrize("status", [401, 403])
def test_authentication_errors_are_not_retried(status):
    with QueueServer([response(status, {"error": "denied"})]) as server:
        provider = ExternalLlmProvider(settings(server.url), sleep=lambda _: None)
        with pytest.raises(ExternalProviderError, match="鉴权失败"):
            provider.analyze(request())
    assert len(server.requests) == 1


@pytest.mark.parametrize("status", [429, 500])
def test_transient_http_error_is_retried(status):
    with QueueServer(
        [
            response(status, {"error": "temporary"}),
            response(200, json_completion(facts())),
            response(200, json_completion(rewrite())),
        ]
    ) as server:
        result = ExternalLlmProvider(
            settings(server.url), sleep=lambda _: None
        ).analyze(request())
    assert result.fields
    assert len(server.requests) == 3


def test_timeout_is_retried_and_fails_safely():
    with QueueServer(
        [
            response(200, json_completion(facts()), delay=0.15),
            response(200, json_completion(facts()), delay=0.15),
        ]
    ) as server:
        provider = ExternalLlmProvider(
            settings(server.url, retries=1, timeout=0.03), sleep=lambda _: None
        )
        with pytest.raises(ExternalProviderError, match="超时或连接失败"):
            provider.analyze(request())
    assert len(server.requests) == 2


def test_empty_content_is_retried():
    with QueueServer(
        [
            response(200, completion("")),
            response(200, json_completion(facts())),
            response(200, json_completion(rewrite())),
        ]
    ) as server:
        result = ExternalLlmProvider(
            settings(server.url), sleep=lambda _: None
        ).analyze(request())
    assert result.fields
    assert len(server.requests) == 3


@pytest.mark.parametrize(
    ("payload", "message"),
    [
        (completion("not-json"), "content 不是合法 JSON"),
        (
            completion('```json\n{"prompt_version":"v1","fields":[]}\n```'),
            "content 不是合法 JSON",
        ),
        (completion(json.dumps({"prompt_version": "v1"})), "不符合 JSON Schema"),
        (completion(json.dumps(facts()), finish_reason="length"), "长度限制"),
    ],
)
def test_invalid_content_schema_and_truncation_are_not_retried(payload, message):
    with QueueServer([response(200, payload)]) as server:
        provider = ExternalLlmProvider(settings(server.url), sleep=lambda _: None)
        with pytest.raises(ExternalProviderError, match=message):
            provider.analyze(request())
    assert len(server.requests) == 1


@pytest.mark.parametrize(
    "invalid_field",
    [
        {
            "field_type": "LOCATION",
            "label": "地点",
            "value": "不存在的地点",
            "source_quote": "原文没有这句话",
            "page_no": 1,
            "segment_id": 101,
            "confidence": 0.8,
        },
        {
            "field_type": "LOCATION",
            "label": "地点",
            "value": "青松社区服务站",
            "source_quote": "地点：青松社区服务站",
            "page_no": 2,
            "segment_id": 101,
            "confidence": 0.8,
        },
    ],
)
def test_untraceable_quote_or_page_mismatch_is_rejected_without_retry(invalid_field):
    with QueueServer(
        [response(200, json_completion(facts([invalid_field])))]
    ) as server:
        provider = ExternalLlmProvider(settings(server.url), sleep=lambda _: None)
        with pytest.raises(ExternalProviderError, match="未生成可追溯"):
            provider.analyze(request())
    assert len(server.requests) == 1


def test_invalid_rewrite_schema_fails_without_mock_fallback():
    invalid_rewrite = rewrite()
    invalid_rewrite.pop("audio_script")
    with QueueServer(
        [
            response(200, json_completion(facts())),
            response(200, json_completion(invalid_rewrite)),
        ]
    ) as server:
        provider = ExternalLlmProvider(settings(server.url), sleep=lambda _: None)
        with pytest.raises(ExternalProviderError, match="适老化结果不符合 JSON Schema"):
            provider.analyze(request())
    assert len(server.requests) == 2


def test_api_key_never_appears_in_logs_or_error(caplog):
    caplog.set_level(logging.INFO)
    with QueueServer([response(401, {"error": TEST_KEY})]) as server:
        provider = ExternalLlmProvider(settings(server.url), sleep=lambda _: None)
        with pytest.raises(ExternalProviderError) as raised:
            provider.analyze(request())
    assert TEST_KEY not in caplog.text
    assert TEST_KEY not in str(raised.value)
    assert "Authorization" not in caplog.text
    assert "Bearer" not in caplog.text


def test_missing_key_fails_only_when_external_provider_is_created(monkeypatch):
    monkeypatch.setenv("EXTERNAL_LLM_API_KEY", "")
    with pytest.raises(ExternalProviderError, match="缺少 EXTERNAL_LLM_API_KEY"):
        ExternalLlmProvider()


def test_mock_mode_does_not_require_external_api_key(monkeypatch):
    monkeypatch.setenv("LLM_PROVIDER", "mock")
    monkeypatch.delenv("EXTERNAL_LLM_API_KEY", raising=False)
    assert isinstance(get_provider(), MockProvider)


@pytest.mark.parametrize(
    "invalid_fields",
    [
        None,
        [],
        [
            {
                "field_type": "CONTACT",
                "field_label": "咨询电话",
                "field_value": "021-5558 7301",
                "source_quote": "咨询电话：021-5558 7301。",
                "page_no": 1,
                "segment_id": 101,
                "confidence": 0.95,
            }
        ],
    ],
)
def test_null_empty_or_wrong_field_contract_fails_before_rewrite(invalid_fields):
    payload = {"prompt_version": "v1", "fields": invalid_fields}
    with QueueServer([response(200, json_completion(payload))]) as server:
        provider = ExternalLlmProvider(settings(server.url), sleep=lambda _: None)
        with pytest.raises(ExternalProviderError):
            provider.analyze(request("咨询电话：021-5558 7301。"))
    assert len(server.requests) == 1


def test_whitespace_only_quote_difference_locates_original_text():
    source = "咨询电话：021-5558 \n\t7301。"
    field = {
        "field_type": "CONTACT",
        "label": "咨询电话",
        "value": "021-5558 7301",
        "source_quote": "咨询电话：021-5558 7301。",
        "page_no": 1,
        "segment_id": 101,
        "confidence": 0.95,
    }
    with QueueServer(
        [
            response(200, json_completion(facts([field]))),
            response(200, json_completion(rewrite())),
        ]
    ) as server:
        result = ExternalLlmProvider(
            settings(server.url), sleep=lambda _: None
        ).analyze(request(source))
    assert result.fields[0].source_quote == source


def test_changed_phone_number_is_rejected():
    source = "咨询电话：021-5558 7301。"
    field = {
        "field_type": "CONTACT",
        "label": "咨询电话",
        "value": "021-5558 7302",
        "source_quote": "咨询电话：021-5558 7301。",
        "page_no": 1,
        "segment_id": 101,
        "confidence": 0.95,
    }
    with QueueServer([response(200, json_completion(facts([field])))]) as server:
        with pytest.raises(ExternalProviderError, match="未生成可追溯"):
            ExternalLlmProvider(
                settings(server.url), sleep=lambda _: None
            ).analyze(request(source))
    assert len(server.requests) == 1


def test_temporal_value_preserves_time_present_in_source_quote():
    source = "请在9月28日18:00以前完成确认。"
    field = {
        "field_type": "END_DATE",
        "label": "确认截止",
        "value": "9月28日",
        "source_quote": "9月28日18:00以前",
        "page_no": 1,
        "segment_id": 101,
        "confidence": 0.95,
    }
    with QueueServer(
        [
            response(200, json_completion(facts([field]))),
            response(200, json_completion(rewrite())),
        ]
    ) as server:
        result = ExternalLlmProvider(
            settings(server.url), sleep=lambda _: None
        ).analyze(request(source))
    assert result.fields[0].value == "9月28日18:00以前"
    assert result.fields[0].source_quote == "9月28日18:00以前"


def test_explicit_adjustment_dates_and_deadline_are_completed_from_source():
    source = (
        "原预约日期为10月1日的，顺延至10月8日；"
        "原预约日期为10月2日的，顺延至10月9日；"
        "原预约日期为10月3日\n的，顺延至10月10日。"
        "请患者在9月28日18:00以前确认。"
        "地点：青松社区服务站"
    )
    with QueueServer(
        [
            response(200, json_completion(facts())),
            response(200, json_completion(rewrite())),
        ]
    ) as server:
        result = ExternalLlmProvider(
            settings(server.url), sleep=lambda _: None
        ).analyze(request(source))
    temporal = [
        field
        for field in result.fields
        if field.field_type in {"EVENT_DATE", "END_DATE"}
    ]
    assert [field.value for field in temporal] == [
        "10月1日 → 10月8日",
        "10月2日 → 10月9日",
        "10月3日 → 10月10日",
        "9月28日18:00以前",
    ]
    assert all(field.source_quote in source for field in temporal)


def test_source_completion_does_not_duplicate_model_temporal_fields():
    source = "原预约日期为10月1日的，顺延至10月8日。地点：青松社区服务站"
    model_fields = facts()["fields"] + [
        {
            "field_type": "EVENT_DATE",
            "label": "预约调整",
            "value": "10月1日 → 10月8日",
            "source_quote": "原预约日期为10月1日的，顺延至10月8日",
            "page_no": 1,
            "segment_id": 101,
            "confidence": 0.98,
        }
    ]
    with QueueServer(
        [
            response(200, json_completion(facts(model_fields))),
            response(200, json_completion(rewrite())),
        ]
    ) as server:
        result = ExternalLlmProvider(
            settings(server.url), sleep=lambda _: None
        ).analyze(request(source))
    assert sum(
        field.field_type == "EVENT_DATE" for field in result.fields
    ) == 1


def test_audit_counts_valid_and_rejected_fields_without_sensitive_content(caplog):
    caplog.set_level(logging.INFO)
    valid = {
        "field_type": "LOCATION",
        "label": "地点",
        "value": "青松社区服务站",
        "source_quote": "地点：青松社区服务站",
        "page_no": 1,
        "segment_id": 101,
        "confidence": 0.98,
    }
    invalid = {**valid, "value": "其他地点", "source_quote": "不存在的原文"}
    with QueueServer(
        [
            response(
                200,
                {
                    "id": "request-test-1",
                    "usage": {
                        "prompt_tokens": 100,
                        "completion_tokens": 20,
                        "total_tokens": 120,
                    },
                    **json_completion(facts([valid, invalid])),
                },
            ),
            response(200, json_completion(rewrite())),
        ]
    ) as server:
        result = ExternalLlmProvider(
            settings(server.url), sleep=lambda _: None
        ).analyze(request())
    assert len(result.fields) == 1
    assert "raw_field_count=2" in caplog.text
    assert "parsed_field_count=2" in caplog.text
    assert "schema_valid_field_count=2" in caplog.text
    assert "trace_valid_field_count=1" in caplog.text
    assert "rejected_field_count=1" in caplog.text
    assert "source_quote_not_found:1" in caplog.text
    assert "prompt_tokens=100" in caplog.text
    assert TEST_KEY not in caplog.text
    assert "Authorization" not in caplog.text
    assert "Bearer" not in caplog.text


@pytest.mark.parametrize(
    ("material", "field_type", "value", "quote"),
    [
        (
            "适用对象：本街道55周岁及以上常住居民。",
            "TARGET_AUDIENCE",
            "本街道55周岁及以上常住居民",
            "本街道55周岁及以上常住居民",
        ),
        (
            "为65周岁及以上老年人提供免费健康体检。",
            "TARGET_AUDIENCE",
            "65周岁及以上老年人",
            "65周岁及以上老年人",
        ),
        (
            "周三下午在松林活动室开展讲座。",
            "LOCATION",
            "松林活动室",
            "松林活动室",
        ),
        (
            "报名截止8月10日；另一处写报名截止8月12日。",
            "END_DATE",
            "8月10日、8月12日（待人工核对）",
            "报名截止8月10日；另一处写报名截止8月12日",
        ),
    ],
)
def test_offline_material_regressions_are_distinct_and_traceable(
    material, field_type, value, quote
):
    field = {
        "field_type": field_type,
        "label": "提取结果",
        "value": value,
        "source_quote": quote,
        "page_no": 1,
        "segment_id": 101,
        "confidence": 0.9,
    }
    with QueueServer(
        [
            response(200, json_completion(facts([field]))),
            response(200, json_completion(rewrite(value))),
        ]
    ) as server:
        result = ExternalLlmProvider(
            settings(server.url), sleep=lambda _: None
        ).analyze(request(material))
    assert result.fields[0].field_type == field_type
    assert result.fields[0].value == value
    assert result.fields[0].source_quote in material
    assert result.plain_text == value


def test_conflicting_dates_are_kept_as_separate_traceable_fields():
    material = "报名截止8月10日。补充通知写报名截止8月12日。"
    fields = [
        {
            "field_type": "END_DATE",
            "label": "报名截止日期（待人工核对）",
            "value": value,
            "source_quote": quote,
            "page_no": 1,
            "segment_id": 101,
            "confidence": 0.65,
        }
        for value, quote in [
            ("8月10日", "报名截止8月10日"),
            ("8月12日", "报名截止8月12日"),
        ]
    ]
    with QueueServer(
        [
            response(200, json_completion(facts(fields))),
            response(200, json_completion(rewrite("两个日期冲突，请人工核对。"))),
        ]
    ) as server:
        result = ExternalLlmProvider(
            settings(server.url), sleep=lambda _: None
        ).analyze(request(material))
    assert [field.value for field in result.fields] == ["8月10日", "8月12日"]
    assert all(field.source_quote in material for field in result.fields)
