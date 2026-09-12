#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ROOT_DIR}/config/ruoyi-local.env"
TEST_BACKEND_PORT="${TEST_BACKEND_PORT:-18080}"
TEST_MOCK_PORT="${TEST_MOCK_PORT:-18081}"
TEST_REDIS_DATABASE="${TEST_REDIS_DATABASE:-15}"
SKIP_BUILD="${SKIP_BUILD:-0}"
BACKEND_PID=""
SECOND_BACKEND_PID=""
TEST_SECOND_BACKEND_PORT="${TEST_SECOND_BACKEND_PORT:-18082}"
RUN_EXTENDED="${RUN_EXTENDED:-1}"
CAPTCHA_ORIGINAL=""

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "缺少 ${ENV_FILE}，无法启动隔离接入回归。" >&2
  exit 1
fi

# shellcheck disable=SC1090
set -a
source "${ENV_FILE}"
set +a
: "${MYSQL_HOST:=127.0.0.1}"
: "${MYSQL_PORT:=3306}"
: "${MYSQL_DATABASE:=engineering_data_cockpit}"
: "${MYSQL_USERNAME:=root}"
: "${MYSQL_PASSWORD:?MYSQL_PASSWORD 不能为空}"

for command_name in curl mysql python3 redis-cli; do
  command -v "${command_name}" >/dev/null 2>&1 || { echo "缺少命令：${command_name}" >&2; exit 1; }
done
if [[ "${SKIP_BUILD}" != "1" ]]; then
  command -v mvn >/dev/null 2>&1 || { echo "缺少命令：mvn" >&2; exit 1; }
fi
for port in "${TEST_BACKEND_PORT}" "${TEST_MOCK_PORT}" "${TEST_SECOND_BACKEND_PORT}"; do
  if lsof -nP -iTCP:"${port}" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "隔离测试端口 ${port} 已被占用。" >&2
    exit 1
  fi
done

mysql_exec() {
  MYSQL_PWD="${MYSQL_PASSWORD}" mysql --protocol=TCP -h"${MYSQL_HOST}" -P"${MYSQL_PORT}" \
    -u"${MYSQL_USERNAME}" "${MYSQL_DATABASE}" "$@"
}

cleanup() {
  local exit_code=$?
  set +e
  if [[ -n "${BACKEND_PID}" ]]; then
    kill "${BACKEND_PID}" >/dev/null 2>&1
    wait "${BACKEND_PID}" >/dev/null 2>&1
  fi
  if [[ -n "${SECOND_BACKEND_PID}" ]]; then kill "${SECOND_BACKEND_PID}" >/dev/null 2>&1; wait "${SECOND_BACKEND_PID}" >/dev/null 2>&1; fi
  mysql_exec -e "
    delete a from dashboard_integration_audit a join dashboard_integration_media_ref m
      on a.resource_code=sha2(m.media_ref,256) where m.source_code like 'mvp-source-%';
    delete from dashboard_integration_audit where resource_code like 'mvp-source-%' or resource_code like 'mvp-in-%';
    delete from dashboard_integration_alert where resource_code like 'mvp-source-%' or resource_code like 'mvp-in-%';
    delete from dashboard_integration_snapshot where source_code like 'mvp-source-%';
    delete s from dashboard_integration_signal s left join dashboard_integration i on i.integration_id=s.integration_id where i.integration_id is null or i.integration_code like 'mvp-in-%';
    delete from dashboard_integration_media_ref where source_code like 'mvp-source-%';
    delete from dashboard_integration where integration_code like 'mvp-in-%';
    delete from dashboard_dataset where dataset_code like 'mvp-dataset-%';
    delete e from dashboard_data_source_endpoint e
      join dashboard_data_source s on s.data_source_id=e.data_source_id
      where s.source_code like 'mvp-source-%';
    delete from dashboard_data_source where source_code like 'mvp-source-%';
    delete from dashboard_page where page_code like 'mvp-media-%';
  " >/dev/null 2>&1
  if [[ -n "${CAPTCHA_ORIGINAL}" ]]; then
    mysql_exec -e "update sys_config set config_value='${CAPTCHA_ORIGINAL}' where config_key='sys.account.captchaEnabled';" >/dev/null 2>&1
  fi
  redis-cli -n "${TEST_REDIS_DATABASE}" del sys_config:sys.account.captchaEnabled >/dev/null 2>&1
  exit "${exit_code}"
}
trap cleanup EXIT INT TERM

mkdir -p "${ROOT_DIR}/.local"
"${ROOT_DIR}/scripts/init-local-mysql.sh" >/dev/null
if [[ "${SKIP_BUILD}" != "1" ]]; then
  (cd "${ROOT_DIR}/ruoyi-backend" && mvn -DskipTests -pl ruoyi-admin -am package \
    >"${ROOT_DIR}/.local/integration-mvp-package.log")
fi

CAPTCHA_ORIGINAL="$(mysql_exec -NBe "select config_value from sys_config where config_key='sys.account.captchaEnabled' limit 1")"
if [[ "${CAPTCHA_ORIGINAL}" != "true" && "${CAPTCHA_ORIGINAL}" != "false" ]]; then
  echo "验证码配置值不符合预期，停止隔离回归。" >&2
  exit 1
fi
mysql_exec -e "update sys_config set config_value='false' where config_key='sys.account.captchaEnabled';" >/dev/null
redis-cli -n "${TEST_REDIS_DATABASE}" del sys_config:sys.account.captchaEnabled >/dev/null 2>&1 || true

if [[ -z "${DASHBOARD_DATASOURCE_ENCRYPTION_KEY:-}" ]]; then
  DASHBOARD_DATASOURCE_ENCRYPTION_KEY="$(python3 -c 'import secrets; print(secrets.token_urlsafe(48))')"
  export DASHBOARD_DATASOURCE_ENCRYPTION_KEY
fi

export DASHBOARD_INTEGRATION_RECOVERY_INTERVAL_SECONDS=10
export TEST_CREDENTIAL_DIRECTORY="${ROOT_DIR}/.local/integration-consolidation/credentials"
export DASHBOARD_INTEGRATION_CREDENTIALS_DIRECTORY="${TEST_CREDENTIAL_DIRECTORY}"

BACKEND_PORT="${TEST_BACKEND_PORT}" REDIS_DATABASE="${TEST_REDIS_DATABASE}" \
  DASHBOARD_INTEGRATION_ALLOW_LOCAL_DEVELOPMENT_TARGETS=true \
  DASHBOARD_INTEGRATION_ALLOW_PLAIN_HTTP=true \
  "${ROOT_DIR}/scripts/start-backend.sh" >"${ROOT_DIR}/.local/integration-mvp-backend.log" 2>&1 &
BACKEND_PID=$!

ready=0
for _ in $(seq 1 120); do
  if curl -fsS --max-time 2 "http://127.0.0.1:${TEST_BACKEND_PORT}/captchaImage" >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 0.5
done
if [[ "${ready}" != "1" ]]; then
  echo "隔离接入后端未就绪，日志见 .local/integration-mvp-backend.log。" >&2
  exit 1
fi

if [[ "${RUN_BASELINE:-1}" == "1" ]]; then
  TEST_BACKEND_PORT="${TEST_BACKEND_PORT}" TEST_MOCK_PORT="${TEST_MOCK_PORT}" \
    python3 "${ROOT_DIR}/scripts/test-dashboard-integration.py"
fi
if [[ "${RUN_EXTENDED}" == "1" ]]; then
  BACKEND_PORT="${TEST_SECOND_BACKEND_PORT}" REDIS_DATABASE="${TEST_REDIS_DATABASE}" \
    DASHBOARD_INTEGRATION_ALLOW_LOCAL_DEVELOPMENT_TARGETS=true DASHBOARD_INTEGRATION_ALLOW_PLAIN_HTTP=true \
    "${ROOT_DIR}/scripts/start-backend.sh" >"${ROOT_DIR}/.local/integration-consolidation/second-backend.log" 2>&1 &
  SECOND_BACKEND_PID=$!
  ready=0
  for _ in $(seq 1 80); do
    if curl -fsS --max-time 2 "http://127.0.0.1:${TEST_SECOND_BACKEND_PORT}/captchaImage" >/dev/null 2>&1; then ready=1; break; fi
    sleep 0.5
  done
  [[ "${ready}" == "1" ]] || { echo "第二后端未就绪" >&2; exit 1; }
  TEST_PRIMARY_PID="${BACKEND_PID}" TEST_BACKEND_PORT="${TEST_BACKEND_PORT}" TEST_SECOND_BACKEND_PORT="${TEST_SECOND_BACKEND_PORT}" TEST_MOCK_PORT="${TEST_MOCK_PORT}" \
    python3 "${ROOT_DIR}/scripts/test-dashboard-integration-extended.py"
fi
echo "第三方数据接入隔离回归通过；MySQL/Redis 共享服务保持运行。"
