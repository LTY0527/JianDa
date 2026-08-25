#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Verify doc 73 is visible in public items with correct channel and region."""
import json
import sys
import urllib.request
import urllib.error

API = "http://127.0.0.1:8080/api"
DOC_ID = 73
TITLE = ("\u4e0a\u6d77\u5e02\u6c11\u653f\u5c40\u5173\u4e8e\u5370\u53d1"
         "\u300a\u5173\u4e8e\u63a8\u8fdb\u8fd0\u7528\u5e02\u573a\u5316\u5e73\u53f0\u673a\u5236"
         "\u4f18\u5316\u8001\u5e74\u9001\u9910\u4e0a\u95e8\u670d\u52a1\u7684\u5b9e\u65bd\u610f\u89c1"
         "\uff08\u8bd5\u884c\uff09\u300b\u7684\u901a\u77e5")
SH = "\u4e0a\u6d77\u5e02"  # 上海市


def http(method, path, body=None, token=None):
    url = API + path
    headers = {"Content-Type": "application/json; charset=utf-8"}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = None
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw}


def main():
    # login
    _, resp = http("POST", "/auth/login", {
        "username": "platform_admin", "password": "Jianda@123"})
    token = resp["data"]["token"]

    # admin detail
    _, det = http("GET", f"/documents/{DOC_ID}", token=token)
    d = det["data"]
    print(f"[admin] status={d['processing_status']} channel={d.get('publish_channel')}")
    print(f"        province={d.get('province')!r} city={d.get('city')!r} district={d.get('district')!r}")
    print(f"        source_name={d.get('source_name')!r}")
    print(f"        title={d.get('title')!r}")

    # public items
    _, pub = http("GET", "/public/items")
    items = pub["data"]
    found = [p for p in items if p.get("title") == TITLE]
    print(f"\n[public] total items={len(items)}")
    if found:
        p = found[0]
        print(f"        doc73 FOUND: channel={p.get('publish_channel')}")
        print(f"        source_name={p.get('source_name')!r}")
        print(f"        province={p.get('province')!r}")
        print(f"        region_code={p.get('region_code')!r}")
    else:
        print("        doc73 NOT FOUND")
        # print first 3 titles to debug
        for p in items[:3]:
            print(f"        sample: {p.get('title')!r} channel={p.get('publish_channel')}")

    ok = (d["processing_status"] == "PUBLISHED"
          and d.get("publish_channel") == "MEALS"
          and d.get("province") == SH
          and len(found) > 0)
    print(f"\nGATE P0B_DOC73_PUBLISH = {'PASS' if ok else 'FAIL'}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
