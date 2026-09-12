// 统一入口的页签与权限目录；详细说明集中维护在 dashboardManagementHelp.js。
export const dashboardDataTabs = [
  { name: 'datasets', label: '数据集', permission: 'dashboard:dataset:list' },
  { name: 'outbound', label: '数据源', permission: 'dashboard:dataset:list' },
  { name: 'inbound', label: '推送接入', permissions: ['dashboard:integration:list', 'dashboard:integration:view', 'dashboard:integration:deadletter'] },
  { name: 'operations', label: '接入运维', permission: 'dashboard:integration:monitor' },
]

export const dashboardInboundTabs = [
  { name: 'integrations', label: '接入方配置', permission: 'dashboard:integration:list' },
  { name: 'batches', label: '接收台账', permission: 'dashboard:integration:view' },
  { name: 'dead', label: '失败消息（死信）', permission: 'dashboard:integration:deadletter' },
]

export function allowedDataManagementTabs(checkPermission) {
  return dashboardDataTabs.filter(tab => checkPermission(tab.permissions || [tab.permission])).map(tab => tab.name)
}

export function allowedInboundDataTabs(checkPermission) {
  return dashboardInboundTabs.filter(tab => checkPermission([tab.permission])).map(tab => tab.name)
}

export const dashboardCreateActions = {
  dataset: { label: '新增数据集', permission: 'dashboard:dataset:edit' },
  source: { label: '新增数据源', permission: 'dashboard:dataset:edit' },
  endpoint: { label: '新增接口', permission: 'dashboard:integration:add' },
  integration: { label: '新增接入方', permission: 'dashboard:integration:add' },
}

export function dataManagementCreateKind(tab, sourceSelected = false, inboundTab = 'integrations') {
  if (tab === 'datasets') return 'dataset'
  if (tab === 'outbound') return sourceSelected ? 'endpoint' : 'source'
  if (tab === 'inbound' && inboundTab === 'integrations') return 'integration'
  return ''
}

export function resolveDataManagementTab(requested, allowed, focus) {
  const candidate = focus === 'source' ? 'outbound' : (['batches', 'dead'].includes(requested) ? 'inbound' : requested)
  return allowed.includes(candidate) ? candidate : (allowed[0] || '')
}

export function resolveInboundDataTab(requested, allowed, inboundTab) {
  const candidate = ['batches', 'dead'].includes(requested) ? requested : inboundTab
  return allowed.includes(candidate) ? candidate : (allowed[0] || '')
}

export function dataManagementTabQuery(query, tab, inboundTab) {
  const { focus, inboundTab: previousInboundTab, ...rest } = query
  return { ...rest, tab, ...(tab === 'inbound' && inboundTab ? { inboundTab } : {}) }
}

export function legacyDatasetDestination(query = {}, hash = '') {
  return { path: '/dashboard/integration', query: { ...query, tab: query.focus === 'source' ? 'outbound' : 'datasets' }, hash, replace: true }
}
