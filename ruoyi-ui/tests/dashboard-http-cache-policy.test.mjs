import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import vm from 'node:vm'
import { computed, reactive } from 'vue'
import { parse, compileScript, compileTemplate } from 'vue/compiler-sfc'
import { inspectHttpCachePolicy, updateHttpCachePolicy } from '../src/utils/dashboardHttpCachePolicy.js'

const componentPath = new URL('../src/views/dashboard/integration/components/HttpCachePolicyEditor.vue', import.meta.url).pathname
const { descriptor } = parse(await fs.readFile(componentPath, 'utf8'))

function createEditor(modelValue = '{}') {
  const props = reactive({ modelValue, responseType: 'JSON' })
  const emitted = []
  const errors = []
  const context = vm.createContext({
    computed, inspectHttpCachePolicy, updateHttpCachePolicy, defineProps: () => props,
    defineEmits: () => (event, value) => { emitted.push({ event, value }); props.modelValue = value },
    ElMessage: { error: message => errors.push(message) },
  })
  vm.runInContext(descriptor.scriptSetup.content.replace(/^import .*?;\s*$/gm, '') + '\nglobalThis.state={policy,update};', context)
  return { props, emitted, errors, ...context.state }
}

test('缺省策略启用1秒共享缓存，读取不擅自补写旧JSON', () => {
  for (const value of ['{}', '', '   ', { rowsPath: 'data.items' }]) {
    assert.deepEqual(inspectHttpCachePolicy(value), { error: '', enabled: true, freshSeconds: 1, staleSeconds: 0 })
  }
  const editor = createEditor('{"rowsPath":"data.items"}')
  assert.equal(editor.policy.value.freshSeconds, 1)
  assert.equal(editor.emitted.length, 0)
  assert.deepEqual(JSON.parse(editor.props.modelValue), { rowsPath: 'data.items' })
})

test('关闭同时清零三个缓存参数，重新启用恢复1秒而不是旧期限', () => {
  const editor = createEditor('{"cacheSeconds":300,"sharedFetchSeconds":60,"staleIfErrorSeconds":600,"rowsPath":"records"}')
  editor.update('enabled', false)
  assert.deepEqual(JSON.parse(editor.props.modelValue), { cacheSeconds: 0, sharedFetchSeconds: 0, staleIfErrorSeconds: 0, rowsPath: 'records' })
  assert.equal(editor.policy.value.enabled, false)
  editor.update('enabled', true)
  assert.deepEqual(JSON.parse(editor.props.modelValue), { cacheSeconds: 0, sharedFetchSeconds: 1, staleIfErrorSeconds: 0, rowsPath: 'records' })
  assert.equal(editor.policy.value.enabled, true)
  assert.equal(editor.policy.value.freshSeconds, 1)
  assert.ok(editor.emitted.every(item => item.event === 'update:modelValue'))
})

test('旧cacheSeconds按两项最大值回显，降低界面期限后不会继续被旧值放大', () => {
  assert.equal(inspectHttpCachePolicy({ cacheSeconds: 120, sharedFetchSeconds: 30 }).freshSeconds, 120)
  assert.equal(inspectHttpCachePolicy({ cacheSeconds: 30, sharedFetchSeconds: 120 }).freshSeconds, 120)
  assert.equal(inspectHttpCachePolicy({ cacheSeconds: 3600, sharedFetchSeconds: 0 }).freshSeconds, 3600)
  const next = JSON.parse(updateHttpCachePolicy('{"cacheSeconds":3600,"sharedFetchSeconds":20,"staleIfErrorSeconds":90}', 'freshSeconds', 5))
  assert.deepEqual(next, { cacheSeconds: 0, sharedFetchSeconds: 5, staleIfErrorSeconds: 90 })
  assert.equal(inspectHttpCachePolicy(next).freshSeconds, 5)
})

test('手写策略与控件双向同步，不覆盖响应解析、认证或自定义嵌套设置', () => {
  const config = { rowsPath: 'data.items', codePath: 'code', successCodes: ['0'], custom: { keep: true } }
  const editor = createEditor(JSON.stringify(config))
  editor.update('staleSeconds', 120)
  assert.deepEqual(JSON.parse(editor.props.modelValue), { ...config, staleIfErrorSeconds: 120 })
  editor.props.modelValue = JSON.stringify({ ...config, sharedFetchSeconds: 7, staleIfErrorSeconds: 240 })
  assert.equal(editor.policy.value.freshSeconds, 7)
  assert.equal(editor.policy.value.staleSeconds, 240)
  editor.update('freshSeconds', 3)
  assert.deepEqual(JSON.parse(editor.props.modelValue), { ...config, sharedFetchSeconds: 3, staleIfErrorSeconds: 240, cacheSeconds: 0 })
  const original = structuredClone(config)
  updateHttpCachePolicy(config, 'freshSeconds', 3)
  assert.deepEqual(config, original)
})

test('零新鲜期限加正故障期限仍启用，全部为零才关闭', () => {
  const editor = createEditor('{"cacheSeconds":0,"sharedFetchSeconds":0,"staleIfErrorSeconds":45}')
  assert.equal(editor.policy.value.enabled, true)
  assert.equal(editor.policy.value.freshSeconds, 0)
  assert.equal(editor.policy.value.staleSeconds, 45)
  editor.update('enabled', true)
  assert.equal(editor.policy.value.staleSeconds, 45)
  assert.equal(editor.policy.value.freshSeconds, 0)
  editor.update('staleSeconds', 0)
  assert.equal(editor.policy.value.enabled, false)
})

test('显式null、字符串数字、小数、负数和超限值拒绝，不把非法JSON改写为默认策略', () => {
  for (const [key, max] of [['cacheSeconds', 3600], ['sharedFetchSeconds', 3600], ['staleIfErrorSeconds', 86400]]) {
    for (const value of [null, '', '30', false, 1.5, -1, max + 1]) {
      const config = JSON.stringify({ [key]: value })
      assert.match(inspectHttpCachePolicy(config).error, /必须是 0 至/)
      assert.throws(() => updateHttpCachePolicy(config, 'enabled', false), /必须是 0 至/)
    }
    assert.equal(inspectHttpCachePolicy({ [key]: max }).error, '')
  }
  for (const config of ['{', '[]', 'null', '"text"', '1']) {
    const editor = createEditor(config)
    assert.match(editor.policy.value.error, /JSON 对象/)
    editor.update('enabled', false)
    assert.equal(editor.props.modelValue, config)
    assert.equal(editor.emitted.length, 0)
    assert.equal(editor.errors.length, 1)
  }
})

test('控件提交非法值不发出变更，原有效策略继续保留', () => {
  const editor = createEditor('{"rowsPath":"data"}')
  for (const [field, value] of [['enabled', 'false'], ['freshSeconds', undefined], ['freshSeconds', 1.5], ['staleSeconds', null], ['staleSeconds', '20']]) {
    editor.update(field, value)
  }
  assert.equal(editor.emitted.length, 0)
  assert.equal(editor.errors.length, 5)
  assert.equal(editor.props.modelValue, '{"rowsPath":"data"}')
})

test('缓存组件和管理页按真实脚本编译，媒体响应类型传入缓存说明组件', async () => {
  for (const filename of [componentPath, new URL('../src/views/dashboard/integration/index.vue', import.meta.url).pathname]) {
    const { descriptor: sfc, errors } = parse(await fs.readFile(filename, 'utf8'))
    assert.deepEqual(errors, [])
    const script = compileScript(sfc, { id: filename })
    assert.deepEqual(compileTemplate({ source: sfc.template.content, filename, id: filename, compilerOptions: { bindingMetadata: script.bindings } }).errors, [])
    if (filename !== componentPath) {
      const find = node => node.tag === 'HttpCachePolicyEditor' ? node : node.children?.map(find).find(Boolean)
      const editor = find(sfc.template.ast)
      assert.ok(editor)
      assert.equal(editor.props.find(prop => prop.name === 'bind' && prop.arg?.content === 'response-type').exp.content, 'endpointDialog.form.responseType')
      assert.equal(editor.props.find(prop => prop.name === 'model').exp.content, 'endpointDialog.form.configJson')
    }
  }
})
