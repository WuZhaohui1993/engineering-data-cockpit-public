import test from 'node:test'
import assert from 'node:assert/strict'
import { calculateFullscreenScale } from '../src/utils/dashboardViewport.js'
import { createFunctionHarness, readDashboardSfc, runtimePath } from './helpers/dashboard-property-audit.mjs'

const closeTo = (actual, expected, message) =>
  assert.ok(Math.abs(actual - expected) < 1e-9, `${message}: ${actual} !== ${expected}`)

test('全屏缩放：高画布 cover 铺满宽度，contain 完整显示高度', () => {
  const viewport = [1200, 700]
  const contain = calculateFullscreenScale(1920, 3000, ...viewport, 'contain')
  const cover = calculateFullscreenScale(1920, 3000, ...viewport, 'cover')
  closeTo(contain.x, 700 / 3000, '高画布 contain x')
  closeTo(contain.y, 700 / 3000, '高画布 contain y')
  closeTo(cover.x, 1200 / 1920, '高画布 cover x')
  closeTo(cover.y, 1200 / 1920, '高画布 cover y')
  assert.ok(3000 * cover.y > viewport[1], 'cover 高画布应产生可滚动的垂直溢出')
})

test('全屏缩放：宽画布 cover 铺满高度，contain 完整显示宽度', () => {
  const viewport = [1200, 700]
  const contain = calculateFullscreenScale(3000, 1000, ...viewport, 'contain')
  const cover = calculateFullscreenScale(3000, 1000, ...viewport, 'cover')
  closeTo(contain.x, 1200 / 3000, '宽画布 contain x')
  closeTo(contain.y, 1200 / 3000, '宽画布 contain y')
  closeTo(cover.x, 700 / 1000, '宽画布 cover x')
  closeTo(cover.y, 700 / 1000, '宽画布 cover y')
  assert.ok(3000 * cover.x > viewport[0], 'cover 宽画布应产生可滚动的水平溢出')
})

test('全屏缩放：stretch 分别使用视口宽高比例', () => {
  const scale = calculateFullscreenScale(1920, 1080, 1200, 700, 'stretch')
  closeTo(scale.x, 1200 / 1920, 'stretch x')
  closeTo(scale.y, 700 / 1080, 'stretch y')
  assert.notEqual(scale.x, scale.y, 'stretch 不应强制等比')
})

test('全屏缩放：非法尺寸返回安全比例', () => {
  for (const values of [[0, 1080, 1200, 700], [1920, -1, 1200, 700], [1920, 1080, NaN, 700], [1920, 1080, 1200, Infinity]]) {
    assert.deepEqual(calculateFullscreenScale(...values, 'cover'), { x: 1, y: 1 })
  }
})

test('runtime fitStage 使用滚动条扣除后的可用尺寸重新计算 cover 比例', async () => {
  const runtime = await readDashboardSfc(runtimePath)
  const viewport = { clientWidth: 1200, clientHeight: 700 }
  const harness = await createFunctionHarness(runtime, {
    stageViewport: { value: viewport },
    canvasWidth: { value: 1920 },
    canvasHeight: { value: 3000 },
    isFullscreen: { value: true },
    isEmbedMode: { value: false },
    isShareMode: { value: false },
    stageScale: { value: 1 },
    stageScaleX: { value: 1 },
    stageScaleY: { value: 1 },
    schema: { value: { canvas: { fullscreenScaleMode: 'cover' }, widgets: [] } },
  })
  harness.fitStage()
  closeTo(harness.stageScaleX.value, 1200 / 1920, '无滚动条时 cover x')
  closeTo(harness.stageScaleY.value, 1200 / 1920, '无滚动条时 cover y')

  // A vertical scrollbar reduces the usable width. The production fitStage
  // must be rerun with the observer's new clientWidth rather than retaining
  // the previous scale and leaving a one-frame overflow mismatch.
  viewport.clientWidth = 1183
  harness.fitStage()
  closeTo(harness.stageScaleX.value, 1183 / 1920, '出现滚动条后 cover x')
  closeTo(harness.stageScaleY.value, 1183 / 1920, '出现滚动条后 cover y')
  assert.ok(harness.stageScaleX.value < 1200 / 1920)
})
