function isObjectOrArray(value) {
  return value !== null && typeof value === 'object'
}

export function normalizeDashboardJsonRowsPath(value) {
  const path = String(value ?? '').trim()
  if (path.length > 128 || (path && !/^[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*$/.test(path))) {
    throw new Error('数据路径使用点分隔字段名，最多 128 个字符，例如 data.rows')
  }
  return path
}

export function validateDashboardStaticJson(payloadText, rowsPath = '') {
  let payload
  try {
    payload = JSON.parse(payloadText)
  } catch {
    throw new Error('JSON 格式不正确，请检查括号、引号和逗号')
  }
  if (!isObjectOrArray(payload)) throw new Error('JSON 内容必须是对象或数组')
  const path = normalizeDashboardJsonRowsPath(rowsPath)
  let extracted = payload
  for (const key of path ? path.split('.') : []) {
    // 与服务端 JsonNode.path(String) 一致：点分隔路径只访问对象字段，
    // 不把数组下标或 JavaScript 原型属性误判成合法数据路径。
    extracted = isObjectOrArray(extracted) && !Array.isArray(extracted) && Object.hasOwn(extracted, key)
      ? extracted[key]
      : undefined
  }
  if (!isObjectOrArray(extracted)) throw new Error('数据路径必须指向 JSON 中存在的对象或数组')
  return { payload, rowsPath: path }
}

export function buildDashboardJsonDatasetConfig({ configJson, mode = 'STATIC', payloadText, rowsPath, urlRef }) {
  let config
  try {
    config = typeof configJson === 'string' ? JSON.parse(configJson || '{}') : configJson ?? {}
  } catch {
    throw new Error('高级配置 JSON 格式不正确，请修正后保存')
  }
  if (!isObjectOrArray(config) || Array.isArray(config)) throw new Error('高级配置 JSON 必须是对象')
  const normalizedMode = String(mode || 'STATIC').toUpperCase()
  if (normalizedMode === 'STATIC') {
    return { ...config, mode: 'STATIC', ...validateDashboardStaticJson(payloadText, rowsPath) }
  }
  if (normalizedMode === 'URL') {
    return { ...config, mode: 'URL', urlRef, rowsPath: normalizeDashboardJsonRowsPath(rowsPath) }
  }
  throw new Error('JSON 模式不支持')
}
