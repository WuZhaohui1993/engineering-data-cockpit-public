import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { normalizeDashboardShareVersionMode, dashboardShareVersionModeLabel, dashboardShareVersionModeConfirmation } from '../src/utils/dashboardShareMode.js'
import { createFunctionHarness, readDashboardSfc } from './helpers/dashboard-property-audit.mjs'

const page = await readDashboardSfc(new URL('../src/views/dashboard/page/index.vue', import.meta.url).pathname)
const clone = (value) => JSON.parse(JSON.stringify(value))
const declaration = page.program.body.filter((node) => node.type === 'VariableDeclaration').flatMap((node) => node.declarations).find((node) => node.id.name === 'shareDialog')
const createShareState = () => new Function('reactive', `return ${page.script.slice(declaration.init.start, declaration.init.end)}`)((value) => value)
const require = createRequire(import.meta.url)
const vueRequire = createRequire(require.resolve('vue'))
const { baseParse } = vueRequire('@vue/compiler-dom')
const entry = { pageId: 392, pageCode: 'overview', pageName: '项目概况', status: '0', isDeleted: '0' }

test('新分享默认跟随当前发布，旧记录缺字段保持固定版本', () => {
  assert.equal(createShareState().versionMode, 'FOLLOW_PUBLISHED')
  for (const value of [undefined, null, '', 'FIXED', 'unexpected']) {
    assert.equal(normalizeDashboardShareVersionMode(value), 'FIXED')
    assert.equal(dashboardShareVersionModeLabel(value), '固定版本')
  }
  assert.equal(dashboardShareVersionModeLabel('FOLLOW_PUBLISHED'), '跟随当前发布')
  assert.match(dashboardShareVersionModeConfirmation('FIXED'), /此刻的当前发布配置/)
  assert.match(dashboardShareVersionModeConfirmation('FOLLOW_PUBLISHED'), /发布和回滚/)
})

async function harness({ status = '0', sharing = {}, confirmed = true } = {}) {
  const events = []
  const current = { ...entry, status }
  const shareDialog = createShareState()
  Object.assign(shareDialog, { open: true, page: current, pageIds: [392, 393], ...sharing })
  const h = await createFunctionHarness(page, {
    shareDialog, rows: { value: [current] }, runnablePages: { value: status === '0' ? [current] : [] },
    publishedPageDetails: { value: [] }, checkingPublishedPages: new Set(),
    window: { location: { origin: 'http://localhost' } },
    ElMessage: { success: (message) => events.push(['success', message]), warning: (message) => events.push(['warning', message]) },
    ElMessageBox: { confirm: async (...args) => { events.push(['confirm', ...args]); if (!confirmed) throw 'cancel' } },
    listPublishedDashboardPages: async () => ({ data: status === '0' ? [current] : [] }),
    listDashboardShares: async () => ({ data: shareDialog.rows }),
    createDashboardShare: async (pageId, body) => { events.push(['create', pageId, clone(body)]); return { data: { token: 'existing-token', versionMode: body.versionMode } } },
    updateDashboardShareMode: async (pageId, shareId, mode) => { events.push(['update', pageId, shareId, mode]); return { data: { shareId, pageId, versionMode: mode, revisionId: 123 } } },
  })
  return { h, events, current }
}

test('创建请求明确提交所选模式，不改变期限和授权页面', async () => {
  for (const versionMode of ['FOLLOW_PUBLISHED', 'FIXED']) {
    const { h, events } = await harness({ sharing: { versionMode, expiryMode: 'TIMED', expiresValue: 7, expiresUnit: 'DAY' } })
    await h.createShare()
    assert.deepEqual(events.find(([type]) => type === 'create'), ['create', 392, {
      permanent: false, expiresValue: 7, expiresUnit: 'DAY', pageIds: [392, 393], versionMode,
    }])
    assert.equal(h.shareDialog.url, 'http://localhost/dashboard/share/existing-token')
    assert.equal(h.shareDialog.creating, false)
  }
})

test('已有链接同 URL 切模式仅更新策略，原令牌、期限和授权成员不改变', async () => {
  const row = { shareId: 8, status: 'ACTIVE', token: 'existing-token', expiresAt: null, pageIds: [392, 393], versionMode: 'FOLLOW_PUBLISHED' }
  const { h, events } = await harness({ sharing: { rows: [row] } })
  const address = h.shareAddress(row)
  await h.changeShareVersionMode(row)
  assert.deepEqual(events.find(([type]) => type === 'update'), ['update', 392, 8, 'FIXED'])
  assert.equal(row.versionMode, 'FIXED')
  assert.equal(h.shareAddress(row), address)
  assert.equal(row.expiresAt, null)
  assert.deepEqual(row.pageIds, [392, 393])
  assert.equal(h.shareDialog.changingMode, null)
  await h.changeShareVersionMode(row)
  assert.deepEqual(events.filter(([type]) => type === 'update').at(-1), ['update', 392, 8, 'FOLLOW_PUBLISHED'])
})

test('旧链接无模式字段，操作目标是改为跟随；取消不发写请求', async () => {
  const row = { shareId: 8, status: 'ACTIVE', token: 'old-token', expiresAt: null }
  const { h, events } = await harness({ sharing: { rows: [row] }, confirmed: false })
  await h.changeShareVersionMode(row)
  assert.match(events[0][2], /跟随当前发布/)
  assert.equal(events.some(([type]) => type === 'update'), false)
  assert.equal(row.versionMode, undefined)
  assert.equal(h.shareDialog.changingMode, null)
})

test('撤销、过期或停用入口不允许改模式，停用页历史地址仍可查看', async () => {
  for (const row of [
    { shareId: 8, status: 'REVOKED', expiresAt: null },
    { shareId: 9, status: 'ACTIVE', expiresAt: '2000-01-01 00:00:00' },
  ]) {
    const { h, events } = await harness({ sharing: { rows: [row] } })
    assert.equal(h.canChangeShareVersionMode(row), false)
    await h.changeShareVersionMode(row)
    assert.equal(events.length, 0)
  }
  const row = { shareId: 8, status: 'ACTIVE', expiresAt: null, token: 'old-token' }
  const { h, events } = await harness({ status: '1', sharing: { rows: [row] } })
  assert.equal(h.canChangeShareVersionMode(row), false)
  await h.changeShareVersionMode(row)
  assert.equal(events.length, 0)
  assert.equal(h.shareAddress(row), 'http://localhost/dashboard/share/old-token')
})

test('提交前页面失效或服务端拒绝某个授权成员，模式原值和对话框保留', async () => {
  const row = { shareId: 8, status: 'ACTIVE', versionMode: 'FIXED' }
  const { h, events } = await harness({ sharing: { rows: [row] } })
  h.updateDashboardShareMode = async () => { throw new Error('成员页面已停用') }
  await h.changeShareVersionMode(row)
  assert.equal(row.versionMode, 'FIXED')
  assert.equal(h.shareDialog.open, true)
  assert.equal(h.shareDialog.changingMode, null)
  assert.equal(events.some(([type]) => type === 'success'), false)

  h.listPublishedDashboardPages = async () => ({ data: [] })
  let called = false
  h.updateDashboardShareMode = async () => { called = true }
  await h.changeShareVersionMode(row)
  assert.equal(called, false)
  assert.equal(row.versionMode, 'FIXED')
})

test('确认期间关闭对话框或重复点击不会误提交到其他页面', async () => {
  const row = { shareId: 8, status: 'ACTIVE', versionMode: 'FIXED' }
  const { h, events } = await harness({ sharing: { rows: [row] } })
  let resolve
  h.ElMessageBox.confirm = () => new Promise((done) => { resolve = done })
  const pending = h.changeShareVersionMode(row)
  await h.changeShareVersionMode(row)
  h.shareDialog.open = false
  resolve()
  await pending
  assert.equal(events.some(([type]) => type === 'update'), false)
  assert.equal(h.shareDialog.changingMode, null)
})

test('生产 UI 提供模式选择和列表标记，模式操作服从原 share:create 权限', () => {
  const ast = baseParse(page.descriptor.template.content)
  const nodes = []
  const visit = (items) => { for (const node of items || []) { if (node.type !== 1) continue; nodes.push(node); visit(node.children) } }
  visit(ast.children)
  const modeGroup = nodes.find((node) => node.tag === 'el-radio-group' && node.props.some((prop) => prop.type === 7 && prop.name === 'model' && prop.exp?.content === 'shareDialog.versionMode'))
  assert.ok(modeGroup)
  assert.ok(nodes.some((node) => node.tag === 'el-table-column' && node.props.some((prop) => prop.type === 6 && prop.name === 'label' && prop.value?.content === '版本模式')))
  const action = nodes.find((node) => node.tag === 'el-button' && node.props.some((prop) => prop.type === 7 && prop.name === 'on' && prop.exp?.content === 'changeShareVersionMode(scope.row)'))
  assert.equal(action.props.find((prop) => prop.type === 7 && prop.name === 'hasPermi')?.exp?.content, "['dashboard:share:create']")
  assert.equal(action.props.find((prop) => prop.type === 7 && prop.name === 'if')?.exp?.content, 'canChangeShareVersionMode(scope.row)')
})

test('分享 API 使用独立轻量版本地址，模式修改只提交 versionMode', async () => {
  const source = readFileSync(new URL('../src/api/dashboard.js', import.meta.url), 'utf8')
  const requests = []
  const api = new Function('request', `${source.replace(/^import .*$/gm, '').replaceAll('export function ', 'function ')}; return { getDashboardShareVersion, updateDashboardShareMode }`)((request) => { requests.push(request); return Promise.resolve({ data: {} }) })
  await api.getDashboardShareVersion('token/?', '', { dashboardRuntimeRequest: true })
  await api.getDashboardShareVersion('token/?', ' overview ', { dashboardRuntimeRequest: true })
  await api.updateDashboardShareMode(392, 8, 'FIXED')
  assert.deepEqual(requests, [
    { url: '/dashboard/public/share/token%2F%3F/version', method: 'get', dashboardRuntimeRequest: true },
    { url: '/dashboard/public/share/token%2F%3F/page/overview/version', method: 'get', dashboardRuntimeRequest: true },
    { url: '/dashboard/page/392/shares/8', method: 'put', data: { versionMode: 'FIXED' } },
  ])
})
