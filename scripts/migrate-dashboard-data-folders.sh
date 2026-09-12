#!/usr/bin/env bash
# 本地增量迁移：保留业务内容和授权，不运行完整初始化脚本。
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "${ROOT_DIR}/config/ruoyi-local.env"
: "${MYSQL_HOST:=127.0.0.1}"
: "${MYSQL_PORT:=3306}"
: "${MYSQL_DATABASE:=engineering_data_cockpit}"
: "${MYSQL_USERNAME:=root}"
: "${MYSQL_PASSWORD:?MYSQL_PASSWORD 不能为空}"
case "${MYSQL_HOST}" in 127.0.0.1|localhost|::1) ;; *) echo "仅允许本地项目数据库" >&2; exit 1 ;; esac
[[ "${MYSQL_DATABASE}" =~ ^[A-Za-z0-9_]+$ ]] || { echo "数据库名称不合法" >&2; exit 1; }
mysql_local() {
  MYSQL_PWD="${MYSQL_PASSWORD}" mysql --protocol=TCP -h "${MYSQL_HOST}" -P "${MYSQL_PORT}" -u "${MYSQL_USERNAME}" "${MYSQL_DATABASE}" "$@"
}
umask 077
BACKUP_DIR="${ROOT_DIR}/.local/data-folder-refactor/backup-$(date +%Y%m%d%H%M%S)-$$"
mkdir -p "${BACKUP_DIR}"
FOLDER_TABLE_EXISTS="$(mysql_local -NBe "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='dashboard_data_folder'")"
BACKUP_TABLES=(dashboard_dataset_group dashboard_dataset dashboard_data_source dashboard_integration)
if [[ "${FOLDER_TABLE_EXISTS}" == 1 ]]; then BACKUP_TABLES+=(dashboard_data_folder); fi
MYSQL_PWD="${MYSQL_PASSWORD}" mysqldump --protocol=TCP -h "${MYSQL_HOST}" -P "${MYSQL_PORT}" -u "${MYSQL_USERNAME}" \
  --no-tablespaces --single-transaction "${MYSQL_DATABASE}" "${BACKUP_TABLES[@]}" > "${BACKUP_DIR}/before.sql"
mysql_local < "${ROOT_DIR}/ruoyi-backend/sql/dashboard_data_folders.sql"
mysql_local -NBe "SELECT table_name,column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND ((table_name IN ('dashboard_data_source','dashboard_integration') AND column_name='folder_id') OR table_name='dashboard_data_folder') ORDER BY table_name,ordinal_position;"
echo "本地文件夹增量迁移完成；原始备份仅保留于 ${BACKUP_DIR}/before.sql"
