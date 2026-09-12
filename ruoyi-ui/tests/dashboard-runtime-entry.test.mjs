import test from 'node:test'
import assert from 'node:assert/strict'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createRequire } from 'node:module'

const require = createRequire(new URL('./helpers/dashboard-property-audit.mjs', import.meta.url))
const vueRequire = createRequire(require.resolve('vue'))
const { baseParse } = vueRequire('@vue/compiler-dom')
import { dashboardMenuMeta } from '../src/utils/dashboardMenuRoute.js'
import { readDashboardSfc } from './helpers/dashboard-property-audit.mjs'

const runtimeSfc = await readDashboardSfc(
  new URL('../src/views/dashboard/runtime/index.vue', import.meta.url).pathname,
)

// Extract the production computed initializer instead of duplicating its route
// predicate in this test. The SFC's script is parsed by the existing audit helper.
const menuDeclaration = runtimeSfc.program.body
  .filter((statement) => statement.type === 'VariableDeclaration')
  .flatMap((statement) => statement.declarations)
  .find((declaration) => declaration.id.name === 'isMenuRuntime')
assert.ok(menuDeclaration, 'runtime SFC must define isMenuRuntime')
const menuInitializerSource = runtimeSfc.script.slice(
  menuDeclaration.init.start,
  menuDeclaration.init.end,
)
const productionIsMenuRuntime = new Function(
  'computed',
  'route',
  'isPreview',
  'revisionId',
  'isEmbedMode',
  'isShareMode',
  `return (${menuInitializerSource})`,
)

const menu = dashboardMenuMeta({
  component: 'dashboard/runtime/index',
  name: 'DashboardRuntime403',
  path: 'runtime/code/component-property-audit-1788672087356',
  query: JSON.stringify({ dashboardPageCode: 'component-property-audit-1788672087356' }),
  meta: { title: '组件属性逐项验收（测试草稿）' },
})

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    {
      path: '/dashboard/runtime/:pageId(\\d+)',
      name: 'DashboardRuntime',
      component: {},
      meta: { title: '大屏运行' },
    },
    {
      path: '/dashboard/runtime/code/:pageCode([A-Za-z0-9_-]+)',
      name: 'DashboardRuntimeCode',
      component: {},
      meta: { title: '大屏运行' },
    },
    {
      path: '/dp/runtime/code/component-property-audit-1788672087356',
      name: 'DashboardRuntime403Menu',
      component: {},
      meta: menu,
    },
  ],
})

const ref = (value) => ({ value })
const evaluate = (route, options = {}) =>
  productionIsMenuRuntime(
    (getter) => getter(),
    route,
    ref(Boolean(options.preview)),
    ref(Number(options.revisionId) || 0),
    ref(Boolean(options.embed)),
    ref(Boolean(options.share)),
  )

test('普通运行编号和编码入口没有菜单元数据，保留管理工具栏', async () => {
  await router.push('/dashboard/runtime/403')
  assert.equal(router.currentRoute.value.meta.dashboardRuntime, undefined)
  assert.equal(evaluate(router.currentRoute.value), false)

  await router.push('/dashboard/runtime/code/component-property-audit-1788672087356')
  assert.equal(router.currentRoute.value.meta.dashboardRuntime, undefined)
  assert.equal(evaluate(router.currentRoute.value), false)
})

test('真实部署菜单的 dashboardMenuMeta 标记触发隐藏管理工具栏', async () => {
  await router.push('/dp/runtime/code/component-property-audit-1788672087356')
  assert.equal(router.currentRoute.value.meta.dashboardRuntime, true)
  assert.equal(evaluate(router.currentRoute.value), true)
})

test('页面管理的草稿和历史版本入口仍无菜单元数据', async () => {
  await router.push('/dashboard/runtime/403?preview=1')
  assert.equal(router.currentRoute.value.meta.dashboardRuntime, undefined)
  assert.equal(evaluate(router.currentRoute.value), false)

  await router.push({
    path: '/dashboard/runtime/code/component-property-audit-1788672087356',
    query: { menuRuntime: '1' },
  })
  assert.equal(router.currentRoute.value.meta.dashboardRuntime, undefined)
  assert.equal(evaluate(router.currentRoute.value), true)

  await router.push('/dashboard/runtime/403?preview=1&revisionId=17')
  assert.equal(router.currentRoute.value.meta.dashboardRuntime, undefined)
  assert.equal(evaluate(router.currentRoute.value), false)
})

function templateIfExpression(className) {
  const ast = baseParse(runtimeSfc.descriptor.template.content)
  const visit = (nodes) => {
    for (const node of nodes || []) {
      if (node.type === 1) {
        const classes = node.props.find((prop) => prop.type === 6 && prop.name === 'class')?.value?.content?.split(/\s+/) || []
        if (classes.includes(className)) {
          return node.props.find((prop) => prop.type === 7 && prop.name === 'if')?.exp?.content
        }
        const found = visit(node.children)
        if (found) return found
      }
    }
    return ''
  }
  return visit(ast.children)
}

test('工具栏和筛选栏的生产 v-if 独立排除嵌入/分享，并服从菜单标记', () => {
  const toolbarIf = templateIfExpression('runtime-toolbar')
  const filterIf = templateIfExpression('runtime-filterbar')
  assert.ok(toolbarIf)
  assert.ok(filterIf)
  const toolbarVisible = new Function('isEmbedMode', 'isShareMode', 'isMenuRuntime', `return ${toolbarIf}`)
  const filterVisible = new Function('isEmbedMode', 'isShareMode', 'isMenuRuntime', 'pageFilters', `return Boolean(${filterIf})`)
  assert.equal(toolbarVisible(false, false, false), true)
  assert.equal(toolbarVisible(false, false, true), false)
  assert.equal(toolbarVisible(true, false, false), false)
  assert.equal(toolbarVisible(false, true, false), false)
  assert.equal(filterVisible(false, false, false, [{}]), true)
  assert.equal(filterVisible(false, false, true, [{}]), false)
  assert.equal(filterVisible(false, false, false, []), false)
})
