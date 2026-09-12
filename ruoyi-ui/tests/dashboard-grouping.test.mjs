import test from 'node:test'
import assert from 'node:assert/strict'
import { getDashboardSelectionBounds, resizeDashboardGroup, translateDashboardClones } from '../src/utils/dashboardGrouping.js'

const canvas = { width: 1000, height: 800, snapToGrid: false }
const widget = (id, x, y, w, h, extra = {}) => ({ id, groupId: 'g', layout: { x, y, w, h, z: 4, rotate: 15 }, state: { locked: false }, ...extra })
const sample = () => [widget('a', 100, 100, 100, 100), widget('b', 250, 250, 150, 150)]
const close = (actual, expected, message) => assert.ok(Math.abs(actual - expected) < 1e-7, `${message || ''}: ${actual} != ${expected}`)
const resize = (widgets, handle, dx, dy, area = canvas) => resizeDashboardGroup(widgets, getDashboardSelectionBounds(widgets), handle, dx, dy, area)

test('外框覆盖全部成员且不要求每个成员属于同一组', () => {
  const widgets = sample()
  widgets[1].groupId = 'another'
  assert.deepEqual(getDashboardSelectionBounds(widgets), { x: 100, y: 100, w: 300, h: 300 })
  assert.deepEqual(getDashboardSelectionBounds(widgets.slice(1)), { x: 250, y: 250, w: 150, h: 150 })
})

for (const [handle, expected] of [
  ['nw', { x: 130, y: 160, w: 270, h: 240 }],
  ['n', { x: 100, y: 160, w: 300, h: 240 }],
  ['ne', { x: 100, y: 160, w: 330, h: 240 }],
  ['e', { x: 100, y: 100, w: 330, h: 300 }],
  ['se', { x: 100, y: 100, w: 330, h: 360 }],
  ['s', { x: 100, y: 100, w: 300, h: 360 }],
  ['sw', { x: 130, y: 100, w: 270, h: 360 }],
  ['w', { x: 130, y: 100, w: 270, h: 300 }],
]) {
  test(`${handle} 手柄只改变对应边，成员横纵分别共享缩放比`, () => {
    const widgets = sample(), result = resize(widgets, handle, 30, 60)
    const actual = getDashboardSelectionBounds(result)
    for (const key of ['x', 'y', 'w', 'h']) close(actual[key], expected[key], key)
    for (let index = 0; index < widgets.length; index++) {
      const original = widgets[index].layout, next = result[index].layout
      close(next.w / original.w, expected.w / 300)
      close(next.h / original.h, expected.h / 300)
      close(next.x, expected.x + (original.x - 100) * expected.w / 300)
      close(next.y, expected.y + (original.y - 100) * expected.h / 300)
    }
  })
}

test('缩到最小值时由最小成员限制整体比例，不单独挤压成员', () => {
  for (const handle of ['nw', 'n', 'ne', 'e', 'se', 's', 'sw', 'w']) {
    const dx = handle.includes('w') ? 999 : -999
    const dy = handle.includes('n') ? 999 : -999
    const result = resize(sample(), handle, dx, dy)
    const horizontal = handle.includes('e') || handle.includes('w')
    const vertical = handle.includes('n') || handle.includes('s')
    assert.equal(result[0].layout.w, horizontal ? 40 : 100)
    assert.equal(result[1].layout.w, horizontal ? 60 : 150)
    assert.equal(result[0].layout.h, vertical ? 40 : 100)
    assert.equal(result[1].layout.h, vertical ? 60 : 150)
    close(result[1].layout.x - result[0].layout.x, horizontal ? 60 : 150)
    close(result[1].layout.y - result[0].layout.y, vertical ? 60 : 150)
  }
})

test('各方向扩展到画布边界仍保持对边固定，网格吸附不能越界', () => {
  const area = { width: 997, height: 797, snapToGrid: true }
  for (const handle of ['nw', 'n', 'ne', 'e', 'se', 's', 'sw', 'w']) {
    const result = resize(sample(), handle, handle.includes('w') ? -5000 : 5000, handle.includes('n') ? -5000 : 5000, area)
    const bounds = getDashboardSelectionBounds(result)
    close(bounds.x, handle.includes('w') ? 0 : 100)
    close(bounds.y, handle.includes('n') ? 0 : 100)
    close(bounds.x + bounds.w, handle.includes('e') ? 997 : 400)
    close(bounds.y + bounds.h, handle.includes('s') ? 797 : 400)
    for (const { layout } of result) {
      assert.ok(layout.x >= 0 && layout.y >= 0)
      assert.ok(layout.x + layout.w <= area.width)
      assert.ok(layout.y + layout.h <= area.height)
    }
  }
})

test('只将拖动外框边缘吸附到 8 像素网格，保留成员缩放后的分数像素', () => {
  const widgets = [widget('a', 101, 102, 101, 104), widget('b', 253, 255, 150, 150)]
  const result = resize(widgets, 'se', 2, 3, { ...canvas, snapToGrid: true })
  const bounds = getDashboardSelectionBounds(result)
  close(bounds.x, 101)
  close(bounds.y, 102)
  close(bounds.x + bounds.w, 408)
  close(bounds.y + bounds.h, 408)
  assert.equal(Number.isInteger(result[0].layout.w), false)
  close(result[0].layout.w / 101, result[1].layout.w / 150)
  close(result[0].layout.h / 104, result[1].layout.h / 150)
})

test('拖回起点输出全部原始位置尺寸，多次计算不累计且不修改输入或其他布局字段', () => {
  const widgets = sample(), before = structuredClone(widgets), bounds = getDashboardSelectionBounds(widgets)
  for (const item of widgets) { Object.freeze(item.layout); Object.freeze(item.state); Object.freeze(item) }
  Object.freeze(widgets)
  const first = resizeDashboardGroup(widgets, bounds, 'se', 78, 26, canvas)
  resizeDashboardGroup(widgets, bounds, 'se', 100, 200, canvas)
  assert.deepEqual(resizeDashboardGroup(widgets, bounds, 'se', 78, 26, canvas), first)
  const restored = resizeDashboardGroup(widgets, bounds, 'se', 0, 0, { ...canvas, snapToGrid: true })
  assert.deepEqual(restored, widgets.map(({ id, layout: { x, y, w, h } }) => ({ id, layout: { x, y, w, h } })))
  assert.deepEqual(widgets, before)
})

test('锁定任一成员拒绝整组缩放', () => {
  const widgets = sample()
  widgets[1].state.locked = true
  assert.deepEqual(resize(widgets, 'se', 100, 100), [])
})

test('空选择、重复标识、无效坐标、外框、手柄和画布安全拒绝', () => {
  for (const value of [null, undefined, {}, [], [null], [widget('a', 0, 0, 50, 50), widget('a', 100, 100, 50, 50)]]) {
    assert.equal(getDashboardSelectionBounds(value), null)
    assert.deepEqual(resizeDashboardGroup(value, {}, 'se', 10, 10, canvas), [])
  }
  for (const key of ['x', 'y', 'w', 'h']) {
    for (const value of [null, undefined, NaN, Infinity, '50', ...(key === 'w' || key === 'h' ? [0, -1] : [])]) {
      const widgets = sample()
      widgets[0].layout[key] = value
      assert.equal(getDashboardSelectionBounds(widgets), null)
    }
  }
  for (const handle of [null, 'north', '', '__proto__']) assert.deepEqual(resize(sample(), handle, 10, 10), [])
  for (const area of [null, {}, { width: 0, height: 800 }, { width: 1000, height: Infinity }]) assert.deepEqual(resize(sample(), 'se', 10, 10, area), [])
  assert.deepEqual(resize(sample(), 'se', NaN, 10), [])
  assert.deepEqual(resize(sample(), 'se', 10, Infinity), [])
  assert.deepEqual(resizeDashboardGroup(sample(), { x: 90, y: 100, w: 310, h: 300 }, 'se', 10, 10, canvas), [])
})

test('复制到画布右下角共享有限位移，锁定组件亦可复制并保持间距', () => {
  const widgets = [widget('a', 500, 450, 100, 100), widget('b', 840, 620, 150, 170)]
  widgets[1].state.locked = true
  const before = structuredClone(widgets)
  for (const item of widgets) { Object.freeze(item.layout); Object.freeze(item) }
  Object.freeze(widgets)
  const clones = translateDashboardClones(widgets, 32, canvas)
  assert.deepEqual(clones.map(item => [item.layout.x, item.layout.y]), [[510, 460], [850, 630]])
  assert.deepEqual(clones.map(item => [item.layout.w, item.layout.h, item.layout.z, item.layout.rotate, item.groupId, item.state.locked]),
    widgets.map(item => [item.layout.w, item.layout.h, item.layout.z, item.layout.rotate, item.groupId, item.state.locked]))
  assert.deepEqual(widgets, before)
  for (let i = 0; i < widgets.length; i++) { assert.notEqual(clones[i], widgets[i]); assert.notEqual(clones[i].layout, widgets[i].layout) }
})

test('复制负位移、分数坐标和超大选择均整体限位，不压缩内部相对位置', () => {
  const widgets = [widget('a', 10.25, 20.5, 50, 60), widget('b', 190.5, 180.75, 100, 100)]
  const result = translateDashboardClones(widgets, -32, { width: 200, height: 150 })
  assert.deepEqual(result.map(item => [item.layout.x, item.layout.y]), [[0, 0], [180.25, 160.25]])
  close(result[1].layout.x - result[0].layout.x, widgets[1].layout.x - widgets[0].layout.x)
  close(result[1].layout.y - result[0].layout.y, widgets[1].layout.y - widgets[0].layout.y)
  assert.deepEqual(translateDashboardClones(sample(), 0, canvas), sample())
  assert.deepEqual(translateDashboardClones(sample(), NaN, canvas), [])
  assert.deepEqual(translateDashboardClones([], 24, canvas), [])
  assert.deepEqual(translateDashboardClones(sample(), 24, null), [])
})

test('分数缩放贴边后满足后端严格边界校验，复制同样消除机器精度越界', () => {
  const area = { width: 1920, height: 1080, snapToGrid: false }
  const widgets = [widget('a', 148, 183, 94, 273), widget('b', 1404, 623, 318, 113)]
  const checkBounds = items => {
    for (const { layout } of items) {
      assert.ok(layout.x >= 0 && layout.y >= 0)
      assert.ok(layout.w >= 40 && layout.h >= 40)
      assert.ok(layout.x + layout.w <= area.width, `${layout.x} + ${layout.w} 必须不大于 ${area.width}`)
      assert.ok(layout.y + layout.h <= area.height, `${layout.y} + ${layout.h} 必须不大于 ${area.height}`)
    }
  }
  for (const handle of ['nw', 'n', 'ne', 'e', 'se', 's', 'sw', 'w']) {
    const resized = resize(widgets, handle, handle.includes('w') ? -5000 : 5000, handle.includes('n') ? -5000 : 5000, area)
    assert.equal(resized.length, 2)
    checkBounds(resized)
    for (const offset of [-5000, 0, 24, 32, 5000]) {
      const clones = translateDashboardClones(resized, offset, area)
      checkBounds(clones)
      assert.deepEqual(clones.map(item => [item.layout.w, item.layout.h]), resized.map(item => [item.layout.w, item.layout.h]))
    }
  }
})
