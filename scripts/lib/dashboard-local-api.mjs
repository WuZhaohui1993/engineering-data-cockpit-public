import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
export const root = resolve(import.meta.dirname, '../..')
export async function connectDashboard() {
  const base = process.env.DASHBOARD_LOCAL_API || 'http://127.0.0.1:8080'
  const url = new URL(base)
  if (!['127.0.0.1', 'localhost'].includes(url.hostname)) throw new Error('此配置脚本仅允许本地系统')
  const defaults = readFileSync(resolve(root,'scripts/test-local-stack.sh'),'utf8')
  const username = process.env.TEST_ADMIN_USERNAME || defaults.match(/TEST_ADMIN_USERNAME:-([^}]+)/)?.[1]
  const password = process.env.TEST_ADMIN_PASSWORD || defaults.match(/TEST_ADMIN_PASSWORD:-([^}]+)/)?.[1]
  const response = await fetch(base+'/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username,password,code:'',uuid:''})})
  const login = await response.json()
  if(login.code !== 200 || !login.token) throw new Error('登录失败：'+login.msg)
  async function request(path, method='GET', data) {
    const isForm = data instanceof FormData
    const res = await fetch(base+path,{method,headers:{Authorization:`Bearer ${login.token}`,...(!isForm ? {'Content-Type':'application/json'}:{})},body:data===undefined?undefined:isForm?data:JSON.stringify(data)})
    const value = await res.json()
    if (!res.ok || (value.code!==undefined && value.code!==200)) throw new Error(`${method} ${path}: ${value.msg || res.status}`)
    return value
  }
  return {request,base}
}
