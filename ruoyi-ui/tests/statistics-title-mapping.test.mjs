import test from 'node:test'
import assert from 'node:assert/strict'
import {
  dashboardStatisticsBodyTitleVisible, dashboardWidgetTitleVisible,
} from '../src/utils/dashboardPresentation.js'
import { dashboardPropertyVisible } from '../src/utils/dashboardComponentCapabilities.js'
import {
  createFunctionHarness, designerPath, runtimePath, readDashboardSfc,
} from './helpers/dashboard-property-audit.mjs'

const [designer, runtime] = await Promise.all([readDashboardSfc(designerPath), readDashboardSfc(runtimePath)])
const [design, run] = await Promise.all([createFunctionHarness(designer), createFunctionHarness(runtime)])
const makeWidget = (row = {}, fieldMap = {}, style = {}) => ({
  id: 'statistics-title', type: 'statistics',
  style: { title: '手工标题', unit: '%', statsTitleVersion: 1, titleVisible: true, ...style },
  binding: { sourceType: 'STATIC', staticRows: [row], fieldMap },
})
const schemaOf = widget => ({ canvas: { width: 1920, height: 1080 }, widgets: [widget] })

for (const image of [false, true]) {
  for (const visible of [false, true]) {
    test(`统计唯一标题：切图 ${image} / 可见 ${visible}，隐藏后仍可编辑手工标题`, () => {
      const widget = makeWidget({}, {}, { titleVisible: visible, titleImageEnabled: image, titleImageRef: image ? '/title.png' : '' })
      const heading = dashboardWidgetTitleVisible(widget)
      const body = dashboardStatisticsBodyTitleVisible(widget)
      assert.equal(Number(heading) + Number(body), visible ? 1 : 0)
      assert.equal(heading, image && visible)
      assert.equal(body, !image && visible)
      assert.equal(dashboardPropertyVisible(widget, 'style.title'), true)
      assert.equal(dashboardPropertyVisible(widget, 'style.statsLabelSize'), visible && !image)
      assert.equal(dashboardPropertyVisible(widget, 'style.titleFontSize'), visible && image)
    })
  }
}

test('旧版关闭标题栏的统计保留正文标题，潜伏切图不意外启用；新显隐经保存重开不变', () => {
  const old = makeWidget({ value: 12 }, {}, { titleVisible: false, titleImageEnabled: true, titleImageRef: '/old.png' })
  delete old.style.statsTitleVersion
  for (const harness of [design, run]) {
    const migrated = harness.normalizeSchema(schemaOf(old)).widgets[0]
    assert.equal(migrated.style.statsTitleVersion, 1)
    assert.equal(migrated.style.titleVisible, true)
    assert.equal(migrated.style.titleImageEnabled, false)
    assert.equal(dashboardStatisticsBodyTitleVisible(migrated), true)
  }
  design.schema = design.normalizeSchema(schemaOf(old))
  design.schema.widgets[0].style.titleVisible = false
  design.schema.widgets[0].style.title = '隐藏时修改的标题'
  const saved = JSON.parse(design.schemaJson())
  for (const harness of [design, run]) {
    const reopened = harness.normalizeSchema(saved).widgets[0]
    assert.equal(reopened.style.title, '隐藏时修改的标题')
    assert.equal(reopened.style.titleVisible, false)
    assert.equal(reopened.style.statsTitleVersion, 1)
    assert.equal(dashboardWidgetTitleVisible(reopened), false)
    assert.equal(dashboardStatisticsBodyTitleVisible(reopened), false)
  }
})

test('旧版开启切图的统计保留图片标题栏，正文不再重复标题', () => {
  const old = makeWidget({}, {}, { titleImageEnabled: true, titleImageRef: '/old.png' })
  delete old.style.statsTitleVersion
  for (const harness of [design, run]) {
    const migrated = harness.normalizeSchema(schemaOf(old)).widgets[0]
    assert.equal(dashboardWidgetTitleVisible(migrated), true)
    assert.equal(dashboardStatisticsBodyTitleVisible(migrated), false)
  }
})

const titleCases = [
  { name: '无映射时不被数据中同名标题覆盖', row: { label: '意外标题', value: 12 }, map: {}, expected: '手工标题' },
  { name: '映射标题优先于手工标题', row: { metricName: '映射标题', value: 12 }, map: { label: 'metricName' }, expected: '映射标题' },
  { name: '映射字段不存在时不误取第一列', row: { label: '意外标题', value: 12 }, map: { label: 'missing' }, expected: '手工标题' },
  { name: '映射空字符串回退手工标题', row: { metricName: '', value: 12 }, map: { label: 'metricName' }, expected: '手工标题' },
  { name: '映射 null 回退手工标题', row: { metricName: null, value: 12 }, map: { label: 'metricName' }, expected: '手工标题' },
  { name: '映射数值零可正常显示', row: { metricName: 0, value: 12 }, map: { label: 'metricName' }, expected: '0' },
]
for (const item of titleCases) {
  test(`设计画布、切图标题、运行页同源：${item.name}`, () => {
    const widget = makeWidget(item.row, item.map)
    run.widgetStates[widget.id] = { rows: [item.row] }
    assert.equal(design.previewStatistic(widget, 'label', widget.style.title), item.expected)
    assert.equal(run.statisticsValue(widget, 'label', widget.style.title), item.expected)
    assert.equal(design.previewWidgetTitle(widget), item.expected)
    assert.equal(run.statisticsHeadingTitle(widget), item.expected)
  })
}

test('缺失映射的数值、对比标签、对比值和升降状态不串用其他列，设计器与运行态保持一致', () => {
  const row = { name: '不要误取', value: 42, compare: 99, compareState: 'down' }
  const widget = makeWidget(row, { value: 'missing', compareLabel: 'missing', compareValue: 'missing', compareState: 'missing' })
  run.widgetStates[widget.id] = { rows: [row] }
  for (const role of ['value', 'compareLabel', 'compareValue', 'compareState']) {
    assert.equal(design.previewStatistic(widget, role, '回退'), '回退')
    assert.equal(run.statisticsValue(widget, role, '回退'), '回退')
  }
  assert.equal(design.previewStatisticsCompareText(widget), '环比 —')
  assert.equal(run.statisticsCompareText(widget), '—')
  assert.equal(design.previewStatisticsCompareClass(widget), run.statisticsCompareClass(widget))
})

test('未映射对比字段只识别对应语义列，不把首列文本显示成对比标签或状态', () => {
  const row = { misleading: 'down', value: 42, compareValue: 0 }
  const widget = makeWidget(row)
  run.widgetStates[widget.id] = { rows: [row] }
  assert.equal(design.previewStatisticsCompareText(widget), '环比 ↑ 0')
  assert.equal(run.statisticsCompareText(widget), '↑ 0')
  assert.equal(design.previewStatisticsCompareClass(widget), 'compare-up')
  assert.equal(run.statisticsCompareClass(widget), 'compare-up')
})
