import test from 'node:test'
import assert from 'node:assert/strict'
import {
  auditInspector, chartFixture, createFunctionHarness, designerPath, runtimePath,
  probeChartProperties, probeMapProperties, probeWidgetContainerProperties, readDashboardSfc,
} from './helpers/dashboard-property-audit.mjs'

const [designer, runtime] = await Promise.all([readDashboardSfc(designerPath), readDashboardSfc(runtimePath)])
const audit = await auditInspector(designer)

test('AST 盘点覆盖全部组件和属性页，并展开字段映射', () => {
  assert.equal(audit.components.length, new Set(audit.components.map(component => component.type)).size)
  assert.ok(audit.components.length >= 53, '组件减少需要显式审查')
  assert.ok(audit.fields.length >= 250, '属性面板抽取不能意外丢失')
  assert.deepEqual([...new Set(audit.fields.map(field => field.tab))], ['基础', '数据', '样式', '交互'])
  assert.ok(audit.fields.every(field => field.label && field.line > 0 && field.possibleTypes.length > 0))
  const roles = type => audit.components.find(component => component.type === type).fieldRoles.map(role => role.key)
  assert.deepEqual(Array.from(roles('weather')).sort(), ['city', 'condition', 'temperature'])
  assert.ok(roles('gantt-chart').includes('dependencies'))
  assert.ok(roles('funnel').includes('value'))
  assert.ok(roles('bar3d-chart').includes('category'))
})

test('属性面板不向文本/媒体开放数据绑定，不向自有控件开放通用点击动作', () => {
  const source = audit.fields.find(field => field.label === '数据来源')
  const action = audit.fields.find(field => field.binding === 'selectedWidget.interaction.onClick')
  for (const type of ['text', 'rich-text', 'image', 'video', 'button', 'iframe', 'tabs', 'filter-form']) assert.ok(!source.possibleTypes.includes(type), `${type} 不应显示数据绑定`)
  for (const type of ['filter-form', 'designer-form', 'online-form', 'tabs', 'video', 'iframe', 'custom-html']) assert.ok(!action.possibleTypes.includes(type), `${type} 不应显示通用点击行为`)
  assert.ok(source.possibleTypes.includes('weather'))
  assert.ok(action.possibleTypes.includes('metric-card'))
})

test('当前暴露的图表属性改变实际运行态选项或格式器结果', async t => {
  const results = await probeChartProperties(audit, runtime)
  assert.ok(results.length >= 650, `图表属性探针只生成 ${results.length} 项`)
  for (const component of new Set(results.map(result => result.component))) {
    await t.test(component, () => {
      for (const result of results.filter(item => item.component === component)) assert.equal(result.status, 'changed', `${result.effectiveType} / ${result.path}: ${result.error || '变更未改变输出'}`)
    })
  }
  t.diagnostic(`${results.length} 个属性变更用例；函数输出证据，不等同于浏览器逐项验收。`)
})

test('六类地图的显示属性改变共享真实地图构造结果', async t => {
  const results = await probeMapProperties(audit)
  assert.ok(results.length >= 140)
  for (const result of results) assert.equal(result.status, 'changed', `${result.component} / ${result.path}: ${result.error || '变更未改变输出'}`)
  t.diagnostic(`${results.length} 个地图属性变更用例；地图资源请求由浏览器另行验证。`)
})

test('各组件暴露的容器属性改变生产 widgetStyle 结果', async t => {
  const results = await probeWidgetContainerProperties(audit, runtime)
  assert.ok(results.length >= 950)
  for (const result of results) assert.equal(result.status, 'changed', `${result.component} / ${result.path}: ${result.error || '变更未改变输出'}`)
  t.diagnostic(`${results.length} 个容器属性变更用例；CSS 计算结果变化，仍需真实浏览器检查布局。`)
})

test('标题颜色/字号/内边距和标题图片适配在两端生产函数一致', async () => {
  const [design, run] = await Promise.all([createFunctionHarness(designer), createFunctionHarness(runtime)])
  const widget = chartFixture(run, 'text')
  widget.style = { titleVisible: true, titleColor: '#123456', titleFontSize: 22, titleFontWeight: 700, titlePaddingTop: 0, titlePaddingRight: 12, titlePaddingBottom: 16, titlePaddingLeft: 24, titleImageEnabled: true, titleImageRef: '/dashboard/assets/title.png', titleImageFit: 'contain', titleImageAlign: 'right' }
  for (const fit of ['contain', 'cover', 'fill']) {
    widget.style.titleImageFit = fit
    const actual = run.widgetHeadingStyle(widget)
    const preview = design.widgetHeadingStyle(widget)
    for (const key of ['color', 'fontSize', 'padding', 'backgroundImage', 'backgroundSize', 'backgroundPosition', 'backgroundRepeat']) assert.equal(actual[key], preview[key], `${fit}/${key}`)
    assert.equal(actual.padding, '0px 12px 16px 24px')
    assert.equal(actual.fontSize, '22px')
    assert.equal(actual.color, '#123456')
  }
  widget.style.titleVisible = false
  assert.equal(run.widgetHeadingStyle(widget).backgroundImage, undefined)
  assert.equal(design.widgetHeadingStyle(widget).backgroundImage, undefined)
})

test('指标值映射、数量级和0至6位小数在设计预览与运行函数一致', async () => {
  const [design, run] = await Promise.all([createFunctionHarness(designer), createFunctionHarness(runtime)])
  const widget = chartFixture(run, 'metric-card')
  widget.binding.sourceType = 'STATIC'
  widget.binding.fieldMap.value = 'amount'
  for (const amount of [0, -12345.6789123, 12345.6789123, '暂无数据']) {
    for (const valueDecimalPlaces of [0, 1, 4, 6]) {
      for (const valueScale of ['none', 'thousand', 'ten-thousand', 'million']) {
        widget.binding.staticRows = [{ label: '测试', amount }]
        widget.style.valueDecimalPlaces = valueDecimalPlaces
        widget.style.chartConfig.valueScale = valueScale
        run.widgetStates[widget.id] = { rows: widget.binding.staticRows }
        assert.equal(run.metricValue(widget), design.previewMetric(widget), `${amount}/${valueScale}/${valueDecimalPlaces}`)
      }
    }
  }
  widget.binding.staticRows = [{ amount: 1234.567891 }]
  run.widgetStates[widget.id] = { rows: widget.binding.staticRows }
  widget.style.valueDecimalPlaces = 6
  widget.style.chartConfig.valueScale = 'none'
  assert.equal(run.metricValue(widget), '1234.567891')
  assert.equal(design.previewMetric(widget), '1234.567891')
  widget.style.valueDecimalPlaces = undefined
  widget.style.chartConfig.valuePrecision = 0
  assert.equal(run.metricValue(widget), '1234.567891', '自动小数位不能读取已隐藏的图表精度')
  assert.equal(design.previewMetric(widget), '1234.567891')
})

test('选项卡与表单 JSON 编辑器转换成运行态消费的配置，错误输入保留原内容', async () => {
  const design = await createFunctionHarness(designer)
  // History bookkeeping and warning presentation are boundaries; conversion bodies are production functions.
  design.mutate = callback => callback()
  const warnings = []
  design.ElMessage = { warning: message => warnings.push(message) }
  design.schema.widgets = [{ id: 'existing-widget' }]
  const widget = { id: 'editor', style: {} }
  design.selectedWidget.value = widget
  design.applyTabsJson(JSON.stringify([{ label: '概览', content: '当前概览', widgetIds: ['existing-widget', 'missing-widget'] }]))
  assert.equal(widget.style.tabs[0].content, '当前概览')
  assert.deepEqual(Array.from(widget.style.tabs[0].widgetIds), ['existing-widget'])
  assert.equal(JSON.parse(widget.style.tabsJson)[0].label, '概览')
  const previousTabs = JSON.stringify(widget.style.tabs)
  design.applyTabsJson('{broken')
  assert.equal(JSON.stringify(widget.style.tabs), previousTabs)
  design.applyFormFieldsJson(JSON.stringify([{ name: 'status', label: '状态', parameter: 'status', type: 'radio', defaultValue: 0, options: [{ label: '全部', value: 0 }, { label: '进行中', value: 1 }] }]))
  assert.equal(widget.style.formFields[0].type, 'RADIO')
  assert.equal(widget.style.formFields[0].defaultValue, 0)
  assert.equal(widget.style.formFields[0].options[0].value, 0)
  const previousFields = JSON.stringify(widget.style.formFields)
  design.applyFormFieldsJson('{}')
  assert.equal(JSON.stringify(widget.style.formFields), previousFields)
  assert.equal(warnings.length, 2)
})
