import test from 'node:test'
import assert from 'node:assert/strict'
import vm from 'node:vm'
import { createFunctionHarness, readDashboardSfc, runtimePath } from './helpers/dashboard-property-audit.mjs'

const source = await readDashboardSfc(runtimePath)
const plain = value => JSON.parse(JSON.stringify(value))
const deferred = () => {
  let resolve, reject
  const promise = new Promise((ok, fail) => { resolve = ok; reject = fail })
  return { promise, resolve, reject }
}
const flush = async () => { for (let i = 0; i < 12; i++) await Promise.resolve() }
const result = (value, revisionId = 12) => ({ rows: [{ value }], quality: 'SUCCESS', revisionId })

function fakeClock() {
  let id = 0
  const timeouts = new Map(), intervals = new Map()
  return {
    timeouts, intervals,
    window: {
      setTimeout(callback, delay) { timeouts.set(++id, { callback, delay }); return id },
      clearTimeout(timer) { timeouts.delete(timer) },
      setInterval(callback, delay) { intervals.set(++id, { callback, delay }); return id },
      clearInterval(timer) { intervals.delete(timer) },
      requestAnimationFrame: callback => callback(),
      location: { protocol: 'http:', host: '127.0.0.1:5173' },
    },
    fire() {
      const [id, timer] = timeouts.entries().next().value
      timeouts.delete(id)
      return timer.callback()
    },
  }
}

async function harness() {
  const clock = fakeClock(), metadata = [], configurations = [], dataRequests = [], events = [], sockets = [], schemaWrites = []
  const viewport = { scrollTop: 140, scrollLeft: 23, scrollTo(value) { this.scrollTop = value.top; this.scrollLeft = value.left } }
  const host = { id: 'fullscreen-host', isConnected: true }
  const configuration = {
    pageId: 403, pageCode: 'overview', pageName: '项目概况', revisionId: 12, versionNo: 3, versionMode: 'FOLLOW_PUBLISHED', datasets: [],
    schema: { canvas: { width: 1920, height: 1080, scaleMode: 'stretch', fullscreenScaleMode: 'cover' }, refresh: { enabled: false },
      filters: [{ id: 'project', parameter: 'project', type: 'STRING', defaultValue: '默认项目' }],
      widgets: [
        { id: 'table', type: 'table', binding: { datasetCode: 'details', parameters: {} }, style: {} },
        { id: 'tabs', type: 'tabs', style: { tabs: [{ key: 'overview', label: '概况' }, { key: 'detail', label: '明细' }] } },
        { id: 'form', type: 'filter-form', style: { formFields: [{ name: 'project', parameter: 'project', defaultValue: '默认项目' }] } },
      ],
    },
  }
  let schemaValue = configuration.schema
  let h
  h = await createFunctionHarness(source, {
    window: clock.window, document: { hidden: false, visibilityState: 'visible', fullscreenElement: host },
    runtimePage: { value: host }, stageViewport: { value: viewport }, isFullscreen: { value: true }, fullscreenFallback: { value: false },
    route: { path: '/dashboard/share/test-share', fullPath: '/dashboard/share/test-share', params: { token: 'test-share' }, query: {}, meta: {} },
    pageId: 403, isShareMode: { value: true }, isEmbedMode: { value: false }, isPreview: { value: false },
    shareToken: { value: 'test-share' }, routePageCode: { value: '' }, revisionId: { value: 0 },
    runtime: { pageName: configuration.pageName, versionNo: 3, versionMode: 'FOLLOW_PUBLISHED' },
    schema: { get value() { return schemaValue }, set value(value) { schemaWrites.push(value); schemaValue = value } },
    pageFilters: { get value() { return schemaValue?.filters || [] } },
    visibleWidgets: { get value() { return schemaValue?.widgets || [] } },
    canvasWidth: { value: 1920 }, canvasHeight: { value: 1080 }, runtimeDatasetsLoaded: { value: true },
    loading: { value: false }, refreshing: { value: false }, refreshNotice: { value: '' }, error: { value: '' }, lastLoaded: { value: '' },
    runtimeRequestOptions: { dashboardRuntimeRequest: true },
    widgetRequests: new Map(), widgetResultKeys: new Map(), socketRequestKeys: new Map(), socketRefreshTimers: new Map(),
    chartRenderSignatures: new Map(), mapRenderSignatures: new Map(), mapRefreshRequests: new Map(),
    detailPopup: { open: false, widget: null, row: null, title: '' },
    wsConnections: {}, wsReconnectTimers: {}, wsReconnectAttempts: {},
    carouselOffsets: {}, advancedOffsets: {}, widgetRefreshTimers: {}, carouselTimers: {},
    loadGeneration: 1, requestGeneration: 0, refreshTimer: undefined, pageRefreshTask: undefined,
    shareVersionTimer: null, shareVersionTask: null, shareVersionEpoch: 0, runtimeActive: true, SHARE_VERSION_POLL_MS: 30000,
    nextTick: callback => Promise.resolve().then(() => callback?.()),
    getDashboardShareVersion: (...args) => {
      const task = deferred(); metadata.push({ args, task }); return task.promise
    },
    getDashboardShareRuntime: (...args) => {
      const task = deferred(); configurations.push({ args, task }); return task.promise
    },
    fetchDashboardShareData: (...args) => {
      const task = deferred(); dataRequests.push({ args, task }); return task.promise
    },
    fetchDashboardSharePageData: (...args) => {
      const task = deferred(); dataRequests.push({ args, task }); return task.promise
    },
    getToken: () => '',
    listDashboardDatasets: async () => ({ rows: [] }),
  })
  h.loadedConfigurationKey = h.runtimeConfigurationKey(configuration)
  h.loadedRevisionId = 12
  h.schema.value = h.normalizeSchema(configuration.schema)
  h.initPageFilters()
  h.initWidgetControls(h.schema.value.widgets)
  schemaWrites.length = 0
  for (const name of ['renderCharts', 'loadMapCharts', 'notifyCustomData', 'disposeCharts', 'closeCustomFrames', 'resetCarouselTimers', 'postBridge', 'fitStage', 'resizeCharts', 'updateRuntimePageTag']) {
    h[name] = (...args) => { events.push({ name, args }) }
  }
  h.WebSocket = class FakeWebSocket {
    constructor(url) { this.url = url; this.readyState = 0; this.sent = []; this.closeCount = 0; sockets.push(this) }
    open() { this.readyState = 1; this.onopen?.() }
    send(value) { this.sent.push(JSON.parse(value)) }
    message(value) { this.onmessage?.({ data: JSON.stringify(value) }) }
    close() { this.closeCount++; this.readyState = 3; this.onclose?.({ code: 1000 }) }
  }
  const declaration = source.program.body.find(node => node.type === 'FunctionDeclaration' && node.id.name === 'openWebSocket')
  vm.runInContext(source.script.slice(declaration.start, declaration.end).replaceAll('import.meta.env.VITE_APP_BASE_API', JSON.stringify('/dev-api')), h)
  const sameMetadata = (extra = {}) => ({ data: { pageId: 403, revisionId: 12, versionNo: 3, versionMode: 'FOLLOW_PUBLISHED', ...extra } })
  return { h, clock, metadata, configurations, dataRequests, events, sockets, schemaWrites, configuration, viewport, host, sameMetadata }
}

test('页面关闭数据轮询、没有数据组件时仍独立每 30 秒检查分享版本', async () => {
  const { h, clock, metadata, configurations, sameMetadata } = await harness()
  h.schema.value.widgets = []
  h.scheduleShareVersionCheck()
  assert.equal(clock.timeouts.size, 1)
  assert.equal([...clock.timeouts.values()][0].delay, 30000)
  const pending = clock.fire()
  await flush()
  assert.equal(metadata.length, 1)
  assert.equal(clock.timeouts.size, 0)
  metadata[0].task.resolve(sameMetadata())
  await pending; await flush()
  assert.equal(configurations.length, 0)
  assert.equal(clock.timeouts.size, 1)
  assert.equal([...clock.timeouts.values()][0].delay, 30000)
  h.stopShareVersionCheck()
})

test('固定版本也检查分享状态，同版本切换模式只更新 metadata 不拉取页面配置', async () => {
  const { h, metadata, configurations, events, sameMetadata } = await harness()
  h.runtime.versionMode = 'FIXED'
  const pending = h.checkShareVersion()
  await flush()
  metadata[0].task.resolve(sameMetadata({ versionMode: 'FOLLOW_PUBLISHED' }))
  await pending
  assert.equal(h.runtime.versionMode, 'FOLLOW_PUBLISHED')
  assert.equal(configurations.length, 0)
  assert.equal(events.some(event => ['disposeCharts', 'renderCharts'].includes(event.name)), false)
  const fixed = h.checkShareVersion()
  await flush()
  metadata[1].task.resolve(sameMetadata({ versionMode: 'FIXED' }))
  await fixed
  assert.equal(h.runtime.versionMode, 'FIXED')
  assert.equal(configurations.length, 0)
})

test('版本检测合并在途任务，停止检测后迟到 metadata 不拉取配置或重启计时器', async () => {
  const { h, clock, metadata, configurations, sameMetadata } = await harness()
  const first = h.checkShareVersion()
  const second = h.checkShareVersion()
  await flush()
  assert.equal(metadata.length, 1)
  h.stopShareVersionCheck()
  metadata[0].task.resolve(sameMetadata({ revisionId: 13, versionNo: 4 }))
  await Promise.all([first, second]); await flush()
  assert.equal(configurations.length, 0)
  assert.equal(h.loadedRevisionId, 12)
  assert.equal(clock.timeouts.size, 0)
})

test('版本检查尚未发送就被停用时，不再发出旧分享请求', async () => {
  const { h, metadata, sameMetadata } = await harness()
  const pending = h.checkShareVersion()
  h.runtimeActive = false
  h.stopShareVersionCheck()
  await flush()
  const sent = metadata.length
  metadata.forEach(({ task }) => task.resolve(sameMetadata()))
  await pending
  assert.equal(sent, 0)
})

test('版本变化等待配置期间保持旧画面，完成后保留兼容筛选、表单、页内选项卡和全屏宿主', async () => {
  const { h, metadata, configurations, dataRequests, configuration, schemaWrites, viewport, host, sameMetadata } = await harness()
  h.pageFilterValues.project = '用户项目'
  h.formValues.form.project = '用户项目'
  h.tabValues.tabs = 1
  const original = h.schema.value
  const pending = h.checkShareVersion()
  await flush()
  metadata[0].task.resolve(sameMetadata({ revisionId: 13, versionNo: 4 }))
  await flush()
  assert.equal(configurations.length, 1)
  assert.equal(h.schema.value, original)
  assert.equal(h.loading.value, false)
  const next = plain(configuration)
  next.revisionId = 13; next.versionNo = 4
  next.schema.widgets.find(widget => widget.id === 'tabs').style.tabs.reverse()
  next.schema.widgets.find(widget => widget.id === 'table').style.title = '新版明细'
  configurations[0].task.resolve({ data: next })
  await flush()
  assert.equal(dataRequests.length, 1)
  assert.equal(dataRequests[0].args[4].project, '用户项目')
  dataRequests[0].task.resolve(result(99, 13))
  await pending
  assert.equal(h.loadedRevisionId, 13)
  assert.equal(h.runtime.versionNo, 4)
  assert.equal(h.schema.value.widgets[0].style.title, '新版明细')
  assert.equal(h.pageFilterValues.project, '用户项目')
  assert.equal(h.formValues.form.project, '用户项目')
  assert.equal(h.tabValues.tabs, 0, '选项卡顺序变更后按 key 保留用户选择')
  assert.equal(schemaWrites.includes(null), false)
  assert.equal(h.runtimePage.value, host)
  assert.equal(h.document.fullscreenElement, host)
  assert.equal(h.isFullscreen.value, true)
  assert.equal(viewport.scrollTop, 140)
  assert.equal(viewport.scrollLeft, 23)
})

test('配置变化仅恢复兼容控件，字段类型和绑定变化不带入旧筛选/排序/联动', async () => {
  const { h, configuration } = await harness()
  h.pageFilterValues.project = '旧项目'
  h.formValues.form.project = '旧项目'
  h.sortState.table = { field: 'name', order: 'desc' }
  h.linkedFilters.table = { params: { project: '旧项目' } }
  const state = h.captureRuntimeViewState()
  const next = plain(configuration.schema)
  next.filters[0].type = 'NUMBER'; next.filters[0].defaultValue = 4
  next.widgets.find(widget => widget.id === 'form').style.formFields[0].type = 'NUMBER'
  next.widgets.find(widget => widget.id === 'form').style.formFields[0].defaultValue = 4
  next.widgets.find(widget => widget.id === 'table').binding.datasetCode = 'new-details'
  h.schema.value = h.normalizeSchema(next)
  h.initPageFilters(); h.initWidgetControls(h.schema.value.widgets)
  h.restoreRuntimeViewState(state)
  assert.equal(h.pageFilterValues.project, 4)
  assert.equal(h.formValues.form.project, 4)
  assert.equal(h.sortState.table, undefined)
  assert.equal(h.linkedFilters.table, undefined)
})

test('配置请求在 A → B → A 后迟到，不应用旧配置或生成新数据请求', async () => {
  const { h, metadata, configurations, dataRequests, configuration, sameMetadata } = await harness()
  const pending = h.checkShareVersion()
  await flush()
  metadata[0].task.resolve(sameMetadata({ revisionId: 13, versionNo: 4 }))
  await flush()
  h.stopShareVersionCheck(); h.loadGeneration++; h.routePageCode.value = 'progress'
  h.stopShareVersionCheck(); h.loadGeneration++; h.routePageCode.value = ''
  configurations[0].task.resolve({ data: { ...configuration, revisionId: 13, versionNo: 4 } })
  await pending
  assert.equal(h.loadedRevisionId, 12)
  assert.equal(dataRequests.length, 0)
})

test('切页或换分享令牌使在途版本检查失效', async () => {
  for (const change of [h => { h.routePageCode.value = 'progress' }, h => { h.shareToken.value = 'other-share' }, h => { h.loadGeneration++ }]) {
    const { h, metadata, configurations, sameMetadata } = await harness()
    const pending = h.checkShareVersion()
    await flush()
    change(h)
    metadata[0].task.resolve(sameMetadata({ revisionId: 13 }))
    await pending
    assert.equal(configurations.length, 0)
    assert.equal(h.loadedRevisionId, 12)
    h.stopShareVersionCheck()
  }
})

test('页面隐藏停止检查，重新可见立即检查，停用后迟到结果不能恢复轮询', async () => {
  const { h, clock, metadata, sameMetadata } = await harness()
  h.scheduleShareVersionCheck()
  h.document.hidden = true
  h.handleShareVisibilityChange()
  assert.equal(clock.timeouts.size, 0)
  h.scheduleShareVersionCheck()
  assert.equal(clock.timeouts.size, 0)
  h.document.hidden = false
  h.handleShareVisibilityChange()
  await flush()
  assert.equal(metadata.length, 1)
  const pending = h.shareVersionTask.promise
  h.runtimeActive = false
  h.stopShareVersionCheck()
  metadata[0].task.resolve(sameMetadata())
  await pending
  assert.equal(clock.timeouts.size, 0)
  h.handleShareVisibilityChange()
  await flush()
  assert.equal(metadata.length, 1)
})

test('版本检查暂时网络故障保留画面且继续轮询，撤销或过期立即清空', async () => {
  const transient = await harness()
  transient.h.scheduleShareVersionCheck()
  const pending = transient.clock.fire()
  await flush()
  const schema = transient.h.schema.value
  transient.metadata[0].task.reject({ requestErrorKind: 'transport', httpStatus: 503, message: '服务暂不可用' })
  await pending
  assert.equal(transient.h.schema.value, schema)
  assert.equal(transient.h.error.value, '')
  assert.equal(transient.clock.timeouts.size, 1)
  transient.h.stopShareVersionCheck()
  for (const status of [403, 410]) {
    const { h, clock, metadata, events } = await harness()
    h.scheduleShareVersionCheck()
    const checking = clock.fire()
    await flush()
    metadata[0].task.reject({ requestErrorKind: 'business', businessCode: status, message: '分享已不可用' })
    await checking
    assert.equal(h.schema.value, null)
    assert.equal(h.error.value, '分享已不可用')
    assert.equal(clock.timeouts.size, 0)
    assert.ok(events.some(event => event.name === 'disposeCharts'))
  }
})

test('新版本 REST 数据不写入旧组件并主动触发配置检查', async () => {
  const { h, metadata } = await harness()
  const widget = h.schema.value.widgets[0]
  h.widgetStates.table = result(8)
  const previous = h.widgetStates.table
  await h.commitWidgetResult(widget, result(99, 13), 'request-key', h.loadGeneration)
  await flush()
  assert.equal(h.widgetStates.table, previous)
  assert.equal(metadata.length, 1)
  const pending = h.shareVersionTask.promise
  h.stopShareVersionCheck()
  metadata[0].task.resolve({ data: {} })
  await pending
})

test('新版本 WebSocket 数据先于配置到达不落旧画布，换版后重新订阅并丢弃旧消息', async () => {
  const { h, metadata, configurations, dataRequests, configuration, sockets, sameMetadata } = await harness()
  h.schema.value.widgets[0].binding.datasetCode = 'stream'
  h.datasetCatalog.value = [{ datasetCode: 'stream', dataType: 'WEBSOCKET' }]
  const widget = h.schema.value.widgets[0]
  await h.loadWidget(widget)
  const oldSocket = sockets[0]
  oldSocket.open(); oldSocket.message(result(8)); await flush()
  const previous = h.widgetStates.table
  oldSocket.message(result(99, 13)); await flush()
  assert.equal(h.widgetStates.table, previous)
  assert.equal(metadata.length, 1)
  const pending = h.shareVersionTask.promise
  metadata[0].task.resolve(sameMetadata({ revisionId: 13, versionNo: 4 }))
  await flush()
  const next = plain(configuration)
  next.revisionId = 13; next.versionNo = 4
  next.schema.widgets[0].binding.datasetCode = 'stream'
  next.datasets = [{ datasetCode: 'stream', dataType: 'WEBSOCKET' }]
  configurations[0].task.resolve({ data: next })
  await pending
  assert.equal(dataRequests.length, 0)
  assert.equal(oldSocket.closeCount, 1)
  assert.equal(sockets.length, 2)
  const currentSocket = sockets[1]
  currentSocket.open(); currentSocket.message(result(100, 13)); await flush()
  const state = h.widgetStates.table
  oldSocket.message(result(-1, 12)); await flush()
  assert.equal(h.widgetStates.table, state)
  assert.equal(state.rows[0].value, 100)
  assert.equal(currentSocket.sent[0].type, 'subscribe')
})

test('换版时释放被移除、改类型或换绑定的图表，兼容组件保留实例与数据', async () => {
  const { h, configuration, dataRequests, events } = await harness()
  const retained = { disposeCount: 0, dispose() { this.disposeCount++ } }
  const removed = { disposeCount: 0, dispose() { this.disposeCount++ } }
  const changed = { disposeCount: 0, dispose() { this.disposeCount++ } }
  const rebound = { disposeCount: 0, dispose() { this.disposeCount++ } }
  h.schema.value.widgets.push(
    { id: 'retained', type: 'line-chart', binding: {} },
    { id: 'removed', type: 'line-chart', binding: {} },
    { id: 'changed', type: 'line-chart', binding: {} },
    { id: 'rebound', type: 'line-chart', binding: { datasetCode: 'old-dataset' } },
  )
  h.schema.value = h.normalizeSchema(h.schema.value)
  Object.assign(h.chartInstances, { retained, removed, changed, rebound })
  Object.assign(h.chartElements, { retained: {}, removed: {}, changed: {}, rebound: {} })
  for (const id of ['retained', 'removed', 'changed', 'rebound']) h.chartRenderSignatures.set(id, {})
  const previousRows = result(8).rows
  h.widgetStates.table = { rows: previousRows, quality: 'SUCCESS' }
  const next = plain(configuration)
  next.revisionId = 13; next.versionNo = 4
  next.schema.widgets.push({ id: 'retained', type: 'line-chart', binding: {} }, { id: 'changed', type: 'table', binding: {} }, { id: 'rebound', type: 'line-chart', binding: { datasetCode: 'new-dataset' } })
  const pending = h.applyRuntimeConfiguration(next)
  await flush()
  assert.equal(h.widgetStates.table.rows, previousRows)
  assert.equal(h.chartInstances.retained, retained)
  assert.equal(retained.disposeCount, 0)
  assert.equal(removed.disposeCount, 1)
  assert.equal(changed.disposeCount, 1)
  assert.equal(rebound.disposeCount, 1)
  assert.equal(h.chartRenderSignatures.has('removed'), false)
  assert.equal(h.chartRenderSignatures.has('changed'), false)
  assert.equal(h.chartRenderSignatures.has('rebound'), false)
  dataRequests.forEach(({ task }) => task.resolve(result(9, 13)))
  await pending
  assert.equal(events.some(event => event.name === 'disposeCharts'), false)
})

test('版本切换使旧地图资源请求失效，迟到资源不能重绘已移除的组件', async () => {
  const { h, configuration, dataRequests } = await harness()
  const oldMap = { id: 'old-map', type: 'map-chart', style: { mapRef: '/dashboard/assets/map/demo-region.json' }, binding: {} }
  h.schema.value.widgets.push(oldMap)
  const resource = deferred(), renders = []
  const declaration = source.program.body.find(node => node.type === 'FunctionDeclaration' && node.id.name === 'loadMapCharts')
  vm.runInContext(source.script.slice(declaration.start, declaration.end), h)
  h.getMapGeoJson = () => resource.promise
  h.runtimeElement = () => ({})
  h.renderMapChart = (...args) => renders.push(args)
  const loadingMap = h.loadMapCharts([oldMap])
  assert.equal(h.mapRefreshRequests.size, 1)
  const applying = h.applyRuntimeConfiguration({ ...configuration, revisionId: 13, versionNo: 4 })
  assert.equal(h.mapRefreshRequests.size, 0)
  resource.resolve({ type: 'FeatureCollection', features: [] })
  await loadingMap
  assert.equal(renders.length, 0)
  await flush()
  dataRequests.forEach(({ task }) => task.resolve(result(9, 13)))
  await applying
  assert.equal(h.mapStates['old-map'], undefined)
})

test('兼容钻取恢复筛选与完整历史，交互规则变化后清除旧层级联动', async () => {
  const { h } = await harness()
  h.schema.value.widgets[0].interaction = { drilldown: { levels: [{ label: '项目', parameterMappings: [{ sourceField: 'project', targetParameter: 'project' }] }] } }
  const history = { level: 1, history: [{ level: 0, params: {}, filters: [] }] }
  h.drilldownStates.table = plain(history)
  h.linkedFilters.table = { params: { project: '已钻取项目' }, filters: [] }
  const captured = h.captureRuntimeViewState()
  h.initWidgetControls(h.schema.value.widgets)
  h.restoreRuntimeViewState(captured)
  assert.deepEqual(plain(h.drilldownStates.table), history)
  assert.equal(h.linkedFilters.table.params.project, '已钻取项目')
  h.schema.value.widgets[0].interaction.drilldown.levels = []
  h.initWidgetControls(h.schema.value.widgets)
  h.restoreRuntimeViewState(captured)
  assert.equal(h.drilldownStates.table.level, 0)
  assert.deepEqual(plain(h.drilldownStates.table.history), [])
  assert.equal(h.linkedFilters.table, undefined)
})

test('换版和分享失效都会关闭并清空旧数据详情弹窗', async () => {
  const { h, configuration, dataRequests } = await harness()
  const openOld = () => Object.assign(h.detailPopup, { open: true, widget: h.schema.value.widgets[0], row: { secretValue: 'old-row' }, title: '旧详情' })
  openOld()
  const pending = h.applyRuntimeConfiguration({ ...configuration, revisionId: 13, versionNo: 4 })
  assert.deepEqual(plain(h.detailPopup), { open: false, widget: null, row: null, title: '' })
  await flush()
  dataRequests.forEach(({ task }) => task.resolve(result(9, 13)))
  await pending
  openOld()
  h.invalidateRuntime({ message: '分享已撤销' })
  assert.deepEqual(plain(h.detailPopup), { open: false, widget: null, row: null, title: '' })
  assert.equal(h.schema.value, null)
})

test('真实停用和销毁生命周期终止版本检查并隔离迟到结果', async () => {
  for (const hookName of ['onDeactivated', 'onBeforeUnmount']) {
    const { h, clock, metadata, configurations, sameMetadata } = await harness()
    h.window.removeEventListener = () => {}
    h.document.removeEventListener = () => {}
    h.clockTimer = undefined
    h.stageResizeObserver = undefined
    const pending = h.checkShareVersion()
    await flush()
    const hook = source.program.body.find(node => node.type === 'ExpressionStatement' && node.expression.type === 'CallExpression' && node.expression.callee.name === hookName)
    assert.ok(hook, hookName)
    const callback = hook.expression.arguments[0]
    vm.runInContext(`(${source.script.slice(callback.start, callback.end)})()`, h)
    assert.equal(h.runtimeActive, false)
    assert.equal(clock.timeouts.size, 0)
    metadata[0].task.resolve(sameMetadata({ revisionId: 13, versionNo: 4 }))
    await pending
    assert.equal(configurations.length, 0)
    assert.equal(clock.timeouts.size, 0)
  }
})
