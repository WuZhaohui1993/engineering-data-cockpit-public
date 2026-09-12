import { connectDashboard } from './lib/dashboard-local-api.mjs'
import { setupManagementFixtures, verifyManagementFixtures, cleanupManagementFixtures } from '../ruoyi-ui/tests/dashboard-management-pagination-api.mjs'

// 本地专用；只创建本轮临时对象。管理员凭证通过环境变量注入。
const { request } = await connectDashboard()
const response = async (path, method, body) => {
  try { return await request(path, method, body) }
  catch (error) { return { code: 500, msg: error.message } }
}
const state = await setupManagementFixtures(response)
try { console.log(JSON.stringify(await verifyManagementFixtures(response, state), null, 2)) }
finally { await cleanupManagementFixtures(response, state) }
