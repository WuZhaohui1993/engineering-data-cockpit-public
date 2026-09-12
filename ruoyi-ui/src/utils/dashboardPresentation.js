/**
 * Titles are user-facing text. Treat null, undefined and whitespace-only values
 * as empty while keeping numeric values such as 0 visible.
 */
export function dashboardTitleText(value) {
  return value === null || value === undefined ? '' : String(value).trim()
}

export function dashboardButtonUsesImage(style = {}) {
  return style.titleImageEnabled === true && Boolean(dashboardTitleText(style.titleImageRef))
}

/**
 * Whether the component has a visible title heading. Statistics without a
 * title image render their title inside the value block, so the outer heading
 * remains false for that type. `resolvedTitle` lets callers supply a mapped
 * statistics label without falling back to a component type name.
 */
export function dashboardWidgetTitleVisible(widget = {}, resolvedTitle) {
  const style = widget.style || {}
  if (widget.type === 'button' || style.titleVisible === false) return false
  const hasImage = style.titleImageEnabled === true && Boolean(dashboardTitleText(style.titleImageRef))
  if (widget.type === 'statistics') return hasImage
  const title = resolvedTitle === undefined ? style.title : resolvedTitle
  return Boolean(dashboardTitleText(title) || dashboardTitleText(style.subtitle) || hasImage)
}

// 旧统计始终展示正文标题，titleVisible 只控制额外标题栏。迁移后它控制唯一标题。
export function normalizeDashboardStatisticsStyle(type, style = {}) {
  if (type !== 'statistics' || Number(style.statsTitleVersion) >= 1) return style
  return {
    ...style,
    titleVisible: true,
    titleImageEnabled: style.titleVisible === false ? false : style.titleImageEnabled === true,
    statsTitleVersion: 1,
  }
}

export function dashboardStatisticsBodyTitleVisible(widget = {}, resolvedTitle) {
  const title = resolvedTitle === undefined ? widget.style?.title : resolvedTitle
  return widget.type === 'statistics' && widget.style?.titleVisible !== false && !dashboardWidgetTitleVisible(widget) && Boolean(dashboardTitleText(title))
}

// 显式映射严格读取所选字段；缺失时由调用方使用手工配置，不能误取首字段。
export function dashboardStatisticsField(widget = {}, row = {}, role) {
  const mappedKey = widget.binding?.fieldMap?.[role]
  if (mappedKey) return { key: mappedKey, value: row[mappedKey] }
  if (role === 'label') return { key: '', value: widget.style?.title }
  if (role === 'suffix') return { key: '', value: widget.style?.unit }
  const aliases = {
    value: ['value', 'total'],
    compareValue: ['compareValue', 'compare', 'rate', 'ratio'],
    compareLabel: ['compareLabel'],
    compareState: ['compareState', 'trend'],
  }
  const key = (aliases[role] || []).find(name => Object.prototype.hasOwnProperty.call(row, name)) || ''
  return { key, value: key ? row[key] : undefined }
}

export function dashboardButtonLabel(style = {}) {
  if (dashboardButtonUsesImage(style) && typeof style.text === 'string') return style.text
  return dashboardTitleText(style.text) || dashboardTitleText(style.title)
}

export function dashboardButtonStyle(style = {}, imageUrl = '') {
  const image = dashboardButtonUsesImage(style) && Boolean(imageUrl)
  return {
    ...(style.buttonFill || image ? { width: '100%', height: '100%', display: 'flex', alignItems: 'var(--widget-content-align-y, center)', justifyContent: 'var(--widget-content-justify, center)', boxSizing: 'border-box' } : {}),
    color: 'var(--accent)',
    borderColor: 'var(--widget-border-color)',
    background: image || style.backgroundTransparent === true || style.embeddedMode === true ? 'transparent' : style.backgroundColor,
    borderRadius: `${style.borderRadius ?? 0}px`, fontWeight: style.fontWeight || 400,
    clipPath: !image && style.buttonShape === 'angled' ? 'polygon(6% 0,94% 0,100% 100%,0 100%)' : undefined,
    boxShadow: !image && style.buttonShape === 'angled' && !style.embeddedMode ? 'inset 0 -2px 6px #0796db66' : undefined,
    ...(image ? {
      borderRadius: '0px',
      border: '0 solid transparent',
      backgroundImage: `url(${JSON.stringify(imageUrl)})`,
      backgroundSize: style.titleImageFit === 'contain' ? 'contain' : style.titleImageFit === 'cover' ? 'cover' : '100% 100%',
      backgroundPosition: style.titleImageFit === 'contain' ? `${['left', 'center', 'right'].includes(style.titleImageAlign) ? style.titleImageAlign : 'center'} center` : 'center',
      backgroundRepeat: 'no-repeat',
    } : {}),
  }
}

export function dashboardRingTextStyle(style = {}) {
  const speed = Math.max(0, Math.min(120, Number(style.ringTextSpeed ?? 20) || 0))
  const radius = Math.max(10, Math.min(48, Number(style.ringTextRadius) || 42))
  return {
    '--ring-inset': `${50 - radius}%`,
    '--ring-duration': `${Math.max(1, 120 - speed)}s`,
    '--ring-direction': style.ringTextDirection === 'reverse' ? 'reverse' : 'normal',
    '--ring-play-state': speed === 0 ? 'paused' : 'running',
    '--ring-tilt': `${Math.max(-45, Math.min(45, Number(style.ringTextTilt) || 0))}deg`,
  }
}

export function dashboardRingTextItemStyle(index, count) {
  const radians = (2 * Math.PI * index) / Math.max(1, count) - Math.PI / 2
  return { left: `${50 + 50 * Math.cos(radians)}%`, top: `${50 + 50 * Math.sin(radians)}%` }
}

export function dashboardHeadingHeight(style = {}, image = false) {
  if (image) return Math.max(20, Math.min(120, Number(style.titleImageHeight) || 32))
  const padding = key => Math.max(0, Math.min(120, Number(style[key]) || 0))
  const title = (Number(style.titleFontSize) || 12) * 1.25
  const subtitle = dashboardTitleText(style.subtitle) ? (Number(style.subtitleFontSize) || 10) * 1.25 + 2 : 0
  return Math.max(25, Math.ceil(title + subtitle + padding('titlePaddingTop') + padding('titlePaddingBottom')))
}

export function dashboardFormOptions(field = {}) {
  let raw = field.options
  if (typeof raw === 'string') {
    try { raw = JSON.parse(raw) } catch { return [] }
  }
  if (!Array.isArray(raw)) return []
  return raw.filter(item => item !== null && item !== undefined).map((item, index) => {
    if (typeof item === 'object') return {
      label: String(item.label ?? item.name ?? item.value ?? `选项 ${index + 1}`),
      value: String(item.value ?? item.name ?? ''),
    }
    return { label: String(item), value: String(item) }
  })
}

export function dashboardFormInputType(field = {}) {
  return { NUMBER: 'number', DATE: 'date', DATETIME: 'datetime-local' }[String(field.type || '').toUpperCase()] || 'text'
}

export function dashboardCarouselRows(rows = [], limit = 3, offset = 0) {
  const count = Math.min(rows.length, Math.max(1, Math.min(1000, Number(limit) || 3)))
  if (!count) return []
  const start = ((Number(offset) || 0) % rows.length + rows.length) % rows.length
  return Array.from({ length: count }, (_, index) => rows[(start + index) % rows.length])
}

export function dashboardColorBlockValue(value, style = {}) {
  const text = value == null ? '' : String(value).trim();
  return /^#(?:[0-9a-f]{3}|[0-9a-f]{4}|[0-9a-f]{6}|[0-9a-f]{8})$/i.test(text) || /^rgba?\([0-9 .,%-]+\)$/i.test(text)
    ? text : style.blockColor || style.color || '#35d4b0';
}

// 资源就绪后落实自动播放配置；浏览器拒绝时保留原生播放器供用户操作。
export async function dashboardVideoAutoplay(event, style = {}) {
  const video = event?.target;
  if (style.autoplay !== true || typeof video?.play !== 'function') return false;
  video.muted = true;
  try { await video.play(); return true; } catch { return false; }
}
