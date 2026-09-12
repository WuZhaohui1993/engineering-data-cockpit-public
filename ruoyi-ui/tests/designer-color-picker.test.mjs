import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import { randomUUID } from 'node:crypto'
import { createRequire } from 'node:module'
import { createRenderer, defineComponent, h, nextTick, reactive } from 'vue'

const require = createRequire(import.meta.url)
const vueRequire = createRequire(require.resolve('vue'))
const { parse, compileScript, compileStyle } = vueRequire('@vue/compiler-sfc')
const filename = new URL('../src/views/dashboard/designer/DesignerColorPicker.vue', import.meta.url)
const { descriptor } = parse(await fs.readFile(filename, 'utf8'), { filename: filename.pathname })
const compiled = compileScript(descriptor, { id: 'designer-color-picker-test', inlineTemplate: true })
const temporary = new URL(`.designer-color-picker-${process.pid}-${randomUUID()}.mjs`, import.meta.url)
await fs.writeFile(temporary, compiled.content)
let ColorPicker
try { ColorPicker = (await import(temporary.href)).default } finally { await fs.unlink(temporary) }

// Mount the compiled Vue component and dispatch its real child component events.
// Element Plus popover/DOM internals are isolated; application state is a real
// reactive parent so prop watchers, event ordering and unmount hooks all run.
const renderer = createRenderer({
  createElement: tag => ({ tag, props: {}, children: [] }),
  createText: text => ({ text }),
  createComment: text => ({ comment: text }),
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
  remove(node) {
    if (node.parent) node.parent.children.splice(node.parent.children.indexOf(node), 1)
  },
})

function stub(name, events) {
  return defineComponent({
    name, inheritAttrs: false,
    props: ['modelValue', 'disabled', 'showAlpha', 'colorFormat', 'predefine', 'size', 'clearable'],
    emits: events,
    setup: (props, { attrs, slots }) => () => h(name, { ...attrs, ...props }, slots.default?.()),
  })
}

function mountPicker(t, initial = {}, bindThroughActive = false, parentKeydown = () => {}) {
  const props = reactive({ modelValue: '#123456', disabled: false, ...initial })
  const events = []
  const listeners = Object.fromEntries(['update:modelValue', 'active-change', 'change', 'focus', 'blur'].map(name => [
    `on${name[0].toUpperCase()}${name.slice(1)}`,
    value => {
      events.push([name, value])
      if (name === (bindThroughActive ? 'active-change' : 'update:modelValue')) props.modelValue = value
    },
  ]))
  const app = renderer.createApp({ render: () => h('section', { onKeydown: parentKeydown }, [h(ColorPicker, { ...props, ...listeners })]) })
  app.component('ElInput', stub('test-input', ['update:modelValue', 'focus', 'blur', 'clear']))
  app.component('ElColorPicker', stub('test-color-picker', ['update:modelValue', 'active-change', 'change', 'focus', 'blur']))
  app.component('ElIcon', stub('test-icon', []))
  const container = { children: [] }
  app.mount(container)
  t.after(() => app.unmount())
  const tree = () => app._instance.subTree.children[0].component.subTree
  const input = () => tree().children.find(child => child.component?.type.name === 'test-input').component
  const picker = () => tree().children.find(child => child.component?.type.name === 'test-color-picker').component
  const changes = () => events.filter(([name]) => !['focus', 'blur'].includes(name))
  return {
    props, events, changes, app, input, picker,
    value: () => input().props.modelValue,
    type: value => input().emit('update:modelValue', value),
    focus: () => input().emit('focus', { type: 'focus' }),
    blur: () => input().emit('blur', { type: 'blur' }),
    clear: () => { input().emit('update:modelValue', ''); input().emit('clear') },
    key: (key, modifiers = {}) => {
      const event = { key, ...modifiers, cancelBubble: false, defaultPrevented: false,
        stopPropagation() { this.cancelBubble = true }, preventDefault() { this.defaultPrevented = true } }
      for (let node = input().subTree.el; node && !event.cancelBubble; node = node.parent) {
        const handlers = node.props?.onKeydown
        for (const handler of Array.isArray(handlers) ? handlers : [handlers]) handler?.(event)
      }
      return event
    },
    pick: () => tree().children.find(child => child.type === 'button').props.onClick({ stopPropagation() {} }),
  }
}

function expectedCommit(value) {
  return [['update:modelValue', value], ['active-change', value], ['change', value]]
}

test('显示外部色值；输入期间只更新草稿，Enter 和随后失焦仅提交一次', async t => {
  const picker = mountPicker(t)
  assert.equal(picker.value(), '#123456')
  picker.focus()
  picker.type(' #AABBCC ')
  await nextTick()
  assert.equal(picker.value(), ' #AABBCC ')
  assert.equal(picker.props.modelValue, '#123456')
  assert.deepEqual(picker.changes(), [])
  picker.key('Enter')
  picker.blur()
  await nextTick()
  assert.equal(picker.value(), '#AABBCC')
  assert.deepEqual(picker.changes(), expectedCommit('#AABBCC'))
  assert.deepEqual(picker.events.map(([name]) => name), ['focus', 'update:modelValue', 'active-change', 'change', 'blur'])
})

test('Ctrl/Command+S 先确认色值再冒泡给父级保存，非法草稿不写入配置', async t => {
  for (const modifiers of [{ ctrlKey: true }, { metaKey: true }]) {
    const saved = []
    const picker = mountPicker(t, {}, false, event => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') saved.push(picker.props.modelValue)
    })
    picker.type('#abcdef')
    const event = picker.key('s', modifiers)
    assert.deepEqual(saved, ['#abcdef'], '父级保存执行时必须已经更新配置')
    assert.equal(event.cancelBubble, false)
    assert.equal(event.defaultPrevented, false)
    assert.deepEqual(picker.changes(), expectedCommit('#abcdef'))
    picker.blur()
    await nextTick()
    assert.deepEqual(picker.changes(), expectedCommit('#abcdef'), '快捷键后失焦不重复提交')
    picker.type('url(image.png)')
    picker.key('S', modifiers)
    assert.deepEqual(saved, ['#abcdef', '#abcdef'], '无效输入不得进入保存配置')
    await nextTick()
    assert.equal(picker.value(), '#abcdef')
    assert.deepEqual(picker.changes(), expectedCommit('#abcdef'))
  }
  const picker = mountPicker(t)
  picker.type('#abcdef')
  picker.key('s')
  picker.key('a', { metaKey: true })
  assert.deepEqual(picker.changes(), [], '普通字符和其他快捷键保持输入草稿')
})

test('粘贴 RGB(A)、百分比、三/四/六/八位 HEX 和透明色可在失焦后保存', async t => {
  for (const [text, expected] of [
    ['#ABC', '#ABC'], ['#AbC8', '#AbC8'], ['#123456', '#123456'], ['#12345678', '#12345678'],
    ['RGB(12,34,56)', 'rgb(12, 34, 56)'], [' rgba(12, 34, 56, .25) ', 'rgba(12, 34, 56, .25)'],
    ['rgb(10%, 20%, 30%)', 'rgb(10%, 20%, 30%)'],
    ['rgba(10%, 20%, 30%, 25%)', 'rgba(10%, 20%, 30%, 25%)'], ['transparent', 'rgba(0, 0, 0, 0)'],
  ]) {
    const picker = mountPicker(t, { modelValue: '#000000' })
    picker.type(text)
    picker.blur()
    await nextTick()
    assert.equal(picker.value(), expected)
    assert.deepEqual(picker.changes(), expectedCommit(expected))
  }
})

test('HEX 限定字段将 RGB(A)、百分比和透明色转为等价 HEX，兼容后端保存', async t => {
  for (const [text, expected] of [
    ['rgb(12, 34, 56)', '#0c2238'], ['rgba(12, 34, 56, .25)', '#0c223840'],
    ['rgb(100%, 0%, 50%)', '#ff0080'], ['rgba(100%, 0%, 50%, 25%)', '#ff008040'],
    ['transparent', '#00000000'], ['#ABCD', '#ABCD'], ['#12345678', '#12345678'],
  ]) {
    const picker = mountPicker(t, { colorFormat: 'hex' })
    assert.equal(picker.picker().props.colorFormat, 'hex')
    picker.type(text)
    picker.key('Enter')
    picker.blur()
    await nextTick()
    assert.equal(picker.value(), expected, text)
    assert.deepEqual(picker.changes(), expectedCommit(expected), text)
  }
  const existing = mountPicker(t, { modelValue: 'transparent' })
  assert.equal(existing.value(), 'transparent', '已有值在尚未编辑时原样显示')
  assert.deepEqual(existing.changes(), [])
  existing.type('transparent')
  existing.blur()
  await nextTick()
  assert.equal(existing.value(), 'rgba(0, 0, 0, 0)')
  assert.deepEqual(existing.changes(), expectedCommit('rgba(0, 0, 0, 0)'))
})

test('非法值不会进入父配置，失焦恢复；Escape 恢复当前值且不提交', async t => {
  for (const text of ['#12', '#12345', '#1234567', 'rgb(256, 0, 0)', 'rgba(1, 2, 3, 1.2)', 'rgb(100%, 0, 0)', 'red', 'url(image.png)', 'var(--color)', 'rgb(1 2 3)', 'rgba(1, 2, 3, -1)']) {
    const picker = mountPicker(t)
    picker.type(text)
    picker.blur()
    await nextTick()
    assert.equal(picker.value(), '#123456', text)
    assert.deepEqual(picker.changes(), [], text)
  }
  const picker = mountPicker(t)
  picker.type('#ffffff')
  picker.key('Escape')
  picker.blur()
  await nextTick()
  assert.equal(picker.value(), '#123456')
  assert.deepEqual(picker.changes(), [])
})

test('清空与重复输入不产生重复提交，active-change 单向绑定也能回写', async t => {
  const picker = mountPicker(t, {}, true)
  picker.clear()
  picker.blur()
  await nextTick()
  assert.equal(picker.value(), '')
  assert.equal(picker.props.modelValue, '')
  assert.deepEqual(picker.changes(), expectedCommit(''))
  picker.type('')
  picker.key('Enter')
  assert.deepEqual(picker.changes(), expectedCommit(''))
})

test('色板连续预览和确认同步色值；延迟预览不覆盖正在编辑的草稿', async t => {
  const predefine = ['#123456', '#abcdef']
  const picker = mountPicker(t, { 'show-alpha': true, predefine, size: 'small' })
  assert.equal(picker.picker().props.showAlpha, true)
  assert.deepEqual(picker.picker().props.predefine, predefine)
  assert.equal(picker.input().props.size, 'small')
  picker.picker().emit('active-change', 'rgba(12, 34, 56, 0.25)')
  await nextTick()
  assert.equal(picker.value(), 'rgba(12, 34, 56, 0.25)')
  picker.picker().emit('update:modelValue', 'rgba(12, 34, 56, 0.25)')
  picker.picker().emit('change', 'rgba(12, 34, 56, 0.25)')
  await nextTick()
  assert.equal(picker.props.modelValue, 'rgba(12, 34, 56, 0.25)')
  picker.focus()
  picker.type('#abcd')
  picker.picker().emit('active-change', '#999999')
  await nextTick()
  assert.equal(picker.value(), '#abcd')
  picker.key('Enter')
  await nextTick()
  assert.equal(picker.value(), '#abcd')
})

test('组件切换、撤销等外部更新替换草稿；取消不会恢复旧组件颜色', async t => {
  const picker = mountPicker(t)
  picker.type('#abcdef')
  picker.props.modelValue = '#654321'
  await nextTick()
  assert.equal(picker.value(), '#654321')
  picker.key('Escape')
  picker.blur()
  await nextTick()
  assert.equal(picker.value(), '#654321')
  assert.deepEqual(picker.changes(), [])
})

test('色板焦点事件完整转发，连续预览在确认前更新 active 绑定且不误作文本编辑', async t => {
  const picker = mountPicker(t, {}, true)
  picker.picker().emit('focus', { type: 'focus' })
  for (const color of ['#ff0000', '#00ff00', '#0000ff']) {
    picker.picker().emit('active-change', color)
    await nextTick()
    assert.equal(picker.props.modelValue, color)
    assert.equal(picker.value(), color)
  }
  assert.equal(picker.events.filter(([name]) => name === 'change').length, 0)
  picker.picker().emit('blur', { type: 'blur' })
  await nextTick()
  assert.deepEqual(picker.events.map(([name]) => name), [
    'focus', 'active-change', 'active-change', 'active-change', 'blur',
  ])
})

test('滴管将取色结果同步到输入框，并保留 RGBA 和短/长 HEX 透明度', async t => {
  const originalWindow = globalThis.window
  globalThis.window = { isSecureContext: true, EyeDropper: class { async open() { return { sRGBHex: '#aabbcc' } } } }
  t.after(() => { globalThis.window = originalWindow })
  for (const [modelValue, expected] of [
    ['rgba(1, 2, 3, 0.25)', 'rgba(170, 187, 204, 0.25)'],
    ['rgba(1, 2, 3, 25%)', 'rgba(170, 187, 204, 25%)'],
    ['#1234', '#aabbcc44'], ['#12345678', '#aabbcc78'],
    ['transparent', 'rgba(170, 187, 204, 0)'], ['#123456', '#aabbcc'],
  ]) {
    const picker = mountPicker(t, { modelValue })
    await picker.pick()
    await nextTick()
    assert.equal(picker.value(), expected)
    assert.deepEqual(picker.changes(), expectedCommit(expected))
  }
  const hexPicker = mountPicker(t, { modelValue: 'rgba(1, 2, 3, 0.25)', colorFormat: 'hex' })
  await hexPicker.pick()
  await nextTick()
  assert.equal(hexPicker.value(), '#aabbcc40')
  assert.deepEqual(hexPicker.changes(), expectedCommit('#aabbcc40'))
})

test('取消、外部颜色变化和卸载时的在途滴管结果不会污染后续颜色', async t => {
  const originalWindow = globalThis.window
  let resolvePick
  globalThis.window = { isSecureContext: true, EyeDropper: class { open() { return new Promise(resolve => { resolvePick = resolve }) } } }
  t.after(() => { globalThis.window = originalWindow })
  const picker = mountPicker(t)
  const pending = picker.pick()
  picker.props.modelValue = '#654321'
  await nextTick()
  resolvePick({ sRGBHex: '#ffffff' })
  await pending
  assert.equal(picker.value(), '#654321')
  assert.deepEqual(picker.changes(), [])
  const unmounted = mountPicker(t)
  const unmountedPending = unmounted.pick()
  unmounted.app.unmount()
  resolvePick({ sRGBHex: '#ffffff' })
  await unmountedPending
  assert.deepEqual(unmounted.changes(), [])
  globalThis.window.EyeDropper = class { async open() { throw new Error('AbortError') } }
  const cancelled = mountPicker(t)
  await cancelled.pick()
  assert.equal(cancelled.value(), '#123456')
  assert.deepEqual(cancelled.changes(), [])
})

test('禁用时输入不能提交；组件样式保持三个控件同高且可收缩', t => {
  const picker = mountPicker(t, { disabled: true })
  assert.equal(picker.input().props.disabled, true)
  assert.equal(picker.picker().props.disabled, true)
  picker.type('#abcdef')
  picker.blur()
  assert.deepEqual(picker.changes(), [])
  const style = compileStyle({ source: descriptor.styles[0].content, filename: filename.pathname, id: 'data-v-designer-color-picker-test', scoped: true })
  assert.deepEqual(style.errors, [])
})
