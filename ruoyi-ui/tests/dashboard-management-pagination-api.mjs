// 本地开发回归；调用方提供认证request，不保存凭证，不访问外部服务。
export async function setupManagementFixtures(request) {
  const state = { prefix: `management-qa-${Date.now().toString(36)}`, folders: [], pages: [], assets: [], maps: [], sources: [], integrations: [], endpoints: [] }
  const call = async (path, method = 'GET', body) => {
    const result = await request(path, method, body)
    if (result.code !== 200) throw new Error(`${method} ${path}: ${result.msg || result.code}`)
    return result
  }
  const create = async (path, body) => (await call(path, 'POST', body)).data
  try {
    state.trees = {}
    for (const scope of ['page', 'resource', 'source', 'integration']) {
      const label = { page: '页面', resource: '资源', source: '数据源', integration: '接入' }[scope]
      const parent = await create(`/dashboard/data-folder/${scope}`, { folderCode: `${state.prefix}-${scope}`, folderName: `验收-${label}` })
      state.folders.push({ scope, ...parent })
      const child = await create(`/dashboard/data-folder/${scope}`, { parentId: parent.folderId, folderName: `验收子目录-${label}` })
      state.folders.push({ scope, ...child })
      state.trees[scope] = { parent, child }
    }
    const page = await create('/dashboard/page', { pageCode: `${state.prefix}-page`, pageName: '分类与分页验收页面', folderId: state.trees.page.child.folderId })
    state.pages.push(page.pageId)
    await call(`/dashboard/page/${page.pageId}/publish`, 'POST', { publishNote: '本地目录元数据保持验证' })
    const oldAsset = (await call('/dashboard/asset/page?assetType=IMAGE&pageNum=1&pageSize=1')).rows[0]
    if (!oldAsset) throw new Error('本地需要一个已有图片引用用于资源元数据回归')
    const asset = await create('/dashboard/asset', { assetCode: `${state.prefix}-asset`, assetName: '分类与分页验收图片', assetType: 'IMAGE', resourcePath: oldAsset.resourcePath, folderId: state.trees.resource.child.folderId, status: '0' })
    state.assets.push(asset.assetId)
    const map = await create('/dashboard/map', { mapCode: `${state.prefix}-map`, mapName: '分类与分页验收地图', folderId: state.trees.resource.child.folderId, geojsonJson: '{"type":"FeatureCollection","features":[]}', status: '0' })
    state.maps.push(map.mapId)
    for (let index = 0; index < 23; index++) {
      const source = await create('/dashboard/source', {
        sourceCode: `${state.prefix}-source-${index}`, sourceName: `分页验收来源${index}`, sourceType: index === 0 ? 'HTTP' : 'MYSQL',
        folderId: state.trees.source.child.folderId, status: 'DRAFT',
        configJson: JSON.stringify(index === 0 ? {
          baseUrl: 'https://example.com', path: '/', method: 'GET', networkProfile: 'PUBLIC_HTTPS', allowedHosts: ['example.com'], allowedPorts: [443], authProvider: 'NO_AUTH', environment: 'TEST'
        } : { jdbcUrl: 'jdbc:mysql://127.0.0.1:3306/management_qa', username: 'management_qa' })
      })
      state.sources.push(source.dataSourceId)
      if (index === 0) state.httpSourceCode = source.sourceCode
      const integration = await create('/dashboard/integration', {
        integrationCode: `${state.prefix}-in-${index}`, integrationName: `分页验收接入${index}`, folderId: state.trees.integration.child.folderId,
        status: 'DRAFT', environment: index % 2 ? 'SANDBOX' : 'TEST', profile: 'EVENT', schemaVersion: '1.0', endpointCode: 'events', networkProfile: 'PRIVATE_LINK', allowedIps: ['127.0.0.1/32'], configJson: '{}'
      })
      state.integrations.push(integration.integrationId)
    }
    for (let index = 0; index < 23; index++) {
      const endpoint = await create(`/dashboard/integration/endpoints/${state.httpSourceCode}`, { endpointCode: `page-qa-${index}`, endpointName: `分页验收接口${index}`, path: `/fixture/${index}`, method: 'GET', requestContentType: 'NONE', responseType: 'JSON', authProvider: 'INHERIT', status: 'DRAFT', configJson: '{}' })
      state.endpoints.push(endpoint.endpointId)
    }
    return state
  } catch (error) {
    await cleanupManagementFixtures(request, state)
    throw error
  }
}

export async function verifyManagementFixtures(request, state) {
  const checks = []
  const assert = (value, message) => { if (!value) throw new Error(message) }
  const call = async (path, method = 'GET', body) => {
    const result = await request(path, method, body)
    assert(result.code === 200, `${path}: ${result.msg || result.code}`)
    return result
  }
  const reject = async (path, method, body) => {
    const result = await request(path, method, body)
    assert([400, 403, 500].includes(result.code), `应明确拒绝此操作: ${path}`)
  }
  const canonical = value => Array.isArray(value) ? value.map(canonical) : value && typeof value === 'object' ? Object.fromEntries(Object.entries(value).sort(([a], [b]) => a.localeCompare(b)).map(([key, item]) => [key, canonical(item)])) : value
  const withoutFolder = object => JSON.stringify(canonical(Object.fromEntries(Object.entries(object).filter(([key]) => !['folderId', 'folderName', 'updateTime', 'updateBy'].includes(key)))))
  for (const [path, idKey, expected] of [
    ['/dashboard/source/page', 'code', 23],
    ['/dashboard/integration/page', 'integrationId', 23],
    [`/dashboard/integration/endpoints/${state.httpSourceCode}/page`, 'endpointId', 23],
  ]) {
    const query = new URLSearchParams({ keyword: path.includes('/endpoints/') ? '分页验收接口' : state.prefix, pageSize: 20 })
    const first = await call(`${path}?${query}&pageNum=1`)
    const second = await call(`${path}?${query}&pageNum=2`)
    assert(first.total === expected && second.total === expected && first.rows.length === 20 && second.rows.length === 3, `${path} 两页总数/条数正确`)
    assert(new Set([...first.rows, ...second.rows].map(row => row[idKey])).size === expected, `${path} 不重复不遗漏`)
    const beyond = await call(`${path}?${query}&pageNum=999`)
    assert(beyond.total === expected && beyond.rows.length === 0, `${path} 超页保留真实总数`)
    checks.push(`${path}：20+3分两页、总数准确、无重复遗漏、超页正确`)
  }
  const allSources = (await call('/dashboard/source/list')).data
  const combined = []
  for (let page = 1; page <= Math.ceil(allSources.length / 2); page++) combined.push(...(await call(`/dashboard/source/page?pageNum=${page}&pageSize=2`)).rows)
  assert(JSON.stringify(combined.map(row => row.code)) === JSON.stringify(allSources.map(row => row.code)), '系统来源与登记来源跨页顺序一致')
  assert((await call('/dashboard/source/page?folderId=-1&pageSize=1&pageNum=2')).rows[0].systemSource, '系统来源可跨页')
  assert((await call(`/dashboard/integration/list?keyword=${state.prefix}`)).data.length === 23, '接入完整下拉不被分页截断')
  assert((await call(`/dashboard/integration/endpoints/${state.httpSourceCode}`)).data.length === 23, '接口完整下拉不被分页截断')
  checks.push('完整下拉兼容，内置来源与登记来源跨页顺序保持一致')

  for (const [path, scope] of [['/dashboard/page/list', 'page'], ['/dashboard/asset/page', 'resource'], ['/dashboard/map/page', 'resource']]) {
    const { parent } = state.trees[scope]
    const query = new URLSearchParams({ folderId: parent.folderId, keyword: state.prefix, pageNum: 1, pageSize: 10 })
    assert((await call(`${path}?${query}`)).total === 0, `${path}精确目录不包含后代`)
    assert((await call(`${path}?${query}&includeChildren=true`)).total === 1, `${path}包含后代仍准确分页`)
  }
  const pageBefore = (await call(`/dashboard/page/${state.pages[0]}`)).data
  await call('/dashboard/data-folder/page/move', 'PUT', { ids: state.pages, folderId: state.trees.page.parent.folderId })
  const pageAfter = (await call(`/dashboard/page/${state.pages[0]}`)).data
  assert(withoutFolder(pageBefore) === withoutFolder(pageAfter), '页面移动不改变状态、发布版本、草稿和页面内容')
  assert(pageAfter.folderId === state.trees.page.parent.folderId, '页面移动归属正确')
  await reject(`/dashboard/data-folder/page/${state.trees.page.parent.folderId}`, 'DELETE')
  await reject('/dashboard/data-folder/page', 'PUT', { folderId: state.trees.page.parent.folderId, parentId: state.trees.page.child.folderId })
  await reject('/dashboard/data-folder/page/move', 'PUT', { ids: [state.pages[0], 2147483647], folderId: 0 })
  assert((await call(`/dashboard/page/${state.pages[0]}`)).data.folderId === state.trees.page.parent.folderId, '页面批移任一对象无效时无部分修改')
  checks.push('页面目录后代查询、原子移动、发布版本/配置保持、防循环与非空保护')

  const assetBefore = (await call(`/dashboard/asset/page?keyword=${state.prefix}-asset`)).rows[0]
  const mapBefore = (await call(`/dashboard/map/${state.maps[0]}`)).data
  await call('/dashboard/data-folder/resource/move', 'PUT', { ids: state.assets, folderId: state.trees.resource.parent.folderId, resourceType: 'asset' })
  await call('/dashboard/data-folder/resource/move', 'PUT', { ids: state.maps, folderId: state.trees.resource.parent.folderId, resourceType: 'map' })
  const assetAfter = (await call(`/dashboard/asset/page?keyword=${state.prefix}-asset`)).rows[0]
  const mapAfter = (await call(`/dashboard/map/${state.maps[0]}`)).data
  assert(withoutFolder(assetBefore) === withoutFolder(assetAfter), '资源移动不改变文件路径、编码和状态')
  assert(withoutFolder(mapBefore) === withoutFolder(mapAfter), '地图移动不改变GeoJSON、编码和状态')
  await reject('/dashboard/data-folder/resource/move', 'PUT', { ids: [0], folderId: 0, resourceType: 'map' })
  await reject('/dashboard/data-folder/resource/move', 'PUT', { ids: state.assets, folderId: 0, resourceType: 'unknown' })
  await reject(`/dashboard/data-folder/resource/${state.trees.resource.parent.folderId}`, 'DELETE')
  checks.push('资源共用目录，图片/地图分别移动且内容不变，内置地图和非法类型拒绝')
  for (const path of ['/dashboard/source/page', '/dashboard/integration/page', `/dashboard/integration/endpoints/${state.httpSourceCode}/page`, '/dashboard/integration/batches/page', '/dashboard/integration/dead-letters/page']) {
    await reject(`${path}?pageNum=0&pageSize=20`, 'GET')
    await reject(`${path}?pageNum=1&pageSize=10001`, 'GET')
  }
  checks.push('所有新增分页接口校验页码和每页上限')
  return { passed: true, checks }
}

export async function cleanupManagementFixtures(request, state) {
  const failures = []
  const remove = async path => {
    const response = await request(path, 'DELETE')
    if (response.code !== 200) failures.push(path)
  }
  for (const id of [...state.endpoints].reverse()) await remove(`/dashboard/integration/endpoints/item/${id}`)
  for (const id of [...state.sources].reverse()) await remove(`/dashboard/source/${id}`)
  for (const id of [...state.integrations].reverse()) await remove(`/dashboard/integration/${id}`)
  for (const id of [...state.assets].reverse()) await remove(`/dashboard/asset/${id}`)
  for (const id of [...state.maps].reverse()) await remove(`/dashboard/map/${id}`)
  for (const id of [...state.pages].reverse()) { await remove(`/dashboard/page/${id}`); await remove(`/dashboard/page/${id}/purge`) }
  for (const folder of [...state.folders].reverse()) await remove(`/dashboard/data-folder/${folder.scope}/${folder.folderId}`)
  if (failures.length) throw new Error(`临时对象清理未完成: ${failures.join(', ')}`)
  return { cleaned: true }
}
