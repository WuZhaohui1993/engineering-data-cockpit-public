const transientQualities = new Set(['TIMEOUT', 'CONNECT_ERROR', 'RATE_LIMITED', 'SOURCE_ERROR'])

export function dashboardRefreshError(error) {
  const status = Number(error?.businessCode || error?.httpStatus || error?.response?.status || 0)
  const transport = error?.requestErrorKind === 'transport'
  const transient = transport && (!status || [408, 429, 502, 503, 504].includes(status))
  const timeout = ['ECONNABORTED', 'ETIMEDOUT'].includes(error?.code) || /timeout/i.test(error?.message || '')
  return {
    rows: [],
    quality: status === 401 ? 'AUTH_ERROR' : status === 403 ? 'FORBIDDEN'
      : transient ? timeout ? 'TIMEOUT' : status === 429 ? 'RATE_LIMITED' : 'CONNECT_ERROR' : 'INVALID_DATA',
    message: error?.message || '数据更新失败',
    transient,
  }
}

export function mergeDashboardRefreshResult(previous, result, sameRequest = false) {
  const next = result || { rows: [], quality: 'INVALID_DATA' }
  const trusted = ['SUCCESS', 'STALE'].includes(previous?.quality) && previous?.rows?.length > 0
  const mayRetain = next.transient === true || (next.transient !== false && transientQualities.has(next.quality))
  if (sameRequest && trusted && mayRetain) {
    return { ...previous, quality: 'STALE', stale: true, refreshError: next.message || '数据更新暂时失败', refreshErrorQuality: next.quality }
  }
  const state = { ...next }
  if (Array.isArray(previous?.rows) && JSON.stringify(previous.rows) === JSON.stringify(next.rows)) state.rows = previous.rows
  return state
}

export async function runDashboardRefreshBatch(items, work, concurrency = 6) {
  let cursor = 0
  const results = new Array(items.length)
  await Promise.all(Array.from({ length: Math.min(items.length, Math.max(1, concurrency)) }, async () => {
    while (cursor < items.length) {
      const index = cursor++
      results[index] = await work(items[index])
    }
  }))
  return results
}
