export const dataFolderScopeLabels = {
  dataset: '数据集',
  source: '数据源',
  integration: '推送接入',
  page: '页面',
  resource: '资源',
}

export const dataFolderEditPermissions = {
  dataset: 'dashboard:dataset:edit',
  source: 'dashboard:dataset:edit',
  integration: 'dashboard:integration:edit',
  page: 'dashboard:page:edit',
  resource: 'dashboard:resource:folder',
}

export const dataFolderCreatePermissions = {
  ...dataFolderEditPermissions,
  page: 'dashboard:page:add',
}

export const dataFolderDeletePermissions = {
  ...dataFolderEditPermissions,
  integration: 'dashboard:integration:delete',
  page: 'dashboard:page:delete',
}

// 整理目录和移动业务对象沿用各自权限，避免目录管理员获得业务编辑权限。
export const dataFolderMovePermissions = {
  ...dataFolderEditPermissions,
  page: 'dashboard:page:edit',
  resource: 'dashboard:resource:edit',
}

export function normalizeDataFolders(folders = []) {
  return folders.map(folder => ({
    ...folder,
    folderId: Number(folder.folderId ?? folder.groupId),
    folderName: folder.folderName ?? folder.groupName ?? '',
    folderCode: folder.folderCode ?? folder.groupCode ?? '',
    groupCode: folder.groupCode ?? folder.folderCode ?? '',
    parentId: Number(folder.parentId || 0),
    sortOrder: Number(folder.sortOrder || 0),
  })).filter(folder => Number.isFinite(folder.folderId) && folder.folderId > 0)
}

// 编辑父目录时排除自己及全部后代，避免循环归属。
export function dataFolderDescendantIds(folders, folderId) {
  const excluded = new Set()
  if (folderId == null || Number(folderId) <= 0) return excluded
  excluded.add(Number(folderId))
  const normalized = normalizeDataFolders(folders)
  let changed = true
  while (changed) {
    changed = false
    normalized.forEach(folder => {
      if (excluded.has(folder.parentId) && !excluded.has(folder.folderId)) {
        excluded.add(folder.folderId)
        changed = true
      }
    })
  }
  return excluded
}

export function buildDataFolderOptions(folders, {
  valueKey = 'folderId', rootLabel = '未分类', excludedId = null, includeRoot = true,
} = {}) {
  const excluded = dataFolderDescendantIds(folders, excludedId)
  const normalized = normalizeDataFolders(folders)
    .filter(folder => !excluded.has(folder.folderId))
    .sort((a, b) => a.sortOrder - b.sortOrder || a.folderId - b.folderId)
  const nodes = new Map(normalized.map(folder => [folder.folderId, {
    value: folder[valueKey], label: folder.folderName, folderId: folder.folderId,
    parentId: folder.parentId, children: [],
  }]))
  const roots = []
  nodes.forEach(node => {
    // 对历史异常父链做容错；树只展示可达的无环结构。
    const visited = new Set([node.folderId])
    let ancestor = nodes.get(node.parentId)
    let cyclic = false
    while (ancestor) {
      if (visited.has(ancestor.folderId)) { cyclic = true; break }
      visited.add(ancestor.folderId)
      ancestor = nodes.get(ancestor.parentId)
    }
    const parent = nodes.get(node.parentId)
    if (parent && !cyclic) parent.children.push(node)
    else roots.push(node)
  })
  return includeRoot
    ? [{ value: valueKey === 'folderId' ? 0 : '', label: rootLabel }, ...roots]
    : roots
}

export function dataFolderName(folders, folderId, allLabel = '全部') {
  if (folderId == null || folderId === '') return allLabel
  if (Number(folderId) === 0) return '未分类'
  if (Number(folderId) === -1) return '系统数据源'
  return normalizeDataFolders(folders).find(folder => folder.folderId === Number(folderId))?.folderName || '未知文件夹'
}
