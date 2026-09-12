import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import vm from 'node:vm'
import { computed, reactive, ref } from 'vue'
import { parse, compileScript, compileTemplate } from 'vue/compiler-sfc'
import * as presentation from '../src/utils/dashboardIntegrationPresentation.js'

const componentPaths = ['IntegrationOperations.vue', 'IntegrationOperationsPolicy.vue'].map(name =>
  new URL(`../src/views/dashboard/integration/components/${name}`, import.meta.url).pathname)
const components = await Promise.all(componentPaths.map(async filename => {
  const source = await fs.readFile(filename, 'utf8')
  return { filename, source, ...parse(source) }
}))

function plain(value) { return JSON.parse(JSON.stringify(value)) }
function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

function createState(index = 0, overrides = {}) {
  const calls = [], messages = [], permissions = ref(['dashboard:integration:monitor'])
  const defaults = {
    getIntegrationMetrics: async () => ({ data: {} }),
    listIntegrationAudits: async () => ({ rows: [], total: 0 }),
    listIntegrationAlerts: async () => ({ rows: [], total: 0 }),
    acknowledgeIntegrationAlert: async () => ({}),
    listIntegrationOperationResources: async () => ({ data: [] }),
    getIntegrationWebSocketStatus: async () => ({ data: { connections: [] } }),
    getIntegrationOperationsPolicy: async () => ({ data: { auditRetentionDays: 30, resolvedAlertRetentionDays: 30, testLogRetentionDays: 30, testResponseBodyStored: false } }),
    updateIntegrationOperationsPolicy: async value => ({ data: { ...value, testResponseBodyStored: false } }),
  }
  const apis = Object.fromEntries(Object.entries({ ...defaults, ...overrides }).map(([name, action]) => [name, (...args) => {
    calls.push({ name, args: plain(args) })
    return action(...args)
  }]))
  let dispose
  const context = vm.createContext({
    ...presentation, ...apis, computed, reactive, ref,
    checkPermi: requested => permissions.value.some(value => value === '*:*:*' || requested.includes(value)),
    onMounted: () => {}, onBeforeUnmount: callback => { dispose = callback },
    setInterval: () => 1, clearInterval: () => {}, defineExpose: () => {},
    ElMessage: { success: message => messages.push(message), warning: message => messages.push(message) },
  })
  const names = index === 0
    ? 'query,tab,rows,total,metrics,resources,websockets,loading,autoRefresh,loadError,lastUpdated,resourceOptions,selectedResource,showInboundMetrics,showConnections,overviewTitle,visibleRequestMetrics,connectionRows,connectionTotal,load,loadResources,refresh,search,changeCategory,changeResource,reset,changeTab,paginateConnections,acknowledge,displayResource'
    : 'form,formRef,saved,canEdit,dirty,loading,saving,loaded,loadError,rules,load,save'
  vm.runInContext(components[index].descriptor.scriptSetup.content.replace(/^import[\s\S]*?from\s+['"][^'"]+['"];\s*$/gm, '')
    + `\nglobalThis.state={${names}};`, context)
  if (index === 1) context.state.formRef.value = { clearValidate: () => {}, validate: async () => true }
  return { state: context.state, calls, messages, permissions, dispose: () => dispose?.() }
}

test('接入方式覆盖所有审计分类，同码来源按分类隔离，保留期和媒体筛选对应业务来源', () => {
  assert.deepEqual(presentation.integrationOperationCategories.map(item => item.value),
    ['OUTBOUND', 'INBOUND', 'WEBSOCKET', 'MEDIA', 'INBOUND_RETENTION'])
  assert.equal(presentation.auditCategoryLabel('WEBSOCKET'), '实时连接')
  const rows = [{ category: 'OUTBOUND', resourceCode: 'same' }, { category: 'INBOUND', resourceCode: 'same' },
    { category: 'MEDIA', resourceCode: 'same' }, { category: 'INBOUND_RETENTION', resourceCode: 'same' }]
  assert.notEqual(presentation.integrationOperationResourceKey(rows[0]), presentation.integrationOperationResourceKey(rows[1]))
  assert.deepEqual(presentation.integrationOperationResources(rows, 'INBOUND_RETENTION'), [rows[3]])
  assert.deepEqual(presentation.integrationOperationResources(rows, 'MEDIA'), [rows[2]])
})

test('选来源同步分类与指标请求，切换类型清除旧来源并重置告警和审计分页', async () => {
  const { state, calls } = createState()
  const rows = [{ category: 'OUTBOUND', resourceCode: 'same', resourceName: '接口来源' },
    { category: 'INBOUND', resourceCode: 'same', resourceName: '推送来源' }]
  state.resources.value = rows
  state.query.pageNum = 4
  await state.changeResource(presentation.integrationOperationResourceKey(rows[1]))
  assert.equal(state.query.category, 'INBOUND')
  assert.equal(state.query.resourceCode, 'same')
  assert.equal(state.query.pageNum, 1)
  assert.equal(state.showInboundMetrics.value, true)
  assert.equal(state.displayResource('same', 'INBOUND'), '推送来源（same）')
  assert.deepEqual(calls.find(call => call.name === 'getIntegrationMetrics').args[0], { category: 'INBOUND', resourceCode: 'same' })
  for (const tab of ['alerts', 'audits']) {
    state.tab.value = tab
    state.query.pageNum = 4
    state.query.category = 'OUTBOUND'
    await state.changeCategory()
    assert.equal(state.query.pageNum, 1)
    assert.equal(state.query.resourceCode, '')
    assert.equal(state.showInboundMetrics.value, false)
    const latest = calls.filter(call => call.name === (tab === 'alerts' ? 'listIntegrationAlerts' : 'listIntegrationAudits')).at(-1)
    assert.equal(latest.args[0].category, 'OUTBOUND')
    assert.equal(latest.args[0].resourceCode, undefined)
  }
})

test('切换来源后迟到的指标和列表响应不会覆盖新来源', async () => {
  const a = deferred(), b = deferred()
  const { state } = createState(0, {
    getIntegrationMetrics: async params => ({ data: { records: params.resourceCode === 'b' ? 2 : 1 } }),
    listIntegrationAlerts: params => params.resourceCode === 'a' ? a.promise : b.promise,
  })
  const aSource = { category: 'INBOUND', resourceCode: 'a' }, bSource = { category: 'INBOUND', resourceCode: 'b' }
  state.resources.value = [aSource, bSource]
  const first = state.changeResource(presentation.integrationOperationResourceKey(aSource))
  const second = state.changeResource(presentation.integrationOperationResourceKey(bSource))
  b.resolve({ rows: [{ alertId: 2 }], total: 1 })
  await second
  assert.equal(state.rows.value[0].alertId, 2)
  a.resolve({ rows: [{ alertId: 1 }], total: 99 })
  await first
  assert.equal(state.rows.value[0].alertId, 2)
  assert.equal(state.metrics.value.records, 2)
  assert.equal(state.total.value, 1)
  assert.equal(state.loading.value, false)
})

test('日志清理导致当前页越界时回到最后一页，继续携带来源筛选', async () => {
  const { state, calls } = createState(0, {
    listIntegrationAudits: async params => ({ rows: params.pageNum === 2 ? [{ auditId: 21 }] : [], total: 21 }),
  })
  state.tab.value = 'audits'
  Object.assign(state.query, { pageNum: 3, category: 'INBOUND_RETENTION', resourceCode: 'goods' })
  await state.load()
  assert.equal(state.query.pageNum, 2)
  assert.equal(state.rows.value[0].auditId, 21)
  assert.deepEqual(calls.filter(call => call.name === 'listIntegrationAudits').map(call => call.args[0]), [
    { pageNum: 3, pageSize: 20, category: 'INBOUND_RETENTION', resourceCode: 'goods' },
    { pageNum: 2, pageSize: 20, category: 'INBOUND_RETENTION', resourceCode: 'goods' },
  ])
})

test('分页回退途中切换分类，旧回退请求不能改写新分类页码', async () => {
  const fallback = deferred()
  const { state } = createState(0, {
    listIntegrationAlerts: params => {
      if (params.category === 'OUTBOUND') return Promise.resolve({ rows: [{ alertId: 50 }], total: 1 })
      if (params.pageNum === 3) return Promise.resolve({ rows: [], total: 21 })
      return fallback.promise
    },
  })
  state.query.pageNum = 3
  const old = state.load()
  await new Promise(resolve => setImmediate(resolve))
  state.query.category = 'OUTBOUND'
  await state.changeCategory()
  fallback.resolve({ rows: [{ alertId: 21 }], total: 21 })
  await old
  assert.equal(state.query.pageNum, 1)
  assert.equal(state.rows.value[0].alertId, 50)
  assert.equal(state.query.category, 'OUTBOUND')
})

test('连接列表仅在全部或实时连接类型显示，来源切换退出不相关连接页签', async () => {
  const { state, calls } = createState(0, {
    getIntegrationWebSocketStatus: async () => ({ data: { connectionCount: 21, connectedCount: 20,
      connections: Array.from({ length: 21 }, (_, index) => ({ connectionId: String(index), resourceCode: 'weather', status: 'CONNECTED' })) } }),
  })
  Object.assign(state.query, { category: 'WEBSOCKET', resourceCode: 'weather' })
  state.tab.value = 'connections'
  await state.load()
  assert.equal(state.connectionTotal.value, 21)
  assert.equal(state.connectionRows.value.length, 20)
  state.query.pageNum = 2
  state.paginateConnections()
  assert.equal(state.connectionRows.value.length, 1)
  state.query.pageNum = 3
  state.paginateConnections()
  assert.equal(state.query.pageNum, 2)
  assert.deepEqual(calls.find(call => call.name === 'getIntegrationWebSocketStatus').args[0], { resourceCode: 'weather' })
  state.query.category = 'OUTBOUND'
  await state.changeCategory()
  assert.equal(state.showConnections.value, false)
  assert.equal(state.tab.value, 'alerts')
  assert.equal(state.query.pageNum, 1)
  assert.equal(calls.filter(call => call.name === 'getIntegrationWebSocketStatus').length, 1)
})

test('连接事件和清理次数按独立统计展示，不把它们误标为HTTP请求数', () => {
  const { state } = createState()
  state.query.category = 'WEBSOCKET'
  assert.equal(state.overviewTitle.value, '连接活动')
  assert.ok(state.visibleRequestMetrics.value.some(item => item.key === 'websocketEvents24h'))
  assert.ok(!state.visibleRequestMetrics.value.some(item => item.key === 'requests24h'))
  state.query.category = 'INBOUND_RETENTION'
  assert.equal(state.overviewTitle.value, '清理活动')
  assert.deepEqual(plain(state.visibleRequestMetrics.value).map(item => item.key), ['retentionRuns24h'])
})

test('告警确认不能只依赖按钮隐藏，处理函数也校验权限', async () => {
  const { state, calls, permissions } = createState()
  await state.acknowledge({ alertId: 12 })
  assert.equal(calls.filter(call => call.name === 'acknowledgeIntegrationAlert').length, 0)
  permissions.value = ['dashboard:integration:ack']
  await state.acknowledge({ alertId: 12 })
  assert.equal(calls.filter(call => call.name === 'acknowledgeIntegrationAlert').length, 1)
})

test('组件销毁后迟到响应不写回，失败刷新明确保留最近结果', async () => {
  const pending = deferred()
  const { state, dispose } = createState(0, { listIntegrationAlerts: () => pending.promise })
  const loading = state.load()
  dispose()
  pending.resolve({ rows: [{ alertId: 12 }], total: 1 })
  await loading
  assert.equal(state.rows.value.length, 0)
  const failed = createState(0, { listIntegrationAlerts: async () => { throw new Error('unavailable') } })
  failed.state.rows.value = [{ alertId: 99 }]
  failed.state.lastUpdated.value = new Date()
  await failed.state.load()
  assert.equal(failed.state.rows.value[0].alertId, 99)
  assert.match(failed.state.loadError.value, /最近一次成功结果/)
})

test('只有监控权限时可以读策略，控件只读且直接调用保存也不会发写请求', async () => {
  const { state, calls } = createState(1)
  await state.load()
  assert.equal(state.loaded.value, true)
  assert.equal(state.canEdit.value, false)
  state.form.auditRetentionDays = 7
  await state.save()
  assert.equal(calls.filter(call => call.name === 'getIntegrationOperationsPolicy').length, 1)
  assert.equal(calls.filter(call => call.name === 'updateIntegrationOperationsPolicy').length, 0)
})

test('保留策略只提交三个整数，正文不保存字段只读且不发送', async () => {
  const { state, calls, permissions } = createState(1)
  permissions.value = ['dashboard:integration:policy']
  await state.load()
  Object.assign(state.form, { auditRetentionDays: 7, resolvedAlertRetentionDays: 90, testLogRetentionDays: 1 })
  assert.equal(state.dirty.value, true)
  await state.save()
  assert.deepEqual(calls.find(call => call.name === 'updateIntegrationOperationsPolicy').args[0],
    { auditRetentionDays: 7, resolvedAlertRetentionDays: 90, testLogRetentionDays: 1 })
  assert.equal(state.dirty.value, false)
  assert.equal(state.saved.value.auditRetentionDays, 7)
  assert.equal(Object.hasOwn(state.form, 'testResponseBodyStored'), false)
})

test('保留策略拒绝零、空值、字符串、越界和小数，保存失败不丢用户修改', async () => {
  const { state, calls, permissions } = createState(1, {
    updateIntegrationOperationsPolicy: async () => { throw new Error('save failed') },
  })
  permissions.value = ['dashboard:integration:policy']
  await state.load()
  for (const key of ['auditRetentionDays', 'resolvedAlertRetentionDays', 'testLogRetentionDays']) {
    for (const value of [0, -1, 3651, null, undefined, '', '30', 1.5]) {
      state.form[key] = value
      await state.save()
      assert.equal(calls.filter(call => call.name === 'updateIntegrationOperationsPolicy').length, 0)
    }
    state.form[key] = 30
  }
  state.form.auditRetentionDays = 7
  await state.save()
  assert.equal(state.form.auditRetentionDays, 7)
  assert.equal(state.saved.value.auditRetentionDays, 30)
  assert.equal(state.dirty.value, true)
  assert.equal(state.saving.value, false)
  assert.match(state.loadError.value, /保存失败/)
})

test('策略读取失败不能使用默认值误覆盖服务器；校验过程中权限失效不再提交', async () => {
  const failed = createState(1, { getIntegrationOperationsPolicy: async () => { throw new Error('read failed') } })
  failed.permissions.value = ['dashboard:integration:policy']
  await failed.state.load()
  await failed.state.save()
  assert.equal(failed.state.loaded.value, false)
  assert.equal(failed.calls.filter(call => call.name === 'updateIntegrationOperationsPolicy').length, 0)
  const fixture = createState(1), validation = deferred()
  fixture.permissions.value = ['dashboard:integration:policy']
  await fixture.state.load()
  fixture.state.formRef.value.validate = () => validation.promise
  const saving = fixture.state.save()
  fixture.permissions.value = ['dashboard:integration:monitor']
  validation.resolve(true)
  await saving
  assert.equal(fixture.calls.filter(call => call.name === 'updateIntegrationOperationsPolicy').length, 0)
})

test('状态与字节数显示不暴露正文，两个组件均使用真实脚本绑定编译', () => {
  assert.equal(presentation.integrationConnectionStatus('RECONNECTING'), '重连中')
  assert.equal(presentation.integrationConnectionTag('ERROR'), 'danger')
  assert.equal(presentation.formatIntegrationBytes(0), '0 B')
  assert.equal(presentation.formatIntegrationBytes(1536), '1.5 KB')
  for (const { filename, descriptor, errors } of components) {
    assert.deepEqual(errors, [])
    const script = compileScript(descriptor, { id: filename })
    assert.deepEqual(compileTemplate({ source: descriptor.template.content, filename, id: filename,
      compilerOptions: { bindingMetadata: script.bindings } }).errors, [])
  }
  assert.doesNotMatch(components[0].descriptor.template.content, /row\.(?:uri|url|payload|headers|subscription|secret)\b/)
  assert.match(components[1].descriptor.template.content, /v-hasPermi="\['dashboard:integration:policy'\]"/)
  assert.match(components[1].descriptor.template.content, /历史测试日志按配置期限清理/)
})

test('运维API助手透传筛选，保留策略写入使用PUT', async () => {
  const source = await fs.readFile(new URL('../src/api/dashboardIntegration.js', import.meta.url), 'utf8')
  const calls = [], context = vm.createContext({ request: config => { calls.push(plain(config)); return Promise.resolve(config) } })
  vm.runInContext(source.replace(/^import .*\n/gm, '').replace(/export function /g, 'function ')
    + '\nglobalThis.api={getIntegrationMetrics,listIntegrationAudits,listIntegrationAlerts,getIntegrationWebSocketStatus,updateIntegrationOperationsPolicy};', context)
  await context.api.getIntegrationMetrics({ category: 'WEBSOCKET', resourceCode: 'weather' })
  await context.api.listIntegrationAudits({ category: 'INBOUND_RETENTION', pageNum: 2 })
  await context.api.updateIntegrationOperationsPolicy({ auditRetentionDays: 7, resolvedAlertRetentionDays: 30, testLogRetentionDays: 30 })
  assert.deepEqual(calls[0].params, { category: 'WEBSOCKET', resourceCode: 'weather' })
  assert.equal(calls[1].params.pageNum, 2)
  assert.equal(calls[2].method, 'put')
  assert.equal(calls[2].url, '/dashboard/integration/operations/policy')
})
