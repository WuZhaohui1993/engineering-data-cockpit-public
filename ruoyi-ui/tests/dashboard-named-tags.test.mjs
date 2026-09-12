import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createPinia, defineStore } from 'pinia'

const storeSource = readFileSync(new URL('../src/store/modules/tagsView.js', import.meta.url), 'utf8')
const clone = (value) => JSON.parse(JSON.stringify(value))

// Execute the actual store actions with Pinia. Only the environment-dependent
// cache/settings imports are replaced; title and route logic remain production code.
function createHarness({ persist = false, persisted = [] } = {}) {
  const storage = new Map([['tags-view-visited', clone(persisted)]])
  const writes = []
  const settings = { tagsViewPersist: persist }
  const cache = {
    local: {
      getJSON: (key) => clone(storage.get(key) ?? null),
      setJSON(key, value) {
        storage.set(key, clone(value))
        writes.push({ key, value: clone(value) })
      },
      remove: (key) => storage.delete(key),
    },
  }
  const loadStore = new Function('defineStore', 'cache', 'useSettingsStore',
    storeSource
      .replace(/^import\s+[^\n]+\n/gm, '')
      .replace(/export default useTagsViewStore\s*$/, 'return useTagsViewStore'),
  )
  const useStore = loadStore(defineStore, cache, () => settings)
  return { store: useStore(createPinia()), storage, writes, settings }
}

function pageRoute(pageId, mode = 'runtime', query = {}) {
  const path = `/dashboard/${mode}/${pageId}`
  const params = new URLSearchParams(query).toString()
  return {
    path,
    fullPath: path + (params ? `?${params}` : ''),
    name: mode === 'designer' ? 'DashboardDesigner' : 'DashboardRuntime',
    params: { pageId: String(pageId) },
    query: { ...query },
    meta: { title: mode === 'designer' ? '大屏设计' : '大屏运行', activeMenu: '/dashboard/page' },
  }
}

const titleAt = (store, path) => store.visitedViews.find((view) => view.path === path)?.title
const viewAt = (store, path) => store.visitedViews.find((view) => view.path === path)
const namePage = (store, route, pageName, mode) => store.updateDashboardPageTitle({ path: route.path, pageName, mode })

test('两页设计和运行标签按路径命名，共用路由名称也不会互相覆盖', () => {
  const { store } = createHarness()
  const routes = [pageRoute(392, 'designer'), pageRoute(393, 'designer'), pageRoute(392), pageRoute(393)]
  const originalRoutes = clone(routes)
  for (const route of routes) store.addView(route)
  namePage(store, routes[0], '项目概况', '设计')
  namePage(store, routes[1], '进度管理', '设计')
  namePage(store, routes[2], '项目概况', '运行')
  namePage(store, routes[3], '进度管理', '运行')

  assert.deepEqual(store.visitedViews.map((view) => view.title), ['项目概况 · 设计', '进度管理 · 设计', '项目概况 · 运行', '进度管理 · 运行'])
  assert.deepEqual(routes, originalRoutes, '命名不得修改 Vue Router 的 meta 或路由记录')
  assert.equal(viewAt(store, routes[0].path).meta.dashboardPageName, '项目概况')
  assert.equal(viewAt(store, routes[0].path).meta.dashboardPageMode, '设计')
  assert.equal(viewAt(store, routes[3].path).meta.activeMenu, '/dashboard/page')
})

test('运行标签仅更新相同路径，保留其他页签和已有缓存顺序', () => {
  const { store } = createHarness()
  const homepage = { path: '/index', fullPath: '/index', name: 'Index', query: {}, meta: { title: '首页', affix: true } }
  const first = pageRoute(392)
  const second = pageRoute(393)
  const designer = pageRoute(392, 'designer')
  for (const route of [homepage, first, designer, second]) store.addView(route)
  namePage(store, first, '项目概况', '预览')
  namePage(store, second, '进度管理', '运行')
  const paths = store.visitedViews.map((view) => view.path)
  const cached = [...store.cachedViews]
  store.iframeViews = [{ path: '/external-dashboard', name: 'External', meta: { link: 'https://example.test/' } }]

  store.setDashboardRuntimeView(pageRoute(392, 'runtime', { preview: '1', runtimePageCode: 'xinghua-progress' }))

  assert.deepEqual(store.visitedViews.map((view) => view.path), paths)
  assert.deepEqual([...store.cachedViews], cached)
  assert.equal(store.iframeViews.length, 1)
  assert.equal(titleAt(store, first.path), '项目概况 · 预览')
  assert.equal(titleAt(store, second.path), '进度管理 · 运行')
  assert.equal(viewAt(store, first.path).meta.dashboardPageName, '项目概况')
  assert.equal(viewAt(store, first.path).query.runtimePageCode, 'xinghua-progress')
})

test('预览内部切页再返回只复用当前路径，普通 query 更新保留命名信息', () => {
  const { store } = createHarness()
  const route = pageRoute(392, 'runtime', { preview: '1', fullscreen: '1' })
  store.setDashboardRuntimeView(route)
  namePage(store, route, '项目概况', '预览')
  for (const pageCode of ['xinghua-progress', 'xinghua-overview']) {
    const target = pageRoute(392, 'runtime', { preview: '1', fullscreen: '1', runtimePageCode: pageCode })
    store.setDashboardRuntimeView(target)
    store.updateVisitedView(target)
    assert.equal(store.visitedViews.length, 1)
    assert.equal(titleAt(store, route.path), '项目概况 · 预览')
    assert.equal(viewAt(store, route.path).meta.dashboardPageMode, '预览')
    assert.equal(viewAt(store, route.path).query.runtimePageCode, pageCode)
    assert.equal(viewAt(store, route.path).query.fullscreen, '1')
  }
})

test('同名页面和重命名仅影响指定路径，不按标题或组件名批量重命名', () => {
  const { store } = createHarness()
  const first = pageRoute(392)
  const second = pageRoute(393)
  store.setDashboardRuntimeView(first)
  store.setDashboardRuntimeView(second)
  namePage(store, first, '项目概况', '运行')
  namePage(store, second, '项目概况', '运行')
  namePage(store, second, '进度管理（新版）', '运行')
  assert.equal(store.visitedViews.length, 2)
  assert.equal(titleAt(store, first.path), '项目概况 · 运行')
  assert.equal(titleAt(store, second.path), '进度管理（新版） · 运行')
  namePage(store, pageRoute(999), '尚未打开的页面', '运行')
  assert.equal(store.visitedViews.length, 2, '异步加载结束时不得重新打开已关闭的页签')
})

test('菜单运行标签在内部切页时保留菜单标题和 activeMenu', () => {
  const { store } = createHarness()
  const menu = {
    path: '/dp/runtime/code/xinghua-overview',
    fullPath: '/dp/runtime/code/xinghua-overview',
    name: 'DashboardMenu392',
    query: { dashboardPageCode: 'xinghua-overview' },
    meta: { title: '项目概况', dashboardRuntime: true, activeMenu: '/dp/runtime/code/xinghua-overview' },
  }
  const management = pageRoute(393)
  store.setDashboardRuntimeView(menu)
  store.setDashboardRuntimeView(management)
  namePage(store, management, '进度管理', '运行')
  store.setDashboardRuntimeView({ ...menu, query: { ...menu.query, runtimePageCode: 'xinghua-progress' } })
  assert.equal(titleAt(store, menu.path), '项目概况')
  assert.equal(viewAt(store, menu.path).meta.activeMenu, menu.path)
  assert.equal(titleAt(store, management.path), '进度管理 · 运行')
  assert.equal(store.visitedViews.length, 2)
})

test('启用持久化时保存并恢复页面标题、模式和内部导航 query', () => {
  const { store, storage, writes } = createHarness({ persist: true })
  const route = pageRoute(392, 'runtime', { preview: '1' })
  store.addView(route)
  namePage(store, route, '项目概况', '预览')
  store.updateVisitedView(pageRoute(392, 'runtime', { preview: '1', runtimePageCode: 'xinghua-progress' }))
  assert.ok(writes.length >= 2)
  const persisted = storage.get('tags-view-visited')
  assert.equal(persisted[0].title, '项目概况 · 预览')
  assert.equal(persisted[0].meta.dashboardPageName, '项目概况')
  assert.equal(persisted[0].meta.dashboardPageMode, '预览')
  assert.equal(persisted[0].query.runtimePageCode, 'xinghua-progress')
  const restored = createHarness({ persist: true, persisted }).store
  restored.loadPersistedViews()
  assert.equal(titleAt(restored, route.path), '项目概况 · 预览')
  assert.equal(viewAt(restored, route.path).meta.dashboardPageName, '项目概况')
})

test('未开启持久化时命名更新不写入本地存储', () => {
  const { store, writes } = createHarness()
  const route = pageRoute(392, 'designer')
  store.addView(route)
  namePage(store, route, '项目概况', '设计')
  assert.equal(titleAt(store, route.path), '项目概况 · 设计')
  assert.equal(writes.length, 0)
})

test('设计器、运行页和标签组件不再调用全量改名或删除运行页签的旧接口', () => {
  for (const file of ['../src/views/dashboard/designer/index.vue', '../src/views/dashboard/runtime/index.vue', '../src/layout/components/TagsView/index.vue']) {
    const source = readFileSync(new URL(file, import.meta.url), 'utf8')
    assert.doesNotMatch(source, /\.(?:updateDashboardRuntimeTitle|delDashboardRuntimeViews)\s*\(/, file)
  }
})
