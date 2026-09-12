const fields = {
  cacheSeconds: { label: '缓存有效秒数', max: 3600, initial: 0 },
  sharedFetchSeconds: { label: '共享缓存秒数', max: 3600, initial: 1 },
  staleIfErrorSeconds: { label: '故障沿用缓存秒数', max: 86400, initial: 0 },
}

function parse(value) {
  try {
    const data = typeof value === 'string' ? JSON.parse(value.trim() || '{}') : value
    if (!data || typeof data !== 'object' || Array.isArray(data)) throw new Error()
    return { ...data }
  } catch { throw new Error('接口策略必须是 JSON 对象，请先修正高级配置') }
}

function seconds(key, value) {
  const field = fields[key]
  if (typeof value !== 'number' || !Number.isInteger(value) || value < 0 || value > field.max) {
    throw new Error(`${field.label}必须是 0 至 ${field.max} 的整数`)
  }
  return value
}

export function inspectHttpCachePolicy(value) {
  try {
    const config = parse(value)
    const values = Object.fromEntries(Object.entries(fields).map(([key, field]) => [key, seconds(key, Object.hasOwn(config, key) ? config[key] : field.initial)]))
    const freshSeconds = Math.max(values.cacheSeconds, values.sharedFetchSeconds)
    return { error: '', enabled: freshSeconds > 0 || values.staleIfErrorSeconds > 0, freshSeconds, staleSeconds: values.staleIfErrorSeconds }
  } catch (error) { return { error: error.message, enabled: false, freshSeconds: 0, staleSeconds: 0 } }
}

export function updateHttpCachePolicy(value, field, next) {
  const config = parse(value)
  const current = inspectHttpCachePolicy(value)
  if (current.error) throw new Error(current.error)
  if (field === 'enabled') {
    if (typeof next !== 'boolean') throw new Error('缓存开关必须是布尔值')
    if (!next) Object.keys(fields).forEach(key => { config[key] = 0 })
    else if (!current.enabled) { config.cacheSeconds = 0; config.sharedFetchSeconds = 1; config.staleIfErrorSeconds = 0 }
  } else if (field === 'freshSeconds') {
    const count = seconds('sharedFetchSeconds', next)
    // 合并旧缓存参数，防止界面写小值后仍被旧 cacheSeconds 放大。
    config.cacheSeconds = 0
    config.sharedFetchSeconds = count
  } else if (field === 'staleSeconds') config.staleIfErrorSeconds = seconds('staleIfErrorSeconds', next)
  else throw new Error('不支持的缓存配置项')
  return JSON.stringify(config, null, 2)
}
