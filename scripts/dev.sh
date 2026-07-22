#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PIDS=()
cleanup() { for pid in "${PIDS[@]:-}"; do kill "$pid" 2>/dev/null || true; done; }
trap cleanup EXIT INT TERM
AI_PYTHON="$ROOT/services/ai-service/.venv/bin/python"
if [[ ! -x "$AI_PYTHON" ]]; then
  echo "AI Python interpreter not found: $AI_PYTHON" >&2
  echo "Create it with a Python 3.11+ interpreter: python3 -m venv services/ai-service/.venv" >&2
  exit 1
fi
AI_VERSION="$($AI_PYTHON -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}")')"
AI_SUPPORTED="$($AI_PYTHON -c 'import sys; print("yes" if sys.version_info >= (3, 11) else "no")')"
echo "AI interpreter: Python $AI_VERSION"
if [[ "$AI_SUPPORTED" != "yes" ]]; then
  echo "Python 3.11 or newer is required. Python 3.9 is not supported." >&2
  exit 1
fi
(cd "$ROOT/services/ai-service" && .venv/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port 8001) & PIDS+=("$!")
(cd "$ROOT/services/backend" && mvn spring-boot:run) & PIDS+=("$!")
(cd "$ROOT" && npm run dev:institution) & PIDS+=("$!")
(cd "$ROOT" && npm run dev:h5) & PIDS+=("$!")
echo "Services started. Verify:"
echo "  Institution web: http://127.0.0.1:5173"
echo "  User H5:         http://127.0.0.1:5174"
echo "  Backend OpenAPI: http://127.0.0.1:8080/v3/api-docs"
echo "  FastAPI health:  http://127.0.0.1:8001/health"
wait
