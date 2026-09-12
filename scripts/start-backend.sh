#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ROOT_DIR}/config/ruoyi-local.env"
if [[ ! -f "${ENV_FILE}" ]]; then
  echo "缺少 ${ENV_FILE}。请先复制 config/ruoyi-local.env.example 并填写本地配置。" >&2
  exit 1
fi

BACKEND_PORT_OVERRIDE="${BACKEND_PORT:-}"
FRONTEND_PORT_OVERRIDE="${FRONTEND_PORT:-}"
REDIS_DATABASE_OVERRIDE="${REDIS_DATABASE:-}"
WEBSOCKET_ORIGIN_OVERRIDE="${DASHBOARD_WEBSOCKET_ALLOWED_ORIGIN:-}"
# shellcheck disable=SC1090
set -a
source "${ENV_FILE}"
set +a
: "${MYSQL_HOST:=127.0.0.1}"
: "${MYSQL_PORT:=3306}"
: "${MYSQL_DATABASE:=engineering_data_cockpit}"
: "${MYSQL_USERNAME:=root}"
: "${MYSQL_PASSWORD:?MYSQL_PASSWORD 不能为空}"
: "${LABOR_MYSQL_HOST:=127.0.0.1}"
: "${LABOR_MYSQL_PORT:=3306}"
: "${LABOR_MYSQL_DATABASE:=labor}"
: "${LABOR_MYSQL_USERNAME:=${MYSQL_USERNAME}}"
: "${LABOR_MYSQL_PASSWORD:=${MYSQL_PASSWORD}}"
: "${REDIS_HOST:=127.0.0.1}"
: "${REDIS_PORT:=6379}"
: "${REDIS_DATABASE:=0}"
: "${BACKEND_PORT:=8080}"
if [[ -z "${TOKEN_SECRET:-}" ]]; then
  TOKEN_SECRET_FILE="${ROOT_DIR}/.local/ruoyi/token-secret"
  mkdir -p "$(dirname "${TOKEN_SECRET_FILE}")"
  if [[ ! -s "${TOKEN_SECRET_FILE}" ]]; then
    umask 077
    openssl rand -hex 64 -out "${TOKEN_SECRET_FILE}"
  fi
  TOKEN_SECRET="$(tr -d '\r\n' < "${TOKEN_SECRET_FILE}")"
fi
export TOKEN_SECRET
if [[ -n "${BACKEND_PORT_OVERRIDE}" ]]; then
  BACKEND_PORT="${BACKEND_PORT_OVERRIDE}"
fi
if [[ -n "${FRONTEND_PORT_OVERRIDE}" ]]; then
  FRONTEND_PORT="${FRONTEND_PORT_OVERRIDE}"
fi
if [[ -n "${WEBSOCKET_ORIGIN_OVERRIDE}" ]]; then
  DASHBOARD_WEBSOCKET_ALLOWED_ORIGIN="${WEBSOCKET_ORIGIN_OVERRIDE}"
fi
if [[ -n "${REDIS_DATABASE_OVERRIDE}" ]]; then
  REDIS_DATABASE="${REDIS_DATABASE_OVERRIDE}"
fi

if ! command -v java >/dev/null 2>&1; then
  echo "找不到 Java。后端要求 JDK 17+。" >&2
  exit 1
fi

if [[ -z "${JAVA_HOME:-}" ]] && command -v /usr/libexec/java_home >/dev/null 2>&1; then
  JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
fi
JAVA_BIN="${JAVA_HOME:+${JAVA_HOME}/bin/}java"
command -v "${JAVA_BIN}" >/dev/null 2>&1 || { echo "找不到 JDK 17 的 java。" >&2; exit 1; }

JAR="${ROOT_DIR}/ruoyi-backend/ruoyi-admin/target/ruoyi-admin.jar"
if [[ ! -f "${JAR}" ]]; then
  echo "未找到后端构建产物，先执行 Maven 构建..."
  (
    cd "${ROOT_DIR}/ruoyi-backend"
    JAVA_HOME="${JAVA_HOME:-}" mvn -DskipTests package
  )
fi

mkdir -p "${ROOT_DIR}/.local/ruoyi/uploadPath"
mkdir -p "${ROOT_DIR}/.local/ruoyi/logs"
mkdir -p "${ROOT_DIR}/.local/ruoyi/runtime"
# Spring Boot may lazily load nested JAR classes after startup. Run from an
# immutable copy so a later Maven build cannot replace the file underneath a
# live process and turn ordinary error responses into class-loading failures.
RUNTIME_JAR="${ROOT_DIR}/.local/ruoyi/runtime/ruoyi-admin-${BACKEND_PORT}-$(date +%Y%m%d%H%M%S)-$$.jar"
cp "${JAR}" "${RUNTIME_JAR}"
JDBC_URL="jdbc:mysql://${MYSQL_HOST}:${MYSQL_PORT}/${MYSQL_DATABASE}?useUnicode=true&characterEncoding=utf8&zeroDateTimeBehavior=convertToNull&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"
LABOR_JDBC_URL="${LABOR_MYSQL_URL:-jdbc:mysql://${LABOR_MYSQL_HOST}:${LABOR_MYSQL_PORT}/${LABOR_MYSQL_DATABASE}?useUnicode=true&characterEncoding=utf8&zeroDateTimeBehavior=convertToNull&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai}"
exec "${JAVA_BIN}" \
  -Dserver.port="${BACKEND_PORT}" \
  -DLOG_PATH="${ROOT_DIR}/.local/ruoyi/logs" \
  -Dspring.datasource.druid.master.url="${JDBC_URL}" \
  -Dspring.datasource.druid.master.username="${MYSQL_USERNAME}" \
  -Dspring.data.redis.host="${REDIS_HOST}" \
  -Dspring.data.redis.port="${REDIS_PORT}" \
  -Dspring.data.redis.database="${REDIS_DATABASE}" \
  -Ddashboard.datasource.labor.url="${LABOR_JDBC_URL}" \
  -Ddashboard.datasource.labor.username="${LABOR_MYSQL_USERNAME}" \
  -Ddashboard.api.labor.base-url="${LABOR_API_BASE_URL:-}" \
  -Ddashboard.api.labor.project-code="${LABOR_PROJECT_CODE:-}" \
  -Ddashboard.api.allowed-hosts="${DASHBOARD_API_ALLOWED_HOSTS:-127.0.0.1,localhost}" \
  -Ddashboard.integration.allow-local-development-targets="${DASHBOARD_INTEGRATION_ALLOW_LOCAL_DEVELOPMENT_TARGETS:-false}" \
  -Ddashboard.integration.allow-plain-http="${DASHBOARD_INTEGRATION_ALLOW_PLAIN_HTTP:-false}" \
  -Ddashboard.integration.recovery-interval-seconds="${DASHBOARD_INTEGRATION_RECOVERY_INTERVAL_SECONDS:-30}" \
  -Ddashboard.websocket.allowed-origin="${DASHBOARD_WEBSOCKET_ALLOWED_ORIGIN:-http://127.0.0.1:${FRONTEND_PORT:-5173}}" \
  -Druoyi.profile="${ROOT_DIR}/.local/ruoyi/uploadPath" \
  -jar "${RUNTIME_JAR}"
