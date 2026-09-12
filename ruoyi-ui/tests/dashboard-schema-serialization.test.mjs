import test from 'node:test'
import assert from 'node:assert/strict'
import {
  chartRows, componentCatalog, createFunctionHarness, designerPath, runtimePath, readDashboardSfc,
} from './helpers/dashboard-property-audit.mjs'
import { dashboardComponentCapabilities } from '../src/utils/dashboardComponentCapabilities.js'

const [designer, runtime] = await Promise.all([readDashboardSfc(designerPath), readDashboardSfc(runtimePath)])
const [design, run] = await Promise.all([createFunctionHarness(designer), createFunctionHarness(runtime)])
const clone = value => JSON.parse(JSON.stringify(value))
const formTypes = ['filter-form', 'designer-form', 'online-form']

function serialize(widgets, extra = {}) {
  design.schema = design.normalizeSchema({ canvas: { width: 1920, height: 1080 }, widgets, ...extra })
  const before = clone(design.schema)
  const json = design.schemaJson()
  assert.deepEqual(clone(design.schema), before, '保存不能修改仍在编辑的对象')
  return { before, json, saved: JSON.parse(json), restored: clone(design.normalizeSchema(json)) }
}

for (const type of formTypes) {
  test(`${type} 的默认字段直接保存仍保留后端必需的字段数组及完整字段`, () => {
    const { before, saved, restored } = serialize([{ id: type, type }], {
      filters: [{ id: 'keyword_filter', parameter: 'keyword', type: 'STRING', label: '关键词' }],
    })
    const field = saved.widgets[0].style.formFields[0]
    assert.equal(saved.widgets[0].style.formFields.length, 1)
    assert.equal(field.name, 'keyword')
    assert.equal(field.parameter, 'keyword')
    assert.equal(field.type, 'STRING')
    assert.deepEqual(field.options, [])
    assert.equal(field.defaultValue, '')
    assert.ok(!Object.hasOwn(field, 'optionsJson'), '不保存可由options还原的编辑文本')
    assert.deepEqual(restored, before)
  })
}

for (const count of [1, 20]) {
  test(`查询表单 ${count} 个字段及空值、零值选项在保存后完整保留`, () => {
    const fields = Array.from({ length: count }, (_, index) => ({
      name: index ? `field${index}` : 'keyword',
      label: index ? `字段 ${index}` : '关键词',
      parameter: index ? `parameter${index}` : 'keyword',
      filterId: `filter${index}`,
      type: index % 2 ? 'RADIO' : 'SELECT',
      placeholder: '', defaultValue: index % 2 ? '' : 0,
      options: [{ label: '全部', value: '' }, { label: '零', value: 0 }, { label: '', value: false }],
    }))
    const filters = fields.map(field => ({ id: field.filterId, parameter: field.parameter, type: 'STRING' }))
    const { before, saved, restored } = serialize(
      formTypes.map(type => ({ id: type, type, style: { formFields: fields } })), { filters },
    )
    for (const widget of saved.widgets) {
      assert.equal(widget.style.formFields.length, count)
      assert.deepEqual(widget.style.formFields, fields)
      assert.equal(widget.style.formFields[0].options[1].value, 0)
      assert.equal(widget.style.formFields[0].options[0].value, '')
    }
    assert.deepEqual(restored, before)
  })
}

test('选项卡含默认项和部分修改项时完整保存，不产生null或缺字段对象', () => {
  const tabs = [
    { key: 'overview', label: '概览', content: '概览内容', widgetIds: [] },
    { key: 'detail', label: '明细', content: '', widgetIds: ['detail-text'] },
  ]
  const { before, saved, restored } = serialize([
    { id: 'tabs', type: 'tabs', style: { tabs } },
    { id: 'detail-text', type: 'text' },
  ])
  assert.deepEqual(saved.widgets[0].style.tabs, tabs)
  assert.deepEqual(restored, before)
})

test('静态数据、字段映射和交互映射作为业务配置整体保留', () => {
  const binding = {
    sourceType: 'STATIC', datasetCode: '', rowLimit: 50,
    fieldMap: { category: 'name', value: 'amount', valueFields: ['amount', 'planned'] },
    staticRows: [{ name: '', amount: 0, planned: null, active: false }, { name: '二', amount: 3, planned: 0 }],
    displayFields: [{ name: 'name', label: '' }, { name: 'amount', label: '数量', precision: 0 }],
    parameters: { keyword: '', amount: 0, enabled: false },
    filters: [{ field: 'amount', operator: 'eq', value: 0 }],
  }
  const interaction = {
    onClick: 'navigate', targetPageCode: 'project-progress', targetMode: 'published',
    parameterMappings: [{ sourceField: 'amount', targetParameter: 'amount', defaultValue: 0 }],
    targetWidgetIds: [], drilldown: { levels: [{ field: 'name', label: '', value: 0 }] },
  }
  const { before, saved, restored } = serialize([{ id: 'table', type: 'table', binding, interaction }])
  assert.deepEqual(saved.widgets[0].binding, before.widgets[0].binding)
  assert.deepEqual(saved.widgets[0].binding.staticRows, binding.staticRows)
  assert.deepEqual(saved.widgets[0].interaction.parameterMappings, interaction.parameterMappings)
  assert.deepEqual(saved.widgets[0].interaction.drilldown, interaction.drilldown)
  assert.deepEqual(restored, before)
})

test('全部组件的默认样式保存后可无损还原，运行容器和图表显示一致', () => {
  const widgets = componentCatalog(designer).map(({ type }) => ({
    id: `roundtrip-${type}`, type,
    binding: { sourceType: 'STATIC', fieldMap: { category: 'name', value: 'value' }, staticRows: chartRows },
  }))
  const { before, saved, restored } = serialize(widgets)
  assert.ok(widgets.length >= 53)
  assert.deepEqual(restored, before)
  const runtimeBefore = run.normalizeSchema(before)
  const runtimeAfter = run.normalizeSchema(saved)
  run.schema = { value: runtimeBefore }
  for (let index = 0; index < widgets.length; index++) {
    const a = runtimeBefore.widgets[index]
    const b = runtimeAfter.widgets[index]
    assert.deepEqual(clone(run.widgetStyle(b)), clone(run.widgetStyle(a)), `${a.type} 容器显示应一致`)
    assert.deepEqual(clone(b.style.chartConfig), clone(a.style.chartConfig), `${a.type} 图表配置应一致`)
    assert.equal(b.style.color, a.style.color, `${a.type} 不得回退到不同的运行默认颜色`)
    assert.equal(b.style.backgroundColor, a.style.backgroundColor, `${a.type} 不得改变背景透明度`)
    if (dashboardComponentCapabilities(a).chart) {
      assert.deepEqual(clone(run.buildOption(b, chartRows)), clone(run.buildOption(a, chartRows)), `${a.type} 图表输出应一致`)
    }
  }
})

test('未知属性、用户设置和隐藏的非默认配置不能被压缩丢弃', () => {
  const style = {
    mapZoom: 2, statsValueSize: 40, titlePaddingLeft: 0,
    extension: { value: 0, enabled: false, empty: '', values: [null, 0, ''] },
    futureArray: [{ key: '', value: 0 }, { key: 'two', value: false }],
  }
  const { before, saved, restored } = serialize([{ id: 'future', type: 'text', style }])
  for (const key of Object.keys(style)) assert.deepEqual(saved.widgets[0].style[key], style[key])
  assert.deepEqual(restored, before)
})

test('107组件的大屏压缩后低于512Ki配置限制，重复保存不会再次膨胀', () => {
  const types = ['border', 'text', 'button', 'decoration', 'icon', 'statistics', 'advanced-table', 'metric-card', 'progress', 'ring-chart', 'image', 'custom-chart', 'bar-chart']
  const widgets = Array.from({ length: 107 }, (_, index) => ({
    id: `large-${index}`, type: types[index % types.length], name: `组件 ${index}`,
    binding: { sourceType: 'STATIC', fieldMap: { category: 'name', value: 'value' }, staticRows: chartRows },
  }))
  const { before, saved, json, restored } = serialize(widgets)
  assert.ok(JSON.stringify(before).length > 512 * 1024, '测试确实覆盖打开设计器后超过大小上限的情况')
  assert.ok(json.length < 512 * 1024)
  assert.ok(Buffer.byteLength(json) < 512 * 1024)
  assert.deepEqual(restored, before)
  design.schema = design.normalizeSchema(saved)
  assert.deepEqual(JSON.parse(design.schemaJson()), saved)
})
