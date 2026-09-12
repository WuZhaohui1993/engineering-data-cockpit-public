import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createRenderer, defineComponent, h, nextTick, ref } from 'vue'

// Exercise the exact key expression used by the actual RouterView host.
const appMain=readFileSync(new URL('../src/layout/components/AppMain.vue',import.meta.url),'utf8')
const expression=appMain.match(/:key="([^"]+)"/)[1]
const viewKey=new Function('route',`return (${expression})`)
function harness(initialPath) {
 let fullscreenElement=null, nativeExits=0
 const node=type=>({type,children:[],parent:null})
 const renderer=createRenderer({
  createElement:node,createText:text=>({...node('text'),text}),createComment:text=>({...node('comment'),text}),
  setText:(n,text)=>{n.text=text},setElementText:(n,text)=>{n.text=text},
  insert(n,parent,anchor){ if(n.parent){const i=n.parent.children.indexOf(n);if(i>=0)n.parent.children.splice(i,1)};n.parent=parent;const i=parent.children.indexOf(anchor);parent.children.splice(i<0?parent.children.length:i,0,n) },
  remove(n){if(n===fullscreenElement){fullscreenElement=null;nativeExits++}const i=n.parent?.children.indexOf(n);if(i>=0)n.parent.children.splice(i,1);n.parent=null},
  parentNode:n=>n.parent,nextSibling:n=>n.parent?.children[n.parent.children.indexOf(n)+1]||null,
  patchProp:(n,k,old,v)=>{n[k]=v},
 })
 const path=ref(initialPath),screen=defineComponent({setup:()=>()=>h('div',{class:'runtime-page'})})
 const root=node('root'),app=renderer.createApp({setup:()=>()=>h(screen,{key:viewKey({path:path.value})})})
 app.mount(root)
 return {root,path,enter(){fullscreenElement=root.children[0];return fullscreenElement},get fullscreen(){return fullscreenElement},get nativeExits(){return nativeExits},close:()=>app.unmount()}
}
test('六页编码切换保留真实宿主节点，不触发全屏元素移除',async()=>{
 const harnessState=harness('/dashboard/runtime/code/xinghua-progress')
 const original=harnessState.enter()
 for(const key of ['overview','quality','safety','hse','smart','progress']){
  harnessState.path.value=`/dashboard/runtime/code/xinghua-${key}`;await nextTick()
  assert.equal(harnessState.root.children[0],original);assert.equal(harnessState.fullscreen,original)
 }
 assert.equal(harnessState.nativeExits,0);harnessState.close()
})
test('编号与编码入口共用运行容器，退出运行页正常销毁全屏节点',async()=>{
 const hs=harness('/dashboard/runtime/393'),original=hs.enter()
 hs.path.value='/dashboard/runtime/code/xinghua-progress';await nextTick();assert.equal(hs.fullscreen,original)
 hs.path.value='/dashboard/page';await nextTick();assert.equal(hs.fullscreen,null);assert.equal(hs.nativeExits,1);hs.close()
})
test('普通管理页及设计器仍保留各自路由key',()=>{
 for(const path of ['/dashboard/page','/dashboard/designer/393','/dashboard/designer/392','/dashboard/dataset','/system/user'])assert.equal(viewKey({path}),path)
})
test('移动到任意目录或根级菜单仍复用大屏容器',()=>{
 const standard=viewKey({path:'/dashboard/runtime/code/xinghua-overview'})
 for(const path of ['/system/runtime/code/xinghua-overview','/parent/nested/custom-screen','/standalone-screen'])assert.equal(viewKey({path,meta:{dashboardRuntime:true}}),standard)
 assert.equal(viewKey({path:'/system/user',meta:{dashboardRuntime:false}}),'/system/user')
})
