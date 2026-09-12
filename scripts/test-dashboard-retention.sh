#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
set -a
source "${ROOT_DIR}/config/ruoyi-local.env"
set +a
[[ "${MYSQL_HOST:-127.0.0.1}" == '127.0.0.1' || "${MYSQL_HOST:-}" == 'localhost' ]] || { echo '只允许本地 MySQL'; exit 1; }
[[ "${MYSQL_DATABASE}" =~ ^[A-Za-z0-9_]+$ ]] || exit 1
export RETENTION_TEST_DATABASE="cockpit_retention_test_$(date +%Y%m%d%H%M%S)_$$"
export RETENTION_TEST_PORT="${RETENTION_TEST_PORT:-18088}"
FRONT_PORT="${RETENTION_TEST_FRONTEND_PORT:-15173}"
TEST_REDIS_PORT="${RETENTION_TEST_REDIS_PORT:-16389}"
BACKEND_PID=''; FRONTEND_PID=''; REDIS_PID=''; FIXTURE_PID=''; CREATED=0
for port in "$RETENTION_TEST_PORT" "$FRONT_PORT" "$TEST_REDIS_PORT"; do
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then echo "测试端口 ${port} 被占用"; exit 1; fi
done
mysql_server() { MYSQL_PWD="$MYSQL_PASSWORD" mysql --protocol=TCP -h"${MYSQL_HOST:-127.0.0.1}" -P"${MYSQL_PORT:-3306}" -u"${MYSQL_USERNAME:-root}" "$@"; }
dump_local() { MYSQL_PWD="$MYSQL_PASSWORD" mysqldump --protocol=TCP -h"${MYSQL_HOST:-127.0.0.1}" -P"${MYSQL_PORT:-3306}" -u"${MYSQL_USERNAME:-root}" --single-transaction --no-tablespaces --skip-triggers "$@"; }
cleanup() {
  local rc=$?
  trap - EXIT INT TERM
  [[ -z "$FRONTEND_PID" ]] || kill "$FRONTEND_PID" 2>/dev/null || true
  [[ -z "$BACKEND_PID" ]] || kill "$BACKEND_PID" 2>/dev/null || true
  [[ -z "$REDIS_PID" ]] || kill "$REDIS_PID" 2>/dev/null || true
  [[ -z "$FIXTURE_PID" ]] || kill "$FIXTURE_PID" 2>/dev/null || true
  [[ -z "$FRONTEND_PID" ]] || wait "$FRONTEND_PID" 2>/dev/null || true
  [[ -z "$BACKEND_PID" ]] || wait "$BACKEND_PID" 2>/dev/null || true
  [[ -z "$REDIS_PID" ]] || wait "$REDIS_PID" 2>/dev/null || true
  [[ -z "$FIXTURE_PID" ]] || wait "$FIXTURE_PID" 2>/dev/null || true
  if [[ "$CREATED" == 1 && "$RETENTION_TEST_DATABASE" =~ ^cockpit_retention_test_[0-9_]+$ ]]; then
    mysql_server -e "DROP DATABASE \`$RETENTION_TEST_DATABASE\`" >/dev/null
  fi
  exit "$rc"
}
trap cleanup EXIT INT TERM
[[ "$(mysql_server -NBe "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='$RETENTION_TEST_DATABASE'")" == 0 ]] || exit 1
mysql_server -e "CREATE DATABASE \`$RETENTION_TEST_DATABASE\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
CREATED=1
mkdir -p "$ROOT_DIR/.local/retention-test"
rm -f "$ROOT_DIR/.local/retention-test/summary.json" "$ROOT_DIR/.local/retention-test/frontend.log"
# 只复制结构、菜单和权限字典，不复制真实账号、业务、密钥、来源、系统参数和定时作业。
dump_local --no-data "$MYSQL_DATABASE" | mysql_server "$RETENTION_TEST_DATABASE"
dump_local --no-create-info "$MYSQL_DATABASE" sys_role sys_user_role sys_role_menu sys_menu sys_dict_type sys_dict_data | mysql_server "$RETENTION_TEST_DATABASE"
mysql_server "$RETENTION_TEST_DATABASE" -e "INSERT INTO sys_config(config_name,config_key,config_value,config_type,create_by,create_time) VALUES('隔离测试验证码','sys.account.captchaEnabled','false','Y','retention-test',NOW());"
mysql_server "$RETENTION_TEST_DATABASE" < "$ROOT_DIR/ruoyi-backend/sql/dashboard_integration_retention.sql" >/dev/null
mysql_server "$RETENTION_TEST_DATABASE" < "$ROOT_DIR/ruoyi-backend/sql/dashboard_integration_retention.sql" >/dev/null
for migration in dashboard_integration_monitoring.sql dashboard_integration_policy.sql; do
  mysql_server "$RETENTION_TEST_DATABASE" < "$ROOT_DIR/ruoyi-backend/sql/$migration" >/dev/null
  mysql_server "$RETENTION_TEST_DATABASE" < "$ROOT_DIR/ruoyi-backend/sql/$migration" >/dev/null
done
echo '隔离数据库已准备，保留期迁移重复执行通过。'
export MYSQL_DATABASE="$RETENTION_TEST_DATABASE"
redis-server --bind 127.0.0.1 --port "$TEST_REDIS_PORT" --save '' --appendonly no > "$ROOT_DIR/.local/retention-test/redis.log" 2>&1 &
REDIS_PID=$!
export REDIS_HOST=127.0.0.1 REDIS_PORT="$TEST_REDIS_PORT" REDIS_DATABASE=0 REDIS_PASSWORD=''
export TOKEN_SECRET="$(openssl rand -hex 48)"
export DASHBOARD_DATASOURCE_ENCRYPTION_KEY="$(openssl rand -hex 32)"
export DASHBOARD_INTEGRATION_RECOVERY_INTERVAL_SECONDS=10
export DASHBOARD_INTEGRATION_RETENTION_CLEANUP_INTERVAL_SECONDS=60
export DASHBOARD_WEBSOCKET_ALLOWED_ORIGIN="http://localhost:$FRONT_PORT"
if [[ "${INTEGRATION_UNIFICATION_TEST:-0}" == 1 ]]; then
  export INTEGRATION_TEST_HTTP_PORT="${INTEGRATION_TEST_HTTP_PORT:-18888}"
  export INTEGRATION_TEST_WS_PORT="${INTEGRATION_TEST_WS_PORT:-18889}"
  for port in "$INTEGRATION_TEST_HTTP_PORT" "$INTEGRATION_TEST_WS_PORT"; do
    if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then echo "模拟上游端口 ${port} 被占用"; exit 1; fi
  done
  export DASHBOARD_INTEGRATION_ALLOW_LOCAL_DEVELOPMENT_TARGETS=true
  export DASHBOARD_INTEGRATION_ALLOW_PLAIN_HTTP=true
  "${INTEGRATION_TEST_PYTHON:-python3}" "$ROOT_DIR/scripts/fixtures/integration-upstream.py" > "$ROOT_DIR/.local/retention-test/upstream.log" 2>&1 &
  FIXTURE_PID=$!
fi
cp "$ROOT_DIR/ruoyi-backend/ruoyi-admin/target/ruoyi-admin.jar" "$ROOT_DIR/.local/retention-test/ruoyi-admin.jar"
java -Dserver.address=127.0.0.1 -Dserver.port="$RETENTION_TEST_PORT" -DLOG_PATH="$ROOT_DIR/.local/retention-test/logs" \
  -Druoyi.profile="$ROOT_DIR/.local/retention-test/uploads" \
  -jar "$ROOT_DIR/.local/retention-test/ruoyi-admin.jar" > "$ROOT_DIR/.local/retention-test/backend.log" 2>&1 &
BACKEND_PID=$!
for i in $(seq 1 90); do
  if curl --silent --fail "http://127.0.0.1:$RETENTION_TEST_PORT/captchaImage" >/dev/null; then break; fi
  kill -0 "$BACKEND_PID" 2>/dev/null || { echo '隔离后端启动失败，请检查本地日志'; exit 1; }
  sleep 1
done
if [[ "${INTEGRATION_UNIFICATION_ONLY:-0}" != 1 ]]; then
  python3 "$ROOT_DIR/scripts/test-dashboard-retention.py"
fi
if [[ "${INTEGRATION_UNIFICATION_TEST:-0}" == 1 ]]; then
  python3 "$ROOT_DIR/scripts/test-dashboard-unification.py"
fi
if [[ "${RETENTION_KEEP_RUNNING:-0}" == 1 ]]; then
  VITE_APP_BACKEND_URL="http://127.0.0.1:$RETENTION_TEST_PORT" \
    "$ROOT_DIR/ruoyi-ui/node_modules/.bin/vite" --host localhost --port "$FRONT_PORT" --strictPort \
    --config "$ROOT_DIR/ruoyi-ui/vite.config.js" "$ROOT_DIR/ruoyi-ui" > "$ROOT_DIR/.local/retention-test/frontend.log" 2>&1 &
  FRONTEND_PID=$!
  echo "浏览器验收可访问 http://localhost:${FRONT_PORT}；结束此脚本后自动停止测试服务并删除临时库。"
  wait "$FRONTEND_PID"
fi
