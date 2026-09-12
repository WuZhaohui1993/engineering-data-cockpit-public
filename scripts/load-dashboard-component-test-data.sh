#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ROOT_DIR}/config/ruoyi-local.env"
SQL_FILE="${ROOT_DIR}/ruoyi-backend/sql/dashboard_component_test_data.sql"
ACTION="${1:-load}"
TEST_CODES_SQL="'test-component-kpi','test-component-series','test-component-calendar','test-component-table','test-component-alerts','test-component-realtime','test-component-map-points','test-component-map-flows','test-component-weather','test-component-filtered','test-component-empty'"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "缺少 ${ENV_FILE}，请先准备本地 MySQL 配置。" >&2
  exit 1
fi

if [[ ! -f "${SQL_FILE}" ]]; then
  echo "缺少测试数据脚本：${SQL_FILE}" >&2
  exit 1
fi

# shellcheck disable=SC1090
source "${ENV_FILE}"
: "${MYSQL_HOST:=127.0.0.1}"
: "${MYSQL_PORT:=3306}"
: "${MYSQL_DATABASE:=engineering_data_cockpit}"
: "${MYSQL_USERNAME:=root}"
: "${MYSQL_PASSWORD:?MYSQL_PASSWORD 不能为空}"

mysql_db() {
  MYSQL_PWD="${MYSQL_PASSWORD}" mysql --protocol=TCP \
    -h"${MYSQL_HOST}" -P"${MYSQL_PORT}" -u"${MYSQL_USERNAME}" "${MYSQL_DATABASE}" "$@"
}

case "${ACTION}" in
  load)
    mysql_db < "${SQL_FILE}"
    ;;
  remove)
    mysql_db <<'SQL'
DELETE FROM dashboard_dataset
WHERE dataset_code IN (
  'test-component-kpi',
  'test-component-series',
  'test-component-calendar',
  'test-component-table',
  'test-component-alerts',
  'test-component-realtime',
  'test-component-map-points',
  'test-component-map-flows',
  'test-component-weather',
  'test-component-filtered',
  'test-component-empty'
);
DELETE FROM dashboard_dataset_group
WHERE group_code = 'component-test'
  AND NOT EXISTS (
    SELECT 1 FROM dashboard_dataset WHERE group_code = 'component-test'
  );
SQL
    echo "已移除固定编码的大屏组件测试数据集。"
    exit 0
    ;;
  *)
    echo "用法：$0 [load|remove]" >&2
    exit 2
    ;;
esac

dataset_count="$(mysql_db -NBe "SELECT COUNT(*) FROM dashboard_dataset WHERE dataset_code IN (${TEST_CODES_SQL})")"
invalid_json_count="$(mysql_db -NBe "SELECT COUNT(*) FROM dashboard_dataset WHERE dataset_code IN (${TEST_CODES_SQL}) AND (JSON_VALID(config_json) = 0 OR JSON_VALID(field_schema_json) = 0 OR JSON_VALID(param_schema_json) = 0)")"
inactive_count="$(mysql_db -NBe "SELECT COUNT(*) FROM dashboard_dataset WHERE dataset_code IN (${TEST_CODES_SQL}) AND status <> 'ACTIVE'")"

if [[ "${dataset_count}" != "11" ]]; then
  echo "测试数据集数量异常：期望 11，实际 ${dataset_count}。" >&2
  exit 1
fi
if [[ "${invalid_json_count}" != "0" ]]; then
  echo "存在 ${invalid_json_count} 个 JSON 配置不合法的测试数据集。" >&2
  exit 1
fi
if [[ "${inactive_count}" != "0" ]]; then
  echo "存在 ${inactive_count} 个未启用的测试数据集。" >&2
  exit 1
fi

echo "大屏组件测试数据已就绪：group=component-test, datasets=${dataset_count}, invalidJson=${invalid_json_count}, inactive=${inactive_count}"
