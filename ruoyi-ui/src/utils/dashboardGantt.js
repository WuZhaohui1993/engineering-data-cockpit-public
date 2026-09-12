const DAY = 86400000

export function ganttDay(value) {
  const match = String(value || '').match(/^(\d{4})-(\d{2})-(\d{2})(?:$|T| )/)
  if (!match) return null
  const [, y, m, d] = match.map(Number)
  const date = new Date(Date.UTC(y, m - 1, d))
  return date.getUTCFullYear() === y && date.getUTCMonth() === m - 1 && date.getUTCDate() === d ? date.getTime() / DAY : null
}

function ganttProgress(value) {
  if ((typeof value !== 'number' && typeof value !== 'string') || (typeof value === 'string' && !value.trim())) return null
  const progress = Number(value)
  return Number.isFinite(progress) && progress >= 0 && progress <= 100 ? progress : null
}

export function buildGanttModel(rows = [], fieldMap = {}, style = {}, width = 960, height = 360) {
  const field = (row, role, fallback = role) => row[fieldMap[role] || fallback]
  const tasks = rows.slice(0, 100).map((row, i) => ({
    id: String(field(row, 'id') || i + 1), name: String(field(row, 'task', 'name') || `任务 ${i + 1}`), row,
    start: ganttDay(field(row, 'start', 'plannedStart')), end: ganttDay(field(row, 'end', 'plannedEnd')),
    actualStart: ganttDay(field(row, 'actualStart')), actualEnd: ganttDay(field(row, 'actualEnd')),
    progress: ganttProgress(field(row, 'progress')), status: String(field(row, 'status') ?? '').trim(),
    dependencies: String(field(row, 'dependencies') || '').split(',').map(x => x.trim()).filter(Boolean),
  }))
  const dates = tasks.flatMap(t => [t.start, t.end, t.actualStart, t.actualEnd]).filter(x => x !== null)
  const start = ganttDay(style.ganttStart) ?? (dates.length ? Math.min(...dates) : ganttDay('2026-04-01'))
  const candidateEnd = ganttDay(style.ganttEnd) ?? (dates.length ? Math.max(...dates) : start + 275)
  const end = Math.min(start + 3660, Math.max(start + 1, candidateEnd))
  const labelWidth = Math.min(Math.max(40, width - 40), Math.max(80, Math.min(400, Number(style.ganttLabelWidth) || 140)))
  const top = 35, bottom = Math.max(top + 24, height - 30), plotWidth = Math.max(1, width - labelWidth - 8)
  const x = day => labelWidth + Math.min(1, Math.max(0, (day - start) / (end - start))) * plotWidth
  const bar = (a, b) => a === null || b === null || b < a || b < start || a > end ? null : { x: x(a), w: Math.max(2, x(b) - x(a)) }
  const rowHeight = (bottom - top) / Math.max(1, tasks.length)
  const plotted = tasks.map((task, i) => ({ ...task, y: top + i * rowHeight, rowHeight, plan: bar(task.start, task.end), actual: bar(task.actualStart, task.actualEnd) }))
  const months = []
  const cursor = new Date(start * DAY)
  cursor.setUTCDate(1)
  for (let i = 0; i < 122 && cursor.getTime() / DAY <= end; i++) {
    const day = cursor.getTime() / DAY
    if (day >= start) months.push({ x: x(day), label: `${cursor.getUTCMonth() + 1}月` })
    cursor.setUTCMonth(cursor.getUTCMonth() + 1)
  }
  const links = plotted.flatMap(to => to.dependencies.map(id => {
    const from = plotted.find(t => t.id === id)
    const a = from?.actual || from?.plan, b = to.actual || to.plan
    if (!a || !b || from === to) return null
    const x1 = a.x + a.w, y1 = from.y + rowHeight * .68, x2 = b.x, y2 = to.y + rowHeight * .68
    const turn = Math.min(width - 3, Math.max(x1 + 12, x2 + 12))
    return { key: `${id}-${to.id}`, points: `${x1},${y1} ${turn},${y1} ${turn},${y2} ${x2},${y2}`, x: x2, y: y2 }
  }).filter(Boolean))
  const current = ganttDay(style.ganttCurrentDate)
  return { tasks: plotted, months, links, labelWidth, top, bottom, currentX: current !== null && current >= start && current <= end ? x(current) : null }
}
