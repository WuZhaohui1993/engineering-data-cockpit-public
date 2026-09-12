import test from 'node:test'
import assert from 'node:assert/strict'
import { legacyFrameTitleRequest, migrateLegacyDashboardFrames } from '../../scripts/lib/dashboard-frame-migration.mjs'
import { legacyFrameAssetCode, legacyFrameAssetSpec, renderLegacyFrameSvg } from '../../scripts/lib/dashboard-frame-assets.mjs'

const assets = { header: '/dashboard/assets/title-header.png', panel: '/profile/title-panel.png' }
const frame = (id, frameVariant, frameTitleHeight, h) => ({
  id, type: 'border', name: `标题${id}`, groupId: 'user-group',
  layout: { x: 10, y: 20, w: 500, h, z: 1 },
  binding: { datasetCode: 'user-json', fieldMap: { value: 'amount' }, parameters: { site: 'A' } },
  interaction: { onClick: 'page', targetPageCode: 'other' },
  style: { frameVariant, frameTitleHeight, titleVisible: false, title: '旧隐藏标题', subtitle: '旧隐藏副标题', backgroundColor: '#03172d', borderWidth: 1, borderRadius: 4, borderColor: '#145787' },
  state: { visible: true, locked: true },
})

test('标题栏迁移保留组件身份、业务绑定、交互、图层和页面设置，不增加组件', () => {
  const schema = { canvas: { w: 1920, h: 1080 }, filters: [{ id: 'filter', targetWidgetIds: ['p'] }], widgets: [frame('h', 'tech-header', 0, 106), frame('p', 'tech-panel', 38, 560), frame('card', 'tech-panel', 0, 80), { id: 'title', type: 'text', style: { text: '独立标题' } }] }
  const before = structuredClone(schema)
  const migrated = migrateLegacyDashboardFrames(schema, assets)
  assert.deepEqual(schema, before)
  assert.equal(migrated.widgets.length, before.widgets.length)
  for (const [index, widget] of migrated.widgets.entries()) {
    const { style, ...rest } = widget
    const { style: oldStyle, ...originalRest } = before.widgets[index]
    assert.deepEqual(rest, originalRest)
    assert.ok(Object.keys(style).every(key => !key.startsWith('frame')))
  }
  assert.equal(migrated.widgets[0].style.titleImageRef, assets.header)
  assert.equal(migrated.widgets[0].style.titleImageHeight, 106)
  assert.equal(migrated.widgets[0].style.backgroundTransparent, true)
  assert.equal(migrated.widgets[0].style.borderWidth, 0)
  assert.equal(migrated.widgets[1].style.titleImageHeight, 38)
  assert.equal(migrated.widgets[1].style.backgroundColor, '#03172d')
  assert.equal(migrated.widgets[1].style.borderWidth, 1)
  assert.equal(migrated.widgets[1].style.titleVisible, true)
  assert.equal(migrated.widgets[1].style.title, '')
  assert.equal(migrated.widgets[1].style.subtitle, '')
  assert.equal(migrated.widgets[2].style.titleImageEnabled, undefined)
  assert.equal(migrated.widgets[2].style.titleVisible, false)
  assert.deepEqual(migrated.canvas, before.canvas)
  assert.deepEqual(migrated.filters, before.filters)
  assert.equal(migrateLegacyDashboardFrames(migrated, assets), migrated)
})

test('保留用户已选标题栏图片的所有配置；保留原本可见的标题文字', () => {
  const customized = frame('custom', 'tech-panel', 38, 200)
  Object.assign(customized.style, { titleImageEnabled: true, titleImageRef: '/profile/user.png', titleImageHeight: 61, titleImageFit: 'contain', titleVisible: false })
  const visible = frame('visible', 'tech-panel', 38, 200)
  visible.style.titleVisible = true
  const migrated = migrateLegacyDashboardFrames({ widgets: [customized, visible] }, assets)
  assert.deepEqual(migrated.widgets[0].style, Object.fromEntries(Object.entries(customized.style).filter(([key]) => !key.startsWith('frame'))))
  assert.equal(migrated.widgets[1].style.title, visible.style.title)
  assert.equal(migrated.widgets[1].style.subtitle, visible.style.subtitle)
})

test('图片资源和高度缺失或非法时中止，resolver可按组件尺寸选择切图', () => {
  const schema = { widgets: [frame('panel', 'tech-panel', 38, 200)] }
  assert.throws(() => migrateLegacyDashboardFrames(schema, {}), /尚未登记/)
  assert.throws(() => migrateLegacyDashboardFrames(schema, { panel: 'data:image/png;base64,abc' }), /尚未登记/)
  assert.throws(() => migrateLegacyDashboardFrames(schema, () => ({ titleImageRef: assets.panel, titleImageHeight: 201 })), /20-120/)
  const migrated = migrateLegacyDashboardFrames(schema, (widget, request) => {
    assert.equal(widget.id, 'panel')
    assert.deepEqual(request, { kind: 'panel', titleImageHeight: 38 })
    return { titleImageRef: `/profile/panel-${widget.layout.w}.png`, titleImageHeight: 40 }
  })
  assert.equal(migrated.widgets[0].style.titleImageRef, '/profile/panel-500.png')
  assert.equal(migrated.widgets[0].style.titleImageHeight, 40)
  assert.equal(legacyFrameTitleRequest(frame('card', 'tech-panel', 0, 60)), null)
  assert.equal(legacyFrameTitleRequest(frame('header', 'tech-header', 0, 106)).titleImageHeight, 106)
})

test('导出资源使用旧几何形状，切图保持原坐标与稳定内容编号', () => {
  const widget = frame('panel', 'tech-panel', 38, 560)
  const spec = legacyFrameAssetSpec(widget)
  const slice = { height: 38 }
  const svg = renderLegacyFrameSvg(spec, slice)
  assert.match(svg, /height="38" viewBox="0 0 500 38"/)
  assert.match(svg, /M8 1 H492 L499 8 V552/)
  assert.match(svg, /M8 38 H492/)
  assert.equal(legacyFrameAssetCode(spec, slice), legacyFrameAssetCode(legacyFrameAssetSpec(structuredClone(widget)), slice))
  assert.notEqual(legacyFrameAssetCode(spec, slice), legacyFrameAssetCode({ ...spec, width: 501 }, slice))
  assert.throws(() => renderLegacyFrameSvg(spec, { top: 550, height: 38 }), /超出/)
  assert.equal(legacyFrameAssetSpec({ type: 'text' }), null)
})
