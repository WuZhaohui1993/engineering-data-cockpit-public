import test from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { getDashboardPublishedRevision, hasDashboardPublishedVersion, isDashboardPageRunnable } from '../src/utils/dashboardPageAvailability.js'
import { createFunctionHarness, designerPath, readDashboardSfc } from './helpers/dashboard-property-audit.mjs'

const pageSfc = await readDashboardSfc(new URL('../src/views/dashboard/page/index.vue', import.meta.url).pathname)
const require = createRequire(import.meta.url)
const vueRequire = createRequire(require.resolve('vue'))
const { baseParse } = vueRequire('@vue/compiler-dom')
const publishedPage = () => ({ pageId: 392, pageCode: 'xinghua-overview', status: '0', isDeleted: '0', currentVersionNo: 7 })
const catalog = [{ pageId: 392, pageCode: 'xinghua-overview', versionNo: 7 }]

test('发布目录是列表运行依据，草稿版本号不会误认为已发布', () => {
  assert.equal(isDashboardPageRunnable(publishedPage(), catalog), true)
  assert.equal(isDashboardPageRunnable({ ...publishedPage(), pageId: '392' }, catalog), true)
  assert.equal(isDashboardPageRunnable(publishedPage(), []), false)
  assert.equal(isDashboardPageRunnable(publishedPage()), false)
  assert.equal(isDashboardPageRunnable({ ...publishedPage(), currentRevisionStatus: 'DRAFT' }), false)
  assert.equal(isDashboardPageRunnable({ ...publishedPage(), pageId: undefined }, catalog), false)
})

test('停用和回收页面没有运行入口，详情兼容历史运行指针指向草稿', () => {
  assert.equal(isDashboardPageRunnable({ ...publishedPage(), status: '1' }, catalog), false)
  assert.equal(isDashboardPageRunnable({ ...publishedPage(), isDeleted: '1' }, catalog), false)
  assert.equal(isDashboardPageRunnable({ ...publishedPage(), currentRevisionStatus: 'DRAFT', revisions: [{ status: 'DRAFT' }, { status: 'PUBLISHED' }] }), true)
  assert.equal(isDashboardPageRunnable({ ...publishedPage(), currentRevisionStatus: 'PUBLISHED', revisions: [{ status: 'ARCHIVED' }] }), false)
  assert.equal(isDashboardPageRunnable({ ...publishedPage(), currentRevisionStatus: 'DRAFT', revisions: [{ status: 'DRAFT' }] }), false)
})

async function harness({ page = publishedPage(), published = catalog, current = page, response = catalog, details = [] } = {}) {
  const events = []
  const h = await createFunctionHarness(pageSfc, {
    rows: { value: [current] }, runnablePages: { value: published }, publishedPageDetails: { value: details }, checkingPublishedPages: new Set(),
    selectedPageIds: { value: [] }, pageTableRef: { value: { clearSelection: () => events.push(['clear-selection']) } },
    router: { push: (path) => events.push(['navigate', path]) },
    ElMessage: { warning: (message) => events.push(['warning', message]) },
    listPublishedDashboardPages: async () => { events.push(['check']); return { data: response } },
    listDashboardShares: async () => { events.push(['share-list']); return { data: [] } },
    getDashboardPage: async () => ({ data: { ...page, revisions: [{ revisionId: 50, versionNo: 7, status: 'PUBLISHED' }] } }),
    createDashboardShare: async () => { events.push(['share-create']); return { data: {} } },
    configureDashboardPageMenu: async () => { events.push(['menu-create']); return { data: {} } },
    menuConfigDialog: { open: false, saving: false, page: null, result: null },
    shareDialog: { open: false, creating: false, page: null },
  })
  return { h, events, page }
}

test('生产模板中运行、分享、菜单配置均隐藏未发布入口，版本管理仍保留', async () => {
  const ast = baseParse(pageSfc.descriptor.template.content)
  const buttons = []
  const visit = (nodes) => {
    for (const node of nodes || []) {
      if (node.type !== 1) continue
      if (['el-button', 'el-dropdown-item'].includes(node.tag)) buttons.push(node)
      visit(node.children)
    }
  }
  visit(ast.children)
  const label = (node) => node.children.filter((child) => child.type === 2).map((child) => child.content).join('').trim()
  const { h, page } = await harness()
  for (const text of ['运行', '分享', '菜单配置']) {
    const node = buttons.find((item) => label(item) === text)
    assert.ok(node, text)
    const expression = node.props.find((prop) => prop.type === 7 && prop.name === 'if')?.exp?.content
    assert.ok(expression, `${text}必须根据发布状态显示`)
    const evaluate = new Function('canUsePublishedPage', 'canManagePageShares', 'scope', 'checkPermi', `return ${expression}`)
    assert.equal(evaluate(h.canUsePublishedPage, h.canManagePageShares, { row: page }, () => true), true)
    h.runnablePages.value = []
    assert.equal(evaluate(h.canUsePublishedPage, h.canManagePageShares, { row: page }, () => true), false)
    h.runnablePages.value = catalog
  }
  const versions = buttons.find((item) => label(item) === '版本')
  assert.ok(versions)
  assert.equal(versions.props.some((prop) => prop.type === 7 && prop.name === 'if'), false)

  const dropdownPermissions = {
    menu: 'dashboard:page:menu', share: 'dashboard:share:list', move: 'dashboard:page:edit',
    copy: 'dashboard:page:add', status: 'dashboard:page:edit', delete: 'dashboard:page:delete',
  }
  for (const [command, permission] of Object.entries(dropdownPermissions)) {
    const node = buttons.find(item => item.tag === 'el-dropdown-item' && item.props.some(prop => prop.type === 6 && prop.name === 'command' && prop.value?.content === command))
    assert.ok(node, `${command}下拉项保留`)
    assert.equal(node.props.some(prop => prop.type === 7 && prop.name === 'hasPermi'), false, '多根组件不使用运行时权限指令')
    const expression = node.props.find(prop => prop.type === 7 && prop.name === 'if')?.exp?.content
    assert.ok(expression, `${command}必须在渲染前判断权限`)
    const evaluate = new Function('canUsePublishedPage', 'canManagePageShares', 'scope', 'checkPermi', `return ${expression}`)
    const check = allowed => required => { assert.deepEqual(required, [permission], `${command}保留原权限字符`); return allowed }
    assert.equal(evaluate(h.canUsePublishedPage, h.canManagePageShares, { row: page }, check(true)), true)
    assert.equal(evaluate(h.canUsePublishedPage, h.canManagePageShares, { row: page }, check(false)), false)
  }
})

test('即使直接执行旧回调，未发布页也不会跳路由或打开分享与菜单配置', async () => {
  const { h, page, events } = await harness({ published: [] })
  await h.openRuntime(page)
  await h.handlePageAction('menu', page)
  await h.handlePageAction('share', page)
  assert.equal(h.menuConfigDialog.open, false)
  assert.equal(h.shareDialog.open, false)
  assert.equal(events.filter(([type]) => type === 'warning').length, 3)
  assert.equal(events.some(([type]) => ['navigate', 'check', 'share-list'].includes(type)), false)
})

test('已发布页保持原运行路径和菜单配置入口，进入前复核发布目录', async () => {
  const { h, page, events } = await harness()
  await h.openRuntime(page)
  await h.handlePageAction('menu', page)
  assert.deepEqual(events, [['check'], ['navigate', '/dashboard/runtime/392'], ['check']])
  assert.equal(h.menuConfigDialog.open, true)
  assert.equal(h.menuConfigDialog.page.pageId, page.pageId)
})

test('列表停留期间页面被撤回，点击运行不进入错误页并移除失效入口', async () => {
  const { h, page, events } = await harness({ response: [] })
  await h.openRuntime(page)
  assert.equal(events.some(([type]) => type === 'navigate'), false)
  assert.equal(events.filter(([type]) => type === 'warning').length, 1)
  assert.equal(h.canUsePublishedPage(page), false)
})

test('旧下拉行已被最新停用状态替换，回调按当前列表拦截', async () => {
  const { h, page, events } = await harness({ current: { ...publishedPage(), status: '1' } })
  await h.handlePageAction('menu', page)
  assert.equal(h.menuConfigDialog.open, false)
  assert.equal(events.some(([type]) => type === 'check'), false)
})

test('分享和菜单对话框打开后发布版本失效，提交不发出写请求', async () => {
  for (const action of ['share', 'menu']) {
    const { h, page, events } = await harness({ response: [] })
    if (action === 'share') {
      Object.assign(h.shareDialog, { open: true, page })
      await h.createShare()
      assert.equal(h.shareDialog.creating, false)
    } else {
      Object.assign(h.menuConfigDialog, { open: true, page })
      await h.submitMenuConfig()
      assert.equal(h.menuConfigDialog.saving, false)
    }
    assert.equal(events.some(([type]) => type.endsWith('-create')), false)
  }
})

test('发布目录复核失败或连续点击时，不提前进入运行页', async () => {
  const { h, page, events } = await harness()
  let resolve
  h.listPublishedDashboardPages = () => new Promise((done) => { resolve = done })
  const pending = h.openRuntime(page)
  await h.openRuntime(page)
  assert.equal(events.length, 0)
  resolve({ data: catalog })
  await pending
  assert.equal(events.filter(([type]) => type === 'navigate').length, 1)

  h.listPublishedDashboardPages = async () => { throw new Error('unavailable') }
  await h.openRuntime(page)
  assert.equal(events.filter(([type]) => type === 'navigate').length, 1)
  assert.equal(h.checkingPublishedPages.size, 0)
})

test('设计器隐藏未发布运行入口，草稿预览独立保留，发布后可正常运行', async () => {
  const designerSfc = await readDashboardSfc(designerPath)
  const declaration = designerSfc.program.body
    .filter((node) => node.type === 'VariableDeclaration')
    .flatMap((node) => node.declarations)
    .find((node) => node.id.name === 'canRunPage')
  const canRun = new Function('computed', 'isDashboardPageRunnable', 'page',
    `return ${designerSfc.script.slice(declaration.init.start, declaration.init.end)}`)
  const page = { ...publishedPage(), currentVersionNo: null, revisions: [{ status: 'DRAFT' }] }
  const availability = canRun((getter) => ({ get value() { return getter() } }), isDashboardPageRunnable, page)
  const events = []
  const h = await createFunctionHarness(designerSfc, {
    page, pageId: page.pageId, canRunPage: availability, checkingRuntime: { value: false },
    draftDirty: { value: false },
    listPublishedDashboardPages: async () => ({ data: catalog }),
    router: { push: (path) => events.push(['navigate', path]) },
    ElMessage: { warning: () => events.push(['warning']) },
  })
  await h.runtime()
  assert.equal(availability.value, false)
  assert.deepEqual(events, [['warning']])
  page.revisions.push({ status: 'PUBLISHED' })
  await h.runtime()
  assert.equal(availability.value, true)
  assert.deepEqual(events[1], ['navigate', '/dashboard/runtime/392'])
  h.listPublishedDashboardPages = async () => ({ data: [] })
  await h.runtime()
  assert.equal(events.filter(([type]) => type === 'navigate').length, 1)
  assert.equal(h.checkingRuntime.value, false)

  const ast = baseParse(designerSfc.descriptor.template.content)
  const buttons = []
  const visit = (nodes) => {
    for (const node of nodes || []) {
      if (node.type !== 1) continue
      if (node.tag === 'el-button') buttons.push(node)
      visit(node.children)
    }
  }
  visit(ast.children)
  const runtimeButton = buttons.find((node) => node.props.some((prop) => prop.type === 7 && prop.name === 'on' && prop.exp?.content === 'runtime'))
  assert.equal(runtimeButton.props.find((prop) => prop.type === 7 && prop.name === 'if')?.exp?.content, 'canRunPage')
  const previewButton = buttons.find((node) => node.props.some((prop) => prop.type === 7 && prop.name === 'on' && prop.exp?.content.startsWith('preview(')))
  assert.ok(previewButton, '草稿预览按钮保留')
  assert.equal(previewButton.props.some((prop) => prop.type === 7 && prop.name === 'if'), false)
})

test('停用但有发布版本的页面保留历史分享管理，禁止运行、菜单配置和生成新链接', async () => {
  const page = { ...publishedPage(), status: '1', currentRevisionId: 60, currentVersionNo: 8 }
  const detail = { ...page, revisions: [{ revisionId: 60, versionNo: 8, status: 'DRAFT' }, { revisionId: 50, versionNo: 7, status: 'PUBLISHED' }] }
  const { h, events } = await harness({ page, published: [], details: [detail] })
  h.getDashboardPage = async () => ({ data: detail })
  const existingShare = { shareId: 90, status: '0' }
  h.listDashboardShares = async () => ({ data: [existingShare] })
  assert.equal(h.canManagePageShares(page), true)
  assert.equal(h.canUsePublishedPage(page), false)
  await h.openShare(page)
  assert.equal(h.shareDialog.open, true)
  assert.equal(h.shareDialog.rows[0], existingShare, '已有链接仍可查看和撤销')
  assert.equal(h.canUsePublishedPage(h.shareDialog.page), false, '生成区在停用时隐藏')
  await h.createShare()
  await h.openRuntime(page)
  await h.openMenuConfig(page)
  assert.equal(events.some(([type]) => type.endsWith('-create') || type === 'navigate'), false)
  assert.equal(h.menuConfigDialog.open, false)
  assert.equal(h.pagePublishedRevision(page).versionNo, 7, '运行版本不得显示草稿 V8')

  const ast = baseParse(pageSfc.descriptor.template.content)
  let createRow
  const visit = (nodes) => {
    for (const node of nodes || []) {
      if (node.type !== 1) continue
      if (node.props.some((prop) => prop.type === 6 && prop.name === 'class' && prop.value?.content === 'share-create-row')) createRow = node
      visit(node.children)
    }
  }
  visit(ast.children)
  const expression = createRow.props.find((prop) => prop.type === 7 && prop.name === 'if')?.exp?.content
  assert.equal(new Function('canUsePublishedPage', 'shareDialog', `return ${expression}`)(h.canUsePublishedPage, h.shareDialog), false)
})

test('停用草稿即使携带历史草稿指针和版本号，也不展示分享或运行版本', async () => {
  const page = { ...publishedPage(), status: '1', currentRevisionStatus: 'DRAFT', revisions: [{ versionNo: 7, status: 'DRAFT' }] }
  const { h, events } = await harness({ page, published: [], details: [page] })
  assert.equal(hasDashboardPublishedVersion(page), false)
  assert.equal(h.canManagePageShares(page), false)
  assert.equal(h.pagePublishedRevision(page), null)
  await h.openShare(page)
  assert.equal(h.shareDialog.open, false)
  assert.equal(events.some(([type]) => type === 'share-list'), false)
})

test('版本列使用实际发布版本，详情按有效运行指针或最新发布版本回退', async () => {
  const page = { ...publishedPage(), currentVersionNo: 10, currentRevisionId: 100 }
  const { h } = await harness({ page })
  assert.equal(h.pagePublishedRevision(page).versionNo, 7, '启用列表的草稿指针服从发布目录')
  const revisions = [
    { revisionId: 60, versionNo: 6, status: 'PUBLISHED' },
    { revisionId: 100, versionNo: 10, status: 'DRAFT' },
    { revisionId: 70, versionNo: 7, status: 'PUBLISHED' },
  ]
  assert.equal(getDashboardPublishedRevision({ ...page, revisions }).versionNo, 7)
  assert.equal(getDashboardPublishedRevision({ ...page, currentRevisionId: 60, revisions }).versionNo, 6)
  assert.equal(revisions[0].versionNo, 6, '查询不得修改原版本数组顺序')
})

test('列表只为当前页的停用记录补取发布详情，正常页面不用额外详情请求', async () => {
  const { h, events } = await harness()
  const active = publishedPage()
  const disabled = { ...publishedPage(), pageId: 500, status: '1' }
  const calls = []
  h.loading = { value: false }
  h.total = { value: 0 }
  h.query = { pageNum: 1, pageSize: 10, keyword: '', folderId: null, includeChildren: false }
  h.selectedPageIds.value = [active.pageId]
  h.listDashboardPages = async () => ({ rows: [active, disabled], total: 2 })
  h.getDashboardPage = async (pageId) => {
    calls.push(pageId)
    return { data: { ...disabled, revisions: [{ revisionId: 22, versionNo: 2, status: 'PUBLISHED' }] } }
  }
  await h.load()
  assert.deepEqual(calls, [500])
  assert.equal(h.canManagePageShares(disabled), true)
  assert.equal(h.canUsePublishedPage(disabled), false)
  assert.equal(h.loading.value, false)
  assert.equal(h.selectedPageIds.value.length, 0, '加载新列表时清空旧页批量选择')
  assert.equal(events.filter(([type]) => type === 'clear-selection').length, 1)
})
