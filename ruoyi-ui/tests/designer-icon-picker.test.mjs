import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import { randomUUID } from 'node:crypto'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
import { effectScope, nextTick, reactive } from 'vue'
import { dashboardIconOptions } from '../src/utils/dashboardIcons.js'
import { dashboardLibraryImages } from '../src/utils/dashboardIconLibrary.js'
import { createFunctionHarness, designerPath, readDashboardSfc } from './helpers/dashboard-property-audit.mjs'

const require = createRequire(import.meta.url)
const vueRequire = createRequire(require.resolve('vue'))
const compilerRequire = createRequire(vueRequire.resolve('@vue/compiler-sfc'))
const { compileScript, compileTemplate, compileStyle } = compilerRequire('@vue/compiler-sfc')
const { baseParse } = compilerRequire('@vue/compiler-dom')
const pickerPath = new URL('../src/views/dashboard/designer/DesignerIconPicker.vue', import.meta.url)
const [picker, designer] = await Promise.all([readDashboardSfc(fileURLToPath(pickerPath)), readDashboardSfc(designerPath)])
const compiled = compileScript(picker.descriptor, { id: 'designer-icon-picker-test' })
// Execute the real setup and watchers; only Vite's public environment value is supplied for Node.
const resourceSource = await fs.readFile(new URL('../src/utils/dashboard.js', import.meta.url), 'utf8')
const resourceModule = `data:text/javascript;base64,${Buffer.from(resourceSource.replaceAll('import.meta.env', '({ VITE_APP_BASE_API: "/dev-api" })')).toString('base64')}`
const moduleCode = compiled.content.replace(/from (['"])@\/([^'"]+)\1/g, (_, quote, path) => {
  const url = path === 'utils/dashboard' ? resourceModule : new URL(`../src/${path}.js`, import.meta.url).href
  return `from ${quote}${url}${quote}`
})
const temporary = new URL(`.designer-icon-picker-${process.pid}-${randomUUID()}.mjs`, import.meta.url)
let Picker
await fs.writeFile(temporary, moduleCode)
try { Picker = (await import(temporary.href)).default } finally { await fs.unlink(temporary) }

const templateNodes = []
function visit(node) {
  if (node.type === 1) templateNodes.push(node)
  for (const child of node.children || []) visit(child)
}
visit(baseParse(picker.descriptor.template.content))
function templateHandler(node, eventName) {
  const event = node.props.find(prop => prop.type === 7 && prop.name === 'on' && prop.arg?.content === eventName)
  assert.ok(event?.exp?.content, `模板包含 ${eventName} 处理器`)
  return new Function('emit', '$event', event.exp.content)
}
const cancelHandler = templateHandler(templateNodes.find(node => node.tag === 'el-button' && node.children.some(child => child.type === 2 && child.content.trim() === '取消')), 'click')
const dialogCloseHandler = templateHandler(templateNodes.find(node => node.tag === 'el-dialog'), 'update:model-value')
const uploadedPng = { assetType: 'IMAGE', assetName: '巡检设备静态图标', assetCode: 'INSPECT_01', folderName: '安全检查', resourcePath: '/profile/icons/inspection.png', mimeType: 'image/png' }
const uploadedGif = { assetType: 'IMAGE', assetName: '告警动态图标', assetCode: 'ALARM_02', folderName: '运行监测', resourcePath: '/profile/icons/alarm.GIF', mimeType: 'image/gif' }

function setupPicker(t, initial = {}) {
  const props = reactive({ modelValue: true, selectedIconName: 'star', selectedImageRef: '', assets: [], loading: false, ...initial })
  const events = []
  const emit = (...event) => {
    events.push(event)
    if (event[0] === 'update:modelValue') props.modelValue = event[1]
  }
  const scope = effectScope()
  const state = scope.run(() => Picker.setup(props, { expose() {}, emit }))
  t.after(() => scope.stop())
  return { props, state, events, cancel: () => cancelHandler(emit), closeDialog: () => dialogCloseHandler(emit, false) }
}

async function setupDesigner(style = {}, assets = [uploadedPng, uploadedGif]) {
  const widget = { id: 'icon-under-edit', type: 'icon', style: { iconName: 'star', imageRef: '', fontSize: 48, color: '#123456', ...style } }
  const other = { id: 'other-icon', type: 'icon', style: { iconName: 'helmet', imageRef: '' } }
  const schema = { canvas: {}, widgets: [widget, other] }
  const warnings = []
  const h = await createFunctionHarness(designer, {
    schema, selectedWidget: { value: other },
    iconPickerTargetId: { value: widget.id }, imageAssets: { value: assets },
    dashboardIconOptions, historyPending: { value: null }, history: { value: [] }, future: { value: ['old-redo'] },
    draftDirty: { value: false }, lastSchemaSnapshot: JSON.stringify(schema),
    ElMessage: { warning: text => warnings.push(text) },
  })
  return { h, widget, other, schema, warnings }
}

test('图标库合并平台、内置图片和上传图片，去重并过滤非图片及不可信地址', t => {
  const invalid = ['https://example.com/icon.gif', '//example.com/icon.png', 'data:image/png;base64,AAAA', '/profile/../secret.png', '/profile/icon.mp4']
  const { state, events } = setupPicker(t, { assets: [uploadedPng, uploadedGif, { ...uploadedPng }, { assetType: 'VIDEO', resourcePath: '/profile/video.png' }, ...invalid.map(resourcePath => ({ assetType: 'IMAGE', resourcePath }))] })
  assert.equal(state.items.value.length, dashboardIconOptions.length + dashboardLibraryImages.length + 2)
  assert.equal(state.items.value.filter(item => item.source === 'resource').length, 2)
  assert.equal(state.items.value.find(item => item.imageRef === uploadedPng.resourcePath).url, `/dev-api${uploadedPng.resourcePath}`)
  const builtin = dashboardLibraryImages[0]
  assert.equal(state.items.value.find(item => item.imageRef === builtin.resourcePath).url, builtin.resourcePath)
  assert.equal(state.draftItem.value.iconName, 'star')
  assert.deepEqual(events, [])
})

test('搜索支持中文名称、分类、编码及多个关键词，类型和来源筛选可组合', t => {
  const { state } = setupPicker(t, { assets: [uploadedPng, uploadedGif] })
  state.query.value = '安全检查 inspect_01'
  assert.deepEqual(state.filteredItems.value.map(item => item.imageRef), [uploadedPng.resourcePath])
  state.query.value = '告警 动态'
  assert.ok(state.filteredItems.value.some(item => item.imageRef === uploadedGif.resourcePath))
  state.source.value = 'resource'
  state.category.value = 'gif'
  assert.deepEqual(state.filteredItems.value.map(item => item.imageRef), [uploadedGif.resourcePath])
  assert.equal(state.categoryCount('gif'), 1)
  state.source.value = 'builtin'
  assert.ok(state.filteredItems.value.every(item => item.source === 'builtin' && item.kind === 'gif'))
  state.query.value = '没有这样的图标'
  assert.equal(state.filteredItems.value.length, 0)
  assert.equal(state.emptyDescription.value, '没有找到匹配的图标')
  state.clearFilters()
  assert.equal(state.filteredItems.value.length, state.items.value.length)
})

test('GIF 按扩展名或媒体类型识别，静态图标不误入动态分类', t => {
  const mimeGif = { ...uploadedPng, resourcePath: '/profile/icons/animated.png', mimeType: 'image/gif; charset=binary' }
  const { state } = setupPicker(t, { assets: [uploadedPng, { ...uploadedGif, mimeType: '' }, mimeGif] })
  state.source.value = 'resource'
  state.category.value = 'gif'
  assert.deepEqual(state.filteredItems.value.map(item => item.imageRef), [uploadedGif.resourcePath, mimeGif.resourcePath])
  state.category.value = 'static'
  assert.deepEqual(state.filteredItems.value.map(item => item.imageRef), [uploadedPng.resourcePath])
})

test('每页最多 48 项，筛选和资源缩减后复位或收敛页码', async t => {
  const assets = Array.from({ length: 120 }, (_, index) => ({ ...uploadedPng, resourcePath: `/profile/icons/${index}.png`, assetName: `设备图标 ${index}` }))
  const { props, state } = setupPicker(t, { assets })
  assert.equal(state.pageItems.value.length, 48)
  state.page.value = 2
  assert.equal(state.pageItems.value.length, 48)
  assert.notEqual(state.pageItems.value[0].key, state.filteredItems.value[0].key)
  state.source.value = 'resource'
  assert.equal(state.page.value, 1)
  state.page.value = 3
  assert.equal(state.pageItems.value.length, 24)
  props.assets = [assets[0]]
  await nextTick()
  assert.equal(state.page.value, 1)
  assert.equal(state.pageItems.value.length, 1)
})

test('候选暂选不提交；取消按钮和对话框关闭仅关闭弹窗，重新打开恢复原选择', async t => {
  const { props, state, events, cancel, closeDialog } = setupPicker(t, { selectedIconName: 'monitor' })
  state.draftKey.value = 'builtin:helmet'
  assert.deepEqual(events, [])
  cancel()
  assert.deepEqual(events, [['update:modelValue', false]])
  assert.equal(props.selectedIconName, 'monitor')
  await nextTick()
  props.modelValue = true
  await nextTick()
  assert.equal(state.draftItem.value.iconName, 'monitor')
  state.draftKey.value = 'builtin:warning'
  closeDialog()
  assert.deepEqual(events, [['update:modelValue', false], ['update:modelValue', false]])
  assert.ok(!events.some(event => event[0] === 'select'))
})

test('确认平台、PNG 和 GIF 候选时提交约定字段并关闭弹窗', t => {
  for (const choice of [
    { key: 'builtin:helmet', payload: { kind: 'builtin', iconName: 'helmet', label: '防护' } },
    { key: `image:${uploadedPng.resourcePath}`, payload: { kind: 'image', imageRef: uploadedPng.resourcePath, label: uploadedPng.assetName } },
    { key: `image:${uploadedGif.resourcePath}`, payload: { kind: 'image', imageRef: uploadedGif.resourcePath, label: uploadedGif.assetName } },
  ]) {
    const { state, events } = setupPicker(t, { assets: [uploadedPng, uploadedGif] })
    state.draftKey.value = choice.key
    state.confirmSelection()
    assert.deepEqual(events, [['select', choice.payload], ['update:modelValue', false]])
  }
})

test('损坏图片不能提交，刷新清理失败状态且仅请求刷新，未知候选不能提交', t => {
  const { state, events } = setupPicker(t, { assets: [uploadedGif], selectedImageRef: uploadedGif.resourcePath })
  state.markFailed(state.draftKey.value)
  state.confirmSelection()
  assert.deepEqual(events, [])
  state.refreshAssets()
  assert.deepEqual(events, [['refresh']])
  assert.deepEqual(state.failedImages.value, {})
  state.confirmSelection()
  assert.equal(events[1][0], 'select')
  const count = events.length
  state.draftKey.value = 'builtin:missing'
  state.confirmSelection()
  assert.equal(events.length, count)
})

test('重开恢复当前图片所在页，清除筛选；资源未加载时保留已保存图片', async t => {
  const assets = Array.from({ length: 80 }, (_, index) => ({ ...uploadedPng, resourcePath: `/profile/icons/reopen-${index}.png` }))
  const selectedImageRef = assets.at(-1).resourcePath
  const { props, state } = setupPicker(t, { assets, selectedImageRef })
  assert.equal(state.draftItem.value.imageRef, selectedImageRef)
  assert.ok(state.pageItems.value.some(item => item.imageRef === selectedImageRef))
  state.query.value = '不存在'
  state.source.value = 'builtin'
  state.category.value = 'gif'
  props.modelValue = false
  await nextTick()
  props.modelValue = true
  await nextTick()
  assert.equal(state.query.value, '')
  assert.equal(state.source.value, 'all')
  assert.equal(state.category.value, 'all')
  assert.ok(state.pageItems.value.some(item => item.imageRef === selectedImageRef))
  const pending = setupPicker(t, { selectedImageRef: uploadedGif.resourcePath, loading: true })
  assert.equal(pending.state.draftItem.value.imageRef, uploadedGif.resourcePath)
  assert.equal(pending.state.draftItem.value.kind, 'gif')
  const invalid = setupPicker(t, { selectedImageRef: 'https://example.com/forbidden.png', selectedIconName: 'missing' })
  assert.equal(invalid.state.draftItem.value.iconName, 'star')
})

test('打开后聚焦并滚动到已选图标，不移动到任意候选', async t => {
  const { state } = setupPicker(t)
  const calls = []
  state.viewport.value = { querySelector: selector => {
    calls.push(selector)
    return { focus: options => calls.push(options), scrollIntoView: options => calls.push(options) }
  } }
  await state.focusSelectedIcon()
  assert.deepEqual(calls, ['.icon-library__item.is-selected', { preventScroll: true }, { block: 'nearest', inline: 'nearest' }])
})

test('设计器确认平台图标清空图片引用，修改绑定目标且只形成一次撤销', async () => {
  const { h, widget, other } = await setupDesigner({ imageRef: uploadedGif.resourcePath })
  h.applyIconSelection({ kind: 'builtin', iconName: 'monitor' })
  assert.equal(widget.style.iconName, 'monitor')
  assert.equal(widget.style.imageRef, '')
  assert.equal(widget.style.fontSize, 48)
  assert.equal(widget.style.color, '#123456')
  assert.equal(other.style.iconName, 'helmet')
  assert.equal(h.history.value.length, 1)
  assert.equal(JSON.parse(h.history.value[0]).widgets[0].style.imageRef, uploadedGif.resourcePath)
  assert.equal(h.draftDirty.value, true)
  assert.equal(h.future.value.length, 0)
  h.applyIconSelection({ kind: 'builtin', iconName: 'monitor' })
  assert.equal(h.history.value.length, 1, '重复确认相同结果不形成额外历史')
})

test('设计器确认上传或内置 PNG/GIF 保存稳定图片引用，每次只形成一次撤销', async () => {
  for (const imageRef of [uploadedPng.resourcePath, uploadedGif.resourcePath, ...dashboardLibraryImages.slice(0, 2).map(item => item.resourcePath)]) {
    const { h, widget } = await setupDesigner()
    h.applyIconSelection({ kind: 'image', imageRef })
    assert.equal(widget.style.imageRef, imageRef)
    assert.equal(widget.style.iconName, 'star')
    assert.equal(h.history.value.length, 1)
    assert.equal(JSON.parse(h.history.value[0]).widgets[0].style.imageRef, '')
  }
})

test('设计器拒绝未登记图片、即使登记的外链和无效平台图标，不写撤销历史', async () => {
  const external = 'https://example.com/icon.gif'
  const traversal = '/profile/../secret.png'
  const { h, widget, warnings } = await setupDesigner({}, [uploadedPng, { resourcePath: external }, { resourcePath: traversal }])
  for (const imageRef of [external, traversal, '/profile/not-registered.png']) h.applyIconSelection({ kind: 'image', imageRef })
  h.applyIconSelection({ kind: 'builtin', iconName: '__proto__' })
  assert.equal(widget.style.imageRef, '')
  assert.equal(widget.style.iconName, 'star')
  assert.equal(warnings.length, 3)
  assert.equal(h.history.value.length, 0)
  assert.equal(h.draftDirty.value, false)
  h.iconPickerTargetId.value = 'removed-widget'
  h.applyIconSelection({ kind: 'builtin', iconName: 'monitor' })
  assert.equal(h.history.value.length, 0)
})

test('图标选择器真实模板和样式可编译', () => {
  const template = compileTemplate({ source: picker.descriptor.template.content, filename: pickerPath.pathname, id: 'designer-icon-picker-test', compilerOptions: { bindingMetadata: compiled.bindings } })
  const style = compileStyle({ source: picker.descriptor.styles[0].content, filename: pickerPath.pathname, id: 'data-v-designer-icon-picker-test', scoped: true })
  assert.deepEqual(template.errors, [])
  assert.deepEqual(style.errors, [])
})
