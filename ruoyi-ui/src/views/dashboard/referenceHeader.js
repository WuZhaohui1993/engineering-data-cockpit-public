/** 精确迁移六页参考页头；不按 y 坐标批量删除组件，避免误伤指标和用户新增内容。 */
const PAGE_KEYS = ['overview', 'progress', 'quality', 'safety', 'hse', 'smart']
const HEADER_ROLES = ['frame', 'title', 'subtitle', 'clock', 'demo-frame', 'demo-label', ...PAGE_KEYS.map(key => `nav-${key}`), 'divider']
const clone = value => JSON.parse(JSON.stringify(value))

function headerRole(widget) {
  if (!widget.layout || widget.layout.y >= 180) return null
  const { type, name, layout, style = {}, interaction = {} } = widget
  if (type === 'border' && name === '顶栏') return 'frame'
  if (type === 'text' && (name === '工程项目综合管控中心' || name?.endsWith('异地搬迁升级改造项目'))) return 'title'
  if (type === 'text' && name === '工程数字化管控平台') return 'subtitle'
  if (type === 'text' && /^(?:▣\s*)?\d{4}-\d{2}-\d{2}(?: \d{2}:\d{2}:\d{2})?$/.test(style.text || name)) return 'clock'
  if (type === 'border' && name === '演示数据标识') return 'demo-frame'
  if (type === 'text' && name === '演示数据') return 'demo-label'
  const page = String(interaction.targetPageCode || '').replace(/^xinghua-/, '')
  if (type === 'button' && name?.startsWith('导航·') && PAGE_KEYS.includes(page)) return `nav-${page}`
  if (type === 'decoration' && name === '装饰分割线' && layout.x === 0 && layout.w === 1920 && layout.h === 2) return 'divider'
  return null
}

export function referenceHeaderWidgets(schema) {
  const roles = new Map()
  for (const widget of schema.widgets || []) {
    const role = headerRole(widget)
    if (!role) continue
    if (roles.has(role)) throw new Error(`页头组件重复：${role}`)
    roles.set(role, widget)
  }
  return HEADER_ROLES.map(role => {
    if (!roles.has(role)) throw new Error(`页头组件缺失：${role}`)
    return roles.get(role)
  })
}

/** 质量/HSE 原指标行侵入统一页头，仅调整这两页原指标框及内部图标、数值的纵向布局。 */
export function fitReferenceHeaderKpis(schema, key) {
  if (!['quality', 'hse'].includes(key)) return schema
  const replacements = new Map()
  const contains = (frame, item) => item.layout.x >= frame.layout.x && item.layout.x < frame.layout.x + frame.layout.w && item.layout.y >= frame.layout.y && item.layout.y < frame.layout.y + frame.layout.h
  const values = schema.widgets.filter(widget => widget.type === 'statistics' && widget.binding?.datasetCode === `xinghua-${key}-kpis` && widget.layout.y < 180)
  for (const value of values) {
    const frame = schema.widgets.find(widget => widget.type === 'border' && widget.name === value.name && widget.layout.y < 112 && widget.layout.h <= 110 && contains(widget, value))
    if (!frame) continue
    // 保留原指标行底边；HSE 至少留 72px，数值和图标仍以原尺寸显示。
    const y = 112
    const h = Math.max(72, frame.layout.y + frame.layout.h - y)
    replacements.set(frame.id, { ...frame, layout: { ...frame.layout, y, h } })
    replacements.set(value.id, { ...value, layout: { ...value.layout, y: y + 3, h: h - 6 } })
    for (const icon of schema.widgets.filter(widget => widget.type === 'icon' && contains(frame, widget))) {
      replacements.set(icon.id, { ...icon, layout: { ...icon.layout, y: y + (h - icon.layout.h) / 2 } })
    }
  }
  return { ...schema, widgets: schema.widgets.map(widget => replacements.get(widget.id) || widget) }
}

/**
 * baselineSchema 传入系统已发布的进度页，避免用模板覆盖系统业务配置。
 * 保留目标组件 ID 和其余 schema 内容，只复制 13 个页头独立组件及调整当前导航高亮。
 */
export function applyReferenceHeader(schema, key, baselineSchema) {
  if (!PAGE_KEYS.includes(key)) throw new Error(`未知参考页面：${key}`)
  const baseline = referenceHeaderWidgets(baselineSchema)
  const target = referenceHeaderWidgets(schema)
  const active = baseline[HEADER_ROLES.indexOf('nav-progress')].style
  const inactive = baseline[HEADER_ROLES.indexOf('nav-overview')].style
  const replacements = new Map(baseline.map((widget, index) => {
    const replacement = { ...clone(widget), id: target[index].id }
    const role = HEADER_ROLES[index]
    if (role.startsWith('nav-')) {
      const palette = role === `nav-${key}` ? active : inactive
      for (const field of ['color', 'backgroundColor', 'borderColor']) replacement.style[field] = palette[field]
    }
    return [replacement.id, replacement]
  }))
  return fitReferenceHeaderKpis({ ...schema, widgets: schema.widgets.map(widget => replacements.get(widget.id) || widget) }, key)
}
