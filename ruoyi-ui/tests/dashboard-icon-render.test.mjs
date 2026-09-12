import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import { createRequire } from 'node:module'
import { createRenderer, createSSRApp, h, nextTick, ref } from 'vue'
import { dashboardLibraryImages } from '../src/utils/dashboardIconLibrary.js'

const require = createRequire(import.meta.url)
const vueRequire = createRequire(require.resolve('vue'))
const { parse, compileScript, compileStyle } = vueRequire('@vue/compiler-sfc')
const { renderToString } = vueRequire('@vue/server-renderer')
const source = await fs.readFile(new URL('../src/components/DashboardIcon/index.vue', import.meta.url), 'utf8')
const { descriptor, errors } = parse(source)
assert.deepEqual(errors, [])
const compiled = compileScript(descriptor, { id: 'dashboard-icon-test', inlineTemplate: true })
// Supply Vite's base API value while executing the real resource resolver in Node.
const resourceSource = await fs.readFile(new URL('../src/utils/dashboard.js', import.meta.url), 'utf8')
const resourceModule = `data:text/javascript;base64,${Buffer.from(resourceSource.replaceAll('import.meta.env', '({ VITE_APP_BASE_API: "/dev-api" })')).toString('base64')}`
const moduleCode = compiled.content.replace(/from (['"])@\/([^'"]+)\1/g, (_, quote, path) => {
  const url = path === 'utils/dashboard' ? resourceModule : new URL(`../src/${path}.js`, import.meta.url).href
  return `from ${quote}${url}${quote}`
})
const temporary = new URL(`.icon-render-${process.pid}.mjs`, import.meta.url)
let DashboardIcon
await fs.writeFile(temporary, moduleCode)
try { DashboardIcon = (await import(temporary.href)).default } finally { await fs.unlink(temporary) }

const renderIcon = (options = {}, name = '') => renderToString(createSSRApp(DashboardIcon, { options, name }))

test('真实图标组件使用平台目录，缺失或未知图标兼容星标，圆框只包裹图标', async () => {
  const star = await renderIcon({ iconName: 'star' })
  assert.equal(await renderIcon(), star)
  assert.equal(await renderIcon({ iconName: 'unknown' }), star)
  assert.equal(await renderIcon({ iconName: '__proto__' }), star)
  assert.notEqual(await renderIcon({ iconName: 'monitor' }), star)
  const framed = await renderIcon({ iconName: 'helmet', iconFrame: true, fontSize: 48 }, '安全防护')
  assert.match(framed, /dashboard-icon--framed/)
  assert.match(framed, /aria-label="安全防护"/)
  assert.match(framed, /--dashboard-icon-size:48px/)
  assert.doesNotMatch(framed, /<img/)
})

test('PNG 和 GIF 均渲染原生图片并保留资源路径，不染色也不继承平台圆框', async () => {
  for (const imageRef of ['/profile/icon.png', '/dashboard/assets/animated.gif']) {
    const html = await renderIcon({ imageRef, iconName: 'star', iconFrame: true, color: '#ff0000' })
    assert.match(html, /<img /)
    assert.ok(html.includes(`src="/dev-api${imageRef}"`))
    assert.doesNotMatch(html, /<svg|dashboard-icon--framed|#ff0000/)
    assert.match(html, /draggable="false"/)
  }
})

test('内置静态和 GIF 图片直接读取随前端发布的素材，不误加后端接口前缀', async () => {
  for (const kind of ['static', 'gif']) {
    const image = dashboardLibraryImages.find(item => item.kind === kind)
    assert.ok(image, `内置目录包含 ${kind}`)
    const html = await renderIcon({ imageRef: image.resourcePath })
    assert.ok(html.includes(`src="${image.resourcePath}"`))
    assert.doesNotMatch(html, /\/dev-api\/dashboard|dashboard-icon__placeholder/)
  }
})

test('无效图片引用显示明确的图片占位，不悄悄回退平台星标', async () => {
  const html = await renderIcon({ imageRef: 'javascript:alert(1)', iconName: 'star', iconFrame: true }, '设备')
  assert.match(html, /设备：图片加载失败/)
  assert.match(html, /dashboard-icon__placeholder/)
  assert.doesNotMatch(html, /<img|dashboard-icon__vector|dashboard-icon--framed/)
})

// Mount the compiled SFC so Vue executes its real watches and image events.
function mountIcon(initial) {
  const node = (type, text = '') => ({
    type, text, children: [], parent: null, props: {},
    getAttribute(key) { return this.props[key] },
  })
  const renderer = createRenderer({
    createElement: node, createText: text => node('text', text), createComment: text => node('comment', text),
    setText: (element, text) => { element.text = text },
    setElementText: (element, text) => { element.text = text; element.children = [] },
    insert(element, parent, anchor) {
      if (element.parent) element.parent.children.splice(element.parent.children.indexOf(element), 1)
      element.parent = parent
      const index = parent.children.indexOf(anchor)
      parent.children.splice(index < 0 ? parent.children.length : index, 0, element)
    },
    remove(element) {
      if (element.parent) element.parent.children.splice(element.parent.children.indexOf(element), 1)
      element.parent = null
    },
    parentNode: element => element.parent,
    nextSibling: element => element.parent?.children[element.parent.children.indexOf(element) + 1] || null,
    patchProp: (element, key, previous, value) => { element.props[key] = value },
  })
  const options = ref(initial), root = node('root')
  const app = renderer.createApp({ setup: () => () => h(DashboardIcon, { options: options.value }) })
  app.mount(root)
  const descendants = element => [element, ...element.children.flatMap(descendants)]
  return {
    options,
    find: type => descendants(root).filter(element => element.type === type),
    close: () => app.unmount(),
  }
}

test('已挂载组件在平台、PNG、GIF 间切换，图片不会复用旧的动画节点', async () => {
  const icon = mountIcon({ iconName: 'monitor', iconFrame: true })
  try {
    assert.equal(icon.find('img').length, 0)
    icon.options.value.imageRef = '/profile/static.png'
    await nextTick()
    const png = icon.find('img')[0]
    assert.equal(png.props.src, '/dev-api/profile/static.png')
    icon.options.value.imageRef = '/profile/animated.gif'
    await nextTick()
    const gif = icon.find('img')[0]
    assert.equal(gif.props.src, '/dev-api/profile/animated.gif')
    assert.notEqual(gif, png)
    assert.doesNotMatch(icon.find('span')[0].props.class, /dashboard-icon--framed/)
    icon.options.value.imageRef = ''
    await nextTick()
    assert.equal(icon.find('img').length, 0)
    assert.match(icon.find('span')[0].props.class, /dashboard-icon--framed/)
  } finally { icon.close() }
})

test('加载失败会显式占位，切源恢复并忽略之前图片迟到的错误事件', async () => {
  const icon = mountIcon({ imageRef: '/profile/first.gif' })
  try {
    const first = icon.find('img')[0]
    first.props.onError({ currentTarget: first })
    await nextTick()
    assert.equal(icon.find('img').length, 0)
    assert.match(icon.find('span')[0].props['aria-label'], /图片加载失败/)
    icon.options.value.imageRef = '/profile/second.png'
    await nextTick()
    assert.equal(icon.find('img')[0].props.src, '/dev-api/profile/second.png')
    first.props.onError({ currentTarget: first })
    await nextTick()
    assert.equal(icon.find('img').length, 1)
    icon.options.value.imageRef = '/profile/first.gif'
    await nextTick()
    assert.equal(icon.find('img').length, 1)
    assert.equal(icon.find('img')[0].props.src, '/dev-api/profile/first.gif')
  } finally { icon.close() }
})

test('图标尺寸的非法值有安全回退，样式继承主题主色且图片完整显示', async () => {
  for (const fontSize of [0, -1, Infinity, 'invalid']) {
    assert.match(await renderIcon({ fontSize }), /--dashboard-icon-size:32px/)
  }
  const css = compileStyle({ source: descriptor.styles[0].content, filename: 'DashboardIcon.vue', id: 'dashboard-icon-test', scoped: true })
  assert.deepEqual(css.errors, [])
  assert.match(css.code, /color:\s*var\(--accent,/)
  assert.match(css.code, /font-size:\s*var\(--widget-font-size,/)
  assert.match(css.code, /object-fit:\s*contain/)
  assert.match(css.code, /flex:\s*0 0 auto/)
})
