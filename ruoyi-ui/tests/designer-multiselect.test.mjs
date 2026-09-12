import test from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { createFunctionHarness, readDashboardSfc, designerPath } from './helpers/dashboard-property-audit.mjs'

const source = await readDashboardSfc(designerPath)
const require = createRequire(import.meta.url)
const vueRequire = createRequire(require.resolve('vue'))
const compilerRequire = createRequire(vueRequire.resolve('@vue/compiler-sfc'))
const { compileTemplate } = compilerRequire('@vue/compiler-sfc')
const { baseParse } = compilerRequire('@vue/compiler-dom')
const plain = value => JSON.parse(JSON.stringify(value))
const widget = (id, x, y, extra = {}) => ({ id, type: 'text', state: {}, layout: { x, y, w: 40, h: 30 }, ...extra })

async function setup(widgets = [widget('a', 10, 10), widget('b', 100, 30), widget('c', 200, 100)]) {
  const calls = { focus: 0, mutate: 0, begin: 0, end: 0 }
  const schema = { canvas: { width: 600, height: 400 }, widgets }
  const rect = { left: 100, top: 60 }
  const h = await createFunctionHarness(source, {
    schema,
    selectedIds: { value: [] }, multiSelectMode: { value: false },
    canvasWidth: { value: 600 }, canvasHeight: { value: 400 }, canvasScale: { value: 1 },
    canvasElement: { value: { getBoundingClientRect: () => rect } },
    canvasViewport: { value: { focus: () => calls.focus++, scrollLeft: 0, scrollTop: 0 } },
    canvasPanEnabled: { value: false }, canvasPan: { active: false, suppressClick: false, moved: false },
    marquee: { active: false, moved: false, startX: 0, startY: 0, x: 0, y: 0, initialIds: [] },
    drag: { mode: '', widget: null, originals: {} },
    contextMenu: { visible: false, widget: null },
    historyPending: { value: null }, history: { value: [] }, future: { value: [] },
    draftDirty: { value: false }, lastSchemaSnapshot: JSON.stringify(schema),
    snapToGrid: { value: false },
    window: { setTimeout: callback => callback() },
  })
  for (const [method, counter] of [['mutate', 'mutate'], ['beginHistory', 'begin'], ['endHistory', 'end']]) {
    const original = h[method]
    h[method] = (...args) => { calls[counter]++; return original(...args) }
  }
  const event = (x = 0, y = 0, extra = {}) => ({
    button: 0, clientX: rect.left + x * h.canvasScale.value, clientY: rect.top + y * h.canvasScale.value,
    prevented: false, preventDefault() { this.prevented = true }, ...extra,
  })
  const ids = () => plain(h.selectedIds.value)
  return { h, widgets, calls, event, ids, rect }
}

for (const modifier of ['shiftKey', 'ctrlKey', 'metaKey', 'multiSelectMode']) {
  test(`${modifier} 添加和取消整组选中，选择本身不写历史`, async () => {
    const { h, widgets, calls, ids } = await setup([
      widget('a', 10, 10), widget('b', 100, 30, { groupId: 'g' }), widget('c', 200, 100, { groupId: 'g' }),
    ])
    h.selectedIds.value = ['a']
    const event = modifier === 'multiSelectMode' ? {} : { [modifier]: true }
    h.multiSelectMode.value = modifier === 'multiSelectMode'
    h.selectWidget(widgets[1], event)
    assert.deepEqual(ids(), ['a', 'b', 'c'])
    h.selectWidget(widgets[2], event)
    assert.deepEqual(ids(), ['a'])
    assert.equal(calls.mutate, 0)
    assert.equal(calls.begin, 0)
    assert.equal(h.history.value.length, 0)
    assert.equal(h.draftDirty.value, false)
  })
}

test('部分组已经选中时补齐整组，再点击整组一起取消', async () => {
  const { h, widgets, ids } = await setup([
    widget('a', 10, 10), widget('b', 100, 30, { groupId: 'g' }), widget('c', 200, 100, { groupId: 'g' }),
  ])
  h.selectedIds.value = ['a', 'b']
  h.selectWidget(widgets[2], { shiftKey: true })
  assert.deepEqual(ids(), ['a', 'b', 'c'])
  h.selectWidget(widgets[1], { shiftKey: true })
  assert.deepEqual(ids(), ['a'])
})

test('拖动已选成员保持多选，整体触边保持相对偏移且只记一次历史', async () => {
  const { h, widgets, calls, event, ids } = await setup()
  h.selectedIds.value = ['a', 'b']
  h.startMove(event(20, 20), widgets[0])
  assert.deepEqual(ids(), ['a', 'b'])
  assert.deepEqual(Object.keys(h.drag.originals), ['a', 'b'])
  h.handlePointerMove(event(2000, 2000))
  assert.deepEqual(widgets.slice(0, 2).map(w => [w.layout.x, w.layout.y]), [[470, 350], [560, 370]])
  h.finishPointer()
  assert.equal(calls.begin, 1)
  assert.equal(calls.end, 1)
  assert.equal(h.history.value.length, 1)
  assert.equal(h.drag.mode, '')
})

test('修饰键轻点在松开后取消当前成员，不产生历史；含锁定组件仍不能群拖', async () => {
  const { h, widgets, calls, event, ids } = await setup()
  h.selectedIds.value = ['a', 'b']
  h.startMove(event(20, 20, { ctrlKey: true }), widgets[0])
  assert.deepEqual(ids(), ['a', 'b'])
  assert.equal(h.drag.mode, 'move')
  h.finishPointer()
  assert.deepEqual(ids(), ['b'])
  assert.equal(h.drag.mode, '')
  assert.equal(calls.begin, 1)
  assert.equal(calls.end, 1)
  assert.equal(h.history.value.length, 0)
  assert.equal(h.draftDirty.value, false)
  widgets[1].state.locked = true
  h.selectedIds.value = ['a', 'b']
  h.startMove(event(20, 20), widgets[0])
  assert.deepEqual(ids(), ['a', 'b'])
  assert.equal(h.drag.mode, '')
  assert.equal(calls.begin, 1)
})

test('多选模式拖动已选组成员保留全部选择，松开后不取消且形成一次历史', async () => {
  const { h, widgets, calls, event, ids } = await setup([
    widget('a', 10, 10), widget('b', 100, 30, { groupId: 'g' }), widget('c', 200, 100, { groupId: 'g' }),
  ])
  h.multiSelectMode.value = true
  h.selectedIds.value = ['a', 'b', 'c']
  h.startMove(event(110, 40), widgets[1])
  assert.deepEqual(ids(), ['a', 'b', 'c'])
  h.handlePointerMove(event(135, 60))
  h.finishPointer()
  assert.deepEqual(ids(), ['a', 'b', 'c'])
  assert.deepEqual(widgets.map(w => [w.layout.x, w.layout.y]), [[35, 30], [125, 50], [225, 120]])
  assert.equal(calls.begin, 1)
  assert.equal(calls.end, 1)
  assert.equal(h.history.value.length, 1)
  assert.equal(h.drag.toggleOnClick, null)
})

test('多选模式轻点已选组成员在松开时取消整组，轻微移动不写历史', async () => {
  const { h, widgets, event, ids } = await setup([
    widget('a', 10, 10), widget('b', 100, 30, { groupId: 'g' }), widget('c', 200, 100, { groupId: 'g' }),
  ])
  h.multiSelectMode.value = true
  h.selectedIds.value = ['a', 'b', 'c']
  h.startMove(event(110, 40), widgets[1])
  assert.deepEqual(ids(), ['a', 'b', 'c'])
  h.handlePointerMove(event(111, 41))
  h.finishPointer()
  assert.deepEqual(ids(), ['a'])
  assert.equal(h.history.value.length, 0)
  assert.equal(h.draftDirty.value, false)
})

test('多选按下期间删除选中项，松开不会重新选中已删除组件', async () => {
  const { h, widgets, event, ids } = await setup()
  h.multiSelectMode.value = true
  h.selectedIds.value = ['a', 'b']
  h.startMove(event(20, 20), widgets[0])
  h.deleteSelected()
  assert.deepEqual(ids(), [])
  h.finishPointer({ type: 'pointerup' })
  assert.deepEqual(ids(), [])
  assert.deepEqual(plain(h.schema.widgets.map(w => w.id)), ['c'])
  assert.equal(h.drag.mode, '')
  assert.equal(h.drag.toggleOnClick, null)
})

test('取消指针或窗口失焦仅结束手势，保持原有多选且不产生历史', async () => {
  for (const reason of ['pointercancel', 'blur']) {
    const { h, widgets, event, ids } = await setup()
    h.multiSelectMode.value = true
    h.selectedIds.value = ['a', 'b']
    h.startMove(event(20, 20), widgets[0])
    if (reason === 'blur') {
      h.spacePressed = { value: true }
      h.resetCanvasPanShortcut()
      assert.equal(h.spacePressed.value, false)
    } else {
      h.finishPointer({ type: reason })
    }
    assert.deepEqual(ids(), ['a', 'b'], reason)
    assert.equal(h.drag.mode, '', reason)
    assert.equal(h.drag.toggleOnClick, null, reason)
    assert.equal(h.history.value.length, 0, reason)
    assert.equal(h.draftDirty.value, false, reason)
  }
})

test('正常 pointerup 仍取消轻点项，按下后已清空选择则不会重新加入', async () => {
  const { h, widgets, event, ids } = await setup()
  h.multiSelectMode.value = true
  h.selectedIds.value = ['a', 'b']
  h.startMove(event(20, 20), widgets[0])
  h.finishPointer({ type: 'pointerup' })
  assert.deepEqual(ids(), ['b'])
  h.startMove(event(110, 40), widgets[1])
  h.clearSelection()
  h.finishPointer({ type: 'pointerup' })
  assert.deepEqual(ids(), [])
  assert.equal(h.history.value.length, 0)
})

test('多选模式和修饰键下调整尺寸始终选中目标，手柄不会切换为取消选中', async () => {
  const { h, widgets, calls, event, ids } = await setup([
    widget('a', 10, 10, { layout: { x: 10, y: 10, w: 100, h: 60 } }), widget('b', 200, 100),
  ])
  h.multiSelectMode.value = true
  h.selectedIds.value = ['a']
  h.startResize(event(110, 70, { shiftKey: true, metaKey: true }), widgets[0], 'se')
  assert.deepEqual(ids(), ['a'])
  assert.equal(h.drag.mode, 'resize')
  h.handlePointerMove(event(140, 90))
  h.finishPointer()
  assert.deepEqual(ids(), ['a'])
  assert.deepEqual(widgets[0].layout, { x: 10, y: 10, w: 130, h: 80 })
  assert.deepEqual(widgets[1].layout, { x: 200, y: 100, w: 40, h: 30 })
  assert.equal(calls.begin, 1)
  assert.equal(calls.end, 1)
  assert.equal(h.history.value.length, 1)
})

test('未选组件普通拖动收缩为该组件，轻点不产生历史', async () => {
  const { h, widgets, calls, event, ids } = await setup()
  h.selectedIds.value = ['a', 'b']
  h.startMove(event(210, 110), widgets[2])
  assert.deepEqual(ids(), ['c'])
  h.finishPointer()
  assert.equal(calls.begin, 1)
  assert.equal(calls.end, 1)
  assert.equal(h.history.value.length, 0)
  assert.equal(h.draftDirty.value, false)
})

for (const scale of [0.5, 1, 1.2]) {
  for (const reverse of [false, true]) {
    test(`框选在 ${scale * 100}% 缩放下${reverse ? '反向' : '正向'}命中正确并忽略隐藏组件`, async () => {
      const { h, calls, event, ids, rect } = await setup([
        widget('a', 10, 10), widget('b', 100, 30), widget('hidden', 20, 20, { state: { visible: false } }),
        widget('outside', 250, 250),
      ])
      h.canvasScale.value = scale
      rect.left = -150 // 模拟画布滚动后的实际边界。
      rect.top = -80
      const before = JSON.stringify(h.schema)
      h.handleCanvasPointerDown(event(...(reverse ? [160, 100] : [0, 0])))
      h.handlePointerMove(event(...(reverse ? [0, 0] : [160, 100])))
      assert.deepEqual(ids(), ['a', 'b'])
      assert.equal(h.marquee.moved, true)
      h.finishPointer()
      assert.equal(h.marquee.active, false)
      assert.equal(JSON.stringify(h.schema), before)
      assert.equal(calls.mutate, 0)
      assert.equal(calls.begin, 0)
      assert.equal(h.draftDirty.value, false)
    })
  }
}

test('追加框选保留初始选择并扩展组合；普通空白点击清空，微移不框选', async () => {
  const { h, calls, event, ids } = await setup([
    widget('a', 10, 10), widget('b', 100, 30, { groupId: 'g' }), widget('c', 250, 200, { groupId: 'g' }),
  ])
  h.selectedIds.value = ['a']
  h.handleCanvasPointerDown(event(95, 25, { metaKey: true }))
  h.handlePointerMove(event(145, 65))
  assert.deepEqual(ids(), ['a', 'b', 'c'])
  h.finishPointer()
  h.handleCanvasPointerDown(event(0, 0))
  h.handlePointerMove(event(1, 1))
  assert.deepEqual(ids(), [])
  assert.equal(h.marquee.moved, false)
  h.finishPointer()
  assert.equal(calls.mutate, 0)
  assert.equal(calls.begin, 0)
})

test('多选模式空白框选会追加，平移工具与中键不会开始框选', async () => {
  const { h, event, ids } = await setup()
  h.multiSelectMode.value = true
  h.selectedIds.value = ['c']
  h.handleCanvasPointerDown(event(0, 0))
  h.handlePointerMove(event(60, 50))
  h.finishPointer()
  assert.deepEqual(ids(), ['c', 'a'])
  h.canvasPanEnabled.value = true
  h.handleCanvasPointerDown(event(300, 300))
  assert.equal(h.canvasPan.active, true)
  assert.equal(h.marquee.active, false)
  assert.deepEqual(ids(), ['c', 'a'])
  h.finishPointer()
  h.canvasPanEnabled.value = false
  h.handleCanvasPointerDown(event(300, 300, { button: 1 }))
  assert.equal(h.canvasPan.active, true)
  assert.equal(h.marquee.active, false)
})

test('真实对齐入口一次修改形成一次撤销，重复对齐和锁定选择不产生修改', async () => {
  const { h, widgets, calls } = await setup()
  h.selectedIds.value = ['a', 'b', 'c']
  h.alignSelected('left')
  assert.deepEqual(widgets.map(w => w.layout.x), [10, 10, 10])
  assert.equal(calls.mutate, 1)
  assert.equal(h.history.value.length, 1)
  h.alignSelected('left')
  assert.equal(calls.mutate, 1)
  widgets[1].state.locked = true
  h.alignSelected('bottom')
  assert.deepEqual(widgets.map(w => w.layout.y), [10, 30, 100])
  assert.equal(calls.mutate, 1)
})

test('布局按组合计数：两个布局单元允许对齐，三个单元才允许平均分布', async () => {
  const { h, widgets, calls } = await setup([
    widget('a', 10, 10), widget('b', 100, 30, { groupId: 'g' }), widget('c', 200, 100, { groupId: 'g' }),
  ])
  h.selectedIds.value = ['a', 'b', 'c']
  assert.equal(h.canArrangeSelection('left'), true)
  assert.equal(h.canArrangeSelection('distribute-x'), false)
  h.alignSelected('distribute-x')
  assert.equal(calls.mutate, 0)
  h.alignSelected('left')
  assert.deepEqual(widgets.map(w => w.layout.x), [10, 10, 110])
  assert.equal(widgets[2].layout.y - widgets[1].layout.y, 70)
  assert.equal(calls.mutate, 1)
})

test('模板可编译且画布点击不重复执行选择逻辑', () => {
  const result = compileTemplate({ source: source.descriptor.template.content, filename: designerPath, id: 'designer-multiselect-test' })
  assert.deepEqual(result.errors, [])
  const ast = baseParse(source.descriptor.template.content)
  const nodes = []
  const visit = node => { if (node.type === 1) nodes.push(node); for (const child of node.children || []) visit(child) }
  visit(ast)
  const canvasWidget = nodes.find(node => node.props.some(prop => prop.type === 6 && prop.name === 'class' && prop.value?.content.split(/\s+/).includes('canvas-widget')))
  assert.ok(canvasWidget)
  const click = canvasWidget.props.find(prop => prop.type === 7 && prop.name === 'on' && prop.arg?.content === 'click')
  assert.ok(click)
  assert.ok(click.modifiers.some(modifier => (typeof modifier === 'string' ? modifier : modifier.content) === 'stop'))
  assert.ok(!click.exp?.content)
  const pointer = canvasWidget.props.find(prop => prop.type === 7 && prop.name === 'on' && prop.arg?.content === 'pointerdown')
  assert.match(pointer.exp.content, /handleWidgetPointerDown/)
})
