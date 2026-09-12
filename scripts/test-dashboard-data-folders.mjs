import { connectDashboard } from './lib/dashboard-local-api.mjs'
import { runDataFolderRegression } from '../ruoyi-ui/tests/dashboard-data-folders-api.mjs'

// 仅供本地系统；凭证由 TEST_ADMIN_USERNAME/TEST_ADMIN_PASSWORD 注入。
const { request } = await connectDashboard()
const response = async (path, method, body) => {
  try { return await request(path, method, body) }
  catch (error) { return { code: 500, msg: error.message } }
}
console.log(JSON.stringify(await runDataFolderRegression(response), null, 2))
