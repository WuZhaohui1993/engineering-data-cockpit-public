// 可在本地 Vite 页面或 Node 中传入已认证的 request 执行；不保存凭证。
// 仅创建 folder-qa-* 临时对象，不请求外部来源、不改已有业务对象。
export async function runDataFolderRegression(request, { keepFixtures = false } = {}) {
  const checks = []
  const suffix = Date.now().toString(36)
  const prefix = `folder-qa-${suffix}`
  const state = { folders: [], datasets: [], sources: [], integrations: [], prefix }
  const assert = (condition, message) => { if (!condition) throw new Error(message) }
  const ok = async (path, method = 'GET', body) => {
    const response = await request(path, method, body)
    assert(response.code === 200, `${method} ${path}: ${response.msg || response.code}`)
    return response
  }
  const data = async (...args) => (await ok(...args)).data
  const deny = async (path, method, body) => {
    const response = await request(path, method, body)
    assert(response.code !== 200, `本应拒绝: ${method} ${path}`)
  }
  const same = (actual, expected, message) => assert(JSON.stringify(actual) === JSON.stringify(expected), message)
  const folder = async (scope, name, parentId = 0) => {
    const value = await data(`/dashboard/data-folder/${scope}`, 'POST', {
      folderCode: `${prefix}-${scope}-${state.folders.length}`, folderName: name, parentId, sortOrder: 2
    })
    assert(value.folderId > 0, '目录创建返回有效编号')
    state.folders.push({ scope, ...value })
    return value
  }
  const list = async (scope, query = {}) => {
    const path = scope === 'dataset' ? '/dashboard/dataset/list' : scope === 'source' ? '/dashboard/source/list' : '/dashboard/integration/list'
    const response = await ok(`${path}?${new URLSearchParams({ pageNum: 1, pageSize: 100, ...query })}`)
    return response.rows || response.data
  }
  const move = (scope, ids, folderId) => ok(`/dashboard/data-folder/${scope}/move`, 'PUT', { ids, folderId })
  const snapshot = (object, fields) => Object.fromEntries(fields.map(key => [key, object[key]]))
  try {
    const trees = {}
    for (const scope of ['dataset', 'source', 'integration']) {
      const parent = await folder(scope, `分类验收-${scope}-${suffix}`)
      const child = await folder(scope, `子目录-${scope}`, parent.folderId)
      trees[scope] = { parent, child }
      const loaded = await data(`/dashboard/data-folder/${scope}/list`)
      assert(loaded.some(item => item.folderId === child.folderId && item.parentId === parent.folderId), '层级目录可读取')
      await deny(`/dashboard/data-folder/${scope}`, 'PUT', { folderId: parent.folderId, parentId: child.folderId })
      await deny(`/dashboard/data-folder/${scope}/${parent.folderId}`, 'DELETE')
      await ok(`/dashboard/data-folder/${scope}`, 'PUT', { folderId: child.folderId, folderName: `子目录-${scope}-已改名`, sortOrder: 7 })
      const updated = (await data(`/dashboard/data-folder/${scope}/list`)).find(item => item.folderId === child.folderId)
      assert(updated.folderName.endsWith('已改名') && updated.sortOrder === 7 && updated.folderCode === child.folderCode, '改名排序不修改稳定编码')
      checks.push(`${scope}：父子目录、改名排序、防循环、非空删除保护`)
    }
    state.trees = trees
    const createDataset = async (name, groupCode) => {
      const result = await data('/dashboard/dataset', 'POST', {
        datasetCode: `${prefix}-${name}`, datasetName: `分类验收数据集-${name}`, groupCode,
        dataType: 'JSON', configJson: JSON.stringify({ mode: 'STATIC', payload: [{ label: '验收', value: 42 }], rowLimit: 100 }),
        fieldSchemaJson: '[{"name":"label","title":"名称","type":"string"},{"name":"value","title":"数值","type":"number"}]',
        paramSchemaJson: '[]', status: 'DRAFT', timeoutSeconds: 10, refreshSeconds: 30
      })
      state.datasets.push(result.datasetId)
      return result
    }
    const dataset = await createDataset('first', trees.dataset.child.groupCode)
    const dataset2 = await createDataset('second', '')
    const tested = await data(`/dashboard/dataset/${dataset.datasetId}/test`, 'POST', {})
    assert(tested.quality === 'SUCCESS', '静态数据集测试成功')
    await ok('/dashboard/dataset', 'PUT', { datasetId: dataset.datasetId, status: 'ACTIVE' })
    const before = await data(`/dashboard/dataset/${dataset.datasetId}`)
    await deny(`/dashboard/data-folder/dataset/${trees.dataset.child.folderId}`, 'DELETE')
    assert((await list('dataset', { folderId: trees.dataset.parent.folderId })).length === 0, '父目录默认精确过滤')
    assert((await list('dataset', { folderId: trees.dataset.parent.folderId, includeChildren: true, dataType: 'JSON', keyword: prefix })).length === 1, '目录后代与类型关键词组合')
    assert((await list('dataset', { folderId: trees.dataset.parent.folderId, includeChildren: true, dataType: 'SQL', keyword: prefix })).length === 0, '类型筛选可叠加')
    await move('dataset', [dataset.datasetId, dataset2.datasetId], trees.dataset.parent.folderId)
    const after = await data(`/dashboard/dataset/${dataset.datasetId}`)
    same(snapshot(after, ['datasetCode', 'configJson', 'fieldSchemaJson', 'paramSchemaJson', 'status', 'lastTestStatus', 'lastTestAt']), snapshot(before, ['datasetCode', 'configJson', 'fieldSchemaJson', 'paramSchemaJson', 'status', 'lastTestStatus', 'lastTestAt']), '移动保留数据契约、状态和最近测试')
    assert(after.groupCode === trees.dataset.parent.groupCode, '批量移动落在指定目录')
    await deny('/dashboard/data-folder/dataset/move', 'PUT', { ids: [dataset.datasetId, 2147483647], folderId: 0 })
    assert((await data(`/dashboard/dataset/${dataset.datasetId}`)).groupCode === trees.dataset.parent.groupCode, '部分对象不存在时整批回滚')
    await move('dataset', [dataset2.datasetId], 0)
    assert((await list('dataset', { folderId: 0, keyword: `${prefix}-second` })).length === 1, '未分类独立于全部')
    checks.push('数据集：创建归档、组合/后代过滤、批量移动、事务回滚、测试状态和引用稳定')

    const source = await data('/dashboard/source', 'POST', {
      sourceCode: `${prefix}-source`, sourceName: '分类验收本地草稿来源', sourceType: 'MYSQL', status: 'DRAFT',
      folderId: trees.source.child.folderId,
      configJson: JSON.stringify({ jdbcUrl: 'jdbc:mysql://127.0.0.1:3306/folder_qa', username: 'folder_qa' })
    })
    state.sources.push(source.dataSourceId)
    await deny(`/dashboard/data-folder/source/${trees.source.child.folderId}`, 'DELETE')
    assert((await list('source', { folderId: trees.source.parent.folderId })).length === 0, '来源精确目录')
    assert((await list('source', { folderId: trees.source.parent.folderId, includeChildren: true, type: 'MYSQL', status: 'DRAFT', keyword: prefix })).length === 1, '来源组合目录')
    const sourceBefore = await data(`/dashboard/source/${encodeURIComponent(source.sourceCode)}/configuration`)
    await move('source', [source.dataSourceId], trees.source.parent.folderId)
    const sourceAfter = await data(`/dashboard/source/${encodeURIComponent(source.sourceCode)}/configuration`)
    same(snapshot(sourceAfter, ['sourceCode', 'configJson', 'status', 'hasSecret']), snapshot(sourceBefore, ['sourceCode', 'configJson', 'status', 'hasSecret']), '移动来源不改连接和状态')
    await deny('/dashboard/data-folder/source/move', 'PUT', { ids: [source.dataSourceId], folderId: trees.integration.parent.folderId })
    await deny('/dashboard/data-folder/source/move', 'PUT', { ids: [source.dataSourceId], folderId: -1 })
    const system = await list('source', { folderId: -1 })
    assert(system.length > 0 && system.every(item => item.systemSource && item.folderId === -1), '内置源独立系统分组')
    checks.push('数据源：独立归档、组合过滤、移动不改配置、跨类型目录拒绝、内置来源保护')

    const integration = await data('/dashboard/integration', 'POST', {
      integrationCode: `${prefix}-inbound`, integrationName: '分类验收推送接入', environment: 'TEST',
      profile: 'EVENT', schemaVersion: '1.0', endpointCode: 'events', networkProfile: 'PRIVATE_LINK',
      timezone: 'Asia/Shanghai', projectScope: ['folder-qa'], allowedIps: ['127.0.0.1/32'], status: 'DRAFT',
      configJson: '{}', folderId: trees.integration.child.folderId
    })
    state.integrations.push(integration.integrationId)
    await deny(`/dashboard/data-folder/integration/${trees.integration.child.folderId}`, 'DELETE')
    assert((await list('integration', { folderId: trees.integration.parent.folderId })).length === 0, '接入精确目录')
    assert((await list('integration', { folderId: trees.integration.parent.folderId, includeChildren: true, environment: 'TEST', status: 'DRAFT', keyword: prefix })).length === 1, '接入组合目录')
    assert((await list('integration', { folderId: trees.integration.parent.folderId, includeChildren: true, environment: 'PRODUCTION', keyword: prefix })).length === 0, '接入环境独立')
    const integrationBefore = await data(`/dashboard/integration/${integration.integrationId}`)
    await move('integration', [integration.integrationId], trees.integration.parent.folderId)
    const integrationAfter = await data(`/dashboard/integration/${integration.integrationId}`)
    same(snapshot(integrationAfter, ['integrationCode', 'configJson', 'status', 'projectScope', 'environment']), snapshot(integrationBefore, ['integrationCode', 'configJson', 'status', 'projectScope', 'environment']), '移动接入不改范围和状态')
    const generated = await data(`/dashboard/integration/operations/inbound/${integration.integrationId}/dataset`, 'POST', {
      datasetCode: `${prefix}-generated`, datasetName: '分类验收接入生成数据集', projectCode: 'folder-qa',
      groupCode: trees.dataset.child.groupCode, realtime: false, fields: []
    })
    state.datasets.push(generated.datasetId)
    assert(generated.groupCode === trees.dataset.child.groupCode, '接入生成数据集归入独立目标目录')
    await deny(`/dashboard/integration/operations/inbound/${integration.integrationId}/dataset`, 'POST', {
      datasetCode: `${prefix}-denied`, datasetName: '不应创建', projectCode: 'outside-scope', groupCode: trees.dataset.child.groupCode
    })
    checks.push('推送接入：独立归档、环境组合、移动保留范围、生成数据集目标目录、范围校验不变')
    for (const scope of ['dataset', 'source', 'integration']) {
      await deny(`/dashboard/data-folder/${scope}/${trees[scope].parent.folderId}`, 'DELETE')
      await deny(`/dashboard/data-folder/${scope}/move`, 'PUT', { ids: [], folderId: 0 })
    }
    await deny('/dashboard/data-folder/unknown/list', 'GET')
    checks.push('非法类型、空批量和非空目录均被拒绝')
    state.checks = checks
    if (keepFixtures) return { passed: true, checks, state, cleaned: false }
  } finally {
    if (!keepFixtures || !state.checks) await cleanupDataFolderFixtures(request, state)
  }
  return { passed: true, checks, cleaned: true }
}

export async function cleanupDataFolderFixtures(request, state) {
  const failures = []
  const remove = async path => {
    const response = await request(path, 'DELETE')
    if (response.code !== 200) failures.push(`${path}: ${response.msg || response.code}`)
  }
  for (const id of [...state.datasets].reverse()) await remove(`/dashboard/dataset/${id}`)
  for (const id of [...state.sources].reverse()) await remove(`/dashboard/source/${id}`)
  for (const id of [...state.integrations].reverse()) await remove(`/dashboard/integration/${id}`)
  for (const folder of [...state.folders].reverse()) await remove(`/dashboard/data-folder/${folder.scope}/${folder.folderId}`)
  if (failures.length) throw new Error(`临时数据清理未完成: ${failures.join('; ')}`)
  return { cleaned: true }
}

// request 的第 4 个参数为可选会话覆盖值，空字符串表示匿名；不输出任何会话内容。
export async function runDataFolderPermissionRegression(request) {
  // 登录用户名上限20字符，比管理表单的长度限制更严格。
  const prefix = `fq-${Date.now().toString(36)}`
  const users = [], roles = [], folders = [], checks = [], sessions = []
  const requireOk = response => {
    if (response.code !== 200) throw new Error(`权限回归请求失败: ${response.msg || response.code}`)
    return response
  }
  const menuList = requireOk(await request('/system/menu/list')).data
  const roleSession = async (label, permissions) => {
    const name = `${prefix}-${label}`
    const menuIds = permissions.map(permission => {
      const menu = menuList.find(item => item.perms === permission)
      if (!menu) throw new Error(`权限菜单不存在: ${permission}`)
      return menu.menuId
    })
    requireOk(await request('/system/role', 'POST', {
      roleName: name, roleKey: name, roleSort: 99, status: '0', dataScope: '1',
      menuCheckStrictly: true, deptCheckStrictly: true, menuIds, deptIds: []
    }))
    const role = requireOk(await request(`/system/role/list?roleName=${name}`)).rows.find(item => item.roleKey === name)
    roles.push({ id: role.roleId, name })
    const password = `Qa!${crypto.randomUUID().replaceAll('-', '').slice(0, 16)}`
    requireOk(await request('/system/user', 'POST', { userName: name, nickName: '文件夹权限回归', password, status: '0', sex: '0', deptId: 103, roleIds: [role.roleId], postIds: [] }))
    const user = requireOk(await request(`/system/user/list?userName=${name}`)).rows.find(item => item.userName === name)
    users.push({ id: user.userId, name })
    const token = requireOk(await request('/login', 'POST', { username: name, password, code: '', uuid: '' }, '')).token
    sessions.push(token)
    return token
  }
  const forbidden = async (path, method, body, token) => {
    const result = await request(path, method, body, token)
    if (result.code !== 403) throw new Error(`应返回403: ${method} ${path}，实际${result.code}`)
  }
  try {
    const reader = await roleSession('reader', ['dashboard:dataset:list', 'dashboard:integration:list'])
    for (const scope of ['dataset', 'source', 'integration']) {
      requireOk(await request(`/dashboard/data-folder/${scope}/list`, 'GET', undefined, reader))
      await forbidden(`/dashboard/data-folder/${scope}`, 'POST', { folderName: '禁止创建' }, reader)
      await forbidden(`/dashboard/data-folder/${scope}/move`, 'PUT', { ids: [1], folderId: 0 }, reader)
    }
    checks.push('只读角色可读取目录，三类目录创建与移动均403')
    const datasetEditor = await roleSession('editor', ['dashboard:dataset:edit'])
    for (const scope of ['dataset', 'source']) {
      requireOk(await request(`/dashboard/data-folder/${scope}/list`, 'GET', undefined, datasetEditor))
      const f = requireOk(await request(`/dashboard/data-folder/${scope}`, 'POST', { folderName: `${prefix}-${scope}` }, datasetEditor)).data
      folders.push({ scope, folderId: f.folderId })
    }
    await forbidden('/dashboard/data-folder/integration/list', 'GET', undefined, datasetEditor)
    await forbidden('/dashboard/data-folder/integration', 'POST', { folderName: '禁止创建' }, datasetEditor)
    checks.push('数据集编辑角色可管理数据集/来源目录，无法访问接入目录')
    const integrationEditor = await roleSession('inbound', ['dashboard:integration:edit'])
    requireOk(await request('/dashboard/data-folder/integration/list', 'GET', undefined, integrationEditor))
    const f = requireOk(await request('/dashboard/data-folder/integration', 'POST', { folderName: `${prefix}-inbound` }, integrationEditor)).data
    folders.push({ scope: 'integration', folderId: f.folderId })
    await forbidden(`/dashboard/data-folder/integration/${f.folderId}`, 'DELETE', undefined, integrationEditor)
    await forbidden('/dashboard/data-folder/source/list', 'GET', undefined, integrationEditor)
    checks.push('接入编辑角色可管理接入目录，删除仍需独立权限，不能访问来源目录')
  } finally {
    for (const session of sessions) requireOk(await request('/logout', 'POST', undefined, session))
    for (const folder of folders.reverse()) requireOk(await request(`/dashboard/data-folder/${folder.scope}/${folder.folderId}`, 'DELETE'))
    for (const user of users) requireOk(await request(`/system/user/${user.id}`, 'DELETE'))
    for (const role of roles) requireOk(await request(`/system/role/${role.id}`, 'DELETE'))
  }
  return { passed: true, checks, logicallyCleaned: true, cleanupIdentities: { users, roles } }
}

export async function setupDataFolderBrowserFixtures(request) {
  const state = { folders: [], datasets: [], sources: [], integrations: [], prefix: `folder-ui-${Date.now().toString(36)}`, trees: {} }
  const create = async (path, body) => {
    const result = await request(path, 'POST', body)
    if (result.code !== 200) throw new Error(`${path}: ${result.msg}`)
    return result.data
  }
  for (const scope of ['dataset', 'source', 'integration']) {
    const label = { dataset: '数据集', source: '数据源', integration: '推送接入' }[scope]
    const parent = await create(`/dashboard/data-folder/${scope}`, { folderName: `界面验收-${label}`, folderCode: `${state.prefix}-${scope}` })
    state.folders.push({ ...parent, scope })
    const child = await create(`/dashboard/data-folder/${scope}`, { folderName: `验收子目录-${label}`, parentId: parent.folderId })
    state.folders.push({ ...child, scope })
    state.trees[scope] = { parent, child }
  }
  const dataset = await create('/dashboard/dataset', { datasetCode: `${state.prefix}-data`, datasetName: '文件夹界面验收数据集', groupCode: state.trees.dataset.child.groupCode, dataType: 'JSON', configJson: '{"mode":"STATIC","payload":[{"label":"验收","value":42}],"rowLimit":100}', fieldSchemaJson: '[]', paramSchemaJson: '[]', status: 'DRAFT', timeoutSeconds: 10, refreshSeconds: 30 })
  state.datasets.push(dataset.datasetId)
  const source = await create('/dashboard/source', { sourceCode: `${state.prefix}-source`, sourceName: '文件夹界面验收来源', sourceType: 'MYSQL', folderId: state.trees.source.child.folderId, status: 'DRAFT', configJson: '{"jdbcUrl":"jdbc:mysql://127.0.0.1:3306/folder_qa","username":"folder_qa"}' })
  state.sources.push(source.dataSourceId)
  const integration = await create('/dashboard/integration', { integrationCode: `${state.prefix}-inbound`, integrationName: '文件夹界面验收接入方', folderId: state.trees.integration.child.folderId, environment: 'TEST', profile: 'EVENT', schemaVersion: '1.0', endpointCode: 'events', networkProfile: 'PRIVATE_LINK', timezone: 'Asia/Shanghai', projectScope: [], allowedIps: ['127.0.0.1/32'], status: 'DRAFT', configJson: '{}' })
  state.integrations.push(integration.integrationId)
  return state
}
