#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PIDS=()
cleanup() { for pid in "${PIDS[@]:-}"; do kill "$pid" 2>/dev/null || true; done; }
trap cleanup EXIT INT TERM
(cd "$ROOT/services/ai-service" && .venv/bin/python -m uvicorn app.main:app --host 0.0.0.0 --port 8001) & PIDS+=("$!")
(cd "$ROOT/services/backend" && mvn spring-boot:run) & PIDS+=("$!")
(cd "$ROOT" && npm run dev:institution) & PIDS+=("$!")
(cd "$ROOT" && npm run dev:h5) & PIDS+=("$!")
echo "简达已启动：机构端 http://localhost:5173，用户端 http://localhost:5174，后端 http://localhost:8080，AI http://localhost:8001"
wait

