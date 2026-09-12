import test from 'node:test'
import assert from 'node:assert/strict'
import { dashboardMenuMeta, resolveDashboardRuntimeTarget } from '../src/utils/dashboardMenuRoute.js'
const menu=(extra={})=>({component:'dashboard/runtime/index',name:'DashboardRuntime296',path:'runtime/code/reference-industrial-overview',query:'',meta:{title:'项目大屏'},...extra})
test('持久化页面标识与目录、路径和路由名称无关',()=>{
 for(const path of ['other/runtime/code/reference-industrial-overview','parent/child/screen','standalone']){
  const meta=dashboardMenuMeta(menu({path,name:'UserRenamedRoute',query:JSON.stringify({dashboardPageCode:'xinghua-overview',projectCode:'demo'})}))
  assert.equal(meta.title,'项目大屏');assert.equal(meta.dashboardRuntime,true)
  assert.deepEqual(resolveDashboardRuntimeTarget({path:'/'+path,meta,query:{pageCode:'bad',pageId:393,dashboardPageCode:'xinghua-progress'}}),{pageCode:'xinghua-overview',pageId:0})
 }
})
test('旧菜单空query从组件路径末尾或稳定路由名识别',()=>{
 for(const path of ['runtime/code/reference-industrial-overview','other/runtime/code/reference-industrial-overview'])assert.equal(dashboardMenuMeta(menu({path})).dashboardPageCode,'reference-industrial-overview')
 assert.deepEqual(resolveDashboardRuntimeTarget({path:'/renamed/path',meta:dashboardMenuMeta(menu({path:'renamed/path'}))}),{pageCode:'',pageId:296})
 assert.equal(dashboardMenuMeta(menu({query:'{bad'})).dashboardPageCode,'reference-industrial-overview')
})
test('参数化运行路由优先，业务同名query不改变页面，分享忽略菜单身份',()=>{
 const meta=dashboardMenuMeta(menu())
 assert.deepEqual(resolveDashboardRuntimeTarget({path:'/dashboard/runtime/393',params:{pageId:'393'},meta,query:{pageId:392}}),{pageCode:'',pageId:393})
 assert.deepEqual(resolveDashboardRuntimeTarget({path:'/dashboard/runtime/code/xinghua-progress',params:{pageCode:'xinghua-progress'},meta,query:{pageId:392}}),{pageCode:'xinghua-progress',pageId:0})
 assert.deepEqual(resolveDashboardRuntimeTarget({path:'/dashboard/share/token',params:{token:'token'},meta,query:{pageCode:'xinghua-overview',pageId:392}}),{pageCode:'',pageId:0})
 assert.deepEqual(resolveDashboardRuntimeTarget({path:'/dashboard/share/token/p/xinghua-quality',params:{token:'token',pageCode:'xinghua-quality'},meta}),{pageCode:'xinghua-quality',pageId:0})
})
test('非运行组件不做标记，非法编码不作为目标',()=>{
 const ordinary={component:'system/user/index',meta:{title:'用户'}};assert.equal(dashboardMenuMeta(ordinary),ordinary.meta)
 for(const code of ['../../system/user','https://evil.example','abc/def',{},['xinghua-overview']])assert.equal(dashboardMenuMeta(menu({name:'NoId',path:'arbitrary',query:JSON.stringify({dashboardPageCode:code})})).dashboardPageCode,'')
})
