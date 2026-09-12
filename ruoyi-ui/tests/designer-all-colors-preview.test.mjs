import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import vm from 'node:vm'
import { randomUUID } from 'node:crypto'
import { createRequire } from 'node:module'
import * as Vue from 'vue'
import { createFunctionHarness, designerPath, readDashboardSfc, setPath } from './helpers/dashboard-property-audit.mjs'

const require = createRequire(import.meta.url)
const compilerRequire = createRequire(createRequire(require.resolve('vue')).resolve('@vue/compiler-sfc'))
const { parse, compileScript } = compilerRequire('@vue/compiler-sfc')
const { baseParse, compile } = compilerRequire('@vue/compiler-dom')
const designer = await readDashboardSfc(designerPath)
const filename = new URL('../src/views/dashboard/designer/DesignerColorPicker.vue', import.meta.url)
const { descriptor } = parse(await fs.readFile(filename, 'utf8'), { filename: filename.pathname })
const compiled = compileScript(descriptor, { id: 'designer-all-colors-preview-test', inlineTemplate: true })
const temporary = new URL(`.designer-all-colors-preview-${process.pid}-${randomUUID()}.mjs`, import.meta.url)
await fs.writeFile(temporary, compiled.content)
let DesignerColorPicker
try { DesignerColorPicker = (await import(temporary.href)).default } finally { await fs.unlink(temporary) }

const fields = []
function findPickers(node) {
  if (node.tag === 'DesignerColorPicker' || node.tag === 'el-color-picker') {
    const binding = node.props.find(prop => prop.type === 7 &&
      (prop.name === 'model' || (prop.name === 'bind' && prop.arg?.content === 'model-value')))?.exp?.content.trim()
    const match = binding?.match(/^(selectedWidget\.style|schema\.canvas|batchStyle)\.([\w.]+)(?:\s*\|\|.*)?$/)
    assert.ok(match, `新增色板必须纳入真实绑定回归：${binding}`)
    fields.push({
      binding, scope: match[1], path: match[2], source: node.loc.source,
      alpha: node.props.some(prop => prop.type === 6 && prop.name === 'show-alpha'),
    })
  }
  for (const child of node.children || []) findPickers(child)
}
findPickers(baseParse(designer.descriptor.template.content))

// Mount each actual inspector fragment and the production color wrapper. The
// custom host replaces browser DOM only; all model and history handlers are real.
const renderer = Vue.createRenderer({
  createElement: tag => ({ tag, props: {}, children: [] }),
  createText: text => ({ text }), createComment: text => ({ comment: text }),
  setText: (node, text) => { node.text = text },
  setElementText: (node, text) => { node.text = text },
  patchProp: (node, key, previous, value) => { node.props[key] = value },
  parentNode: node => node.parent,
  nextSibling: node => node.parent?.children[node.parent.children.indexOf(node) + 1] || null,
  insert(node, parent, anchor) {
    if (node.parent) node.parent.children.splice(node.parent.children.indexOf(node), 1)
    node.parent = parent
    const at = anchor ? parent.children.indexOf(anchor) : -1
    if (at < 0) parent.children.push(node)
    else parent.children.splice(at, 0, node)
  },
  remove(node) { if (node.parent) node.parent.children.splice(node.parent.children.indexOf(node), 1) },
})
function stub(name, events) {
  return Vue.defineComponent({
    name, inheritAttrs: false, props: ['modelValue', 'disabled'], emits: events,
    setup: (props, { attrs, slots }) => () => Vue.h(name, { ...attrs, ...props }, slots.default?.()),
  })
}
const getPath = (object, path) => path.split('.').reduce((value, key) => value?.[key], object)
const chartColorPaths = {
  'chartConfig.tooltip.textColor': 'tooltip.textStyle.color',
  'chartConfig.legend.textColor': 'legend.textStyle.color',
  'chartConfig.xAxis.axisLineColor': 'xAxis.axisLine.lineStyle.color',
  'chartConfig.yAxis.axisLineColor': 'yAxis.axisLine.lineStyle.color',
  'chartConfig.xAxis.labelColor': 'xAxis.axisLabel.color',
  'chartConfig.yAxis.labelColor': 'yAxis.axisLabel.color',
  'chartConfig.xAxis.splitLineColor': 'xAxis.splitLine.lineStyle.color',
  'chartConfig.yAxis.splitLineColor': 'yAxis.splitLine.lineStyle.color',
  'chartConfig.label.color': 'series.0.label.color',
  'chartConfig.markLine.color': 'series.0.markLine.lineStyle.color',
}
const visualColorPaths = {
  color: '--accent', backgroundColor: 'background', borderColor: '--widget-border-color',
  flipCellBackground: '--flip-cell-background', flipCellBorderColor: '--flip-cell-border',
  statsLabelColor: '--stats-label-color', statsUnitColor: '--stats-unit-color',
  statsUpColor: '--stats-up', statsDownColor: '--stats-down',
  timelineDoneColor: '--timeline-done', timelineActiveColor: '--timeline-active',
  tableHeaderColor: '--table-header-color', tableTextColor: '--table-text-color', tableValueColor: '--table-value-color',
}

function actualComputed(harness, name) {
  const declaration = designer.program.body.filter(node => node.type === 'VariableDeclaration')
    .flatMap(node => node.declarations).find(node => node.id.name === name)
  assert.ok(declaration, `真实画布计算属性 ${name} 必须存在`)
  return vm.runInContext(designer.script.slice(declaration.init.start, declaration.init.end), harness)
}

async function mountField(t, field) {
  const schema = Vue.reactive({ canvas: {}, widgets: [] })
  const selectedWidget = Vue.computed(() => schema.widgets[0])
  const selectedIds = Vue.ref(field.scope === 'batchStyle' ? ['selected-a', 'selected-b'] : ['selected-a'])
  const batchStyle = Vue.reactive({ color: '#123456', backgroundColor: '#123456' })
  const harness = await createFunctionHarness(designer, {
    schema, selectedWidget, selectedIds, batchStyle, computed: Vue.computed,
    historyPending: Vue.ref(null), history: Vue.ref([]), future: Vue.ref([]), draftDirty: Vue.ref(false),
    lastSchemaSnapshot: '', showGrid: Vue.ref(false), canvasWidth: Vue.ref(1920), canvasHeight: Vue.ref(1080), canvasScale: Vue.ref(1),
  })
  Object.assign(schema, harness.defaultSchema())
  for (const id of ['selected-a', 'selected-b', 'unselected']) {
    const widget = harness.normalizeWidget({ id, type: field.path.startsWith('table') ? 'advanced-table' : 'bar-chart' }, 0, schema.canvas)
    widget.binding = { sourceType: 'STATIC', staticRows: [{ name: '进度', value: 50 }], fieldMap: { category: 'name', value: 'value' } }
    widget.style.useSystemPalette = true
    widget.style.backgroundTransparent = false
    widget.style.borderTransparent = false
    widget.style.chartConfig.markLine.show = true
    widget.style.chartConfig.markLine.value = 30
    for (const item of fields.filter(item => item.scope !== 'schema.canvas')) setPath(widget.style, item.path, '#123456')
    schema.widgets.push(widget)
  }
  for (const item of fields.filter(item => item.scope === 'schema.canvas')) setPath(schema.canvas, item.path, '#123456')
  harness.lastSchemaSnapshot = JSON.stringify(schema)
  const canvasStyle = actualComputed(harness, 'canvasStyle')
  const watermarkStyle = actualComputed(harness, 'canvasWatermarkStyle')
  const visualPath = field.scope === 'schema.canvas'
    ? field.path === 'background.color' ? 'backgroundColor' : 'color'
    : chartColorPaths[field.path] ? 'color' : visualColorPaths[field.path]
  const previewStyle = () => {
    if (field.scope === 'schema.canvas') return field.path === 'background.color' ? canvasStyle.value : watermarkStyle.value
    if (chartColorPaths[field.path]) return { color: getPath(harness.designChartOption(selectedWidget.value), chartColorPaths[field.path]) }
    return harness.widgetStyle(selectedWidget.value)
  }
  const render = new Function('Vue', compile(`<section>${field.source}<span :style="previewStyle()" /></section>`, {
    mode: 'function', prefixIdentifiers: true,
  }).code)(Vue)
  const app = renderer.createApp({
    setup: () => ({
      schema, selectedWidget, selectedIds, batchStyle, pageId: 'all-colors-preview', previewStyle,
      ...Object.fromEntries(['beginHistory', 'endHistory', 'updateStyleColor', 'finishStyleColor', 'updateCanvasColor',
        'finishCanvasColor', 'updateBatchStyleColor', 'finishBatchStyleColor', 'setStyle', 'markDirty', 'applyBatchStyle']
        .map(name => [name, harness[name]])),
    }),
    render,
  })
  app.component('DesignerColorPicker', DesignerColorPicker)
  app.component('ElColorPicker', stub('test-color-picker', ['update:modelValue', 'active-change', 'change', 'focus', 'blur']))
  app.component('ElInput', stub('test-input', ['update:modelValue', 'focus', 'blur', 'clear']))
  app.component('ElIcon', stub('test-icon', []))
  app.mount({ children: [] })
  t.after(() => app.unmount())
  const picker = () => app._instance.subTree.children[0].component.subTree.children
    .find(node => node.component?.type.name === 'test-color-picker').component
  const targets = data => field.scope === 'schema.canvas' ? [data.canvas]
    : data.widgets.filter(widget => selectedIds.value.includes(widget.id)).map(widget => widget.style)
  return {
    schema, harness, picker, targets, batchStyle, visualPath,
    visibleColor: () => app._instance.subTree.children[1].el.props.style[visualPath],
  }
}

test('全设计器色板回归覆盖组件、嵌套图表、画布及多选批量属性', () => {
  assert.ok(fields.length >= 40, '避免模板扫描失效后空跑通过')
  for (const scope of ['selectedWidget.style', 'schema.canvas', 'batchStyle']) assert.ok(fields.some(field => field.scope === scope))
  for (const path of Object.keys(chartColorPaths)) assert.ok(fields.some(field => field.path === path), path)
})

for (const field of fields) {
  test(`${field.binding} 拖动逐帧预览，单次撤销、清空与保存`, async t => {
    const mounted = await mountField(t, field)
    const { schema, harness, picker, targets } = mounted
    const untouched = JSON.stringify(schema.widgets.filter(widget => !harness.selectedIds.value.includes(widget.id)))
    const frames = ['#3388aa', '#8844cc', field.alpha ? 'rgba(238, 85, 119, 0.4)' : '#ee5577']
    picker().emit('focus', { type: 'focus' })
    for (const value of frames) {
      picker().emit('active-change', value)
      await Vue.nextTick()
      for (const target of targets(schema)) assert.equal(getPath(target, field.path), value, '确认前最终渲染模型已更新')
      if (field.scope === 'batchStyle') assert.equal(getPath(mounted.batchStyle, field.path), value)
      if (mounted.visualPath) assert.equal(mounted.visibleColor(), value, '实际画布样式或图表选项随帧更新')
      assert.equal(harness.history.value.length, 0, '连续拖动不能拆分撤销记录')
      assert.ok(harness.historyPending.value)
      assert.equal(harness.draftDirty.value, true)
      assert.equal(JSON.stringify(schema.widgets.filter(widget => !harness.selectedIds.value.includes(widget.id))), untouched, '未选中组件保持不变')
    }
    picker().emit('update:modelValue', frames.at(-1))
    picker().emit('change', frames.at(-1))
    await Vue.nextTick()
    assert.equal(harness.historyPending.value, null)
    assert.equal(harness.history.value.length, 1)
    for (const target of targets(JSON.parse(harness.history.value[0]))) assert.equal(getPath(target, field.path), '#123456', '撤销快照保留拖动前色值')
    for (const target of targets(JSON.parse(harness.schemaJson()))) assert.equal(getPath(target, field.path), frames.at(-1), '生产保存序列化保留最终颜色')
    if (field.scope !== 'schema.canvas') {
      for (const target of targets(schema)) assert.equal(target.useSystemPalette, field.path !== 'color', '仅修改主色关闭系统配色')
    }
    picker().emit('blur', { type: 'blur' })
    await Vue.nextTick()
    assert.equal(harness.history.value.length, 1, '确认后失焦不重复记入历史')

    picker().emit('update:modelValue', null)
    picker().emit('change', null)
    await Vue.nextTick()
    for (const target of targets(schema)) assert.equal(getPath(target, field.path), '', '清空写入空色值而不是残留上一帧')
    for (const target of targets(JSON.parse(harness.schemaJson()))) assert.equal(getPath(target, field.path), '', '保存不恢复已清空的颜色')
    assert.equal(harness.history.value.length, 2)
    assert.equal(harness.historyPending.value, null)
  })
}

function deferredSave(harness) {
  let resolve
  let payload
  const request = new Promise(done => { resolve = done })
  harness.pageId = 'all-colors-preview'
  harness.saving = Vue.ref(false)
  harness.ElMessage = { success() {} }
  harness.saveDashboardDraft = (pageId, body) => {
    assert.equal(pageId, 'all-colors-preview')
    payload = body
    return request
  }
  return { resolve, payload: () => JSON.parse(payload.schemaJson) }
}

test('拖动色板未确认即保存：真实保存请求包含当前颜色并保留一次撤销', async t => {
  const field = fields.find(field => field.path === 'chartConfig.tooltip.textColor')
  const { picker, harness } = await mountField(t, field)
  const request = deferredSave(harness)
  picker().emit('active-change', '#abcdef')
  await Vue.nextTick()
  assert.ok(harness.historyPending.value)
  const saving = harness.persistDraft()
  assert.equal(getPath(request.payload().widgets[0].style, field.path), '#abcdef')
  assert.equal(harness.historyPending.value, null, '发送请求前结束当前拖动的历史')
  assert.equal(harness.history.value.length, 1)
  assert.equal(getPath(JSON.parse(harness.history.value[0]).widgets[0].style, field.path), '#123456')
  request.resolve()
  await saving
  assert.equal(harness.saving.value, false)
  assert.equal(harness.draftDirty.value, false)
  assert.equal(harness.history.value.length, 1)
})

test('保存请求期间再次拖色：旧响应保留新颜色的脏状态及未结束的撤销快照', async t => {
  const field = fields.find(field => field.scope === 'schema.canvas' && field.path === 'background.color')
  const { schema, picker, harness } = await mountField(t, field)
  const request = deferredSave(harness)
  picker().emit('active-change', '#abcdef')
  await Vue.nextTick()
  const saving = harness.persistDraft()
  picker().emit('active-change', '#cc8844')
  await Vue.nextTick()
  const pending = harness.historyPending.value
  assert.equal(JSON.parse(pending).canvas.background.color, '#abcdef')
  request.resolve()
  await saving
  assert.equal(request.payload().canvas.background.color, '#abcdef', '请求只包含发送时的颜色')
  assert.equal(schema.canvas.background.color, '#cc8844')
  assert.equal(harness.draftDirty.value, true, '后续颜色仍待保存')
  assert.equal(harness.historyPending.value, pending, '旧响应不能清空新拖动的历史')
  assert.equal(harness.history.value.length, 1)
  picker().emit('change', '#cc8844')
  await Vue.nextTick()
  assert.equal(harness.historyPending.value, null)
  assert.equal(harness.history.value.length, 2)
})
