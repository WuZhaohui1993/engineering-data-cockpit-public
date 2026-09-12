#!/usr/bin/env bash
# Local-only repair of redundant super-admin links written by the former menu configurator.
# Never delete ordinary role grants. Preserve an exact snapshot for rollback/audit.
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
source "${ROOT_DIR}/config/ruoyi-local.env"
if [[ "${MYSQL_HOST:-127.0.0.1}" != '127.0.0.1' && "${MYSQL_HOST:-127.0.0.1}" != 'localhost' ]]; then
  echo '该脚本仅用于已授权的本地数据库' >&2; exit 1
fi
MYSQL_TOOL="$(command -v mysql)"
: "${MYSQL_PASSWORD:?缺少本地数据库配置}"
umask 077
backup_dir="${ROOT_DIR}/.local/menu-repair/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$backup_dir"
mysql_local() { MYSQL_PWD="$MYSQL_PASSWORD" "$MYSQL_TOOL" --protocol=TCP -h"${MYSQL_HOST:-127.0.0.1}" -P"${MYSQL_PORT:-3306}" -u"${MYSQL_USERNAME:-root}" "${MYSQL_DATABASE:-engineering_data_cockpit}" "$@"; }
mysql_local -NBr -e 'select role_id,menu_id from sys_role_menu order by role_id,menu_id' > "$backup_dir/all-role-menus-before.tsv"
mysql_local -NBr -e "select rm.role_id,rm.menu_id,p.page_code,m.menu_name from sys_role_menu rm join sys_menu m on m.menu_id=rm.menu_id join dashboard_page p on m.route_name=concat('DashboardRuntime',p.page_id) where rm.role_id=1 and m.menu_type='C' and m.component='dashboard/runtime/index' and m.path=concat('runtime/code/',p.page_code) order by rm.menu_id" > "$backup_dir/generated-superadmin-before.tsv"
mysql_local <<'SQL'
START TRANSACTION;
DELETE rm FROM sys_role_menu rm
JOIN sys_menu m ON m.menu_id=rm.menu_id
JOIN dashboard_page p ON m.route_name=CONCAT('DashboardRuntime',p.page_id)
WHERE rm.role_id=1 AND m.menu_type='C' AND m.component='dashboard/runtime/index'
  AND m.path=CONCAT('runtime/code/',p.page_code);
SELECT ROW_COUNT() AS repaired_superadmin_links;
COMMIT;
SQL
mysql_local -NBr -e 'select role_id,menu_id from sys_role_menu order by role_id,menu_id' > "$backup_dir/all-role-menus-after.tsv"
python3 - "$backup_dir" <<'PY'
import pathlib,sys
p=pathlib.Path(sys.argv[1]);before=set(p.joinpath('all-role-menus-before.tsv').read_text().splitlines());after=set(p.joinpath('all-role-menus-after.tsv').read_text().splitlines());targets={ '\t'.join(row.split('\t')[:2]) for row in p.joinpath('generated-superadmin-before.tsv').read_text().splitlines() }
assert before-after==targets and not after-before
assert {r for r in before if not r.startswith('1\t')}=={r for r in after if not r.startswith('1\t')}
print(f'已修复 {len(targets)} 条冗余超级管理员关联，普通角色关联未变；备份：{p}')
PY
