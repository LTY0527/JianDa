#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Test assistant chat with Baoshan region code."""
import json
import urllib.request
import urllib.error

API = "http://127.0.0.1:8080/api"
VISITOR = "trae-acceptance-visitor"


def http(method, path, body=None, headers=None, timeout=120):
    url = API + path
    h = {"Content-Type": "application/json; charset=utf-8"}
    if headers:
        h.update(headers)
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body else None
    req = urllib.request.Request(url, data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw}


# Try with Baoshan region code 310113
q = "\u666e\u9640\u533a\u8001\u5e74\u4eba\u52a9\u9910\u8865\u8d34\u6709\u54ea\u4e9b\u7c7b\u578b\uff1f\u8865\u8d34\u6807\u51c6\u662f\u4ec0\u4e48\uff1f"
print(f"question: {q}")

# Try different region codes
for rc in ["310113", "310113102", ""]:
    print(f"\n--- regionCode={rc!r} ---")
    body = {"message": q}
    if rc:
        body["regionCode"] = rc
    status, resp = http("POST", "/public/assistant/chat", body,
                         headers={"X-Visitor-Id": VISITOR})
    print(f"status={status}")
    if status == 200:
        d = resp.get("data", resp)
        print(f"  answer_preview: {d.get('answer', '')[:200]}")
        print(f"  provider={d.get('provider') or d.get('aiProvider')}")
        print(f"  completion_tokens={d.get('completion_tokens')}")
    else:
        print(f"  resp={resp}")

# Try with X-Region-Code header
print("\n--- with X-Region-Code header ---")
status, resp = http("POST", "/public/assistant/chat", {"message": q},
                     headers={"X-Visitor-Id": VISITOR, "X-Region-Code": "310113"})
print(f"status={status}")
if status == 200:
    d = resp.get("data", resp)
    print(f"  answer_preview: {d.get('answer', '')[:200]}")
else:
    print(f"  resp={resp}")
