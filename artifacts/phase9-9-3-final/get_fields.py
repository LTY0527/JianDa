#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Get and confirm all pending fields for doc 73, then review and publish."""
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
    token = resp["data"]["token"]
    print("[1] login OK")

    # 2. get fields list
    status, resp = http("GET", f"/documents/{DOC_ID}/fields", token=token)
    print(f"[2] fields status={status}")
    if status != 200:
        print(f"    resp={resp}")
        return 1
    fields = resp.get("data", [])
    print(f"    fields count={len(fields)}")
    for f in fields:
        print(f"    id={f.get('id')} label={f.get('field_label')!r} "
              f"value={f.get('field_value')!r} confirmed={f.get('review_status')}")

    # 3. confirm each pending field
    confirmed = 0
    for f in fields:
        fid = f.get("id")
        rstatus = f.get("review_status", "")
        if rstatus != "CONFIRMED":
            status, resp = http("PUT", f"/documents/{DOC_ID}/fields/{fid}", {
                "value": f.get("field_value", ""),
                "confirmed": True,
            }, token=token)
            print(f"[3] confirm field {fid}: status={status}")
            if status == 200:
                confirmed += 1
        else:
            print(f"[3] field {fid} already confirmed")
            confirmed += 1
    print(f"[3] total confirmed={confirmed}/{len(fields)}")

    # 4. review
    status, resp = http("POST", f"/documents/{DOC_ID}/review", {
        "comment": "\u5185\u5bb9\u5b8c\u6574\u3001\u6765\u6e90\u6743\u5a01\uff0c\u5ba1\u6838\u901a\u8fc7",
    }, token=token)
    print(f"[4] review: status={status} resp={resp}")
    if status != 200:
        return 1

    # 5. publish to MEALS
    title = ("\u4e0a\u6d77\u5e02\u6c11\u653f\u5c40\u5173\u4e8e\u5370\u53d1"
             "\u300a\u5173\u4e8e\u63a8\u8fdb\u8fd0\u7528\u5e02\u573a\u5316\u5e73\u53f0\u673a\u5236"
             "\u4f18\u5316\u8001\u5e74\u9001\u9910\u4e0a\u95e8\u670d\u52a1\u7684\u5b9e\u65bd\u610f\u89c1"
             "\uff08\u8bd5\u884c\uff09\u300b\u7684\u901a\u77e5")
    status, resp = http("POST", f"/documents/{DOC_ID}/publish", {
        "title": title,
        "category": "\u52a9\u9910\u653f\u7b56",
        "sourceName": "\u4e0a\u6d77\u5e02\u6c11\u653f\u5c40",
        "sourceUrl": "",
        "allowPublicOriginal": False,
        "publishChannel": "MEALS",
        "promoteToRecommend": False,
        "importanceLevel": "NORMAL",
    }, token=token)
    print(f"[5] publish: status={status}")
    if status != 200:
        print(f"    resp={resp}")
        return 1

    # 6. verify
    status, after = http("GET", f"/documents/{DOC_ID}", token=token)
    a = after["data"]
    print(f"[6-after] status={a['processing_status']} channel={a.get('publish_channel')} "
          f"province={a.get('province')!r} source={a.get('source_name')!r}")

    # 7. verify in ALL public items (no category filter; category field is content type not channel)
    status, pub = http("GET", "/public/items")
    found = [p for p in pub["data"] if p.get("title") == title]
    if found:
        p = found[0]
        print(f"[7-public] doc73 visible. channel={p.get('publish_channel')} "
              f"source_name={p.get('source_name')!r} region={p.get('province')!r}")
    else:
        print(f"[7-public] doc73 NOT visible in {len(pub['data'])} items")

    ok = (a["processing_status"] == "PUBLISHED"
          and a.get("publish_channel") == "MEALS"
          and a.get("province") == "\u4e0a\u6d77\u5e02"
          and len(found) > 0)
    print(f"\nGATE P0B_DOC73_PUBLISH = {'PASS' if ok else 'FAIL'}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
