import test from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { buildDashboardJsonDatasetConfig, validateDashboardStaticJson } from '../src/utils/dashboardJsonDataset.js'
import { createFunctionHarness, readDashboardSfc } from './helpers/dashboard-property-audit.mjs'

const dataset = await readDashboardSfc(new URL('../src/views/dashboard/dataset/index.vue', import.meta.url).pathname)
const clone = (value) => JSON.parse(JSON.stringify(value))
const require = createRequire(import.meta.url)
const vueRequire = createRequire(require.resolve('vue'))
const { baseParse } = vueRequire('@vue/compiler-dom')

test('静态 JSON 严格解析，拒绝语法错误和非对象数组', () => {
  for (const text of ['', '[', '{"value":}', '[{"value":1},]']) {
    assert.throws(() => validateDashboardStaticJson(text), /JSON 格式不正确/)
  }
  for (const text of ['null', 'false', '42', '"文本"']) {
    assert.throws(() => validateDashboardStaticJson(text), /必须是对象或数组/)
  }
  for (const payload of [[], {}, [{ name: '计划进度', value: 64 }]]) {
    assert.deepEqual(validateDashboardStaticJson(JSON.stringify(payload)).payload, payload)
  }
})

test('数据路径按服务端对象字段读取，存在的对象或数组均可作为结果', () => {
  const payload = { data: { rows: [{ name: '甲', value: 1 }], totals: { total: 1 }, nil: null, count: 1 } }
  for (const rowsPath of ['data.rows', 'data.totals', 'data']) {
    assert.deepEqual(validateDashboardStaticJson(JSON.stringify(payload), rowsPath), { payload, rowsPath })
  }
  assert.equal(validateDashboardStaticJson(JSON.stringify(payload), '  data.rows  ').rowsPath, 'data.rows')
  for (const path of ['data.missing', 'data.nil', 'data.count', 'data.rows.0', 'constructor']) {
    assert.throws(() => validateDashboardStaticJson(JSON.stringify(payload), path), /必须指向 JSON 中存在的对象或数组/)
  }
  for (const path of ['data[0]', '.data', 'data..rows', 'data.', 'x'.repeat(129)]) {
    assert.throws(() => validateDashboardStaticJson(JSON.stringify(payload), path), /数据路径使用点分隔/)
  }
})

test('URL JSON 不解析隐藏的静态文本，保留未知配置和有效路径', () => {
  const config = { mode: 'URL', urlRef: 'registered-json', response: { totalPath: 'data.total' }, compatibility: { marker: true }, payload: [{ value: 9 }] }
  const before = clone(config)
  const result = buildDashboardJsonDatasetConfig({ configJson: config, mode: 'URL', urlRef: 'new-json', rowsPath: 'data.rows', payloadText: 'invalid hidden text' })
  assert.deepEqual(result, { ...config, urlRef: 'new-json', rowsPath: 'data.rows' })
  assert.deepEqual(config, before, '构造配置不得修改加载来源')
  assert.throws(() => buildDashboardJsonDatasetConfig({ configJson: '{}', mode: 'URL', rowsPath: 'data[0]' }), /数据路径/)
})

async function harness() {
  const writes = []
  const messages = []
  const h = await createFunctionHarness(dataset, {
    dialog: { open: true, saving: false, editing: false, form: { dataType: 'JSON', datasetCode: 'example', configJson: '{}', rowLimit: 1000 }, fields: [], params: [] },
    editor: { mode: 'STATIC', payloadText: '[]', rowsPath: '' },
    formRef: { value: { validate: (callback) => callback(true) } },
    ElMessage: { error: (text) => messages.push(['error', text]), warning: (text) => messages.push(['warning', text]), success: (text) => messages.push(['success', text]) },
    addDashboardDataset: async (data) => { writes.push(clone(data)); return { data: { datasetId: 1 } } },
    updateDashboardDataset: async (data) => { writes.push(clone(data)); return { data: 1 } },
  })
  h.load = async () => {}
  return { h, writes, messages }
}

test('实际保存回调拒绝错误 JSON、空值、坏路径及超限配置，不发出任何写请求', async () => {
  const { h, writes, messages } = await harness()
  const cases = [
    ['[{"x":}]', '', '{}'],
    ['null', '', '{}'],
    ['{"data":{}}', 'data.rows', '{}'],
    ['[]', '', '{broken'],
    ['[]', '', '[]'],
    [JSON.stringify([{ value: 'x'.repeat(512 * 1024) }]), '', '{}'],
  ]
  for (const [text, path, config] of cases) {
    Object.assign(h.editor, { payloadText: text, rowsPath: path })
    h.dialog.form.configJson = config
    await h.submit(false)
    assert.equal(h.dialog.saving, false)
    assert.equal(h.dialog.open, true)
  }
  assert.equal(writes.length, 0)
  assert.equal(messages.filter(([type]) => type === 'error').length, cases.length)
})

test('校验并格式化只调整合法内容的排版，错误时保留用户输入', async () => {
  const { h, writes, messages } = await harness()
  const raw = '{"data":{"rows":[{"value":1}]}}'
  Object.assign(h.editor, { payloadText: raw, rowsPath: ' data.rows ' })
  h.formatJsonPayload()
  assert.equal(h.editor.payloadText, JSON.stringify(JSON.parse(raw), null, 2))
  assert.equal(h.editor.rowsPath, 'data.rows')
  h.editor.payloadText = '[invalid]'
  h.formatJsonPayload()
  assert.equal(h.editor.payloadText, '[invalid]')
  assert.equal(messages.at(-1)[0], 'error')
  assert.equal(writes.length, 0)
})

test('旧静态 JSON 加载、保存、再加载保留 payload、rowsPath、字段和兼容配置', async () => {
  const { h, writes } = await harness()
  const config = { payload: { result: { records: [{ amount: 0, name: '' }] } }, rowsPath: 'result.records', compatibility: { version: 1 }, response: { totalPath: 'result.total' }, rowLimit: 50 }
  const row = { datasetId: 99, datasetCode: 'legacy-static', datasetName: '旧静态数据', dataType: 'JSON', configJson: JSON.stringify(config), fieldSchemaJson: '[{"name":"amount","title":"数量","type":"number"}]', paramSchemaJson: '[{"name":"keyword","type":"STRING"}]', refreshSeconds: 30, status: 'ACTIVE' }
  const before = clone(row)
  h.edit(row)
  assert.equal(h.editor.mode, 'STATIC')
  assert.equal(h.editor.rowsPath, 'result.records')
  await h.submit(false)
  assert.equal(writes.length, 1)
  assert.deepEqual(JSON.parse(writes[0].configJson), { ...config, mode: 'STATIC' })
  assert.equal(writes[0].fieldSchemaJson, row.fieldSchemaJson)
  assert.equal(writes[0].paramSchemaJson, row.paramSchemaJson)
  assert.deepEqual(row, before)
  h.edit(writes[0])
  assert.deepEqual(clone(h.buildConfig()), { ...config, mode: 'STATIC' })
})

test('旧数据中显式的空值和标量不在加载时静默转换为空数组', async () => {
  const { h, writes } = await harness()
  for (const payload of [null, 0, false]) {
    h.edit({ datasetId: 99, dataType: 'JSON', configJson: JSON.stringify({ payload }), fieldSchemaJson: '[]', paramSchemaJson: '[]' })
    assert.equal(h.editor.payloadText, JSON.stringify(payload))
    await h.submit(false)
  }
  assert.equal(writes.length, 0)
})

test('静态内容与校验按钮服从 JSON 模式，URL 模式隐藏无效输入', () => {
  const ast = baseParse(dataset.descriptor.template.content)
  let contentItem
  const visit = (nodes, formItem) => {
    for (const node of nodes || []) {
      if (node.type !== 1) continue
      const currentFormItem = node.tag === 'el-form-item' ? node : formItem
      if (node.tag === 'el-input' && node.props.some((prop) => prop.type === 7 && prop.name === 'model' && prop.exp?.content === 'editor.payloadText')) contentItem = currentFormItem
      visit(node.children, currentFormItem)
    }
  }
  visit(ast.children)
  assert.ok(contentItem, '静态 JSON 输入必须位于表单项中')
  const expression = contentItem.props.find((prop) => prop.type === 7 && prop.name === 'if')?.exp?.content
  assert.ok(expression)
  const visible = new Function('editor', `return ${expression}`)
  assert.equal(visible({ mode: 'STATIC' }), true)
  assert.equal(visible({ mode: 'URL' }), false)
  const texts = []
  const collectText = (nodes) => { for (const node of nodes || []) { if (node.type === 2) texts.push(node.content); collectText(node.children) } }
  collectText(contentItem.children)
  assert.ok(texts.some((text) => text.trim() === '校验并格式化'))
})
