const alignmentCommands = new Set(['left', 'center', 'right', 'top', 'middle', 'bottom'])
const distributionCommands = new Set(['distribute-x', 'distribute-y'])

function layoutUnits(widgets) {
  if (!Array.isArray(widgets)) return null
  const units = [], groups = new Map(), ids = new Set()
  for (const widget of widgets) {
    const layout = widget?.layout
    if (!widget || widget.id == null || widget.id === '' || ids.has(widget.id)
      || !layout || ![layout.x, layout.y, layout.w, layout.h].every(Number.isFinite)
      || layout.w <= 0 || layout.h <= 0
      || !Number.isFinite(layout.x + layout.w) || !Number.isFinite(layout.y + layout.h)) return null
    ids.add(widget.id)
    const grouped = widget.groupId != null && widget.groupId !== ''
    let unit = grouped ? groups.get(widget.groupId) : null
    if (!unit) {
      unit = { widgets: [], left: Infinity, top: Infinity, right: -Infinity, bottom: -Infinity }
      units.push(unit)
      if (grouped) groups.set(widget.groupId, unit)
    }
    unit.widgets.push(widget)
    unit.left = Math.min(unit.left, layout.x)
    unit.top = Math.min(unit.top, layout.y)
    unit.right = Math.max(unit.right, layout.x + layout.w)
    unit.bottom = Math.max(unit.bottom, layout.y + layout.h)
  }
  for (const unit of units) {
    unit.width = unit.right - unit.left
    unit.height = unit.bottom - unit.top
    if (![unit.width, unit.height].every(Number.isFinite)) return null
  }
  return units
}

/** 组合整体算一个布局单位，供设计器判断对齐和分布是否可用。 */
export function dashboardLayoutUnitCount(widgets) {
  return layoutUnits(widgets)?.length || 0
}

function clampTranslation(delta, start, end, canvasSize) {
  const minimum = Math.ceil(-start)
  const maximum = Math.floor(canvasSize - end)
  // 没有能完整容纳选择的整数坐标时，整体贴近原点，禁止逐组件限位。
  if (minimum > maximum) return Math.round(-start)
  return Math.max(minimum, Math.min(maximum, Math.round(delta)))
}

/** 拖动和方向键共用的整体平移，所有选中成员使用同一个整数位移。 */
export function translateDashboardWidgets(widgets, dx, dy, canvas) {
  if (![dx, dy].every(Number.isFinite) || (Math.round(dx) === 0 && Math.round(dy) === 0)) return []
  if (!canvas || ![canvas.width, canvas.height].every(value => Number.isFinite(value) && value > 0)) return []
  const units = layoutUnits(widgets)
  if (!units?.length || widgets.some(widget => widget.state?.locked === true)) return []
  const left = Math.min(...units.map(unit => unit.left)), right = Math.max(...units.map(unit => unit.right))
  const top = Math.min(...units.map(unit => unit.top)), bottom = Math.max(...units.map(unit => unit.bottom))
  if (![right - left, bottom - top].every(Number.isFinite)) return []
  const moveX = clampTranslation(dx, left, right, canvas.width)
  const moveY = clampTranslation(dy, top, bottom, canvas.height)
  if (moveX === 0 && moveY === 0) return []
  const changes = widgets.map(widget => ({ id: widget.id, x: widget.layout.x + moveX, y: widget.layout.y + moveY }))
  return changes.every(change => Number.isFinite(change.x) && Number.isFinite(change.y)) ? changes : []
}

function clampUnitPosition(position, size, canvasSize) {
  // 超出画布大小的组合贴近零边缘，仍保持所有成员的相对位置。
  return Math.max(0, Math.min(Math.max(0, canvasSize - size), position))
}

/** 仅生成坐标变更；不修改原组件、尺寸、层级、旋转或组合关系。 */
export function arrangeDashboardWidgets(widgets, command, canvas) {
  const distribute = distributionCommands.has(command)
  if (!distribute && !alignmentCommands.has(command)) return []
  if (!canvas || ![canvas.width, canvas.height].every(value => Number.isFinite(value) && value > 0)) return []
  const units = layoutUnits(widgets)
  if (!units || units.length < (distribute ? 3 : 2) || widgets.some(widget => widget.state?.locked === true)) return []

  const horizontal = ['left', 'center', 'right', 'distribute-x'].includes(command)
  const origin = horizontal ? 'left' : 'top'
  const extent = horizontal ? 'right' : 'bottom'
  const size = horizontal ? 'width' : 'height'
  const targets = new Map()

  if (distribute) {
    const sorted = [...units].sort((a, b) => a[origin] - b[origin])
    const first = sorted[0], last = sorted[sorted.length - 1]
    const totalSize = sorted.reduce((total, unit) => total + unit[size], 0)
    const gap = (last[extent] - first[origin] - totalSize) / (sorted.length - 1)
    if (!Number.isFinite(gap)) return []
    let cursor = first[origin]
    sorted.forEach((unit, index) => {
      // 固定两端；只对中间项取整，避免反复操作产生累计位移。
      targets.set(unit, index === 0 || index === sorted.length - 1 ? unit[origin] : Math.round(cursor))
      cursor += unit[size] + gap
    })
  } else {
    const start = Math.min(...units.map(unit => unit[origin]))
    const end = Math.max(...units.map(unit => unit[extent]))
    if (!Number.isFinite(end - start)) return []
    for (const unit of units) {
      let target = start
      if (command === 'right' || command === 'bottom') target = end - unit[size]
      if (command === 'center' || command === 'middle') target = Math.round(start + (end - start - unit[size]) / 2)
      targets.set(unit, target)
    }
  }

  const changes = new Map()
  for (const unit of units) {
    const target = targets.get(unit)
    if (!Number.isFinite(target)) return []
    const x = clampUnitPosition(horizontal ? target : unit.left, unit.width, canvas.width)
    const y = clampUnitPosition(horizontal ? unit.top : target, unit.height, canvas.height)
    const dx = x - unit.left, dy = y - unit.top
    if (!Number.isFinite(dx) || !Number.isFinite(dy)) return []
    if (dx === 0 && dy === 0) continue
    for (const widget of unit.widgets) {
      const nextX = widget.layout.x + dx, nextY = widget.layout.y + dy
      if (!Number.isFinite(nextX) || !Number.isFinite(nextY)) return []
      changes.set(widget.id, { id: widget.id, x: nextX, y: nextY })
    }
  }
  return widgets.flatMap(widget => changes.has(widget.id) ? [changes.get(widget.id)] : [])
}
