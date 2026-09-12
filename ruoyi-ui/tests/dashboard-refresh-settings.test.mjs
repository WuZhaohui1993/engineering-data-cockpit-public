import test from 'node:test'
import assert from 'node:assert/strict'
import { createFunctionHarness, designerPath, readDashboardSfc } from './helpers/dashboard-property-audit.mjs'

const designer = await readDashboardSfc(designerPath)
const clone = value => JSON.parse(JSON.stringify(value))

test('每日数据更新时间在设计器加载、保存、再次加载后保持原值', async () => {
  const h = await createFunctionHarness(designer)
  for (const at of ['00:00', '08:00', '09:05', '16:30', '23:59']) {
    h.schema = h.normalizeSchema({ refresh: { enabled: true, mode: 'daily', seconds: 60, at } })
    assert.equal(h.schema.refresh.at, at, `加载 ${at} 不得重置为默认时间`)
    const before = clone(h.schema.refresh)
    const json = h.schemaJson()
    assert.deepEqual(JSON.parse(json).refresh, before)
    assert.deepEqual(clone(h.normalizeSchema(json).refresh), before)
    assert.deepEqual(clone(h.schema.refresh), before, '保存不得修改编辑中的刷新配置')
  }
})

test('无效每日时间回落安全默认值，关闭开关仍保存原有策略和时间', async () => {
  const h = await createFunctionHarness(designer)
  for (const at of ['', '24:00', '16:60', '9:05', 'invalid']) {
    assert.equal(h.normalizeSchema({ refresh: { mode: 'daily', at } }).refresh.at, '08:00')
  }
  h.schema = h.normalizeSchema({ refresh: { enabled: false, mode: 'daily', seconds: 45, at: '16:30' } })
  const restored = h.normalizeSchema(h.schemaJson()).refresh
  assert.deepEqual(clone(restored), { enabled: false, mode: 'daily', seconds: 45, at: '16:30' })
})

test('组件数据更新间隔只保存跟随默认或 5–3600 秒整数，与运行周期一致', async () => {
  const h = await createFunctionHarness(designer)
  h.beginHistory = () => {}
  h.endHistory = () => {}
  const cases = [
    [0, 0], [-1, 0], [1, 5], [4, 5], [5, 5], [60, 60],
    [3600, 3600], [3601, 3600], ['10', 10], ['', 0], [null, 0],
    [undefined, 0], ['invalid', 0], [5.4, 5], [5.6, 6],
  ]
  for (const [input, expected] of cases) {
    h.schema = h.normalizeSchema({ widgets: [{ id: 'table', type: 'table' }] })
    h.selectedWidget.value = h.schema.widgets[0]
    h.setWidgetRefresh(input)
    assert.equal(h.selectedWidget.value.binding.refreshSeconds, expected, `输入 ${String(input)}`)
    const restored = h.normalizeSchema(h.schemaJson())
    assert.equal(restored.widgets[0].binding.refreshSeconds, expected, '保存和加载不改变间隔')
  }
})
