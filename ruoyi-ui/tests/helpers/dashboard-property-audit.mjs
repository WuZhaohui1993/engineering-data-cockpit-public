import fs from 'node:fs/promises'
import path from 'node:path'
import vm from 'node:vm'
import { createRequire } from 'node:module'
import { fileURLToPath, pathToFileURL } from 'node:url'

const require = createRequire(import.meta.url)
const vueRequire = createRequire(require.resolve('vue'))
const compilerRequire = createRequire(vueRequire.resolve('@vue/compiler-sfc'))
const { parse: parseSfc } = compilerRequire('@vue/compiler-sfc')
const { baseParse } = compilerRequire('@vue/compiler-dom')
const { parse: parseJs, parseExpression } = compilerRequire('@babel/parser')
export const projectRoot = fileURLToPath(new URL('../../..', import.meta.url))
export const designerPath = path.join(projectRoot, 'ruoyi-ui/src/views/dashboard/designer/index.vue')
export const runtimePath = path.join(projectRoot, 'ruoyi-ui/src/views/dashboard/runtime/index.vue')

export async function readDashboardSfc(filename) {
  const source = await fs.readFile(filename, 'utf8')
  const { descriptor, errors } = parseSfc(source, { filename })
  if (errors.length) throw new Error(`${filename}: ${errors.map(String).join('\n')}`)
  const script = descriptor.scriptSetup.content
  return { filename, source, script, descriptor, program: parseJs(script, { sourceType: 'module' }).program }
}

function literal(node) {
  if (!node) return undefined
  if (['StringLiteral', 'NumericLiteral', 'BooleanLiteral'].includes(node.type)) return node.value
  if (node.type === 'NullLiteral') return null
  if (node.type === 'ArrayExpression') return node.elements.map(literal)
  if (node.type === 'ObjectExpression') return Object.fromEntries(node.properties.filter(p => p.type === 'ObjectProperty').map(p => [p.key.name ?? p.key.value, literal(p.value)]))
  if (node.type === 'NewExpression' && node.callee.name === 'Set') return new Set(literal(node.arguments[0]))
  if (node.type === 'UnaryExpression' && node.operator === '-') return -literal(node.argument)
  return undefined
}

function variable(sfc, name) {
  for (const statement of sfc.program.body) {
    if (statement.type !== 'VariableDeclaration') continue
    const found = statement.declarations.find(item => item.id.name === name)
    if (found) return found
  }
  return undefined
}

export function componentCatalog(sfc) {
  const groups = literal(variable(sfc, 'componentGroups')?.init)
  if (!Array.isArray(groups)) throw new Error('没有找到设计器 componentGroups')
  return groups.flatMap(group => group.items.map(item => ({ type: item.type, label: item.label, group: group.label })))
}

export async function createFunctionHarness(sfc, overrides = {}) {
  // Only declarations are loaded. Lifecycle hooks, network requests and route setup never execute.
  // Function bodies come from the application source, so assertions exercise production logic.
  const context = vm.createContext({
    URL, Date, Math, JSON, Number, String, Boolean, Object, Array, Set, Map,
    schema: { value: { canvas: {}, widgets: [] }, canvas: {}, widgets: [] },
    datasetCatalog: { value: [] }, datasets: { value: [] },
    dictionaryCache: {}, widgetStates: {}, designDataPreview: {},
    overrideData: {}, sortState: {}, drilldownStates: {}, linkedFilters: {},
    pageFilterValues: {}, formValues: {}, tabValues: {}, mapStates: {},
    selectedWidget: { value: null }, clockNow: { value: new Date('2026-09-06T01:02:03Z') },
    memberEditId: { value: '' }, layerDragMemberOnly: { value: false }, clipboardGroups: { value: {} },
    ...overrides,
  })
  for (const statement of sfc.program.body) {
    if (statement.type !== 'ImportDeclaration') continue
    const imported = statement.source.value
    // Local pure dashboard helpers can be imported directly, without mocking their behavior.
    if (!/^@\/utils\/dashboard/.test(imported) || /Icons/.test(imported)) continue
    const filename = path.join(projectRoot, 'ruoyi-ui/src', imported.slice(2)) + '.js'
    try {
      const helperSource = await fs.readFile(filename, 'utf8')
      // This helper has no imports. Supply Vite's public compile-time base path for Node execution.
      const module = imported === '@/utils/dashboard' && helperSource.includes('import.meta.env')
        ? await import(`data:text/javascript;base64,${Buffer.from(helperSource.replaceAll('import.meta.env', '({ VITE_APP_BASE_API: "/dev-api" })')).toString('base64')}`)
        : await import(pathToFileURL(filename).href)
      for (const specifier of statement.specifiers) {
        if (specifier.type === 'ImportSpecifier') context[specifier.local.name] = module[specifier.imported.name]
      }
    } catch (error) {
      // Browser-only helpers remain absent; an attempted call fails visibly instead of silently mocking it.
      context.__unavailableImports ||= []
      context.__unavailableImports.push({ imported, error: error.message })
    }
  }
  context.echarts = await import('echarts')
  for (const statement of sfc.program.body) {
    if (statement.type === 'FunctionDeclaration') {
      const source = sfc.script.slice(statement.start, statement.end)
      if (source.includes('import.meta')) continue
      vm.runInContext(source, context)
    }
    if (statement.type === 'VariableDeclaration') {
      for (const declaration of statement.declarations) {
        if (declaration.id.type !== 'Identifier') continue
        const value = literal(declaration.init)
        if (value !== undefined && !(declaration.id.name in context)) context[declaration.id.name] = value
      }
    }
  }
  if (variable(sfc, 'componentGroups')) {
    context.metaMap = Object.fromEntries(componentCatalog(sfc).map(item => [item.type, item]))
  }
  return context
}

const UNKNOWN = Symbol('dynamic condition')
function evaluate(node, values, helpers) {
  if (!node) return UNKNOWN
  const direct = literal(node)
  if (direct !== undefined && !['ObjectExpression', 'ArrayExpression'].includes(node.type)) return direct
  if (node.type === 'Identifier') return Object.hasOwn(values, node.name) ? values[node.name] : UNKNOWN
  if (node.type === 'ArrayExpression') return node.elements.map(item => evaluate(item, values, helpers))
  if (['MemberExpression', 'OptionalMemberExpression'].includes(node.type)) {
    const object = evaluate(node.object, values, helpers)
    const key = node.computed ? evaluate(node.property, values, helpers) : node.property.name
    if (object === UNKNOWN || object == null || key === UNKNOWN) return UNKNOWN
    return Object.hasOwn(object, key) ? object[key] : UNKNOWN
  }
  if (node.type === 'LogicalExpression') {
    const left = evaluate(node.left, values, helpers)
    const right = evaluate(node.right, values, helpers)
    if (node.operator === '&&') {
      if ((left !== UNKNOWN && !left) || (right !== UNKNOWN && !right)) return false
      return left === UNKNOWN || right === UNKNOWN ? UNKNOWN : Boolean(left && right)
    }
    if (node.operator === '||') {
      if ((left !== UNKNOWN && left) || (right !== UNKNOWN && right)) return true
      return left === UNKNOWN || right === UNKNOWN ? UNKNOWN : false
    }
  }
  if (node.type === 'UnaryExpression' && node.operator === '!') {
    const value = evaluate(node.argument, values, helpers)
    return value === UNKNOWN ? UNKNOWN : !value
  }
  if (node.type === 'BinaryExpression') {
    const left = evaluate(node.left, values, helpers)
    const right = evaluate(node.right, values, helpers)
    if (left === UNKNOWN || right === UNKNOWN) return UNKNOWN
    if (['===', '=='].includes(node.operator)) return left === right
    if (['!==', '!='].includes(node.operator)) return left !== right
    if (node.operator === '>') return left > right
    if (node.operator === '<') return left < right
  }
  if (node.type === 'CallExpression' || node.type === 'OptionalCallExpression') {
    const args = node.arguments.map(item => evaluate(item, values, helpers))
    if (args.includes(UNKNOWN)) return UNKNOWN
    const callee = node.callee
    if (callee.type === 'Identifier' && callee.name === 'selectedPropertyVisible') {
      const independent = ['binding.rowLimit', 'style.qualityVisible', 'style.color', 'style.useSystemPalette', 'style.fontSize', 'style.fontWeight', 'style.textAlign', 'style.contentVerticalAlign']
      return independent.includes(args[0]) && helpers.dashboardPropertyVisible ? helpers.dashboardPropertyVisible(values.selectedWidget, args[0]) : UNKNOWN
    }
    if (callee.type === 'Identifier' && typeof helpers[callee.name] === 'function') {
      try { return helpers[callee.name](...args) } catch { return UNKNOWN }
    }
    if (callee.type === 'MemberExpression') {
      const object = evaluate(callee.object, values, helpers)
      const method = callee.property.name
      if (object !== UNKNOWN && ['includes', 'has'].includes(method) && typeof object?.[method] === 'function') return object[method](...args)
    }
  }
  return UNKNOWN
}

const attribute = (node, name) => node.props?.find(prop => prop.type === 6 && prop.name === name)?.value?.content
const directive = (node, name, arg) => node.props?.find(prop => prop.type === 7 && prop.name === name && (!arg || prop.arg?.content === arg))
const compact = value => String(value || '').replace(/\s+/g, ' ').trim()
function visibleText(node) {
  return compact((node.children || []).map(child => child.type === 2 ? child.content : child.type === 1 && !/^(el-|input|textarea|select)/.test(child.tag) ? visibleText(child) : '').join(' '))
}

export async function auditInspector(sfc) {
  sfc ||= await readDashboardSfc(designerPath)
  const catalog = componentCatalog(sfc)
  const helpers = await createFunctionHarness(sfc)
  const ast = baseParse(sfc.descriptor.template.content)
  const fields = []
  const valuesFor = (type, customChartType) => {
    const widget = { type, style: { customChartType } }
    return { selectedWidget: widget, selectedCapabilities: helpers.dashboardComponentCapabilities?.(widget) ?? UNKNOWN }
  }
  function walkChildren(children, state) {
    let branchConditions = []
    for (const node of children || []) {
      if (node.type !== 1) continue
      const condition = directive(node, 'if')?.exp?.content
      const elseif = directive(node, 'else-if')?.exp?.content
      const otherwise = directive(node, 'else')
      let local = []
      if (condition) { branchConditions = [condition]; local = [condition] }
      else if (elseif) { local = [...branchConditions.map(c => `!(${c})`), elseif]; branchConditions.push(elseif) }
      else if (otherwise) { local = branchConditions.map(c => `!(${c})`); branchConditions = [] }
      else branchConditions = []
      const show = directive(node, 'show')?.exp?.content
      if (show) local.push(show)
      const next = { ...state, conditions: [...state.conditions, ...local], ancestors: [...state.ancestors, node] }
      if (node.tag === 'el-tab-pane' && ['basic', 'data', 'style', 'interaction'].includes(attribute(node, 'name'))) {
        next.tab = attribute(node, 'label')
        next.tabName = attribute(node, 'name')
      }
      if (attribute(node, 'class')?.split(/\s+/).includes('property-section')) {
        const title = node.children?.find(child => child.type === 1 && attribute(child, 'class')?.includes('section-label'))
        next.section = visibleText(title || {})
      }
      if (directive(node, 'for')?.exp?.content) next.loops = [...state.loops, compact(directive(node, 'for').exp.content)]
      const binding = directive(node, 'model')?.exp?.content || directive(node, 'bind', 'model-value')?.exp?.content
      const events = (node.props || []).filter(prop => prop.type === 7 && prop.name === 'on').map(prop => ({ event: prop.arg?.content, handler: compact(prop.exp?.content) }))
      const actionOnly = !binding && events.some(event => event.event === 'click') && ['button', 'el-button'].includes(node.tag)
      if (next.tab && (binding || actionOnly)) {
        const formItem = [...next.ancestors].reverse().find(parent => parent.tag === 'el-form-item')
        const labelParent = [...next.ancestors].reverse().find(parent => parent.tag === 'label' || attribute(parent, 'class')?.includes('toggle-row'))
        const field = {
          id: `property-${fields.length + 1}`,
          tab: next.tab, section: next.section || '',
          label: attribute(formItem || {}, 'label') || attribute(formItem || {}, ':label') || visibleText(labelParent || {}) || attribute(node, 'placeholder') || (actionOnly ? visibleText(node) : '') || compact(binding) || `图标操作：${events.find(event => event.event === 'click')?.handler}`,
          kind: actionOnly ? 'action' : events.length ? 'editable' : attribute(node, 'disabled') !== undefined || node.props.some(p => p.type === 6 && p.name === 'disabled') ? 'readonly' : 'editable',
          control: node.tag, binding: compact(binding), events,
          conditions: next.conditions.map(compact), loops: next.loops,
          line: sfc.descriptor.template.loc.start.line + node.loc.start.line - 1,
          options: (node.children || []).filter(child => child.type === 1 && ['el-option', 'el-radio-button', 'el-radio'].includes(child.tag)).map(child => ({ label: attribute(child, 'label') || visibleText(child), value: attribute(child, 'value') ?? compact(directive(child, 'bind', 'value')?.exp?.content), condition: compact(directive(child, 'if')?.exp?.content) || undefined })),
          constraints: Object.fromEntries((node.props || []).filter(prop => prop.type === 7 && prop.name === 'bind' && ['min', 'max', 'step', 'disabled'].includes(prop.arg?.content)).map(prop => [prop.arg.content, compact(prop.exp?.content)])),
        }
        const parsedConditions = field.conditions.map(condition => { try { return parseExpression(condition) } catch { return null } })
        field.possibleTypes = catalog.filter(item => {
          // Unknown data/toggle conditions remain conditional. Custom chart subtypes are separate possibilities.
          const chartTypes = helpers.customChartTypes instanceof Set ? [...helpers.customChartTypes] : ['bar-chart']
          const variants = item.type === 'custom-chart' ? chartTypes : [undefined]
          return variants.some(customChartType => parsedConditions.every(condition => evaluate(condition, valuesFor(item.type, customChartType), helpers) !== false))
        }).map(item => item.type)
        field.dynamicConditions = field.conditions.filter((condition, index) => catalog.some(item => evaluate(parsedConditions[index], valuesFor(item.type), helpers) === UNKNOWN))
        fields.push(field)
      }
      walkChildren(node.children, next)
    }
  }
  walkChildren(ast.children, { conditions: [], loops: [], ancestors: [] })
  const roleInitializer = variable(sfc, 'fieldRoles')?.init?.arguments?.[0]
  const rolesFor = (type, customChartType) => {
    if (!roleInitializer) return []
    helpers.selectedWidget.value = { type, style: { customChartType } }
    helpers.selectedCapabilities = { value: helpers.dashboardComponentCapabilities?.(helpers.selectedWidget.value) }
    return vm.runInContext(`(${sfc.script.slice(roleInitializer.start, roleInitializer.end)})()`, helpers)
  }
  return {
    generatedAt: new Date().toISOString(),
    evidenceBoundary: 'AST inventory records controls and conditional visibility; it does not certify browser behavior or prove that a source reference produces a visible effect.',
    components: catalog.map(item => ({ ...item, controls: fields.filter(field => field.possibleTypes.includes(item.type)).map(field => field.id), fieldRoles: rolesFor(item.type), ...(item.type === 'custom-chart' ? { subtypeFieldRoles: Object.fromEntries([...helpers.customChartTypes].map(type => [type, rolesFor(item.type, type)])) } : {}) })),
    fields,
  }
}

export function fieldPath(field) {
  return field.binding.match(/^selectedWidget\.(style(?:\.[\w]+)+|layout\.[\w]+|name)/)?.[1] || ''
}

export function setPath(object, key, value) {
  const parts = key.split('.')
  for (const part of parts.slice(0, -1)) object = object[part] ||= {}
  object[parts.at(-1)] = value
}

export function chartFixture(harness, type) {
  return {
    id: `audit-${type}`, type, name: type,
    layout: { x: 0, y: 0, w: 480, h: 300, z: 1 },
    binding: { fieldMap: { category: 'name', value: 'value' }, rowLimit: 50, staticRows: [] },
    interaction: { onClick: 'none' }, state: {},
    style: {
      color: '#35d4b0', titleVisible: true, customChartType: 'bar-chart',
      chartConfig: harness.defaultChartConfig(type),
    },
  }
}

export const chartRows = [
  { name: '2026-09-01', value: 12345.6789, other: 3156.4321, max: 20000 },
  { name: '2026-09-02', value: 7654.321, other: 4812.3456, max: 20000 },
]

function probeValues(field, key) {
  if (key === 'style.mapVisualMin') return [0, 20]
  if (key === 'style.mapVisualMax') return [100, 200]
  if (key.endsWith('.colorsText')) return ['#123456,#abcdef', '#fedcba,#654321']
  if (field.control === 'el-switch') return [false, true]
  if (['el-color-picker', 'DesignerColorPicker'].includes(field.control) || /Color$/.test(key)) return ['#123456', '#abcdef']
  const values = field.options.filter(option => !option.condition).map(option => {
    if (/^(true|false)$/.test(option.value)) return option.value === 'true'
    if (/^-?\d+(?:\.\d+)?$/.test(option.value)) return Number(option.value)
    return option.value
  }).filter(value => value !== '' && value != null)
  if (values.length >= 2) return [values[0], values.at(-1)]
  if (['el-input-number', 'el-slider'].includes(field.control)) {
    const min = Number(field.constraints.min)
    const max = Number(field.constraints.max)
    return [Number.isFinite(min) ? min : 1, Number.isFinite(max) ? Math.min(max, (Number.isFinite(min) ? min : 1) + 23) : 23]
  }
  if (key.endsWith('.format')) return ['{b}: {c}', '{c}']
  return ['审计甲', '审计乙']
}

export async function probeChartProperties(audit, runtimeSfc) {
  const harness = await createFunctionHarness(runtimeSfc || await readDashboardSfc(runtimePath))
  const { dashboardComponentCapabilities, dashboardPropertyVisible } = await import('../../src/utils/dashboardComponentCapabilities.js')
  const results = []
  for (const component of audit.components) {
    const capabilities = dashboardComponentCapabilities({ type: component.type })
    const chart = capabilities?.chart ?? /chart$/.test(component.type)
    if (!chart || component.type === 'word-cloud') continue
    const variants = component.type === 'custom-chart' ? [...harness.customChartTypes] : [component.type]
    for (const effectiveType of variants) {
      const widget = chartFixture(harness, component.type)
      widget.style.customChartType = effectiveType
      widget.style.chartConfig.legend.show = true
      widget.style.chartConfig.label.show = true
      widget.style.chartConfig.markLine = { show: true, value: 1000, label: '计划', color: '#123456', lineType: 'dashed' }
      widget.style.chartConfig.xAxis.splitLineShow = true
      widget.style.chartConfig.yAxis.splitLineShow = true
      widget.style.ringLegendShowValue = true
      widget.style.chartConfig.legend.orient = 'vertical'
      if (effectiveType === 'radar') widget.binding.fieldMap.max = 'max'
      for (const field of audit.fields) {
        const key = fieldPath(field)
        if (!field.possibleTypes.includes(component.type) || !key.startsWith('style.chartConfig.')) continue
        const c = dashboardComponentCapabilities(widget)
        if (c && ((key.includes('.legend.') && !c.legend) || (/[xy]Axis/.test(key) && !c.axis) || (key.includes('.grid.') && !c.grid) || (key.includes('.markLine.') && !c.markLine) || (key.includes('.center.') && !c.center) || (key.endsWith('.smooth') && !c.smooth) || (key.endsWith('.stack') && !c.stack) || (key.endsWith('.areaOpacity') && effectiveType !== 'area-chart'))) continue
        if (!dashboardPropertyVisible(widget, key)) continue
        const values = probeValues(field, key)
        const outputs = []
        let failure
        try {
          for (const value of values) {
            const changed = structuredClone(widget)
            setPath(changed, key, value)
            if (key.endsWith('.colorsText')) changed.style.chartConfig.colors = String(value).split(',')
            const option = harness.buildOption(changed, chartRows)
            outputs.push(JSON.stringify(option, (_key, value) => {
              if (typeof value !== 'function') return value
              const examples = [123.45, { name: '九月', value: ['2026-09-01', 123.45], seriesName: '数值', percent: 42 }].map(input => {
                try { return value(input) } catch (error) { return { error: error.message } }
              })
              return { function: String(value), examples }
            }))
          }
        } catch (error) { failure = error.message }
        results.push({ component: component.type, effectiveType, field: field.id, path: key, label: field.label, values, evidence: 'production-buildOption-output', status: failure ? 'error' : outputs[0] !== outputs[1] ? 'changed' : 'unchanged', ...(failure ? { error: failure } : {}) })
      }
    }
  }
  return results
}

export async function probeMapProperties(audit) {
  const { buildDashboardMapOption } = await import('../../src/utils/dashboardMapOptions.js')
  const fixtures = {
    points: [{ label: '一区', value: 40, coordinate: [108, 34], row: { name: '一区', value: 40 } }],
    regions: [{ name: '一区', value: 40 }, { name: '二区', value: 80 }],
    flows: [{ fromName: '一区', toName: '二区', from: [108, 34], to: [109, 35], value: 50, group: '九月', row: { name: '一区' } }],
  }
  const results = []
  for (const component of audit.components.filter(item => item.type.startsWith('map-'))) {
    for (const field of audit.fields) {
      const key = fieldPath(field)
      if (!field.possibleTypes.includes(component.type) || !key.startsWith('style.map') || key === 'style.mapRef') continue
      const widget = { type: component.type, style: { mapAreaGradient: /map(Center|Edge)Color/.test(key), mapShowLabels: true, mapShadowBlur: 10, mapVisualMap: true } }
      const values = probeValues(field, key)
      let error
      let outputs
      try {
        outputs = values.map(value => {
          const changed = structuredClone(widget)
          setPath(changed, key, value)
          return JSON.stringify(buildDashboardMapOption(changed, 'audit-map', fixtures))
        })
      } catch (failure) { error = failure.message }
      results.push({ component: component.type, field: field.id, path: key, label: field.label, values, evidence: 'production-buildDashboardMapOption-output', status: error ? 'error' : outputs[0] !== outputs[1] ? 'changed' : 'unchanged', ...(error ? { error } : {}) })
    }
  }
  return results
}

export async function probeWidgetContainerProperties(audit, runtimeSfc) {
  const harness = await createFunctionHarness(runtimeSfc || await readDashboardSfc(runtimePath))
  const cases = [
    ['layout.x', 0, 80], ['layout.y', 0, 60], ['layout.w', 100, 320], ['layout.h', 80, 160], ['layout.rotate', 0, 45],
    ['style.opacity', 0, 1], ['style.borderRadius', 0, 12],
    ['style.backgroundTransparent', false, true], ['style.backgroundColor', '#123456', '#abcdef'],
    ['style.borderTransparent', false, true], ['style.borderColor', '#123456', '#abcdef'],
    ['style.borderWidth', 0, 4], ['style.borderStyle', 'solid', 'dashed'], ['style.borderOpacity', 0, 1],
    ['style.contentPaddingTop', 0, 18], ['style.contentPaddingRight', 0, 18], ['style.contentPaddingBottom', 0, 18], ['style.contentPaddingLeft', 0, 18],
    ['style.fontSize', 12, 24], ['style.fontWeight', 400, 700], ['style.textAlign', 'left', 'right'], ['style.contentVerticalAlign', 'top', 'bottom'],
    ['style.color', '#123456', '#abcdef'], ['style.useSystemPalette', false, true], ['style.embeddedMode', false, true],
  ]
  const results = []
  for (const component of audit.components) {
    const widget = chartFixture(harness, component.type)
    widget.style.borderWidth = 2
    widget.style.borderColor = '#456789'
    widget.style.backgroundColor = '#203040'
    widget.style.color = '#fedcba'
    for (const [key, first, second] of cases) {
      const fields = audit.fields.filter(field => fieldPath(field) === key && field.possibleTypes.includes(component.type))
      if (!fields.length) continue
      let error
      let outputs
      try {
        outputs = [first, second].map(value => {
          const changed = structuredClone(widget)
          setPath(changed, key, value)
          const containerStyle = harness.widgetStyle(changed)
          // Button appearance is rendered on the real button inside its transparent
          // position container. Audit both production styles, including its border
          // variables, instead of assuming every control paints the outer widget.
          return JSON.stringify(component.type === 'button' ? {
            container: containerStyle,
            button: harness.dashboardButtonStyle(changed.style, harness.dashboardResourceUrl(changed.style.titleImageRef)),
          } : containerStyle)
        })
      } catch (failure) { error = failure.message }
      results.push({ component: component.type, field: fields[0].id, path: key, label: fields[0].label, values: [first, second], evidence: component.type === 'button' ? 'production-widgetStyle-and-dashboardButtonStyle-output-CSS-only' : 'production-widgetStyle-output-CSS-only', status: error ? 'error' : outputs[0] !== outputs[1] ? 'changed' : 'unchanged', ...(error ? { error } : {}) })
    }
  }
  return results
}
