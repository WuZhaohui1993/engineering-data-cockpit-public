import test from 'node:test'
import assert from 'node:assert/strict'
import { chartFixture, chartRows, createFunctionHarness, readDashboardSfc, runtimePath } from './helpers/dashboard-property-audit.mjs'

const runtime = await readDashboardSfc(runtimePath)
const geoJson = { type: 'FeatureCollection', features: [{ type: 'Feature', properties: { name: '测试区域' }, geometry: {
  type: 'Polygon', coordinates: [[[100, 30], [101, 30], [101, 31], [100, 31], [100, 30]]],
} }] }
const plain = value => JSON.parse(JSON.stringify(value))

async function harness() {
  const elements = new Map(), initialized = [], registrations = [], requests = [], stateWrites = [], clicks = []
  const h = await createFunctionHarness(runtime, {
    visibleWidgets: { value: [] },
    stageElement: { value: { querySelectorAll: selector => [...elements.values()].filter(element => selector === `[${element.attribute}]`) } },
    chartRenderSignatures: new Map(), mapRenderSignatures: new Map(), mapRefreshRequests: new Map(),
    mapRegistrations: new Map(), mapGeoJsonCache: new Map(), mapRequests: new Map(),
    mapStates: new Proxy({}, { set(target, id, value) { stateWrites.push({ id, ...value }); target[id] = value; return true } }),
    nextTick: callback => Promise.resolve().then(() => callback?.()),
    fetch: async (url, options) => { requests.push({ url, options }); return { ok: true, json: async () => geoJson } },
  })
  h.echarts = { ...h.echarts,
    init: element => {
      const chart = { element, options: [], events: {}, disposed: false, state: {},
        dispose() { this.disposed = true }, isDisposed() { return this.disposed },
        setOption(option, settings) { this.options.push({ option, settings }); this.state = plain(option) }, getOption() { return this.state },
        off(event) { delete this.events[event] }, on(event, callback) { this.events[event] = callback },
      }
      initialized.push(chart)
      return chart
    }, registerMap: (name, json) => registrations.push({ name, json }),
  }
  h.handleWidgetClick = (widget, row) => clicks.push({ widget, row })
  const add = (id, type = 'line-chart', mapRef = '/dashboard/assets/test-map.json') => {
    const widget = chartFixture(h, type)
    widget.id = id
    if (h.isMapWidget(type)) widget.style.mapRef = mapRef
    h.visibleWidgets.value.push(widget)
    h.widgetStates[id] = { rows: plain(chartRows), quality: 'SUCCESS', fetchedAt: 'first' }
    const attribute = h.isMapWidget(type) ? 'data-map-widget-id' : 'data-chart-widget-id'
    elements.set(id, { attribute, getAttribute: name => name === attribute ? id : null })
    return widget
  }
  return { h, elements, initialized, registrations, requests, stateWrites, clicks, add }
}

test('同一图表数据更新复用实例，目标 ID 刷新不触碰其他图表', async () => {
  const { h, add, initialized, clicks } = await harness()
  const first = add('first'); add('second')
  h.renderCharts()
  assert.equal(initialized.length, 2)
  const chart = h.chartInstances.first
  h.widgetStates.first.rows[0].value = 66
  h.widgetStates.second.rows[0].value = 77
  h.renderCharts(['first'])
  assert.equal(h.chartInstances.first, chart)
  assert.equal(chart.disposed, false)
  assert.equal(chart.options.length, 2)
  assert.equal(h.chartInstances.second.options.length, 1)
  assert.equal(chart.options[1].settings.notMerge, false)
  assert.deepEqual(Array.from(chart.options[1].settings.replaceMerge), ['series', 'visualMap'])
  chart.events.click({ dataIndex: 0 })
  assert.equal(clicks[0].row.value, 66)
  assert.equal(clicks[0].widget, first)
})

test('fetchedAt 和质量状态变化不重新绘图，样式变化更新同一实例', async () => {
  const { h, add, initialized } = await harness()
  const widget = add('one')
  h.renderCharts()
  const chart = h.chartInstances.one
  h.widgetStates.one = { rows: plain(chartRows), quality: 'SUCCESS', fetchedAt: 'later' }
  h.renderCharts()
  h.widgetStates.one.quality = 'CONNECT_ERROR'
  h.renderCharts()
  assert.equal(chart.options.length, 1)
  widget.style.color = '#123456'
  h.renderCharts()
  assert.equal(chart.options.length, 2)
  assert.equal(chart.options[1].settings.notMerge, true)
  assert.equal(initialized.length, 1)
})

test('仅组件 DOM 变化或移除才销毁对应图表', async () => {
  const { h, add, elements, initialized } = await harness()
  add('first'); add('second')
  h.renderCharts()
  const original = h.chartInstances.first, second = h.chartInstances.second
  elements.set('first', { ...elements.get('first') })
  h.renderCharts(['first'])
  assert.equal(original.disposed, true)
  assert.equal(initialized.length, 3)
  assert.equal(second.disposed, false)
  h.visibleWidgets.value = h.visibleWidgets.value.filter(widget => widget.id !== 'first')
  h.renderCharts(['second'])
  assert.equal(h.chartInstances.first, undefined)
  assert.equal(h.chartRenderSignatures.has('first'), false)
  elements.delete('second')
  h.renderCharts(['second'])
  assert.equal(second.disposed, true)
})

test('数据刷新保留生产选项中的图例选择、缩放和地图漫游状态', async () => {
  const { h } = await harness()
  const previous = {
    legend: [{ selected: { '数值': false } }], dataZoom: [{ id: 'zoom-a', start: 20, end: 60, startValue: 2, endValue: 6 }],
    geo: [{ zoom: 2.5, center: [110, 30] }], timeline: [{ currentIndex: 2, autoPlay: false }],
  }
  let applied
  const chart = { getOption: () => previous, setOption: (option, settings) => { applied = { option, settings } } }
  const option = { legend: {}, dataZoom: [{ id: 'zoom-a', start: 0, end: 100 }], geo: { zoom: 1 }, timeline: { currentIndex: 0, autoPlay: true }, series: [] }
  h.updateRuntimeChart(chart, option, false)
  assert.deepEqual(plain(applied.option.legend.selected), { '数值': false })
  assert.equal(applied.option.dataZoom[0].start, 20)
  assert.equal(applied.option.geo.zoom, 2.5)
  assert.deepEqual(applied.option.geo.center, [110, 30])
  assert.equal(applied.option.timeline.currentIndex, 2)
  assert.equal(applied.option.timeline.autoPlay, false)
})

test('地图只更新传入组件，资源只请求/注册一次，更新期间画面保持 ready', async () => {
  const { h, add, initialized, registrations, requests, stateWrites } = await harness()
  const first = add('first', 'map-chart'), second = add('second', 'map-chart')
  await h.loadMapCharts([first, second])
  assert.equal(requests.length, 1)
  assert.equal(requests[0].options.credentials, 'same-origin')
  assert.equal(registrations.length, 1)
  assert.equal(initialized.length, 2)
  const firstChart = h.mapInstances.first, secondChart = h.mapInstances.second
  stateWrites.length = 0
  firstChart.state.geo = [{ zoom: 2.5, center: [100.5, 30.5] }]
  h.widgetStates.first.rows[0].value = 99
  h.widgetStates.second.rows[0].value = 88
  await h.loadMapCharts([first])
  assert.equal(h.mapInstances.first, firstChart)
  assert.equal(firstChart.disposed, false)
  assert.equal(secondChart.options.length, 1)
  assert.equal(firstChart.options.length, 2)
  assert.equal(firstChart.options[1].option.geo.zoom, 2.5)
  assert.equal(stateWrites.some(state => state.ready === false), false)
  assert.equal(stateWrites.some(state => state.id === 'second'), false)
  assert.equal(registrations.length, 1)
  assert.equal(requests.length, 1)
  h.widgetStates.first.fetchedAt = 'later'
  await h.loadMapCharts([first])
  assert.equal(firstChart.options.length, 2)
})

test('地图组件换 DOM 会重建，页面销毁后旧资源请求不会重绘或回填缓存', async () => {
  const { h, add, elements, initialized } = await harness()
  const widget = add('map', 'map-chart')
  await h.loadMapCharts([widget])
  const original = h.mapInstances.map
  elements.set('map', { ...elements.get('map') })
  await h.loadMapCharts([widget])
  assert.equal(original.disposed, true)
  assert.equal(initialized.length, 2)
  h.clearIncrementalChartCaches()
  let resolveRequest
  h.fetch = () => new Promise(resolve => { resolveRequest = resolve })
  const pending = h.loadMapCharts([widget])
  h.clearIncrementalChartCaches()
  h.visibleWidgets.value = []
  resolveRequest({ ok: true, json: async () => geoJson })
  await pending
  assert.equal(h.mapGeoJsonCache.size, 0)
  assert.equal(h.mapRenderSignatures.size, 0)
  assert.equal(initialized.length, 2)
})

test('地图资源错误保留已显示画面，首次加载错误保留明确提示', async () => {
  const { h, add } = await harness()
  const widget = add('map', 'map-chart')
  await h.loadMapCharts([widget])
  const original = h.mapInstances.map
  h.mapGeoJsonCache.clear()
  h.fetch = async () => ({ ok: false, status: 503 })
  await h.loadMapCharts([widget])
  assert.equal(h.mapStates.map.ready, true)
  assert.match(h.mapStates.map.message, /503/)
  assert.equal(original.disposed, false)
  const missing = add('missing', 'map-chart', '/dashboard/assets/missing.json')
  await h.loadMapCharts([missing])
  assert.equal(h.mapStates.missing.ready, false)
  assert.match(h.mapStates.missing.message, /503/)
})

test('真实 ECharts 增量选项删除旧系列并保留图例选择和数据缩放', async t => {
  const { h } = await harness()
  const echarts = await import('echarts')
  const chart = echarts.init(null, null, { renderer: 'svg', ssr: true, width: 500, height: 300 })
  t.after(() => chart.dispose())
  // The extracted function lives in a VM realm; normalize arrays at the real library boundary.
  const bridge = { getOption: () => chart.getOption(), setOption: (option, settings) => chart.setOption(plain(option), plain(settings)) }
  h.updateRuntimeChart(bridge, {
    animation: false, legend: { data: ['甲', '乙'] }, xAxis: { type: 'category', data: ['一', '二', '三'] }, yAxis: {},
    dataZoom: [{ type: 'inside', start: 0, end: 100 }],
    series: [{ name: '甲', type: 'bar', data: [1, 2, 3] }, { name: '乙', type: 'bar', data: [4, 5, 6] }],
  }, true)
  const originalSeries = chart.getModel().getSeries()[0]
  chart.dispatchAction({ type: 'legendUnSelect', name: '甲' })
  chart.dispatchAction({ type: 'dataZoom', start: 20, end: 80 })
  h.updateRuntimeChart(bridge, {
    animation: false, legend: { data: ['甲'] }, xAxis: { type: 'category', data: ['一', '二', '三'] }, yAxis: {},
    dataZoom: [{ type: 'inside', start: 0, end: 100 }], series: [{ name: '甲', type: 'bar', data: [10, 20, 30] }],
  }, false)
  const state = chart.getOption()
  assert.equal(chart.getModel().getSeries()[0], originalSeries, '数据变化保留系列模型，避免重播入场动画')
  assert.equal(state.series.filter(Boolean).length, 1)
  assert.deepEqual(state.series.filter(Boolean)[0].data, [10, 20, 30])
  assert.equal(state.legend[0].selected['甲'], false)
  assert.equal(state.dataZoom[0].start, 20)
  assert.equal(state.dataZoom[0].end, 80)
})
