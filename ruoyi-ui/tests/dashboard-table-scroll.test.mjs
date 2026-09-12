import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import { createRequire } from 'node:module'
import { createRenderer, createSSRApp, h, nextTick, ref } from 'vue'
import { dashboardTableScrollMetrics } from '../src/utils/dashboardTableScroll.js'

const require = createRequire(import.meta.url)
const vueRequire = createRequire(require.resolve('vue'))
const { parse, compileScript } = vueRequire('@vue/compiler-sfc')
const { renderToString } = vueRequire('@vue/server-renderer')
const source = await fs.readFile(new URL('../src/components/DashboardDataTable/index.vue', import.meta.url), 'utf8')
const { descriptor, errors } = parse(source)
assert.deepEqual(errors, [])
const compiled = compileScript(descriptor, { id: 'dashboard-table-scroll-test', inlineTemplate: true })
const moduleCode = compiled.content.replace(/from (['"])@\/([^'"]+)\1/g, (_, quote, path) => `from ${quote}${new URL(`../src/${path}.js`, import.meta.url).href}${quote}`)
const temporary = new URL(`.table-scroll-${process.pid}.mjs`, import.meta.url)
let DataTable
await fs.writeFile(temporary, moduleCode)
try { DataTable = (await import(temporary.href)).default } finally { await fs.unlink(temporary) }

test('滚动以真实内容溢出为准：小于六行也能滚动，刚好容纳时不滚动', () => {
  for (const rowCount of [1, 3, 6, 20]) {
    assert.equal(dashboardTableScrollMetrics({ contentHeight: 100.6, viewportHeight: 100, rowCount }).overflow, true)
    for (const height of [80, 100, 100.5]) {
      assert.equal(dashboardTableScrollMetrics({ contentHeight: height, viewportHeight: 100, rowCount }).overflow, false)
    }
  }
})

test('每圈距离包含全部真实行高，时长按秒每行计算并限制速度范围', () => {
  assert.deepEqual(dashboardTableScrollMetrics({ contentHeight: 210.4, viewportHeight: 90, rowCount: 7, secondsPerRow: 2 }), {
    overflow: true, distance: 210.4, duration: 14000,
  })
  for (const [secondsPerRow, expected] of [['3', 21000], [0.5, 7000], [100, 420000], [0, 35000], [-2, 35000], [undefined, 35000], ['invalid', 35000], [Infinity, 35000]]) {
    assert.equal(dashboardTableScrollMetrics({ contentHeight: 210.4, viewportHeight: 90, rowCount: 7, secondsPerRow }).duration, expected)
  }
})

test('未测量、空行和非法尺寸不会启动零距离或零时长动画', () => {
  assert.deepEqual(dashboardTableScrollMetrics(), { overflow: false, distance: 0, duration: 0 })
  const valid = { contentHeight: 200, viewportHeight: 100, rowCount: 4 }
  for (const [key, values] of Object.entries({
    contentHeight: [0, -1, NaN, Infinity, 'bad'],
    viewportHeight: [0, -1, NaN, Infinity, 'bad'],
    rowCount: [0, -1, NaN, Infinity, 'bad'],
  })) {
    for (const value of values) assert.equal(dashboardTableScrollMetrics({ ...valid, [key]: value }).overflow, false, `${key}=${value}`)
  }
})

test('真实表格 SFC 可渲染格式化值、排序标题及空值，未测量时不复制正文', async () => {
  const html = await renderToString(createSSRApp(DataTable, {
    rows: [{ name: '甲', value: 1 }, { name: '乙', value: null }],
    columns: [{ name: 'name', title: '名称', sortable: true }, { name: 'value', title: '数值' }],
    autoScroll: true,
    sortState: { field: 'name', direction: 'asc' },
    formatValue: value => value == null ? '暂无' : `值：${value}`,
  }))
  assert.match(html, /名称.*↑/)
  assert.match(html, /值：甲/)
  assert.match(html, /暂无/)
  assert.equal((html.match(/class="body-table"/g) || []).length, 1)
  const readonly = await renderToString(createSSRApp(DataTable, { columns: [{ name: 'name', sortable: true }], interactive: false }))
  assert.doesNotMatch(readonly, /sort-button/)
})

// Mount the actual compiled SFC. The host supplies only layout measurements and
// Web Animation records; Vue still performs real rendering, watches and cleanup.
function mountTable(initial) {
  const scheduled = new Map(), animations = [], observers = []
  let frameId = 0
  const previous = new Map(['requestAnimationFrame', 'cancelAnimationFrame', 'ResizeObserver', 'getComputedStyle'].map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]))
  globalThis.requestAnimationFrame = callback => { scheduled.set(++frameId, callback); return frameId }
  globalThis.cancelAnimationFrame = id => scheduled.delete(id)
  globalThis.ResizeObserver = class {
    constructor(callback) { this.callback = callback; this.disconnected = false; observers.push(this) }
    observe() {}
    disconnect() { this.disconnected = true }
  }
  globalThis.getComputedStyle = element => ({ height: `${element.offsetHeight}px`, width: `${element.offsetWidth}px` })
  const node = (type, text = '') => ({
    type, text, children: [], parent: null, props: {},
    offsetHeight: type === 'table' ? 210.4 : 30,
    offsetWidth: type === 'table' ? 240 : 120,
    clientHeight: 90,
    get tBodies() { return this.children.filter(child => child.type === 'tbody') },
    get tHead() { return this.children.find(child => child.type === 'thead') },
    get rows() { return this.children.filter(child => child.type === 'tr') },
    get cells() { return this.children.filter(child => ['th', 'td'].includes(child.type)) },
    contains(other) { for (let cursor = other; cursor; cursor = cursor.parent) if (cursor === this) return true; return false },
    animate(keyframes, options) {
      const animation = { keyframes, options, currentTime: 0, playState: 'running', cancelled: false,
        cancel() { this.cancelled = true; this.playState = 'idle' },
        pause() { this.playState = 'paused' }, play() { this.playState = 'running' },
      }
      animations.push(animation)
      return animation
    },
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
    remove(element) { if (element.parent) element.parent.children.splice(element.parent.children.indexOf(element), 1); element.parent = null },
    parentNode: element => element.parent,
    nextSibling: element => element.parent?.children[element.parent.children.indexOf(element) + 1] || null,
    patchProp: (element, key, previousValue, value) => { element.props[key] = value },
  })
  const props = ref(initial), root = node('root')
  const app = renderer.createApp({ setup: () => () => h(DataTable, props.value) })
  app.mount(root)
  const descendants = element => [element, ...element.children.flatMap(descendants)]
  return {
    props, root, animations, observers,
    find: predicate => descendants(root).filter(predicate),
    async flush() {
      await nextTick()
      for (let step = 0; step < 5 && scheduled.size; step++) {
        const callbacks = [...scheduled.values()]; scheduled.clear()
        for (const callback of callbacks) callback()
        await nextTick()
      }
      await nextTick()
    },
    close() {
      app.unmount()
      for (const [key, descriptor] of previous) {
        if (descriptor) Object.defineProperty(globalThis, key, descriptor)
        else delete globalThis[key]
      }
    },
  }
}

const columns = [{ name: 'name', title: '名称', sortable: true }, { name: 'value', title: '数值' }]
const rows = [{ name: '甲', value: 1 }, { name: '乙', value: 2 }, { name: '丙', value: 3 }]
const byClass = name => node => node.props.class?.split(' ').includes(name)

test('流畅循环只移动双份正文，表头固定且副本不重复暴露给辅助阅读', async () => {
  const table = mountTable({ rows, columns, autoScroll: true, secondsPerRow: 2 })
  try {
    await table.flush()
    const [animation] = table.animations
    assert.equal(table.animations.length, 1)
    assert.deepEqual(animation.keyframes, [{ transform: 'translate3d(0, 0, 0)' }, { transform: 'translate3d(0, -210.4px, 0)' }])
    assert.deepEqual(animation.options, { duration: 6000, iterations: Infinity, easing: 'linear' })
    const [track] = table.find(byClass('table-body-track'))
    const [header] = table.find(byClass('header-table'))
    assert.equal(track.contains(header), false)
    const bodies = table.find(byClass('body-table'))
    assert.equal(bodies.length, 2)
    assert.equal(bodies[0].props['aria-hidden'], undefined)
    assert.equal(bodies[1].props['aria-hidden'], 'true')
    for (const body of bodies) assert.equal(body.tBodies[0].rows.length, rows.length)
  } finally { table.close() }
})

test('同值数据刷新保留当前动画，修改速度保持进度，关闭和卸载正确清理', async () => {
  const table = mountTable({ rows, columns, autoScroll: true, secondsPerRow: 2 })
  try {
    await table.flush()
    const original = table.animations[0]
    original.currentTime = 1500
    table.props.value = { ...table.props.value, rows: structuredClone(rows), columns: structuredClone(columns) }
    await table.flush()
    assert.equal(table.animations.length, 1)
    assert.equal(original.cancelled, false)
    assert.equal(original.currentTime, 1500)
    table.props.value = { ...table.props.value, secondsPerRow: 4 }
    await table.flush()
    assert.equal(original.cancelled, true)
    assert.equal(table.animations[1].currentTime, 3000)
    table.props.value = { ...table.props.value, autoScroll: false }
    await table.flush()
    assert.equal(table.animations[1].cancelled, true)
    assert.equal(table.find(byClass('body-table')).length, 1)
    table.props.value = { ...table.props.value, autoScroll: true }
    await table.flush()
  } finally { table.close() }
  assert.equal(table.animations.at(-1).cancelled, true)
  assert.equal(table.observers.every(observer => observer.disconnected), true)
})

test('悬停或聚焦暂停连续滚动，离开后继续且排序和行点击仍可交互', async () => {
  const sorted = [], clicked = []
  const table = mountTable({ rows, columns, autoScroll: true, onSort: column => sorted.push(column), onRowClick: row => clicked.push(row) })
  try {
    await table.flush()
    const root = table.find(byClass('dashboard-data-table'))[0]
    const animation = table.animations[0]
    root.props.onMouseenter(); await table.flush()
    assert.equal(animation.playState, 'paused')
    root.props.onMouseleave(); await table.flush()
    assert.equal(animation.playState, 'running')
    root.props.onFocusin(); await table.flush()
    assert.equal(animation.playState, 'paused')
    root.props.onFocusout({ currentTarget: root, relatedTarget: null }); await table.flush()
    assert.equal(animation.playState, 'running')
    table.find(byClass('sort-button'))[0].props.onClick({ stopPropagation() {} })
    table.find(byClass('body-table'))[0].tBodies[0].rows[0].props.onClick({ stopPropagation() {} })
    assert.deepEqual(sorted, [columns[0]])
    assert.deepEqual(clicked, [rows[0]])
  } finally { table.close() }
})
