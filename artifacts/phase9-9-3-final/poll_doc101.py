#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Poll doc 101 until AI processing completes, check image_reviewed."""
import json
import time
import urllib.request
import urllib.error

API = "http://127.0.0.1:8080/api"

def http(method, path, body=None, token=None):
    url = API + path
    headers = {"Content-Type": "application/json; charset=utf-8"}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body else None
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

_, resp = http("POST", "/auth/login", {"username": "platform_admin", "password": "Jianda@123"})
token = resp["data"]["token"]

# Poll
for i in range(60):
    status, resp = http("GET", "/documents/101", token=token)
    if status != 200:
        print(f"[{i*5}s] error {status}")
        time.sleep(5)
        continue
    d = resp["data"]
    ps = d["processing_status"]
    ir = d.get("image_reviewed")
    sc = d.get("suggested_publish_channel")
    cc = d.get("channel_confidence")
    print(f"[{i*5}s] status={ps} image_reviewed={ir} suggested={sc} confidence={cc}")
    if ps == "WAITING_REVIEW":
        print("Reached WAITING_REVIEW!")
        print(f"  title={d.get('title')!r}")
        print(f"  source_name={d.get('source_name')!r}")
        print(f"  cover_image_url={d.get('cover_image_url')!r}")
        print(f"  cover_image_type={d.get('cover_image_type')!r}")
        print(f"  image_reviewed={d.get('image_reviewed')}")
        break
    if ps == "UPLOADED" and i > 0:
        # still UPLOADED, maybe process didn't actually trigger
        print("  still UPLOADED, retrying process...")
        http("POST", "/documents/101/process", {}, token=token)
    time.sleep(5)
