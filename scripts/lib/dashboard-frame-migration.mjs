/**
 * Convert the retired frame decorations to the existing title-image settings.
 * Image references must be registered by the caller before saving the schema.
 * This migration never adds, removes, renames or regroups designer widgets.
 */
const LEGACY_VARIANTS = new Set(['tech-panel', 'tech-header'])
const numeric = (value, fallback) => {
  const parsed = value === '' || value == null ? NaN : Number(value)
  return Number.isFinite(parsed) ? parsed : fallback
}

export function legacyFrameTitleRequest(widget) {
  const style = widget?.style || {}
  if (widget?.type !== 'border' || !LEGACY_VARIANTS.has(style.frameVariant)) return null
  // A user-selected title image takes precedence over the retired decoration.
  if (style.titleImageEnabled === true && style.titleImageRef) return null
  const header = style.frameVariant === 'tech-header'
  const height = header ? numeric(widget.layout?.h, 106) : numeric(style.frameTitleHeight, 38)
  if (!header && height <= 0) return null
  return {
    kind: header ? 'header' : 'panel',
    titleImageHeight: Math.max(20, Math.min(120, height)),
  }
}

/**
 * assets accepts { header: '/profile/...', panel: '/profile/...' }, or a resolver
 * (widget, request) => ({ titleImageRef, titleImageHeight }) for per-size slices.
 * Unrelated schema properties and all widget identities/data remain untouched.
 */
export function migrateLegacyDashboardFrames(schema, assets = {}) {
  let changed = false
  const widgets = (schema.widgets || []).map(widget => {
    const originalStyle = widget.style || {}
    if (widget.type !== 'border' || !Object.keys(originalStyle).some(key => key.startsWith('frame'))) return widget
    const style = Object.fromEntries(Object.entries(originalStyle).filter(([key]) => !key.startsWith('frame')))
    const request = legacyFrameTitleRequest(widget)
    if (request) {
      const resolved = typeof assets === 'function' ? assets(widget, request) : { titleImageRef: assets[request.kind] }
      const imageRef = resolved?.titleImageRef
      if (typeof imageRef !== 'string' || !/^\/(?:profile|dashboard\/assets)\//.test(imageRef)) {
        throw new Error(`边框“${widget.name || widget.id}”的标题栏切图尚未登记为平台图片资源`)
      }
      const height = numeric(resolved.titleImageHeight, request.titleImageHeight)
      if (height < 20 || height > 120) throw new Error(`边框“${widget.name || widget.id}”的标题栏高度必须在 20-120 范围内`)
      Object.assign(style, {
        titleImageEnabled: true,
        titleImageRef: imageRef,
        titleImageHeight: height,
        titleImageFit: 'stretch',
        titleImageAlign: 'left',
        titleVisible: true,
      })
      // Existing reference pages use independent text components for titles.
      // Do not accidentally reveal the old hidden default title or subtitle.
      if (originalStyle.titleVisible === false) {
        style.title = ''
        style.subtitle = ''
      }
      if (request.kind === 'header') {
        // The complete header image contains its own silhouette and fill.
        style.backgroundTransparent = true
        style.borderTransparent = true
        style.borderWidth = 0
      }
    }
    changed = true
    return { ...widget, style }
  })
  return changed ? { ...schema, widgets } : schema
}
