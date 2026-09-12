#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ROOT_DIR}/config/ruoyi-local.env"
FRONTEND_HOST_OVERRIDE="${FRONTEND_HOST:-}"
FRONTEND_PORT_OVERRIDE="${FRONTEND_PORT:-}"
BACKEND_PORT_OVERRIDE="${BACKEND_PORT:-}"
if [[ -f "${ENV_FILE}" ]]; then
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
fi
: "${FRONTEND_HOST:=127.0.0.1}"
: "${FRONTEND_PORT:=5173}"
: "${BACKEND_PORT:=8080}"
if [[ -n "${FRONTEND_HOST_OVERRIDE}" ]]; then
  FRONTEND_HOST="${FRONTEND_HOST_OVERRIDE}"
fi
if [[ -n "${FRONTEND_PORT_OVERRIDE}" ]]; then
  FRONTEND_PORT="${FRONTEND_PORT_OVERRIDE}"
fi
if [[ -n "${BACKEND_PORT_OVERRIDE}" ]]; then
  BACKEND_PORT="${BACKEND_PORT_OVERRIDE}"
fi
: "${VITE_APP_BACKEND_URL:=http://127.0.0.1:${BACKEND_PORT}}"
export VITE_APP_BACKEND_URL

command -v node >/dev/null 2>&1 || { echo "找不到 Node.js。" >&2; exit 1; }
command -v pnpm >/dev/null 2>&1 || { echo "找不到 pnpm。" >&2; exit 1; }

if [[ ! -d "${ROOT_DIR}/ruoyi-ui/node_modules" ]]; then
  (
    cd "${ROOT_DIR}/ruoyi-ui"
    pnpm install --registry=https://registry.npmmirror.com
  )
fi

cd "${ROOT_DIR}/ruoyi-ui"
exec pnpm dev --host "${FRONTEND_HOST}" --port "${FRONTEND_PORT}"
