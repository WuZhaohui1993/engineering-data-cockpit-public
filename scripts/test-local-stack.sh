#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ROOT_DIR}/config/ruoyi-local.env"
TEST_BACKEND_PORT="${TEST_BACKEND_PORT:-18080}"
TEST_FRONTEND_PORT="${TEST_FRONTEND_PORT:-15173}"
TEST_REDIS_DATABASE="${TEST_REDIS_DATABASE:-15}"
TEST_ADMIN_USERNAME="${TEST_ADMIN_USERNAME:-admin}"
TEST_ADMIN_PASSWORD="${TEST_ADMIN_PASSWORD:-admin123}"
SKIP_BUILD="${SKIP_BUILD:-0}"
BACKEND_PID=""
FRONTEND_PID=""
CAPTCHA_ORIGINAL=""
SMOKE_BASE_PAGE_ID=""
SMOKE_SOURCE_ID=""
SMOKE_DATASET_ID=""
SMOKE_DYNAMIC_DATASET_ID=""
SMOKE_GROUP_ID=""
SMOKE_CHILD_GROUP_ID=""
SMOKE_PAGE_ID=""
SMOKE_FOLDER_ID=""
SMOKE_CHILD_FOLDER_ID=""
SMOKE_FOLDER_PAGE_ID=""
SMOKE_RESOURCE_FOLDER_ID=""
SMOKE_RESOURCE_CHILD_FOLDER_ID=""
SMOKE_RESOURCE_ASSET_ID=""
SMOKE_RESOURCE_MAP_ID=""
SMOKE_SHARE_HOUR_ID=""
SMOKE_SHARE_DAY_ID=""
SMOKE_SHARE_PERMANENT_ID=""

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "缺少 ${ENV_FILE}，请先准备本地 MySQL 配置。" >&2
  exit 1
fi

# shellcheck disable=SC1090
source "${ENV_FILE}"
: "${MYSQL_HOST:=127.0.0.1}"
: "${MYSQL_PORT:=3306}"
: "${MYSQL_DATABASE:=engineering_data_cockpit}"
: "${MYSQL_USERNAME:=root}"
: "${MYSQL_PASSWORD:?MYSQL_PASSWORD 不能为空}"
: "${LABOR_PROJECT_CODE:=demo}"

for command_name in curl jq mysql pnpm mvn python3; do
  command -v "${command_name}" >/dev/null 2>&1 || { echo "缺少命令：${command_name}" >&2; exit 1; }
done

if lsof -nP -iTCP:"${TEST_BACKEND_PORT}" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "测试后端端口 ${TEST_BACKEND_PORT} 已被占用。" >&2
  exit 1
fi
if lsof -nP -iTCP:"${TEST_FRONTEND_PORT}" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "测试前端端口 ${TEST_FRONTEND_PORT} 已被占用。" >&2
  exit 1
fi

mysql_exec() {
  MYSQL_PWD="${MYSQL_PASSWORD}" mysql --protocol=TCP -h"${MYSQL_HOST}" -P"${MYSQL_PORT}" -u"${MYSQL_USERNAME}" "${MYSQL_DATABASE}" "$@"
}

cleanup() {
  local exit_code=$?
  if [[ -n "${FRONTEND_PID}" ]]; then kill "${FRONTEND_PID}" >/dev/null 2>&1 || true; wait "${FRONTEND_PID}" >/dev/null 2>&1 || true; fi
  if [[ -n "${BACKEND_PID}" ]]; then kill "${BACKEND_PID}" >/dev/null 2>&1 || true; wait "${BACKEND_PID}" >/dev/null 2>&1 || true; fi
  if [[ -n "${SMOKE_BASE_PAGE_ID}" ]]; then mysql_exec -e "delete from dashboard_page where page_id=${SMOKE_BASE_PAGE_ID};" >/dev/null 2>&1 || true; fi
  if [[ -n "${SMOKE_DATASET_ID}" ]]; then mysql_exec -e "delete from dashboard_dataset where dataset_id=${SMOKE_DATASET_ID};" >/dev/null 2>&1 || true; fi
  if [[ -n "${SMOKE_DYNAMIC_DATASET_ID}" ]]; then mysql_exec -e "delete from dashboard_dataset where dataset_id=${SMOKE_DYNAMIC_DATASET_ID};" >/dev/null 2>&1 || true; fi
  if [[ -n "${SMOKE_CHILD_GROUP_ID}" ]]; then mysql_exec -e "delete from dashboard_dataset_group where group_id=${SMOKE_CHILD_GROUP_ID};" >/dev/null 2>&1 || true; fi
  if [[ -n "${SMOKE_GROUP_ID}" ]]; then mysql_exec -e "delete from dashboard_dataset_group where group_id=${SMOKE_GROUP_ID};" >/dev/null 2>&1 || true; fi
  if [[ -n "${SMOKE_SOURCE_ID}" ]]; then mysql_exec -e "delete from dashboard_data_source where data_source_id=${SMOKE_SOURCE_ID};" >/dev/null 2>&1 || true; fi
  if [[ -n "${SMOKE_PAGE_ID}" ]]; then mysql_exec -e "delete from dashboard_page where page_id=${SMOKE_PAGE_ID};" >/dev/null 2>&1 || true; fi
  if [[ -n "${SMOKE_FOLDER_PAGE_ID}" ]]; then mysql_exec -e "delete from dashboard_page where page_id=${SMOKE_FOLDER_PAGE_ID};" >/dev/null 2>&1 || true; fi
  if [[ -n "${SMOKE_RESOURCE_ASSET_ID}" ]]; then mysql_exec -e "delete from dashboard_asset where asset_id=${SMOKE_RESOURCE_ASSET_ID};" >/dev/null 2>&1 || true; fi
  if [[ -n "${SMOKE_RESOURCE_MAP_ID}" ]]; then mysql_exec -e "delete from dashboard_map_resource where map_id=${SMOKE_RESOURCE_MAP_ID};" >/dev/null 2>&1 || true; fi
  if [[ -n "${SMOKE_SHARE_HOUR_ID}" ]]; then mysql_exec -e "delete from dashboard_share where share_id=${SMOKE_SHARE_HOUR_ID};" >/dev/null 2>&1 || true; fi
  if [[ -n "${SMOKE_SHARE_DAY_ID}" ]]; then mysql_exec -e "delete from dashboard_share where share_id=${SMOKE_SHARE_DAY_ID};" >/dev/null 2>&1 || true; fi
  if [[ -n "${SMOKE_SHARE_PERMANENT_ID}" ]]; then mysql_exec -e "delete from dashboard_share where share_id=${SMOKE_SHARE_PERMANENT_ID};" >/dev/null 2>&1 || true; fi
  if [[ -n "${SMOKE_CHILD_FOLDER_ID}" ]]; then mysql_exec -e "delete from dashboard_page_folder where folder_id=${SMOKE_CHILD_FOLDER_ID};" >/dev/null 2>&1 || true; fi
  if [[ -n "${SMOKE_FOLDER_ID}" ]]; then mysql_exec -e "delete from dashboard_page_folder where folder_id=${SMOKE_FOLDER_ID};" >/dev/null 2>&1 || true; fi
  if [[ -n "${SMOKE_RESOURCE_CHILD_FOLDER_ID}" ]]; then mysql_exec -e "delete from dashboard_resource_folder where folder_id=${SMOKE_RESOURCE_CHILD_FOLDER_ID};" >/dev/null 2>&1 || true; fi
  if [[ -n "${SMOKE_RESOURCE_FOLDER_ID}" ]]; then mysql_exec -e "delete from dashboard_resource_folder where folder_id=${SMOKE_RESOURCE_FOLDER_ID};" >/dev/null 2>&1 || true; fi
  if [[ -n "${SOURCE_TEST_CODE:-}" ]]; then
    mysql_exec -e "delete from dashboard_integration_audit where resource_code='${SOURCE_TEST_CODE}/captcha'; delete from dashboard_integration_snapshot where source_code='${SOURCE_TEST_CODE}';" >/dev/null 2>&1 || true
  fi
  if [[ -n "${CAPTCHA_ORIGINAL}" ]]; then
    mysql_exec -e "update sys_config set config_value='${CAPTCHA_ORIGINAL}' where config_key='sys.account.captchaEnabled';" >/dev/null 2>&1 || true
  fi
  if command -v redis-cli >/dev/null 2>&1; then
    redis-cli -n "${TEST_REDIS_DATABASE}" del sys_config:sys.account.captchaEnabled >/dev/null 2>&1 || true
  fi
  exit "${exit_code}"
}
trap cleanup EXIT INT TERM

wait_for_url() {
  local url=$1
  local name=$2
  local attempts=${3:-120}
  local index
  for index in $(seq 1 "${attempts}"); do
    if curl -fsS "${url}" >/dev/null 2>&1; then return 0; fi
    sleep 0.5
  done
  echo "${name} 未在预期时间内就绪：${url}" >&2
  return 1
}

assert_json_code() {
  local response=$1
  local expected=$2
  local context=$3
  local actual
  actual="$(printf '%s' "${response}" | jq -r '.code // empty')"
  if [[ "${actual}" != "${expected}" ]]; then
    echo "${context} 失败：期望 code=${expected}，实际响应=${response}" >&2
    exit 1
  fi
}

echo "[1/8] 初始化基础数据库"
"${ROOT_DIR}/scripts/init-local-mysql.sh"

if [[ "${SKIP_BUILD}" != "1" ]]; then
  echo "[2/8] 构建后端和前端"
  mkdir -p "${ROOT_DIR}/.local"
  (cd "${ROOT_DIR}/ruoyi-backend" && mvn -DskipTests package >"${ROOT_DIR}/.local/ruoyi-maven.log")
  (cd "${ROOT_DIR}/ruoyi-ui" && pnpm build:prod >"${ROOT_DIR}/.local/ruoyi-frontend-build.log")
else
  echo "[2/8] 按 SKIP_BUILD=1 跳过构建"
fi

CAPTCHA_ORIGINAL="$(mysql_exec -NBe "select config_value from sys_config where config_key='sys.account.captchaEnabled' limit 1")"
mysql_exec -e "update sys_config set config_value='false' where config_key='sys.account.captchaEnabled';" >/dev/null

echo "[3/8] 启动隔离测试端口"
mkdir -p "${ROOT_DIR}/.local"
REDIS_DATABASE="${TEST_REDIS_DATABASE}" BACKEND_PORT="${TEST_BACKEND_PORT}" FRONTEND_PORT="${TEST_FRONTEND_PORT}" \
  DASHBOARD_WEBSOCKET_ALLOWED_ORIGIN="http://127.0.0.1:${TEST_FRONTEND_PORT}" \
  DASHBOARD_INTEGRATION_ALLOW_LOCAL_DEVELOPMENT_TARGETS=true \
  DASHBOARD_INTEGRATION_ALLOW_PLAIN_HTTP=true \
  "${ROOT_DIR}/scripts/start-backend.sh" >"${ROOT_DIR}/.local/ruoyi-e2e-backend.log" 2>&1 &
BACKEND_PID=$!
wait_for_url "http://127.0.0.1:${TEST_BACKEND_PORT}/captchaImage" "后端"
FRONTEND_PORT="${TEST_FRONTEND_PORT}" BACKEND_PORT="${TEST_BACKEND_PORT}" "${ROOT_DIR}/scripts/start-frontend.sh" >"${ROOT_DIR}/.local/ruoyi-e2e-frontend.log" 2>&1 &
FRONTEND_PID=$!
wait_for_url "http://127.0.0.1:${TEST_FRONTEND_PORT}/" "前端"
wait_for_url "http://127.0.0.1:${TEST_FRONTEND_PORT}/dev-api/captchaImage" "前端代理"

echo "[4/8] 登录和动态菜单"
LOGIN_RESPONSE="$(curl -fsS -H 'Content-Type: application/json' \
  -d "$(jq -nc --arg username "${TEST_ADMIN_USERNAME}" --arg password "${TEST_ADMIN_PASSWORD}" '{username:$username,password:$password,code:"",uuid:""}')" \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/login")"
assert_json_code "${LOGIN_RESPONSE}" 200 "管理员登录"
TOKEN="$(printf '%s' "${LOGIN_RESPONSE}" | jq -r '.token')"
ROUTERS_RESPONSE="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" "http://127.0.0.1:${TEST_BACKEND_PORT}/getRouters")"
assert_json_code "${ROUTERS_RESPONSE}" 200 "动态菜单"

echo "[5/8] 验证基础接口"
PROFILE_RESPONSE="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" "http://127.0.0.1:${TEST_BACKEND_PORT}/system/user/profile")"
assert_json_code "${PROFILE_RESPONSE}" 200 "用户资料"
CONFIG_RESPONSE="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" "http://127.0.0.1:${TEST_BACKEND_PORT}/system/config/configKey/sys.index.skinName")"
assert_json_code "${CONFIG_RESPONSE}" 200 "系统参数"

echo "[6/8] 验证轻量大屏页面和四类数据集"
DASHBOARD_PAGE_RESPONSE="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/list")"
assert_json_code "${DASHBOARD_PAGE_RESPONSE}" 200 "大屏页面列表"
# 冒烟页面由本轮创建，不依赖已被归档或清理的历史示例，也不修改用户页面。
SMOKE_BASE_PAGE_CODE="smoke-baseline-${RANDOM}-${RANDOM}"
SMOKE_BASE_SCHEMA="$(cat "${ROOT_DIR}/scripts/fixtures/dashboard-smoke-page.json")"
SMOKE_BASE_RESPONSE="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -X POST \
  -d "$(jq -nc --arg code "${SMOKE_BASE_PAGE_CODE}" --arg schema "${SMOKE_BASE_SCHEMA}" '{pageCode:$code,pageName:"隔离冒烟基线页面",schemaJson:$schema}')" \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page")"
assert_json_code "${SMOKE_BASE_RESPONSE}" 200 "创建隔离冒烟页面"
SMOKE_BASE_PAGE_ID="$(printf '%s' "${SMOKE_BASE_RESPONSE}" | jq -r '.data.pageId')"
DASHBOARD_PAGE_ID="${SMOKE_BASE_PAGE_ID}"
SMOKE_BASE_PUBLISH="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -X POST -d '{}' \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/${DASHBOARD_PAGE_ID}/publish")"
assert_json_code "${SMOKE_BASE_PUBLISH}" 200 "发布隔离冒烟页面"
printf '%s' "${DASHBOARD_PAGE_RESPONSE}" | jq -e '.rows | map(select(.currentRevisionId != null)) | all(.[]; .currentVersionNo != null)' >/dev/null \
  || { echo "页面列表未返回当前发布版本号 currentVersionNo：${DASHBOARD_PAGE_RESPONSE}" >&2; exit 1; }
DASHBOARD_RUNTIME="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/${DASHBOARD_PAGE_ID}/runtime")"
assert_json_code "${DASHBOARD_RUNTIME}" 200 "大屏运行版本"
WIDGET_COUNT="$(printf '%s' "${DASHBOARD_RUNTIME}" | jq -r '.data.schema.widgets | length')"
[[ "${WIDGET_COUNT}" == "6" ]] || { echo "示例大屏组件数量异常：${WIDGET_COUNT}" >&2; exit 1; }
DASHBOARD_PREVIEW="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/${DASHBOARD_PAGE_ID}/preview")"
assert_json_code "${DASHBOARD_PREVIEW}" 200 "大屏预览版本"
DESIGNER_PREVIEW_DATASET_CODE="$(printf '%s' "${DASHBOARD_PREVIEW}" | jq -r '.data.schema.widgets[]?.binding.datasetCode // empty' | head -1)"
if [[ -n "${DESIGNER_PREVIEW_DATASET_CODE}" ]]; then
  DESIGNER_DATA_PREVIEW="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -X POST -d '{}' "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/dataset/${DESIGNER_PREVIEW_DATASET_CODE}/preview")"
  assert_json_code "${DESIGNER_DATA_PREVIEW}" 200 "设计器数据集预览"
  printf '%s' "${DESIGNER_DATA_PREVIEW}" | jq -e '.data.quality != null and (.data.rows | type) == "array"' >/dev/null \
    || { echo "设计器数据集预览结构异常：${DESIGNER_DATA_PREVIEW}" >&2; exit 1; }
fi
DASHBOARD_DRAFT_COUNT="$(mysql_exec -NBe "select count(*) from dashboard_page_revision where page_id=${DASHBOARD_PAGE_ID} and status='DRAFT'")"
if [[ "${DASHBOARD_DRAFT_COUNT}" == "0" ]]; then
  printf '%s' "${DASHBOARD_PREVIEW}" | jq -e '.data.preview == true and .data.previewSource == "PUBLISHED" and .data.previewReadOnly == true' >/dev/null \
    || { echo "无草稿页面未回退到发布版本预览：${DASHBOARD_PREVIEW}" >&2; exit 1; }
else
  printf '%s' "${DASHBOARD_PREVIEW}" | jq -e '.data.preview == true and .data.previewSource == "DRAFT"' >/dev/null \
    || { echo "存在草稿时预览未使用草稿版本：${DASHBOARD_PREVIEW}" >&2; exit 1; }
fi

DASHBOARD_REVISION_ID="$(printf '%s' "${DASHBOARD_RUNTIME}" | jq -r '.data.revisionId // empty')"
if [[ -n "${DASHBOARD_REVISION_ID}" ]]; then
  REVISION_PREVIEW="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" \
    "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/${DASHBOARD_PAGE_ID}/revision/${DASHBOARD_REVISION_ID}/preview")"
  assert_json_code "${REVISION_PREVIEW}" 200 "预览指定历史版本"
  printf '%s' "${REVISION_PREVIEW}" | jq -e --arg revisionId "${DASHBOARD_REVISION_ID}" \
    '.data.preview == true and .data.previewSource == "REVISION" and (.data.revisionId|tostring) == $revisionId' >/dev/null \
    || { echo "历史版本预览返回不正确：${REVISION_PREVIEW}" >&2; exit 1; }
  REVISION_WIDGET_ID="$(printf '%s' "${REVISION_PREVIEW}" | jq -r '.data.schema.widgets[] | select(.binding.datasetCode != null and .binding.datasetCode != "") | .id' | head -1)"
  REVISION_DATASET_CODE="$(printf '%s' "${REVISION_PREVIEW}" | jq -r '.data.schema.widgets[] | select(.binding.datasetCode != null and .binding.datasetCode != "") | .binding.datasetCode' | head -1)"
  if [[ -n "${REVISION_WIDGET_ID}" && -n "${REVISION_DATASET_CODE}" ]]; then
    REVISION_DATA_RESPONSE="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
      -d "$(jq -nc --argjson pageId "${DASHBOARD_PAGE_ID}" --arg widgetId "${REVISION_WIDGET_ID}" --arg datasetCode "${REVISION_DATASET_CODE}" --argjson revisionId "${DASHBOARD_REVISION_ID}" '{pageId:$pageId,widgetId:$widgetId,datasetCode:$datasetCode,params:{},filters:[],revisionId:$revisionId}')" \
      "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/runtime/preview/data")"
    assert_json_code "${REVISION_DATA_RESPONSE}" 200 "历史版本组件取数"
    printf '%s' "${REVISION_DATA_RESPONSE}" | jq -e --arg revisionId "${DASHBOARD_REVISION_ID}" '(.data.revisionId|tostring) == $revisionId' >/dev/null \
      || { echo "历史版本组件取数未使用指定版本：${REVISION_DATA_RESPONSE}" >&2; exit 1; }
  fi
fi

echo "验证页面文件夹、文件夹过滤和回收站生命周期"
FOLDER_TEST_CODE="smoke-page-folder-${RANDOM}-${RANDOM}"
FOLDER_CREATE_RESPONSE="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -X POST \
  -d "$(jq -nc --arg code "${FOLDER_TEST_CODE}" '{folderCode:$code,folderName:"页面文件夹冒烟",sortOrder:999,remark:"自动化测试"}')" \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/folder")"
assert_json_code "${FOLDER_CREATE_RESPONSE}" 200 "新增页面文件夹"
SMOKE_FOLDER_ID="$(printf '%s' "${FOLDER_CREATE_RESPONSE}" | jq -r '.data.folderId')"
[[ -n "${SMOKE_FOLDER_ID}" && "${SMOKE_FOLDER_ID}" != "null" ]] || { echo "页面文件夹未返回 folderId：${FOLDER_CREATE_RESPONSE}" >&2; exit 1; }
FOLDER_LIST_RESPONSE="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/folders")"
assert_json_code "${FOLDER_LIST_RESPONSE}" 200 "查询页面文件夹"
printf '%s' "${FOLDER_LIST_RESPONSE}" | jq -e --arg code "${FOLDER_TEST_CODE}" '.data | any(.[]; .folderCode == $code)' >/dev/null \
  || { echo "页面文件夹列表未找到新建文件夹：${FOLDER_LIST_RESPONSE}" >&2; exit 1; }
CHILD_FOLDER_CODE="smoke-child-folder-${RANDOM}-${RANDOM}"
CHILD_FOLDER_RESPONSE="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -X POST \
  -d "$(jq -nc --arg code "${CHILD_FOLDER_CODE}" --argjson parentId "${SMOKE_FOLDER_ID}" '{folderCode:$code,folderName:"子文件夹冒烟",parentId:$parentId,sortOrder:1}')" \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/folder")"
assert_json_code "${CHILD_FOLDER_RESPONSE}" 200 "新增子页面文件夹"
SMOKE_CHILD_FOLDER_ID="$(printf '%s' "${CHILD_FOLDER_RESPONSE}" | jq -r '.data.folderId')"
CYCLE_RESPONSE="$(curl -sS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -X PUT \
  -d "$(jq -nc --argjson folderId "${SMOKE_FOLDER_ID}" --argjson parentId "${SMOKE_CHILD_FOLDER_ID}" '{folderId:$folderId,parentId:$parentId}')" \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/folder")"
[[ "$(printf '%s' "${CYCLE_RESPONSE}" | jq -r '.code // empty')" == "500" ]] || { echo "页面文件夹环路未被阻止：${CYCLE_RESPONSE}" >&2; exit 1; }
FOLDER_PAGE_CODE="smoke-folder-page-${RANDOM}-${RANDOM}"
FOLDER_PAGE_RESPONSE="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -X POST \
  -d "$(jq -nc --arg code "${FOLDER_PAGE_CODE}" --argjson folderId "${SMOKE_FOLDER_ID}" '{pageCode:$code,pageName:"文件夹页面冒烟",folderId:$folderId,remark:"自动化测试"}')" \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page")"
assert_json_code "${FOLDER_PAGE_RESPONSE}" 200 "创建文件夹页面"
SMOKE_FOLDER_PAGE_ID="$(printf '%s' "${FOLDER_PAGE_RESPONSE}" | jq -r '.data.pageId')"
FOLDER_FILTER_RESPONSE="$(curl -fsS -G -H "Authorization: Bearer ${TOKEN}" --data-urlencode "folderId=${SMOKE_FOLDER_ID}" --data-urlencode 'pageNum=1' --data-urlencode 'pageSize=20' "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/list")"
assert_json_code "${FOLDER_FILTER_RESPONSE}" 200 "按文件夹查询页面"
printf '%s' "${FOLDER_FILTER_RESPONSE}" | jq -e --arg code "${FOLDER_PAGE_CODE}" --arg folderId "${SMOKE_FOLDER_ID}" '.rows | any(.[]; .pageCode == $code and (.folderId|tostring) == $folderId)' >/dev/null \
  || { echo "文件夹过滤未找到页面：${FOLDER_FILTER_RESPONSE}" >&2; exit 1; }
MOVE_PAGE_TO_ROOT="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -X PUT \
  -d "$(jq -nc --argjson pageId "${SMOKE_FOLDER_PAGE_ID}" '{pageId:$pageId,folderId:0}')" \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page")"
assert_json_code "${MOVE_PAGE_TO_ROOT}" 200 "移动页面到根目录"
ROOT_FOLDER_RESPONSE="$(curl -fsS -G -H "Authorization: Bearer ${TOKEN}" --data-urlencode 'folderId=0' --data-urlencode "keyword=${FOLDER_PAGE_CODE}" --data-urlencode 'pageNum=1' --data-urlencode 'pageSize=20' "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/list")"
printf '%s' "${ROOT_FOLDER_RESPONSE}" | jq -e --arg code "${FOLDER_PAGE_CODE}" '.rows | any(.[]; .pageCode == $code and (.folderId // 0) == 0)' >/dev/null \
  || { echo "移动后根目录未找到页面：${ROOT_FOLDER_RESPONSE}" >&2; exit 1; }
MOVE_PAGE_BACK="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -X PUT \
  -d "$(jq -nc --argjson pageId "${SMOKE_FOLDER_PAGE_ID}" --argjson folderId "${SMOKE_FOLDER_ID}" '{pageId:$pageId,folderId:$folderId}')" \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page")"
assert_json_code "${MOVE_PAGE_BACK}" 200 "移动页面到指定文件夹"
curl -fsS -H "Authorization: Bearer ${TOKEN}" -X DELETE "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/${SMOKE_FOLDER_PAGE_ID}" >/dev/null
FOLDER_MAIN_AFTER_DELETE="$(curl -fsS -G -H "Authorization: Bearer ${TOKEN}" --data-urlencode "folderId=${SMOKE_FOLDER_ID}" --data-urlencode 'pageNum=1' --data-urlencode 'pageSize=20' "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/list")"
printf '%s' "${FOLDER_MAIN_AFTER_DELETE}" | jq -e --arg code "${FOLDER_PAGE_CODE}" '.rows | all(.[]; .pageCode != $code)' >/dev/null \
  || { echo "移入回收站后主列表仍返回页面：${FOLDER_MAIN_AFTER_DELETE}" >&2; exit 1; }
RECYCLE_RESPONSE="$(curl -fsS -G -H "Authorization: Bearer ${TOKEN}" --data-urlencode "keyword=${FOLDER_PAGE_CODE}" --data-urlencode 'pageNum=1' --data-urlencode 'pageSize=20' "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/recycle")"
assert_json_code "${RECYCLE_RESPONSE}" 200 "查询页面回收站"
printf '%s' "${RECYCLE_RESPONSE}" | jq -e --arg code "${FOLDER_PAGE_CODE}" '.rows | any(.[]; .pageCode == $code and .isDeleted == "1")' >/dev/null \
  || { echo "回收站未找到软删除页面：${RECYCLE_RESPONSE}" >&2; exit 1; }
curl -fsS -H "Authorization: Bearer ${TOKEN}" -X POST "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/${SMOKE_FOLDER_PAGE_ID}/restore" >/dev/null
curl -fsS -H "Authorization: Bearer ${TOKEN}" -X DELETE "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/${SMOKE_FOLDER_PAGE_ID}" >/dev/null
curl -fsS -H "Authorization: Bearer ${TOKEN}" -X DELETE "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/${SMOKE_FOLDER_PAGE_ID}/purge" >/dev/null
PURGED_RECYCLE_RESPONSE="$(curl -fsS -G -H "Authorization: Bearer ${TOKEN}" --data-urlencode "keyword=${FOLDER_PAGE_CODE}" --data-urlencode 'pageNum=1' --data-urlencode 'pageSize=20' "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/recycle")"
assert_json_code "${PURGED_RECYCLE_RESPONSE}" 200 "彻底删除页面"
printf '%s' "${PURGED_RECYCLE_RESPONSE}" | jq -e --arg code "${FOLDER_PAGE_CODE}" '.rows | all(.[]; .pageCode != $code)' >/dev/null \
  || { echo "彻底删除后回收站仍返回页面：${PURGED_RECYCLE_RESPONSE}" >&2; exit 1; }
SMOKE_FOLDER_PAGE_ID=""
FOLDER_DELETE_NONEMPTY="$(curl -sS -H "Authorization: Bearer ${TOKEN}" -X DELETE "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/folder/${SMOKE_FOLDER_ID}")"
[[ "$(printf '%s' "${FOLDER_DELETE_NONEMPTY}" | jq -r '.code // empty')" == "500" ]] || { echo "非空页面文件夹未被阻止删除：${FOLDER_DELETE_NONEMPTY}" >&2; exit 1; }
curl -fsS -H "Authorization: Bearer ${TOKEN}" -X DELETE "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/folder/${SMOKE_CHILD_FOLDER_ID}" >/dev/null
SMOKE_CHILD_FOLDER_ID=""
FOLDER_DELETE_RESPONSE="$(curl -sS -H "Authorization: Bearer ${TOKEN}" -X DELETE "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/folder/${SMOKE_FOLDER_ID}")"
assert_json_code "${FOLDER_DELETE_RESPONSE}" 200 "删除空页面文件夹"
SMOKE_FOLDER_ID=""

echo "验证受控内置 GeoJSON 地图资源"
MAP_ASSET_RESPONSE="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/assets/map/demo-region.json")"
printf '%s' "${MAP_ASSET_RESPONSE}" | jq -e '.type == "FeatureCollection" and (.features | length) == 4' >/dev/null \
  || { echo "内置 GeoJSON 地图资源结构异常：${MAP_ASSET_RESPONSE}" >&2; exit 1; }
INVALID_MAP_RESPONSE="$(curl --path-as-is -sS \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/assets/map/../application.json")"
if printf '%s' "${INVALID_MAP_RESPONSE}" | jq -e '.type == "FeatureCollection"' >/dev/null 2>&1; then
  echo "地图资源路径校验失败：疑似允许目录穿越。" >&2
  exit 1
fi

echo "验证资源文件夹、资源和地图的分组管理"
RESOURCE_FOLDER_CODE="smoke-resource-folder-${RANDOM}-${RANDOM}"
RESOURCE_FOLDER_CREATE="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -X POST \
  -d "$(jq -nc --arg code "${RESOURCE_FOLDER_CODE}" '{folderCode:$code,folderName:"资源文件夹冒烟",parentId:0,sortOrder:999,remark:"自动化测试"}')" \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/resource/folder")"
assert_json_code "${RESOURCE_FOLDER_CREATE}" 200 "新增资源文件夹"
SMOKE_RESOURCE_FOLDER_ID="$(printf '%s' "${RESOURCE_FOLDER_CREATE}" | jq -r '.data.folderId')"
[[ -n "${SMOKE_RESOURCE_FOLDER_ID}" && "${SMOKE_RESOURCE_FOLDER_ID}" != "null" ]] \
  || { echo "新增资源文件夹未返回 folderId：${RESOURCE_FOLDER_CREATE}" >&2; exit 1; }

RESOURCE_CHILD_CODE="smoke-resource-child-${RANDOM}-${RANDOM}"
RESOURCE_CHILD_CREATE="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -X POST \
  -d "$(jq -nc --arg code "${RESOURCE_CHILD_CODE}" --argjson parentId "${SMOKE_RESOURCE_FOLDER_ID}" '{folderCode:$code,folderName:"资源子文件夹冒烟",parentId:$parentId,sortOrder:1,remark:"自动化测试"}')" \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/resource/folder")"
assert_json_code "${RESOURCE_CHILD_CREATE}" 200 "新增资源子文件夹"
SMOKE_RESOURCE_CHILD_FOLDER_ID="$(printf '%s' "${RESOURCE_CHILD_CREATE}" | jq -r '.data.folderId')"
RESOURCE_CHILD_UPDATE="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -X PUT \
  -d "$(jq -nc --argjson folderId "${SMOKE_RESOURCE_CHILD_FOLDER_ID}" --argjson parentId "${SMOKE_RESOURCE_FOLDER_ID}" '{folderId:$folderId,folderName:"资源子文件夹已更新",parentId:$parentId,sortOrder:2}')" \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/resource/folder")"
assert_json_code "${RESOURCE_CHILD_UPDATE}" 200 "编辑资源子文件夹"
RESOURCE_FOLDER_LIST="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/resource/folders")"
assert_json_code "${RESOURCE_FOLDER_LIST}" 200 "查询资源文件夹"
printf '%s' "${RESOURCE_FOLDER_LIST}" | jq -e --arg code "${RESOURCE_CHILD_CODE}" --arg parentId "${SMOKE_RESOURCE_FOLDER_ID}" \
  '.data | any(.[]; .folderCode == $code and .folderName == "资源子文件夹已更新" and (.parentId|tostring) == $parentId and .sortOrder == 2)' >/dev/null \
  || { echo "资源文件夹层级或编辑结果异常：${RESOURCE_FOLDER_LIST}" >&2; exit 1; }

RESOURCE_ASSET_CODE="smoke-resource-asset-${RANDOM}-${RANDOM}"
RESOURCE_ASSET_CREATE="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -X POST \
  -d "$(jq -nc --arg code "${RESOURCE_ASSET_CODE}" --argjson folderId "${SMOKE_RESOURCE_FOLDER_ID}" '{assetCode:$code,assetName:"文件夹图片冒烟",folderId:$folderId,assetType:"IMAGE",category:"自动化测试",resourcePath:"/profile/dashboard/smoke-resource.png",status:"0",remark:"自动化测试"}')" \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/asset")"
assert_json_code "${RESOURCE_ASSET_CREATE}" 200 "新增文件夹图片资源"
SMOKE_RESOURCE_ASSET_ID="$(printf '%s' "${RESOURCE_ASSET_CREATE}" | jq -r '.data.assetId')"

RESOURCE_MAP_CODE="smoke-resource-map-${RANDOM}-${RANDOM}"
RESOURCE_MAP_GEOJSON='{"type":"FeatureCollection","features":[]}'
RESOURCE_MAP_CREATE="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -X POST \
  -d "$(jq -nc --arg code "${RESOURCE_MAP_CODE}" --arg geojson "${RESOURCE_MAP_GEOJSON}" --argjson folderId "${SMOKE_RESOURCE_FOLDER_ID}" '{mapCode:$code,mapName:"文件夹地图冒烟",folderId:$folderId,geojsonJson:$geojson,status:"0",remark:"自动化测试"}')" \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/map")"
assert_json_code "${RESOURCE_MAP_CREATE}" 200 "新增文件夹地图资源"
SMOKE_RESOURCE_MAP_ID="$(printf '%s' "${RESOURCE_MAP_CREATE}" | jq -r '.data.mapId')"

RESOURCE_ASSET_FILTER="$(curl -fsS -G -H "Authorization: Bearer ${TOKEN}" \
  --data-urlencode "folderId=${SMOKE_RESOURCE_FOLDER_ID}" --data-urlencode "keyword=${RESOURCE_ASSET_CODE}" \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/asset/list")"
assert_json_code "${RESOURCE_ASSET_FILTER}" 200 "按文件夹查询图片资源"
printf '%s' "${RESOURCE_ASSET_FILTER}" | jq -e --arg code "${RESOURCE_ASSET_CODE}" --arg folderId "${SMOKE_RESOURCE_FOLDER_ID}" \
  '.data | any(.[]; .assetCode == $code and (.folderId|tostring) == $folderId and .folderName == "资源文件夹冒烟")' >/dev/null \
  || { echo "资源文件夹过滤未找到图片：${RESOURCE_ASSET_FILTER}" >&2; exit 1; }
RESOURCE_MAP_FILTER="$(curl -fsS -G -H "Authorization: Bearer ${TOKEN}" \
  --data-urlencode "folderId=${SMOKE_RESOURCE_FOLDER_ID}" --data-urlencode "keyword=${RESOURCE_MAP_CODE}" \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/map/list")"
assert_json_code "${RESOURCE_MAP_FILTER}" 200 "按文件夹查询地图资源"
printf '%s' "${RESOURCE_MAP_FILTER}" | jq -e --arg code "${RESOURCE_MAP_CODE}" --arg folderId "${SMOKE_RESOURCE_FOLDER_ID}" \
  '.data | any(.[]; .mapCode == $code and (.folderId|tostring) == $folderId and .folderName == "资源文件夹冒烟")' >/dev/null \
  || { echo "资源文件夹过滤未找到地图：${RESOURCE_MAP_FILTER}" >&2; exit 1; }

RESOURCE_FOLDER_DELETE_NONEMPTY="$(curl -sS -H "Authorization: Bearer ${TOKEN}" -X DELETE \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/resource/folder/${SMOKE_RESOURCE_FOLDER_ID}")"
[[ "$(printf '%s' "${RESOURCE_FOLDER_DELETE_NONEMPTY}" | jq -r '.code // empty')" == "500" ]] \
  || { echo "包含资源或子文件夹时仍允许删除资源文件夹：${RESOURCE_FOLDER_DELETE_NONEMPTY}" >&2; exit 1; }
curl -fsS -H "Authorization: Bearer ${TOKEN}" -X DELETE \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/resource/folder/${SMOKE_RESOURCE_CHILD_FOLDER_ID}" >/dev/null
SMOKE_RESOURCE_CHILD_FOLDER_ID=""
RESOURCE_FOLDER_DELETE_WITH_ASSETS="$(curl -sS -H "Authorization: Bearer ${TOKEN}" -X DELETE \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/resource/folder/${SMOKE_RESOURCE_FOLDER_ID}")"
[[ "$(printf '%s' "${RESOURCE_FOLDER_DELETE_WITH_ASSETS}" | jq -r '.code // empty')" == "500" ]] \
  || { echo "包含资源时仍允许删除资源文件夹：${RESOURCE_FOLDER_DELETE_WITH_ASSETS}" >&2; exit 1; }
curl -fsS -H "Authorization: Bearer ${TOKEN}" -X DELETE \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/asset/${SMOKE_RESOURCE_ASSET_ID}" >/dev/null
SMOKE_RESOURCE_ASSET_ID=""
curl -fsS -H "Authorization: Bearer ${TOKEN}" -X DELETE \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/map/${SMOKE_RESOURCE_MAP_ID}" >/dev/null
SMOKE_RESOURCE_MAP_ID=""
RESOURCE_FOLDER_DELETE="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" -X DELETE \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/resource/folder/${SMOKE_RESOURCE_FOLDER_ID}")"
assert_json_code "${RESOURCE_FOLDER_DELETE}" 200 "删除空资源文件夹"
SMOKE_RESOURCE_FOLDER_ID=""

echo "验证数据集分组、分组过滤和非空分组删除约束"
GROUP_TEST_CODE="smoke-group-${RANDOM}-${RANDOM}"
GROUP_DATASET_CODE="smoke-group-dataset-${RANDOM}-${RANDOM}"
GROUP_CREATE_RESPONSE="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -X POST \
  -d "$(jq -nc --arg code "${GROUP_TEST_CODE}" '{groupCode:$code,groupName:"冒烟数据集分组",sortOrder:999,remark:"自动化测试"}')" \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/dataset/group")"
assert_json_code "${GROUP_CREATE_RESPONSE}" 200 "新增数据集分组"
SMOKE_GROUP_ID="$(printf '%s' "${GROUP_CREATE_RESPONSE}" | jq -r '.data.groupId')"
[[ -n "${SMOKE_GROUP_ID}" && "${SMOKE_GROUP_ID}" != "null" ]] || { echo "新增分组未返回 groupId：${GROUP_CREATE_RESPONSE}" >&2; exit 1; }
GROUP_LIST_RESPONSE="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/dataset/group/list")"
assert_json_code "${GROUP_LIST_RESPONSE}" 200 "查询数据集分组"
printf '%s' "${GROUP_LIST_RESPONSE}" | jq -e --arg code "${GROUP_TEST_CODE}" '.data | any(.[]; .groupCode == $code)' >/dev/null \
  || { echo "分组列表未找到新建分组：${GROUP_LIST_RESPONSE}" >&2; exit 1; }
CHILD_GROUP_CODE="smoke-child-group-${RANDOM}-${RANDOM}"
CHILD_GROUP_RESPONSE="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -X POST \
  -d "$(jq -nc --arg code "${CHILD_GROUP_CODE}" --argjson parentId "${SMOKE_GROUP_ID}" '{groupCode:$code,groupName:"冒烟数据集子文件夹",parentId:$parentId,sortOrder:1,remark:"自动化测试"}')" \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/dataset/group")"
assert_json_code "${CHILD_GROUP_RESPONSE}" 200 "新增数据集子文件夹"
SMOKE_CHILD_GROUP_ID="$(printf '%s' "${CHILD_GROUP_RESPONSE}" | jq -r '.data.groupId')"
GROUP_CYCLE_RESPONSE="$(curl -sS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -X PUT \
  -d "$(jq -nc --argjson groupId "${SMOKE_GROUP_ID}" --argjson parentId "${SMOKE_CHILD_GROUP_ID}" '{groupId:$groupId,parentId:$parentId}')" \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/dataset/group")"
[[ "$(printf '%s' "${GROUP_CYCLE_RESPONSE}" | jq -r '.code // empty')" == "500" ]] \
  || { echo "数据集文件夹环路未被阻止：${GROUP_CYCLE_RESPONSE}" >&2; exit 1; }
GROUP_LIST_RESPONSE="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/dataset/group/list")"
printf '%s' "${GROUP_LIST_RESPONSE}" | jq -e --arg code "${CHILD_GROUP_CODE}" --arg parentId "${SMOKE_GROUP_ID}" '.data | any(.[]; .groupCode == $code and (.parentId|tostring) == $parentId)' >/dev/null \
  || { echo "数据集子文件夹层级不正确：${GROUP_LIST_RESPONSE}" >&2; exit 1; }
GROUP_DATASET_CONFIG="$(jq -nc '{mode:"STATIC",payload:[{name:"分组样例",value:1}],rowsPath:""}')"
GROUP_DATASET_RESPONSE="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -X POST \
  -d "$(jq -nc --arg code "${GROUP_DATASET_CODE}" --arg group "${GROUP_TEST_CODE}" --arg config "${GROUP_DATASET_CONFIG}" '{datasetCode:$code,datasetName:"分组冒烟数据集",groupCode:$group,dataType:"JSON",status:"DRAFT",configJson:$config,fieldSchemaJson:"[]",paramSchemaJson:"[]",timeoutSeconds:5,refreshSeconds:30}')" \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/dataset")"
assert_json_code "${GROUP_DATASET_RESPONSE}" 200 "新增分组数据集"
SMOKE_DATASET_ID="$(printf '%s' "${GROUP_DATASET_RESPONSE}" | jq -r '.data.datasetId')"
[[ -n "${SMOKE_DATASET_ID}" && "${SMOKE_DATASET_ID}" != "null" ]] || { echo "新增分组数据集未返回 datasetId：${GROUP_DATASET_RESPONSE}" >&2; exit 1; }
GROUP_FILTER_RESPONSE="$(curl -fsS -G -H "Authorization: Bearer ${TOKEN}" \
  --data-urlencode "groupCode=${GROUP_TEST_CODE}" --data-urlencode 'pageNum=1' --data-urlencode 'pageSize=20' \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/dataset/list")"
assert_json_code "${GROUP_FILTER_RESPONSE}" 200 "按分组查询数据集"
printf '%s' "${GROUP_FILTER_RESPONSE}" | jq -e --arg code "${GROUP_DATASET_CODE}" --arg group "${GROUP_TEST_CODE}" '.rows | any(.[]; .datasetCode == $code and .groupCode == $group)' >/dev/null \
  || { echo "分组过滤未找到数据集：${GROUP_FILTER_RESPONSE}" >&2; exit 1; }
GROUP_DELETE_REFERENCED="$(curl -sS -H "Authorization: Bearer ${TOKEN}" -X DELETE "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/dataset/group/${SMOKE_GROUP_ID}")"
[[ "$(printf '%s' "${GROUP_DELETE_REFERENCED}" | jq -r '.code // empty')" == "500" ]] \
  || { echo "非空数据集分组未被阻止删除：${GROUP_DELETE_REFERENCED}" >&2; exit 1; }
curl -fsS -H "Authorization: Bearer ${TOKEN}" -X DELETE "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/dataset/${SMOKE_DATASET_ID}" >/dev/null
SMOKE_DATASET_ID=""
GROUP_DELETE_WITH_CHILD="$(curl -sS -H "Authorization: Bearer ${TOKEN}" -X DELETE "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/dataset/group/${SMOKE_GROUP_ID}")"
[[ "$(printf '%s' "${GROUP_DELETE_WITH_CHILD}" | jq -r '.code // empty')" == "500" ]] \
  || { echo "包含子文件夹时仍允许删除数据集文件夹：${GROUP_DELETE_WITH_CHILD}" >&2; exit 1; }
curl -fsS -H "Authorization: Bearer ${TOKEN}" -X DELETE "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/dataset/group/${SMOKE_CHILD_GROUP_ID}" >/dev/null
SMOKE_CHILD_GROUP_ID=""
GROUP_DELETE_EMPTY="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" -X DELETE "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/dataset/group/${SMOKE_GROUP_ID}")"
assert_json_code "${GROUP_DELETE_EMPTY}" 200 "删除空数据集分组"
SMOKE_GROUP_ID=""

for dataset_code in labor-hik-total labor-hik-by-direction labor-hik-events-api labor-hik-live design-json-demo; do
  DATASET_ID="$(mysql_exec -NBe "select dataset_id from dashboard_dataset where dataset_code='${dataset_code}' limit 1")"
  DATASET_RESPONSE="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
    -d "$(jq -nc --arg projectCode "${LABOR_PROJECT_CODE}" '{projectCode:$projectCode,page:0,size:20}')" "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/dataset/${DATASET_ID}/test")"
  assert_json_code "${DATASET_RESPONSE}" 200 "数据集 ${dataset_code}"
  QUALITY="$(printf '%s' "${DATASET_RESPONSE}" | jq -r '.data.quality')"
  [[ -n "${QUALITY}" && "${QUALITY}" != "null" ]] || { echo "数据集 ${dataset_code} 缺少质量状态。" >&2; exit 1; }
done

echo "验证官方 '\${参数}' 动态 SQL 语法的安全绑定"
DYNAMIC_DATASET_CODE="smoke-dynamic-sql-${RANDOM}-${RANDOM}"
DYNAMIC_SQL="SELECT CASE WHEN 'prefix-\${keyword}-suffix' LIKE '%ok%' THEN 1 ELSE 0 END AS value"
DYNAMIC_CONFIG="$(jq -nc --arg sql "${DYNAMIC_SQL}" '{dataSourceCode:"master",sql:$sql,rowLimit:10}')"
DYNAMIC_PARAMS='[{"name":"keyword","title":"关键字","type":"STRING","required":true}]'
DYNAMIC_DATASET_REQUEST="$(jq -nc --arg code "${DYNAMIC_DATASET_CODE}" --arg config "${DYNAMIC_CONFIG}" --arg params "${DYNAMIC_PARAMS}" '{datasetCode:$code,datasetName:"动态 SQL 冒烟数据集",dataType:"SQL",status:"DRAFT",configJson:$config,fieldSchemaJson:"[{\"name\":\"value\",\"title\":\"结果\",\"type\":\"number\"}]",paramSchemaJson:$params,timeoutSeconds:5,refreshSeconds:30}')"
DYNAMIC_DATASET_RESPONSE="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -X POST \
  -d "${DYNAMIC_DATASET_REQUEST}" "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/dataset")"
assert_json_code "${DYNAMIC_DATASET_RESPONSE}" 200 "新增动态 SQL 数据集"
SMOKE_DYNAMIC_DATASET_ID="$(printf '%s' "${DYNAMIC_DATASET_RESPONSE}" | jq -r '.data.datasetId')"
[[ -n "${SMOKE_DYNAMIC_DATASET_ID}" && "${SMOKE_DYNAMIC_DATASET_ID}" != "null" ]] || { echo "动态 SQL 数据集未返回 datasetId：${DYNAMIC_DATASET_RESPONSE}" >&2; exit 1; }
DYNAMIC_TEST_RESPONSE="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
  -d '{"keyword":"ok"}' "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/dataset/${SMOKE_DYNAMIC_DATASET_ID}/test")"
assert_json_code "${DYNAMIC_TEST_RESPONSE}" 200 "执行动态 SQL 数据集"
printf '%s' "${DYNAMIC_TEST_RESPONSE}" | jq -e '.data.quality == "SUCCESS" and .data.rows[0].value == 1' >/dev/null \
  || { echo "动态 SQL 绑定结果异常：${DYNAMIC_TEST_RESPONSE}" >&2; exit 1; }
curl -fsS -H "Authorization: Bearer ${TOKEN}" -X DELETE "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/dataset/${SMOKE_DYNAMIC_DATASET_ID}" >/dev/null
SMOKE_DYNAMIC_DATASET_ID=""

echo "验证 API totalPath 虚拟字段和空 option 配置"
VIRTUAL_TOTAL_PAGE_CODE="smoke-api-total-${RANDOM}-${RANDOM}"
VIRTUAL_TOTAL_SCHEMA="$(jq -nc '{schemaVersion:"1.0",canvas:{width:640,height:360},widgets:[{id:"api-total-card",type:"metric-card",layout:{x:0,y:0,w:320,h:160},binding:{datasetCode:"labor-hik-events-api",fieldMap:{value:"total"}},style:{title:"API 总数",optionJson:""}}]}')"
VIRTUAL_TOTAL_PAGE_RESPONSE="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -X POST \
  -d "$(jq -nc --arg code "${VIRTUAL_TOTAL_PAGE_CODE}" --arg schema "${VIRTUAL_TOTAL_SCHEMA}" '{pageCode:$code,pageName:"API totalPath 虚拟字段测试",schemaJson:$schema,remark:"自动化测试"}')" \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page")"
assert_json_code "${VIRTUAL_TOTAL_PAGE_RESPONSE}" 200 "保存 API totalPath 虚拟字段页面"
SMOKE_PAGE_ID="$(printf '%s' "${VIRTUAL_TOTAL_PAGE_RESPONSE}" | jq -r '.data.pageId')"
[[ -n "${SMOKE_PAGE_ID}" && "${SMOKE_PAGE_ID}" != "null" ]] || { echo "API totalPath 测试页未返回 pageId：${VIRTUAL_TOTAL_PAGE_RESPONSE}" >&2; exit 1; }
curl -fsS -H "Authorization: Bearer ${TOKEN}" -X DELETE "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/${SMOKE_PAGE_ID}" >/dev/null
SMOKE_PAGE_ID=""

for binding in \
  'labor-hik-total-card|labor-hik-total' \
  'labor-hik-api-card|labor-hik-events-api' \
  'design-json-chart|design-json-demo' \
  'labor-hik-live-list|labor-hik-live'; do
  widget_id="${binding%%|*}"
  dataset_code="${binding##*|}"
  BOUND_RUNTIME_DATA="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
    -d "$(jq -nc --arg widgetId "${widget_id}" --arg datasetCode "${dataset_code}" --argjson pageId "${DASHBOARD_PAGE_ID}" --arg projectCode "${LABOR_PROJECT_CODE}" '{pageId:$pageId,widgetId:$widgetId,datasetCode:$datasetCode,params:{projectCode:$projectCode,page:0,size:20}}')" \
    "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/runtime/data")"
  assert_json_code "${BOUND_RUNTIME_DATA}" 200 "页面组件绑定数据集 ${dataset_code}"
  QUALITY="$(printf '%s' "${BOUND_RUNTIME_DATA}" | jq -r '.data.quality')"
  [[ "${QUALITY}" == "SUCCESS" || "${QUALITY}" == "NO_DATA" || "${QUALITY}" == "NOT_CONNECTED" || "${QUALITY}" == "SOURCE_ERROR" || "${QUALITY}" == "AUTH_ERROR" ]] || { echo "页面组件绑定数据集 ${dataset_code} 返回异常质量状态：${QUALITY}" >&2; exit 1; }
done

FILTERED_JSON_DATA="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
  -d "$(jq -nc --argjson pageId "${DASHBOARD_PAGE_ID}" '{pageId:$pageId,widgetId:"design-json-chart",datasetCode:"design-json-demo",params:{},filters:[{field:"name",operator:"eq",value:"示例一"}]}')" \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/runtime/data")"
assert_json_code "${FILTERED_JSON_DATA}" 200 "数据集服务端过滤"
FILTERED_ROWS="$(printf '%s' "${FILTERED_JSON_DATA}" | jq -r '.data.rows | length')"
[[ "${FILTERED_ROWS}" == "1" ]] || { echo "服务端过滤结果异常：${FILTERED_ROWS}" >&2; exit 1; }
INVALID_FILTER_DATA="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
  -d "$(jq -nc --argjson pageId "${DASHBOARD_PAGE_ID}" '{pageId:$pageId,widgetId:"design-json-chart",datasetCode:"design-json-demo",params:{},filters:[{field:"notDeclared",operator:"eq",value:"x"}]}')" \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/runtime/data")"
assert_json_code "${INVALID_FILTER_DATA}" 200 "非法过滤字段拦截"
INVALID_FILTER_QUALITY="$(printf '%s' "${INVALID_FILTER_DATA}" | jq -r '.data.quality')"
[[ "${INVALID_FILTER_QUALITY}" == "INVALID_DATA" ]] || { echo "非法过滤字段未被拦截：${INVALID_FILTER_QUALITY}" >&2; exit 1; }

INVALID_FILTER_FORM_SCHEMA="$(jq -nc '{schemaVersion:"1.0",canvas:{width:1920,height:1080},filters:[{id:"keyword-filter",parameter:"keyword",type:"STRING"}],widgets:[{id:"filter-form-test",type:"filter-form",layout:{x:0,y:0,w:480,h:180},style:{formFields:[{name:"keyword",label:"关键词",parameter:"not-declared"}]}}]}')"
INVALID_FILTER_FORM_RESPONSE="$(curl -sS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -X PUT \
  -d "$(jq -nc --arg schema "${INVALID_FILTER_FORM_SCHEMA}" '{schemaJson:$schema}')" \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/${DASHBOARD_PAGE_ID}/draft")"
INVALID_FILTER_FORM_CODE="$(printf '%s' "${INVALID_FILTER_FORM_RESPONSE}" | jq -r '.code // empty')"
[[ "${INVALID_FILTER_FORM_CODE}" == "500" ]] || { echo "非法查询表单未被服务端拦截：${INVALID_FILTER_FORM_RESPONSE}" >&2; exit 1; }

VALID_FORM_PAGE_CODE="smoke-form-components-${RANDOM}-${RANDOM}"
VALID_FORM_SCHEMA="$(jq -nc '{schemaVersion:"1.0",canvas:{width:960,height:540},filters:[{id:"keyword-filter",parameter:"keyword",type:"STRING",defaultValue:""},{id:"date-filter",parameter:"eventDate",type:"DATE",defaultValue:""}],widgets:[{id:"designer-form-test",type:"designer-form",layout:{x:0,y:0,w:460,h:180},style:{formFields:[{name:"keyword",label:"关键词",parameter:"keyword",placeholder:"请输入关键词",type:"STRING",filterId:"keyword-filter"}]}},{id:"online-form-test",type:"online-form",layout:{x:480,y:0,w:460,h:180},style:{formFields:[{name:"eventDate",label:"事件日期",parameter:"eventDate",type:"DATE",filterId:"date-filter"}]}}]}')"
VALID_FORM_RESPONSE="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -X POST \
  -d "$(jq -nc --arg code "${VALID_FORM_PAGE_CODE}" --arg schema "${VALID_FORM_SCHEMA}" '{pageCode:$code,pageName:"受控表单组件测试",schemaJson:$schema,remark:"自动化测试"}')" \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page")"
assert_json_code "${VALID_FORM_RESPONSE}" 200 "保存设计器表单和在线查询表单"
SMOKE_PAGE_ID="$(printf '%s' "${VALID_FORM_RESPONSE}" | jq -r '.data.pageId')"
VALID_FORM_READ="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/${SMOKE_PAGE_ID}")"
assert_json_code "${VALID_FORM_READ}" 200 "读取设计器表单和在线查询表单"
printf '%s' "${VALID_FORM_READ}" | jq -e '.data.currentSchema.widgets | any(.[]; .type == "designer-form" and .style.formFields[0].parameter == "keyword" and .style.formFields[0].filterId == "keyword-filter") and any(.[]; .type == "online-form" and .style.formFields[0].parameter == "eventDate" and .style.formFields[0].type == "DATE")' >/dev/null \
  || { echo "受控表单配置保存后读取不一致：${VALID_FORM_READ}" >&2; exit 1; }
curl -fsS -H "Authorization: Bearer ${TOKEN}" -X DELETE "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/${SMOKE_PAGE_ID}" >/dev/null
SMOKE_PAGE_ID=""

INVALID_CHART_SCHEMA="$(jq -nc '{schemaVersion:"1.0",canvas:{width:1920,height:1080},widgets:[{id:"chart-config-test",type:"line-chart",layout:{x:0,y:0,w:480,h:240},style:{chartConfig:{label:{show:true,format:"{x}"}}}}]}')"
INVALID_CHART_RESPONSE="$(curl -sS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -X PUT \
  -d "$(jq -nc --arg schema "${INVALID_CHART_SCHEMA}" '{schemaJson:$schema}')" \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/${DASHBOARD_PAGE_ID}/draft")"
INVALID_CHART_CODE="$(printf '%s' "${INVALID_CHART_RESPONSE}" | jq -r '.code // empty')"
[[ "${INVALID_CHART_CODE}" == "500" ]] || { echo "非法图表标签格式未被服务端拦截：${INVALID_CHART_RESPONSE}" >&2; exit 1; }

VALID_CHART_PAGE_CODE="smoke-chart-config-${RANDOM}-${RANDOM}"
VALID_CHART_SCHEMA="$(jq -nc '{schemaVersion:"1.0",canvas:{width:1920,height:1080},widgets:[{id:"chart-config-test",type:"line-chart",layout:{x:0,y:0,w:480,h:240},style:{title:"通用配置测试",titleVerticalAlign:"bottom",chartConfig:{legend:{show:true,position:"right",orient:"vertical",margin:{left:8,right:12,top:16,bottom:20}},xAxis:{type:"time",axisLineShow:false},yAxis:{type:"value",axisLineShow:true},valueScale:"ten-thousand",valuePrecision:1,label:{show:true,format:"{b}: {c}"}}}},{id:"static-data-test",type:"bar-chart",layout:{x:500,y:0,w:480,h:240},binding:{sourceType:"STATIC",datasetCode:"",fieldMap:{category:"name",value:"value",valueFields:["value","secondary"]},staticRows:[{name:"静态一",value:7,secondary:4},{name:"静态二",value:3,secondary:6}]},style:{title:"静态数据源"}}]}')"
VALID_CHART_PAGE_RESPONSE="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -X POST \
  -d "$(jq -nc --arg code "${VALID_CHART_PAGE_CODE}" --arg schema "${VALID_CHART_SCHEMA}" '{pageCode:$code,pageName:"通用图表配置测试",schemaJson:$schema,remark:"自动化测试"}')" \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page")"
assert_json_code "${VALID_CHART_PAGE_RESPONSE}" 200 "保存合法通用图表配置"
SMOKE_PAGE_ID="$(printf '%s' "${VALID_CHART_PAGE_RESPONSE}" | jq -r '.data.pageId')"
[[ -n "${SMOKE_PAGE_ID}" && "${SMOKE_PAGE_ID}" != "null" ]] || { echo "合法图表配置测试页未返回 pageId：${VALID_CHART_PAGE_RESPONSE}" >&2; exit 1; }
VALID_CHART_PAGE_READ="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/${SMOKE_PAGE_ID}")"
assert_json_code "${VALID_CHART_PAGE_READ}" 200 "读取合法通用图表配置"
printf '%s' "${VALID_CHART_PAGE_READ}" | jq -e '.data.currentSchema as $schema | ($schema.widgets | any(.[]; .style.titleVerticalAlign == "bottom" and .style.chartConfig.legend.margin.right == 12 and .style.chartConfig.xAxis.type == "time" and .style.chartConfig.valueScale == "ten-thousand")) and ($schema.widgets | any(.[]; .binding.sourceType == "STATIC" and .binding.staticRows[0].value == 7 and .binding.staticRows[1].secondary == 6 and .binding.fieldMap.valueFields == ["value","secondary"]))' >/dev/null \
  || { echo "合法通用图表配置保存后读取不一致：${VALID_CHART_PAGE_READ}" >&2; exit 1; }
curl -fsS -H "Authorization: Bearer ${TOKEN}" -X DELETE "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/${SMOKE_PAGE_ID}" >/dev/null
SMOKE_PAGE_ID=""

INVALID_MULTI_SERIES_SCHEMA="$(jq -nc '{schemaVersion:"1.0",canvas:{width:1920,height:1080},widgets:[{id:"invalid-series-test",type:"line-chart",layout:{x:0,y:0,w:480,h:240},binding:{datasetCode:"labor-hik-events-api",fieldMap:{category:"direction",valueFields:["eventId","eventId"]}}}]}')"
INVALID_MULTI_SERIES_RESPONSE="$(curl -sS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -X PUT \
  -d "$(jq -nc --arg schema "${INVALID_MULTI_SERIES_SCHEMA}" '{schemaJson:$schema}')" \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/${DASHBOARD_PAGE_ID}/draft")"
INVALID_MULTI_SERIES_CODE="$(printf '%s' "${INVALID_MULTI_SERIES_RESPONSE}" | jq -r '.code // empty')"
[[ "${INVALID_MULTI_SERIES_CODE}" == "500" ]] || { echo "重复多系列字段未被服务端拦截：${INVALID_MULTI_SERIES_RESPONSE}" >&2; exit 1; }

INVALID_FIELD_MAP_SCHEMA="$(jq -nc '{schemaVersion:"1.0",canvas:{width:1920,height:1080},widgets:[{id:"invalid-field-map-test",type:"bar-chart",layout:{x:0,y:0,w:480,h:240},binding:{sourceType:"STATIC",fieldMap:{category:["name"]},staticRows:[{name:"静态一",value:7}]}}]}')"
INVALID_FIELD_MAP_RESPONSE="$(curl -sS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -X PUT \
  -d "$(jq -nc --arg schema "${INVALID_FIELD_MAP_SCHEMA}" '{schemaJson:$schema}')" \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/${DASHBOARD_PAGE_ID}/draft")"
INVALID_FIELD_MAP_CODE="$(printf '%s' "${INVALID_FIELD_MAP_RESPONSE}" | jq -r '.code // empty')"
[[ "${INVALID_FIELD_MAP_CODE}" == "500" ]] || { echo "非字符串普通字段映射未被服务端拦截：${INVALID_FIELD_MAP_RESPONSE}" >&2; exit 1; }

MAP_CONFIG_PAGE_CODE="smoke-map-config-${RANDOM}-${RANDOM}"
MAP_CONFIG_SCHEMA="$(jq -nc '{schemaVersion:"1.0",canvas:{width:960,height:540},widgets:[{id:"map-config-test",type:"map-chart",layout:{x:0,y:0,w:960,h:540},binding:{sourceType:"STATIC",fieldMap:{label:"region",value:"value",longitude:"longitude",latitude:"latitude"},staticRows:[{region:"示例一区",value:25,longitude:101,latitude:31},{region:"示例二区",value:80,longitude:103,latitude:31}]},style:{title:"地图高级配置",mapRef:"/dashboard/assets/map/demo-region.json",mapShowLabels:true,mapLabelColor:"#dbeaf4",mapLabelFontSize:12,mapRoam:true,mapZoom:1.2,mapAspectScale:1,mapLayoutX:48,mapLayoutY:52,mapLayoutSize:110,mapAreaColor:"#17344a",mapAreaGradient:true,mapCenterColor:"#235c6c",mapEdgeColor:"#10283d",mapBorderColor:"#4c8297",mapBorderWidth:1.2,mapEmphasisColor:"#2d6d80",mapShadowBlur:10,mapShadowOffsetX:2,mapShadowOffsetY:3,mapShadowColor:"rgba(0,0,0,.35)",mapVisualMap:true,mapVisualMin:0,mapVisualMax:100,mapVisualMinColor:"#17344a",mapVisualMaxColor:"#35d4b0",mapPointSize:10}}]}')"
MAP_CONFIG_PAGE_RESPONSE="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -X POST \
  -d "$(jq -nc --arg code "${MAP_CONFIG_PAGE_CODE}" --arg schema "${MAP_CONFIG_SCHEMA}" '{pageCode:$code,pageName:"地图高级配置测试",schemaJson:$schema,remark:"自动化测试"}')" \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page")"
assert_json_code "${MAP_CONFIG_PAGE_RESPONSE}" 200 "保存地图高级配置"
SMOKE_PAGE_ID="$(printf '%s' "${MAP_CONFIG_PAGE_RESPONSE}" | jq -r '.data.pageId')"
MAP_CONFIG_PAGE_READ="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/${SMOKE_PAGE_ID}")"
assert_json_code "${MAP_CONFIG_PAGE_READ}" 200 "读取地图高级配置"
printf '%s' "${MAP_CONFIG_PAGE_READ}" | jq -e '.data.currentSchema.widgets[0] | .style.mapAreaGradient == true and .style.mapVisualMap == true and .style.mapZoom == 1.2 and .style.mapLayoutSize == 110 and .binding.staticRows[1].value == 80' >/dev/null \
  || { echo "地图高级配置保存后读取不一致：${MAP_CONFIG_PAGE_READ}" >&2; exit 1; }
curl -fsS -H "Authorization: Bearer ${TOKEN}" -X DELETE "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/${SMOKE_PAGE_ID}" >/dev/null
SMOKE_PAGE_ID=""

INVALID_MAP_CONFIG_SCHEMA="$(jq -nc '{schemaVersion:"1.0",canvas:{width:960,height:540},widgets:[{id:"invalid-map-config",type:"map-chart",layout:{x:0,y:0,w:960,h:540},style:{mapRef:"/dashboard/assets/map/demo-region.json",mapVisualMap:true,mapVisualMin:100,mapVisualMax:10}}]}')"
INVALID_MAP_CONFIG_RESPONSE="$(curl -sS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -X PUT \
  -d "$(jq -nc --arg schema "${INVALID_MAP_CONFIG_SCHEMA}" '{schemaJson:$schema}')" \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/${DASHBOARD_PAGE_ID}/draft")"
INVALID_MAP_CONFIG_CODE="$(printf '%s' "${INVALID_MAP_CONFIG_RESPONSE}" | jq -r '.code // empty')"
[[ "${INVALID_MAP_CONFIG_CODE}" == "500" ]] || { echo "非法地图视觉映射范围未被服务端拦截：${INVALID_MAP_CONFIG_RESPONSE}" >&2; exit 1; }

EXTENDED_CHART_PAGE_CODE="smoke-extended-chart-${RANDOM}-${RANDOM}"
EXTENDED_CHART_SCHEMA="$(jq -nc '{schemaVersion:"1.0",canvas:{width:960,height:540},widgets:[{id:"treemap-test",type:"treemap-chart",layout:{x:0,y:0,w:470,h:540},binding:{sourceType:"STATIC",fieldMap:{category:"name",value:"value"},staticRows:[{name:"进度",value:45},{name:"质量",value:25},{name:"安全",value:18},{name:"人员",value:12}]},style:{title:"矩形树图测试"}},{id:"calendar-test",type:"calendar-chart",layout:{x:490,y:0,w:470,h:540},binding:{sourceType:"STATIC",fieldMap:{category:"date",value:"value"},staticRows:[{date:"2026-08-01",value:8},{date:"2026-08-02",value:18},{date:"2026-08-03",value:28}]},style:{title:"日历热力图测试"}}]}')"
EXTENDED_CHART_PAGE_RESPONSE="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -X POST \
  -d "$(jq -nc --arg code "${EXTENDED_CHART_PAGE_CODE}" --arg schema "${EXTENDED_CHART_SCHEMA}" '{pageCode:$code,pageName:"扩展图表测试",schemaJson:$schema,remark:"自动化测试"}')" \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page")"
assert_json_code "${EXTENDED_CHART_PAGE_RESPONSE}" 200 "保存矩形树图和日历热力图"
SMOKE_PAGE_ID="$(printf '%s' "${EXTENDED_CHART_PAGE_RESPONSE}" | jq -r '.data.pageId')"
EXTENDED_CHART_PAGE_READ="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/${SMOKE_PAGE_ID}")"
assert_json_code "${EXTENDED_CHART_PAGE_READ}" 200 "读取矩形树图和日历热力图"
printf '%s' "${EXTENDED_CHART_PAGE_READ}" | jq -e '.data.currentSchema.widgets | any(.[]; .type == "treemap-chart") and any(.[]; .type == "calendar-chart" and .binding.staticRows[2].value == 28)' >/dev/null \
  || { echo "扩展图表配置保存后读取不一致：${EXTENDED_CHART_PAGE_READ}" >&2; exit 1; }
curl -fsS -H "Authorization: Bearer ${TOKEN}" -X DELETE "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/${SMOKE_PAGE_ID}" >/dev/null
SMOKE_PAGE_ID=""

echo "验证 JimuReport 对齐组件白名单和页面 JSON"
DESIGNER_COMPONENT_PAGE_CODE="smoke-designer-components-${RANDOM}-${RANDOM}"
DESIGNER_COMPONENT_SCHEMA="$(jq -nc '{schemaVersion:"1.0",canvas:{width:1920,height:1080,scaleMode:"stretch"},widgets:[
 {id:"statistics-test",type:"statistics",layout:{x:0,y:0,w:300,h:180},binding:{sourceType:"STATIC",fieldMap:{label:"label",value:"value",suffix:"suffix",compareValue:"compare",compareLabel:"compareLabel",compareState:"compareState"},staticRows:[{label:"完成率",value:82.5,suffix:"%",compare:8.2,compareLabel:"环比",compareState:"up"}]},interaction:{onClick:"browser",target:"https://example.com/dashboard?source=screen"},style:{statsMode:"card",statsShowCompare:true,statsValueSize:32,borderTransparent:true}},
 {id:"metric-style-test",type:"metric-card",layout:{x:1580,y:0,w:300,h:180},binding:{sourceType:"STATIC",fieldMap:{value:"value"},staticRows:[{value:88}]},style:{title:"指标样式",titleColor:"#fedcba",useSystemPalette:true,fontSize:30,fontWeight:700,textAlign:"right",borderWidth:3,borderColor:"#123456",borderOpacity:0.45,borderStyle:"dashed"}},
 {id:"advanced-table-test",type:"advanced-table",layout:{x:320,y:0,w:420,h:260},binding:{sourceType:"STATIC",fieldMap:{},displayFields:["name","value"],staticRows:[{name:"一",value:1},{name:"二",value:2}]},style:{advancedShowHeader:true,advancedShowIndex:true,advancedStripe:true,advancedRowHeight:32}},
 {id:"carousel-test",type:"carousel",layout:{x:760,y:0,w:420,h:220},binding:{sourceType:"STATIC",fieldMap:{},staticRows:[{name:"一",value:1},{name:"二",value:2}]},style:{carouselInterval:4,carouselDirection:"horizontal",carouselShowIndex:true,carouselHighlight:true}},
 {id:"ring-text-test",type:"ring-text",layout:{x:1200,y:0,w:360,h:220},binding:{sourceType:"STATIC",fieldMap:{category:"name"},staticRows:[{name:"进度"},{name:"质量"}]},style:{ringTextRadius:42,ringTextSpeed:20,ringTextDirection:"normal",ringTextShowOrbit:true}},
 {id:"milestone-test",type:"milestone-timeline",layout:{x:0,y:240,w:760,h:220},binding:{sourceType:"STATIC",fieldMap:{label:"label",startDate:"startDate",endDate:"endDate",status:"status"},staticRows:[{label:"登记",startDate:"2026-08-01",endDate:"2026-08-02",status:"已完成"},{label:"施工",startDate:"2026-08-03",endDate:"2026-08-08",status:"进行中"}]},style:{timelineShowDates:true,timelineShowEndDate:true,backgroundTransparent:true,embeddedMode:true,qualityVisible:false,titleImageEnabled:true,titleImageRef:"/profile/demo-title.svg",titleImageFit:"contain",titleImageAlign:"left",titleImageHeight:32,titlePaddingTop:1,titlePaddingRight:8,titlePaddingBottom:2,titlePaddingLeft:12}},
 {id:"bar3d-test",type:"bar3d-chart",layout:{x:0,y:300,w:420,h:260},binding:{sourceType:"STATIC",fieldMap:{category:"name",value:"value"},staticRows:[{name:"一",value:4},{name:"二",value:7}]},style:{bar3dDepth:8}},
 {id:"map-flow-test",type:"map-flow",layout:{x:440,y:300,w:420,h:260},binding:{sourceType:"STATIC",fieldMap:{fromName:"from",toName:"to",fromLongitude:"flng",fromLatitude:"flat",toLongitude:"tlng",toLatitude:"tlat",value:"value"},staticRows:[{from:"A",to:"B",flng:101,flat:31,tlng:103,tlat:32,value:5}]},style:{mapRef:"/dashboard/assets/map/demo-region.json"}},
 {id:"map-bar-test",type:"map-bar",layout:{x:880,y:300,w:300,h:260},binding:{sourceType:"STATIC",fieldMap:{label:"name",value:"value",longitude:"longitude",latitude:"latitude"},staticRows:[{name:"A",value:5,longitude:101,latitude:31}]},style:{mapRef:"/dashboard/assets/map/demo-region.json"}},
 {id:"map-heat-test",type:"map-heat",layout:{x:1200,y:300,w:300,h:260},binding:{sourceType:"STATIC",fieldMap:{label:"name",value:"value",longitude:"longitude",latitude:"latitude"},staticRows:[{name:"A",value:5,longitude:101,latitude:31}]},style:{mapRef:"/dashboard/assets/map/demo-region.json"}},
 {id:"map-ranking-test",type:"map-ranking",layout:{x:0,y:580,w:420,h:260},binding:{sourceType:"STATIC",fieldMap:{label:"name",value:"value",longitude:"longitude",latitude:"latitude"},staticRows:[{name:"A",value:5,longitude:101,latitude:31}]},style:{mapRef:"/dashboard/assets/map/demo-region.json"}},
 {id:"map-timeline-test",type:"map-timeline",layout:{x:440,y:580,w:420,h:260},binding:{sourceType:"STATIC",fieldMap:{fromName:"from",toName:"to",fromLongitude:"flng",fromLatitude:"flat",toLongitude:"tlng",toLatitude:"tlat",value:"value",group:"period"},staticRows:[{from:"A",to:"B",flng:101,flat:31,tlng:103,tlat:32,value:5,period:"第1期"},{from:"A",to:"B",flng:101,flat:31,tlng:104,tlat:33,value:7,period:"第2期"}]},style:{mapRef:"/dashboard/assets/map/demo-region.json",carouselInterval:3}}
 ]}')"
 DESIGNER_COMPONENT_RESPONSE="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -X POST -d "$(jq -nc --arg code "${DESIGNER_COMPONENT_PAGE_CODE}" --arg schema "${DESIGNER_COMPONENT_SCHEMA}" '{pageCode:$code,pageName:"设计器组件白名单测试",schemaJson:$schema,remark:"自动化测试"}')" "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page")"
 assert_json_code "${DESIGNER_COMPONENT_RESPONSE}" 200 "保存设计器对齐组件"
 SMOKE_PAGE_ID="$(printf '%s' "${DESIGNER_COMPONENT_RESPONSE}" | jq -r '.data.pageId')"
 DESIGNER_COMPONENT_READ="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/${SMOKE_PAGE_ID}")"
 assert_json_code "${DESIGNER_COMPONENT_READ}" 200 "读取设计器对齐组件"
 printf '%s' "${DESIGNER_COMPONENT_READ}" | jq -e '.data.currentSchema as $schema | $schema.canvas.scaleMode == "stretch" and ($schema.widgets | length == 12) and (($schema.widgets | map(.type) | index("statistics")) != null) and (($schema.widgets | map(.type) | index("advanced-table")) != null) and (($schema.widgets | map(.type) | index("carousel")) != null) and (($schema.widgets | map(.type) | index("bar3d-chart")) != null) and (($schema.widgets | map(.type) | index("ring-text")) != null) and (($schema.widgets | map(.type) | index("milestone-timeline")) != null) and (($schema.widgets | map(.type) | index("map-timeline")) != null) and (($schema.widgets[] | select(.id == "statistics-test") | .interaction.onClick) == "browser") and (($schema.widgets[] | select(.id == "statistics-test") | .interaction.target) == "https://example.com/dashboard?source=screen") and (($schema.widgets[] | select(.id == "statistics-test") | .style.borderTransparent) == true) and (($schema.widgets[] | select(.id == "metric-style-test") | .style.useSystemPalette) == true) and (($schema.widgets[] | select(.id == "metric-style-test") | .style.fontSize) == 30) and (($schema.widgets[] | select(.id == "metric-style-test") | .style.fontWeight) == 700) and (($schema.widgets[] | select(.id == "metric-style-test") | .style.textAlign) == "right") and (($schema.widgets[] | select(.id == "metric-style-test") | .style.borderWidth) == 3) and (($schema.widgets[] | select(.id == "metric-style-test") | .style.borderColor) == "#123456") and (($schema.widgets[] | select(.id == "metric-style-test") | .style.borderOpacity) == 0.45) and (($schema.widgets[] | select(.id == "metric-style-test") | .style.borderStyle) == "dashed") and (($schema.widgets[] | select(.id == "metric-style-test") | .style.titleColor) == "#fedcba") and (($schema.widgets[] | select(.id == "milestone-test") | .style.titleImageAlign) == "left") and (($schema.widgets[] | select(.id == "milestone-test") | .style.titlePaddingTop) == 1) and (($schema.widgets[] | select(.id == "milestone-test") | .style.titlePaddingLeft) == 12)' >/dev/null \
   || { echo "设计器对齐组件保存后读取不一致：${DESIGNER_COMPONENT_READ}" >&2; exit 1; }
 INVALID_COMPONENT_SCHEMA="$(jq -nc '{schemaVersion:"1.0",canvas:{width:640,height:360},widgets:[{id:"invalid-stats",type:"statistics",layout:{x:0,y:0,w:300,h:180},style:{statsValueSize:999}}]}')"
 INVALID_COMPONENT_RESPONSE="$(curl -sS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -X PUT -d "$(jq -nc --arg schema "${INVALID_COMPONENT_SCHEMA}" '{schemaJson:$schema}')" "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/${DASHBOARD_PAGE_ID}/draft")"
 [[ "$(printf '%s' "${INVALID_COMPONENT_RESPONSE}" | jq -r '.code // empty')" == "500" ]] || { echo "非法设计器组件属性未被服务端拦截：${INVALID_COMPONENT_RESPONSE}" >&2; exit 1; }
 INVALID_BORDER_OPACITY_SCHEMA="$(jq -nc '{schemaVersion:"1.0",canvas:{width:640,height:360},widgets:[{id:"invalid-border-opacity",type:"metric-card",layout:{x:0,y:0,w:300,h:180},style:{borderOpacity:1.2}}]}')"
 INVALID_BORDER_OPACITY_RESPONSE="$(curl -sS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -X PUT -d "$(jq -nc --arg schema "${INVALID_BORDER_OPACITY_SCHEMA}" '{schemaJson:$schema}')" "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/${DASHBOARD_PAGE_ID}/draft")"
 [[ "$(printf '%s' "${INVALID_BORDER_OPACITY_RESPONSE}" | jq -r '.code // empty')" == "500" ]] || { echo "非法边框透明度未被服务端拦截：${INVALID_BORDER_OPACITY_RESPONSE}" >&2; exit 1; }
 INVALID_BORDER_STYLE_SCHEMA="$(jq -nc '{schemaVersion:"1.0",canvas:{width:640,height:360},widgets:[{id:"invalid-border-style",type:"metric-card",layout:{x:0,y:0,w:300,h:180},style:{borderStyle:"groove"}}]}')"
 INVALID_BORDER_STYLE_RESPONSE="$(curl -sS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -X PUT -d "$(jq -nc --arg schema "${INVALID_BORDER_STYLE_SCHEMA}" '{schemaJson:$schema}')" "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/${DASHBOARD_PAGE_ID}/draft")"
 [[ "$(printf '%s' "${INVALID_BORDER_STYLE_RESPONSE}" | jq -r '.code // empty')" == "500" ]] || { echo "非法边框样式未被服务端拦截：${INVALID_BORDER_STYLE_RESPONSE}" >&2; exit 1; }
 INVALID_TITLE_PADDING_SCHEMA="$(jq -nc '{schemaVersion:"1.0",canvas:{width:640,height:360},widgets:[{id:"invalid-title-padding",type:"text",layout:{x:0,y:0,w:300,h:180},style:{title:"标题",titlePaddingLeft:121}}]}')"
 INVALID_TITLE_PADDING_RESPONSE="$(curl -sS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -X PUT -d "$(jq -nc --arg schema "${INVALID_TITLE_PADDING_SCHEMA}" '{schemaJson:$schema}')" "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/${DASHBOARD_PAGE_ID}/draft")"
 [[ "$(printf '%s' "${INVALID_TITLE_PADDING_RESPONSE}" | jq -r '.code // empty')" == "500" ]] || { echo "非法标题内边距未被服务端拦截：${INVALID_TITLE_PADDING_RESPONSE}" >&2; exit 1; }
 INVALID_BROWSER_LINK_SCHEMA="$(jq -nc '{schemaVersion:"1.0",canvas:{width:640,height:360},widgets:[{id:"invalid-browser-link",type:"button",layout:{x:0,y:0,w:300,h:180},interaction:{onClick:"browser",target:"javascript:alert(1)"}}]}')"
 INVALID_BROWSER_LINK_RESPONSE="$(curl -sS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -X PUT -d "$(jq -nc --arg schema "${INVALID_BROWSER_LINK_SCHEMA}" '{schemaJson:$schema}')" "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/${DASHBOARD_PAGE_ID}/draft")"
 [[ "$(printf '%s' "${INVALID_BROWSER_LINK_RESPONSE}" | jq -r '.code // empty')" == "500" ]] || { echo "非法浏览器链接未被服务端拦截：${INVALID_BROWSER_LINK_RESPONSE}" >&2; exit 1; }
 curl -fsS -H "Authorization: Bearer ${TOKEN}" -X DELETE "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/${SMOKE_PAGE_ID}" >/dev/null
 SMOKE_PAGE_ID=""

echo "验证受控 custom-html 组件和服务端内容门禁"
CUSTOM_HTML_PAGE_CODE="smoke-custom-html-${RANDOM}-${RANDOM}"
CUSTOM_HTML_CONTENT='<style>#custom-message{color:#35d4b0}</style><div id="custom-message">JLink smoke</div><script>window.JLink.onData(function(rows){var el=document.getElementById("custom-message");if(el)el.dataset.rows=String(rows.length)})</script>'
CUSTOM_HTML_SCHEMA="$(jq -nc --arg html "${CUSTOM_HTML_CONTENT}" '{schemaVersion:"1.0",canvas:{width:960,height:540},widgets:[{id:"custom-html-test",type:"custom-html",layout:{x:0,y:0,w:480,h:240},style:{title:"受控 HTML",htmlContent:$html}}]}')"
CUSTOM_HTML_RESPONSE="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -X POST \
  -d "$(jq -nc --arg code "${CUSTOM_HTML_PAGE_CODE}" --arg schema "${CUSTOM_HTML_SCHEMA}" '{pageCode:$code,pageName:"受控 HTML 组件测试",schemaJson:$schema,remark:"自动化测试"}')" \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page")"
assert_json_code "${CUSTOM_HTML_RESPONSE}" 200 "保存受控 custom-html 组件"
SMOKE_PAGE_ID="$(printf '%s' "${CUSTOM_HTML_RESPONSE}" | jq -r '.data.pageId')"
CUSTOM_HTML_READ="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/${SMOKE_PAGE_ID}")"
assert_json_code "${CUSTOM_HTML_READ}" 200 "读取受控 custom-html 组件"
printf '%s' "${CUSTOM_HTML_READ}" | jq -e --arg html "${CUSTOM_HTML_CONTENT}" '.data.currentSchema.widgets[0].type == "custom-html" and .data.currentSchema.widgets[0].style.htmlContent == $html' >/dev/null \
  || { echo "custom-html 配置保存后读取不一致：${CUSTOM_HTML_READ}" >&2; exit 1; }
for invalid_html in \
  '<iframe src="/dashboard/page/1"></iframe>' \
  '<img src="https://example.invalid/image.png">' \
  '<div onclick="alert(1)">bad</div>' \
  '<div onload=alert(1)>bad</div>' \
  '<script>document.cookie</script>'; do
  INVALID_CUSTOM_HTML_SCHEMA="$(jq -nc --arg html "${invalid_html}" '{schemaVersion:"1.0",canvas:{width:640,height:360},widgets:[{id:"invalid-custom-html",type:"custom-html",layout:{x:0,y:0,w:320,h:180},style:{htmlContent:$html}}]}')"
  INVALID_CUSTOM_HTML_RESPONSE="$(curl -sS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -X POST \
    -d "$(jq -nc --arg code "smoke-invalid-custom-html-${RANDOM}-${RANDOM}" --arg schema "${INVALID_CUSTOM_HTML_SCHEMA}" '{pageCode:$code,pageName:"非法 custom-html 测试",schemaJson:$schema,remark:"自动化测试"}')" \
    "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page")"
  [[ "$(printf '%s' "${INVALID_CUSTOM_HTML_RESPONSE}" | jq -r '.code // empty')" == "500" ]] \
    || { echo "非法 custom-html 内容未被拦截：${INVALID_CUSTOM_HTML_RESPONSE}" >&2; exit 1; }
done
curl -fsS -H "Authorization: Bearer ${TOKEN}" -X DELETE "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/${SMOKE_PAGE_ID}" >/dev/null
SMOKE_PAGE_ID=""

INVALID_SELF_DRILLDOWN_SCHEMA="$(jq -nc '{schemaVersion:"1.0",canvas:{width:1920,height:1080},widgets:[{id:"self-drilldown-test",type:"bar-chart",layout:{x:0,y:0,w:480,h:240},interaction:{onClick:"self-drilldown",drilldown:{levels:[{parameterMappings:[{sourceField:"value",targetParameter:"p"}]}],oops:true}}}]}')"
INVALID_SELF_DRILLDOWN_RESPONSE="$(curl -sS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -X PUT \
  -d "$(jq -nc --arg schema "${INVALID_SELF_DRILLDOWN_SCHEMA}" '{schemaJson:$schema}')" \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/${DASHBOARD_PAGE_ID}/draft")"
INVALID_SELF_DRILLDOWN_CODE="$(printf '%s' "${INVALID_SELF_DRILLDOWN_RESPONSE}" | jq -r '.code // empty')"
[[ "${INVALID_SELF_DRILLDOWN_CODE}" == "500" ]] || { echo "非法组件逐级钻取未被服务端拦截：${INVALID_SELF_DRILLDOWN_RESPONSE}" >&2; exit 1; }

echo "验证数据源登记、凭证保护和引用删除约束"
SOURCE_TEST_CODE="smoke-source-${RANDOM}-${RANDOM}"
SOURCE_TEST_DATASET="smoke-dataset-${RANDOM}-${RANDOM}"
SOURCE_CONFIG="$(jq -nc --arg url "http://127.0.0.1:${TEST_BACKEND_PORT}" --argjson port "${TEST_BACKEND_PORT}" '{baseUrl:$url,path:"/captchaImage",method:"GET",authProvider:"NO_AUTH",networkProfile:"PRIVATE_LINK",allowedHosts:["127.0.0.1"],allowedCidrs:["127.0.0.1/32"],allowedPorts:[$port]}')"
SOURCE_CREATE_RESPONSE="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -X POST \
  -d "$(jq -nc --arg code "${SOURCE_TEST_CODE}" --arg config "${SOURCE_CONFIG}" '{sourceCode:$code,sourceName:"冒烟 HTTP 来源",sourceType:"HTTP",status:"ACTIVE",configJson:$config,remark:"自动化测试"}')" \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/source")"
assert_json_code "${SOURCE_CREATE_RESPONSE}" 200 "新增 HTTP 数据源"
SOURCE_ID="$(printf '%s' "${SOURCE_CREATE_RESPONSE}" | jq -r '.data.dataSourceId')"
SMOKE_SOURCE_ID="${SOURCE_ID}"
SOURCE_HAS_SECRET="$(printf '%s' "${SOURCE_CREATE_RESPONSE}" | jq -r '.data.hasSecret')"
[[ "${SOURCE_HAS_SECRET}" == "false" ]] || { echo "数据源新增响应泄露或误报凭证状态：${SOURCE_CREATE_RESPONSE}" >&2; exit 1; }
SOURCE_DETAIL="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/source/${SOURCE_TEST_CODE}")"
assert_json_code "${SOURCE_DETAIL}" 200 "查询数据源详情"
if [[ "${SOURCE_DETAIL}" == *"secretCiphertext"* || "${SOURCE_DETAIL}" == *"password"* || "${SOURCE_DETAIL}" == *"Authorization"* ]]; then
  echo "数据源详情包含敏感字段：${SOURCE_DETAIL}" >&2
  exit 1
fi
SOURCE_ENDPOINT_RESPONSE="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -X POST \
  -d '{"endpointCode":"captcha","endpointName":"来源探测接口","path":"/captchaImage","method":"GET","requestContentType":"NONE","responseType":"JSON","authProvider":"INHERIT","configJson":"{\"envelopeMode\":\"HTTP_ONLY\"}","status":"DRAFT"}' \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/integration/endpoints/${SOURCE_TEST_CODE}")"
assert_json_code "${SOURCE_ENDPOINT_RESPONSE}" 200 "新增来源接口"
SOURCE_ENDPOINT_TEST="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -X POST -d '{}' \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/integration/endpoints/${SOURCE_TEST_CODE}/captcha/test")"
assert_json_code "${SOURCE_ENDPOINT_TEST}" 200 "测试来源接口"
SOURCE_ENDPOINT_ENABLED="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -X PUT \
  -d "$(printf '%s' "${SOURCE_ENDPOINT_RESPONSE}" | jq -c '{endpointId:.data.endpointId,status:"ACTIVE"}')" \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/integration/endpoints/${SOURCE_TEST_CODE}")"
assert_json_code "${SOURCE_ENDPOINT_ENABLED}" 200 "启用来源接口"
SOURCE_DATASET_RESPONSE="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -X POST \
  -d "$(jq -nc --arg code "${SOURCE_TEST_DATASET}" --arg config "$(jq -nc --arg endpoint "${SOURCE_TEST_CODE}" '{sourceCode:$endpoint,endpointCode:"captcha",response:{rowsPath:""}}')" '{datasetCode:$code,datasetName:"冒烟引用数据集",dataType:"API",status:"DRAFT",configJson:$config,fieldSchemaJson:"[]",paramSchemaJson:"[]",timeoutSeconds:5,refreshSeconds:30}')" \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/dataset")"
assert_json_code "${SOURCE_DATASET_RESPONSE}" 200 "新增数据源引用数据集"
SOURCE_DATASET_ID="$(printf '%s' "${SOURCE_DATASET_RESPONSE}" | jq -r '.data.datasetId')"
SMOKE_DATASET_ID="${SOURCE_DATASET_ID}"
SOURCE_DELETE_REFERENCED="$(curl -sS -H "Authorization: Bearer ${TOKEN}" -X DELETE "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/source/${SOURCE_ID}")"
[[ "$(printf '%s' "${SOURCE_DELETE_REFERENCED}" | jq -r '.code // empty')" == "500" ]] || { echo "被数据集引用的数据源未被阻止删除：${SOURCE_DELETE_REFERENCED}" >&2; exit 1; }
if [[ -z "${DASHBOARD_DATASOURCE_ENCRYPTION_KEY:-}" ]]; then
  SECRET_SOURCE_RESPONSE="$(curl -sS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' -X POST \
    -d "$(jq -nc --arg code "secret-${SOURCE_TEST_CODE}" --arg config "${SOURCE_CONFIG}" '{sourceCode:$code,sourceName:"冒烟密钥来源",sourceType:"HTTP",status:"DRAFT",configJson:$config,secret:"test-only-placeholder"}')" \
    "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/source")"
  [[ "$(printf '%s' "${SECRET_SOURCE_RESPONSE}" | jq -r '.code // empty')" == "500" ]] || { echo "缺少加密密钥时仍允许保存凭证：${SECRET_SOURCE_RESPONSE}" >&2; exit 1; }
fi
curl -fsS -H "Authorization: Bearer ${TOKEN}" -X DELETE "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/dataset/${SOURCE_DATASET_ID}" >/dev/null
curl -fsS -H "Authorization: Bearer ${TOKEN}" -X DELETE "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/source/${SOURCE_ID}" >/dev/null
SMOKE_DATASET_ID=""
SMOKE_SOURCE_ID=""

echo "验证小时、天和永久只读分享"
SHARE_RESPONSE="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
  -d '{"expiresHours":1}' "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/${DASHBOARD_PAGE_ID}/shares")"
assert_json_code "${SHARE_RESPONSE}" 200 "创建小时分享"
SHARE_TOKEN="$(printf '%s' "${SHARE_RESPONSE}" | jq -r '.data.token')"
[[ "${SHARE_TOKEN}" != "" && "${SHARE_TOKEN}" != "null" ]] || { echo "分享响应缺少令牌" >&2; exit 1; }
SMOKE_SHARE_HOUR_ID="$(printf '%s' "${SHARE_RESPONSE}" | jq -r '.data.shareId')"
printf '%s' "${SHARE_RESPONSE}" | jq -e '.data.permanent == false and .data.expiresAt != null' >/dev/null \
  || { echo "小时分享有效期异常：${SHARE_RESPONSE}" >&2; exit 1; }

DAY_SHARE_RESPONSE="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
  -d '{"expiresValue":2,"expiresUnit":"DAY"}' "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/${DASHBOARD_PAGE_ID}/shares")"
assert_json_code "${DAY_SHARE_RESPONSE}" 200 "创建天分享"
SMOKE_SHARE_DAY_ID="$(printf '%s' "${DAY_SHARE_RESPONSE}" | jq -r '.data.shareId')"
printf '%s' "${DAY_SHARE_RESPONSE}" | jq -e '.data.permanent == false and .data.expiresAt != null' >/dev/null \
  || { echo "天分享有效期异常：${DAY_SHARE_RESPONSE}" >&2; exit 1; }

PERMANENT_SHARE_RESPONSE="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
  -d '{"permanent":true}' "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/${DASHBOARD_PAGE_ID}/shares")"
assert_json_code "${PERMANENT_SHARE_RESPONSE}" 200 "创建永久分享"
SMOKE_SHARE_PERMANENT_ID="$(printf '%s' "${PERMANENT_SHARE_RESPONSE}" | jq -r '.data.shareId')"
PERMANENT_SHARE_TOKEN="$(printf '%s' "${PERMANENT_SHARE_RESPONSE}" | jq -r '.data.token')"
printf '%s' "${PERMANENT_SHARE_RESPONSE}" | jq -e '.data.permanent == true and .data.expiresAt == null' >/dev/null \
  || { echo "永久分享仍存在过期时间：${PERMANENT_SHARE_RESPONSE}" >&2; exit 1; }

SHARE_LIST_RESPONSE="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/${DASHBOARD_PAGE_ID}/shares")"
assert_json_code "${SHARE_LIST_RESPONSE}" 200 "查询分享列表"
printf '%s' "${SHARE_LIST_RESPONSE}" | jq -e \
  --arg hourId "${SMOKE_SHARE_HOUR_ID}" --arg hourToken "${SHARE_TOKEN}" --arg dayId "${SMOKE_SHARE_DAY_ID}" --arg permanentId "${SMOKE_SHARE_PERMANENT_ID}" --arg permanentToken "${PERMANENT_SHARE_TOKEN}" \
  '(.data | any(.[]; (.shareId|tostring) == $hourId and .token == $hourToken and .expiresAt != null)) and
   (.data | any(.[]; (.shareId|tostring) == $dayId and .expiresAt != null)) and
   (.data | any(.[]; (.shareId|tostring) == $permanentId and .token == $permanentToken and .expiresAt == null))' >/dev/null \
  || { echo "分享列表未完整返回三种有效期：${SHARE_LIST_RESPONSE}" >&2; exit 1; }

PERMANENT_SHARE_RUNTIME="$(curl -fsS "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/public/share/${PERMANENT_SHARE_TOKEN}")"
assert_json_code "${PERMANENT_SHARE_RUNTIME}" 200 "匿名读取永久分享"
printf '%s' "${PERMANENT_SHARE_RUNTIME}" | jq -e --arg pageId "${DASHBOARD_PAGE_ID}" \
  '.data.shared == true and (.data.pageId|tostring) == $pageId and .data.expiresAt == null' >/dev/null \
  || { echo "永久分享运行态异常：${PERMANENT_SHARE_RUNTIME}" >&2; exit 1; }
SHARE_RUNTIME="$(curl -fsS "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/public/share/${SHARE_TOKEN}")"
assert_json_code "${SHARE_RUNTIME}" 200 "匿名读取分享页面"
SHARED_PAGE_ID="$(printf '%s' "${SHARE_RUNTIME}" | jq -r '.data.pageId')"
[[ "${SHARED_PAGE_ID}" == "${DASHBOARD_PAGE_ID}" ]] || { echo "分享页面绑定错误：${SHARED_PAGE_ID}" >&2; exit 1; }
SHARE_DATA="$(curl -fsS -H 'Content-Type: application/json' \
  -d "$(jq -nc --arg token "${SHARE_TOKEN}" --argjson pageId "${DASHBOARD_PAGE_ID}" '{pageId:$pageId,widgetId:"design-json-chart",datasetCode:"design-json-demo",params:{},filters:[]}')" \
  "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/public/share/${SHARE_TOKEN}/runtime/data")"
assert_json_code "${SHARE_DATA}" 200 "分享页面组件取数"
SHARE_WS_TOKEN="${SHARE_TOKEN}" SHARE_WS_PAGE_ID="${DASHBOARD_PAGE_ID}" SHARE_WS_PORT="${TEST_FRONTEND_PORT}" SHARE_WS_BACKEND_PORT="${TEST_BACKEND_PORT}" python3 - <<'PY'
import base64, json, os, socket, urllib.parse
host = "127.0.0.1"
port = int(os.environ["SHARE_WS_PORT"])
query = urllib.parse.urlencode({
    "pageId": os.environ["SHARE_WS_PAGE_ID"],
    "widgetId": "labor-hik-live-list",
    "datasetCode": "labor-hik-live",
    "token": os.environ["SHARE_WS_TOKEN"],
})
key = base64.b64encode(os.urandom(16)).decode()
sock = socket.create_connection((host, port), timeout=8)
request = "\r\n".join([
    f"GET /dev-api/dashboard/runtime/share/ws?{query} HTTP/1.1",
    f"Host: {host}:{port}", "Upgrade: websocket", "Connection: Upgrade",
    f"Sec-WebSocket-Key: {key}", "Sec-WebSocket-Version: 13",
    f"Origin: http://127.0.0.1:{port}", "\r\n",
])
sock.sendall(request.encode())
response = b""
while b"\r\n\r\n" not in response:
    response += sock.recv(4096)
headers, remainder = response.split(b"\r\n\r\n", 1)
if b" 101 " not in headers:
    raise RuntimeError("分享 WebSocket 握手失败: " + headers.decode("latin1", errors="replace"))
def read_exact(size):
    value = b""
    while len(value) < size:
        value += sock.recv(size - len(value))
    return value
def read_frame(initial=b""):
    header = initial
    while len(header) < 2: header += read_exact(2 - len(header))
    first, second = header[:2]
    length = second & 0x7f
    if length == 126: length = int.from_bytes(read_exact(2), "big")
    elif length == 127: length = int.from_bytes(read_exact(8), "big")
    payload = read_exact(length) if length else b""
    return first & 0x0f, payload
opcode, payload = read_frame(remainder)
message = json.loads(payload.decode())
if opcode != 1 or message.get("datasetCode") != "labor-hik-live":
    raise RuntimeError("分享 WebSocket 首条消息不符合契约")
sock.close()
print("分享 WebSocket 订阅通过")
PY
REVOKE_RESPONSE="$(curl -fsS -X DELETE -H "Authorization: Bearer ${TOKEN}" "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/${DASHBOARD_PAGE_ID}/shares/${SMOKE_SHARE_HOUR_ID}")"
assert_json_code "${REVOKE_RESPONSE}" 200 "撤销小时分享"
REVOKED_SHARE_RUNTIME="$(curl -sS "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/public/share/${SHARE_TOKEN}")"
[[ "$(printf '%s' "${REVOKED_SHARE_RUNTIME}" | jq -r '.code // empty')" == "500" ]] \
  || { echo "已撤销分享仍可访问：${REVOKED_SHARE_RUNTIME}" >&2; exit 1; }
DAY_REVOKE_RESPONSE="$(curl -fsS -X DELETE -H "Authorization: Bearer ${TOKEN}" "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/${DASHBOARD_PAGE_ID}/shares/${SMOKE_SHARE_DAY_ID}")"
assert_json_code "${DAY_REVOKE_RESPONSE}" 200 "撤销天分享"
PERMANENT_REVOKE_RESPONSE="$(curl -fsS -X DELETE -H "Authorization: Bearer ${TOKEN}" "http://127.0.0.1:${TEST_BACKEND_PORT}/dashboard/page/${DASHBOARD_PAGE_ID}/shares/${SMOKE_SHARE_PERMANENT_ID}")"
assert_json_code "${PERMANENT_REVOKE_RESPONSE}" 200 "撤销永久分享"

echo "验证平台 WebSocket 运行态订阅"
WS_TOKEN="${TOKEN}" WS_PAGE_ID="${DASHBOARD_PAGE_ID}" WS_PORT="${TEST_FRONTEND_PORT}" WS_FRONTEND_PORT="${TEST_FRONTEND_PORT}" python3 - <<'PY'
import base64
import json
import os
import socket
import urllib.parse

host = "127.0.0.1"
port = int(os.environ["WS_PORT"])
frontend_port = os.environ["WS_FRONTEND_PORT"]
query = urllib.parse.urlencode({
    "pageId": os.environ["WS_PAGE_ID"],
    "widgetId": "labor-hik-live-list",
    "datasetCode": "labor-hik-live",
    "token": os.environ["WS_TOKEN"],
})
key = base64.b64encode(os.urandom(16)).decode()
sock = socket.create_connection((host, port), timeout=8)
request = "\r\n".join([
    f"GET /dev-api/dashboard/runtime/ws?{query} HTTP/1.1",
    f"Host: {host}:{port}",
    "Upgrade: websocket",
    "Connection: Upgrade",
    f"Sec-WebSocket-Key: {key}",
    "Sec-WebSocket-Version: 13",
    f"Origin: http://127.0.0.1:{frontend_port}",
    "\r\n",
])
sock.sendall(request.encode())
response = b""
while b"\r\n\r\n" not in response:
    chunk = sock.recv(4096)
    if not chunk:
        raise RuntimeError("WebSocket 握手提前断开")
    response += chunk
headers, remainder = response.split(b"\r\n\r\n", 1)
if b" 101 " not in headers:
    raise RuntimeError("WebSocket 握手失败: " + headers.decode("latin1", errors="replace"))

def read_exact(size):
    value = b""
    while len(value) < size:
        chunk = sock.recv(size - len(value))
        if not chunk:
            raise RuntimeError("WebSocket 连接已关闭")
        value += chunk
    return value

def read_frame(initial=b""):
    header = initial
    while len(header) < 2:
        header += read_exact(2 - len(header))
    first, second = header[:2]
    opcode = first & 0x0F
    length = second & 0x7F
    if length == 126:
        length = int.from_bytes(read_exact(2), "big")
    elif length == 127:
        length = int.from_bytes(read_exact(8), "big")
    mask = read_exact(4) if second & 0x80 else b""
    payload = read_exact(length) if length else b""
    if mask:
        payload = bytes(item ^ mask[index % 4] for index, item in enumerate(payload))
    return opcode, payload

opcode, payload = read_frame(remainder)
if opcode != 1:
    raise RuntimeError(f"WebSocket 首条消息不是文本帧: opcode={opcode}, payload={payload!r}")
message = json.loads(payload.decode())
if message.get("datasetCode") != "labor-hik-live":
    raise RuntimeError("WebSocket 数据集编码不匹配")
if message.get("quality") not in {"SUCCESS", "NO_DATA", "SOURCE_ERROR", "NOT_CONNECTED", "AUTH_ERROR"}:
    raise RuntimeError("WebSocket 返回了未知质量状态: " + str(message.get("quality")))
if not isinstance(message.get("rows"), list):
    raise RuntimeError("WebSocket rows 不是数组")

# 客户端文本帧必须带 mask；发送受控订阅参数请求立即刷新，验证页面过滤器/组件
# 参数能进入平台通道且连接可持续工作。
payload = json.dumps({"type": "subscribe", "params": {"page": 0, "size": 5}, "filters": []}, separators=(",", ":")).encode()
mask = os.urandom(4)
masked = bytes(item ^ mask[index % 4] for index, item in enumerate(payload))
sock.sendall(bytes([0x81, 0x80 | len(payload)]) + mask + masked)
opcode, payload = read_frame()
if opcode != 1:
    raise RuntimeError(f"WebSocket 刷新响应不是文本帧: opcode={opcode}")
refresh_message = json.loads(payload.decode())
if refresh_message.get("datasetCode") != "labor-hik-live" or not isinstance(refresh_message.get("rows"), list):
    raise RuntimeError("WebSocket 参数订阅响应不符合契约")
print("WebSocket 实时订阅通过：首条推送和刷新响应均有效")
sock.close()
PY

echo "[7/8] 验证前端代理鉴权"
PROXY_ROUTERS="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" "http://127.0.0.1:${TEST_FRONTEND_PORT}/dev-api/getRouters")"
assert_json_code "${PROXY_ROUTERS}" 200 "前端代理鉴权"

PROXY_DASHBOARD="$(curl -fsS -H "Authorization: Bearer ${TOKEN}" "http://127.0.0.1:${TEST_FRONTEND_PORT}/dev-api/dashboard/page/list")"
assert_json_code "${PROXY_DASHBOARD}" 200 "前端代理大屏接口"

echo "[8/8] 清理测试配置缓存"
if command -v redis-cli >/dev/null 2>&1; then
  redis-cli -n "${TEST_REDIS_DATABASE}" del sys_config:sys.account.captchaEnabled >/dev/null 2>&1 || true
fi

echo "本地冒烟通过：基础功能、轻量大屏页面/版本、SQL/API/JSON/WebSocket 数据集、外部业务来源（已配置时）和前端代理均已验证。"
