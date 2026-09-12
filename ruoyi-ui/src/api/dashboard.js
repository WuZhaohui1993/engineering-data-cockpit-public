import request from '@/utils/request'

export function listDashboardPages(query) {
  return request({ url: '/dashboard/page/list', method: 'get', params: query })
}

// 只返回已经发布且可运行的页面。旧版后端没有该接口时，调用方会回退到
// listDashboardPages 并在前端按发布版本字段过滤。
export function listPublishedDashboardPages(query = {}) {
  return request({ url: '/dashboard/page/published', method: 'get', params: query })
}

export function listDashboardPageFolders() {
  return request({ url: '/dashboard/page/folders', method: 'get' })
}

export function addDashboardPageFolder(data) {
  return request({ url: '/dashboard/page/folder', method: 'post', data })
}

export function updateDashboardPageFolder(data) {
  return request({ url: '/dashboard/page/folder', method: 'put', data })
}

export function delDashboardPageFolder(folderId) {
  return request({ url: `/dashboard/page/folder/${folderId}`, method: 'delete' })
}

export function listDashboardPageRecycle(query) {
  return request({ url: '/dashboard/page/recycle', method: 'get', params: query })
}

export function restoreDashboardPage(pageId) {
  return request({ url: `/dashboard/page/${pageId}/restore`, method: 'post' })
}

export function purgeDashboardPage(pageId) {
  return request({ url: `/dashboard/page/${pageId}/purge`, method: 'delete' })
}

export function purgeDashboardPageRecycle() {
  return request({ url: '/dashboard/page/recycle', method: 'delete' })
}

export function getDashboardPage(pageId) {
  return request({ url: `/dashboard/page/${pageId}`, method: 'get' })
}

export function getDashboardRuntime(pageId, preview = false, options = {}) {
  return request({ url: `/dashboard/page/${pageId}/${preview ? 'preview' : 'runtime'}`, method: 'get', dashboardRuntimeRequest: options.dashboardRuntimeRequest === true })
}

export function configureDashboardPageMenu(pageId) {
  return request({ url: `/dashboard/page/${pageId}/menu`, method: 'post' })
}

// 页面编码是跨环境迁移时的稳定标识；pageId 仅作为旧配置和旧路由的兼容路径。
// preview=true 与后端 /page/code/{pageCode}/preview 对齐，读取当前用户可见的
// 草稿（无草稿时回退已发布版本）；运行态则只读取已发布版本。
export function getDashboardRuntimeByCode(pageCode, preview = false, options = {}) {
  const encoded = encodeURIComponent(String(pageCode || ''))
  return request({ url: `/dashboard/page/code/${encoded}/${preview ? 'preview' : 'runtime'}`, method: 'get', dashboardRuntimeRequest: options.dashboardRuntimeRequest === true })
}

export function getDashboardRevisionPreview(pageId, revisionId, options = {}) {
  return request({ url: `/dashboard/page/${pageId}/revision/${revisionId}/preview`, method: 'get', dashboardRuntimeRequest: options.dashboardRuntimeRequest === true })
}

export function addDashboardPage(data) {
  return request({ url: '/dashboard/page', method: 'post', data })
}

export function copyDashboardPage(pageId, data = {}) {
  return request({ url: `/dashboard/page/${pageId}/copy`, method: 'post', data })
}

export function updateDashboardPage(data) {
  return request({ url: '/dashboard/page', method: 'put', data })
}

export function delDashboardPage(pageId) {
  return request({ url: `/dashboard/page/${pageId}`, method: 'delete' })
}

export function saveDashboardDraft(pageId, data) {
  return request({ url: `/dashboard/page/${pageId}/draft`, method: 'put', data })
}

export function publishDashboardPage(pageId, data = {}) {
  return request({ url: `/dashboard/page/${pageId}/publish`, method: 'post', data })
}

export function rollbackDashboardPage(pageId, revisionId, data = {}) {
  return request({ url: `/dashboard/page/${pageId}/rollback/${revisionId}`, method: 'post', data })
}

export function listDashboardShares(pageId) {
  return request({ url: `/dashboard/page/${pageId}/shares`, method: 'get' })
}

export function createDashboardShare(pageId, data = {}) {
  return request({ url: `/dashboard/page/${pageId}/shares`, method: 'post', data })
}

export function updateDashboardShareMode(pageId, shareId, versionMode) {
  return request({ url: `/dashboard/page/${pageId}/shares/${shareId}`, method: 'put', data: { versionMode } })
}

export function revokeDashboardShare(pageId, shareId) {
  return request({ url: `/dashboard/page/${pageId}/shares/${shareId}`, method: 'delete' })
}

export function getDashboardShareRuntime(token, pageCode = '', options = {}) {
  const encodedToken = encodeURIComponent(token)
  const encodedPageCode = String(pageCode || '').trim()
  const url = encodedPageCode
    ? `/dashboard/public/share/${encodedToken}/page/${encodeURIComponent(encodedPageCode)}`
    : `/dashboard/public/share/${encodedToken}`
  return request({ url, method: 'get', dashboardRuntimeRequest: options.dashboardRuntimeRequest === true })
}

export function getDashboardShareVersion(token, pageCode = '', options = {}) {
  const encodedToken = encodeURIComponent(token)
  const code = String(pageCode || '').trim()
  const url = code
    ? `/dashboard/public/share/${encodedToken}/page/${encodeURIComponent(code)}/version`
    : `/dashboard/public/share/${encodedToken}/version`
  return request({ url, method: 'get', dashboardRuntimeRequest: options.dashboardRuntimeRequest === true })
}

export function fetchDashboardShareData(token, pageId, widgetId, datasetCode, params = {}, filters = [], options = {}) {
  return request({ url: `/dashboard/public/share/${encodeURIComponent(token)}/runtime/data`, method: 'post', dashboardRuntimeRequest: options.dashboardRuntimeRequest === true, headers: { repeatSubmit: false }, data: { pageId, widgetId, datasetCode, params, filters } }).then(response => response.data || response)
}

export function fetchDashboardSharePageData(token, pageCode, widgetId, datasetCode, params = {}, filters = [], options = {}) {
  return request({
    url: `/dashboard/public/share/${encodeURIComponent(token)}/page/${encodeURIComponent(String(pageCode || ''))}/runtime/data`,
    method: 'post',
    dashboardRuntimeRequest: options.dashboardRuntimeRequest === true,
    headers: { repeatSubmit: false },
    data: { widgetId, datasetCode, params, filters }
  }).then(response => response.data || response)
}

export function issueDashboardShareMediaRef(token, data = {}, pageCode = '') {
  const encodedToken = encodeURIComponent(token)
  const code = String(pageCode || '').trim()
  const url = code
    ? `/dashboard/public/share/${encodedToken}/page/${encodeURIComponent(code)}/media/ref`
    : `/dashboard/public/share/${encodedToken}/media/ref`
  return request({ url, method: 'post', dashboardRuntimeRequest: true, headers: { repeatSubmit: false }, data })
}

export function dashboardShareMediaUrl(token, mediaRef, pageCode = '') {
  const encodedToken = encodeURIComponent(token)
  const value = encodeURIComponent(String(mediaRef || '').trim())
  const code = String(pageCode || '').trim()
  if (!value) return ''
  return code
    ? `/dashboard/public/share/${encodedToken}/page/${encodeURIComponent(code)}/media/${value}`
    : `/dashboard/public/share/${encodedToken}/media/${value}`
}

export function listDashboardDatasets(query) {
  return request({ url: '/dashboard/dataset/list', method: 'get', params: query })
}

export function listDashboardDatasetGroups() {
  return request({ url: '/dashboard/dataset/group/list', method: 'get' })
}

export function addDashboardDatasetGroup(data) {
  return request({ url: '/dashboard/dataset/group', method: 'post', data })
}

export function updateDashboardDatasetGroup(data) {
  return request({ url: '/dashboard/dataset/group', method: 'put', data })
}

export function delDashboardDatasetGroup(groupId) {
  return request({ url: `/dashboard/dataset/group/${groupId}`, method: 'delete' })
}

export function getDashboardDataset(datasetId) {
  return request({ url: `/dashboard/dataset/${datasetId}`, method: 'get' })
}

export function addDashboardDataset(data) {
  return request({ url: '/dashboard/dataset', method: 'post', data })
}

export function updateDashboardDataset(data) {
  return request({ url: '/dashboard/dataset', method: 'put', data })
}

export function delDashboardDataset(datasetId) {
  return request({ url: `/dashboard/dataset/${datasetId}`, method: 'delete' })
}

export function testDashboardDataset(datasetId, params = {}) {
  return request({ url: `/dashboard/dataset/${datasetId}/test`, method: 'post', data: params })
}

export function previewDashboardDataset(datasetCode, params = {}) {
  return request({ url: `/dashboard/dataset/${encodeURIComponent(datasetCode)}/preview`, method: 'post', data: params }).then(response => response.data || response)
}

// Component preview includes unsaved filters, validated using the same dataset field contract as runtime.
export function previewDashboardWidgetData(datasetCode, params = {}, filters = []) {
  return request({ url: `/dashboard/dataset/${encodeURIComponent(datasetCode)}/preview-widget`, method: 'post', data: { params, filters } }).then(response => response.data || response)
}

export function listDashboardDataSources(query = {}) {
  return request({ url: '/dashboard/source/list', method: 'get', params: query })
}

export function pageDashboardDataSources(query = {}) {
  return request({ url: '/dashboard/source/page', method: 'get', params: query })
}

export function testDashboardDataSource(sourceCode) {
  return request({ url: `/dashboard/source/${encodeURIComponent(sourceCode)}/test`, method: 'post' })
}

export function getDashboardDataSource(sourceCode) {
  return request({ url: `/dashboard/source/detail/code/${encodeURIComponent(sourceCode)}`, method: 'get' })
}

export function addDashboardDataSource(data) {
  return request({ url: '/dashboard/source', method: 'post', data })
}

export function updateDashboardDataSource(data) {
  return request({ url: '/dashboard/source', method: 'put', data })
}

export function delDashboardDataSource(dataSourceId) {
  return request({ url: `/dashboard/source/${dataSourceId}`, method: 'delete' })
}

export function fetchDashboardData(pageId, widgetId, datasetCode, params = {}, preview = false, filters = [], revisionId = null, options = {}) {
  return request({ url: `/dashboard/runtime/${preview ? 'preview/' : ''}data`, method: 'post', dashboardRuntimeRequest: options.dashboardRuntimeRequest === true, headers: { repeatSubmit: false }, data: { pageId, widgetId, datasetCode, params, filters, revisionId } }).then(response => response.data || response)
}

export function issueDashboardMediaRef(data = {}) {
  return request({ url: '/dashboard/runtime/media/ref', method: 'post', data })
}

export function dashboardMediaUrl(mediaRef) {
  const value = String(mediaRef || '').trim()
  return value ? `/dashboard/runtime/media/${encodeURIComponent(value)}` : ''
}

export function listDashboardAssets(query = {}) {
  return request({ url: '/dashboard/asset/list', method: 'get', params: query })
}

export function pageDashboardAssets(query = {}) {
  return request({ url: '/dashboard/asset/page', method: 'get', params: query })
}

export function listDashboardResourceFolders() {
  return request({ url: '/dashboard/resource/folders', method: 'get' })
}

export function addDashboardResourceFolder(data) {
  return request({ url: '/dashboard/resource/folder', method: 'post', data })
}

export function updateDashboardResourceFolder(data) {
  return request({ url: '/dashboard/resource/folder', method: 'put', data })
}

export function delDashboardResourceFolder(folderId) {
  return request({ url: `/dashboard/resource/folder/${folderId}`, method: 'delete' })
}

export function addDashboardAsset(data) {
  return request({ url: '/dashboard/asset', method: 'post', data })
}

export function updateDashboardAsset(data) {
  return request({ url: '/dashboard/asset', method: 'put', data })
}

export function delDashboardAsset(assetId) {
  return request({ url: `/dashboard/asset/${assetId}`, method: 'delete' })
}

export function listDashboardMaps(query = {}) {
  return request({ url: '/dashboard/map/list', method: 'get', params: query })
}

export function pageDashboardMaps(query = {}) {
  return request({ url: '/dashboard/map/page', method: 'get', params: query })
}

export function getDashboardMap(mapId) {
  return request({ url: `/dashboard/map/${mapId}`, method: 'get' })
}

export function addDashboardMap(data) {
  return request({ url: '/dashboard/map', method: 'post', data })
}

export function updateDashboardMap(data) {
  return request({ url: '/dashboard/map', method: 'put', data })
}

export function delDashboardMap(mapId) {
  return request({ url: `/dashboard/map/${mapId}`, method: 'delete' })
}

export function fetchDashboardMediaBlob(mediaRef, shareToken = '', pageCode = '') {
  const url = shareToken ? dashboardShareMediaUrl(shareToken, mediaRef, pageCode) : dashboardMediaUrl(mediaRef);
  return request({ url, method: 'get', responseType: 'blob', headers: { isToken: !shareToken }, timeout: 35000 });
}

export function getDashboardDataSourceConfiguration(sourceCode) {
  return request({ url: `/dashboard/source/${encodeURIComponent(sourceCode)}/configuration`, method: 'get' });
}
