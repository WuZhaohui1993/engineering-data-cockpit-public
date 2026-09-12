export function splitSourceList(value) {
  return String(value || '').split(/[,，\n]/).map(item => item.trim()).filter(Boolean)
}

// 自动值随地址变化；已保存或手工填写的白名单由用户维护。
export function fillHttpSourceAllowlist(form, previousDefaults = {}) {
  let target
  try { target = new URL(String(form.baseUrl || '').trim()) } catch { return previousDefaults }
  if (!['http:', 'https:'].includes(target.protocol)) return previousDefaults
  const defaults = {
    allowedHostsText: target.hostname,
    allowedPortsText: target.port || (target.protocol === 'http:' ? '80' : '443'),
  }
  const nextDefaults = { ...previousDefaults }
  for (const field of Object.keys(defaults)) {
    if (!String(form[field] || '').trim() || form[field] === previousDefaults[field]) {
      form[field] = defaults[field]
      nextDefaults[field] = defaults[field]
    }
  }
  return nextDefaults
}

export function httpSourceNetworkError(form) {
  const hosts = splitSourceList(form.allowedHostsText)
  const ports = splitSourceList(form.allowedPortsText).map(Number)
  if (form.networkProfile === 'PUBLIC_HTTP') {
    if (!hosts.length) return '公网 HTTP/HTTPS 来源必须填写主机白名单'
    if (!ports.length) return '公网 HTTP/HTTPS 来源必须填写端口白名单'
  }
  if (form.networkProfile === 'PRIVATE_LINK' && !splitSourceList(form.allowedCidrsText).length)
    return '专网/隧道来源必须填写批准的地址范围（CIDR）'
  if (ports.some(value => !Number.isInteger(value) || value < 1 || value > 65535))
    return '端口白名单必须是 1-65535 的整数'
  return ''
}
