import test from 'node:test'
import assert from 'node:assert/strict'
import { copyTextWithFallback } from '../src/utils/clipboard.js'
import { createFunctionHarness, readDashboardSfc } from './helpers/dashboard-property-audit.mjs'

const link = 'http://example.test/dashboard/share/example-token'
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }

function environment({ command = () => true, clipboard } = {}) {
  const events = [], created = []
  const ranges = [{ name: 'first', cloneRange() { return { name: this.name } } }, { name: 'second', cloneRange() { return { name: this.name } } }]
  const selection = {
    ranges: [...ranges], get rangeCount() { return this.ranges.length },
    getRangeAt(index) { return this.ranges[index] },
    removeAllRanges() { this.ranges = [] }, addRange(range) { this.ranges.push(range) },
  }
  const window = { scrollX: 17, scrollY: 550, scrollTo(left, top) { this.scrollX = left; this.scrollY = top } }
  const document = { defaultView: window, getSelection: () => selection }
  function element(name, parentElement = null) {
    return {
      name, parentElement, children: [], style: {}, value: '', isConnected: true,
      scrollLeft: 7, scrollTop: 19, selectionStart: 0, selectionEnd: 0, selectionDirection: 'none',
      setAttribute() {},
      appendChild(child) { this.children.push(child); child.parentElement = this },
      remove() { this.parentElement.children = this.parentElement.children.filter(item => item !== this); this.isConnected = false },
      focus() { document.activeElement = this },
      select() {
        document.activeElement = this; this.selectionStart = 0; this.selectionEnd = this.value.length
        selection.ranges = []
        this.scrollLeft = 99; this.scrollTop = 111
        if (this.parentElement) { this.parentElement.scrollLeft = 201; this.parentElement.scrollTop = 202 }
        window.scrollX = 91; window.scrollY = 901
      },
      setSelectionRange(start, end, direction) { this.selectionStart = start; this.selectionEnd = end; this.selectionDirection = direction || 'none' },
      closest(selector) { return selector === '[role="dialog"]' ? dialog : null },
    }
  }
  document.documentElement = element('html')
  document.body = element('body', document.documentElement)
  document.scrollingElement = document.documentElement
  const panel = element('panel', document.body)
  const dialog = element('dialog', panel)
  const input = element('link', dialog); input.value = link
  const focused = element('previous-input', dialog); focused.value = 'existing text'; focused.setSelectionRange(2, 6, 'backward')
  document.activeElement = focused
  document.createElement = () => { const item = element('temporary'); created.push(item); return item }
  if (command) document.execCommand = (action) => { events.push(['exec', action, document.activeElement.value, document.activeElement.parentElement.name]); return command() }
  const navigator = { clipboard }
  return { document, window, navigator, input, focused, dialog, panel, selection, created, events }
}

function preserved(env) {
  assert.equal(env.document.activeElement, env.focused)
  assert.deepEqual([env.focused.selectionStart, env.focused.selectionEnd, env.focused.selectionDirection], [2, 6, 'backward'])
  assert.deepEqual(env.selection.ranges.map(range => range.name), ['first', 'second'])
  assert.deepEqual([env.window.scrollX, env.window.scrollY], [17, 550])
  for (const item of [env.dialog, env.panel, env.focused, env.input]) assert.deepEqual([item.scrollLeft, item.scrollTop], [7, 19])
  assert.equal(env.created.every(item => item.isConnected === false), true)
  assert.equal(env.dialog.children.length, 0)
}

test('HTTP 缺少 Clipboard API 时同步复制成功，恢复焦点、全部选区、滚动并移除临时 DOM', async () => {
  const env = environment()
  const result = copyTextWithFallback(link, env)
  assert.deepEqual(env.events, [['exec', 'copy', link, 'dialog']], '同步点击调用栈中完成兼容复制并位于弹窗内')
  preserved(env)
  assert.deepEqual(await result, { copied: true, selected: false })
})

test('现代 Clipboard 被禁止时仍可用同步兼容方案，不等待拒绝才执行复制', async () => {
  let modernCalls = 0
  const env = environment({ clipboard: { writeText() { modernCalls++; return Promise.reject(new Error('NotAllowedError')) } } })
  assert.deepEqual(await copyTextWithFallback(link, env), { copied: true, selected: false })
  assert.equal(modernCalls, 0, '兼容复制成功后不再发起可能弹权限请求的现代复制')
  preserved(env)
})

test('兼容命令失败时立即调用现代接口，异步成功也不会改变原焦点和选区', async () => {
  const modern = deferred()
  const env = environment({ command: () => false, clipboard: { writeText(value) { env.events.push(['modern', value]); return modern.promise } } })
  const pending = copyTextWithFallback(link, env)
  assert.deepEqual(env.events.map(([type]) => type), ['exec', 'modern'])
  preserved(env)
  modern.resolve()
  assert.deepEqual(await pending, { copied: true, selected: false })
  preserved(env)
})

test('现代接口拒绝且兼容失败时选中可见链接，保留滚动，不虚报复制成功', async () => {
  const env = environment({ command: () => false, clipboard: { writeText: () => Promise.reject(new Error('NotAllowedError')) } })
  assert.deepEqual(await copyTextWithFallback(link, env), { copied: false, selected: true })
  assert.equal(env.document.activeElement, env.input)
  assert.deepEqual([env.input.selectionStart, env.input.selectionEnd], [0, link.length])
  assert.deepEqual([env.window.scrollX, env.window.scrollY, env.dialog.scrollTop, env.input.scrollTop], [17, 550, 19, 19])
  assert.equal(env.created.every(item => item.isConnected === false), true)
})

test('execCommand 抛错或缺失、现代接口同步拒绝均保留手动选区并清理临时 DOM', async () => {
  for (const command of [null, () => { throw new Error('copy disabled') }]) {
    const env = environment({ command, clipboard: { writeText() { throw new Error('denied') } } })
    assert.deepEqual(await copyTextWithFallback(link, env), { copied: false, selected: true })
    assert.equal(env.created.every(item => item.isConnected === false), true)
    assert.deepEqual([env.input.selectionStart, env.input.selectionEnd], [0, link.length])
  }
})

test('异步拒绝时链接已改变或已移除，不抢夺新页面焦点，也不选中错误地址', async () => {
  for (const change of [input => { input.value = 'new link' }, input => { input.isConnected = false }]) {
    const modern = deferred()
    const env = environment({ command: () => false, clipboard: { writeText: () => modern.promise } })
    const pending = copyTextWithFallback(link, env)
    change(env.input)
    modern.reject(new Error('denied'))
    assert.deepEqual(await pending, { copied: false, selected: false })
    assert.equal(env.document.activeElement, env.focused)
  }
})

test('两个分享复制入口都使用实际链接和相邻可见输入，按真实结果给出提示', async () => {
  const sfc = await readDashboardSfc(new URL('../src/views/dashboard/page/index.vue', import.meta.url).pathname)
  const messages = [], calls = []
  const input = { value: link }
  const event = { currentTarget: { closest: () => ({ querySelector: () => input }) } }
  let result = { copied: true, selected: false }
  const h = await createFunctionHarness(sfc, {
    shareDialog: { url: link }, window: { location: { origin: 'http://example.test' } },
    copyTextWithFallback: (text, options) => { calls.push({ text, input: options.input }); return Promise.resolve(result) },
    ElMessage: { success: (message) => messages.push(['success', message]), info: (message) => messages.push(['info', message]) },
  })
  await h.copyShareUrl(event)
  result = { copied: false, selected: true }
  await h.copyShareRow({ token: 'example-token' }, event)
  assert.deepEqual(calls, [{ text: link, input }, { text: link, input }])
  assert.equal(messages[0][0], 'success')
  assert.equal(messages[1][0], 'info')
  assert.match(messages[1][1], /链接已选中.*Ctrl\+C/)
})
