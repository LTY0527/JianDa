#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Count public items per publish_channel to verify 7-channel coverage >= 5 each."""
import json
import sys
import urllib.request
import urllib.error
from collections import Counter

API = "http://127.0.0.1:8080/api"

TARGET = {"HEALTH": 5, "ELDERLY": 5, "MEALS": 5, "SERVICES": 5,
          "FRAUD": 5, "ACTIVITY": 5, "COMMUNITY": 5}


def http(method, path):
    url = API + path
    req = urllib.request.Request(url, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return e.code, {"error": e.read().decode("utf-8", errors="replace")}


def main():
    status, pub = http("GET", "/public/items")
    if status != 200:
        print(f"FAIL: public/items returned {status}")
        return 1
    items = pub["data"]
    channels = Counter(p.get("publish_channel") for p in items)
    print(f"Total public items: {len(items)}")
    print(f"Channel distribution:")
    all_ok = True
    for ch, target in TARGET.items():
        cnt = channels.get(ch, 0)
        mark = "OK" if cnt >= target else "MISSING"
        if cnt < target:
            all_ok = False
        print(f"  {ch:12s} {cnt:3d}  (target>={target})  [{mark}]")
    # also show recommend items
    rec = [p for p in items if p.get("promote_to_recommend")]
    print(f"\nRecommend stream: {len(rec)} items (target>=20)")
    rec_ok = len(rec) >= 20
    if not rec_ok:
        all_ok = False
    print(f"GATE CHANNEL_CONTENT_COVERAGE_ACCEPTANCE = {'PASS' if all_ok and rec_ok else 'FAIL'}")
    return 0 if all_ok and rec_ok else 1


if __name__ == "__main__":
    sys.exit(main())
