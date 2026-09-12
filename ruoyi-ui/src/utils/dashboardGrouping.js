const resizeHandles = new Set(['nw', 'n', 'ne', 'e', 'se', 's', 'sw', 'w'])
const minimumWidgetSize = 40
const epsilon = 1e-7

function validCanvas(canvas) {
  return canvas && [canvas.width, canvas.height].every(value => Number.isFinite(value) && value > 0)
}

/** 画布坐标中的选择外接矩形；旋转和层级保持组件自己的设置。 */
export function getDashboardSelectionBounds(widgets) {
  if (!Array.isArray(widgets) || !widgets.length) return null
  const ids = new Set()
  let left = Infinity, top = Infinity, right = -Infinity, bottom = -Infinity
  for (const widget of widgets) {
    const layout = widget?.layout
    if (!widget || widget.id == null || widget.id === '' || ids.has(widget.id)
      || !layout || ![layout.x, layout.y, layout.w, layout.h].every(Number.isFinite)
      || layout.w <= 0 || layout.h <= 0
      || !Number.isFinite(layout.x + layout.w) || !Number.isFinite(layout.y + layout.h)) return null
    ids.add(widget.id)
    left = Math.min(left, layout.x)
    top = Math.min(top, layout.y)
    right = Math.max(right, layout.x + layout.w)
    bottom = Math.max(bottom, layout.y + layout.h)
  }
  const bounds = { x: left, y: top, w: right - left, h: bottom - top }
  return [bounds.w, bounds.h].every(value => Number.isFinite(value) && value > 0) ? bounds : null
}

function resizeAxis(start, size, delta, fromStart, fromEnd, limit, minimum, snapToGrid) {
  const end = start + size
  if (!fromStart && !fromEnd) {
    return start >= 0 && end <= limit && size + epsilon >= minimum ? { start, size } : null
  }
  const fixed = fromStart ? end : start
  const available = fromStart ? fixed : limit - fixed
  if (fixed < 0 || fixed > limit || minimum > available + epsilon) return null
  const step = snapToGrid ? 8 : 1
  // 只吸附正在拖动的外框边缘，避免对每个成员取整而改变内部间距。
  const proposed = (fromStart ? start : end) + delta
  const snapped = delta === 0 ? proposed : Math.round(proposed / step) * step
  const moving = fromStart
    ? Math.max(0, Math.min(fixed - minimum, snapped))
    : Math.max(fixed + minimum, Math.min(limit, snapped))
  return fromStart ? { start: moving, size: fixed - moving } : { start: fixed, size: moving - fixed }
}

function fitResizedAxis(position, size, limit) {
  const start = Math.max(0, Math.min(limit - minimumWidgetSize, position))
  // 共享缩放比的浮点运算可能让边缘多出机器精度；收紧尺寸避免后端严格边界校验拒绝。
  const length = Math.min(Math.max(minimumWidgetSize, size), limit - start)
  return { start, size: length }
}

function fitTranslatedPosition(position, size, limit, selectionSize) {
  // 整体位移已完成限位；此处仅消除成员坐标相加带来的浮点越界，不改变复制件尺寸。
  // 超大选择仍保持原始间距并贴近原点。
  return selectionSize <= limit
    ? Math.max(0, Math.min(limit - size, position))
    : Math.max(0, position)
}

/**
 * 从 pointerdown 保存的成员 layout 和外框计算绝对结果，禁止传入上次 pointermove 的结果作基线。
 * 同一轴共享缩放比；最小尺寸和画布边界优先于网格吸附。
 * 合法零位移仍返回所有成员，供拖回起点时恢复；无效输入或任一成员锁定时返回 []。
 */
export function resizeDashboardGroup(widgets, originalBounds, handle, dx, dy, canvas) {
  const bounds = getDashboardSelectionBounds(widgets)
  if (!bounds || !validCanvas(canvas) || !resizeHandles.has(handle) || ![dx, dy].every(Number.isFinite)
    || widgets.some(widget => widget.state?.locked === true)
    || !originalBounds || !['x', 'y', 'w', 'h'].every(key => Number.isFinite(originalBounds[key])
      && Math.abs(originalBounds[key] - bounds[key]) < epsilon)) return []

  const minimumWidth = bounds.w * Math.max(...widgets.map(widget => minimumWidgetSize / widget.layout.w))
  const minimumHeight = bounds.h * Math.max(...widgets.map(widget => minimumWidgetSize / widget.layout.h))
  const horizontal = resizeAxis(bounds.x, bounds.w, dx, handle.includes('w'), handle.includes('e'), canvas.width, minimumWidth, canvas.snapToGrid)
  const vertical = resizeAxis(bounds.y, bounds.h, dy, handle.includes('n'), handle.includes('s'), canvas.height, minimumHeight, canvas.snapToGrid)
  if (!horizontal || !vertical) return []
  const scaleX = horizontal.size / bounds.w, scaleY = vertical.size / bounds.h
  const changes = widgets.map(widget => {
    const x = fitResizedAxis(horizontal.start + (widget.layout.x - bounds.x) * scaleX, widget.layout.w * scaleX, canvas.width)
    const y = fitResizedAxis(vertical.start + (widget.layout.y - bounds.y) * scaleY, widget.layout.h * scaleY, canvas.height)
    return { id: widget.id, layout: { x: x.start, y: y.start, w: x.size, h: y.size } }
  })
  return changes.every(change => Object.values(change.layout).every(Number.isFinite)) ? changes : []
}

/** 复制/粘贴共用一个平移量，整体限位，保留成员之间的间距以及所有其他配置。 */
export function translateDashboardClones(widgets, offset, canvas) {
  const bounds = getDashboardSelectionBounds(widgets)
  if (!bounds || !Number.isFinite(offset) || !validCanvas(canvas)) return []
  const position = (start, size, limit) => Math.max(0, Math.min(Math.max(0, limit - size), start + Math.round(offset)))
  const dx = position(bounds.x, bounds.w, canvas.width) - bounds.x
  const dy = position(bounds.y, bounds.h, canvas.height) - bounds.y
  return widgets.map(widget => ({
    ...widget,
    layout: {
      ...widget.layout,
      x: fitTranslatedPosition(widget.layout.x + dx, widget.layout.w, canvas.width, bounds.w),
      y: fitTranslatedPosition(widget.layout.y + dy, widget.layout.h, canvas.height, bounds.h),
    },
  }))
}
