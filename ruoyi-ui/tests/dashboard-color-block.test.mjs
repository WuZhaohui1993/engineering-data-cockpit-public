import test from 'node:test'
import assert from 'node:assert/strict'
import { dashboardColorBlockValue } from '../src/utils/dashboardPresentation.js'

test('颜色字段可覆盖静态颜色，透明色和RGBA不会丢失', () => {
  for (const color of ['#f00', '#1234', '#123456', '#12345600', 'rgba(12, 24, 48, 0)']) {
    assert.equal(dashboardColorBlockValue(color, { blockColor: '#abcdef' }), color)
  }
})
test('数字指标、空值及非法色值回退组件颜色，不生成不可见色块', () => {
  for (const value of [82.6, 0, false, null, undefined, '', '#12345', 'url(https://example.com/a.png)']) {
    assert.equal(dashboardColorBlockValue(value, { blockColor: '#abcdef' }), '#abcdef')
  }
})
