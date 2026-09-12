import test from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { renderToString } from 'vue/server-renderer'
import {
  componentCatalog,
  createFunctionHarness,
  designerPath,
  readDashboardSfc,
  runtimePath,
} from './helpers/dashboard-property-audit.mjs'
import {
  dashboardStatisticsBodyTitleVisible,
  dashboardTitleText,
  dashboardWidgetTitleVisible,
  dashboardHeadingHeight,
} from '../src/utils/dashboardPresentation.js'
import { dashboardPropertyVisible } from '../src/utils/dashboardComponentCapabilities.js'

const require = createRequire(import.meta.url)
const vueRequire = createRequire(require.resolve('vue'))
const Vue = require('vue')
const { baseParse, compile } = vueRequire('@vue/compiler-dom')

const [designer, runtime] = await Promise.all([
  readDashboardSfc(designerPath),
  readDashboardSfc(runtimePath),
])
const [designHarness, runtimeHarness] = await Promise.all([
  createFunctionHarness(designer),
  createFunctionHarness(runtime),
])
const catalog = componentCatalog(designer)

function findElement(node, className) {
  if (
    node.type === 1 &&
    node.props?.some(
      (prop) =>
        prop.type === 6 &&
        prop.name === 'class' &&
        prop.value?.content.split(/\s+/).includes(className),
    )
  ) {
    return node
  }
  for (const child of node.children || []) {
    const found = findElement(child, className)
    if (found) return found
  }
  return undefined
}

function compileFragment(sfc, className) {
  const ast = baseParse(sfc.descriptor.template.content)
  const node = findElement(ast, className)
  assert.ok(node, `${className} must remain in the production template`)
  const { code } = compile(node.loc.source, {
    mode: 'function',
    prefixIdentifiers: true,
  })
  return new Function('Vue', code)(Vue)
}

const renderRuntimeHeading = compileFragment(runtime, 'widget-heading')
const renderDesignerHeading = compileFragment(designer, 'widget-preview-heading')
const renderRuntimeStatisticsLabel = compileFragment(runtime, 'statistics-label')
const renderDesignerStatisticsLabel = compileFragment(designer, 'preview-statistics-label')

test('运行态和设计器标题模板通过统一可见性与有效标题函数，不在模板层回退组件类型名', () => {
  const runtimeHeading = findElement(baseParse(runtime.descriptor.template.content), 'widget-heading').loc.source
  const designerHeading = findElement(baseParse(designer.descriptor.template.content), 'widget-preview-heading').loc.source
  assert.match(runtimeHeading, /isWidgetTitleVisible\(widget\)/)
  assert.match(designerHeading, /isWidgetTitleVisible\(widget\)/)
  assert.match(runtimeHeading, /statisticsHeadingTitle\(widget\)/)
  assert.match(designerHeading, /previewWidgetTitle\(widget\)/)
  assert.doesNotMatch(runtimeHeading, /typeLabels\[widget\.type\]/)
  assert.doesNotMatch(designerHeading, /meta\(widget\.type\)\.label/)
  assert.match(
    designer.source,
    /v-model="selectedWidget\.style\.title"[\s\S]{0,240}@keydown\.enter\.stop\.prevent="endHistory"/,
    '手工标题输入框必须拦截回车，避免触发表单整页提交',
  )

  const runtimeStatsLabel = findElement(baseParse(runtime.descriptor.template.content), 'statistics-label').loc.source
  const designerStatsLabel = findElement(baseParse(designer.descriptor.template.content), 'preview-statistics-label').loc.source
  assert.match(runtimeStatsLabel, /dashboardStatisticsBodyTitleVisible\(widget/)
  assert.match(designerStatsLabel, /dashboardStatisticsBodyTitleVisible\(widget/)
})

function makeWidget(type, style = {}, binding = {}) {
  return {
    id: `empty-title-${type}`,
    type,
    style: {
      title: '',
      titleVisible: true,
      subtitle: '',
      titleImageEnabled: false,
      titleImageRef: '',
      ...style,
    },
    binding: {
      sourceType: 'STATIC',
      staticRows: [],
      fieldMap: {},
      rowLimit: 50,
      ...binding,
    },
  }
}

async function renderHeading(render, harness, widget, state = { rows: [] }) {
  harness.widget = widget
  harness.widgetStates[widget.id] = state
  return renderToString(
    Vue.createSSRApp({ render: () => render(harness, []) }),
  )
}

test('标题空值语义统一：null、undefined、空字符串和纯空白都不产生可见标题文本', () => {
  for (const value of [null, undefined, '', '   ', '\n\t']) {
    assert.equal(dashboardTitleText(value), '', `空标题值 ${String(value)}`)
  }
  assert.equal(dashboardTitleText(0), '0', '数值 0 是有效标题文本')
  assert.equal(dashboardTitleText(false), 'false', '布尔值按文本显示，避免误判为空')
})

test('显式空标题经过设计器保存模型和运行态规范化后仍保持隐藏', () => {
  for (const title of [null, '', '   ']) {
    const schema = { canvas: { width: 1920, height: 1080 }, widgets: [makeWidget('bar-chart', { title })] }
    const designerWidget = designHarness.normalizeSchema(schema).widgets[0]
    const runtimeWidget = runtimeHarness.normalizeSchema(schema).widgets[0]
    assert.equal(designHarness.isWidgetTitleVisible(designerWidget), false, `designer: ${String(title)}`)
    assert.equal(runtimeHarness.isWidgetTitleVisible(runtimeWidget), false, `runtime: ${String(title)}`)
  }
})

test('设计器与运行态 53 类组件的空标题均不渲染 heading', async (t) => {
  assert.equal(catalog.length, 53)
  const emptyValues = [null, undefined, '', '   ']
  for (const { type } of catalog) {
    await t.test(type, async () => {
      for (const title of emptyValues) {
        const widget = makeWidget(type, { title })
        const designTitle = designHarness.previewWidgetTitle(widget)
        designHarness.widget = widget
        runtimeHarness.widget = widget
        runtimeHarness.widgetStates[widget.id] = { rows: [] }
        const runtimeTitle = runtimeHarness.statisticsHeadingTitle(widget)

        assert.equal(designTitle, '', `${type}: designer effective title`)
        assert.equal(runtimeTitle, '', `${type}: runtime effective title`)
        assert.equal(
          designHarness.isWidgetTitleVisible(widget),
          false,
          `${type}: designer visibility for ${String(title)}`,
        )
        assert.equal(
          runtimeHarness.isWidgetTitleVisible(widget),
          false,
          `${type}: runtime visibility for ${String(title)}`,
        )
        assert.equal(
          dashboardWidgetTitleVisible(widget),
          false,
          `${type}: shared visibility for ${String(title)}`,
        )

        const [runtimeHtml, designerHtml] = await Promise.all([
          renderHeading(renderRuntimeHeading, runtimeHarness, widget),
          renderHeading(renderDesignerHeading, designHarness, widget),
        ])
        assert.match(runtimeHtml, /^<!--v-if-->$/, `${type}: runtime heading`)
        assert.match(designerHtml, /^<!--v-if-->$/, `${type}: designer heading`)
      }
    })
  }
})

test('非统计组件有标题时仍显示，关闭标题显隐优先级最高', async () => {
  for (const { type } of catalog.filter(({ type }) => type !== 'button' && type !== 'statistics')) {
    const widget = makeWidget(type, { title: '有效标题' })
    assert.equal(dashboardWidgetTitleVisible(widget), true, type)
    assert.equal(designHarness.isWidgetTitleVisible(widget), true, `${type}: designer`)
    assert.equal(runtimeHarness.isWidgetTitleVisible(widget), true, `${type}: runtime`)
    const [runtimeHtml, designerHtml] = await Promise.all([
      renderHeading(renderRuntimeHeading, runtimeHarness, widget),
      renderHeading(renderDesignerHeading, designHarness, widget),
    ])
    assert.match(runtimeHtml, /有效标题/, `${type}: runtime title text`)
    assert.match(designerHtml, /有效标题/, `${type}: designer title text`)

    widget.style.titleVisible = false
    assert.equal(designHarness.isWidgetTitleVisible(widget), false, `${type}: designer hidden`)
    assert.equal(runtimeHarness.isWidgetTitleVisible(widget), false, `${type}: runtime hidden`)
  }
})

test('统计概览的映射标题与手工标题均遵循空值规则，设计器和运行态一致', async () => {
  const mapped = makeWidget(
    'statistics',
    { title: '' },
    { fieldMap: { label: 'label' }, staticRows: [{ label: '动态标题', value: 7 }] },
  )
  mapped.id = 'empty-title-statistics-mapped'
  const fallback = makeWidget(
    'statistics',
    { title: '手工标题' },
    { fieldMap: { label: 'label' }, staticRows: [{ label: '  ', value: 7 }] },
  )
  fallback.id = 'empty-title-statistics-fallback'
  const empty = makeWidget(
    'statistics',
    { title: '   ' },
    { fieldMap: { label: 'label' }, staticRows: [{ label: null, value: 7 }] },
  )
  empty.id = 'empty-title-statistics-empty'

  const expectedTitles = new Map([
    [mapped.id, '动态标题'],
    [fallback.id, '手工标题'],
    [empty.id, ''],
  ])
  for (const widget of [mapped, fallback, empty]) {
    const row = widget.binding.staticRows[0]
    designHarness.widget = widget
    runtimeHarness.widget = widget
    runtimeHarness.widgetStates[widget.id] = { rows: [row] }
    const designTitle = designHarness.previewWidgetTitle(widget)
    const runtimeTitle = runtimeHarness.statisticsHeadingTitle(widget)
    assert.equal(designTitle, expectedTitles.get(widget.id), `${widget.id}: expected effective title`)
    assert.equal(designTitle, runtimeTitle, `${widget.id}: effective title parity`)
    assert.equal(
      designHarness.isWidgetTitleVisible(widget),
      false,
      `${widget.id}: statistics has no outer heading without title image`,
    )
    assert.equal(
      runtimeHarness.isWidgetTitleVisible(widget),
      false,
      `${widget.id}: runtime has no outer heading without title image`,
    )
    assert.equal(
      dashboardStatisticsBodyTitleVisible(widget, designTitle),
      Boolean(designTitle),
      `${widget.id}: body title visibility`,
    )
    const [runtimeHtml, designerHtml] = await Promise.all([
      renderHeading(renderRuntimeHeading, runtimeHarness, widget),
      renderHeading(renderDesignerHeading, designHarness, widget),
    ])
    assert.match(runtimeHtml, /^<!--v-if-->$/, `${widget.id}: runtime outer heading`)
    assert.match(designerHtml, /^<!--v-if-->$/, `${widget.id}: designer outer heading`)

    const [runtimeLabelHtml, designerLabelHtml] = await Promise.all([
      renderHeading(renderRuntimeStatisticsLabel, runtimeHarness, widget, { rows: [row] }),
      renderHeading(renderDesignerStatisticsLabel, designHarness, widget),
    ])
    if (expectedTitles.get(widget.id)) {
      assert.match(runtimeLabelHtml, new RegExp(expectedTitles.get(widget.id)), `${widget.id}: runtime body title`)
      assert.match(designerLabelHtml, new RegExp(expectedTitles.get(widget.id)), `${widget.id}: designer body title`)
    } else {
      assert.match(runtimeLabelHtml, /^<!--v-if-->$/, `${widget.id}: runtime empty body title`)
      assert.match(designerLabelHtml, /^<!--v-if-->$/, `${widget.id}: designer empty body title`)
    }
  }
})

test('统计映射标题为空时保留手工数值 0', () => {
  const widget = makeWidget(
    'statistics',
    { title: 0 },
    { fieldMap: { label: 'label' }, staticRows: [{ label: '   ', value: 7 }] },
  )
  runtimeHarness.widget = widget
  runtimeHarness.widgetStates[widget.id] = { rows: [widget.binding.staticRows[0]] }
  assert.equal(designHarness.previewWidgetTitle(widget), '0')
  assert.equal(runtimeHarness.statisticsHeadingTitle(widget), '0')
  assert.equal(dashboardStatisticsBodyTitleVisible(widget, '0'), true)
})

test('标题切图作为独立标题资源保留；按钮空标题和空文字不回退为默认可见文案', async () => {
  const imageWidget = makeWidget('text', {
    title: '',
    titleImageEnabled: true,
    titleImageRef: '/dashboard/assets/title.png',
  })
  assert.equal(dashboardWidgetTitleVisible(imageWidget), true)
  assert.equal(designHarness.isWidgetTitleVisible(imageWidget), true)
  assert.equal(runtimeHarness.isWidgetTitleVisible(imageWidget), true)
  const [runtimeHtml, designerHtml] = await Promise.all([
    renderHeading(renderRuntimeHeading, runtimeHarness, imageWidget),
    renderHeading(renderDesignerHeading, designHarness, imageWidget),
  ])
  assert.match(runtimeHtml, /widget-heading/)
  assert.match(designerHtml, /widget-preview-heading/)

  const button = makeWidget('button', { title: '', text: '' })
  assert.equal(dashboardWidgetTitleVisible(button), false)
  assert.equal(runtimeHarness.dashboardButtonLabel(button.style), '')
  assert.equal(designHarness.dashboardButtonLabel(button.style), '')
})

test('纯空白副标题和标题图片引用不产生空子标题或空图片标题栏', async () => {
  const widget = makeWidget('text', {
    title: '主标题',
    subtitle: '  \n\t ',
    titleImageEnabled: true,
    titleImageRef: '   ',
  })
  assert.equal(designHarness.hasTitleImage(widget), false, '设计器不应把空白资源当标题切图')
  assert.equal(runtimeHarness.hasTitleImage(widget), false, '运行态不应把空白资源当标题切图')
  assert.equal(dashboardPropertyVisible(widget, 'style.titleImageFit'), false, '空白资源不应暴露切图适配')
  assert.equal(dashboardPropertyVisible(widget, 'style.subtitleFontSize'), false, '空白副标题不应暴露副标题样式')
  assert.equal(dashboardHeadingHeight({ titleFontSize: 12, subtitle: '   ' }), 25, '空白副标题不增加标题高度')
  const [runtimeHtml, designerHtml] = await Promise.all([
    renderHeading(renderRuntimeHeading, runtimeHarness, widget),
    renderHeading(renderDesignerHeading, designHarness, widget),
  ])
  assert.match(runtimeHtml, /主标题/)
  assert.match(designerHtml, /主标题/)
  assert.doesNotMatch(runtimeHtml, /<small/)
  assert.doesNotMatch(designerHtml, /<small/)
})
