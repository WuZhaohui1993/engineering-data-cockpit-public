function normalizeDashboardDateOnly(value) {
  if (typeof value !== 'string') return ''
  const match = /^(\d{4})[-/.](\d{2})[-/.](\d{2})$/.exec(
    value.trim(),
  )
  if (!match) return ''
  const year = Number(match[1])
  const month = Number(match[2])
  const day = Number(match[3])
  const leapYear = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0)
  const daysInMonth = [
    31,
    leapYear ? 29 : 28,
    31,
    30,
    31,
    30,
    31,
    31,
    30,
    31,
    30,
    31,
  ]
  if (month < 1 || month > 12 || day < 1 || day > daysInMonth[month - 1])
    return ''
  return `${match[1]}-${match[2]}-${match[3]}`
}

/**
 * 大屏页面统一的日期时间展示格式。
 * 后端可能返回 ISO 字符串、时间戳或 Date 对象，界面统一显示为本地
 * YYYY-MM-DD HH:mm:ss，避免直接渲染 ISO 字符串时出现 T、毫秒和时区尾缀。
 */
export function formatDashboardDateTime(value, fallback = '-') {
  if (value === null || value === undefined || value === '') return fallback
  const textValue = typeof value === 'string' ? value.trim() : value
  const dateOnly = normalizeDashboardDateOnly(textValue)
  if (dateOnly) return `${dateOnly} 00:00:00`
  if (
    typeof textValue === 'string' &&
    /^\d{4}[-/.]\d{2}[-/.]\d{2}$/.test(textValue)
  )
    return fallback

  let date
  if (textValue instanceof Date) {
    date = textValue
  } else if (
    typeof textValue === 'number' ||
    (typeof textValue === 'string' && /^\d+$/.test(textValue))
  ) {
    const numeric = Number(textValue)
    date = new Date(String(textValue).length === 10 ? numeric * 1000 : numeric)
  } else {
    // Java LocalDateTime 可能带有超过三位的小数，Date 只接受毫秒精度。
    const normalized = String(textValue)
      .replace(/\[[^\]]+\]$/, '')
      .replace(/^(\d{4})[/.](\d{2})[/.](\d{2})/, '$1-$2-$3')
      .replace(/^(\d{4}-\d{2}-\d{2})[Tt ]+/, '$1T')
      .replace(/\s+([+-]\d{2}:?\d{2})$/, '$1')
      .replace(/\s+([Zz])$/, '$1')
      .replace(/([+-]\d{2})(\d{2})$/, '$1:$2')
      .replace(/z$/, 'Z')
      .replace(/(\.\d{3})\d+/, '$1')
    date = new Date(normalized)
    // 部分数据源返回 "YYYY-MM-DD HH:mm:ss"，将空格转为 ISO 分隔符，
    // 避免不同浏览器对非标准日期字符串解析结果不一致。
    if (
      Number.isNaN(date.getTime()) &&
      /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}/.test(normalized)
    ) {
      date = new Date(normalized.replace(' ', 'T'))
    }
  }

  if (!Number.isNaN(date.getTime())) {
    const pad = number => String(number).padStart(2, '0')
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  }

  return fallback
}

export function formatDashboardDate(value, fallback = '-') {
  if (value === null || value === undefined || value === '') return fallback
  const text = String(value).trim()
  const formatted = formatDashboardDateTime(text, '')
  return formatted ? formatted.slice(0, 10) : fallback
}

const DASHBOARD_DATE_TYPES = new Set(['DATE', 'LOCALDATE'])
const DASHBOARD_DATETIME_TYPES = new Set([
  'DATETIME',
  'LOCALDATETIME',
  'TIMESTAMP',
  'INSTANT',
  'OFFSETDATETIME',
  'ZONEDDATETIME',
])
const DASHBOARD_DATE_ONLY_PATTERN = /^\d{4}[-/.]\d{2}[-/.]\d{2}$/
const DASHBOARD_DATETIME_PATTERN =
  /^\d{4}[-/.]\d{2}[-/.]\d{2}[Tt ]\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?(?:\s?(?:[Zz]|[+-]\d{2}:?\d{2}))?(?:\[[^\]]+\])?$/

function normalizedDashboardFieldType(field) {
  const rawType =
    typeof field === 'string'
      ? field
      : field?.type || field?.dataType || field?.fieldType || ''
  return String(rawType)
    .trim()
    .toUpperCase()
    .replace(/[\s_-]+/g, '')
}

function isDashboardDateTimeType(type) {
  return (
    DASHBOARD_DATETIME_TYPES.has(type) ||
    type.includes('DATETIME') ||
    type.includes('TIMESTAMP') ||
    type.endsWith('INSTANT')
  )
}

function isDashboardTemporalField(field) {
  const name = String(field?.name || '')
    .trim()
    .toLowerCase()
  return /(?:date|time|timestamp|datetime|at)$/.test(name)
}

function isDashboardEpochValue(value) {
  const text = String(value)
  if (!/^\d{10}(?:\d{3})?$/.test(text)) return false
  const numeric = Number(value)
  const millis = text.length === 10 ? numeric * 1000 : numeric
  return millis >= 946684800000 && millis <= 4102444800000
}

/**
 * 按数据集字段定义格式化单元格值，同时对未声明类型但符合常见日期格式的
 * 字符串做兜底处理；字段名带有时间语义且值为合理 Unix 时间戳时也会处理。
 * 非日期值保持原样，调用方可继续决定是否转成字符串。
 */
export function formatDashboardFieldValue(value, field = {}, fallback = '-') {
  if (value === null || value === undefined || value === '') return fallback

  const type = normalizedDashboardFieldType(field)
  const text = typeof value === 'string' ? value.trim() : ''
  const dateType = DASHBOARD_DATE_TYPES.has(type)
  const datetimeType = isDashboardDateTimeType(type)
  const temporalField = isDashboardTemporalField(field)
  const dateOnly = DASHBOARD_DATE_ONLY_PATTERN.test(text)
  const datetime = DASHBOARD_DATETIME_PATTERN.test(text)

  if (dateType || dateOnly) {
    return formatDashboardDate(value, fallback) || value
  }
  if (
    datetimeType ||
    datetime ||
    value instanceof Date ||
    (temporalField && isDashboardEpochValue(value))
  ) {
    return formatDashboardDateTime(value, fallback) || value
  }
  return value
}

/**
 * 将大屏配置中保存的平台资源路径转换为当前环境可访问的地址。
 * 数据库存储稳定的 /profile/、/dashboard/assets/ 或 /static/ 路径；浏览器
 * 访问时统一经 VITE_APP_BASE_API 进入后端，避免开发环境命中 Vite 的 HTML
 * 回退。/static/ 用于随应用打包的内置演示资源。
 */
export function dashboardResourceUrl(value) {
  const path = String(value || '').trim()
  if (!path) return ''
  if (!path.startsWith('/profile/') && !path.startsWith('/dashboard/assets/') && !path.startsWith('/static/')) return ''
  const basePath = String(import.meta.env.VITE_APP_BASE_API || '/dev-api').replace(/\/$/, '')
  return `${basePath}${path}`
}

/**
 * 将页面背景显示方式转换为浏览器背景布局属性。
 * 对应常见桌面背景的填充、适应、拉伸、平铺和居中五种模式。
 */
export function dashboardBackgroundLayout(background = {}) {
  const mode = ['fill', 'fit', 'stretch', 'tile', 'center'].includes(background.mode) ? background.mode : 'fill'
  const position = background.position || 'center'
  if (mode === 'fit') return { backgroundSize: 'contain', backgroundPosition: position, backgroundRepeat: 'no-repeat' }
  if (mode === 'stretch') return { backgroundSize: '100% 100%', backgroundPosition: 'center', backgroundRepeat: 'no-repeat' }
  if (mode === 'tile') return { backgroundSize: 'auto', backgroundPosition: 'left top', backgroundRepeat: 'repeat' }
  if (mode === 'center') return { backgroundSize: 'auto', backgroundPosition: position, backgroundRepeat: 'no-repeat' }
  return { backgroundSize: 'cover', backgroundPosition: position, backgroundRepeat: 'no-repeat' }
}
