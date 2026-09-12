import test from 'node:test'
import assert from 'node:assert/strict'
import * as echarts from 'echarts'
import { applyDashboardChartProperties, mergeDashboardChartOption } from '../src/utils/dashboardChartOptions.js'
import { buildDashboardMapOption, dashboardMapData } from '../src/utils/dashboardMapOptions.js'

const label = { show: true, color: '#fa1234', fontSize: 23, fontWeight: 700, formatter: '{b}: {c}' }
const tooltip = { show: false, textStyle: { fontSize: 26, color: '#abcdef' } }
const legend = { show: true, left: 'right', textStyle: { fontSize: 22 } }
const context = { labels: ['A', 'B'], categoryValues: [0, 5], values: [10, 20], seriesNames: ['产值', '进度'], tooltip, legend, label }
const widget = (type, chartConfig = {}, extra = {}) => ({ type, style: { chartConfig, ...extra } })

test('部分 JSON 样式保留标签开关、系列和数据，且不改写基础对象', () => {
  const base = { series: [{ type: 'bar', data: [3, 5], label: { show: true, color: '#fff' } }, { type: 'line', data: [4, 8] }], xAxis: { axisLabel: { fontSize: 12, color: '#fff' } } }
  const merged = mergeDashboardChartOption(base, { xAxis: { axisLabel: { fontSize: 20 } }, series: [{ label: { color: '#f00' } }] }, true)
  assert.equal(merged.series.length, 2)
  assert.deepEqual(merged.series[0].label, { show: true, color: '#f00' })
  assert.deepEqual(merged.series[1].data, [4, 8])
  assert.equal(merged.xAxis.axisLabel.color, '#fff')
  assert.equal(base.series[0].label.color, '#fff')
})
for (const type of ['gauge', 'progress', 'treemap-chart', 'calendar-chart']) {
  test(`${type} 标签开关、字体和提示开关生效`, () => {
    const base = { series: [{ type: type === 'progress' ? 'bar' : type, detail: { show: true }, data: [{ value: 18 }], label: { show: true } }], yAxis: { data: [''] } }
    const result = applyDashboardChartProperties(base, widget(type, { label: { format: '{b}: {c}' } }), context)
    assert.equal(result.tooltip.show, false)
    const actual = type === 'gauge' ? result.series[0].detail : result.series[0].label
    assert.equal(actual.fontSize, 23)
    assert.equal(actual.fontWeight, 700)
    assert.equal(actual.color, '#fa1234')
    applyDashboardChartProperties(base, widget(type), { ...context, label: { ...label, show: false } })
    assert.equal((type === 'gauge' ? base.series[0].detail : base.series[0].label).show, false)
  })
}
test('日历与等分环图的标签仍显示实际数值', () => {
  const calendar = { series: [{ data: [] }] }
  applyDashboardChartProperties(calendar, widget('calendar-chart', { label: { format: '{b}: {c}' } }), context)
  assert.equal(calendar.series[0].label.formatter({ value: ['2026-09-06', 23] }), '2026-09-06: 23')
  const ring = { series: [{ data: [{ value: 1 }, { value: 1 }] }] }
  applyDashboardChartProperties(ring, widget('ring-chart', { label: { format: '{c}' } }, { ringEqualSegments: true }), context)
  assert.equal(ring.series[0].label.formatter({ dataIndex: 1 }), '20')
  assert.equal(ring.tooltip.formatter({ name: 'B', dataIndex: 1 }), 'B: 20')
})
test('雷达图图例由系列名称关联数据项，支持图例选择', () => {
  const base = { series: [{ type: 'radar', data: [{ value: [1, 2] }] }, { type: 'radar', data: [{ value: [2, 4] }] }] }
  applyDashboardChartProperties(base, widget('radar'), context)
  assert.equal(base.legend.show, true)
  assert.deepEqual(base.series.map((item) => item.data[0].name), ['产值', '进度'])
})
test('数值轴保留真实 X=0、时间轴保留日期，横向图同步分类和辅助线', () => {
  const make = () => ({ xAxis: { type: 'value' }, yAxis: { type: 'value' }, series: [{ type: 'line', data: [10, 20] }] })
  const numeric = make()
  applyDashboardChartProperties(numeric, widget('line-chart'), context)
  assert.deepEqual(numeric.series[0].data, [[0, 10], [5, 20]])
  const time = make(); time.xAxis.type = 'time'
  applyDashboardChartProperties(time, widget('line-chart'), { ...context, categoryValues: ['2026-01-01', '2026-02-01'] })
  assert.deepEqual(time.series[0].data[0], ['2026-01-01', 10])
  const horizontal = make(); horizontal.xAxis.type = 'category'; horizontal.yAxis.type = 'category'
  applyDashboardChartProperties(horizontal, widget('bar-chart', { markLine: { show: true, value: 15, color: '#456789', lineType: 'dotted' } }), context)
  assert.equal(horizontal.xAxis.type, 'value')
  assert.deepEqual(horizontal.yAxis.data, ['A', 'B'])
  assert.deepEqual(horizontal.series[0].markLine.data, [{ xAxis: 15 }])
  const empty = make()
  applyDashboardChartProperties(empty, widget('bar-chart', { markLine: { show: true, value: null } }), context)
  assert.equal(empty.series[0].markLine, undefined)
})

const geoJson = { type: 'FeatureCollection', features: [{ type: 'Feature', properties: { name: '区域甲' }, geometry: { type: 'Polygon', coordinates: [[[100, 30], [110, 30], [110, 40], [100, 40], [100, 30]]] } }] }
const rows = [{ name: '区域甲', value: 20, longitude: 105, latitude: 35, fromLongitude: 104, fromLatitude: 34, toLongitude: 106, toLatitude: 36 }]
const mapWidget = (type, style = {}) => ({ type, binding: { fieldMap: { label: 'name', value: 'value' } }, style })
test('地图字段缺失、空字符串和越界经纬度不生成虚假原点或飞线', () => {
  const result = dashboardMapData(mapWidget('map-chart'), [...rows, { name: '空', value: 2, longitude: null, latitude: '' }, { longitude: 999, latitude: 40 }, {}])
  assert.equal(result.points.filter((item) => item.coordinate).length, 1)
  assert.equal(result.flows.length, 1)
})
test('地图自动数值范围不把空值解释成零，布局标签边界阴影均进入 geo', () => {
  const w = mapWidget('map-chart', { mapVisualMap: true, mapVisualMin: null, mapVisualMax: '', mapLayoutX: 30, mapZoom: 1.8, mapShowLabels: true, mapLabelFontSize: 21, mapShadowOffsetY: 10, mapBorderWidth: 0 })
  const option = buildDashboardMapOption(w, 'chart-property-map', dashboardMapData(w, rows))
  assert.equal(option.visualMap.min, 20)
  assert.equal(option.visualMap.max, 21)
  assert.deepEqual(option.geo.layoutCenter, ['30%', '50%'])
  assert.equal(option.geo.zoom, 1.8)
  assert.equal(option.geo.label.fontSize, 21)
  assert.equal(option.geo.itemStyle.borderWidth, 0)
  assert.equal(option.geo.itemStyle.shadowOffsetY, 10)
})
test('热力图颜色映射必须指向热力系列，隐藏图例仍保留渐变映射', () => {
  const w = mapWidget('map-heat', { mapVisualMap: false, mapPointSize: 24, mapVisualMinColor: '#123456', mapVisualMaxColor: '#fedcba' })
  const option = buildDashboardMapOption(w, 'chart-property-map', dashboardMapData(w, rows))
  assert.equal(option.visualMap.show, false)
  assert.equal(option.visualMap.seriesIndex, 1)
  assert.equal(option.visualMap.dimension, 2)
  assert.deepEqual(option.visualMap.inRange.color, ['#123456', '#fedcba'])
  assert.equal(option.series[1].pointSize, 24)
})
for (const type of ['map-bar', 'map-ranking']) {
  test(`${type} 按数值生成柱体且点位大小改变柱体尺寸`, () => {
    const make = (size) => { const w = mapWidget(type, { mapPointSize: size }); return buildDashboardMapOption(w, 'chart-property-map', dashboardMapData(w, rows)).series[1].data[0] }
    const small = make(6), large = make(18)
    assert.equal(small.symbol, 'rect')
    assert.equal(large.symbolSize[0] / small.symbolSize[0], 3)
    assert.equal(large.symbolSize[1] / small.symbolSize[1], 3)
    assert.equal(large.symbolOffset[1], -large.symbolSize[1] / 2)
  })
}
test('真实 ECharts SVG 渲染地图及柱形/排名/飞线/时间线的 option', () => {
  echarts.registerMap('chart-property-map', geoJson)
  for (const type of ['map-chart', 'map-bar', 'map-ranking', 'map-flow', 'map-timeline']) {
    const w = mapWidget(type, { mapShowLabels: true, mapVisualMap: true })
    const chart = echarts.init(null, null, { renderer: 'svg', ssr: true, width: 600, height: 400 })
    try {
      chart.setOption(buildDashboardMapOption(w, 'chart-property-map', dashboardMapData(w, rows)), true)
      assert.match(chart.renderToSVGString(), /<path/)
    } finally { chart.dispose() }
  }
})


test('实际设计器和运行态雷达图在加载空窗、数据到达和清空后均能渲染', async () => {
  const { readDashboardSfc, createFunctionHarness, designerPath, runtimePath } = await import('./helpers/dashboard-property-audit.mjs')
  for (const [filename, generator] of [[designerPath, 'designChartOption'], [runtimePath, 'buildOption']]) {
    const harness = await createFunctionHarness(await readDashboardSfc(filename))
    const widget = {
      id: 'radar-empty-regression', type: 'radar',
      style: { chartConfig: {}, optionJson: '' },
      binding: { sourceType: 'STATIC', staticRows: [], fieldMap: { category: 'category', value: 'actualValue', valueFields: ['actualValue', 'planValue', 'lastYearValue'] } },
    }
    const chart = echarts.init(null, null, { renderer: 'svg', ssr: true, width: 600, height: 400 })
    try {
      for (const rows of [[], [{ category: '质量', actualValue: 85, planValue: 90, lastYearValue: 75 }, { category: '进度', actualValue: 70, planValue: 80, lastYearValue: 60 }], []]) {
        widget.binding.staticRows = rows
        const option = harness[generator](widget, rows)
        assert.equal(option.radar.indicator.length, rows.length)
        assert.ok(option.series.every(series => series.data.length === (rows.length ? 1 : 0)))
        assert.doesNotThrow(() => chart.setOption(option, true))
        assert.match(chart.renderToSVGString(), /<svg/)
      }
    } finally { chart.dispose() }
  }
})

test('通用图表 JSON 清空雷达维度不会让ECharts产生空维度数据项', () => {
  const base = { radar: { indicator: [{ name: '进度' }] }, series: [{ type: 'radar', data: [{ value: [80] }] }] }
  const option = mergeDashboardChartOption(base, { radar: { indicator: [] }, series: [{ data: [{ value: [50] }] }] }, true)
  assert.deepEqual(option.series[0].data, [])
  assert.deepEqual(base.series[0].data[0].value, [80])
  const chart = echarts.init(null, null, { renderer: 'svg', ssr: true, width: 600, height: 400 })
  try { assert.doesNotThrow(() => chart.setOption(option, true)) } finally { chart.dispose() }
})
