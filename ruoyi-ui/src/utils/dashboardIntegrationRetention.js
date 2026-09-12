const retentionLabels = {
  rawRetentionDays: '原文保留天数',
  successRetentionDays: '成功明细保留天数',
  recordRetentionDays: '业务记录保留天数',
}

export function parseIntegrationPolicy(value) {
  try {
    const config = typeof value === 'string' ? JSON.parse(value.trim() || '{}') : (value ?? {})
    if (!config || typeof config !== 'object' || Array.isArray(config)) throw new Error()
    return { ...config }
  } catch {
    throw new Error('接入策略必须是 JSON 对象')
  }
}

export function integrationRetentionDays(value, key) {
  // 老接入方没有新增策略时保持长期保留，不能套用新增表单的 30 天默认值。
  if (value === undefined && key !== 'rawRetentionDays') return 0
  if (typeof value !== 'number' || !Number.isInteger(value) || value < 0 || value > 3650) {
    throw new Error(`${retentionLabels[key]}必须是 0 至 3650 的整数`)
  }
  return value
}

export function inspectIntegrationRetention(value) {
  let config
  try {
    config = parseIntegrationPolicy(value)
  } catch (error) {
    return { parseError: error.message, errors: {}, retainRawBody: false }
  }
  const result = { parseError: '', errors: {}, retainRawBody: config.retainRawBody === true }
  if (config.retainRawBody !== undefined && typeof config.retainRawBody !== 'boolean') {
    result.errors.retainRawBody = '保存原始报文必须是布尔值（true 或 false）'
  }
  for (const key of ['successRetentionDays', 'recordRetentionDays']) {
    try {
      result[key] = integrationRetentionDays(config[key], key)
    } catch (error) {
      result.errors[key] = error.message
    }
  }
  return result
}

export function updateIntegrationRetention(value, key, nextValue) {
  const config = parseIntegrationPolicy(value)
  if (key === 'retainRawBody') {
    if (typeof nextValue !== 'boolean') throw new Error('保存原始报文必须是布尔值（true 或 false）')
    config[key] = nextValue
  } else if (['successRetentionDays', 'recordRetentionDays'].includes(key)) {
    config[key] = integrationRetentionDays(nextValue, key)
  } else {
    throw new Error('不支持的数据保留配置项')
  }
  return JSON.stringify(config, null, 2)
}

export function validateIntegrationRetention(value) {
  const state = inspectIntegrationRetention(value)
  const error = state.parseError || Object.values(state.errors)[0]
  if (error) throw new Error(error)
  return parseIntegrationPolicy(value)
}

export function integrationBatchDetailsExpired(row) {
  return row?.detailExpired === true || !!row?.detailsExpiredAt
}

export function integrationRetentionSummary(row, key) {
  try {
    const config = validateIntegrationRetention(row.configJson)
    if (key === 'rawRetentionDays') {
      const days = integrationRetentionDays(row.rawRetentionDays, key)
      return config.retainRawBody === true && days > 0 ? `${days} 天` : '不保存'
    }
    const days = integrationRetentionDays(config[key], key)
    return days === 0 ? '长期保留' : `${days} 天`
  } catch {
    return '配置待修正'
  }
}
