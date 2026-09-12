import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import { createFunctionHarness, readDashboardSfc } from './helpers/dashboard-property-audit.mjs'

const sfc = await readDashboardSfc(new URL('../src/views/dashboard/resource/index.vue', import.meta.url).pathname)
const clone = value => JSON.parse(JSON.stringify(value))
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done }); return { promise, resolve } }

async function harness() {
  const requests = []
  const clearedSelections = []
  const request = (kind, query) => {
    const task = deferred()
    requests.push({ kind, query: clone(query), task })
    return task.promise
  }
  const h = await createFunctionHarness(sfc, {
    assets: { value: [] }, maps: { value: [] }, assetTotal: { value: 0 }, mapTotal: { value: 0 },
    assetLoading: { value: false }, mapLoading: { value: false },
    selectedAssetIds: { value: [900] }, selectedMapIds: { value: [901] },
    assetTableRef: { value: { clearSelection: () => clearedSelections.push('assets') } },
    mapTableRef: { value: { clearSelection: () => clearedSelections.push('maps') } },
    assetQuery: { pageNum: 3, pageSize: 10, keyword: '', assetType: '', folderId: null, includeChildren: false },
    mapQuery: { pageNum: 3, pageSize: 10, keyword: '', folderId: null, includeChildren: false },
    activeTab: { value: 'assets' },
    pageDashboardAssets: query => request('assets', query), pageDashboardMaps: query => request('maps', query),
    nextTick: callback => Promise.resolve().then(callback),
  })
  return { h, requests, clearedSelections }
}

test('图库和地图管理请求真实页码，显示服务端总数，不在浏览器切片', async () => {
  const { h, requests, clearedSelections } = await harness()
  const assets = h.loadAssets(), maps = h.loadMaps()
  assert.deepEqual(requests.map(item => [item.kind, item.query.pageNum, item.query.pageSize]), [['assets', 3, 10], ['maps', 3, 10]])
  requests[0].task.resolve({ rows: [{ assetId: 1 }], total: 21 })
  requests[1].task.resolve({ rows: [{ mapCode: 'map-1' }, { mapCode: 'map-2' }], total: 22 })
  await Promise.all([assets, maps])
  assert.equal(h.assetTotal.value, 21)
  assert.equal(h.mapTotal.value, 22)
  assert.equal(h.assets.value.length, 1)
  assert.equal(h.maps.value.length, 2)
  assert.equal(h.selectedAssetIds.value.length, 0)
  assert.equal(h.selectedMapIds.value.length, 0)
  assert.deepEqual(clearedSelections, ['assets', 'maps'], '切换列表同步清理表格复选状态')
})

test('搜索及文件夹筛选回第一页，资源类型和目录参数完整传给服务端', async () => {
  const { h, requests } = await harness()
  h.assetQuery.keyword = '标题'
  const query = h.queryAssets()
  assert.equal(requests[0].query.pageNum, 1)
  assert.equal(requests[0].query.keyword, '标题')
  requests[0].task.resolve({ rows: [], total: 0 }); await query
  // 类型下拉和共用目录树先通过 v-model 更新各自字段，再触发查询。
  h.assetQuery.assetType = 'VIDEO'
  const typeQuery = h.queryAssets()
  assert.deepEqual(requests[1].query, { pageNum: 1, pageSize: 10, keyword: '标题', assetType: 'VIDEO', folderId: null, includeChildren: false })
  requests[1].task.resolve({ rows: [], total: 0 }); await typeQuery
  h.assetQuery.folderId = 7
  h.assetQuery.includeChildren = true
  const folderQuery = h.handleResourceFilter()
  assert.deepEqual(requests[2].query, { pageNum: 1, pageSize: 10, keyword: '标题', assetType: 'VIDEO', folderId: 7, includeChildren: true }, '文件夹与资源类型、关键词叠加')
  requests[2].task.resolve({ rows: [], total: 0 }); await folderQuery
  h.activeTab.value = 'maps'
  h.mapQuery.folderId = 7
  const mapQuery = h.handleResourceFilter()
  assert.equal(h.activeTab.value, 'maps')
  assert.equal(requests[3].query.pageNum, 1)
  assert.equal(requests[3].query.folderId, 7)
  assert.equal(h.assetQuery.assetType, 'VIDEO', '地图筛选不清空图库筛选')
  requests[3].task.resolve({ rows: [], total: 0 }); await mapQuery
})

test('删除末页最后一条后回到有效页，不留下空的失效页码', async () => {
  const { h, requests } = await harness()
  const pending = h.loadAssets()
  requests[0].task.resolve({ rows: [], total: 20 })
  await Promise.resolve(); await Promise.resolve()
  assert.equal(h.assetQuery.pageNum, 2)
  assert.equal(requests[1].query.pageNum, 2)
  assert.equal(h.assetLoading.value, true)
  requests[1].task.resolve({ rows: [{ assetId: 12 }], total: 20 })
  await pending
  assert.equal(h.assets.value[0].assetId, 12)
  assert.equal(h.assetLoading.value, false)
})

test('快速切页后迟到的旧结果不能覆盖新页的数据和总数', async () => {
  const { h, requests } = await harness()
  const old = h.loadMaps()
  h.mapQuery.pageNum = 1
  h.mapQuery.keyword = '内置'
  const latest = h.loadMaps()
  requests[1].task.resolve({ rows: [{ mapCode: 'demo-region' }], total: 1 })
  await latest
  requests[0].task.resolve({ rows: [{ mapCode: 'map-older' }], total: 55 })
  await old
  assert.equal(h.mapTotal.value, 1)
  assert.equal(h.mapQuery.pageNum, 1)
  assert.equal(h.maps.value[0].mapCode, 'demo-region')
})

test('管理分页与设计器完整资源目录使用独立接口，重复 JSON 管理入口已移除', async () => {
  const api = await fs.readFile(new URL('../src/api/dashboard.js', import.meta.url), 'utf8')
  assert.match(api, /function listDashboardAssets\([^]*?url: '\/dashboard\/asset\/list'/)
  assert.match(api, /function listDashboardMaps\([^]*?url: '\/dashboard\/map\/list'/)
  assert.match(api, /function pageDashboardAssets\([^]*?url: '\/dashboard\/asset\/page'/)
  assert.match(api, /function pageDashboardMaps\([^]*?url: '\/dashboard\/map\/page'/)
  assert.equal((sfc.descriptor.template.content.match(/<pagination\b/g) || []).length, 2)
  assert.doesNotMatch(sfc.source, /JsonResourceManager|json-resource-manager|name="json"/)
})
