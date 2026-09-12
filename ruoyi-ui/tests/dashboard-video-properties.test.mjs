import test from 'node:test'
import assert from 'node:assert/strict'
import { dashboardVideoAutoplay } from '../src/utils/dashboardPresentation.js'

test('资源就绪仅在配置自动播放时启动，并按界面约定保持静音', async () => {
  let plays = 0
  const target = { muted: false, play: async () => { plays++ } }
  assert.equal(await dashboardVideoAutoplay({ target }, { autoplay: false }), false)
  assert.equal(plays, 0)
  assert.equal(await dashboardVideoAutoplay({ target }, { autoplay: true }), true)
  assert.equal(plays, 1)
  assert.equal(target.muted, true)
})
test('浏览器拒绝自动播放时不产生未处理异常', async () => {
  const target = { play: async () => { throw new Error('NotAllowedError') } }
  assert.equal(await dashboardVideoAutoplay({ target }, { autoplay: true }), false)
})
