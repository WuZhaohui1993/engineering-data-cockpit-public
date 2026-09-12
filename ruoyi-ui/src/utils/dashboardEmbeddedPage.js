// 仅嵌入平台运行页。允许执行该页面自身的 Vue 应用，保留其登录与权限校验。
export function dashboardEmbeddedPageUrl(value, parentUrl = '') {
  const text = String(value || '').trim()
  if (!/^\/dashboard\/runtime\/(?:\d+|code\/[A-Za-z0-9_-]+)(?:\?[^#]*)?$/.test(text) || /\\|\.\.|%2f|%5c/i.test(text)) return ''
  const url = new URL(text, 'http://dashboard.local')
  const parent = new URL(parentUrl || '/', 'http://dashboard.local')
  if (url.pathname === parent.pathname || parent.searchParams.get('embeddedDepth') === '1') return ''
  url.searchParams.set('embedMode', '1')
  url.searchParams.set('embeddedDepth', '1')
  return url.pathname + url.search
}
