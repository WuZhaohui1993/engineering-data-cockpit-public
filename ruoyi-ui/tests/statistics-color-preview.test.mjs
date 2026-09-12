import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import { randomUUID } from 'node:crypto'
import { createRequire } from 'node:module'
import * as Vue from 'vue'
import { createFunctionHarness, designerPath, readDashboardSfc } from './helpers/dashboard-property-audit.mjs'

const require = createRequire(import.meta.url)
const vueRequire = createRequire(require.resolve('vue'))
const compilerRequire = createRequire(vueRequire.resolve('@vue/compiler-sfc'))
const { parse, compileScript } = compilerRequire('@vue/compiler-sfc')
const { baseParse, compile } = compilerRequire('@vue/compiler-dom')
const designer = await readDashboardSfc(designerPath)
const filename = new URL('../src/views/dashboard/designer/DesignerColorPicker.vue', import.meta.url)
const { descriptor } = parse(await fs.readFile(filename, 'utf8'), { filename: filename.pathname })
const compiled = compileScript(descriptor, { id: 'statistics-color-preview-test', inlineTemplate: true })
const temporary = new URL(`.statistics-color-preview-${process.pid}-${randomUUID()}.mjs`, import.meta.url)
await fs.writeFile(temporary, compiled.content)
let DesignerColorPicker
try { DesignerColorPicker = (await import(temporary.href)).default } finally { await fs.unlink(temporary) }

const fields = [
  ['statsLabelColor', '--stats-label-color', '#9aabbd'],
  ['statsUnitColor', '--stats-unit-color', '#9aabbd'],
  ['statsUpColor', '--stats-up', '#35d4b0'],
  ['statsDownColor', '--stats-down', '#ef8d8d'],
]
const fragments = new Map()
function findPickers(node) {
  if (node.tag === 'DesignerColorPicker') {
    const binding = node.props.find(prop => prop.type === 7 &&
      (prop.name === 'model' || (prop.name === 'bind' && prop.arg?.content === 'model-value')))
    for (const [key] of fields) {
      if (binding?.exp?.content === `selectedWidget.style.${key}`) fragments.set(key, node.loc.source)
    }
  }
  for (const child of node.children || []) findPickers(child)
}
findPickers(baseParse(designer.descriptor.template.content))

// Compile the actual inspector bindings and mount the actual wrapper. Only
// Element Plus DOM/popover internals are stubbed; parent state and handlers run.
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

async function mountField(t, key) {
  assert.ok(fragments.has(key), `统计颜色属性 ${key} 必须存在于真实模板`)
  const schema = Vue.reactive({ canvas: {}, widgets: [{ id: 'statistics', type: 'statistics', style: Object.fromEntries(fields.map(([key]) => [key, '#123456'])) }] })
  const selectedWidget = Vue.computed(() => schema.widgets[0])
  const harness = await createFunctionHarness(designer, {
    schema, selectedWidget,
    historyPending: Vue.ref(null), history: Vue.ref([]), future: Vue.ref([]), draftDirty: Vue.ref(false),
    lastSchemaSnapshot: JSON.stringify(schema),
  })
  const template = `<section>${fragments.get(key)}<span :style="widgetVisualVariables(selectedWidget.style, false, '#0099ff')" /></section>`
  const render = new Function('Vue', compile(template, { mode: 'function', prefixIdentifiers: true }).code)(Vue)
  const app = renderer.createApp({
    setup: () => ({
      selectedWidget, pageId: 'color-preview', selectedIds: ['statistics'],
      beginHistory: harness.beginHistory, endHistory: harness.endHistory,
      updateStyleColor: harness.updateStyleColor, finishStyleColor: harness.finishStyleColor,
      markDirty: harness.markDirty, widgetVisualVariables: harness.widgetVisualVariables,
    }),
    render,
  })
  app.component('DesignerColorPicker', DesignerColorPicker)
  app.component('ElColorPicker', stub('test-color-picker', ['update:modelValue', 'active-change', 'change', 'focus', 'blur']))
  app.component('ElInput', stub('test-input', ['update:modelValue', 'focus', 'blur', 'clear']))
  app.component('ElIcon', stub('test-icon', []))
  app.mount({ children: [] })
  t.after(() => app.unmount())
  const wrapper = () => app._instance.subTree.children[0].component.subTree
  const child = name => wrapper().children.find(node => node.component?.type.name === name).component
  return {
    harness,
    value: () => selectedWidget.value.style[key],
    css: variable => app._instance.subTree.children[1].el.props.style[variable],
    picker: () => child('test-color-picker'),
    input: () => child('test-input'),
  }
}

for (const [key, variable, fallback] of fields) {
  test(`统计 ${key} 拖动即更新画布 CSS，多帧确认只产生一次历史`, async t => {
    const field = await mountField(t, key)
    field.picker().emit('focus', { type: 'focus' })
    for (const value of ['#3388aa', '#8844cc', '#ee5577']) {
      field.picker().emit('active-change', value)
      await Vue.nextTick()
      assert.equal(field.value(), value, '不等待确认，父组件样式已经变化')
      assert.equal(field.css(variable), value, '画布样式的 CSS 变量随每帧更新')
      assert.equal(field.harness.history.value.length, 0, '拖动中不拆分撤销记录')
      assert.ok(field.harness.historyPending.value)
      assert.equal(field.harness.draftDirty.value, true)
    }
    field.picker().emit('update:modelValue', '#ee5577')
    field.picker().emit('change', '#ee5577')
    await Vue.nextTick()
    assert.equal(field.harness.historyPending.value, null, '确认后立即结束历史，不等待失焦')
    assert.equal(field.harness.history.value.length, 1)
    assert.equal(JSON.parse(field.harness.history.value[0]).widgets[0].style[key], '#123456')
    field.picker().emit('blur', { type: 'blur' })
    await Vue.nextTick()
    assert.equal(field.harness.history.value.length, 1, '确认后失焦不重复记录')

    field.picker().emit('update:modelValue', null)
    field.picker().emit('change', null)
    await Vue.nextTick()
    assert.equal(field.value(), '')
    assert.equal(field.css(variable), fallback, '色板清空后恢复组件默认颜色')
    assert.equal(field.harness.history.value.length, 2)
  })
}

test('统计色值手工输入提交与非法值恢复继续经过相同的真实绑定', async t => {
  const field = await mountField(t, 'statsUnitColor')
  field.input().emit('focus', { type: 'focus' })
  field.input().emit('update:modelValue', '#abcdef')
  await Vue.nextTick()
  assert.equal(field.value(), '#123456', '手工未提交草稿不进入样式')
  field.input().emit('blur', { type: 'blur' })
  await Vue.nextTick()
  assert.equal(field.value(), '#abcdef')
  assert.equal(field.css('--stats-unit-color'), '#abcdef')
  assert.equal(field.harness.history.value.length, 1)

  field.input().emit('focus', { type: 'focus' })
  field.input().emit('update:modelValue', '#12')
  field.input().emit('blur', { type: 'blur' })
  await Vue.nextTick()
  assert.equal(field.value(), '#abcdef')
  assert.equal(field.input().props.modelValue, '#abcdef')
  assert.equal(field.harness.history.value.length, 1)
})
