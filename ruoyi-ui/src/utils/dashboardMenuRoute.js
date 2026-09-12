const runtimeComponent = 'dashboard/runtime/index'
const safeCode = value => typeof value === 'string' && /^[a-z0-9][a-z0-9_-]{2,63}$/.test(value) ? value : ''
const positiveId = value => Number.isSafeInteger(Number(value)) && Number(value) > 0 ? Number(value) : 0

function codeAtPathEnd(path) {
  const encoded = String(path || '').match(/(?:^|\/)runtime\/code\/([^/]+)\/?$/)?.[1]
  if (!encoded) return ''
  try { return safeCode(decodeURIComponent(encoded)) } catch { return '' }
}

/** Called only on router records received from getRouters, before loadView. */
export function dashboardMenuMeta(menu) {
  if (menu.component !== runtimeComponent) return menu.meta
  let query = {}
  try {
    const parsed = typeof menu.query === 'string' ? JSON.parse(menu.query || '{}') : menu.query
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) query = parsed
  } catch { /* Old menus without valid JSON can still use their original path. */ }
  const code = safeCode(query.dashboardPageCode) || codeAtPathEnd(menu.path)
  const id = positiveId(String(menu.name || menu.routeName || '').match(/^DashboardRuntime(\d+)$/)?.[1])
  return { ...menu.meta, dashboardRuntime: true, dashboardPageCode: code, dashboardPageId: code ? 0 : id }
}

/** URL business query parameters never override a menu's persisted page identity. */
export function resolveDashboardRuntimeTarget(route) {
  const params = route.params || {}, query = route.query || {}, meta = route.meta || {}
  const code = typeof params.pageCode === 'string' ? params.pageCode.trim() : ''
  if (String(route.path || '').startsWith('/dashboard/share/') || params.token) return { pageCode: code, pageId: 0 }
  if (code) return { pageCode: code, pageId: 0 }
  if (positiveId(params.pageId)) return { pageCode: '', pageId: positiveId(params.pageId) }
  if (meta.dashboardRuntime) return { pageCode: safeCode(meta.dashboardPageCode), pageId: safeCode(meta.dashboardPageCode) ? 0 : positiveId(meta.dashboardPageId) }
  if (String(route.path || '').startsWith('/dashboard/runtime/code/')) return { pageCode: codeAtPathEnd(route.path), pageId: 0 }
  // Compatibility for old internal embeds without a path parameter or menu identity.
  return { pageCode: '', pageId: positiveId(query.pageId) }
}
