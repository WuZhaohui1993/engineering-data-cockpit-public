import request from '@/utils/request'

export function listDataFolders(scope) {
  return request({ url: `/dashboard/data-folder/${scope}/list`, method: 'get' })
}

export function addDataFolder(scope, data) {
  return request({ url: `/dashboard/data-folder/${scope}`, method: 'post', data })
}

export function updateDataFolder(scope, data) {
  return request({ url: `/dashboard/data-folder/${scope}`, method: 'put', data })
}

export function deleteDataFolder(scope, folderId) {
  return request({ url: `/dashboard/data-folder/${scope}/${folderId}`, method: 'delete' })
}

// 仅调整归档信息，不触发数据集执行配置或测试状态的更新。
export function moveDataFolderItems(scope, data) {
  return request({ url: `/dashboard/data-folder/${scope}/move`, method: 'put', data })
}
