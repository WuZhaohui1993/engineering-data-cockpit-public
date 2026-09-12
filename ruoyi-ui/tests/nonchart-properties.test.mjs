import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import { createRequire } from 'node:module'
import { pathToFileURL, fileURLToPath } from 'node:url'
import { formatDashboardMetricDisplay } from '../src/utils/dashboardMetric.js'
import { dashboardButtonStyle, dashboardCarouselRows, dashboardFormOptions, dashboardFormInputType, dashboardHeadingHeight, dashboardRingTextStyle, dashboardRingTextItemStyle } from '../src/utils/dashboardPresentation.js'
import { buildGanttModel } from '../src/utils/dashboardGantt.js'

const require = createRequire(import.meta.url)
const vueRequire = createRequire(require.resolve('vue'))
const { parse, compileScript, compileTemplate } = vueRequire('@vue/compiler-sfc')
const { createSSRApp } = require('vue')
const { renderToString } = vueRequire('@vue/server-renderer')

async function renderComponent(name, props) {
  const source = await fs.readFile(new URL(`../src/components/${name}/index.vue`, import.meta.url), 'utf8')
  const { descriptor, errors } = parse(source)
  assert.deepEqual(errors, [])
  const script = compileScript(descriptor, { id: name, inlineTemplate: true })
  const moduleCode = script.content.replace(/from (['"])@\/([^'"]+)\1/g, (_, quote, path) => `from ${quote}${new URL(`../src/${path}.js`, import.meta.url).href}${quote}`)
  const temporary = new URL(`.nonchart-${process.pid}-${name}.mjs`, import.meta.url)
  await fs.writeFile(temporary, moduleCode)
  try {
    const { default: component } = await import(`${temporary.href}?t=${Date.now()}`)
    return await renderToString(createSSRApp(component, props))
  } finally { await fs.unlink(temporary) }
}

test('metric scale and decimals preserve precision, missing values and non-numeric text', () => {
  assert.equal(formatDashboardMetricDisplay(12345.67891, { valueDecimalPlaces: 5, chartConfig: { valueScale: 'thousand', valuePrecision: 2 } }), '12.34568千')
  assert.equal(formatDashboardMetricDisplay(25000, { valueDecimalPlaces: 0, chartConfig: { valueScale: 'ten-thousand' } }), '3万')
  assert.equal(formatDashboardMetricDisplay(1200000, { valueDecimalPlaces: 2, chartConfig: { valueScale: 'million' } }), '1.20百万')
  for (const value of [null, undefined, '', ' ', false, '未接入']) assert.equal(formatDashboardMetricDisplay(value, { valueDecimalPlaces: 2 }), value)
  assert.equal(formatDashboardMetricDisplay(3.14159, {}), '3.14159')
})

test('button shape works without fill and respects transparent background and inherited border', () => {
  const shape = dashboardButtonStyle({ buttonShape: 'angled', borderRadius: 0, backgroundTransparent: true })
  assert.match(shape.clipPath, /^polygon/)
  assert.equal(shape.width, undefined)
  assert.equal(shape.background, 'transparent')
  assert.equal(shape.borderColor, 'var(--widget-border-color)')
  const fill = dashboardButtonStyle({ buttonFill: true, backgroundColor: '#abcdef', fontWeight: 700 })
  assert.equal(fill.width, '100%')
  assert.equal(fill.boxSizing, 'border-box')
  assert.equal(fill.background, '#abcdef')
  assert.equal(fill.fontWeight, 700)
})

test('ring speed, direction, radius and tilt have explicit geometry and pause semantics', () => {
  const style = dashboardRingTextStyle({ ringTextRadius: 20, ringTextSpeed: 0, ringTextDirection: 'reverse', ringTextTilt: -30 })
  assert.equal(style['--ring-inset'], '30%')
  assert.equal(style['--ring-play-state'], 'paused')
  assert.equal(style['--ring-direction'], 'reverse')
  assert.equal(style['--ring-tilt'], '-30deg')
  assert.equal(dashboardRingTextStyle({ ringTextSpeed: 120 })['--ring-duration'], '1s')
  assert.deepEqual(dashboardRingTextItemStyle(0, 4), { left: '50%', top: '0%' })
  assert.deepEqual(dashboardRingTextItemStyle(1, 4), { left: '100%', top: '50%' })
})

test('heading height accounts for large text, subtitles and vertical padding', () => {
  assert.equal(dashboardHeadingHeight({}), 25)
  assert.equal(dashboardHeadingHeight({ titleFontSize: 40, subtitle: '说明', subtitleFontSize: 16, titlePaddingTop: 8, titlePaddingBottom: 9 }), 89)
  assert.equal(dashboardHeadingHeight({ titleImageHeight: 56, titleFontSize: 40 }, true), 56)
})

test('form options retain explicit empty and zero values and render each configured native input type', () => {
  assert.deepEqual(dashboardFormOptions({ options: [{ label: '全部', value: '' }, { label: '零', value: 0 }, false, 'A'] }), [{ label: '全部', value: '' }, { label: '零', value: '0' }, { label: 'false', value: 'false' }, { label: 'A', value: 'A' }])
  assert.deepEqual(dashboardFormOptions({ options: 'broken' }), [])
  assert.deepEqual(['STRING', 'NUMBER', 'DATE', 'DATETIME'].map(type => dashboardFormInputType({ type })), ['text', 'number', 'date', 'datetime-local'])
})

test('carousel rotates across all source rows, wraps once and never duplicates a short list', () => {
  const rows = ['A', 'B', 'C', 'D']
  assert.deepEqual(dashboardCarouselRows(rows, 2, 3), ['D', 'A'])
  assert.deepEqual(dashboardCarouselRows(rows, 8, 0), rows)
  assert.deepEqual(dashboardCarouselRows(rows, 2, -1), ['D', 'A'])
  assert.deepEqual(dashboardCarouselRows([], 3, 2), [])
  assert.deepEqual(rows, ['A', 'B', 'C', 'D'])
})

test('gantt configured width, date range, current line and dependencies affect the model', () => {
  const rows = [{ id: '1', name: 'A', plannedStart: '2026-01-01', plannedEnd: '2026-02-01' }, { id: '2', name: 'B', plannedStart: '2026-02-01', plannedEnd: '2026-03-01', dependencies: '1' }]
  const model = buildGanttModel(rows, {}, { ganttLabelWidth: 300, ganttStart: '2026-01-01', ganttEnd: '2026-03-01', ganttCurrentDate: '2026-02-01' }, 600)
  assert.equal(model.labelWidth, 300)
  assert.equal(model.links.length, 1)
  assert.ok(model.currentX > model.labelWidth)
  assert.ok(model.tasks[0].plan.w > 0)
})

test('gantt reads mapped progress and status, preserving 0/100 and rejecting invalid percentages', () => {
  const values = [0, 100, ' 62.5 ', null, undefined, '', ' ', -1, 101, '进行中', false, [], NaN, Infinity]
  const rows = values.map((value, index) => ({ name: `任务${index}`, completion: value, taskState: ' 进行中 ', progress: 88, status: '错误字段' }))
  const model = buildGanttModel(rows, { progress: 'completion', status: 'taskState' })
  assert.deepEqual(model.tasks.map(task => task.progress), [0, 100, 62.5, ...Array(11).fill(null)])
  assert.ok(model.tasks.every(task => task.status === '进行中'))
  assert.equal(buildGanttModel([{ name: '默认字段', progress: 25, status: '未开始' }]).tasks[0].progress, 25)
  assert.equal(buildGanttModel([{ name: '缺少字段' }]).tasks[0].status, '')
})

test('SSR: gantt task tooltip exposes mapped name, plan/actual dates, status and completion', async () => {
  const html = await renderComponent('DashboardGantt', {
    rows: [{ title: '基础施工', planA: '2026-01-01', planB: '2026-02-01', actualA: '2026-01-02', actualB: '2026-01-20', completion: 0, taskState: '未开始' }, { title: '设备安装', completion: 62.5, taskState: '进行中' }],
    fieldMap: { task: 'title', start: 'planA', end: 'planB', actualStart: 'actualA', actualEnd: 'actualB', progress: 'completion', status: 'taskState' },
  })
  const titles = [...html.matchAll(/<title>(.*?)<\/title>/g)].map(match => match[1])
  assert.equal(titles[0], '基础施工：计划 2026-01-01 至 2026-02-01；实际 2026-01-02 至 2026-01-20；状态 未开始；完成进度 0%')
  assert.equal(titles[1], '设备安装：计划 — 至 —；实际 — 至 —；状态 进行中；完成进度 62.5%')
})

test('SSR: ring orbit visibility does not hide labels, carousel flags and all selected columns render', async () => {
  const ring = await renderComponent('DashboardRingText', { items: ['安全', '质量'], options: { ringTextShowOrbit: false, ringTextGradient: true, ringTextCenterText: '项目', ringTextSpeed: 0 } })
  assert.match(ring, /orbit-hidden/); assert.match(ring, /gradient/); assert.match(ring, /安全/); assert.match(ring, /质量/); assert.match(ring, /项目/); assert.match(ring, /--ring-play-state:paused/)
  const columns = ['a', 'b', 'c', 'd'].map(name => ({ name }))
  const cards = await renderComponent('DashboardCarousel', { rows: [{ a: '甲', b: 2, c: 3, d: '第四列' }], columns, options: { carouselDirection: 'vertical', carouselShowIndex: true, carouselHighlight: true } })
  assert.match(cards, /vertical/); assert.match(cards, /highlighted/); assert.match(cards, /<b>1<\/b>/); assert.match(cards, /第四列/)
})

test('SSR: radio fields use radio inputs, empty select labels survive, and tabs show the selected content', async () => {
  const form = await renderComponent('DashboardFilterForm', { fields: [
    { name: 'kind', label: '类别', type: 'RADIO', options: [{ label: '零', value: 0 }, { label: '一', value: 1 }] },
    { name: 'state', label: '状态', type: 'SELECT', options: [{ label: '全部', value: '' }] },
    { name: 'when', type: 'DATETIME' },
  ], modelValue: { kind: '0' } })
  assert.equal((form.match(/type="radio"/g) || []).length, 2)
  assert.match(form, /value="0" checked/); assert.match(form, /全部/); assert.match(form, /datetime-local/)
  const tabs = await renderComponent('DashboardTabs', { tabs: [{ label: '一', content: '内容一' }, { label: '二', content: '内容二' }], modelValue: 1 })
  assert.match(tabs, /内容二/); assert.doesNotMatch(tabs, /内容一/)
})

test('all modified dashboard SFC templates compile with their actual script bindings', async () => {
  for (const path of ['views/dashboard/designer/index.vue', 'views/dashboard/runtime/index.vue', ...['DashboardRingText', 'DashboardCarousel', 'DashboardFilterForm', 'DashboardTabs', 'DashboardGantt'].map(name => `components/${name}/index.vue`)]) {
    const filename = fileURLToPath(new URL(`../src/${path}`, import.meta.url))
    const { descriptor, errors } = parse(await fs.readFile(filename, 'utf8'), { filename })
    assert.deepEqual(errors, [])
    const script = compileScript(descriptor, { id: path })
    const template = compileTemplate({ source: descriptor.template.content, filename, id: path, compilerOptions: { bindingMetadata: script.bindings } })
    assert.deepEqual(template.errors, [], path)
  }
})
