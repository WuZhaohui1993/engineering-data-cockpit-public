const maximumGroupNameLength = 100
const copyNameSuffix = ' 副本'

function validGroupId(value) {
  return typeof value === 'string' && value.trim().length > 0
}

function groupName(value, fallback) {
  const name = typeof value === 'string' ? value.trim() : ''
  return (name || fallback).slice(0, maximumGroupNameLength)
}

function ownMetadata(groups, id) {
  if (!groups || typeof groups !== 'object' || Array.isArray(groups)
    || !Object.hasOwn(groups, id)) return undefined
  const metadata = groups[id]
  return metadata && typeof metadata === 'object' && !Array.isArray(metadata) ? metadata : undefined
}

/** 组件仍保持平铺；仅补全真实组合的图层名称和折叠状态，清理已解组/删除的孤立元数据。 */
export function normalizeDashboardGroups(widgets, groups = {}) {
  const ids = [...new Set((Array.isArray(widgets) ? widgets : [])
    .map(widget => widget?.groupId).filter(validGroupId))]
  return Object.fromEntries(ids.map((id, index) => {
    const metadata = ownMetadata(groups, id)
    return [id, {
      name: groupName(metadata?.name, `组合 ${index + 1}`),
      collapsed: metadata?.collapsed !== false,
    }]
  }))
}

/** 复制组合时同步创建独立元数据；映射方向为旧组合 ID -> 新组合 ID。 */
export function copyDashboardGroupMetadata(groups, idMap) {
  const entries = idMap instanceof Map
    ? [...idMap.entries()]
    : idMap && typeof idMap === 'object' && !Array.isArray(idMap) ? Object.entries(idMap) : []
  const mappings = entries.filter(([source, target]) => validGroupId(source) && validGroupId(target) && source !== target)
  const sources = normalizeDashboardGroups(mappings.map(([groupId]) => ({ groupId })), groups)
  return Object.fromEntries(mappings.map(([source, target]) => [target, {
    name: sources[source].name.slice(0, maximumGroupNameLength - copyNameSuffix.length) + copyNameSuffix,
    collapsed: sources[source].collapsed,
  }]))
}
