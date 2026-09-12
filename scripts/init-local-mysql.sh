#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ROOT_DIR}/config/ruoyi-local.env"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "缺少 ${ENV_FILE}。请先复制 config/ruoyi-local.env.example 并填写 MYSQL_PASSWORD。" >&2
  exit 1
fi

# shellcheck disable=SC1090
source "${ENV_FILE}"
: "${MYSQL_HOST:=127.0.0.1}"
: "${MYSQL_PORT:=3306}"
: "${MYSQL_DATABASE:=engineering_data_cockpit}"
: "${MYSQL_USERNAME:=root}"
: "${MYSQL_PASSWORD:?MYSQL_PASSWORD 不能为空}"

if [[ ! "${MYSQL_DATABASE}" =~ ^[A-Za-z0-9_]+$ ]]; then
  echo "MYSQL_DATABASE 只能包含字母、数字和下划线。" >&2
  exit 1
fi

mysql_root() {
  MYSQL_PWD="${MYSQL_PASSWORD}" mysql --protocol=TCP -h"${MYSQL_HOST}" -P"${MYSQL_PORT}" -u"${MYSQL_USERNAME}" "$@"
}

mysql_db() {
  mysql_root "${MYSQL_DATABASE}" "$@"
}

mysql_root -e "CREATE DATABASE IF NOT EXISTS \`${MYSQL_DATABASE}\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

if mysql_root -NBe \
  "SELECT 1 FROM information_schema.tables WHERE table_schema='${MYSQL_DATABASE}' AND table_name='sys_user' LIMIT 1" | grep -qx 1; then
  echo "${MYSQL_DATABASE} 已存在基础表，跳过重复初始化。"
else
  mysql_db < "${ROOT_DIR}/ruoyi-backend/sql/ry_20260320.sql"
  mysql_db < "${ROOT_DIR}/ruoyi-backend/sql/quartz.sql"
fi

if [[ -f "${ROOT_DIR}/ruoyi-backend/sql/dashboard.sql" ]]; then
  mysql_db < "${ROOT_DIR}/ruoyi-backend/sql/dashboard.sql"
  echo "轻量大屏表和外部业务来源示例数据集已检查。"
fi

if [[ -f "${ROOT_DIR}/ruoyi-backend/sql/dashboard_integration.sql" ]]; then
  mysql_db < "${ROOT_DIR}/ruoyi-backend/sql/dashboard_integration.sql"
  mysql_db < "${ROOT_DIR}/ruoyi-backend/sql/dashboard_integration_retention.sql"
  echo "第三方数据接入表和管理菜单已检查。"
fi

mysql_db < "${ROOT_DIR}/ruoyi-backend/sql/dashboard_integration_operations.sql"
mysql_db < "${ROOT_DIR}/ruoyi-backend/sql/dashboard_data_management.sql"
mysql_db < "${ROOT_DIR}/ruoyi-backend/sql/dashboard_data_folders.sql"
mysql_db < "${ROOT_DIR}/ruoyi-backend/sql/dashboard_integration_monitoring.sql"
mysql_db < "${ROOT_DIR}/ruoyi-backend/sql/dashboard_integration_policy.sql"

if [[ -f "${ROOT_DIR}/ruoyi-backend/sql/platform_branding_cleanup.sql" ]]; then
  mysql_db < "${ROOT_DIR}/ruoyi-backend/sql/platform_branding_cleanup.sql"
fi

table_count="$(mysql_root -NBe \
  "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${MYSQL_DATABASE}'")"
user_count="$(mysql_db -NBe "SELECT COUNT(*) FROM sys_user")"
echo "基础数据库初始化完成：database=${MYSQL_DATABASE}, tables=${table_count}, users=${user_count}"
