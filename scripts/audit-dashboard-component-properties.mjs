#!/usr/bin/env node
import fs from 'node:fs/promises'
import path from 'node:path'
import { createHash } from 'node:crypto'
import {
  auditInspector, designerPath, runtimePath, projectRoot, fieldPath,
  probeChartProperties, probeMapProperties, probeWidgetContainerProperties,
} from '../ruoyi-ui/tests/helpers/dashboard-property-audit.mjs'

const audit = await auditInspector()
const [charts, maps, containers] = await Promise.all([
  probeChartProperties(audit), probeMapProperties(audit), probeWidgetContainerProperties(audit),
])
const probes = [...charts, ...maps, ...containers]
const outputDirectory = path.join(projectRoot, 'output/component-property-audit')
const relative = filename => path.relative(projectRoot, filename).split(path.sep).join('/')
const escape = value => String(value ?? '').replaceAll('|', '\\|').replaceAll('\n', ' ')

async function sourceFiles(directory) {
  const entries = await fs.readdir(directory, { withFileTypes: true })
  const result = []
  for (const entry of entries) {
    const filename = path.join(directory, entry.name)
    if (entry.isDirectory()) result.push(...await sourceFiles(filename))
    else if (/\.(js|vue)$/.test(entry.name)) result.push(filename)
  }
  return result
}
const consumerPaths = [runtimePath,
  ...(await sourceFiles(path.join(projectRoot, 'ruoyi-ui/src/components'))).filter(filename => /\/Dashboard[^/]*\//.test(filename)),
  ...(await sourceFiles(path.join(projectRoot, 'ruoyi-ui/src/utils'))).filter(filename => /\/dashboard[^/]*\.js$/.test(filename) && !/Capabilities/.test(filename)),
]
const consumers = await Promise.all(consumerPaths.map(async filename => ({ filename, lines: (await fs.readFile(filename, 'utf8')).split('\n') })))
for (const field of audit.fields) {
  const property = fieldPath(field)
  const editorTargets = { 'style.tabsJson': 'style.tabs', 'style.formFieldsJson': 'style.formFields' }
  const target = editorTargets[property] || property
  if (target !== property) field.editorConversion = { target, handler: field.events.find(event => event.event === 'change')?.handler, verification: 'dashboard-property-behavior.test.mjs JSON editor conversion test' }
  const key = target.split('.').at(-1)
  const matches = []
  if (key) {
    const expression = new RegExp(`(?:\\?\\.|\\.)${key}\\b|["']${key}["']`)
    for (const source of consumers) {
      source.lines.forEach((line, index) => {
        if (expression.test(line)) matches.push({ file: relative(source.filename), line: index + 1 })
      })
    }
  }
  field.sourceReferenceCandidates = matches.slice(0, 6)
  field.sourceReferenceBoundary = '同名属性引用候选；可能位于兼容归一化或非当前组件分支，不能单独证明生效。'
  field.functionProbes = probes.filter(probe => probe.field === field.id).map(probe => ({ component: probe.component, effectiveType: probe.effectiveType, evidence: probe.evidence, status: probe.status }))
  field.browserStatus = '由浏览器报告另行记录，静态矩阵不自动标记通过'
}
audit.sourceHashes = Object.fromEntries(await Promise.all([designerPath, runtimePath, ...consumerPaths.slice(1)].map(async filename => [relative(filename), createHash('sha256').update(await fs.readFile(filename)).digest('hex')])))
audit.probeSummary = { chart: charts.length, map: maps.length, container: containers.length, changed: probes.filter(probe => probe.status === 'changed').length, unchanged: probes.filter(probe => probe.status === 'unchanged').length, error: probes.filter(probe => probe.status === 'error').length }
audit.probeSummary.distinctControlDefinitions = audit.fields.filter(field => field.functionProbes.length).length

await fs.mkdir(outputDirectory, { recursive: true })
await fs.writeFile(path.join(outputDirectory, 'attribute-matrix.json'), JSON.stringify(audit, null, 2) + '\n')
await fs.writeFile(path.join(outputDirectory, 'behavior-probes.json'), JSON.stringify({ generatedAt: audit.generatedAt, evidenceBoundary: audit.evidenceBoundary, summary: audit.probeSummary, probes }, null, 2) + '\n')

const typeLabels = Object.fromEntries(audit.components.map(component => [component.type, component.label]))
const lines = [
  '# 组件属性逐项覆盖矩阵', '',
  '本文件由 `node scripts/audit-dashboard-component-properties.mjs` 从当前 Vue 模板 AST 自动生成；不要手工维护属性名单。', '',
  `盘点 ${audit.components.length} 类组件、${audit.fields.length} 个控件/操作定义。控件含条件分支、只读组件类型、重复资源编辑入口及数组项编辑器；不能将定义数理解成每个组件都显示这么多项。`, '',
  '“可适用组件”是满足类型条件的上界；数据源、开关、文本是否填写等动态条件仍须满足。通用图表子类型可能不同。完整条件、枚举选项、约束、事件、字段映射、源码哈希和逐用例结果保存在 `output/component-property-audit/attribute-matrix.json` 与 `behavior-probes.json`。', '',
  '“函数探针”只证明实际生产函数输出发生变化；CSS 输出、图表 option 输出不能替代浏览器视觉、保存/加载、数据服务权限和点击动作验收。', '',
  '## 按组件查找', '',
  '| 类别 | 组件 | 编码 | 可适用控件定义 | 字段映射 | 函数变更用例 |',
  '| --- | --- | --- | ---: | --- | ---: |',
  ...audit.components.map(component => `| ${component.group} | ${component.label} | \`${component.type}\` | ${component.controls.length} | ${component.fieldRoles.map(role => `${role.label}（${role.key}）`).join('、') || '无'} | ${probes.filter(probe => probe.component === component.type).length} |`),
]
for (const tab of ['基础', '数据', '样式', '交互']) {
  lines.push('', `## ${tab}`, '', '| 编号 / 源码行 | 分组 / 属性 | 绑定或动作 | 控件 | 可适用组件 | 函数探针 |', '| --- | --- | --- | --- | --- | --- |')
  for (const field of audit.fields.filter(field => field.tab === tab)) {
    const types = field.possibleTypes.length === audit.components.length ? '全部组件' : field.possibleTypes.map(type => typeLabels[type]).join('、')
    const source = `../../ruoyi-ui/src/views/dashboard/designer/index.vue#L${field.line}`
    const evidence = field.functionProbes.length ? `${field.functionProbes.filter(probe => probe.status === 'changed').length}/${field.functionProbes.length} 输出变化` : '未由本探针覆盖'
    lines.push(`| [${field.id} / ${field.line}](${source}) | ${escape(field.section)} / ${escape(field.label)} | \`${escape(field.binding || field.events.map(event => `${event.event}: ${event.handler}`).join('; '))}\` | ${field.control} | ${escape(types)} | ${evidence} |`)
  }
}
await fs.writeFile(path.join(outputDirectory, '组件属性逐项覆盖矩阵.md'), lines.join('\n') + '\n')

if (process.argv.includes('--report')) {
  const report = [
    '# 自动审计摘要', '',
    `生成时间：${audit.generatedAt}。`, '',
    '| 检查 | 结果 |',
    '| --- | --- |',
    `| 模板 AST | ${audit.components.length} 类组件，${audit.fields.length} 个控件/动作定义 |`,
    `| 函数探针 | 图表 ${charts.length} 项、地图 ${maps.length} 项、容器 ${containers.length} 项 |`,
    `| 探针结果 | ${audit.probeSummary.changed} 项输出变化、${audit.probeSummary.unchanged} 项未变化、${audit.probeSummary.error} 项异常 |`,
    `| 探针覆盖的控件定义 | ${audit.probeSummary.distinctControlDefinitions}/${audit.fields.length} |`, '',
    '本脚本只执行结构审计和生产函数探针，不执行浏览器、保存发布、接口权限、数据库或构建验收；输出变化不等于视觉效果和业务链路已通过。', '',
    '- [组件属性逐项覆盖矩阵](组件属性逐项覆盖矩阵.md)',
    '- [完整属性清单与源码哈希](attribute-matrix.json)',
    '- [逐用例结果](behavior-probes.json)',
    '- [测试与已知限制](../../docs/测试与已知限制.md)', '',
    '重新执行 `node scripts/audit-dashboard-component-properties.mjs --report` 会更新本目录的自动产物，人工验收记录保留在项目文档中。',
  ]
  await fs.writeFile(path.join(outputDirectory, '自动审计摘要.md'), report.join('\n') + '\n')
}
console.log(JSON.stringify({ components: audit.components.length, controls: audit.fields.length, componentControlPairs: audit.components.reduce((sum, component) => sum + component.controls.length, 0), ...audit.probeSummary, output: relative(outputDirectory) }, null, 2))
if (audit.probeSummary.unchanged || audit.probeSummary.error) process.exitCode = 1
