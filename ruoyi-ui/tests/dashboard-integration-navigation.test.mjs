import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import vm from 'node:vm'
import { computed, reactive, ref, watch, nextTick } from 'vue'
import { parse, compileScript, compileTemplate } from 'vue/compiler-sfc'
import * as navigation from '../src/utils/dashboardDataManagement.js'
import * as retention from '../src/utils/dashboardIntegrationRetention.js'

const filename = new URL('../src/views/dashboard/integration/index.vue', import.meta.url).pathname
const { descriptor, errors } = parse(await fs.readFile(filename, 'utf8'))
const checkFor = permissions => requested => requested.some(permission => permissions.includes(permission))

function createPageState(permissions, query = {}) {
  const calls = { folders: 0, integrations: 0, options: 0, batches: 0, dead: 0 }
  const route = reactive({ query: { ...query }, hash: '#test' })
  const replacements = []
  let mount
  const context = vm.createContext({
    ...navigation, ...retention, computed, reactive, ref, watch,
    onMounted: callback => { mount = callback }, useRoute: () => route,
    useRouter: () => ({ replace: target => { replacements.push(target); route.query = { ...target.query } } }),
    checkPermi: checkFor(permissions), Refresh: {},
    listDataFolders: async () => { calls.folders++; return { data: [] } },
    pageDashboardIntegrations: async () => { calls.integrations++; return { rows: [], total: 0 } },
    listDashboardIntegrations: async () => { calls.options++; return { data: [] } },
    pageDashboardIntegrationBatches: async () => { calls.batches++; return { rows: [], total: 0 } },
    pageDashboardIntegrationDeadLetters: async () => { calls.dead++; return { rows: [], total: 0 } },
  })
  vm.runInContext(descriptor.scriptSetup.content.replace(/^import[\s\S]*?from\s+['"][^'"]+['"];\s*$/gm, '')
    + '\nglobalThis.state={activeTab,activeInboundTab,allowedTabs,allowedInboundTabs,canInbound,canConfigureInbound,handleNavigationChange};', context)
  return { state: context.state, route, calls, replacements, mount }
}

async function settle() {
  await nextTick()
  await Promise.resolve()
  await nextTick()
}

test('一级导航只有四个入口，推送二级页与模板层级一致', () => {
  assert.deepEqual(navigation.dashboardDataTabs.map(tab => tab.name), ['datasets', 'outbound', 'inbound', 'operations'])
  const ast = descriptor.template.ast
  const find = (node, tag) => node.tag === tag ? node : node.children?.map(child => find(child, tag)).find(Boolean)
  const name = node => node.props.find(prop => prop.name === 'name')?.value?.content
  const main = find(ast, 'el-tabs')
  const panes = main.children.filter(node => node.tag === 'el-tab-pane')
  assert.deepEqual(panes.map(name), ['datasets', 'outbound', 'inbound', 'operations'])
  const inbound = find(panes.find(pane => name(pane) === 'inbound'), 'el-tabs')
  assert.deepEqual(inbound.children.filter(node => node.tag === 'el-tab-pane').map(name), ['integrations', 'batches', 'dead'])
})

test('只读台账或死信权限可看到父入口，配置列表与新增权限不被扩大', async () => {
  for (const [permission, selected, request] of [
    ['dashboard:integration:view', 'batches', 'batches'],
    ['dashboard:integration:deadletter', 'dead', 'dead'],
  ]) {
    const page = createPageState([permission])
    assert.deepEqual([...page.state.allowedTabs.value], ['inbound'])
    assert.deepEqual([...page.state.allowedInboundTabs.value], [selected])
    assert.equal(page.state.canInbound.value, true)
    assert.equal(page.state.canConfigureInbound.value, false)
    assert.equal(page.state.activeTab.value, 'inbound')
    assert.equal(page.state.activeInboundTab.value, selected)
    page.mount()
    await settle()
    assert.equal(page.calls[request], 1)
    assert.equal(page.calls.integrations, 0)
    assert.equal(page.calls.folders, 0)
    assert.equal(page.calls.options, 0)
    assert.equal(navigation.dataManagementCreateKind('inbound', false, selected), '')
    assert.equal(checkFor([permission])([navigation.dashboardCreateActions.integration.permission]), false)
  }
})

test('旧台账与死信链接进入对应二级页，保留筛选及锚点且只查询一次', async () => {
  for (const tab of ['batches', 'dead']) {
    const page = createPageState(['dashboard:integration:view', 'dashboard:integration:deadletter'], { tab, keyword: 'trace' })
    page.mount()
    await settle()
    assert.equal(page.state.activeTab.value, 'inbound')
    assert.equal(page.state.activeInboundTab.value, tab)
    assert.deepEqual({ ...page.route.query }, { tab: 'inbound', inboundTab: tab, keyword: 'trace' })
    assert.equal(page.replacements[0].hash, '#test')
    assert.equal(page.calls[tab], 1)
  }
})

test('二级页切换更新链接，浏览器返回旧链接恢复台账，越权子页回退到已授权页面', async () => {
  const page = createPageState(['dashboard:integration:view', 'dashboard:integration:deadletter'], { tab: 'batches' })
  page.mount()
  await settle()
  page.state.activeInboundTab.value = 'dead'
  await settle()
  assert.equal(page.route.query.inboundTab, 'dead')
  assert.equal(page.calls.dead, 1)
  page.route.query = { tab: 'batches' }
  await settle()
  assert.equal(page.state.activeInboundTab.value, 'batches')
  assert.equal(page.calls.batches, 2)
  const restricted = createPageState(['dashboard:integration:deadletter'], { tab: 'inbound', inboundTab: 'integrations' })
  restricted.mount()
  await settle()
  assert.equal(restricted.state.activeInboundTab.value, 'dead')
  assert.equal(restricted.calls.integrations, 0)
})

test('无推送权限不会出现父入口，离开推送时清除二级参数', () => {
  assert.deepEqual(navigation.allowedDataManagementTabs(checkFor(['dashboard:dataset:list'])), ['datasets', 'outbound'])
  assert.deepEqual(navigation.allowedDataManagementTabs(checkFor(['dashboard:integration:monitor'])), ['operations'])
  assert.deepEqual(navigation.allowedDataManagementTabs(checkFor([])), [])
  assert.deepEqual(navigation.dataManagementTabQuery({ tab: 'inbound', inboundTab: 'dead', keyword: '保留', focus: 'source' }, 'outbound', 'dead'), { tab: 'outbound', keyword: '保留' })
  assert.equal(navigation.resolveDataManagementTab('batches', ['datasets', 'outbound']), 'datasets')
})

test('嵌套导航模板以实际脚本绑定编译', () => {
  assert.deepEqual(errors, [])
  const script = compileScript(descriptor, { id: filename })
  assert.deepEqual(compileTemplate({ source: descriptor.template.content, filename, id: filename, compilerOptions: { bindingMetadata: script.bindings } }).errors, [])
})
