import test from 'node:test'
import assert from 'node:assert/strict'
import { createFunctionHarness, readDashboardSfc, designerPath } from './helpers/dashboard-property-audit.mjs'

const source = await readDashboardSfc(designerPath)
const plain = value => JSON.parse(JSON.stringify(value))
const widget = (id, x, y, extra = {}) => ({
  id, type: 'text', name: `组件 ${id}`, layout: { x, y, w: 100, h: 100 },
  style: { title: `标题 ${id}`, color: '#123456' },
  binding: { sourceType: 'STATIC', staticRows: [{ name: id, value: 0 }], fieldMap: { value: 'value' } },
  ...extra,
})
const members = () => [
  widget('a', 100, 100, { groupId: 'g' }),
  widget('b', 300, 300, { groupId: 'g' }),
  widget('outside', 600, 100),
]

async function setup(rawWidgets = members(), groups = { g: { name: '施工概况', collapsed: false } }) {
  const calls = { focus: 0, messages: [] }
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
    draftDirty: { value: false }, snapToGrid: { value: false }, clipboardWidgets: { value: [] }, staticRowsEditorText: { value: '' },
    ElMessage: { success: message => calls.messages.push(message), warning: message => calls.messages.push(message) },
    window: { setTimeout: callback => callback() },
  })
  h.schema = h.normalizeSchema({ canvas: { width: 1000, height: 800 }, widgets: rawWidgets, groups })
  h.lastSchemaSnapshot = JSON.stringify(h.schema)
  h.orderedWidgets = { get value() { return [...h.schema.widgets].sort((a, b) => a.layout.z - b.layout.z) } }
  h.selectedWidget = { get value() { return h.selectedIds.value.length === 1 ? h.widgetById(h.selectedIds.value[0]) : null } }
  const event = (x = 0, y = 0, extra = {}) => ({
    button: 0, clientX: rect.left + x * h.canvasScale.value, clientY: rect.top + y * h.canvasScale.value,
    prevented: false, preventDefault() { this.prevented = true }, ...extra,
  })
  const w = id => h.widgetById(id)
  const ids = () => plain(h.selectedIds.value)
  return { h, w, calls, event, ids }
}

test('规范化及保存重开保留组名和展开状态，组件保持平铺且旧组合兼容', async () => {
  const { h } = await setup(members(), { g: { name: '  施工概况  ', collapsed: false }, orphan: { name: '已删除' } })
  assert.deepEqual(plain(h.schema.groups), { g: { name: '施工概况', collapsed: false } })
  const saved = JSON.parse(h.schemaJson())
  assert.deepEqual(saved.groups, { g: { name: '施工概况', collapsed: false } })
  assert.equal(saved.widgets.length, 3)
  assert.deepEqual(saved.widgets.map(item => [item.id, item.groupId]), [['a', 'g'], ['b', 'g'], ['outside', undefined]])
  assert.ok(saved.widgets.every(item => !item.children), '不能将运行态组件改为树形数据')
  assert.deepEqual(plain(h.normalizeSchema(JSON.stringify(saved)).groups), saved.groups)
  const legacy = h.normalizeSchema({ canvas: { width: 1000, height: 800 }, widgets: [
    ...members(), widget('other', 750, 400, { groupId: 'second' }),
  ] })
  assert.deepEqual(plain(legacy.groups), { g: { name: '组合 1', collapsed: true }, second: { name: '组合 2', collapsed: true } })
})

test('组名及折叠操作进入历史，撤销重做和保存均保留变更', async () => {
  const { h } = await setup()
  h.renameLayerGroup('g', '  项目进度  ')
  h.toggleLayerGroup('g')
  assert.deepEqual(plain(h.schema.groups.g), { name: '项目进度', collapsed: true })
  assert.equal(h.history.value.length, 2)
  h.undo()
  assert.deepEqual(plain(h.schema.groups.g), { name: '项目进度', collapsed: false })
  h.undo()
  assert.deepEqual(plain(h.schema.groups.g), { name: '施工概况', collapsed: false })
  h.redo()
  h.redo()
  assert.deepEqual(JSON.parse(h.schemaJson()).groups.g, { name: '项目进度', collapsed: true })
  const before = JSON.stringify(h.schema)
  h.renameLayerGroup('g', '  ')
  h.renameLayerGroup('g', '项目进度')
  h.renameLayerGroup('missing', '不存在')
  h.toggleLayerGroup('missing')
  assert.equal(JSON.stringify(h.schema), before)
  assert.equal(h.history.value.length, 2)
})

test('图层子项选择开启单成员编辑，画布点击和双击保持该成员选择', async () => {
  const { h, w, ids } = await setup()
  h.selectLayerWidget(w('a'), {})
  assert.deepEqual(ids(), ['a'])
  assert.equal(h.memberEditId.value, 'a')
  assert.equal(h.selectedWidget.value.id, 'a')
  h.selectWidget(w('a'), {})
  h.focusWidget(w('a'))
  assert.deepEqual(ids(), ['a'])
  assert.equal(h.memberEditId.value, 'a')
  assert.equal(h.inspectorTab.value, 'style')
  assert.equal(h.history.value.length, 0)
})

test('单成员的属性、布局、静态数据和字段映射修改互不影响其他成员，并可独立撤销', async () => {
  const { h, w, ids } = await setup()
  h.selectLayerWidget(w('a'), {})
  const original = plain(w('a'))
  const untouched = plain([w('b'), w('outside')])
  h.setStyle('color', '#ff0000')
  h.setLayout('x', 180)
  h.setStaticRowsJson('[{"指标":"进度","数值":65}]')
  h.setFieldMap('value', '数值')
  assert.equal(w('a').style.color, '#ff0000')
  assert.equal(w('a').layout.x, 180)
  assert.deepEqual(plain(w('a').binding.staticRows), [{ 指标: '进度', 数值: 65 }])
  assert.equal(w('a').binding.fieldMap.value, '数值')
  assert.deepEqual(plain([w('b'), w('outside')]), untouched)
  assert.equal(w('a').groupId, 'g')
  assert.equal(h.history.value.length, 4)
  for (let index = 0; index < 4; index++) h.undo()
  assert.deepEqual(plain(w('a')), original)
  assert.deepEqual(ids(), ['a'])
  assert.equal(h.memberEditId.value, 'a')
  for (let index = 0; index < 4; index++) h.redo()
  assert.equal(w('a').binding.fieldMap.value, '数值')
  assert.deepEqual(plain([w('b'), w('outside')]), untouched)
})

test('单成员拖动只改变该成员，锁定的其他组员不阻止拖动', async () => {
  const { h, w, ids, event } = await setup()
  w('b').state.locked = true
  const untouched = plain([w('b'), w('outside')])
  h.selectLayerWidget(w('a'), {})
  h.startMove(event(110, 110), w('a'))
  assert.equal(h.drag.mode, 'move')
  assert.deepEqual(Object.keys(h.drag.originals), ['a'])
  h.handlePointerMove(event(160, 140))
  assert.deepEqual([w('a').layout.x, w('a').layout.y], [150, 130])
  h.handlePointerMove(event(110, 110))
  assert.deepEqual([w('a').layout.x, w('a').layout.y], [100, 100])
  h.handlePointerMove(event(160, 140))
  h.finishPointer({ type: 'pointerup' })
  assert.deepEqual(plain([w('b'), w('outside')]), untouched)
  assert.deepEqual(ids(), ['a'])
  assert.equal(h.memberEditId.value, 'a')
  assert.equal(h.history.value.length, 1)
  h.undo()
  assert.deepEqual([w('a').layout.x, w('a').layout.y], [100, 100])
})

test('单成员缩放走成员手柄，方向键只移动当前成员，锁定后拒绝两种操作', async () => {
  const { h, w, ids, event } = await setup()
  h.selectLayerWidget(w('a'), {})
  const untouched = plain([w('b'), w('outside')])
  h.startResize(event(200, 200), w('a'), 'se')
  assert.equal(h.drag.mode, 'resize')
  assert.deepEqual(Object.keys(h.drag.originals), ['a'])
  h.handlePointerMove(event(250, 220))
  h.finishPointer({ type: 'pointerup' })
  assert.deepEqual([w('a').layout.w, w('a').layout.h], [150, 120])
  h.onKeydown(event(0, 0, { key: 'ArrowRight' }))
  h.onKeydown(event(0, 0, { key: 'ArrowDown', shiftKey: true }))
  assert.deepEqual([w('a').layout.x, w('a').layout.y], [101, 110])
  assert.deepEqual(ids(), ['a'])
  assert.deepEqual(plain([w('b'), w('outside')]), untouched)
  h.toggleLocked(w('a'), true)
  const before = JSON.stringify(h.schema)
  const historyLength = h.history.value.length
  h.startMove(event(110, 110), w('a'))
  h.startResize(event(251, 230), w('a'), 'se')
  h.onKeydown(event(0, 0, { key: 'ArrowLeft' }))
  assert.equal(h.drag.mode, '')
  assert.equal(JSON.stringify(h.schema), before)
  assert.equal(h.history.value.length, historyLength)
})

test('子项锁定及隐藏只影响该成员，组入口仍统一全组状态', async () => {
  const { h, w } = await setup()
  h.selectLayerWidget(w('a'), {})
  h.toggleLocked(w('a'), true)
  h.toggleVisible(w('a'), true)
  assert.deepEqual(h.schema.widgets.map(item => [item.state.locked, item.state.visible]), [[true, false], [false, true], [false, true]])
  h.toggleLocked(w('b'))
  h.toggleVisible(w('b'))
  assert.deepEqual(h.schema.widgets.map(item => [item.state.locked, item.state.visible]), [[false, true], [false, true], [false, true]])
  assert.equal(h.history.value.length, 4)
})

test('选中组节点恢复整体选择，折叠正在编辑的组合也退出成员编辑', async () => {
  const { h, w, ids } = await setup()
  h.selectLayerWidget(w('a'), {})
  h.selectLayerGroup('g', {})
  assert.deepEqual(ids(), ['a', 'b'])
  assert.equal(h.memberEditId.value, '')
  h.selectLayerWidget(w('b'), {})
  h.toggleLayerGroup('g')
  assert.deepEqual(ids(), ['a', 'b'])
  assert.equal(h.memberEditId.value, '')
  assert.equal(h.schema.groups.g.collapsed, true)
  h.toggleLayerGroup('g')
  assert.deepEqual(ids(), ['a', 'b'])
  assert.equal(h.schema.groups.g.collapsed, false)
  h.selectLayerWidget(w('a'), {})
  h.selectLayerWidget(w('outside'), {})
  assert.deepEqual(ids(), ['outside'])
  assert.equal(h.memberEditId.value, '')
})

test('单成员复制为独立组件，修改复制件不污染来源组件或组合', async () => {
  const { h, w, ids } = await setup()
  h.selectLayerWidget(w('a'), {})
  h.duplicateSelected()
  assert.equal(ids().length, 1)
  const clone = w(ids()[0])
  assert.notEqual(clone.id, 'a')
  assert.equal(clone.groupId, undefined)
  assert.equal(w('a').groupId, 'g')
  assert.equal(w('b').groupId, 'g')
  assert.deepEqual(plain(h.schema.groups), { g: { name: '施工概况', collapsed: false } })
  assert.equal(h.memberEditId.value, '')
  h.setStyle('title', '复制件标题')
  h.setStaticRowsJson('[{"value":88}]')
  assert.equal(w('a').style.title, '标题 a')
  assert.deepEqual(plain(w('a').binding.staticRows), [{ name: 'a', value: 0 }])
})

test('整组复制创建独立组名副本及展开状态，重命名和折叠副本不影响原组合', async () => {
  const { h, w, ids } = await setup()
  h.selectLayerGroup('g')
  h.duplicateSelected()
  const clones = ids().map(w)
  const groupId = clones[0].groupId
  assert.equal(clones.length, 2)
  assert.ok(groupId && groupId !== 'g')
  assert.ok(clones.every(item => item.groupId === groupId))
  assert.deepEqual(plain(h.schema.groups[groupId]), { name: '施工概况 副本', collapsed: false })
  h.renameLayerGroup(groupId, '副本改名')
  h.toggleLayerGroup(groupId)
  assert.deepEqual(plain(h.schema.groups.g), { name: '施工概况', collapsed: false })
  assert.deepEqual(plain(h.schema.groups[groupId]), { name: '副本改名', collapsed: true })
})

test('整组剪切清理页面元数据，粘贴从剪贴板恢复名称和展开状态', async () => {
  const { h, w, ids } = await setup()
  h.selectLayerGroup('g')
  h.cutWidgets()
  assert.deepEqual(plain(h.schema.widgets.map(item => item.id)), ['outside'])
  assert.deepEqual(plain(h.schema.groups), {})
  assert.deepEqual(plain(h.clipboardGroups.value), { g: { name: '施工概况', collapsed: false } })
  h.pasteWidgets()
  const clones = ids().map(w)
  const groupId = clones[0].groupId
  assert.equal(clones.length, 2)
  assert.ok(groupId && groupId !== 'g')
  assert.ok(clones.every(item => item.groupId === groupId))
  assert.deepEqual(plain(h.schema.groups[groupId]), { name: '施工概况 副本', collapsed: false })
  assert.deepEqual(plain(h.clipboardGroups.value), { g: { name: '施工概况', collapsed: false } })
  h.undo()
  assert.deepEqual(plain(h.schema.groups), {})
  h.undo()
  assert.deepEqual(plain(h.schema.groups.g), { name: '施工概况', collapsed: false })
  assert.deepEqual(plain(h.schema.widgets.map(item => item.id)), ['a', 'b', 'outside'])
})

test('子成员复制粘贴不携带原组合，剪切仍保留其他成员与元数据', async () => {
  const { h, w, ids } = await setup()
  h.selectLayerWidget(w('a'), {})
  h.cutWidgets()
  assert.deepEqual(plain(h.clipboardGroups.value), {})
  assert.equal(h.clipboardWidgets.value[0].groupId, undefined)
  assert.equal(w('b').groupId, 'g')
  assert.deepEqual(plain(h.schema.groups.g), { name: '施工概况', collapsed: false })
  h.pasteWidgets()
  assert.equal(ids().length, 1)
  assert.equal(w(ids()[0]).groupId, undefined)
  assert.deepEqual(Object.keys(h.schema.groups), ['g'])
})

test('单成员删除保留其他组员和名称，最后一员删除才清理孤立元数据', async () => {
  for (const useDeleteWidget of [false, true]) {
    const { h, w, ids } = await setup()
    h.selectLayerWidget(w('a'), {})
    useDeleteWidget ? h.deleteWidget(w('a')) : h.deleteSelected()
    assert.deepEqual(plain(h.schema.widgets.map(item => item.id)), ['b', 'outside'])
    assert.equal(w('b').groupId, 'g')
    assert.deepEqual(plain(h.schema.groups.g), { name: '施工概况', collapsed: false })
    assert.deepEqual(ids(), [])
    assert.equal(h.memberEditId.value, '')
    h.selectLayerWidget(w('b'), {})
    useDeleteWidget ? h.deleteWidget(w('b')) : h.deleteSelected()
    assert.deepEqual(plain(h.schema.widgets.map(item => item.id)), ['outside'])
    assert.deepEqual(plain(h.schema.groups), {})
    h.undo()
    assert.equal(w('b').groupId, 'g')
    assert.deepEqual(plain(h.schema.groups.g), { name: '施工概况', collapsed: false })
    h.undo()
    assert.equal(w('a').groupId, 'g')
    assert.equal(w('b').groupId, 'g')
  }
})

test('组内拖动只调整成员层级，越组拖放被拒绝并清理拖动状态', async () => {
  const { h, w, event } = await setup()
  const original = plain(h.schema.widgets.map(item => item.id))
  h.startLayerDrag(event(), w('a'), true)
  h.dropLayer(w('outside'), event(), false)
  assert.deepEqual(plain(h.schema.widgets.map(item => item.id)), original)
  assert.equal(h.layerDragId.value, '')
  assert.equal(h.layerDragMemberOnly.value, false)
  assert.equal(h.history.value.length, 0)
  h.startLayerDrag(event(), w('a'), true)
  h.dropLayer(w('b'), event(), true)
  assert.deepEqual(plain(h.schema.widgets.map(item => item.id)), ['b', 'a', 'outside'])
  assert.equal(w('a').groupId, 'g')
  assert.equal(w('b').groupId, 'g')
  assert.equal(h.history.value.length, 1)
  h.undo()
  assert.deepEqual(plain(h.schema.widgets.map(item => item.id)), original)
})
