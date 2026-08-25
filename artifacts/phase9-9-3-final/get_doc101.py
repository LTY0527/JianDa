#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Get doc 101 full detail to find ai_queue_id and image review fields."""
import json
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

# doc detail
_, det = http("GET", "/documents/101", token=token)
print(json.dumps(det["data"], ensure_ascii=False, indent=2))
