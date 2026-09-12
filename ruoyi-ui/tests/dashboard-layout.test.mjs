import test from 'node:test'
import assert from 'node:assert/strict'
import { dashboardLayoutUnitCount, arrangeDashboardWidgets, translateDashboardWidgets } from '../src/utils/dashboardLayout.js'

const canvas = { width: 1000, height: 800 }
const commands = ['left', 'center', 'right', 'top', 'middle', 'bottom', 'distribute-x', 'distribute-y']
const widget = (id, x, y, w, h, extra = {}) => ({ id, layout: { x, y, w, h, z: 4, rotate: 15 }, state: { locked: false }, ...extra })
const arranged = (widgets, command, area = canvas) => {
  const changes = new Map(arrangeDashboardWidgets(widgets, command, area).map(change => [change.id, change]))
  return widgets.map(item => ({ ...item, layout: { ...item.layout, ...changes.get(item.id) } }))
}
const sample = () => [widget('a', 40, 30, 60, 50), widget('b', 180, 180, 100, 90), widget('c', 390, 390, 80, 70)]

for (const [command, axis, expected] of [
  ['left', 'x', [40, 40, 40]],
  ['center', 'x', [225, 205, 215]],
  ['right', 'x', [410, 370, 390]],
  ['top', 'y', [30, 30, 30]],
  ['middle', 'y', [220, 200, 210]],
  ['bottom', 'y', [410, 370, 390]],
  ['distribute-x', 'x', [40, 195, 390]],
  ['distribute-y', 'y', [30, 190, 390]],
]) {
  test(`${command} 按所选组件外接范围排列且不改变另一轴`, () => {
    const widgets = sample()
    const result = arranged(widgets, command)
    assert.deepEqual(result.map(item => item.layout[axis]), expected)
    const other = axis === 'x' ? 'y' : 'x'
    assert.deepEqual(result.map(item => item.layout[other]), widgets.map(item => item.layout[other]))
  })
}

test('水平和纵向分布按不同尺寸的边缘等间距，固定两端且取整误差不超过 1 像素', () => {
  for (const [command, axis, size] of [['distribute-x', 'x', 'w'], ['distribute-y', 'y', 'h']]) {
    const widgets = [widget('a', 10, 10, 35, 35), widget('b', 95, 95, 60, 60), widget('c', 220, 220, 25, 25), widget('d', 411, 411, 75, 75)]
    const result = arranged(widgets, command)
    assert.equal(result[0].layout[axis], widgets[0].layout[axis])
    assert.equal(result.at(-1).layout[axis], widgets.at(-1).layout[axis])
    const gaps = result.slice(1).map((item, index) => item.layout[axis] - result[index].layout[axis] - result[index].layout[size])
    assert.ok(Math.max(...gaps) - Math.min(...gaps) <= 1)
    assert.deepEqual(arrangeDashboardWidgets(result, command, canvas), [], '重复布局不产生额外变更')
  }
})

test('分布固定按起点排序的端点，即使最宽项超过末项边缘也不移动端点', () => {
  const widgets = [widget('end', 150, 0, 20, 20), widget('wide', 0, 0, 200, 20), widget('middle', 100, 0, 20, 20)]
  const result = arranged(widgets, 'distribute-x')
  assert.deepEqual(result.map(item => item.layout.x), [150, 0, 165])
})

test('组合按外接矩形计数；未分组项和空组合名各自独立', () => {
  const widgets = [widget('a', 0, 0, 20, 20, { groupId: 'g' }), widget('b', 50, 40, 30, 30, { groupId: 'g' }), widget('c', 100, 100, 20, 20), widget('d', 150, 150, 20, 20, { groupId: '' })]
  assert.equal(dashboardLayoutUnitCount(widgets), 3)
  assert.equal(dashboardLayoutUnitCount(widgets.slice(0, 2)), 1)
  assert.deepEqual(arrangeDashboardWidgets(widgets.slice(0, 2), 'left', canvas), [])
  assert.deepEqual(arrangeDashboardWidgets(widgets.slice(0, 3), 'distribute-x', canvas), [])
})

test('所有布局操作整体移动组合，共享位移并保持成员相对位置', () => {
  const widgets = [
    widget('a', 100, 100, 20, 20, { groupId: 'group' }),
    widget('b', 145, 160, 40, 50, { groupId: 'group' }),
    widget('c', 40, 40, 25, 25),
    widget('d', 510, 420, 80, 70),
  ]
  for (const command of commands) {
    const result = arranged(widgets, command)
    assert.equal(result[1].layout.x - result[0].layout.x, 45, command)
    assert.equal(result[1].layout.y - result[0].layout.y, 60, command)
    const changes = arrangeDashboardWidgets(widgets, command, canvas)
    const groupChanges = changes.filter(item => ['a', 'b'].includes(item.id))
    assert.equal(groupChanges.length, 2, `${command} 应移动组合整体`)
    assert.equal(groupChanges[0].x - widgets[0].layout.x, groupChanges[1].x - widgets[1].layout.x)
    assert.equal(groupChanges[0].y - widgets[0].layout.y, groupChanges[1].y - widgets[1].layout.y)
  }
})

test('组合分布计算组合外接尺寸和组间边缘距离', () => {
  const widgets = [widget('a', 20, 10, 20, 20), widget('g1', 80, 10, 20, 20, { groupId: 'g' }), widget('g2', 120, 40, 40, 40, { groupId: 'g' }), widget('b', 310, 20, 50, 50)]
  const result = arranged(widgets, 'distribute-x')
  assert.deepEqual(result.map(item => item.layout.x), [20, 135, 175, 310])
  assert.equal(result[1].layout.x - (result[0].layout.x + result[0].layout.w), 95)
  assert.equal(result[3].layout.x - (result[2].layout.x + result[2].layout.w), 95)
})

test('任意锁定成员阻止整个布局，不跳过锁定成员或破坏组合', () => {
  for (const grouped of [false, true]) {
    for (const command of commands) {
      const widgets = sample()
      widgets[1].state.locked = true
      if (grouped) widgets[0].groupId = widgets[1].groupId = 'locked-group'
      assert.deepEqual(arrangeDashboardWidgets(widgets, command, canvas), [])
    }
  }
})

test('空选择、数量不足、未知命令及已有布局均不产生变更', () => {
  for (const value of [undefined, null, [], {}]) {
    assert.equal(dashboardLayoutUnitCount(value), 0)
    assert.deepEqual(arrangeDashboardWidgets(value, 'left', canvas), [])
  }
  const widgets = sample()
  for (const command of commands) assert.deepEqual(arrangeDashboardWidgets(widgets.slice(0, 1), command, canvas), [])
  for (const command of ['distribute-x', 'distribute-y']) assert.deepEqual(arrangeDashboardWidgets(widgets.slice(0, 2), command, canvas), [])
  for (const command of [undefined, null, 'unknown', '__proto__']) assert.deepEqual(arrangeDashboardWidgets(widgets, command, canvas), [])
  assert.deepEqual(arrangeDashboardWidgets([widget('a', 10, 20, 20, 20), widget('b', 10, 50, 20, 20)], 'left', canvas), [])
})

test('无效坐标、尺寸、重复标识和非有限计算安全返回空变更', () => {
  for (const key of ['x', 'y', 'w', 'h']) {
    const invalid = [NaN, Infinity, -Infinity, undefined, null, '20']
    if (key === 'w' || key === 'h') invalid.push(0, -1)
    for (const value of invalid) {
      const widgets = sample()
      widgets[1].layout[key] = value
      assert.equal(dashboardLayoutUnitCount(widgets), 0)
      for (const command of commands) assert.deepEqual(arrangeDashboardWidgets(widgets, command, canvas), [])
    }
  }
  for (const invalid of [null, {}, { id: 'bad' }, { ...sample()[0], id: null }, sample()[0]]) {
    const widgets = [...sample(), invalid]
    assert.deepEqual(arrangeDashboardWidgets(widgets, 'left', canvas), [])
  }
  const extreme = [widget('a', -1e308, 0, 1, 20), widget('b', 1e308, 0, 1, 20)]
  assert.deepEqual(arrangeDashboardWidgets(extreme, 'center', canvas), [])
  for (const area of [undefined, null, {}, { width: Infinity, height: 800 }, { width: 1000, height: 0 }, { width: '1000', height: 800 }]) {
    assert.deepEqual(arrangeDashboardWidgets(sample(), 'left', area), [])
  }
})

test('不修改输入，只输出实际变更坐标，保留尺寸、层级、旋转与未知配置', () => {
  const widgets = sample()
  widgets[0].style = { custom: true }
  const before = structuredClone(widgets)
  for (const item of widgets) {
    Object.freeze(item.layout)
    Object.freeze(item.state)
    Object.freeze(item)
  }
  Object.freeze(widgets)
  for (const command of commands) {
    const changes = arrangeDashboardWidgets(widgets, command, Object.freeze({ ...canvas }))
    for (const change of changes) assert.deepEqual(Object.keys(change).sort(), ['id', 'x', 'y'])
    assert.deepEqual(widgets, before)
  }
  assert.deepEqual(arrangeDashboardWidgets(widgets, 'left', canvas).map(item => item.id), ['b', 'c'])
})

test('画布边界钳制组合共享位移，负坐标和越界成员仍保持内部距离', () => {
  const widgets = [widget('a', -30, -20, 20, 20, { groupId: 'g' }), widget('b', 40, 50, 40, 40, { groupId: 'g' }), widget('c', 230, 190, 40, 40)]
  for (const command of ['left', 'right', 'top', 'bottom', 'center', 'middle']) {
    const result = arranged(widgets, command, { width: 250, height: 200 })
    assert.equal(result[1].layout.x - result[0].layout.x, 70, command)
    assert.equal(result[1].layout.y - result[0].layout.y, 70, command)
    for (const item of result) {
      assert.ok(item.layout.x >= 0 && item.layout.x + item.layout.w <= 250, command)
      assert.ok(item.layout.y >= 0 && item.layout.y + item.layout.h <= 200, command)
    }
  }
})

test('超大组合贴近画布原点而不挤压成员，分数组合移动也保持精确相对位置', () => {
  const widgets = [widget('a', 50.25, 30.5, 50, 20, { groupId: 'g' }), widget('b', 190.5, 140.75, 100, 80, { groupId: 'g' }), widget('c', 120, 90, 20, 20)]
  const result = arranged(widgets, 'right', { width: 200, height: 150 })
  assert.equal(result[0].layout.x, 0)
  assert.equal(result[0].layout.y, 0)
  assert.equal(result[1].layout.x - result[0].layout.x, 140.25)
  assert.equal(result[1].layout.y - result[0].layout.y, 110.25)
  assert.deepEqual(result.map(item => [item.layout.w, item.layout.h, item.layout.z, item.layout.rotate]), widgets.map(item => [item.layout.w, item.layout.h, item.layout.z, item.layout.rotate]))
})

test('整体平移共享整数位移且不修改输入，只返回位置变更', () => {
  const widgets = sample()
  const before = structuredClone(widgets)
  const changes = translateDashboardWidgets(widgets, 12.6, -8.2, canvas)
  assert.deepEqual(changes, widgets.map(item => ({ id: item.id, x: item.layout.x + 13, y: item.layout.y - 8 })))
  assert.deepEqual(widgets, before)
  for (const change of changes) assert.deepEqual(Object.keys(change).sort(), ['id', 'x', 'y'])
})

test('拖动和方向键到达四个边界时所有成员保持原始相对位置', () => {
  const widgets = sample()
  for (const [dx, dy, expectedX, expectedY] of [[-200, 0, -40, 0], [1000, 0, 530, 0], [0, -200, 0, -30], [0, 1000, 0, 340], [1000, -1000, 530, -30]]) {
    const changes = translateDashboardWidgets(widgets, dx, dy, canvas)
    assert.deepEqual(changes, widgets.map(item => ({ id: item.id, x: item.layout.x + expectedX, y: item.layout.y + expectedY })))
    const moved = widgets.map((item, index) => ({ ...item, layout: { ...item.layout, x: changes[index].x, y: changes[index].y } }))
    assert.deepEqual(translateDashboardWidgets(moved, dx, dy, canvas), [], '继续向同一边界移动应为空操作')
  }
})

test('平移锁定选择、零位移、无效输入时不产生变更', () => {
  const widgets = sample()
  for (const [dx, dy] of [[0, 0], [0.1, -0.1], [NaN, 1], [1, Infinity], ['5', 0]]) {
    assert.deepEqual(translateDashboardWidgets(widgets, dx, dy, canvas), [])
  }
  for (const value of [null, [], [null]]) assert.deepEqual(translateDashboardWidgets(value, 10, 10, canvas), [])
  assert.deepEqual(translateDashboardWidgets(widgets, 10, 10, { width: 0, height: 800 }), [])
  widgets[1].state.locked = true
  assert.deepEqual(translateDashboardWidgets(widgets, 10, 10, canvas), [])
})

test('包含多个组合的整体平移不拆分选择，分数边界仍使用共享整数位移', () => {
  const widgets = [widget('a', 10.25, 20.5, 30, 20, { groupId: 'first' }), widget('b', 80.75, 110.25, 40, 30, { groupId: 'second' }), widget('c', 140.5, 170.5, 40, 30, { groupId: 'second' })]
  const result = translateDashboardWidgets(widgets, 100, -100, { width: 200, height: 250 })
  assert.deepEqual(result, widgets.map(item => ({ id: item.id, x: item.layout.x + 19, y: item.layout.y - 20 })))
  assert.equal(result[2].x - result[0].x, 130.25)
  assert.equal(result[2].y - result[0].y, 150)
})
