import test from 'node:test'
import assert from 'node:assert/strict'
import { dashboardButtonLabel, dashboardButtonStyle, dashboardButtonUsesImage, dashboardWidgetTitleVisible } from '../src/utils/dashboardPresentation.js'
import { dashboardPropertyVisible } from '../src/utils/dashboardComponentCapabilities.js'

const image = { titleVisible: false, titleImageEnabled: true, titleImageRef: '/profile/button.png', buttonShape: 'angled', borderRadius: 8, contentPaddingTop: 20 }

test('图片按钮独立于旧标题显隐，切图取代纯色背景、斜角和默认圆角', () => {
  assert.equal(dashboardButtonUsesImage(image), true)
  assert.equal(dashboardWidgetTitleVisible({ type: 'button', style: { titleVisible: true } }), false)
  const rendered = dashboardButtonStyle(image, '/dev-api/profile/button.png')
  assert.equal(rendered.width, '100%')
  assert.equal(rendered.height, '100%')
  assert.equal(rendered.backgroundImage, 'url("/dev-api/profile/button.png")')
  assert.equal(rendered.background, 'transparent')
  assert.equal(rendered.border, '0 solid transparent')
  assert.equal(rendered.borderRadius, '0px')
  assert.equal(rendered.clipPath, undefined)
  assert.equal(rendered.boxShadow, undefined)
  assert.equal(dashboardButtonStyle({ ...image, titleImageEnabled: false }).backgroundImage, undefined)
})

test('切图支持拉伸、完整显示和裁切，标题栏高度不参与按钮尺寸', () => {
  for (const [fit, expected] of [['stretch', '100% 100%'], ['contain', 'contain'], ['cover', 'cover']]) {
    const style = { ...image, titleImageFit: fit, titleImageAlign: 'right', titleImageHeight: 20 }
    const result = dashboardButtonStyle(style, '/profile/button.png')
    assert.equal(result.backgroundSize, expected)
    assert.equal(result.backgroundPosition, fit === 'contain' ? 'right center' : 'center')
    assert.deepEqual(result, dashboardButtonStyle({ ...style, titleImageHeight: 120 }, '/profile/button.png'))
  }
})

test('属性面板只暴露实际作用于按钮的控件，旧标题文字仍能作为回退', () => {
  const widget = { type: 'button', style: image }
  for (const property of ['title', 'titleVisible', 'titleFontSize', 'subtitle', 'titleImageHeight', 'buttonShape', 'buttonFill', 'backgroundColor', 'backgroundTransparent', 'borderColor', 'borderWidth', 'borderRadius', 'contentPaddingTop']) {
    assert.equal(dashboardPropertyVisible(widget, `style.${property}`), false, property)
  }
  assert.equal(dashboardPropertyVisible(widget, 'style.titleImageFit'), true)
  assert.equal(dashboardPropertyVisible(widget, 'style.fontSize'), true)
  assert.equal(dashboardButtonLabel({ title: '旧版按钮' }), '旧版按钮')
  assert.equal(dashboardButtonLabel({ ...image, title: '旧版按钮', text: '' }), '')
  assert.equal(dashboardButtonLabel({ ...image, text: '进入项目' }), '进入项目')
  assert.equal(dashboardWidgetTitleVisible({ type: 'text', style: { title: '标题', titleVisible: true } }), true)
  assert.equal(dashboardWidgetTitleVisible({ type: 'text', style: { title: '   ', titleVisible: true } }), false)
  assert.equal(dashboardPropertyVisible({ type: 'text', style: { ...image, titleVisible: true } }, 'style.titleImageHeight'), true)
})
