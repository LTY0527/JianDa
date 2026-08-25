#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Publish doc 73 (Shanghai Civil Affairs Bureau elderly meal delivery policy) to MEALS channel."""
import json
import sys
import urllib.request
import urllib.error

API = "http://127.0.0.1:8080/api"
DOC_ID = 73


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
            raw = resp.read().decode("utf-8")
            return resp.status, json.loads(raw)
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
        try:
            body_json = json.loads(raw)
        except Exception:
            body_json = {"raw": raw}
        return e.code, body_json


def main():
    # 1. login
    status, resp = http("POST", "/auth/login", {
        "username": "platform_admin",
        "password": "Jianda@123",
    })
    assert status == 200, f"login failed: {status} {resp}"
    token = resp["data"]["token"]
    print(f"[1] login OK")

    # 2. before: detail
    status, before = http("GET", f"/documents/{DOC_ID}", token=token)
    assert status == 200, f"detail failed: {status} {before}"
    b_status = before["data"]["processing_status"]
    b_region = before["data"].get("province")
    print(f"[2-before] status={b_status} province={b_region!r}")

    # 3. fix region scope (province/city/district were garbled from early upload)
    # localScope whitelist: LOCAL_TOWN / DISTRICT_SHARED / CITY_SHARED / NATIONAL_SHARED / UNCLASSIFIED
    status, resp = http("PUT", f"/documents/{DOC_ID}/region-scope", {
        "localScope": "DISTRICT_SHARED",
        "province": "\u4e0a\u6d77\u5e02",          # 上海市
        "city": "\u4e0a\u6d77\u5e02",              # 上海市
        "district": "\u5b9d\u5c71\u533a",          # 宝山区
        "streetOrTown": "",
        "regionCode": "310113",
    }, token=token)
    assert status == 200, f"region-scope failed: {status} {resp}"
    print(f"[3] region-scope fixed: {resp.get('data', resp)}")

    # 4. review (approve)
    status, resp = http("POST", f"/documents/{DOC_ID}/review", {
        "comment": "\u5185\u5bb9\u5b8c\u6574\u3001\u6765\u6e90\u6743\u5a01\uff0c\u5ba1\u6838\u901a\u8fc7",
    }, token=token)
    assert status == 200, f"review failed: {status} {resp}"
    print(f"[4] review approved")

    # 5. publish to MEALS channel
    title = ("\u4e0a\u6d77\u5e02\u6c11\u653f\u5c40\u5173\u4e8e\u5370\u53d1"
             "\u300a\u5173\u4e8e\u63a8\u8fdb\u8fd0\u7528\u5e02\u573a\u5316\u5e73\u53f0\u673a\u5236"
             "\u4f18\u5316\u8001\u5e74\u9001\u9910\u4e0a\u95e8\u670d\u52a1\u7684\u5b9e\u65bd\u610f\u89c1"
             "\uff08\u8bd5\u884c\uff09\u300b\u7684\u901a\u77e5")
    status, resp = http("POST", f"/documents/{DOC_ID}/publish", {
        "title": title,
        "category": "\u52a9\u9910\u653f\u7b56",     # 助餐政策
        "sourceName": "\u4e0a\u6d77\u5e02\u6c11\u653f\u5c40",  # 上海市民政局
        "sourceUrl": "",
        "allowPublicOriginal": False,
        "publishChannel": "MEALS",
        "promoteToRecommend": False,
        "importanceLevel": "NORMAL",
    }, token=token)
    assert status == 200, f"publish failed: {status} {resp}"
    print(f"[5] published: {resp.get('data', resp)}")

    # 6. after: verify
    status, after = http("GET", f"/documents/{DOC_ID}", token=token)
    assert status == 200, f"detail-after failed: {status} {after}"
    a_status = after["data"]["processing_status"]
    a_channel = after["data"].get("publish_channel")
    a_province = after["data"].get("province")
    a_title = after["data"].get("title")
    a_source = after["data"].get("source_name")
    print(f"[6-after] status={a_status} channel={a_channel} province={a_province!r}")
    print(f"          title={a_title!r}")
    print(f"          source={a_source!r}")

    # 7. verify in public items
    status, pub = http("GET", "/public/items?category=MEALS")
    assert status == 200, f"public items failed: {status} {pub}"
    found = [p for p in pub["data"] if p.get("title") == title]
    print(f"[7-public] MEALS public items count={len(pub['data'])}, doc73 found={len(found)>0}")

    sh = "\u4e0a\u6d77\u5e02"  # 上海市
    region_ok = (a_province == sh)
    all_pass = (a_status == "PUBLISHED" and a_channel == "MEALS"
                and region_ok and len(found) > 0)
    print("\n==== SUMMARY ====")
    print("published={} channel_ok={} region_fixed={} public_visible={}".format(
        a_status == "PUBLISHED", a_channel == "MEALS", region_ok, len(found) > 0))
    print("GATE P0B_DOC73_PUBLISH = {}".format("PASS" if all_pass else "FAIL"))
    return 0 if all_pass else 1


if __name__ == "__main__":
    sys.exit(main())
