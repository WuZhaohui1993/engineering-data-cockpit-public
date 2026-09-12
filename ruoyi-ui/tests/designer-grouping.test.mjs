import test from 'node:test'
import assert from 'node:assert/strict'
import { createFunctionHarness, readDashboardSfc, designerPath } from './helpers/dashboard-property-audit.mjs'

const source = await readDashboardSfc(designerPath)
const plain = value => JSON.parse(JSON.stringify(value))
const widget = (id, x, y, w = 100, h = 100, extra = {}) => ({
  id, type: 'text', name: `组件 ${id}`, layout: { x, y, w, h, rotate: 15 },
  binding: { sourceType: 'STATIC', staticRows: [{ name: id, value: 0 }] },
  ...extra,
})
const members = () => [
  widget('a', 100, 100, 100, 100, { groupId: 'g' }),
  widget('b', 250, 250, 150, 150, { groupId: 'g' }),
  widget('outside', 600, 100),
]

async function setup(rawWidgets = members()) {
  const calls = { focus: 0, begin: 0, end: 0, mutate: 0, messages: [] }
  const rect = { left: 80, top: 60 }
  const h = await createFunctionHarness(source, {
    selectedIds: { value: [] }, multiSelectMode: { value: false }, inspectorTab: { value: 'basic' }, iconPickerVisible: { value: false },
    canvasWidth: { value: 1000 }, canvasHeight: { value: 800 }, canvasScale: { value: 1 },
    canvasElement: { value: { getBoundingClientRect: () => rect } },
    canvasViewport: { value: { focus: () => calls.focus++, scrollLeft: 0, scrollTop: 0 } },
    canvasPanEnabled: { value: false }, canvasPan: { active: false, suppressClick: false, moved: false },
    marquee: { active: false }, drag: { mode: '', widget: null, originals: {} },
    contextMenu: { visible: false, widget: null }, layerDragId: { value: '' },
    historyPending: { value: null }, history: { value: [] }, future: { value: [] },
    draftDirty: { value: false }, snapToGrid: { value: false }, clipboardWidgets: { value: [] },
    ElMessage: { success: message => calls.messages.push(message) },
    window: { setTimeout: callback => callback() },
  })
  h.schema = h.normalizeSchema({ canvas: { width: 1000, height: 800 }, widgets: rawWidgets })
  h.lastSchemaSnapshot = JSON.stringify(h.schema)
  h.orderedWidgets = { get value() { return [...h.schema.widgets].sort((a, b) => a.layout.z - b.layout.z) } }
  for (const [method, counter] of [['mutate', 'mutate'], ['beginHistory', 'begin'], ['endHistory', 'end']]) {
    const original = h[method]
    h[method] = (...args) => { calls[counter]++; return original(...args) }
  }
  const event = (x = 0, y = 0, extra = {}) => ({
    button: 0, clientX: rect.left + x * h.canvasScale.value, clientY: rect.top + y * h.canvasScale.value,
    prevented: false, preventDefault() { this.prevented = true }, ...extra,
  })
  const w = id => h.widgetById(id)
  const ids = () => plain(h.selectedIds.value)
  const layouts = () => plain(h.schema.widgets.map(item => ({ id: item.id, groupId: item.groupId, layout: item.layout })))
  return { h, w, calls, event, ids, layouts }
}

test('组合选框包含旋转成员的可见四角，原始布局保持不变', async () => {
  const { h } = await setup();
  const widgets = [{ id: 'rotated', layout: { x: 100, y: 100, w: 100, h: 100, rotate: 45 } }];
  const before = plain(widgets);
  const bounds = h.groupSelectionVisualBounds(widgets);
  assert.ok(Math.abs(bounds.x - (150 - Math.sqrt(5000))) < 1e-9);
  assert.ok(Math.abs(bounds.y - (150 - Math.sqrt(5000))) < 1e-9);
  assert.ok(Math.abs(bounds.w - Math.sqrt(20000)) < 1e-9);
  assert.deepEqual(widgets, before);
});

test('组合按钮按选择状态启用，禁用操作不改变组件或历史', async t => {
  const cases = [
    { name: '空选', selected: [], group: false, ungroup: false },
    { name: '单个未组合组件', selected: ['outside'], group: false, ungroup: false },
    { name: '多个未组合组件', selected: ['outside', 'loose'], group: true, ungroup: false },
    { name: '同一组合全部成员', selected: ['a', 'b'], group: false, ungroup: true },
    { name: '同一组合代表成员', selected: ['a'], group: false, ungroup: true },
    { name: '多个已有组合', selected: ['a', 'b', 'c', 'd'], group: true, ungroup: true },
    { name: '多个已有组合代表成员', selected: ['a', 'c'], group: true, ungroup: true },
    { name: '组合和未组合组件混合选择', selected: ['a', 'b', 'outside'], group: true, ungroup: false },
    { name: '组合代表和未组合组件混合选择', selected: ['a', 'outside'], group: true, ungroup: false },
    { name: '锁定未组合组件', selected: ['outside', 'loose'], locked: 'outside', group: false, ungroup: false },
    { name: '组合包含锁定成员', selected: ['a', 'b'], locked: 'b', group: false, ungroup: false },
    { name: '组合代表扩展出锁定成员', selected: ['a', 'c'], locked: 'b', group: false, ungroup: false },
  ]
  for (const entry of cases) {
    await t.test(entry.name, async () => {
      const { h, w, calls, ids } = await setup([
        ...members(),
        widget('loose', 750, 100),
        widget('c', 500, 350, 100, 100, { groupId: 'second' }),
        widget('d', 650, 500, 100, 100, { groupId: 'second' }),
      ])
      h.selectedIds.value = [...entry.selected]
      if (entry.locked) w(entry.locked).state.locked = true
      const before = JSON.stringify(h.schema)

      assert.equal(h.canGroupSelection(), entry.group, '组合按钮可用状态')
      assert.equal(h.canUngroupSelection(), entry.ungroup, '取消组合按钮可用状态')
      if (!entry.group) h.groupSelected()
      if (!entry.ungroup) h.ungroupSelected()

      assert.equal(JSON.stringify(h.schema), before, '禁用操作保留全部组件')
      assert.deepEqual(ids(), entry.selected, '禁用操作保留当前选择')
      assert.equal(h.history.value.length, 0, '禁用操作不增加历史')
      assert.equal(h.draftDirty.value, false, '禁用操作不标记草稿变更')
      assert.equal(calls.mutate, 0, '禁用操作不进入变更事务')
      assert.equal(calls.begin, 0)
      assert.equal(calls.end, 0)
    })
  }
})

test('组合和取消组合只改变组合关系，每次一条历史，已成一组不重复组合', async () => {
  const { h, w, calls, ids } = await setup(members().map(({ groupId, ...item }) => item))
  h.selectedIds.value = ['a', 'b']
  const original = plain(h.schema.widgets)
  assert.equal(h.canGroupSelection(), true)
  h.groupSelected()
  const group = w('a').groupId
  assert.ok(group)
  assert.equal(w('b').groupId, group)
  assert.equal(w('outside').groupId, undefined)
  assert.deepEqual(ids(), ['a', 'b'])
  assert.equal(h.canGroupSelection(), false)
  assert.equal(h.canUngroupSelection(), true)
  assert.equal(h.history.value.length, 1)
  h.groupSelected()
  assert.equal(h.history.value.length, 1)
  h.ungroupSelected()
  assert.deepEqual(plain(h.schema.widgets), original)
  assert.deepEqual(ids(), ['a', 'b'])
  assert.equal(h.history.value.length, 2)
  assert.equal(calls.begin, 2)
  assert.equal(calls.end, 2)
})

test('合并已有组合时扩展全部成员并产生独立组合编码，未选组件不受影响', async () => {
  const { h, w, ids } = await setup([
    ...members().slice(0, 2),
    widget('c', 500, 100, 100, 100, { groupId: 'second' }),
    widget('d', 650, 250, 100, 100, { groupId: 'second' }),
    widget('outside', 800, 500),
  ])
  // 入口即使只传入各组代表，最终操作仍必须覆盖组内所有成员。
  h.selectedIds.value = ['a', 'c']
  h.groupSelected()
  const group = w('a').groupId
  assert.ok(group && !['g', 'second'].includes(group))
  assert.deepEqual(ids(), ['a', 'b', 'c', 'd'])
  assert.ok(['a', 'b', 'c', 'd'].every(id => w(id).groupId === group))
  assert.equal(w('outside').groupId, undefined)
  h.selectedIds.value = ['b']
  h.ungroupSelected()
  assert.ok(h.schema.widgets.every(item => !item.groupId))
  assert.equal(h.history.value.length, 2)
})

test('锁定成员阻止组合和取消组合，包括通过另一成员代表访问的锁定组员', async () => {
  for (const partial of [false, true]) {
    const { h, w, calls } = await setup()
    w('b').state.locked = true
    const before = JSON.stringify(h.schema)
    h.selectedIds.value = partial ? ['a', 'outside'] : ['a', 'b', 'outside']
    assert.equal(h.canGroupSelection(), false, `partial=${partial}`)
    h.groupSelected()
    h.selectedIds.value = partial ? ['a'] : ['a', 'b']
    assert.equal(h.canUngroupSelection(), false, `partial=${partial}`)
    h.ungroupSelected()
    assert.equal(JSON.stringify(h.schema), before)
    assert.equal(calls.mutate, 0)
    assert.equal(h.history.value.length, 0)
  }
})

test('单成员图层入口统一锁定、解锁、隐藏和显示整组，保留其他状态与未选对象', async () => {
  const { h, w } = await setup()
  w('a').state.custom = 'kept'
  h.selectedIds.value = ['outside']
  h.toggleLocked(w('a'))
  assert.deepEqual(h.schema.widgets.map(item => item.state.locked), [true, true, false])
  h.toggleLocked(w('b'))
  assert.deepEqual(h.schema.widgets.map(item => item.state.locked), [false, false, false])
  h.toggleVisible(w('b'))
  assert.deepEqual(h.schema.widgets.map(item => item.state.visible), [false, false, true])
  h.toggleVisible(w('a'))
  assert.deepEqual(h.schema.widgets.map(item => item.state.visible), [true, true, true])
  assert.equal(w('a').state.custom, 'kept')
  assert.equal(h.history.value.length, 4)
})

test('历史混合状态的组合在一次操作后统一显示或解除锁定', async () => {
  const { h, w } = await setup()
  w('a').state.visible = false
  w('b').state.locked = true
  h.toggleVisible(w('b'))
  h.toggleLocked(w('a'))
  assert.ok(['a', 'b'].every(id => w(id).state.visible && !w(id).state.locked))
  assert.equal(h.history.value.length, 2)
})

test('双击 focusWidget 保留完整组合选择，不写历史', async () => {
  const { h, w, ids } = await setup()
  h.selectedIds.value = ['a', 'b']
  h.focusWidget(w('b'))
  assert.deepEqual(ids(), ['a', 'b'])
  assert.equal(h.inspectorTab.value, 'style')
  assert.equal(h.history.value.length, 0)
  assert.equal(h.draftDirty.value, false)
})

test('组合缩放多帧使用按下快照，可拖回起点，并且整次手势只记录一条历史', async () => {
  const { h, w, calls, event, layouts, ids } = await setup()
  h.selectWidget(w('b'), {})
  const original = layouts()
  h.startGroupResize(event(400, 400), 'se')
  assert.equal(h.drag.mode, 'group-resize')
  assert.deepEqual(ids(), ['a', 'b'])
  h.handlePointerMove(event(490, 520))
  const firstFrame = layouts()
  assert.equal(w('a').layout.w, 130)
  assert.equal(w('b').layout.h, 210)
  h.handlePointerMove(event(550, 550))
  h.handlePointerMove(event(490, 520))
  assert.deepEqual(layouts(), firstFrame, '重复指针坐标不得累计缩放')
  h.handlePointerMove(event(400, 400))
  assert.deepEqual(layouts(), original, '回到按下位置应恢复全部成员')
  h.handlePointerMove(event(490, 520))
  assert.equal(h.history.value.length, 0)
  h.finishPointer({ type: 'pointerup' })
  assert.equal(h.history.value.length, 1)
  assert.equal(calls.begin, 1)
  assert.equal(calls.end, 1)
  assert.equal(h.drag.mode, '')
  assert.equal(h.drag.bounds, null)
  assert.deepEqual(layouts().at(-1), original.at(-1), '未选对象不应缩放')
  assert.ok(['a', 'b'].every(id => w(id).groupId === 'g' && w(id).layout.rotate === 15))
  h.undo()
  assert.deepEqual(layouts(), original)
  h.redo()
  assert.deepEqual(layouts(), firstFrame)
})

test('成员缩放入口转交整组；拖回原处松开不增加历史', async () => {
  const { h, w, calls, event, ids, layouts } = await setup()
  const before = layouts()
  h.selectedIds.value = ['outside']
  h.startResize(event(400, 400, { ctrlKey: true }), w('b'), 'se')
  assert.equal(h.drag.mode, 'group-resize')
  assert.deepEqual(ids(), ['a', 'b'])
  h.handlePointerMove(event(430, 450))
  h.handlePointerMove(event(400, 400))
  h.finishPointer({ type: 'pointerup' })
  assert.deepEqual(layouts(), before)
  assert.equal(h.history.value.length, 0)
  assert.equal(calls.begin, 1)
  assert.equal(calls.end, 1)
})

test('任一组员锁定及跨组合选择拒绝缩放，不进入历史或指针手势', async () => {
  for (const mode of ['locked', 'mixed']) {
    const { h, w, calls, event, layouts } = await setup()
    h.selectedIds.value = mode === 'mixed' ? ['a', 'b', 'outside'] : ['a', 'b']
    if (mode === 'locked') w('b').state.locked = true
    const before = layouts()
    h.startGroupResize(event(400, 400), 'se')
    h.handlePointerMove(event(500, 500))
    h.finishPointer({ type: 'pointerup' })
    assert.deepEqual(layouts(), before)
    assert.equal(h.drag.mode, '')
    assert.equal(calls.begin, 0)
    assert.equal(calls.end, 0)
    assert.equal(h.draftDirty.value, false)
  }
})

test('复制组合产生新组和新成员编码，右下边缘整体限位保持间距与数据', async () => {
  const { h, w } = await setup([
    widget('a', 500, 450, 100, 100, { groupId: 'g' }),
    widget('b', 840, 620, 150, 170, { groupId: 'g' }),
  ])
  h.selectWidget(w('a'), {})
  const before = plain(h.schema.widgets)
  h.duplicateSelected()
  const clones = h.selectedIds.value.map(h.widgetById)
  assert.equal(clones.length, 2)
  assert.ok(clones[0].groupId && clones[0].groupId !== 'g')
  assert.equal(clones[1].groupId, clones[0].groupId)
  assert.ok(clones.every(item => !['a', 'b'].includes(item.id)))
  assert.deepEqual(plain(clones.map(item => [item.layout.x, item.layout.y])), [[510, 460], [850, 630]])
  assert.deepEqual(plain(clones.map(item => item.binding)), before.map(item => item.binding))
  for (const item of before) {
    const { z, ...originalLayout } = item.layout
    const { z: nextZ, ...nextLayout } = plain(w(item.id).layout)
    assert.deepEqual(nextLayout, originalLayout)
    assert.deepEqual(plain(w(item.id).binding), item.binding)
    assert.equal(w(item.id).groupId, 'g')
  }
  assert.equal(h.history.value.length, 1)
})

test('连续粘贴每次建立独立组合，剪贴板和原组保持不变，触边不拆散', async () => {
  const { h, w, calls } = await setup([
    widget('a', 500, 450, 100, 100, { groupId: 'g' }),
    widget('b', 840, 620, 150, 170, { groupId: 'g' }),
  ])
  h.selectWidget(w('b'), {})
  h.copyWidgets()
  const clipboard = plain(h.clipboardWidgets.value)
  assert.equal(h.history.value.length, 0)
  const groups = new Set(['g'])
  for (let count = 0; count < 2; count++) {
    h.pasteWidgets()
    const pasted = h.selectedIds.value.map(h.widgetById)
    assert.equal(pasted.length, 2)
    assert.equal(pasted[0].groupId, pasted[1].groupId)
    assert.equal(groups.has(pasted[0].groupId), false)
    groups.add(pasted[0].groupId)
    assert.deepEqual(plain(pasted.map(item => [item.layout.x, item.layout.y])), [[510, 460], [850, 630]])
    assert.deepEqual(plain(h.clipboardWidgets.value), clipboard)
  }
  assert.equal(new Set(h.schema.widgets.map(item => item.id)).size, 6)
  assert.equal(h.history.value.length, 2)
  assert.equal(calls.messages.length, 1)
})

test('组合关系经过保存重开、撤销重做与取消组合后的恢复仍保留', async () => {
  const { h, w, layouts } = await setup(members().map(({ groupId, ...item }) => item))
  h.selectedIds.value = ['a', 'b']
  h.groupSelected()
  const grouped = layouts()
  const saved = JSON.parse(h.schemaJson())
  assert.ok(saved.widgets[0].groupId)
  assert.equal(saved.widgets[0].groupId, saved.widgets[1].groupId)
  const reopened = await setup(saved.widgets)
  assert.deepEqual(reopened.layouts(), grouped)
  h.undo()
  assert.equal(w('a').groupId, undefined)
  assert.equal(w('b').groupId, undefined)
  h.redo()
  assert.deepEqual(layouts(), grouped)
  h.ungroupSelected()
  assert.equal(w('a').groupId, undefined)
  h.undo()
  assert.deepEqual(layouts(), grouped)
  h.redo()
  assert.equal(w('a').groupId, undefined)
  assert.equal(w('b').groupId, undefined)
})

test('组合分数缩放结果在保存重开及撤销重做后保留全部成员尺寸和间距', async () => {
  const { h, w, event, layouts } = await setup()
  h.selectWidget(w('a'), {})
  const before = layouts()
  h.startGroupResize(event(400, 400), 'se')
  h.handlePointerMove(event(407, 411))
  h.finishPointer({ type: 'pointerup' })
  const resized = layouts()
  assert.equal(Number.isInteger(w('a').layout.w), false)
  const saved = JSON.parse(h.schemaJson())
  const reopened = await setup(saved.widgets)
  assert.deepEqual(reopened.layouts(), resized)
  h.undo()
  assert.deepEqual(layouts(), before)
  h.redo()
  assert.deepEqual(layouts(), resized)
})

for (const [sourceId, targetId, expected] of [
  ['b', 'd', ['c', 'd', 'a', 'b', 'outside']],
  ['d', 'a', ['c', 'd', 'a', 'b', 'outside']],
]) {
  test(`图层拖动 ${sourceId} 到 ${targetId} 挪动整个来源组且不插入目标组内部`, async () => {
    const { h, w, calls } = await setup([
      ...members().slice(0, 2),
      widget('c', 500, 100, 100, 100, { groupId: 'second' }),
      widget('d', 650, 250, 100, 100, { groupId: 'second' }),
      widget('outside', 800, 500),
    ])
    const before = new Map(h.schema.widgets.map(item => [item.id, plain(item.layout)]))
    const transfer = { value: '', setData(type, value) { this.value = value }, getData() { return this.value } }
    h.startLayerDrag({ dataTransfer: transfer }, w(sourceId))
    assert.equal(transfer.value, sourceId)
    h.dropLayer(w(targetId), { dataTransfer: transfer })
    assert.deepEqual(plain(h.orderedWidgets.value.map(item => item.id)), expected)
    assert.deepEqual(plain(h.schema.widgets.map(item => item.layout.z)), [1, 2, 3, 4, 5])
    for (const item of h.schema.widgets) {
      const { z, ...position } = plain(item.layout)
      const { z: originalZ, ...originalPosition } = before.get(item.id)
      assert.deepEqual(position, originalPosition)
    }
    assert.equal(h.layerDragId.value, '')
    assert.equal(h.history.value.length, 1)
    assert.equal(calls.mutate, 1)
  })
}

test('同一组合内部拖动图层不重排、不记历史', async () => {
  const { h, w, calls, layouts } = await setup()
  const before = layouts()
  h.startLayerDrag({}, w('a'))
  h.dropLayer(w('b'), {})
  assert.deepEqual(layouts(), before)
  assert.equal(h.layerDragId.value, '')
  assert.equal(h.history.value.length, 0)
  assert.equal(calls.mutate, 0)
})

test('成员删除入口移除整组，撤销后组合与选择恢复为剩余有效成员', async () => {
  const { h, w, ids, layouts } = await setup()
  const before = layouts()
  h.selectedIds.value = ['a', 'b', 'outside']
  h.deleteWidget(w('b'))
  assert.deepEqual(plain(h.schema.widgets.map(item => item.id)), ['outside'])
  assert.deepEqual(ids(), ['outside'])
  h.undo()
  assert.deepEqual(layouts(), before)
  assert.deepEqual(ids(), ['outside'])
})

for (const key of ['ArrowRight', 'Delete']) {
  test(`取消组合后单选再撤销，${key} 操作仍覆盖恢复后的整个组合`, async () => {
    const { h, w, ids, event } = await setup()
    h.selectWidget(w('a'), {})
    h.ungroupSelected()
    h.selectWidget(w('a'), {})
    assert.deepEqual(ids(), ['a'])
    h.undo()
    assert.deepEqual(ids(), ['a', 'b'])
    assert.equal(w('a').groupId, 'g')
    assert.equal(w('b').groupId, 'g')
    const keyEvent = event(0, 0, { key, target: { tagName: 'DIV' } })
    h.onKeydown(keyEvent)
    assert.equal(keyEvent.prevented, true)
    if (key === 'Delete') {
      assert.deepEqual(plain(h.schema.widgets.map(item => item.id)), ['outside'])
      assert.deepEqual(ids(), [])
    } else {
      assert.equal(w('a').layout.x, 101)
      assert.equal(w('b').layout.x, 251)
      assert.equal(w('outside').layout.x, 600)
      assert.deepEqual(ids(), ['a', 'b'])
    }
    assert.equal(h.history.value.length, 1)
  })
}
