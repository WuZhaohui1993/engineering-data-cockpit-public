const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value)

// Partial option JSON is a style override. Keep sibling properties and generated
// series when the override only supplies a label, line style or the first series.
function mergeOptionObject(base, custom) {
  if (!isObject(custom)) return custom
  const result = { ...(isObject(base) ? base : {}) }
  for (const [key, value] of Object.entries(custom)) {
    if (['__proto__', 'prototype', 'constructor'].includes(key)) continue
    result[key] = isObject(value) ? mergeOptionObject(result[key], value) : value
  }
  return result
}

export function mergeDashboardChartOption(base, custom = {}, allowSeries = false) {
  const result = { ...base }
  if (!isObject(custom)) return normalizeRadarSeries(result)
  for (const key of ['title', 'legend', 'tooltip', 'grid', 'xAxis', 'yAxis', 'color', 'radar']) {
    if (!custom[key] || typeof custom[key] !== 'object') continue
    result[key] = Array.isArray(custom[key]) ? custom[key] : mergeOptionObject(base[key], custom[key])
  }
  if (allowSeries && Array.isArray(custom.series)) {
    const length = Math.max(base.series?.length || 0, custom.series.length)
    result.series = Array.from({ length }, (_, i) => mergeOptionObject(base.series?.[i], custom.series[i] || {}))
  }
  if (result.yAxis?.type === 'category' && result.xAxis?.type === 'value') {
    result.yAxis = { ...result.yAxis, data: custom.yAxis?.data ?? base.xAxis?.data ?? base.yAxis?.data ?? [] }
    result.xAxis = { ...result.xAxis }
    delete result.xAxis.data
    result.series = result.series?.map((series) => !series.markLine ? series : {
      ...series,
      markLine: { ...series.markLine, data: series.markLine.data?.map((line) => {
        if (line.yAxis === undefined) return line
        const { yAxis, ...rest } = line
        return { ...rest, xAxis: yAxis }
      }) },
    })
  }
  return normalizeRadarSeries(result)
}

// ECharts 5 radar layout requires at least one indicator before laying out a
// data item. Loading/empty datasets have no indicators, even with mapped series.
function normalizeRadarSeries(option) {
  const radars = Array.isArray(option.radar) ? option.radar : [option.radar]
  option.series = option.series?.map((series) => {
    if (series.type !== 'radar') return series
    const radar = radars[Number(series.radarIndex) || 0]
    return Array.isArray(radar?.indicator) && radar.indicator.length
      ? series
      : { ...series, data: [] }
  })
  return option
}

const cartesianTypes = new Set(['line-chart', 'area-chart', 'bar-chart', 'scatter', 'pictorial-chart', 'bar3d-chart'])
const legendTypes = new Set(['line-chart', 'area-chart', 'bar-chart', 'pie-chart', 'ring-chart', 'funnel', 'radar'])
const numberOr = (value, fallback) => value !== null && value !== '' && Number.isFinite(Number(value)) ? Number(value) : fallback

// Apply the inspector contract after type-specific series construction. Keeping
// this shared avoids the designer accepting properties that runtime then drops.
export function applyDashboardChartProperties(option, widget, context) {
  const config = widget.style?.chartConfig || {}
  const { labels = [], categoryValues = [], seriesNames = [], tooltip, label, legend } = context
  const type = widget.type
  option.tooltip = { ...tooltip, trigger: ['line-chart', 'area-chart', 'bar-chart', 'pictorial-chart', 'bar3d-chart'].includes(type) ? 'axis' : 'item' }
  if (legendTypes.has(type)) option.legend = legend
  const firstSeries = option.series?.[0]
  if (!firstSeries) return option

  if (type === 'ring-chart' && widget.style?.ringEqualSegments === true) {
    const values = context.values || []
    const format = config.label?.format || '{c}'
    firstSeries.label = { ...label, formatter: (params) => format.replace(/\{[abcd]\}/g, (token) => token === '{b}' ? String(params.name || '') : token === '{a}' ? String(seriesNames[0] || '') : token === '{d}' ? String(params.percent ?? '') : String(values[params.dataIndex] ?? '')) }
    option.tooltip = { ...option.tooltip, renderMode: 'richText', formatter: (params) => `${params.name}: ${values[params.dataIndex] ?? ''}` }
  }
  if (type === 'gauge') {
    const format = config.label?.format || '{c}'
    const suffix = `${widget.style?.unit || ''}${({ thousand: '千', 'ten-thousand': '万', million: '百万' })[config.valueScale] || ''}`
    firstSeries.detail = {
      ...firstSeries.detail, show: label.show, color: label.color,
      fontSize: label.fontSize, fontWeight: label.fontWeight,
      formatter: (value) => format.replace(/\{[abcd]\}/g, (token) => token === '{b}' ? String(labels[0] ?? '') : token === '{a}' ? String(seriesNames[0] ?? '') : String(value)) + (format === '{c}' ? suffix : ''),
    }
    firstSeries.data = firstSeries.data.map((item) => ({ ...item, name: labels[0] || '' }))
  } else if (type === 'progress') {
    firstSeries.name = seriesNames[0] || ''
    firstSeries.label = { ...label, position: 'right' }
    if (!config.label?.format || config.label.format === '{c}') {
      const suffix = `${widget.style?.unit || '%'}${({ thousand: '千', 'ten-thousand': '万', million: '百万' })[config.valueScale] || ''}`
      firstSeries.label.formatter = `{c}${suffix}`
    }
    option.yAxis.data = [labels[0] || '']
  } else if (type === 'treemap-chart') {
    firstSeries.label = { ...label, position: 'inside' }
  } else if (type === 'calendar-chart') {
    const template = config.label?.format || '{c}'
    firstSeries.label = {
      ...label, position: 'inside',
      formatter: (params) => template.replace(/\{[abcd]\}/g, (token) => token === '{b}' ? String(params.value?.[0] || '') : token === '{a}' ? String(seriesNames[0] || '') : String(params.value?.[1] ?? '')),
    }
  } else if (type === 'radar') {
    option.series.forEach((series, index) => {
      series.name = seriesNames[index] || series.name
      series.data = series.data.map((item) => ({ ...item, name: series.name, label }))
    })
  }
  if (!cartesianTypes.has(type)) return option

  // A category Y axis means a horizontal chart. Numeric/time X axes require
  // explicit [x, y] values; ECharts cannot infer X from scalar series values.
  const horizontal = option.yAxis.type === 'category'
  if (horizontal) {
    option.yAxis.data = labels
    if (option.xAxis.type === 'category') option.xAxis.type = 'value'
    delete option.xAxis.data
  } else if (option.xAxis.type === 'category') option.xAxis.data = labels
  for (const series of option.series) {
    if (type === 'scatter') {
      series.data = series.data.map((item, index) => horizontal ? [item[1], index] : option.xAxis.type === 'category' ? [index, item[1]] : [option.xAxis.type === 'time' ? categoryValues[index] : numberOr(categoryValues[index], index), item[1]])
    } else if (!horizontal && option.xAxis.type !== 'category') {
      series.data = series.data.map((value, index) => [option.xAxis.type === 'time' ? categoryValues[index] : numberOr(categoryValues[index], index), value])
    }
  }
  // Auxiliary lines belong to the value axis, including horizontal charts.
  const mark = config.markLine
  if (mark?.show === true && mark.value !== null && mark.value !== '' && Number.isFinite(Number(mark.value))) {
    firstSeries.markLine = {
      symbol: 'none', silent: true,
      lineStyle: { color: mark.color || '#e7ab47', type: ['solid', 'dashed', 'dotted'].includes(mark.lineType) ? mark.lineType : 'dashed' },
      label: { show: true, formatter: String(mark.label || mark.value), color: label.color, fontSize: 10 },
      data: [{ [horizontal ? 'xAxis' : 'yAxis']: Number(mark.value) }],
    }
  } else delete firstSeries.markLine
  return option
}
