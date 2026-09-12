import test from 'node:test'
import assert from 'node:assert/strict'
import vm from 'node:vm'
import { createRequire } from 'node:module'
import { componentCatalog, createFunctionHarness, designerPath, readDashboardSfc, runtimePath } from './helpers/dashboard-property-audit.mjs'
import { dashboardComponentCapabilities } from '../src/utils/dashboardComponentCapabilities.js'

const require = createRequire(import.meta.url)
const vueRequire = createRequire(require.resolve('vue'))
const { baseParse, compile } = vueRequire('@vue/compiler-dom')
const Vue = require('vue')
const { renderToString } = vueRequire('@vue/server-renderer')
const [runtime, designer] = await Promise.all([readDashboardSfc(runtimePath), readDashboardSfc(designerPath)])
const template = baseParse(runtime.descriptor.template.content)
const plain = value => JSON.parse(JSON.stringify(value))

function findElement(node, predicate) {
  if (node.type === 1 && predicate(node)) return node
  for (const child of node.children || []) {
    const found = findElement(child, predicate)
    if (found) return found
  }
}
const withClass = value => node => node.props.some(prop => prop.type === 6 && prop.name === 'class' && prop.value?.content.split(' ').includes(value))
const heading = findElement(template, withClass('widget-heading'))
const widgetNode = findElement(template, withClass('runtime-widget'))
const errorNode = findElement(template, withClass('error-state'))
const buttonNode = findElement(findElement(template, withClass('button-content')), node => node.tag === 'button')
const widgetClass = widgetNode.props.find(prop => prop.type === 7 && prop.name === 'bind' && prop.arg?.content === 'class').exp.content
const errorVisible = errorNode.props.find(prop => prop.type === 7 && prop.name === 'if').exp.content

async function renderFragment(node, context) {
  const { code } = compile(node.loc.source, { mode: 'function', prefixIdentifiers: true })
  const render = new Function('Vue', code)(Vue)
  return renderToString(Vue.createSSRApp({ render: () => render(context, []) }))
}

async function createHarness() {
  const harness = await createFunctionHarness(runtime, { activeWidgetId: { value: '' } })
  // Keep the production decision logic; observe only the data-commit boundary.
  harness.applyWidgetResult = async (widget, result) => {
    harness.widgetStates[widget.id] = result
    return result
  }
  return harness
}

test('所有无数据能力组件跳过静态数据质量计算与历史绑定，当前时间不再产生无数据标识', async () => {
  const h = await createHarness()
  const types = componentCatalog(designer).filter(item => !dashboardComponentCapabilities(item).data).map(item => item.type)
  assert.ok(types.includes('current-time'))
  assert.ok(types.length >= 14)
  for (const type of types) {
    for (const binding of [
      { staticRows: [] },
      { sourceType: 'STATIC', staticRows: [{ value: 3 }] },
      { datasetCode: 'old-binding', staticRows: [] },
    ]) {
      const widget = { id: type, type, style: { titleVisible: false, qualityVisible: true }, binding }
      h.overrideData[type] = [{ value: 9 }]
      const result = await h.requestWidgetData(widget, {}, {})
      assert.deepEqual(plain(result.rows), [], type)
      assert.equal(result.quality, null, type)
      assert.equal(h.hasWidgetQuality(widget), false, type)
      h.widgetStates[type].quality = 'NO_DATA'
      assert.equal(h.hasWidgetQuality(widget), false, `${type} 旧状态不能显示品质标识`)
      h.widget = widget
      assert.equal(vm.runInContext(`(${widgetClass})`, h)['title-hidden'], true, `${type} 不预留品质标题高度`)
      assert.doesNotMatch(await renderFragment(heading, h), /widget-heading|quality-badge/, type)
    }
  }
})

test('数据组件保持真实空数据、正常数据和显隐行为，错误提示不依赖品质徽标开关', async () => {
  const h = await createHarness()
  const widget = { id: 'chart', type: 'bar-chart', style: { titleVisible: false }, binding: { sourceType: 'STATIC', staticRows: [] } }
  h.widget = widget
  let result = await h.requestWidgetData(widget, {}, {})
  assert.equal(result.quality, 'NO_DATA')
  assert.equal(h.hasWidgetQuality(widget), true)
  assert.match(await renderFragment(heading, h), /quality-badge[^>]*>无数据</)
  assert.equal(vm.runInContext(`(${widgetClass})`, h)['title-hidden'], false)
  widget.binding.staticRows = [{ value: 0 }]
  result = await h.requestWidgetData(widget, {}, {})
  assert.equal(result.quality, 'SUCCESS')
  assert.deepEqual(plain(result.rows), [{ value: 0 }])
  for (const quality of ['SUCCESS', 'STALE', 'FORBIDDEN', 'SOURCE_ERROR']) {
    h.widgetStates.chart.quality = quality
    assert.equal(h.hasWidgetQuality(widget), true, quality)
    assert.match(await renderFragment(heading, h), /quality-badge/, quality)
  }
  widget.style.qualityVisible = false
  assert.equal(h.hasWidgetQuality(widget), false)
  assert.equal(vm.runInContext(`(${widgetClass})`, h)['title-hidden'], true)
  assert.equal(vm.runInContext(`Boolean(${errorVisible})`, h), true)
  assert.doesNotMatch(await renderFragment(heading, h), /quality-badge/)
})

test('视频媒体加载失败仍显示错误内容，后续无数据组件刷新不会清除真实媒体错误', async () => {
  const h = await createHarness()
  const widget = { id: 'video', type: 'video', style: { titleVisible: false, videoRef: '/profile/broken.mp4' }, binding: { staticRows: [] } }
  h.widget = widget
  await h.requestWidgetData(widget, {}, {})
  h.markMediaError(widget)
  for (let refresh = 0; refresh < 2; refresh++) {
    assert.equal(h.widgetStates.video.quality, 'INVALID_DATA')
    assert.equal(h.widgetStates.video.message, '媒体资源加载失败')
    assert.equal(h.hasWidgetQuality(widget), false)
    assert.equal(vm.runInContext(`Boolean(${errorVisible})`, h), true)
    await h.requestWidgetData(widget, {}, {})
  }
  widget.style.videoRef = '/profile/replaced.mp4'
  await h.requestWidgetData(widget, {}, {})
  assert.equal(h.widgetStates.video.quality, null, '更换视频资源后允许重新加载，不保留上一资源的错误')
})

test('按钮复用标题切图为本体背景，旧隐藏标题不阻止切图，保留文字回退与纯图片按钮', async () => {
  const h = await createHarness()
  const widget = { id: 'button', type: 'button', layout: { w: 360, h: 72 }, style: {
    title: '原按钮标题', titleVisible: false, titleImageEnabled: true,
    titleImageRef: '/dashboard/assets/button-title.png', titleImageFit: 'contain',
    buttonShape: 'angled', buttonFill: false, borderRadius: 8,
    contentPaddingTop: 12, contentPaddingRight: 14, contentPaddingBottom: 16, contentPaddingLeft: 18,
  } }
  h.widget = widget
  for (const titleVisible of [false, true]) {
    widget.style.titleVisible = titleVisible
    assert.equal(h.hasTitleImage(widget), false)
    assert.equal(vm.runInContext(`(${widgetClass})`, h)['title-hidden'], true)
    assert.doesNotMatch(await renderFragment(heading, h), /widget-heading/)
    const rootStyle = h.widgetStyle(widget)
    assert.equal(rootStyle.background, 'transparent')
    assert.equal(rootStyle.padding, '0px')
    assert.equal(rootStyle.borderRadius, '0px')
    const html = await renderFragment(buttonNode, h)
    assert.match(html, /原按钮标题/)
    assert.match(html, /background-image:url\(&quot;\/dev-api\/dashboard\/assets\/button-title\.png&quot;\)/)
    assert.match(html, /width:100%/)
    assert.match(html, /height:100%/)
    assert.doesNotMatch(html, /polygon\(/)
  }
  widget.style.text = ''
  assert.match(await renderFragment(buttonNode, h), /<button[^>]*><\/button>/)
  widget.style.text = '点击查看项目'
  assert.match(await renderFragment(buttonNode, h), />点击查看项目<\/button>/)
})

test('普通按钮只有一个文字入口，父容器透明且保留原内容内边距', async () => {
  const h = await createHarness()
  const widget = { id: 'plain', type: 'button', style: {
    title: '兼容原按钮', text: '', titleVisible: true, backgroundColor: '#123456',
    buttonFill: false, contentPaddingTop: 12, contentPaddingRight: 14, contentPaddingBottom: 16, contentPaddingLeft: 18,
  } }
  h.widget = widget
  const rootStyle = h.widgetStyle(widget)
  assert.equal(rootStyle.background, 'transparent')
  assert.equal(rootStyle.padding, '12px 14px 16px 18px')
  assert.doesNotMatch(await renderFragment(heading, h), /widget-heading/)
  assert.match(await renderFragment(buttonNode, h), />兼容原按钮<\/button>/)
  widget.style.text = '实际按钮文字'
  assert.match(await renderFragment(buttonNode, h), />实际按钮文字<\/button>/)
})
