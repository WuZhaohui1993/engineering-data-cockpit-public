// 浏览器可直接 import。管理员 request 由调用方提供；本模块不保存或返回凭证。
// 仅使用 state 中可核对编码前缀的临时业务对象，并将其移动到原所在目录。
export async function runFolderAccessRegression(request, state) {
  const checks = [], users = [], roles = [], sessions = [], folders = []
  const random = crypto.randomUUID().replaceAll('-', '').slice(0, 10)
  const identityPrefix = `fa-${random}`
  const fixturePrefix = String(state?.fixturePrefix || state?.prefix || '')
  if (!/^[A-Za-z0-9_-]{1,64}$/.test(fixturePrefix)) throw new Error('缺少可核对的临时数据前缀')
  const folderPrefix = `${fixturePrefix.slice(0, 35)}-access-${random}`
  const assert = (condition, message) => { if (!condition) throw new Error(message) }
  const positiveId = (value, label) => {
    const id = Number(value)
    assert(Number.isSafeInteger(id) && id > 0, `${label}必须是有效的临时对象编号`)
    return id
  }
  const invoke = async (path, method = 'GET', body, token) => {
    try { return await request(path, method, body, token) }
    catch (error) {
      const code = Number(error?.response?.data?.code || error?.httpStatus || error?.response?.status)
      if ([401, 403].includes(code)) return { code }
      // 不把外部适配器的异常内容、请求正文或会话信息带入报告。
      throw new Error(`请求未返回可验证结果：${method} ${path}`)
    }
  }
  const ok = async (path, method = 'GET', body, token) => {
    const response = await invoke(path, method, body, token)
    assert(Number(response?.code) === 200, `${method} ${path} 应返回200，实际${response?.code}`)
    return response
  }
  const forbidden = async (path, method, body, token) => {
    const response = await invoke(path, method, body, token)
    // 权限失效导致意外创建时，仍登记本模块生成的临时目录以便 finally 清理。
    const scope = path.match(/^\/dashboard\/data-folder\/(page|resource)$/)?.[1]
    if (method === 'POST' && scope && Number(response?.code) === 200 && response?.data?.folderId) {
      folders.push({ scope, folderId: Number(response.data.folderId), deleted: false })
    }
    assert(Number(response?.code) === 403, `${method} ${path} 应返回403，实际${response?.code}`)
  }
  const folderBody = label => ({ folderName: `${folderPrefix}-${label}`, folderCode: `${folderPrefix}-${label}`, parentId: 0, sortOrder: 0 })
  const createFolder = async (scope, label, token) => {
    const response = await ok(`/dashboard/data-folder/${scope}`, 'POST', folderBody(label), token)
    const folder = { scope, folderId: positiveId(response.data?.folderId, '新增目录'), deleted: false }
    folders.push(folder)
    return folder
  }
  const matchesFixture = (record, codeKey, idKey, expectedId) => {
    assert(Number(record?.[idKey]) === expectedId && String(record?.[codeKey] || '').startsWith(`${fixturePrefix}-`), `${idKey}未能核对为本轮临时数据`)
    return record
  }
  const pageId = positiveId(state?.pages?.[0], '页面')
  const assetId = positiveId(state?.assets?.[0], '媒体资源')
  const mapId = positiveId(state?.maps?.[0], '地图')
  const pageParentId = positiveId(state?.trees?.page?.parent?.folderId, '页面父目录')
  const resourceParentId = positiveId(state?.trees?.resource?.parent?.folderId, '资源父目录')
  let testFailure
  const cleanupFailures = []
  try {
    const [menuResponse, adminInfo, pageResponse, assetResponse, mapResponse, pageFolders, resourceFolders] = await Promise.all([
      ok('/system/menu/list'), ok('/getInfo'), ok(`/dashboard/page/${pageId}`),
      ok(`/dashboard/asset/list?keyword=${encodeURIComponent(fixturePrefix)}`), ok(`/dashboard/map/${mapId}`),
      ok('/dashboard/data-folder/page/list'), ok('/dashboard/data-folder/resource/list'),
    ])
    const menuList = menuResponse.data || []
    const deptId = positiveId(adminInfo.user?.deptId, '当前管理员部门')
    const page = matchesFixture(pageResponse.data, 'pageCode', 'pageId', pageId)
    const asset = matchesFixture((assetResponse.data || []).find(row => Number(row.assetId) === assetId), 'assetCode', 'assetId', assetId)
    const map = matchesFixture(mapResponse.data, 'mapCode', 'mapId', mapId)
    assert(!map.builtin, '只能使用本轮登记地图')
    for (const [list, id] of [[pageFolders.data, pageParentId], [resourceFolders.data, resourceParentId]]) {
      assert((list || []).some(folder => Number(folder.folderId) === id && String(folder.folderCode || '').startsWith(`${fixturePrefix}-`)), '父目录必须属于本轮临时数据')
    }
    const pageMove = { ids: [pageId], folderId: Number(page.folderId || 0) }
    const assetMove = { ids: [assetId], folderId: Number(asset.folderId || 0), resourceType: 'asset' }
    const mapMove = { ids: [mapId], folderId: Number(map.folderId || 0), resourceType: 'map' }
    const roleSession = async (suffix, permissions) => {
      const name = `${identityPrefix}-${suffix}`
      assert(name.length <= 20, '临时用户名超过登录长度上限')
      const menuIds = [...new Set(permissions.map(permission => {
        const menu = menuList.find(item => item.perms === permission)
        assert(menu, `权限菜单不存在：${permission}`)
        return positiveId(menu.menuId, '权限菜单')
      }))]
      await ok('/system/role', 'POST', { roleName: name, roleKey: name, roleSort: 99, status: '0', dataScope: '1', menuCheckStrictly: true, deptCheckStrictly: true, menuIds, deptIds: [] })
      const roleIdentity = { name }
      roles.push(roleIdentity)
      const roleRows = (await ok(`/system/role/list?roleName=${encodeURIComponent(name)}&pageNum=1&pageSize=100`)).rows || []
      roleIdentity.id = positiveId(roleRows.find(role => role.roleKey === name)?.roleId, '临时角色')
      const password = `Qa!${crypto.randomUUID().replaceAll('-', '').slice(0, 16)}`
      await ok('/system/user', 'POST', { userName: name, nickName: '目录权限回归', password, status: '0', sex: '0', deptId, roleIds: [roleIdentity.id], postIds: [] })
      const userIdentity = { name }
      users.push(userIdentity)
      const userRows = (await ok(`/system/user/list?userName=${encodeURIComponent(name)}&pageNum=1&pageSize=100`)).rows || []
      userIdentity.id = positiveId(userRows.find(user => user.userName === name)?.userId, '临时用户')
      const login = await ok('/login', 'POST', { username: name, password, code: '', uuid: '' }, '')
      assert(typeof login.token === 'string' && login.token, '临时会话建立失败')
      sessions.push(login.token)
      const info = await ok('/getInfo', 'GET', undefined, login.token)
      const actual = [...new Set((info.permissions || []).filter(Boolean))].sort()
      assert(JSON.stringify(actual) === JSON.stringify([...permissions].sort()), '临时角色实际权限与指定边界不符')
      return login.token
    }

    const temporaryPageFolder = await createFolder('page', 'page')
    const temporaryResourceFolder = await createFolder('resource', 'resource')
    const reader = await roleSession('read', ['dashboard:page:list', 'dashboard:resource:list'])
    for (const scope of ['page', 'resource']) {
      await ok(`/dashboard/data-folder/${scope}/list`, 'GET', undefined, reader)
      await forbidden(`/dashboard/data-folder/${scope}`, 'POST', folderBody(`deny-${scope}`), reader)
    }
    for (const path of ['/dashboard/page/list', '/dashboard/asset/page', '/dashboard/map/page']) {
      await ok(`${path}?pageNum=1&pageSize=1`, 'GET', undefined, reader)
    }
    await forbidden('/dashboard/data-folder/page/move', 'PUT', pageMove, reader)
    await forbidden('/dashboard/data-folder/resource/move', 'PUT', assetMove, reader)
    await forbidden('/dashboard/data-folder/resource/move', 'PUT', mapMove, reader)
    await forbidden(`/dashboard/data-folder/page/${pageParentId}`, 'DELETE', undefined, reader)
    await forbidden(`/dashboard/data-folder/resource/${resourceParentId}`, 'DELETE', undefined, reader)
    checks.push('只读角色可读取页面/资源目录与分页列表，目录创建、删除及页面/图片/地图移动均403')

    const pageEditor = await roleSession('page', ['dashboard:page:edit'])
    await ok('/dashboard/data-folder/page/list', 'GET', undefined, pageEditor)
    await ok('/dashboard/data-folder/page', 'PUT', { folderId: temporaryPageFolder.folderId, folderName: `${folderPrefix}-page-edited` }, pageEditor)
    await forbidden('/dashboard/data-folder/page', 'POST', folderBody('deny-page-edit'), pageEditor)
    await forbidden(`/dashboard/data-folder/page/${pageParentId}`, 'DELETE', undefined, pageEditor)
    await ok('/dashboard/data-folder/page/move', 'PUT', pageMove, pageEditor)
    assert(Number((await ok(`/dashboard/page/${pageId}`)).data.folderId || 0) === pageMove.folderId, '页面同目录移动后归档保持一致')
    checks.push('page:edit可读/改页面目录并移动临时页面，创建与删除目录仍403')

    const folderManager = await roleSession('dir', ['dashboard:resource:folder'])
    await ok('/dashboard/data-folder/resource/list', 'GET', undefined, folderManager)
    const managed = await createFolder('resource', 'managed', folderManager)
    await ok('/dashboard/data-folder/resource', 'PUT', { folderId: managed.folderId, folderName: `${folderPrefix}-managed-edited`, sortOrder: 3 }, folderManager)
    await ok(`/dashboard/data-folder/resource/${managed.folderId}`, 'DELETE', undefined, folderManager)
    managed.deleted = true
    await forbidden('/dashboard/data-folder/resource/move', 'PUT', assetMove, folderManager)
    await forbidden('/dashboard/data-folder/resource/move', 'PUT', mapMove, folderManager)
    checks.push('resource:folder可读及新增/修改/删除资源目录，图片与地图移动仍403')

    const resourceEditor = await roleSession('edit', ['dashboard:resource:edit'])
    await ok('/dashboard/data-folder/resource/list', 'GET', undefined, resourceEditor)
    await forbidden('/dashboard/data-folder/resource', 'POST', folderBody('deny-resource-edit'), resourceEditor)
    await forbidden('/dashboard/data-folder/resource', 'PUT', { folderId: temporaryResourceFolder.folderId, folderName: `${folderPrefix}-denied` }, resourceEditor)
    await forbidden(`/dashboard/data-folder/resource/${resourceParentId}`, 'DELETE', undefined, resourceEditor)
    await ok('/dashboard/data-folder/resource/move', 'PUT', assetMove, resourceEditor)
    await ok('/dashboard/data-folder/resource/move', 'PUT', mapMove, resourceEditor)
    const assetAfter = (await ok(`/dashboard/asset/list?keyword=${encodeURIComponent(fixturePrefix)}`)).data.find(row => Number(row.assetId) === assetId)
    const mapAfter = (await ok(`/dashboard/map/${mapId}`)).data
    assert(Number(assetAfter.folderId || 0) === assetMove.folderId && Number(mapAfter.folderId || 0) === mapMove.folderId, '资源同目录移动后归档保持一致')
    checks.push('resource:edit可读目录并移动临时图片/地图，目录新增/修改/删除均403')

    assert(state.httpSourceCode, '缺少用于验证接口分页匿名访问的临时HTTP来源编码')
    const anonymousPaths = ['/dashboard/source/page', '/dashboard/integration/page', `/dashboard/integration/endpoints/${encodeURIComponent(state.httpSourceCode)}/page`, '/dashboard/integration/batches/page', '/dashboard/integration/dead-letters/page']
    for (const path of anonymousPaths) {
      const response = await invoke(`${path}?pageNum=1&pageSize=1`, 'GET', undefined, '')
      assert([401, 403].includes(Number(response?.code)), `匿名访问${path}应返回401或403`)
    }
    checks.push('来源、接入方、取数接口、接收台账和死信五类新增分页均拒绝匿名访问')
  } catch (error) {
    testFailure = error
  } finally {
    // 每项独立清理；一项失败也继续注销会话和清理其余身份，避免留下可登录账号。
    const clean = async (label, action) => { try { await action() } catch { cleanupFailures.push(label) } }
    for (const token of sessions) await clean('临时会话退出', async () => {
      const response = await invoke('/logout', 'POST', undefined, token)
      assert([200, 401].includes(Number(response?.code)), '退出临时会话失败')
    })
    sessions.length = 0
    for (const folder of [...folders].reverse()) if (!folder.deleted) {
      await clean(`目录${folder.scope}/${folder.folderId}`, () => ok(`/dashboard/data-folder/${folder.scope}/${folder.folderId}`, 'DELETE'))
    }
    for (const identity of users) await clean(`用户${identity.name}`, async () => {
      if (!identity.id) {
        const response = await ok(`/system/user/list?userName=${encodeURIComponent(identity.name)}&pageNum=1&pageSize=100`)
        identity.id = positiveId((response.rows || []).find(user => user.userName === identity.name)?.userId, '待清理用户')
      }
      await ok(`/system/user/${identity.id}`, 'DELETE')
    })
    for (const identity of roles) await clean(`角色${identity.name}`, async () => {
      if (!identity.id) {
        const response = await ok(`/system/role/list?roleName=${encodeURIComponent(identity.name)}&pageNum=1&pageSize=100`)
        identity.id = positiveId((response.rows || []).find(role => role.roleKey === identity.name)?.roleId, '待清理角色')
      }
      await ok(`/system/role/${identity.id}`, 'DELETE')
    })
  }
  const cleanupIdentities = { users, roles }
  if (testFailure || cleanupFailures.length) {
    const error = new Error([testFailure?.message, cleanupFailures.length ? `清理未完成：${cleanupFailures.join('；')}` : ''].filter(Boolean).join('；'))
    error.cleanupIdentities = cleanupIdentities
    throw error
  }
  checks.push('所有临时会话已退出，自建目录及临时用户/角色已通过管理接口清理')
  return { checks, cleanupIdentities }
}
