import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import vm from 'node:vm'
import { computed, reactive, ref, watch } from 'vue'
import { parse, compileScript, compileTemplate } from 'vue/compiler-sfc'
import * as retention from '../src/utils/dashboardIntegrationRetention.js'
import { auditCategoryLabel, integrationResultLabel } from '../src/utils/dashboardIntegrationPresentation.js'
import * as dataManagement from '../src/utils/dashboardDataManagement.js'

const filename = new URL('../src/views/dashboard/integration/index.vue', import.meta.url).pathname
const { descriptor, errors } = parse(await fs.readFile(filename, 'utf8'))

function createPageState() {
  const payloads = []
  const warnings = []
  const context = vm.createContext({
    ...retention, ...dataManagement, computed, reactive, ref, watch,
    onMounted: () => {}, useRoute: () => ({ query: {} }), useRouter: () => ({}),
    checkPermi: () => true, Refresh: {},
    ElMessage: { warning: message => warnings.push(message), success: () => {} },
    addDashboardIntegration: async payload => payloads.push(payload),
    updateDashboardIntegration: async payload => payloads.push(payload),
    pageDashboardIntegrations: async () => ({ rows: [], total: 0 }),
  })
  vm.runInContext(descriptor.scriptSetup.content.replace(/^import[\s\S]*?from\s+['"][^'"]+['"];\s*$/gm, '')
    + '\nglobalThis.state={integrationDialog,integrationRetention,integrationFormRef,integrationRules,openIntegrationCreate,openIntegrationEdit,setIntegrationRetention,submitIntegration};', context)
  context.state.integrationFormRef.value = { validate: callback => callback(true), clearValidate: () => {} }
  return { state: context.state, payloads, warnings }
}

test('新增接入默认压缩30天成功明细，编辑缺项的旧接入仍长期保留且不补写策略', async () => {
  const { state, payloads } = createPageState()
  state.openIntegrationCreate()
  assert.equal(state.integrationRetention.value.successRetentionDays, 30)
  assert.equal(state.integrationRetention.value.recordRetentionDays, 0)
  assert.equal(state.integrationRetention.value.retainRawBody, false)
  state.openIntegrationEdit({ integrationId: 12, configJson: '{"maxAttempts":3}' })
  assert.equal(state.integrationRetention.value.successRetentionDays, 0)
  assert.equal(state.integrationRetention.value.recordRetentionDays, 0)
  state.submitIntegration()
  assert.deepEqual(JSON.parse(payloads[0].configJson), { maxAttempts: 3 })
  await Promise.resolve()
})

test('可视控件和手写JSON双向同步，提交使用最后编辑值并保留其他策略', async () => {
  const { state, payloads } = createPageState()
  state.openIntegrationEdit({ integrationId: 12, configJson: '{"allowedOrigins":["https://example.com"],"nested":{"unchanged":true}}' })
  state.setIntegrationRetention('successRetentionDays', 7)
  state.setIntegrationRetention('retainRawBody', true)
  let config = JSON.parse(state.integrationDialog.form.configJson)
  assert.equal(config.successRetentionDays, 7)
  assert.equal(config.retainRawBody, true)
  assert.deepEqual(config.nested, { unchanged: true })
  config = { ...config, successRetentionDays: 14, recordRetentionDays: 90, retainRawBody: false }
  state.integrationDialog.form.configJson = JSON.stringify(config)
  assert.equal(state.integrationRetention.value.successRetentionDays, 14)
  assert.equal(state.integrationRetention.value.recordRetentionDays, 90)
  assert.equal(state.integrationRetention.value.retainRawBody, false)
  state.submitIntegration()
  assert.deepEqual(JSON.parse(payloads[0].configJson), config)
  await Promise.resolve()
})

test('空对象和缺项兼容为0，显式0保留；null、空串、字符串数字、小数、负数均拒绝', () => {
  for (const value of [undefined, '', '{}', {}]) {
    const state = retention.inspectIntegrationRetention(value)
    assert.equal(state.successRetentionDays, 0)
    assert.equal(state.recordRetentionDays, 0)
  }
  for (const key of ['successRetentionDays', 'recordRetentionDays']) {
    for (const value of [0, 1, 3650]) {
      assert.equal(retention.validateIntegrationRetention({ [key]: value })[key], value)
    }
    for (const value of [null, '', '30', false, -1, 0.5, 3651]) {
      assert.throws(() => retention.validateIntegrationRetention({ [key]: value }), /0 至 3650 的整数/)
    }
  }
  for (const value of [undefined, null, '', '30', -1, 0.5, 3651]) {
    assert.throws(() => retention.integrationRetentionDays(value, 'rawRetentionDays'), /0 至 3650 的整数/)
  }
  assert.equal(retention.integrationRetentionDays(0, 'rawRetentionDays'), 0)
})

test('非法JSON不被控件覆盖，字段校验和保存均拒绝非法保留期', () => {
  const { state, payloads, warnings } = createPageState()
  state.integrationDialog.form.configJson = '{"successRetentionDays":'
  state.setIntegrationRetention('successRetentionDays', 7)
  assert.equal(state.integrationDialog.form.configJson, '{"successRetentionDays":')
  assert.ok(state.integrationRetention.value.parseError)
  state.submitIntegration()
  assert.equal(payloads.length, 0)
  assert.equal(warnings.length, 2)
  for (const config of ['[]', 'null', '{"successRetentionDays":-1}', '{"recordRetentionDays":1.5}']) {
    let error
    state.integrationRules.configJson[0].validator({}, config, value => { error = value })
    assert.ok(error)
    state.integrationDialog.form.configJson = config
    state.submitIntegration()
    assert.equal(payloads.length, 0)
  }
  state.integrationDialog.form.configJson = '{}'
  state.integrationDialog.form.rawRetentionDays = undefined
  state.submitIntegration()
  assert.equal(payloads.length, 0)
})

test('详情区分不保存原文与长期保留，原文期限不被成功保留期覆盖', () => {
  const row = { rawRetentionDays: 30, configJson: { retainRawBody: true, successRetentionDays: 7 } }
  assert.equal(retention.integrationRetentionSummary(row, 'rawRetentionDays'), '30 天')
  assert.equal(retention.integrationRetentionSummary(row, 'successRetentionDays'), '7 天')
  assert.equal(retention.integrationRetentionSummary(row, 'recordRetentionDays'), '长期保留')
  assert.equal(retention.integrationRetentionSummary({ ...row, rawRetentionDays: 0 }, 'rawRetentionDays'), '不保存')
  const before = structuredClone(row)
  retention.updateIntegrationRetention(row.configJson, 'successRetentionDays', 0)
  assert.deepEqual(row, before)
})

test('已清理批次通过明确摘要标记识别，不能把正常空列表当作已清理', () => {
  assert.equal(retention.integrationBatchDetailsExpired(null), false)
  assert.equal(retention.integrationBatchDetailsExpired({ items: [] }), false)
  assert.equal(retention.integrationBatchDetailsExpired({ detailExpired: true, items: [] }), true)
  assert.equal(retention.integrationBatchDetailsExpired({ detailsExpiredAt: '2026-09-10 12:00:00' }), true)
  assert.equal(retention.integrationBatchDetailsExpired({ detailExpired: false, detailsExpiredAt: null }), false)
  assert.equal(auditCategoryLabel('INBOUND_RETENTION'), '保留期清理')
  assert.equal(integrationResultLabel('CLEANED'), '清理完成（CLEANED）')
})

test('保留配置和过期摘要模板以真实脚本绑定编译', () => {
  assert.deepEqual(errors, [])
  const script = compileScript(descriptor, { id: filename })
  assert.deepEqual(compileTemplate({ source: descriptor.template.content, filename, id: filename, compilerOptions: { bindingMetadata: script.bindings } }).errors, [])
})
