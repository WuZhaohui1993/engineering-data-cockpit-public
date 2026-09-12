// 稳定资源引用写入页面配置，内置素材随前端发布，上传图片沿用平台资源接口。
const definitions = [
  ['fan', '风机', '设备运行'],
  ['gear', '齿轮', '设备运行'],
  ['alarm', '告警灯', '状态告警'],
  ['radar', '雷达', '监测定位'],
  ['data-flow', '数据传输', '数据通信'],
  ['location-pulse', '定位', '监测定位'],
]

export const dashboardLibraryImages = definitions.flatMap(([key, label, category]) => [
  { key: `${key}-static`, label, category, kind: 'static', resourcePath: `/dashboard/assets/icon-library/${key}.png` },
  { key: `${key}-gif`, label, category, kind: 'gif', resourcePath: `/dashboard/assets/icon-library/${key}.gif` },
])

const builtinPaths = new Set(dashboardLibraryImages.map(item => item.resourcePath))

export function dashboardIconUsesImage(style = {}) {
  return Boolean(String(style.imageRef || '').trim())
}

export function dashboardIconImageUrl(value, resolvePlatformResource = path => path) {
  const path = String(value || '').trim()
  if (builtinPaths.has(path)) return path
  if (!/^\/(?:profile|dashboard\/assets)\//.test(path)
    || /[\\\u0000-\u001f]/.test(path) || path.includes('..')
    || !/\.(?:png|jpe?g|webp|gif|svg)(?:[?#].*)?$/i.test(path)) return ''
  return resolvePlatformResource(path)
}

export function dashboardIconImageLabel(value, assets = []) {
  const builtin = dashboardLibraryImages.find(item => item.resourcePath === value)
  if (builtin) return `${builtin.label} · ${builtin.kind === 'gif' ? 'GIF 动态图标' : '静态图标'}`
  const asset = assets.find(item => item.resourcePath === value)
  return asset?.assetName || String(value || '').split('/').pop() || '图片图标'
}
