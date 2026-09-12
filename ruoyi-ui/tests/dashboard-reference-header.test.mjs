import test from 'node:test'
import assert from 'node:assert/strict'
import { referenceSchema, applyReferenceHeader, referenceHeaderWidgets } from '../src/views/dashboard/referencePresets.js'

const keys = ['overview', 'progress', 'quality', 'safety', 'hse', 'smart']
const clone = value => JSON.parse(JSON.stringify(value))

test('六页模板采用相同进度页页头，导航只改变当前页高亮', () => {
  const baseline = referenceSchema('progress')
  for (const key of keys) {
    const schema = referenceSchema(key)
    const expected = applyReferenceHeader(schema, key, baseline)
    assert.deepEqual(referenceHeaderWidgets(schema), referenceHeaderWidgets(expected))
    const active = referenceHeaderWidgets(schema).filter(widget => widget.type === 'button' && widget.style.backgroundColor === '#064779')
    assert.equal(active.length, 1)
    assert.equal(active[0].interaction.targetPageCode, `xinghua-${key}`)
  }
})

test('从发布基准复制页头保留目标ID、自定义业务、资源、数据和schema设置，重复执行不再变化', () => {
  const baseline = clone(referenceSchema('progress'))
  baseline.widgets[1].style.fontSize = 39 // 使用调用者传入的系统基准，而非写死模板副本。
  const schema = clone(referenceSchema('smart'))
  schema.widgets[0].layout.h = 100
  schema.widgets[6].layout = { x: 10, y: 112, w: 308, h: 54, z: 20 }
  schema.widgets[12].layout.y = 176
  schema.widgets.push({ id: 'user-watermark', type: 'text', name: '用户新增', style: { text: '自定义' }, layout: { x: 4, y: 105, w: 10, h: 5 } })
  schema.canvas.custom = '保留画布设置'
  schema.widgets[20].binding.custom = '保留数据绑定'
  const before = clone(schema)
  const headerIds = new Set(referenceHeaderWidgets(schema).map(widget => widget.id))
  const updated = applyReferenceHeader(schema, 'smart', baseline)
  assert.deepEqual(schema, before)
  assert.deepEqual(updated.canvas, before.canvas)
  assert.deepEqual(updated.widgets.filter(widget => !headerIds.has(widget.id)), before.widgets.filter(widget => !headerIds.has(widget.id)))
  assert.deepEqual(updated.widgets.map(widget => widget.id), before.widgets.map(widget => widget.id))
  assert.equal(updated.widgets[1].style.fontSize, 39)
  assert.deepEqual(applyReferenceHeader(updated, 'smart', baseline), updated)
})

test('旧质量/HSE指标只在顶行避让页头，不移动业务面板或改变指标数据', () => {
  const baseline = referenceSchema('progress')
  for (const [key, oldY, oldH, bodyY] of [['quality', 97, 95, 206], ['hse', 93, 87, 190]]) {
    const schema = clone(referenceSchema(key))
    for (let start = 14; start < 32; start += 3) {
      Object.assign(schema.widgets[start].layout, { y: oldY, h: oldH })
      Object.assign(schema.widgets[start + 1].layout, { y: oldY + 11 })
      Object.assign(schema.widgets[start + 2].layout, { y: oldY + 10, h: oldH - 17 })
    }
    const before = clone(schema)
    const updated = applyReferenceHeader(schema, key, baseline)
    assert.deepEqual(updated.widgets.slice(32), before.widgets.slice(32))
    for (let start = 14; start < 32; start += 3) {
      const [frame, icon, value] = updated.widgets.slice(start, start + 3)
      assert.equal(frame.layout.y, 112)
      assert.ok(frame.layout.y + frame.layout.h < bodyY)
      for (const component of [icon, value]) {
        assert.ok(component.layout.y >= frame.layout.y)
        assert.ok(component.layout.y + component.layout.h <= frame.layout.y + frame.layout.h)
        const original = before.widgets.find(widget => widget.id === component.id)
        assert.deepEqual(component.binding, original.binding)
        assert.deepEqual(component.style, original.style)
      }
    }
    assert.deepEqual(applyReferenceHeader(updated, key, baseline), updated)
  }
})

test('缺失或重复页头组件时停止迁移，避免按位置误改内容', () => {
  const baseline = referenceSchema('progress')
  const missing = referenceSchema('overview')
  missing.widgets.splice(0, 1)
  assert.throws(() => applyReferenceHeader(missing, 'overview', baseline), /页头组件缺失/)
  const duplicate = referenceSchema('overview')
  duplicate.widgets.push({ ...clone(duplicate.widgets[0]), id: 'duplicate-frame' })
  assert.throws(() => applyReferenceHeader(duplicate, 'overview', baseline), /页头组件重复/)
})
