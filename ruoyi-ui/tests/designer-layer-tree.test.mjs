import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import { randomUUID } from 'node:crypto'
import { createRequire } from 'node:module'
import { effectScope, reactive } from 'vue'

const require = createRequire(import.meta.url)
const vueRequire = createRequire(require.resolve('vue'))
const { parse, compileScript, compileTemplate, compileStyle } = vueRequire('@vue/compiler-sfc')
const filename = new URL('../src/views/dashboard/designer/DesignerLayerTree.vue', import.meta.url)
const source = await fs.readFile(filename, 'utf8')
const { descriptor, errors } = parse(source, { filename: filename.pathname })
assert.deepEqual(errors, [])
const compiled = compileScript(descriptor, { id: 'designer-layer-tree-test' })
const temporary = new URL(`.designer-layer-tree-${process.pid}-${randomUUID()}.mjs`, import.meta.url)
let LayerTree
await fs.writeFile(temporary, compiled.content)
try { LayerTree = (await import(temporary.href)).default } finally { await fs.unlink(temporary) }

function setupTree(t, overrides = {}) {
  const props = reactive({
    widgets: [
      { id: 'member-a', name: '总投资', groupId: 'g', layout: { z: 1 }, state: {} },
      { id: 'outside', name: '项目标题', layout: { z: 2 }, state: {} },
      { id: 'member-b', name: '完成比例', groupId: 'g', layout: { z: 3 }, state: {} },
    ],
    groups: { g: { name: '投资统计', collapsed: true } },
    selectedIds: [], memberEditId: '', keyword: '', dragId: '',
    meta: () => ({ label: '统计组件', icon: null, tone: 'blue' }),
    ...overrides,
  })
  const events = []
  const scope = effectScope()
  const state = scope.run(() => LayerTree.setup(props, { expose() {}, emit: (...args) => events.push(args) }))
  t.after(() => scope.stop())
  return { props, state, events }
}

test('图层按层级倒序显示，非相邻组合成员合并且不更改原始数据', t => {
  const { props, state } = setupTree(t)
  const original = JSON.stringify(props.widgets)
  assert.deepEqual(state.nodes.value.map(node => node.key), ['group:g', 'widget:outside'])
  const group = state.nodes.value[0]
  assert.deepEqual(group.members.map(widget => widget.id), ['member-b', 'member-a'])
  assert.equal(group.name, '投资统计')
  assert.equal(group.widget.id, 'member-b')
  assert.equal(group.expanded, false)
  assert.equal(JSON.stringify(props.widgets), original)
  props.groups = {}
  assert.equal(state.nodes.value[0].name, '组合')
  assert.equal(state.nodes.value[0].expanded, false)
})

test('搜索组名显示全组，搜索成员保留父组并临时展开，清空后恢复折叠', t => {
  const { props, state, events } = setupTree(t)
  props.keyword = ' 投资统计 '
  assert.equal(state.nodes.value.length, 1)
  assert.equal(state.nodes.value[0].visibleMembers.length, 2)
  assert.equal(state.nodes.value[0].expanded, true)
  props.keyword = '总投资'
  assert.deepEqual(state.nodes.value[0].visibleMembers.map(widget => widget.id), ['member-a'])
  assert.equal(state.nodes.value[0].members.length, 2)
  assert.equal(state.nodes.value[0].expanded, true)
  state.toggleNode(state.nodes.value[0])
  assert.deepEqual(events, [])
  assert.equal(props.groups.g.collapsed, true)
  props.keyword = ''
  assert.equal(state.nodes.value[0].expanded, false)
  state.toggleNode(state.nodes.value[0])
  assert.deepEqual(events, [['toggle-group', 'g']])
  props.keyword = '没有的图层'
  assert.deepEqual(state.nodes.value, [])
})

test('父组与独立成员分别选择，成员编辑状态不误报整组被选中', t => {
  const { props, state, events } = setupTree(t)
  const group = state.nodes.value[0]
  const event = {}
  state.selectNode(group, event)
  assert.deepEqual(events[0], ['select-group', 'g', event])
  state.selectNode(state.nodes.value[1], event)
  assert.equal(events[1][0], 'select-widget')
  assert.equal(events[1][1].id, 'outside')
  props.selectedIds = ['member-a', 'member-b']
  assert.equal(state.isNodeSelected(group), true)
  props.memberEditId = 'member-a'
  assert.equal(state.isNodeSelected(group), false)
  assert.equal(state.isWidgetSelected(props.widgets[0]), true)
  const target = {}
  const keyEvent = { key: 'Enter', target, currentTarget: target, preventDefault() {}, stopPropagation() {} }
  state.handleMemberKeydown(keyEvent, props.widgets[0])
  assert.deepEqual(events.at(-1), ['select-widget', props.widgets[0], keyEvent])
})

test('重命名自动聚焦全选，提交去除空格且只发一次，取消不发修改事件', async t => {
  const { state, events } = setupTree(t)
  let focusCount = 0
  let selectCount = 0
  state.setRenameInput({ focus() { focusCount++ }, select() { selectCount++ } })
  await state.startRename(state.nodes.value[0])
  assert.equal(focusCount, 1)
  assert.equal(selectCount, 1)
  assert.equal(state.renameValue.value, '投资统计')
  state.renameValue.value = '  经营指标  '
  state.commitRename()
  state.commitRename()
  assert.deepEqual(events, [['rename-group', 'g', '经营指标']])
  await state.startRename(state.nodes.value[0])
  state.renameValue.value = '不保存的名称'
  state.cancelRename()
  state.commitRename()
  assert.equal(events.length, 1)
  await state.startRename(state.nodes.value[0])
  state.renameValue.value = '   '
  state.commitRename()
  assert.equal(events.length, 1)
})

test('组合混合隐藏或锁定状态提供显示和解锁操作，全部隐藏才降低父节点可见度', t => {
  const { props, state } = setupTree(t)
  const group = state.nodes.value[0]
  assert.equal(state.visibilityLabel(group), '隐藏组合')
  assert.equal(state.lockLabel(group), '锁定组合')
  props.widgets[0].state.visible = false
  props.widgets[0].state.locked = true
  assert.equal(state.visibilityLabel(group), '显示组合')
  assert.equal(state.lockLabel(group), '解锁组合')
  assert.equal(state.isNodeHidden(group), false)
  props.widgets[2].state.visible = false
  assert.equal(state.isNodeHidden(group), true)
})

test('重命名输入法确认和取消候选不会提前退出，结束输入后回车才提交完整名称', async t => {
  const { state, events } = setupTree(t)
  await state.startRename(state.nodes.value[0])
  const keyEvent = (key, extra = {}) => ({
    key, prevented: false, stopped: false,
    preventDefault() { this.prevented = true },
    stopPropagation() { this.stopped = true },
    ...extra,
  })
  for (const key of ['Enter', 'Escape']) {
    for (const composing of [{ isComposing: true }, { keyCode: 229 }]) {
      const event = keyEvent(key, composing)
      state.handleRenameKeydown(event)
      assert.equal(state.renamingId.value, 'g')
      assert.equal(state.renameValue.value, '投资统计')
      assert.equal(event.prevented, false)
      assert.equal(event.stopped, true)
      assert.deepEqual(events, [])
    }
  }
  state.renameValue.value = '工程进度'
  const enter = keyEvent('Enter')
  state.handleRenameKeydown(enter)
  assert.equal(enter.prevented, true)
  assert.equal(state.renamingId.value, '')
  assert.deepEqual(events, [['rename-group', 'g', '工程进度']])
  await state.startRename(state.nodes.value[0])
  state.renameValue.value = '未提交名称'
  const escape = keyEvent('Escape')
  state.handleRenameKeydown(escape)
  assert.equal(escape.prevented, true)
  assert.equal(state.renamingId.value, '')
  assert.equal(events.length, 1)
})

test('树节点支持键盘展开、选择和重命名，按钮键盘事件不触发父节点选择', async t => {
  const { props, state, events } = setupTree(t)
  const target = {}
  const keyEvent = key => ({ key, target, currentTarget: target, preventDefault() {}, stopPropagation() {} })
  state.handleNodeKeydown(keyEvent('ArrowRight'), state.nodes.value[0])
  assert.deepEqual(events, [['toggle-group', 'g']])
  props.groups.g.collapsed = false
  state.handleNodeKeydown(keyEvent('ArrowLeft'), state.nodes.value[0])
  assert.equal(events.length, 2)
  state.handleNodeKeydown(keyEvent('Enter'), state.nodes.value[0])
  assert.equal(events.at(-1)[0], 'select-group')
  state.handleNodeKeydown({ ...keyEvent('Enter'), target: {} }, state.nodes.value[0])
  assert.equal(events.length, 3)
  state.handleNodeKeydown(keyEvent('F2'), state.nodes.value[0])
  assert.equal(state.renamingId.value, 'g')
})

test('图层的上下方向键及 Home/End 只移动可见节点焦点，不冒泡移动画布或改变选择', t => {
  const { state, events, props } = setupTree(t)
  const focused = []
  const items = Array.from({ length: 4 }, (_, index) => ({
    focus() { focused.push(index) },
    closest(selector) { return selector === '[role="tree"]' ? tree : { parentElement: items[0] } },
  }))
  const tree = { querySelectorAll: () => items }
  const keyEvent = (key, index) => ({
    key, target: items[index], currentTarget: items[index],
    prevented: false, stopped: false,
    preventDefault() { this.prevented = true }, stopPropagation() { this.stopped = true },
  })
  const cases = [
    ['ArrowDown', 0, 1, false], ['ArrowUp', 0, 0, false],
    ['End', 0, 3, false], ['Home', 3, 0, false],
    ['ArrowDown', 1, 2, true], ['ArrowUp', 2, 1, true],
    ['Home', 2, 0, true], ['End', 1, 3, true],
    ['ArrowDown', 3, 3, false],
  ]
  for (const [key, start, expected, member] of cases) {
    const event = keyEvent(key, start)
    if (member) state.handleMemberKeydown(event, props.widgets[0])
    else state.handleNodeKeydown(event, state.nodes.value[start === 0 ? 0 : 1])
    assert.equal(focused.at(-1), expected, `${key} 从 ${start} 移动至 ${expected}`)
    assert.equal(event.prevented, true)
    assert.equal(event.stopped, true)
  }
  assert.deepEqual(events, [])
  assert.deepEqual(props.selectedIds, [])
})

test('独立图层的左右箭头和成员右箭头不冒泡，成员左箭头只返回父组焦点', t => {
  const { state, events, props } = setupTree(t)
  let parentFocused = 0
  const item = { closest: () => ({ parentElement: { focus() { parentFocused++ } } }) }
  const keyEvent = key => ({
    key, target: item, currentTarget: item,
    prevented: false, stopped: false,
    preventDefault() { this.prevented = true }, stopPropagation() { this.stopped = true },
  })
  for (const member of [false, true]) {
    for (const key of ['ArrowLeft', 'ArrowRight']) {
      const event = keyEvent(key)
      if (member) state.handleMemberKeydown(event, props.widgets[0])
      else state.handleNodeKeydown(event, state.nodes.value[1])
      assert.equal(event.prevented, true)
      assert.equal(event.stopped, true)
    }
  }
  assert.equal(parentFocused, 1)
  assert.deepEqual(events, [])
})

test('真实模板和样式可编译，图层面板不提供组合或取消组合操作', () => {
  const template = compileTemplate({ source: descriptor.template.content, filename: filename.pathname, id: 'designer-layer-tree-test', compilerOptions: { bindingMetadata: compiled.bindings } })
  assert.deepEqual(template.errors, [])
  const style = compileStyle({ source: descriptor.styles[0].content, filename: filename.pathname, id: 'designer-layer-tree-test', scoped: true })
  assert.deepEqual(style.errors, [])
  assert.doesNotMatch(descriptor.template.content, /(?:aria-label|content)="(?:组合|取消组合)"/)
  assert.match(descriptor.template.content, /emit\('select-widget', widget, \$event\)/)
  assert.match(descriptor.template.content, /emit\('drag-start', \$event, widget, true\)/)
  assert.match(descriptor.template.content, /emit\('toggle-visible', widget, true\)/)
})
