// 仅转换展示文字；协议编码与已保存配置保持原值。
export const integrationProfiles = [
  { value: 'EVENT', label: '事件通知（EVENT）', description: '适合设备采样、门禁通行、告警发生等一次性事件。每次事件使用新的事件编号（eventId）和发生时间（occurredAt）。' },
  { value: 'SNAPSHOT', label: '状态快照（SNAPSHOT）', description: '适合定时上报库存、设备状态或进度汇总。每次快照使用新的快照编号（snapshotId）和快照时间（snapshotTime）；当前不会自动覆盖或删除上一批快照。' },
  { value: 'RECORD', label: '业务记录（RECORD）', description: '适合工单、质量检查、验收记录等结构化业务数据，使用记录编号（recordId）和更新时间（updatedAt）。当前同一编号内容变化会判为冲突，持续更新同一条记录需另行适配。' },
  { value: 'FILE_NOTIFICATION', label: '文件通知（FILE_NOTIFICATION）', description: '适合已受控接入的图片、报告等文件到达通知，使用文件编号（fileId）、接收时间（receivedAt）及有效媒体引用（mediaRef）。须先登记媒体来源编码（sourceCode）；不支持直接推送任意下载地址。' },
]

export const networkProfileLabel = value => ({ PUBLIC_HTTPS: '公网加密访问（HTTPS）', PUBLIC_HTTP: '公网访问（HTTP/HTTPS）', PRIVATE_LINK: '受控专网或隧道' }[value] || `未识别策略（${value || '空'}）`)
export const profileDescription = value => integrationProfiles.find(item => item.value === value)?.description || '请选择与来源业务一致的消息类型。'
export const profileLabel = value => integrationProfiles.find(item => item.value === value)?.label || `未识别类型（${value || '空'}）`
export const requestMethodLabel = value => ({ GET: '获取（GET）', POST: '提交（POST）' }[value] || `请求方法（${value}）`)
export const auditCategoryLabel = value => ({ OUTBOUND: '主动取数', INBOUND: '推送接收', WEBSOCKET: '实时连接', INBOUND_RETENTION: '保留期清理', MEDIA: '媒体访问' }[value] || `其他类型（${value || '空'}）`)

export const integrationOperationCategories = [
  { value: 'OUTBOUND', label: 'HTTP 主动取数' },
  { value: 'INBOUND', label: '第三方推送' },
  { value: 'WEBSOCKET', label: 'WebSocket 实时连接' },
  { value: 'MEDIA', label: '媒体访问' },
  { value: 'INBOUND_RETENTION', label: '保留期清理' },
]

export function integrationOperationResourceKey(row) {
  return JSON.stringify([row.category, row.resourceCode])
}

export function integrationOperationResources(rows = [], category = '') {
  return rows.filter(row => row?.resourceCode && (!category || row.category === category))
}

export function integrationConnectionStatus(value) {
  return ({ CONNECTED: '已连接', CONNECTING: '连接中', RECONNECTING: '重连中', ERROR: '连接异常',
    DISCONNECTED: '未连接', CLOSED: '已关闭', IDLE: '空闲' })[value] || '状态未知'
}

export function integrationConnectionTag(value) {
  return ({ CONNECTED: 'success', CONNECTING: 'primary', RECONNECTING: 'warning', ERROR: 'danger' })[value] || 'info'
}

export function formatIntegrationBytes(value) {
  const bytes = Number(value)
  if (!Number.isFinite(bytes) || bytes < 0) return '—'
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB', 'TB']
  let amount = bytes / 1024, index = 0
  while (amount >= 1024 && index < units.length - 1) { amount /= 1024; index++ }
  return `${amount.toFixed(amount >= 100 ? 0 : 1)} ${units[index]}`
}

export const integrationOperationsPolicyFields = [
  { key: 'auditRetentionDays', label: '访问审计保留天数', help: '适用于主动取数、推送、实时连接、媒体和保留期清理审计。' },
  { key: 'resolvedAlertRetentionDays', label: '已确认告警保留天数', help: '从最近发生时间计算，仅清理已确认告警。' },
  { key: 'testLogRetentionDays', label: '测试日志保留天数', help: '适用于数据集测试和 HTTP 接口测试，不影响其他业务操作日志。' },
]

export function validateIntegrationOperationsPolicy(value = {}) {
  const result = {}
  for (const field of integrationOperationsPolicyFields) {
    const days = value[field.key]
    if (typeof days !== 'number' || !Number.isInteger(days) || days < 1 || days > 3650) {
      throw new Error(`${field.label}必须是 1 至 3650 的整数`)
    }
    result[field.key] = days
  }
  return result
}

const resultLabels = {
  SUCCESS: '成功', ACCEPTED: '已接受', PROCESSING: '处理中', RECEIVED: '已接收', CLEANED: '清理完成',
  ALLOWED: '允许访问', DENIED: '拒绝访问', FAILED: '失败', ERROR: '错误', REJECTED: '已拒绝',
  DEAD_LETTER: '进入死信', CIRCUIT_OPEN: '来源暂时熔断', SOURCE_ERROR: '来源错误',
  AUTH_ERROR: '鉴权失败', AUTH_FAILED: '认证失败', SIGNATURE_INVALID: '签名无效',
  KEY_INVALID: '密钥无效', TIMESTAMP_EXPIRED: '请求已过期', REPLAYED_NONCE: '随机标识已使用',
  IDEMPOTENCY_CONFLICT: '幂等内容冲突', CONFLICT: '业务内容冲突', RATE_LIMITED: '请求过于频繁',
  CONCURRENCY_LIMITED: '并发超限', INTEGRATION_INACTIVE: '接入方未启用',
  PROJECT_NOT_ALLOWED: '外部项目不在允许范围', ITEM_INVALID: '记录格式错误', PARTIAL: '部分成功',
  CONNECTED: '连接成功', CONNECTING: '正在连接', RECONNECTING: '正在重连', DISCONNECTED: '连接断开',
  CONNECTION_FAILED: '连接失败', CONNECTION_ERROR: '连接异常', CLOSED: '连接已关闭',
  MESSAGE_RECEIVED: '收到消息', TIMEOUT: '请求超时', NO_DATA: '暂无数据',
  RECONNECTED: '重连成功', IDLE_CLOSED: '空闲连接已释放', WS_CONNECT_FAILED: '实时连接失败',
  WS_DISCONNECTED: '实时连接断开', WS_RETRY_EXHAUSTED: '重连次数已耗尽',
  WS_HEARTBEAT_TIMEOUT: '实时连接心跳超时', WS_SUBSCRIPTION_FAILED: '实时订阅失败',
  WS_INVALID_JSON: '实时消息格式无效', WS_MESSAGE_TOO_LARGE: '实时消息超出大小限制',
  WS_UNSUPPORTED_MESSAGE: '实时消息类型不支持',
}
export function integrationResultLabel(value) {
  if (!value) return '—'
  return `${resultLabels[value] || '处理结果'}（${value}）`
}

export function integrationProjectScope(row = {}) {
  if (Array.isArray(row.projectScope)) return [...row.projectScope]
  try { const parsed = JSON.parse(row.projectScopeJson || '[]'); return Array.isArray(parsed) ? parsed : [] } catch { return [] }
}

const commonTimezones = [
  ['Asia/Shanghai', '中国标准时间'], ['UTC', '协调世界时'], ['Asia/Hong_Kong', '中国香港时间'],
  ['Asia/Tokyo', '日本时间'], ['Asia/Singapore', '新加坡时间'], ['Asia/Dubai', '迪拜时间'],
  ['Europe/London', '英国时间'], ['Europe/Paris', '法国时间'], ['Europe/Berlin', '德国时间'],
  ['America/New_York', '美国纽约时间'], ['America/Los_Angeles', '美国洛杉矶时间'], ['Australia/Sydney', '澳大利亚悉尼时间'],
]
let zoneOptions
export function timezoneOptions(current = '') {
  if (!zoneOptions) {
    const common = new Map(commonTimezones)
    const supported = typeof Intl.supportedValuesOf === 'function' ? Intl.supportedValuesOf('timeZone') : []
    zoneOptions = [...common.keys(), ...supported.filter(zone => !common.has(zone))].map(value => {
      const name = common.get(value) || new Intl.DateTimeFormat('zh-CN', { timeZone: value, timeZoneName: 'longGeneric' })
        .formatToParts(new Date('2026-01-01T00:00:00Z')).find(part => part.type === 'timeZoneName')?.value || '标准时区'
      return { value, label: `${/[A-Za-z]/.test(name) ? "标准时区" : name}（${value}）` }
    })
  }
  // 兼容服务端曾接受的别名与固定偏移，不擅自改写旧配置；新值只能从下拉选项选择。
  return current && !zoneOptions.some(item => item.value === current)
    ? [...zoneOptions, { value: current, label: `已保存时区（${current}）` }] : zoneOptions
}
export function timezoneLabel(value) { return timezoneOptions(value).find(item => item.value === value)?.label || '未设置' }

export const integrationPolicyHelp = [
  ['时间戳单位', 'timestampUnit', '设备输出十三位毫秒时间戳时改为毫秒（MILLISECONDS）；默认使用秒（SECONDS）。'],
  ['允许时钟偏差', 'clockSkewSeconds', '先校准设备时钟。确有传输延迟时再调整时间窗，默认 300 秒。'],
  ['请求随机标识', 'nonceRequired', '用于防止请求重放，保持开启（true）；当前入站签名始终要求随机标识。'],
  ['强制幂等标识', 'idempotencyRequired', '网络重试时避免重复执行，通常保持开启（true）。'],
  ['从批次编号派生幂等键', 'allowDerivedIdempotency', '来源不能设置幂等请求头，但能保证批次编号唯一且重发稳定时才启用。'],
  ['强制版本请求头', 'requireSchemaHeader', '双方约定必须在请求头声明版本时设为开启（true）。'],
  ['处理方式', 'processingMode', '当前仅支持异步处理（ASYNC），提交后查询最终回执。'],
  ['单条错误处理', 'itemErrorMode', '独立测点可用部分接收（PARTIAL）；整批记录必须同时合法时选择整批拒绝（REJECT）。'],
  ['快速重试范围', 'maxAttempts', '偶发数据库或处理故障时调整，默认 3；不控制发送端重发。'],
  ['进入死信的失败次数', 'deadLetterAfter', '默认累计 5 次处理失败后转人工排查；按运维处理约定调整。'],
  ['保留原始报文', 'retainRawBody', '与上方“保存原始报文”开关同步。默认关闭；开启且原文保留天数大于 0 才保存，标准业务记录仍按独立期限保留。'],
  ['成功明细保留天数', 'successRetentionDays', '0 为长期保留，范围 0 至 3650。成功明细到期后保留必要去重摘要；未完成、失败和死信不自动清理。旧配置缺项按 0 处理。'],
  ['业务记录保留天数', 'recordRetentionDays', '0 为长期保留，范围 0 至 3650。按平台更新时间清理标准业务记录，重复推送不延长期限；保留业务主键及内容摘要防止旧数据重新入库。'],
  ['签名原文规则', 'canonicalProfile', '只有第三方签名拼接契约不同且平台支持时才改，双方必须同步。'],
  ['签名输出编码', 'signatureEncoding', '第三方要求十六进制时使用 HEX 编码；默认使用 Base64 编码（BASE64）。'],
  ['接口密钥请求头', 'apiKeyHeader', '只用于接口密钥（API Key）认证，按来源约定改名；签名认证不使用。'],
  ['浏览器来源白名单', 'allowedOrigins', '仅在确需带来源标识（Origin）的请求时登记完整来源；空数组不会放行带该标识的请求。'],
  ['未知外层字段', 'unknownFieldMode', '默认拒绝（REJECT）；允许忽略额外外层字段时用忽略（IGNORE），不等于业务字段已通过校验。'],
  ['批次编号必填', 'messageIdRequired', '用于跟踪和查询批次，通常保持开启（true）。'],
  ['业务时间必填', 'businessTimeRequired', '用于采样、事件或记录时间，通常保持开启（true）。'],
  ['项目与租户一致', 'projectTenantMustMatch', '只有上游明确将项目编码和租户编码定义为同一值时才开启。'],
]
