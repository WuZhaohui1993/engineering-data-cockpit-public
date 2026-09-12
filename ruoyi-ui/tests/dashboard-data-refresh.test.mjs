import test from 'node:test'
import assert from 'node:assert/strict'
import vm from 'node:vm'
import { createFunctionHarness, readDashboardSfc, runtimePath } from './helpers/dashboard-property-audit.mjs'
import { dashboardRefreshError, mergeDashboardRefreshResult, runDashboardRefreshBatch } from '../src/utils/dashboardRefresh.js'

const runtimeSource = await readDashboardSfc(runtimePath)
const plain = value => JSON.parse(JSON.stringify(value))
const deferred = () => {
  let resolve, reject
  const promise = new Promise((ok, fail) => { resolve = ok; reject = fail })
  return { promise, resolve, reject }
}
const flush = async () => { for (let i = 0; i < 8; i++) await Promise.resolve() }
const result = value => ({ rows: [{ value }], quality: 'SUCCESS', fetchedAt: 'now' })
const widget = (id, binding = {}) => ({ id, type: 'data-table', binding: { datasetCode: `dataset-${id}`, parameters: {}, ...binding }, style: {} })

function timers() {
  let id = 0
  const timeouts = new Map(), intervals = new Map()
  return {
    timeouts, intervals,
    window: {
      setTimeout: (callback, delay) => { timeouts.set(++id, { callback, delay }); return id },
      clearTimeout: timer => timeouts.delete(timer),
      setInterval: (callback, delay) => { intervals.set(++id, { callback, delay }); return id },
      clearInterval: timer => intervals.delete(timer),
    },
    fireTimeout() {
      const [key, timer] = timeouts.entries().next().value
      timeouts.delete(key)
      return timer.callback()
    },
  }
}

async function harness(widgets = [widget('one'), widget('two')]) {
  const clock = timers()
  const events = [], requests = [], configurations = []
  const config = { pageId: 403, pageName: '测试页', revisionId: 12, schema: { canvas: {}, refresh: { enabled: true, seconds: 5 }, widgets }, datasets: [] }
  const h = await createFunctionHarness(runtimeSource, {
    window: clock.window, runtime: {},
    route: { path: '/dashboard/runtime/403', params: { pageId: '403' }, query: {}, meta: {} },
    pageId: 403, isShareMode: { value: false }, isEmbedMode: { value: false },
    isPreview: { value: false }, revisionId: { value: 0 }, routePageCode: { value: '' }, shareToken: { value: '' },
    loading: { value: false }, refreshing: { value: false }, refreshNotice: { value: '' }, error: { value: '' },
    detailPopup: { open: false, widget: null, row: null, title: '' },
    schema: { value: config.schema }, pageFilters: { value: [] }, lastLoaded: { value: '' },
    canvasWidth: { value: 1920 }, canvasHeight: { value: 1080 }, runtimeDatasetsLoaded: { value: true },
    runtimeRequestOptions: { dashboardRuntimeRequest: true },
    loadGeneration: 1, requestGeneration: 0, pageRefreshTask: undefined, refreshTimer: undefined,
    widgetRequests: new Map(), widgetResultKeys: new Map(), widgetRefreshTimers: {},
    socketRequestKeys: new Map(), socketRefreshTimers: new Map(), wsConnections: {},
    carouselOffsets: {}, advancedOffsets: {},
    nextTick: callback => Promise.resolve().then(() => callback?.()),
    fetchDashboardData: (...args) => {
      const task = deferred()
      requests.push({ args, task })
      return task.promise
    },
    getDashboardRuntime: (...args) => { configurations.push(args); return Promise.resolve({ data: config }) },
    listDashboardDatasets: async () => ({ rows: [] }),
  })
  h.loadedConfigurationKey = h.runtimeConfigurationKey(config)
  h.loadedRevisionId = config.revisionId
  // Rendering and browser resources are observed at their boundaries. The refresh
  // orchestration, request identity, merge and generation guards run unchanged.
  for (const name of ['renderCharts', 'loadMapCharts', 'notifyCustomData', 'closeSockets', 'disposeCharts', 'closeCustomFrames', 'resetCarouselTimers', 'postBridge', 'fitStage', 'updateRuntimePageTag']) {
    h[name] = (...args) => { events.push({ name, args }) }
  }
  return { h, clock, events, requests, configurations, config, widgets }
}

async function websocketHarness(widgets) {
  const setup = await harness(widgets)
  const { h } = setup, sockets = []
  h.datasetCatalog.value = widgets.map(item => ({ datasetCode: item.binding.datasetCode, dataType: 'WEBSOCKET' }))
  h.window.location = { protocol: 'http:', host: '127.0.0.1:5173' }
  h.getToken = () => ''
  h.WebSocket = class FakeWebSocket {
    constructor(url) {
      this.url = url; this.readyState = 0; this.sent = []; this.closeCount = 0
      sockets.push(this)
    }
    open() { this.readyState = 1; this.onopen?.() }
    send(data) { assert.equal(this.readyState, 1); this.sent.push(JSON.parse(data)) }
    close() { this.closeCount++; this.readyState = 3; this.onclose?.({ code: 1000 }) }
    // Deliberately allow delivery after close, as an already queued browser event
    // must also be rejected by the production connection identity guard.
    message(value) { this.onmessage?.({ data: JSON.stringify(value) }) }
  }
  for (const name of ['openWebSocket', 'closeSockets']) {
    const declaration = runtimeSource.program.body.find(node => node.type === 'FunctionDeclaration' && node.id.name === name)
    // Substitute only Vite's public compile-time API prefix; all WebSocket
    // handlers and connection lifecycle logic execute directly from production.
    const source = runtimeSource.script.slice(declaration.start, declaration.end)
      .replaceAll('import.meta.env.VITE_APP_BASE_API', JSON.stringify('/dev-api'))
    vm.runInContext(source, h)
  }
  return { ...setup, sockets }
}

test('暂时网络故障保留同一查询的上一帧并标记 STALE，恢复后清除旧错误', () => {
  const previous = result(8)
  for (const quality of ['TIMEOUT', 'CONNECT_ERROR', 'RATE_LIMITED', 'SOURCE_ERROR']) {
    const stale = mergeDashboardRefreshResult(previous, { rows: [], quality, message: '稍后重试' }, true)
    assert.equal(stale.rows, previous.rows)
    assert.equal(stale.quality, 'STALE')
    assert.equal(stale.refreshErrorQuality, quality)
    const recovered = mergeDashboardRefreshResult(stale, result(8), true)
    assert.equal(recovered.rows, previous.rows)
    assert.equal(recovered.quality, 'SUCCESS')
    assert.equal(recovered.refreshError, undefined)
  }
})

test('新筛选条件、真实空数据和授权失效不会展示旧查询结果', () => {
  const previous = result(8)
  for (const quality of ['NO_DATA', 'FORBIDDEN', 'AUTH_ERROR', 'INVALID_DATA']) {
    const next = mergeDashboardRefreshResult(previous, { rows: [], quality }, true)
    assert.deepEqual(next.rows, [])
    assert.equal(next.quality, quality)
  }
  const newQuery = mergeDashboardRefreshResult(previous, { rows: [], quality: 'CONNECT_ERROR', transient: true }, false)
  assert.deepEqual(newQuery.rows, [])
  const permanentSourceError = mergeDashboardRefreshResult(previous, { rows: [], quality: 'SOURCE_ERROR', transient: false }, true)
  assert.deepEqual(permanentSourceError.rows, [])
})

test('相同业务数据复用 rows 引用，时间戳更新不会重置滚动轨道', () => {
  const previous = result(8)
  const next = mergeDashboardRefreshResult(previous, { ...result(8), fetchedAt: 'later' }, true)
  assert.equal(next.rows, previous.rows)
  assert.equal(next.fetchedAt, 'later')
  assert.notEqual(mergeDashboardRefreshResult(previous, result(9), true).rows, previous.rows)
})

test('错误分类区分暂时传输故障、授权错误和业务拒绝', () => {
  const cases = [
    [{ requestErrorKind: 'transport', code: 'ECONNABORTED' }, 'TIMEOUT', true],
    [{ requestErrorKind: 'transport', httpStatus: 503 }, 'CONNECT_ERROR', true],
    [{ requestErrorKind: 'transport', httpStatus: 429 }, 'RATE_LIMITED', true],
    [{ requestErrorKind: 'transport', httpStatus: 401 }, 'AUTH_ERROR', false],
    [{ requestErrorKind: 'transport', httpStatus: 403 }, 'FORBIDDEN', false],
    [{ requestErrorKind: 'business', businessCode: 500 }, 'INVALID_DATA', false],
  ]
  for (const [error, quality, transient] of cases) {
    const failure = dashboardRefreshError(error)
    assert.equal(failure.quality, quality)
    assert.equal(failure.transient, transient)
  }
})

test('首次加载初始化一次；同配置刷新只取数据，保持画布、筛选和滚动状态', async () => {
  const { h, requests, events, configurations, config } = await harness()
  let loads = 0
  const loadWidgets = h.loadWidgets
  h.loadWidgets = (...args) => { loads++; return loadWidgets(...args) }
  const first = h.load(config)
  await flush()
  assert.equal(requests.length, 2)
  requests.forEach(({ task }, i) => task.resolve(result(i)))
  await first
  assert.equal(loads, 1)
  const schema = h.schema.value, previousRows = h.widgetStates.one.rows
  h.formValues.one = { selected: '甲' }
  h.carouselOffsets.one = 42
  events.length = 0
  const pending = h.refreshData()
  await flush()
  assert.equal(h.loading.value, false)
  assert.equal(h.schema.value, schema)
  assert.equal(h.widgetStates.one.rows, previousRows)
  assert.equal(configurations.length, 1)
  assert.equal(requests.length, 4)
  requests[2].task.resolve(result(10)); requests[3].task.resolve(result(11))
  await pending
  assert.equal(loads, 1)
  assert.deepEqual(plain(h.formValues.one), { selected: '甲' })
  assert.equal(h.carouselOffsets.one, 42)
  assert.equal(events.some(event => ['disposeCharts', 'closeSockets', 'closeCustomFrames', 'resetCarouselTimers'].includes(event.name)), false)
  assert.equal(h.widgetStates.one.rows[0].value, 10)
  assert.equal(h.refreshing.value, false)
})

test('只刷新指定组件，不读取页面配置也不更新其他组件', async () => {
  const { h, requests, configurations, events, widgets } = await harness()
  h.widgetStates.two = result(22)
  const untouched = h.widgetStates.two
  const pending = h.refreshData({ widgets: [widgets[0]], checkConfiguration: false })
  assert.equal(requests.length, 1)
  assert.equal(requests[0].args[1], 'one')
  requests[0].task.resolve(result(11))
  await pending
  assert.equal(configurations.length, 0)
  assert.equal(h.widgetStates.two, untouched)
  assert.deepEqual(events.filter(event => event.name === 'renderCharts').map(event => plain(event.args[0])), [['one']])
})

test('页面筛选变化只重新请求绑定到该筛选的组件', async () => {
  const { h, requests, widgets } = await harness()
  h.pageFilters.value = [{ id: 'project-filter', parameter: 'project', targetWidgetIds: ['one'] }]
  h.pageFilterValues['project-filter'] = ''
  const initial = Promise.all(widgets.map(item => h.loadWidget(item)))
  requests.forEach(({ task }, i) => task.resolve(result(i)))
  await initial
  h.pageFilterValues['project-filter'] = '新项目'
  h.changePageFilter()
  assert.equal(requests.length, 3)
  assert.equal(requests[2].args[1], 'one')
  assert.equal(requests[2].args[3].project, '新项目')
  const pending = h.widgetRequests.get('one').promise
  requests[2].task.resolve(result(3))
  await pending
  h.changePageFilter()
  await flush()
  assert.equal(requests.length, 3)
})

test('整页定时范围跳过静态、宿主覆盖和独立刷新组件', async () => {
  const list = [widget('inherited'), widget('independent', { refreshSeconds: 10 }), widget('static', { sourceType: 'STATIC' }), widget('host')]
  const { h, requests } = await harness(list)
  h.overrideData.host = result(7)
  const pending = h.refreshData({ scope: 'inherited', checkConfiguration: false })
  assert.equal(requests.length, 1)
  assert.equal(requests[0].args[1], 'inherited')
  requests[0].task.resolve(result(1))
  await pending
})

test('同组件同参数请求合并，参数变化后的旧回包不能覆盖新结果', async () => {
  const { h, requests, widgets } = await harness()
  const first = h.loadWidget(widgets[0])
  assert.equal(h.loadWidget(widgets[0]), first)
  assert.equal(requests.length, 1)
  h.linkedFilters.one = { params: { project: '乙' } }
  const second = h.loadWidget(widgets[0])
  assert.notEqual(second, first)
  assert.equal(requests.length, 2)
  requests[1].task.resolve(result(2))
  await second
  requests[0].task.resolve(result(1))
  await first
  assert.equal(h.widgetStates.one.rows[0].value, 2)
  assert.equal(h.widgetRequests.size, 0)
})

test('同参数取数失败保留旧帧，改筛选后失败清空旧帧', async () => {
  const { h, requests, widgets } = await harness()
  const first = h.loadWidget(widgets[0]); requests[0].task.resolve(result(10)); await first
  const rows = h.widgetStates.one.rows
  const second = h.loadWidget(widgets[0]); requests[1].task.reject({ requestErrorKind: 'transport', message: 'Network Error' }); await second
  assert.equal(h.widgetStates.one.rows, rows)
  assert.equal(h.widgetStates.one.quality, 'STALE')
  h.linkedFilters.one = { params: { project: '新项目' } }
  const third = h.loadWidget(widgets[0]); requests[2].task.reject({ requestErrorKind: 'transport', message: 'Network Error' }); await third
  assert.deepEqual(plain(h.widgetStates.one.rows), [])
})

test('切页后旧组件请求及旧配置检查均丢弃，不回填新页面', async () => {
  const { h, requests, widgets } = await harness()
  const oldData = h.loadWidget(widgets[0])
  const configuration = deferred()
  h.getDashboardRuntime = () => configuration.promise
  const oldRefresh = h.refreshData()
  h.loadGeneration++
  const current = result(99)
  h.widgetStates.one = current
  requests[0].task.resolve(result(1))
  configuration.resolve({ data: { schema: { widgets: [] }, revisionId: 99 } })
  await Promise.all([oldData, oldRefresh])
  assert.equal(h.widgetStates.one, current)
  assert.equal(requests.length, 1)
  assert.equal(h.loading.value, false)
})

test('旧页面首次批量加载被切页打断后，不再启动尚未发送的组件请求', async () => {
  const { h, requests, widgets } = await harness(Array.from({ length: 8 }, (_, i) => widget(`widget-${i}`)))
  const pending = h.loadWidgets(widgets)
  await flush()
  assert.equal(requests.length, 6)
  h.loadGeneration++
  h.pageId = 404
  requests.slice().forEach(({ task }) => task.resolve(result(1)))
  await flush()
  const sent = requests.length
  // Release any incorrectly scheduled requests so a failing assertion cannot
  // leave pending asynchronous work behind.
  requests.slice(6).forEach(({ task }) => task.resolve(result(2)))
  await pending
  assert.equal(sent, 6)
  assert.deepEqual(plain(h.widgetStates), {})
})

test('并发切换页面只接受最新配置，较早 load 不覆盖当前 schema', async () => {
  const { h, config } = await harness([])
  const old = deferred(), latest = deferred()
  let calls = 0
  h.getDashboardRuntime = () => (++calls === 1 ? old.promise : latest.promise)
  const first = h.load()
  const second = h.load()
  latest.resolve({ data: { ...config, pageName: '最新页' } })
  await second
  const schema = h.schema.value
  old.resolve({ data: { ...config, pageName: '旧页' } })
  await first
  assert.equal(h.runtime.pageName, '最新页')
  assert.equal(h.schema.value, schema)
  assert.equal(h.loading.value, false)
})

test('全页刷新合并在途任务，等待完成才安排下一次定时且不调用 load', async () => {
  const { h, requests, clock } = await harness()
  h.load = () => assert.fail('相同配置的定时刷新不能重建页面')
  h.resetRefreshTimer()
  assert.equal(clock.timeouts.size, 1)
  assert.equal([...clock.timeouts.values()][0].delay, 5000)
  const tick = clock.fireTimeout()
  const task = h.pageRefreshTask
  assert.equal(h.refreshData(), task)
  await flush()
  assert.equal(clock.timeouts.size, 0)
  assert.equal(requests.length, 2)
  requests.forEach(({ task }, i) => task.resolve(result(i)))
  await tick
  assert.equal(clock.timeouts.size, 1)
  assert.equal(h.pageRefreshTask, undefined)
  h.resetRefreshTimer()
  assert.equal(clock.timeouts.size, 1)
})

test('独立组件定时请求未完成时去重，静态与预览页不启动轮询', async () => {
  const { h, requests, clock, widgets } = await harness([widget('one', { refreshSeconds: 5 }), widget('static', { refreshSeconds: 5, sourceType: 'STATIC' })])
  h.resetWidgetRefreshTimers(widgets)
  assert.equal(clock.intervals.size, 1)
  const timer = [...clock.intervals.values()][0]
  timer.callback(); timer.callback()
  assert.equal(requests.length, 1)
  requests[0].task.resolve(result(1))
  await flush()
  h.isPreview.value = true
  h.resetWidgetRefreshTimers(widgets)
  h.resetRefreshTimer()
  assert.equal(clock.intervals.size, 0)
  assert.equal(clock.timeouts.size, 0)
})

test('关闭页面默认自动更新仅停止默认周期，独立组件仍按自己的周期更新', async () => {
  const { h, requests, clock, widgets } = await harness([
    widget('inherited', { refreshSeconds: 0 }),
    widget('independent', { refreshSeconds: 10 }),
  ])
  h.resetRefreshTimer()
  assert.equal(clock.timeouts.size, 1)
  h.schema.value.refresh.enabled = false
  h.resetRefreshTimer()
  h.resetWidgetRefreshTimers(widgets)
  assert.equal(clock.timeouts.size, 0, '关闭开关须清理先前的默认周期')
  assert.equal(clock.intervals.size, 1)
  const timer = [...clock.intervals.values()][0]
  assert.equal(timer.delay, 10000)
  timer.callback()
  assert.deepEqual(requests.map(({ args }) => args[1]), ['independent'])
  requests[0].task.resolve(result(2))
  await flush()
  assert.equal(h.widgetStates.independent.rows[0].value, 2)
  assert.equal(h.widgetStates.inherited, undefined)
})

test('WebSocket 不加入页面或独立组件轮询，关闭默认周期仍接收推送', async () => {
  const { h, requests, clock, widgets, sockets } = await websocketHarness([
    widget('live-inherited', { refreshSeconds: 0 }),
    widget('live-independent', { refreshSeconds: 10 }),
  ])
  h.resetWidgetRefreshTimers(widgets)
  assert.equal(clock.intervals.size, 0, '实时连接不受组件轮询间隔驱动')
  await h.refreshData({ scope: 'inherited', checkConfiguration: false })
  assert.equal(requests.length, 0)
  assert.equal(sockets.length, 0, '默认周期不重建实时连接')
  h.schema.value.refresh.enabled = false
  h.resetRefreshTimer()
  assert.equal(clock.timeouts.size, 0)
  await h.loadWidget(widgets[0])
  sockets[0].open()
  sockets[0].message(result(7))
  await flush()
  assert.equal(h.widgetStates['live-inherited'].rows[0].value, 7)
  assert.equal(sockets[0].closeCount, 0)
  assert.equal(requests.length, 0)
})

test('刷新时配置确实发生变化才重载；配置校验网络错误保留当前页面', async () => {
  const { h, config } = await harness()
  let changed
  h.load = data => { changed = data }
  h.getDashboardRuntime = async () => ({ data: { ...config, revisionId: 13 } })
  await h.refreshData()
  assert.equal(changed.revisionId, 13)
  const schema = h.schema.value
  h.getDashboardRuntime = async () => { throw { requestErrorKind: 'transport', httpStatus: 503, message: '维护中' } }
  await h.refreshData()
  assert.equal(h.schema.value, schema)
  assert.match(h.refreshNotice.value, /保留/)
  assert.equal(h.error.value, '')
})

test('配置权限失效立即移除旧页面并停止数据与媒体资源', async () => {
  const { h, events } = await harness()
  h.getDashboardRuntime = async () => { throw { requestErrorKind: 'business', businessCode: 403, message: '访问已撤销' } }
  await h.refreshData()
  assert.equal(h.schema.value, null)
  assert.equal(h.error.value, '访问已撤销')
  assert.equal(h.loadGeneration, 2)
  for (const name of ['disposeCharts', 'closeSockets', 'closeCustomFrames']) assert.ok(events.some(event => event.name === name))
  assert.equal(h.widgetRequests.size, 0)
})

test('数据版本变化不写入旧画布，触发一次最新配置检查', async () => {
  const { h, requests, widgets, configurations } = await harness()
  const previous = result(8)
  h.widgetStates.one = previous
  const pending = h.refreshData({ widgets: [widgets[0]], checkConfiguration: false })
  requests[0].task.resolve({ ...result(99), revisionId: 13 })
  await pending
  assert.equal(h.widgetStates.one, previous)
  assert.equal(configurations.length, 1)
  assert.equal(h.needsConfigurationCheck, false)
})

test('WebSocket 切页后的迟到消息和错误不能污染当前页面或启动旧连接重试', async () => {
  const { h, widgets, sockets, events, clock } = await websocketHarness([widget('one')])
  await h.loadWidget(widgets[0])
  const oldSocket = sockets[0]
  oldSocket.open(); oldSocket.message(result(1)); await flush()
  h.loadGeneration++
  h.pageId = 404
  const current = result(99)
  h.widgetStates.one = current
  oldSocket.message(result(-1))
  assert.equal(h.widgetStates.one, current)
  h.closeSockets()
  await h.loadWidget(widgets[0])
  const newSocket = sockets[1]
  newSocket.open(); newSocket.message(result(100)); await flush()
  const state = h.widgetStates.one
  events.length = 0
  oldSocket.message(result(-2)); oldSocket.onerror(); oldSocket.onclose({ code: 1006 })
  await flush()
  assert.equal(h.widgetStates.one, state)
  assert.equal(state.rows[0].value, 100)
  assert.equal(h.wsConnections.one, newSocket)
  assert.equal(oldSocket.closeCount, 1)
  assert.equal(new URL(newSocket.url).searchParams.get('pageId'), '404')
  assert.equal(clock.timeouts.size, 0)
  assert.equal(events.length, 0)
})

test('WebSocket 同查询刷新保留连接并发送 refresh，筛选变化只重订阅目标组件', async () => {
  const { h, widgets, sockets, clock } = await websocketHarness([widget('one'), widget('two')])
  h.pageFilters.value = [{ id: 'project-filter', parameter: 'project', targetWidgetIds: ['one'] }]
  h.pageFilterValues['project-filter'] = ''
  await Promise.all(widgets.map(item => h.loadWidget(item)))
  const [first, second] = sockets
  for (const socket of sockets) { socket.open(); socket.message(result(1)) }
  await flush()
  await h.refreshData({ checkConfiguration: false })
  assert.equal(sockets.length, 2)
  assert.equal(first.closeCount + second.closeCount, 0)
  assert.equal(clock.timeouts.size, 2)
  for (const timer of clock.timeouts.values()) assert.equal(timer.delay, 300)
  while (clock.timeouts.size) await clock.fireTimeout()
  for (const socket of sockets) {
    assert.deepEqual(socket.sent.map(message => message.type), ['subscribe', 'refresh'])
    assert.deepEqual(socket.sent[1].params, {})
    assert.deepEqual(socket.sent[1].filters, [])
  }
  h.pageFilterValues['project-filter'] = '新项目'
  h.changePageFilter()
  await flush()
  assert.equal(sockets.length, 3)
  assert.equal(first.closeCount, 1)
  assert.equal(second.closeCount, 0)
  assert.equal(h.wsConnections.two, second)
  const replacement = sockets[2]
  replacement.open()
  assert.deepEqual(replacement.sent, [{ type: 'subscribe', params: { project: '新项目' }, filters: [] }])
  replacement.message(result(77)); await flush()
  const state = h.widgetStates.one
  first.message(result(-1)); await flush()
  assert.equal(h.widgetStates.one, state)
  assert.equal(state.rows[0].value, 77)
  assert.deepEqual(second.sent.map(message => message.type), ['subscribe', 'refresh'])
})

test('批量刷新限制并发并保持结果顺序', async () => {
  const tasks = Array.from({ length: 9 }, () => deferred())
  let active = 0, maximum = 0, started = 0
  const pending = runDashboardRefreshBatch(tasks, async (task) => {
    const index = started++
    maximum = Math.max(maximum, ++active)
    await task.promise
    active--
    return index
  }, 3)
  assert.equal(started, 3)
  for (let i = 0; i < tasks.length; i++) { tasks[i].resolve(); await flush() }
  assert.equal(maximum, 3)
  assert.deepEqual(await pending, Array.from({ length: 9 }, (_, i) => i))
})
