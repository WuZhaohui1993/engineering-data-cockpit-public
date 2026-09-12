import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import vm from 'node:vm'
import { ref, reactive, watch, nextTick } from 'vue'
import { parse, compileTemplate } from 'vue/compiler-sfc'

const file = new URL('../src/components/DashboardMediaImage/index.vue', import.meta.url)
const { descriptor } = parse(await fs.readFile(file, 'utf8'))

function harness(issue = async () => ({ data: { mediaRef: 'reference', expiresAt: '2099-01-01T00:00:00Z' } })) {
  const props = reactive({ value: 'A'.repeat(64), context: { pageId: 1, revisionId: 2, widgetId: 'image', datasetCode: 'data' } })
  const revoked = [], issued = [], fetched = [], watchers = []
  let unmount, expire
  const context = vm.createContext({
    props, ref, Blob, URL: { createObjectURL: () => 'blob:test-image', revokeObjectURL: value => revoked.push(value) },
    defineProps: () => props, defineEmits: () => () => {},
    watch: (...args) => { const stop = watch(...args); watchers.push(stop); return stop },
    onBeforeUnmount: callback => { unmount = callback },
    setTimeout: callback => { expire = callback; return 1 }, clearTimeout: () => {},
    dashboardResourceUrl: value => value.startsWith('/profile/') ? '/dev-api' + value : '',
    issueDashboardMediaRef: body => { issued.push(body); return issue(body) },
    issueDashboardShareMediaRef: (token, body) => { issued.push({ token, ...body }); return issue(body) },
    fetchDashboardMediaBlob: async (...args) => { fetched.push(args); return new Blob(['image'], { type: 'image/png' }) },
  })
  const script = descriptor.scriptSetup.content.replace(/^import .*?;\s*$/gm, '')
  vm.runInContext(script + '\nglobalThis.state = { src, failed };', context)
  return { props, issued, fetched, revoked, state: context.state, expire: () => expire?.(), close: () => { watchers.forEach(stop => stop()); unmount() } }
}
async function flush() { for (let i = 0; i < 5; i++) { await nextTick(); await Promise.resolve() } }

test('媒体引用只换取一次，相同内容的上下文重新渲染不重复消费候选', async () => {
  const h = harness()
  await flush()
  assert.equal(h.state.src.value, 'blob:test-image')
  h.props.context = { ...h.props.context }
  await flush()
  assert.equal(h.issued.length, 1)
  assert.equal(h.fetched.length, 1)
  h.close()
  assert.deepEqual(h.revoked, ['blob:test-image'])
})

test('切换数据期间的迟到媒体授权不会再请求图片或覆盖当前图片', async () => {
  let finish
  const h = harness(() => new Promise(resolve => { finish = resolve }))
  h.props.value = '/profile/local.png'
  await flush()
  finish({ data: { mediaRef: 'obsolete' } })
  await flush()
  assert.equal(h.state.src.value, '/dev-api/profile/local.png')
  assert.equal(h.fetched.length, 0)
  h.close()
})

test('媒体到期释放 Blob，任意外部 URL 不触发授权或图片抓取', async () => {
  const h = harness()
  await flush()
  h.expire()
  assert.equal(h.state.src.value, '')
  assert.equal(h.revoked.length, 1)
  h.props.value = 'https://external.example/secret.jpg'
  await flush()
  assert.equal(h.issued.length, 1)
  assert.equal(h.state.failed.value, true)
  h.close()
})

test('媒体展示模板可编译，加载失败具有可读反馈', () => {
  const result = compileTemplate({ source: descriptor.template.content, filename: file.pathname, id: 'media-test' })
  assert.deepEqual(result.errors, [])
  assert.match(descriptor.template.content, /图片暂不可用/)
})
