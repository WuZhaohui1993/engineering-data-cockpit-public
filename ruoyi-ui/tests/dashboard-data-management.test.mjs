import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import vm from 'node:vm'
import { ref, reactive, computed } from 'vue'
import { parse, compileScript, compileTemplate } from 'vue/compiler-sfc'
import { dashboardDataTabs, resolveDataManagementTab, legacyDatasetDestination, dashboardCreateActions, dataManagementCreateKind } from '../src/utils/dashboardDataManagement.js'
import * as integrationPresentation from '../src/utils/dashboardIntegrationPresentation.js'

test('统一入口默认打开数据集，未获授权的页签回退到可访问工作区', () => {
  const all = dashboardDataTabs.map(tab => tab.name)
  assert.equal(resolveDataManagementTab(undefined, all), 'datasets')
  assert.equal(resolveDataManagementTab('operations', ['datasets', 'outbound']), 'datasets')
  assert.equal(resolveDataManagementTab('datasets', ['operations']), 'operations')
  assert.equal(resolveDataManagementTab('operations', []), '')
  assert.equal(resolveDataManagementTab(undefined, all, 'source'), 'outbound')
  assert.equal(new Set(all).size, all.length)
})

test('新增入口按当前页签和选中来源选择对象，纯查询页不提供新增', () => {
  assert.equal(dataManagementCreateKind('datasets'), 'dataset')
  assert.equal(dataManagementCreateKind('outbound'), 'source')
  assert.equal(dataManagementCreateKind('outbound', true), 'endpoint')
  assert.equal(dataManagementCreateKind('inbound'), 'integration')
  for (const tab of ['batches', 'dead', 'operations']) assert.equal(dataManagementCreateKind(tab), '')
})

test('同一个新增按钮切换对象时重新检查权限，不沿用数据集编辑权限创建接入方', async () => {
  const filename = new URL('../src/components/DashboardDataManagement/CreateButton.vue', import.meta.url).pathname
  const { descriptor } = parse(await fs.readFile(filename, 'utf8'))
  const props = reactive({ kind: 'dataset', disabled: false })
  const context = vm.createContext({ computed, dashboardCreateActions, defineProps: () => props, defineEmits: () => {}, checkPermi: permissions => permissions.includes('dashboard:dataset:edit') })
  vm.runInContext(descriptor.scriptSetup.content.replace(/^import .*?;\s*$/gm, '') + '\nglobalThis.state={action,canCreate};', context)
  assert.equal(context.state.canCreate.value, true)
  props.kind = 'integration'
  assert.equal(context.state.canCreate.value, false)
  props.kind = 'source'
  assert.equal(context.state.canCreate.value, true)
  assert.equal(context.state.action.value.label, '新增数据源')
})

test('旧数据集链接进入统一页签并保留创建意图、筛选及锚点', () => {
  const query = { focus: 'create', keyword: '考勤', pageNum: '2' }
  const original = structuredClone(query)
  assert.deepEqual(legacyDatasetDestination(query, '#fields'), {
    path: '/dashboard/integration', query: { ...query, tab: 'datasets' }, hash: '#fields', replace: true,
  })
  assert.equal(legacyDatasetDestination({ focus: 'source' }).query.tab, 'outbound')
  assert.deepEqual(query, original)
})

test('统一入口、内嵌数据集、操作说明和运维模板以真实脚本绑定编译', async () => {
  for (const path of ['views/dashboard/integration/index.vue', 'views/dashboard/dataset/index.vue', 'components/DashboardManagement/Help.vue', 'views/dashboard/integration/components/IntegrationOperations.vue']) {
    const filename = new URL('../src/' + path, import.meta.url).pathname
    const { descriptor, errors } = parse(await fs.readFile(filename, 'utf8'))
    assert.deepEqual(errors, [])
    const script = compileScript(descriptor, { id: path })
    assert.deepEqual(compileTemplate({ source: descriptor.template.content, filename, id: path, compilerOptions: { bindingMetadata: script.bindings } }).errors, [])
  }
})

test('运维切换到审计时使用第一页，迟到的告警响应不覆盖审计记录', async () => {
  const filename = new URL('../src/views/dashboard/integration/components/IntegrationOperations.vue', import.meta.url).pathname
  const { descriptor } = parse(await fs.readFile(filename, 'utf8'))
  let finishAlert
  const queries = []
  const context = vm.createContext({
    ...integrationPresentation, ref, reactive, computed,
    onMounted: () => {}, onBeforeUnmount: () => {}, defineExpose: () => {}, checkPermi: () => true,
    getIntegrationMetrics: async () => ({ data: { pending: 0 } }),
    listIntegrationOperationResources: async () => ({ data: [] }),
    getIntegrationWebSocketStatus: async () => ({ data: { connections: [] } }),
    listIntegrationAlerts: () => new Promise(resolve => { finishAlert = resolve }),
    listIntegrationAudits: async params => { queries.push(params); return { rows: [{ auditId: 8 }], total: 1 } },
  })
  vm.runInContext(descriptor.scriptSetup.content.replace(/^import .*?;\s*$/gm, '') + '\nglobalThis.state={load,changeTab,tab,query,rows,total};', context)
  const state = context.state
  state.query.pageNum = 4
  const first = state.load()
  state.tab.value = 'audits'
  await state.changeTab()
  assert.equal(queries[0].pageNum, 1)
  finishAlert({ rows: [{ alertId: 1 }], total: 40 })
  await first
  assert.equal(state.rows.value[0].auditId, 8)
  assert.equal(state.total.value, 1)
})
