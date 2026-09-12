import test from 'node:test'
import assert from 'node:assert/strict'
import { createFunctionHarness, readDashboardSfc, designerPath } from './helpers/dashboard-property-audit.mjs'

const source = await readDashboardSfc(designerPath)

function target(tagName = 'DIV', role = '', parent = null, editable = false) {
  const element = { tagName, role, isContentEditable: editable, parent }
  element.closest = selector => {
    const selectors = selector.split(',')
    for (let node = element; node; node = node.parent) {
      if (selectors.some(item => item === node.tagName.toLowerCase() || item === `[role="${node.role}"]`)) return node
    }
    return null
  }
  return element
}

async function setup() {
  const widget = { id: 'w', state: {}, layout: { x: 50, y: 50, w: 100, h: 100 } }
  const harness = await createFunctionHarness(source, {
    selectedIds: { value: ['w'] }, spacePressed: { value: false }, panMode: { value: false }, multiSelectMode: { value: false }, iconPickerVisible: { value: false },
    canvasWidth: { value: 300 }, canvasHeight: { value: 200 },
  })
  const actions = []
  for (const name of ['save', 'redo', 'undo', 'copyWidgets', 'cutWidgets', 'pasteWidgets', 'deleteSelected', 'closeContextMenu']) harness[name] = () => actions.push(name)
  harness.mutate = callback => callback()
  harness.widgetById = id => id === 'w' ? widget : undefined
  const send = (key, element, extra = {}) => {
    const event = { key, code: key === ' ' ? 'Space' : key, target: element, ctrlKey: false, metaKey: false, shiftKey: false, prevented: false, preventDefault() { this.prevented = true }, ...extra }
    harness.onKeydown(event)
    return event
  }
  return { harness, actions, send, widget }
}

test('真实 onKeydown 保留输入框、富文本和所有属性控件的编辑快捷键与方向键', async () => {
  const { actions, send, widget } = await setup()
  const controls = [target('INPUT'), target('TEXTAREA'), target('DIV', '', null, true), target('SELECT'), target('BUTTON'), ...['slider', 'spinbutton', 'combobox', 'switch', 'tab'].map(role => target('DIV', role)), target('SPAN', '', target('BUTTON'))]
  for (const control of controls) {
    for (const modifier of ['ctrlKey', 'metaKey']) {
      for (const key of ['c', 'v', 'z', 'x', 'y']) assert.equal(send(key, control, { [modifier]: true }).prevented, false, `${control.tagName}/${control.role} ${modifier}+${key}`)
    }
    for (const key of ['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Backspace', 'Delete', ' ']) assert.equal(send(key, control).prevented, false)
  }
  assert.deepEqual(actions, [])
  assert.deepEqual(widget.layout, { x: 50, y: 50, w: 100, h: 100 })
})

test('真实 onKeydown 在编辑控件内保留 Ctrl/Command+S 页面保存', async () => {
  const { actions, send } = await setup()
  for (const control of [target('INPUT'), target('DIV', 'slider'), target('BUTTON'), target('DIV', '', null, true)]) {
    assert.equal(send('s', control, { ctrlKey: true }).prevented, true)
    assert.equal(send('S', control, { metaKey: true }).prevented, true)
  }
  assert.deepEqual(actions, Array(8).fill('save'))
})

test('真实 onKeydown 画布快捷键仍能复制粘贴撤销、删除与平移', async () => {
  const { actions, send, harness } = await setup()
  const canvas = target()
  for (const key of ['c', 'x', 'v', 'z', 'y']) assert.equal(send(key, canvas, { ctrlKey: true }).prevented, true)
  send('z', canvas, { metaKey: true, shiftKey: true })
  send('Delete', canvas)
  send(' ', canvas)
  assert.deepEqual(actions, ['copyWidgets', 'cutWidgets', 'pasteWidgets', 'undo', 'redo', 'redo', 'deleteSelected'])
  assert.equal(harness.spacePressed.value, true)
})

test('真实 onKeydown 方向键按步长移动组件，遵守边界和锁定状态', async () => {
  const { send, widget } = await setup()
  const canvas = target()
  send('ArrowRight', canvas)
  assert.equal(widget.layout.x, 51)
  send('ArrowDown', canvas, { shiftKey: true })
  assert.equal(widget.layout.y, 60)
  widget.layout.x = 198
  send('ArrowRight', canvas, { shiftKey: true })
  assert.equal(widget.layout.x, 200)
  widget.layout.y = 2
  send('ArrowUp', canvas, { shiftKey: true })
  assert.equal(widget.layout.y, 0)
  widget.state.locked = true
  send('ArrowLeft', canvas)
  assert.equal(widget.layout.x, 200)
})

test('从组件库新增后焦点交给画布，Delete 和 Backspace 可立即删除选中组件', async () => {
  const { harness, actions, send } = await setup()
  let focused = target('BUTTON')
  const focusOptions = []
  harness.canvasViewport = { value: { focus(options) { focused = target(); focusOptions.push(options) } } }
  harness.inspectorTab = { value: 'style' }
  harness.normalizeWidget = widget => widget
  harness.reindexZ = () => {}

  harness.addWidget({ type: 'text', label: '文本' })
  assert.equal(harness.schema.widgets.length, 1)
  assert.equal(harness.selectedIds.value[0], harness.schema.widgets[0].id)
  assert.equal(focused.tagName, 'DIV')
  assert.equal(focusOptions[0].preventScroll, true)
  assert.equal(send('Delete', focused).prevented, true)
  assert.equal(send('Backspace', focused).prevented, true)
  assert.deepEqual(actions, ['deleteSelected', 'deleteSelected'])
})

test('从属性编辑切回组件时先提交旧组件，再移交画布快捷键；平移抑制点击不抢焦点', async () => {
  const { harness, actions, send } = await setup()
  let focused = target('INPUT')
  const committedIds = []
  harness.canvasPan = { suppressClick: false }
  harness.canvasViewport = { value: { focus() {
    committedIds.push(harness.selectedIds.value[0])
    focused = target()
  } } }

  assert.equal(send('Delete', focused).prevented, false)
  harness.selectWidget({ id: 'next' })
  assert.deepEqual(committedIds, ['w'])
  assert.equal(harness.selectedIds.value[0], 'next')
  assert.equal(send('Delete', focused).prevented, true)
  assert.deepEqual(actions, ['closeContextMenu', 'deleteSelected'])

  focused = target('BUTTON')
  harness.canvasPan.suppressClick = true
  harness.selectWidget({ id: 'ignored' })
  assert.equal(focused.tagName, 'BUTTON')
  assert.deepEqual(committedIds, ['w'])
  assert.equal(harness.selectedIds.value[0], 'next')
})
