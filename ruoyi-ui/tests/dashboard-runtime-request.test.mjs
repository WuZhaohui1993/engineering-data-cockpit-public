import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import axios from 'axios'
import errorCode from '../src/utils/errorCode.js'

// Exercise the actual Axios interceptors with isolated UI/session dependencies.
const requestSource = readFileSync(new URL('../src/utils/request.js', import.meta.url), 'utf8')
  .replace(/^import .+\r?\n/gm, '')
  .replace('import.meta.env.VITE_APP_BASE_API', "'/dev-api'")
  .replace('export let isRelogin', 'let isRelogin')
  .replace('export function download', 'function download')
  .replace('export default service', 'return { service, isRelogin }')
const apiSource = readFileSync(new URL('../src/api/dashboard.js', import.meta.url), 'utf8')
  .replace(/^import .+\r?\n/gm, '')
  .replaceAll('export function ', 'function ')
const runtimeApiNames = [
  'getDashboardRuntime', 'getDashboardRuntimeByCode', 'getDashboardRevisionPreview',
  'getDashboardShareRuntime', 'fetchDashboardData', 'fetchDashboardShareData', 'fetchDashboardSharePageData',
  'getDashboardPage', 'saveDashboardDraft',
]

function harness() {
  const notices = [], logs = [], requests = []
  const session = new Map()
  const { service, isRelogin } = new Function(
    'axios', 'ElNotification', 'ElMessageBox', 'ElMessage', 'ElLoading', 'getToken',
    'errorCode', 'tansParams', 'blobValidate', 'cache', 'saveAs', 'useUserStore', 'console', requestSource,
  )(
    axios, { error: value => notices.push(['notification', value]) },
    { confirm: (...args) => { notices.push(['login', args]); return new Promise(() => {}) } },
    value => notices.push(['message', value]), {}, () => 'test-token', errorCode,
    params => new URLSearchParams(params).toString(), () => false,
    { session: { getJSON: key => session.get(key), setJSON: (key, value) => session.set(key, value) } },
    () => {}, () => ({ logOut() { throw new Error('Login requires user confirmation') } }),
    { log: (...args) => logs.push(args), warn: (...args) => logs.push(args) },
  )
  let responseData = { code: 200, data: { rows: [{ value: 1 }], quality: 'SUCCESS' } }
  let transportFailure
  service.defaults.adapter = async config => {
    requests.push(config)
    if (transportFailure) {
      const error = new axios.AxiosError(transportFailure.message, transportFailure.code, config)
      if (transportFailure.status) error.response = { status: transportFailure.status }
      throw error
    }
    return { data: responseData, config, status: 200, request: {} }
  }
  const api = new Function('request', `${apiSource}\nreturn { ${runtimeApiNames.join(', ')} }`)(service)
  return {
    service, api, notices, logs, requests, isRelogin,
    respond(value) { responseData = value },
    fail(value) { transportFailure = value },
  }
}

const quiet = { dashboardRuntimeRequest: true }

test('运行页业务失败保留权限和状态信息，仅在页面内显示', async () => {
  for (const [code, msg] of [[403, '没有权限'], [404, '页面不存在'], [500, '页面已停用'], [601, '请求受限']]) {
    const state = harness()
    state.respond({ code, msg })
    await assert.rejects(state.api.getDashboardRuntime(1, false, quiet), error => {
      assert.equal(error.businessCode, code)
      assert.equal(error.httpStatus, 200)
      assert.equal(error.requestErrorKind, 'business')
      assert.equal(error.message, errorCode[code] || msg)
      return true
    })
    assert.equal(state.notices.length, 0)
    assert.equal(state.logs.length, 0)
  }
})

test('分享撤销不能被包装成网络失败，普通页面错误提示保持不变', async () => {
  const state = harness()
  state.respond({ code: 500, msg: '分享链接无效或已撤销' })
  await assert.rejects(state.api.getDashboardShareRuntime('test-share', '', quiet), error =>
    error.requestErrorKind === 'business' && error.businessCode === 500 && error.message.includes('已撤销'))
  assert.equal(state.notices.length, 0)
  await assert.rejects(state.api.getDashboardRuntime(1), error => !error.requestErrorKind)
  assert.equal(state.notices.length, 1)
  assert.equal(state.notices[0][0], 'message')
})

test('登录过期仍触发若依统一登录提示，并保留结构化401', async () => {
  const state = harness()
  state.respond({ code: 401, msg: '登录状态已过期' })
  for (let index = 0; index < 2; index++) {
    await assert.rejects(state.api.fetchDashboardData(1, 'widget', 'dataset', {}, false, [], null, quiet), error =>
      error.businessCode === 401 && error.requestErrorKind === 'business')
  }
  assert.equal(state.notices.length, 1)
  assert.equal(state.notices[0][0], 'login')
  assert.equal(state.isRelogin.show, true)
})

test('网络超时和HTTP权限失败保留Axios字段，不产生逐组件全局提示', async () => {
  for (const failure of [
    { code: 'ECONNABORTED', message: 'timeout of 10000ms exceeded' },
    { code: 'ERR_NETWORK', message: 'Network Error' },
    { code: 'ERR_BAD_REQUEST', message: 'Request failed with status code 403', status: 403 },
  ]) {
    const state = harness()
    state.fail(failure)
    await assert.rejects(state.api.getDashboardRuntimeByCode('test-page', false, quiet), error => {
      assert.equal(error.code, failure.code)
      assert.equal(error.httpStatus, failure.status || 0)
      assert.equal(error.requestErrorKind, 'transport')
      assert.equal(axios.isAxiosError(error), true)
      return true
    })
    assert.equal(state.notices.length, 0)
    assert.equal(state.logs.length, 0)
  }
})

test('运行API显式启用静默选项，不修改接口权限、预览参数和分享路径', async () => {
  const state = harness()
  await state.api.getDashboardRuntime(10, true, quiet)
  await state.api.getDashboardRuntimeByCode('test page', true, quiet)
  await state.api.getDashboardRevisionPreview(10, 20, quiet)
  await state.api.getDashboardShareRuntime('share token', 'test-page', quiet)
  assert.deepEqual(state.requests.map(item => item.url), [
    '/dashboard/page/10/preview', '/dashboard/page/code/test%20page/preview',
    '/dashboard/page/10/revision/20/preview', '/dashboard/public/share/share%20token/page/test-page',
  ])
  assert.ok(state.requests.every(item => item.dashboardRuntimeRequest === true))
  assert.ok(state.requests.every(item => item.headers.Authorization === 'Bearer test-token'))
  await state.api.getDashboardPage(10)
  assert.equal(state.requests.at(-1).dashboardRuntimeRequest, undefined)
})

test('只读取数允许快速重复刷新，写入接口仍保留防重复提交', async () => {
  const state = harness()
  const calls = [
    () => state.api.fetchDashboardData(1, 'widget', 'dataset', { project: 'a' }, true, [], 20, quiet),
    () => state.api.fetchDashboardShareData('share', 1, 'widget', 'dataset', {}, [], quiet),
    () => state.api.fetchDashboardSharePageData('share', 'test-page', 'widget', 'dataset', {}, [], quiet),
  ]
  for (const call of calls) {
    const first = await call()
    const second = await call()
    assert.equal(first.quality, 'SUCCESS')
    assert.deepEqual(second, first)
  }
  assert.equal(state.requests.length, 6)
  assert.ok(state.requests.every(item => item.dashboardRuntimeRequest === true))
  assert.equal(JSON.parse(state.requests[0].data).revisionId, 20)
  await state.api.saveDashboardDraft(1, { schema: {} })
  await assert.rejects(state.api.saveDashboardDraft(1, { schema: {} }), /请勿重复提交/)
  assert.equal(state.requests.length, 7)
})
