export function dashboardTableScrollMetrics({ contentHeight, viewportHeight, rowCount, secondsPerRow } = {}) {
  const distance = Number(contentHeight)
  const viewport = Number(viewportHeight)
  const count = Number(rowCount)
  const seconds = Number(secondsPerRow)
  const duration = Math.max(1, Math.min(60, Number.isFinite(seconds) && seconds > 0 ? seconds : 5)) * Math.max(0, Number.isFinite(count) ? count : 0) * 1000
  return {
    overflow: Number.isFinite(distance) && Number.isFinite(viewport) && viewport > 0 && Number.isFinite(count) && count > 0 && distance > viewport + 0.5,
    distance: Number.isFinite(distance) ? Math.max(0, distance) : 0,
    duration,
  }
}
