import test from 'node:test'
import assert from 'node:assert/strict'
import {
  auditInspector, createFunctionHarness, designerPath, runtimePath, readDashboardSfc,
} from './helpers/dashboard-property-audit.mjs'

const [designer, runtime] = await Promise.all([readDashboardSfc(designerPath), readDashboardSfc(runtimePath)])
const [design, run, inspector] = await Promise.all([
  createFunctionHarness(designer), createFunctionHarness(runtime), auditInspector(designer),
])

function statisticsWidget(row, fieldMap = {}, unit = '%') {
  return {
    id: 'statistics-suffix', type: 'statistics', style: { unit },
    binding: { sourceType: 'STATIC', staticRows: [row], fieldMap },
  }
}

test('统计概览在样式面板中提供唯一的数值后缀输入，并复用已有单位配置', () => {
  const fields = inspector.fields.filter(field =>
    field.binding === 'selectedWidget.style.unit' && field.possibleTypes.includes('statistics'),
  )
  assert.equal(fields.length, 1)
  assert.equal(fields[0].label, '数值后缀')
  assert.equal(fields[0].tab, '样式')
  assert.equal(fields[0].control, 'el-input')
  assert.ok(fields[0].events.some(event => event.event === 'change'), '输入修改应参与保存状态或历史记录')
})

const cases = [
  { name: '未绑定单位字段时显示配置的百分号', row: { value: 61.5, suffix: '人' }, map: {}, expected: '%' },
  { name: '非空单位字段值优先于配置后缀', row: { value: 61.5, unitText: '人' }, map: { suffix: 'unitText' }, expected: '人' },
  { name: '单位字段为空字符串时回退配置后缀', row: { value: 61.5, unitText: '' }, map: { suffix: 'unitText' }, expected: '%' },
  { name: '单位字段为 null 时回退配置后缀', row: { value: 61.5, unitText: null }, map: { suffix: 'unitText' }, expected: '%' },
  { name: '单位字段为 undefined 时回退配置后缀', row: { value: 61.5, unitText: undefined }, map: { suffix: 'unitText' }, expected: '%' },
  { name: '映射字段缺失时回退配置后缀，不误取首字段或其他单位字段', row: { value: 61.5, suffix: '亿元' }, map: { suffix: 'unitText' }, expected: '%' },
  { name: '单位字段为数值零时保留零', row: { value: 61.5, unitText: 0 }, map: { suffix: 'unitText' }, expected: '0' },
]

for (const scenario of cases) {
  test(`统计概览设计器与运行页：${scenario.name}`, () => {
    const widget = statisticsWidget(scenario.row, scenario.map)
    run.widgetStates[widget.id] = { rows: [scenario.row] }
    const fallback = widget.style.unit || ''
    assert.equal(design.previewStatistic(widget, 'suffix', fallback), scenario.expected)
    assert.equal(run.statisticsValue(widget, 'suffix', fallback), scenario.expected)
  })
}

test('百分号后缀经过保存、设计器重开及运行配置还原后保持生效', () => {
  design.schema = design.normalizeSchema({
    canvas: { width: 1920, height: 1080 },
    widgets: [statisticsWidget({ value: 61.5 }, { value: 'value' })],
  })
  const saved = JSON.parse(design.schemaJson())
  assert.equal(saved.widgets[0].style.unit, '%')
  const reopened = design.normalizeSchema(saved).widgets[0]
  const published = run.normalizeSchema(saved).widgets[0]
  assert.equal(reopened.style.unit, '%')
  assert.equal(published.style.unit, '%')
  assert.equal(design.previewStatistic(reopened, 'suffix', reopened.style.unit || ''), '%')
  run.widgetStates[published.id] = { rows: [{ value: 61.5 }] }
  assert.equal(run.statisticsValue(published, 'suffix', published.style.unit || ''), '%')
})

test('画布、组件预览及运行模板均向后缀解析传入同一单位配置', () => {
  const designCalls = [...designer.descriptor.template.content.matchAll(
    /previewStatistic\(\s*(widget|componentPreview\.widget),\s*["']suffix["'],\s*\1\.style\?\.unit\s*\|\|\s*["']["']\s*,?\s*\)/g,
  )].map(match => match[1])
  assert.deepEqual(designCalls, ['widget', 'componentPreview.widget'])
  assert.match(runtime.descriptor.template.content,
    /statisticsValue\(\s*widget,\s*["']suffix["'],\s*widget\.style\?\.unit\s*\|\|\s*["']["']\s*,?\s*\)/,
  )
})
