/**
 * One-off export of the retired DashboardFrame decorations into ordinary image
 * resources. This module is intentionally outside the application renderer:
 * saved pages consume titleImageRef / imageRef, not a second frame appearance API.
 */
import { createHash } from 'node:crypto'

const number = (value, fallback, min, max) => {
  const parsed = value === '' || value == null ? NaN : Number(value)
  return Math.min(max, Math.max(min, Number.isFinite(parsed) ? parsed : fallback))
}
const escape = value => String(value).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;' }[c]))

/** The resolved values also form a stable deduplication key for registered assets. */
export function legacyFrameAssetSpec(widget) {
  if (widget?.type !== 'border' || !['tech-panel', 'tech-header'].includes(widget.style?.frameVariant)) return null
  const style = widget.style
  const width = number(widget.layout?.w, 400, 1, 10000)
  const height = number(widget.layout?.h, 240, 1, 10000)
  return {
    variant: style.frameVariant,
    width,
    height,
    lineColor: style.frameLineColor || style.borderColor || '#156397',
    accentColor: style.frameAccentColor || '#24c7ff',
    backgroundColor: style.frameBackgroundColor || style.backgroundColor || '#02172e',
    backgroundEnd: style.frameBackgroundEnd || '#001023',
    fillOpacity: style.backgroundTransparent ? 0 : number(style.frameFillOpacity, 0.95, 0, 1),
    lineWidth: number(style.frameLineWidth, 1, 0.3, 4),
    titleHeight: number(style.frameTitleHeight, 38, 0, Math.max(0, height - 4)),
    titleWidth: number(style.frameTitleWidth, 200, 20, width),
    headerWidth: number(style.frameHeaderWidth, width * 0.39, width * 0.28, width * 0.72),
  }
}

/** Export any vertical slice at its original scale; no browser / database side effects. */
export function renderLegacyFrameSvg(spec, { top = 0, height = spec.height } = {}) {
  if (!spec || !['tech-panel', 'tech-header'].includes(spec.variant)) throw new Error('无效的历史边框资源描述')
  if (!(height > 0) || top < 0 || top + height > spec.height) throw new Error('切图范围超出边框尺寸')
  const s = spec, w = s.width, h = s.height
  const path = (d, attributes) => `<path d="${escape(d)}" ${attributes}/>`
  const color = key => escape(s[key])
  const defs = `<defs>
    <linearGradient id="body" x1="0" y1="0" x2="0.65" y2="1"><stop offset="0" stop-color="${color('backgroundColor')}" stop-opacity="${s.fillOpacity}"/><stop offset="1" stop-color="${color('backgroundEnd')}" stop-opacity="${s.fillOpacity}"/></linearGradient>
    <linearGradient id="title" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="${color('accentColor')}" stop-opacity="0.09"/><stop offset="0.48" stop-color="${color('lineColor')}" stop-opacity="0.04"/><stop offset="1" stop-color="${color('lineColor')}" stop-opacity="0"/></linearGradient>
    <linearGradient id="line" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="${color('lineColor')}" stop-opacity="0.2"/><stop offset="0.12" stop-color="${color('accentColor')}" stop-opacity="0.95"/><stop offset="0.5" stop-color="${color('lineColor')}" stop-opacity="0.6"/><stop offset="1" stop-color="${color('lineColor')}" stop-opacity="0.08"/></linearGradient>
    <linearGradient id="headerGlow" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${color('accentColor')}" stop-opacity="0"/><stop offset="0.65" stop-color="${color('lineColor')}" stop-opacity="0.02"/><stop offset="1" stop-color="${color('accentColor')}" stop-opacity="0.13"/></linearGradient>
  </defs>`
  const stroke = (key, width = s.lineWidth, opacity = 1) => `fill="none" stroke="${color(key)}" stroke-width="${width}" stroke-opacity="${opacity}"`
  const parts = []
  if (s.variant === 'tech-header') {
    const left = (w - s.headerWidth) / 2, right = w - left
    const slope = Math.min(w * 0.045, h * 0.86)
    const topY = Math.min(8, h * 0.08), bottom = h - Math.min(5, h * 0.06), shoulder = h * 0.45
    parts.push(path(`M1 ${topY} H${left - slope * 1.2} L${left} ${bottom} H${right} L${right + slope * 1.2} ${topY} H${w - 1} V${h - 1} H1 Z`, `fill="url(#body)" stroke="${color('lineColor')}" stroke-width="${s.lineWidth}" stroke-opacity="0.5"`))
    parts.push(path(`M${left - slope * 1.2} 0 H${right + slope * 1.2} L${right} ${bottom} H${left} Z`, 'fill="url(#headerGlow)"'))
    parts.push(path(`M1 ${shoulder} H${left - slope * 1.2} l${slope * 0.3} 0 L${left - slope * 0.63} ${shoulder + h * 0.08} H${left - slope * 0.45} L${left} ${bottom} H${right} L${right + slope * 0.45} ${shoulder + h * 0.08} H${right + slope * 0.63} L${right + slope * 0.9} ${shoulder} H${w - 1}`, stroke('accentColor', s.lineWidth + 0.45, 0.85)))
    parts.push(path(`M${left - slope * 1.08} ${topY + 3} L${left + 10} ${bottom - 7} H${right - 10} L${right + slope * 1.08} ${topY + 3}`, stroke('lineColor', s.lineWidth, 0.5)))
    parts.push(path(`M1 ${shoulder + 6} H${left - slope * 0.91 - 12} L${left - 9} ${h - 1} H${right + 9} L${right + slope * 0.91 + 12} ${shoulder + 6} H${w - 1}`, stroke('lineColor', s.lineWidth, 0.35)))
    parts.push(path(`M${left + 16} ${bottom - 3} h48 M${right - 64} ${bottom - 3} h48`, stroke('accentColor', s.lineWidth + 0.6, 0.45)))
    for (let side = 0; side < 2; side++) for (let i = 0; i < 5; i++) {
      const x = side ? right - 35 - i * 11 : left + 27 + i * 11
      parts.push(path(`M${x} ${bottom - 11} h5 l3 3 h-5 Z`, `fill="${color('accentColor')}" fill-opacity="${0.2 + (side * 5 + i) % 4 * 0.11}"`))
    }
  } else {
    const edge = Math.min(1, w / 8, h / 8), cut = Math.min(8, w / 7, h / 7), corner = Math.min(22, w / 5, h / 3)
    const titleEnd = Math.min(w * 0.66, s.titleWidth), titleY = s.titleHeight
    parts.push(path(`M${cut} ${edge} H${w - cut} L${w - edge} ${cut} V${h - cut} L${w - cut} ${h - edge} H${cut} L${edge} ${h - cut} V${cut} Z`, `fill="url(#body)" stroke="${color('lineColor')}" stroke-width="${s.lineWidth}" stroke-opacity="0.68"`))
    if (titleY > 0) {
      parts.push(path(`M${cut} ${edge + 1} H${w - cut} L${w - edge - 1} ${cut} V${titleY} H${edge + 1} V${cut} Z`, 'fill="url(#title)"'))
      parts.push(path(`M${cut} ${titleY} H${w - cut}`, `fill="none" stroke="url(#line)" stroke-width="${s.lineWidth}"`))
      parts.push(path(`M${cut} ${titleY} H${Math.min(titleEnd, 42)} M${Math.max(44, titleEnd - 30)} ${titleY} H${titleEnd} l5 -3 h18`, stroke('accentColor', s.lineWidth + 0.6, 0.7)))
    }
    parts.push(path(`M${edge} ${corner} V${cut} L${cut} ${edge} H${corner} M${w - corner} ${edge} H${w - cut} L${w - edge} ${cut} V${Math.min(corner, 14)} M${edge} ${h - corner} V${h - cut} L${cut} ${h - edge} H${Math.min(corner, 16)} M${w - corner} ${h - edge} H${w - cut} L${w - edge} ${h - cut} V${h - Math.min(corner, 15)}`, stroke('accentColor', s.lineWidth + 0.3, 0.86)))
    parts.push(path(`M5 ${Math.min(h - 5, corner + 6)} V${cut + 2} L${cut + 2} 5 H${corner + 12} M${w - 5} ${Math.max(5, h - corner - 6)} V${h - cut - 2} L${w - cut - 2} ${h - 5} H${w - corner - 12}`, stroke('lineColor', s.lineWidth, 0.34)))
    parts.push(path(`M${Math.max(corner + 20, w - 118)} 6 h42 l4 -3 h35 M${Math.max(corner + 25, w - 112)} 10 h27 l4 -3 h17`, stroke('lineColor', s.lineWidth, 0.45)))
  }
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${height}" viewBox="0 ${top} ${w} ${height}" preserveAspectRatio="none">${defs}${parts.join('')}</svg>`
}

export function legacyFrameAssetCode(spec, slice) {
  return `dashboard-title-${createHash('sha256').update(renderLegacyFrameSvg(spec, slice)).digest('hex').slice(0, 20)}`
}
