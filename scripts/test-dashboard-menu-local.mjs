import {mkdirSync,writeFileSync,readFileSync} from 'node:fs'
import {resolve} from 'node:path'
import assert from 'node:assert/strict'
import {connectDashboard,root} from './lib/dashboard-local-api.mjs'
const {request}=await connectDashboard()
const dir=resolve(root,'.local/menu-regression');mkdirSync(dir,{recursive:true,mode:0o700})
const statePath=resolve(dir,'state.json'),mode=process.argv[2]||'setup'
const menuBody=m=>Object.fromEntries(['menuId','menuName','parentId','orderNum','path','component','query','routeName','isFrame','isCache','menuType','visible','status','perms','icon'].filter(k=>m[k]!==undefined).map(k=>[k,m[k]]))
const menus=async()=> (await request('/system/menu/list')).data
async function createSystemMenu(body){await request('/system/menu','POST',body);const found=(await menus()).find(m=>m.menuName===body.menuName&&m.parentId===body.parentId&&m.path===body.path);assert.ok(found);return found}
async function removeDenied(menuId,pattern){try{await request('/system/menu/'+menuId,'DELETE');assert.fail('Unexpected deletion')}catch(e){assert.match(e.message,pattern)}}
if(mode==='setup'){
 const tag=Date.now().toString(),code='menu-regression-'+tag
 const page=(await request('/dashboard/page','POST',{pageCode:code,pageName:'菜单回归临时页面',remark:'MENU_REGRESSION_FIXTURE',schemaJson:JSON.stringify({schemaVersion:'1.0',canvas:{width:640,height:360,theme:'dark',backgroundColor:'#102033'},refresh:{enabled:false,seconds:60},widgets:[{id:'proof',type:'text',layout:{x:20,y:30,w:600,h:100,z:1},style:{text:'菜单移动与删除验证',titleVisible:false,qualityVisible:false,fontSize:28}}]})})).data
 await request(`/dashboard/page/${page.pageId}/publish`,'POST',{publishNote:'本地菜单修复验证，完成后清理'})
 const configured=(await request(`/dashboard/page/${page.pageId}/menu`,'POST')).data
 assert.equal(JSON.parse(configured.query).dashboardPageCode,code)
 // A newly configured page menu has no implicit role assignment and can be deleted immediately.
 await request('/system/menu/'+configured.menuId,'DELETE')
 assert.ok(!(await menus()).some(m=>m.menuId===configured.menuId))
 let active=(await request(`/dashboard/page/${page.pageId}/menu`,'POST')).data
 const child=await createSystemMenu({menuName:'菜单回归子权限-'+tag,parentId:active.menuId,orderNum:1,path:'#',menuType:'F',visible:'0',status:'0',isFrame:'1',isCache:'0',icon:'#',perms:'test:menu:read'})
 await removeDenied(active.menuId,/存在子菜单/)
 await request('/system/menu/'+child.menuId,'DELETE')
 const roleBody={roleName:'菜单回归角色-'+tag,roleKey:'menu-regression-'+tag,roleSort:99,status:'0',dataScope:'1',menuCheckStrictly:true,deptCheckStrictly:true,menuIds:[active.menuId],remark:'MENU_REGRESSION_FIXTURE'}
 await request('/system/role','POST',roleBody)
 const role=(await request('/system/role/list?pageNum=1&pageSize=1000')).rows.find(r=>r.roleKey===roleBody.roleKey)
 assert.ok(role)
 await removeDenied(active.menuId,/菜单已分配/)
 await request('/system/role','PUT',{...roleBody,roleId:role.roleId,menuIds:[]})
 await request('/system/menu/'+active.menuId,'DELETE')
 await request('/system/role/'+role.roleId,'DELETE')
 active=(await request(`/dashboard/page/${page.pageId}/menu`,'POST')).data
 const system=(await menus()).find(m=>m.parentId===0&&m.path==='system'&&m.menuType==='M')
 const parent=await createSystemMenu({menuName:'菜单回归目录-'+tag,parentId:0,orderNum:99,path:'menu-qa-'+tag,menuType:'M',visible:'0',status:'0',isFrame:'1',isCache:'0',icon:'tree'})
 const nested=await createSystemMenu({menuName:'菜单回归子目录-'+tag,parentId:parent.menuId,orderNum:1,path:'nested',menuType:'M',visible:'0',status:'0',isFrame:'1',isCache:'0',icon:'tree'})
 const state={tag,code,pageId:page.pageId,menuId:active.menuId,systemId:system.menuId,parentId:parent.menuId,nestedId:nested.menuId,parentPath:parent.path,deletionChecks:{newUnassigned:true,childProtected:true,roleAssignedProtected:true,unassignedDeleted:true},moves:[]}
 writeFileSync(statePath,JSON.stringify(state,null,2));console.log(JSON.stringify(state,null,2))
}else{
 const state=JSON.parse(readFileSync(statePath))
 if(mode==='cleanup'){
  const all=await menus();for(const id of [state.menuId,state.nestedId,state.parentId])if(all.some(m=>m.menuId===id))await request('/system/menu/'+id,'DELETE')
  await request('/dashboard/page/'+state.pageId,'DELETE');state.cleaned=true;writeFileSync(statePath,JSON.stringify(state,null,2));console.log('临时菜单/目录已删除，测试页面已移入回收站');process.exit(0)
 }
 const before=(await request('/system/menu/'+state.menuId)).data
 const updates={system:{parentId:state.systemId},nested:{parentId:state.nestedId},root:{parentId:0},renamed:{parentId:state.nestedId,path:'custom-screen',routeName:'MenuRegressionCustom'+state.tag,menuName:'菜单回归自定义名称'}}
 assert.ok(updates[mode],mode)
 const body={...menuBody(before),...updates[mode]};await request('/system/menu','PUT',body)
 const repeated=(await request(`/dashboard/page/${state.pageId}/menu`,'POST')).data
 assert.equal(repeated.menuId,state.menuId);assert.equal(repeated.created,false)
 const after=(await request('/system/menu/'+state.menuId)).data
 for(const k of Object.keys(updates[mode]))assert.equal(after[k],updates[mode][k])
 let url=mode==='system'?'/system/'+after.path:mode==='root'?'/'+after.path:`/${state.parentPath}/nested/${after.path}`
 state.current={mode,url,menuName:after.menuName};state.moves.push({mode,menuId:state.menuId,path:after.path,parentId:after.parentId,query:after.query,url});writeFileSync(statePath,JSON.stringify(state,null,2));console.log(JSON.stringify(state.current))
}
