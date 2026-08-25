#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Full publish flow for doc 101: confirm fields, review, publish to ACTIVITY.
If publish fails due to image_reviewed, download cover and upload it."""
import json
import sys
import time
import uuid
import urllib.request
import urllib.error

API = "http://127.0.0.1:8080/api"
DOC_ID = 101


def http(method, path, body=None, token=None):
    url = API + path
    headers = {"Content-Type": "application/json; charset=utf-8"}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body else None
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw}


def upload_cover(doc_id, image_url, token):
    """Download image and upload as cover via multipart form-data."""
    print(f"  downloading cover from {image_url[:80]}...")
    req = urllib.request.Request(image_url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        image_data = resp.read()
    ext = "png" if image_url.lower().endswith(".png") else "jpg"
    ctype = "image/png" if ext == "png" else "image/jpeg"
    print(f"  downloaded {len(image_data)} bytes")

    boundary = uuid.uuid4().hex
    body = b"--" + boundary.encode() + b"\r\n"
    body += b'Content-Disposition: form-data; name="file"; filename="cover.' + ext.encode() + b'"\r\n'
    body += b"Content-Type: " + ctype.encode() + b"\r\n\r\n"
    body += image_data + b"\r\n"
    body += b"--" + boundary.encode() + b"--\r\n"

    url = API + f"/documents/{doc_id}/cover"
    req = urllib.request.Request(url, data=body, method="POST")
    req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
    req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return e.code, {"raw": e.read().decode("utf-8", errors="replace")}


def main():
    _, resp = http("POST", "/auth/login", {"username": "platform_admin", "password": "Jianda@123"})
    token = resp["data"]["token"]
    print("[1] login OK")

    # confirm fields
    _, resp = http("GET", f"/documents/{DOC_ID}/fields", token=token)
    fields = resp.get("data", [])
    print(f"[2] fields={len(fields)}")
    for f in fields:
        fid = f.get("id")
        if f.get("review_status") != "CONFIRMED":
            http("PUT", f"/documents/{DOC_ID}/fields/{fid}",
                 {"value": f.get("field_value", ""), "confirmed": True}, token=token)
            print(f"    confirmed field {fid}: {f.get('field_label')!r}")
        else:
            print(f"    field {fid} already confirmed")

    # review
    status, resp = http("POST", f"/documents/{DOC_ID}/review",
                        {"comment": "\u5b98\u65b9\u6d3b\u52a8\u5185\u5bb9\uff0c\u5ba1\u6838\u901a\u8fc7"}, token=token)
    print(f"[3] review: status={status}")
    if status != 200:
        print(f"    {resp}")
        return 1

    # try publish to ACTIVITY
    _, det = http("GET", f"/documents/{DOC_ID}", token=token)
    d = det["data"]
    title = d.get("title")
    source_name = d.get("source_name") or "\u4e0a\u6d77\u5e02\u4eba\u6c11\u653f\u5e9c"
    cover_url = d.get("cover_image_url", "")

    status, resp = http("POST", f"/documents/{DOC_ID}/publish", {
        "title": title,
        "category": "\u793e\u533a\u6d3b\u52a8",
        "sourceName": source_name,
        "sourceUrl": d.get("original_url", ""),
        "allowPublicOriginal": False,
        "publishChannel": "ACTIVITY",
        "promoteToRecommend": True,
        "importanceLevel": "NORMAL",
    }, token=token)
    print(f"[4] publish: status={status}")
    if status != 200:
        print(f"    resp={resp}")
        # if image_reviewed issue, upload cover and retry
        msg = resp.get("message", "")
        if "\u56fe\u7247" in msg or "\u5c01\u9762" in msg or "image" in msg.lower():
            print("[4] image review required, uploading cover...")
            status, resp = upload_cover(DOC_ID, cover_url, token)
            print(f"    cover upload: status={status} {resp}")
            if status == 200:
                # re-confirm fields (cover upload might have changed something)
                # retry publish
                status, resp = http("POST", f"/documents/{DOC_ID}/publish", {
                    "title": title,
                    "category": "\u793e\u533a\u6d3b\u52a8",
                    "sourceName": source_name,
                    "sourceUrl": d.get("original_url", ""),
                    "allowPublicOriginal": False,
                    "publishChannel": "ACTIVITY",
                    "promoteToRecommend": True,
                    "importanceLevel": "NORMAL",
                }, token=token)
                print(f"[4b] publish retry: status={status}")
                if status != 200:
                    print(f"    {resp}")
                    return 1
            else:
                return 1
        else:
            return 1

    # verify
    _, after = http("GET", f"/documents/{DOC_ID}", token=token)
    a = after["data"]
    print(f"[5-after] status={a['processing_status']} channel={a.get('publish_channel')} "
          f"image_reviewed={a.get('image_reviewed')}")

    _, pub = http("GET", "/public/items")
    found = [p for p in pub["data"] if p.get("title") == title]
    print(f"    public visible={len(found)>0}")

    ok = (a["processing_status"] == "PUBLISHED"
          and a.get("publish_channel") == "ACTIVITY"
          and len(found) > 0)
    print(f"\nGATE P0C_DOC101_ACTIVITY = {'PASS' if ok else 'FAIL'}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
