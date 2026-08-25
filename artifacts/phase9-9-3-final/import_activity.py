#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Import a real official activity article, wait for AI processing, review, publish to ACTIVITY channel."""
import json
import sys
import time
import urllib.request
import urllib.error

API = "http://127.0.0.1:8080/api"
# Shanghai gov: Putuo silver economy upgrade - elderly cultural activities (electric flute class, modern happy-age club)
URL = ("https://www.shanghai.gov.cn/nw15343/20260819/"
       "4c6d75553d3e4aa89157b246dac3285a.html")
TITLE_EXPECTED = ("\u6253\u9020\u53ef\u611f\u77e5\u7684\u9002\u8001\u7a7a\u95f4\u3001"
                  "\u53ef\u53c2\u4e0e\u7684\u4eab\u8001\u751f\u6d3b\uff0c"
                  "\u666e\u9640\u201c\u94f6\u53d1\u7ecf\u6d4e\u201d\u518d\u5347\u7ea7")


def http(method, path, body=None, token=None, timeout=30):
    url = API + path
    headers = {"Content-Type": "application/json; charset=utf-8"}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = None
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw}


def main():
    # 1. login
    _, resp = http("POST", "/auth/login", {
        "username": "platform_admin", "password": "Jianda@123"})
    token = resp["data"]["token"]
    print("[1] login OK")

    # 2. import URL
    status, resp = http("POST", "/web-articles/import", {"url": URL}, token=token)
    print(f"[2] import: status={status}")
    if status != 200:
        print(f"    resp={resp}")
        return 1
    doc_id = None
    data = resp.get("data", {})
    if isinstance(data, dict):
        doc_id = data.get("documentId") or data.get("id") or data.get("document_id")
    print(f"    resp data={data}")
    if not doc_id:
        # try to find by listing recent documents
        print("[2] no doc_id in response, searching recent docs...")
        status, resp = http("GET", "/documents?pageSize=5&pageNo=1", token=token)
        if status == 200:
            rows = resp.get("data", {}).get("rows", resp.get("data", []))
            if isinstance(rows, list):
                for r in rows:
                    t = r.get("title", "")
                    if "\u94f6\u53d1\u7ecf\u6d4e" in t or "\u9002\u8001\u7a7a\u95f4" in t:
                        doc_id = r.get("id")
                        print(f"    found doc_id={doc_id} title={t!r}")
                        break
    if not doc_id:
        print("[2] FAIL: cannot determine doc_id")
        return 1

    # 3. wait for AI processing -> WAITING_REVIEW
    print(f"[3] waiting for doc {doc_id} to reach WAITING_REVIEW...")
    max_wait = 180
    waited = 0
    last_status = None
    while waited < max_wait:
        status, resp = http("GET", f"/documents/{doc_id}", token=token)
        if status == 200:
            last_status = resp["data"]["processing_status"]
            if last_status == "WAITING_REVIEW":
                print(f"    reached WAITING_REVIEW after {waited}s")
                break
            print(f"    [{waited}s] status={last_status}")
        time.sleep(5)
        waited += 5
    if last_status != "WAITING_REVIEW":
        print(f"[3] FAIL: status={last_status} after {waited}s")
        return 1

    # 4. get detail and confirm fields
    status, det = http("GET", f"/documents/{doc_id}", token=token)
    d = det["data"]
    print(f"[4] doc title={d.get('title')!r}")
    print(f"    channel_suggested={d.get('suggested_publish_channel')}")
    print(f"    region={d.get('province')!r}/{d.get('city')!r}/{d.get('district')!r}")

    # confirm fields
    status, resp = http("GET", f"/documents/{doc_id}/fields", token=token)
    if status == 200:
        fields = resp.get("data", [])
        print(f"    fields={len(fields)}")
        for f in fields:
            fid = f.get("id")
            if f.get("review_status") != "CONFIRMED":
                http("PUT", f"/documents/{doc_id}/fields/{fid}", {
                    "value": f.get("field_value", ""), "confirmed": True}, token=token)
                print(f"    confirmed field {fid}")
            else:
                print(f"    field {fid} already confirmed")

    # 5. review
    status, resp = http("POST", f"/documents/{doc_id}/review", {
        "comment": "\u5b98\u65b9\u6d3b\u52a8\u5185\u5bb9\uff0c\u5ba1\u6838\u901a\u8fc7"}, token=token)
    print(f"[5] review: status={status}")
    if status != 200:
        print(f"    resp={resp}")
        return 1

    # 6. publish to ACTIVITY
    title = d.get("title") or TITLE_EXPECTED
    source_name = d.get("source_name") or "\u4e0a\u6d77\u5e02\u4eba\u6c11\u653f\u5e9c"
    status, resp = http("POST", f"/documents/{doc_id}/publish", {
        "title": title,
        "category": "\u793e\u533a\u6d3b\u52a8",
        "sourceName": source_name,
        "sourceUrl": URL,
        "allowPublicOriginal": False,
        "publishChannel": "ACTIVITY",
        "promoteToRecommend": True,
        "importanceLevel": "NORMAL",
    }, token=token)
    print(f"[6] publish: status={status}")
    if status != 200:
        print(f"    resp={resp}")
        return 1

    # 7. verify
    status, after = http("GET", f"/documents/{doc_id}", token=token)
    a = after["data"]
    print(f"[7-after] status={a['processing_status']} channel={a.get('publish_channel')}")

    _, pub = http("GET", "/public/items")
    found = [p for p in pub["data"] if p.get("title") == title]
    print(f"    public visible={len(found)>0}")

    ok = (a["processing_status"] == "PUBLISHED"
          and a.get("publish_channel") == "ACTIVITY"
          and len(found) > 0)
    print(f"\nGATE P0C_ACTIVITY_IMPORT = {'PASS' if ok else 'FAIL'}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
