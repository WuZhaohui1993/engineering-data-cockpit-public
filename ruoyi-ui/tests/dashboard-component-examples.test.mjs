import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import { componentCatalog, createFunctionHarness, readDashboardSfc, designerPath, runtimePath } from './helpers/dashboard-property-audit.mjs'
import { dashboardComponentCapabilities } from '../src/utils/dashboardComponentCapabilities.js'
import { dashboardComponentExampleTypes, getDashboardComponentExample } from '../src/utils/dashboardComponentExamples.js'
import { buildGanttModel, ganttDay } from '../src/utils/dashboardGantt.js'
import { dashboardMapData, buildDashboardMapOption } from '../src/utils/dashboardMapOptions.js'
import { dashboardTableScrollMetrics } from '../src/utils/dashboardTableScroll.js'

const catalog = componentCatalog(await readDashboardSfc(designerPath))
const runtime = await createFunctionHarness(await readDashboardSfc(runtimePath), { carouselOffsets: {} })
const sample = type => getDashboardComponentExample({ type })
const fixture = type => {
  const example = sample(type)
  const widget = runtime.normalizeSchema({ widgets: [{ id: type, type, binding: {
    sourceType: 'STATIC', staticRows: example.rows, fieldMap: example.fieldMap, displayFields: example.displayFields,
  }, style: example.mapRef ? { mapRef: example.mapRef } : {} }] }).widgets[0]
  runtime.widgetStates[type] = { rows: example.rows, quality: 'SUCCESS' }
  return { example, widget }
}

test('所有真实组件均有独立说明：53 类，39 类支持数据，14 类使用本地配置', () => {
  assert.deepEqual([...dashboardComponentExampleTypes].sort(), catalog.map(item => item.type).sort())
  assert.equal(catalog.length, 53)
  let dataCount = 0
  for (const { type, label } of catalog) {
    const example = sample(type)
    assert.equal(example.label, label)
    assert.ok(example.purpose && example.source && example.notes.length, type)
    assert.equal(example.data, dashboardComponentCapabilities({ type }).data)
    if (example.data) { dataCount++; assert.ok(example.rows.length > 0, type) }
    else { assert.equal(example.rows.length, 0); assert.equal(example.fields.length, 0) }
  }
  assert.equal(dataCount, 39)
  assert.equal(sample('unknown-component'), null)
})

test('标准样例可直接作为静态数组，所有映射和展示字段都存在且为基础值', () => {
  for (const { type } of catalog) {
    const example = sample(type)
    if (!example.data) continue
    const copy = JSON.parse(JSON.stringify(example.rows))
    assert.ok(Array.isArray(copy), type)
    assert.ok(copy.length <= 1000)
    const mappedFields = Object.values(example.fieldMap).flat()
    for (const row of copy) {
      assert.ok(row && typeof row === 'object' && !Array.isArray(row))
      for (const value of Object.values(row)) assert.ok(value === null || ['string', 'number', 'boolean'].includes(typeof value), type)
      for (const key of [...mappedFields, ...example.displayFields]) assert.ok(Object.hasOwn(row, key), `${type}: ${key}`)
    }
    for (const field of example.fields) {
      assert.ok(Object.hasOwn(copy[0], field.field))
      if (field.type === 'number') assert.ok(copy.every(row => typeof row[field.field] === 'number' && Number.isFinite(row[field.field])))
      if (field.type === 'date') assert.ok(copy.every(row => ganttDay(row[field.field]) !== null))
    }
  }
})

test('通用图表样例跟随真实有效子类型，未知子类型回退柱状图', () => {
  for (const type of runtime.customChartTypes) {
    const guide = getDashboardComponentExample({ type: 'custom-chart', style: { customChartType: type } })
    const expected = sample(type)
    assert.equal(guide.effectiveType, type)
    assert.deepEqual(guide.rows, expected.rows)
    assert.deepEqual(guide.fieldMap, expected.fieldMap)
    assert.ok(guide.title.includes(expected.label))
  }
  assert.equal(getDashboardComponentExample({ type: 'custom-chart', style: { customChartType: 'unsupported' } }).effectiveType, 'bar-chart')
})

test('样例对象彼此独立，读取说明不修改组件或后续样例', () => {
  const widget = { type: 'line-chart', binding: { datasetCode: 'business-data' } }
  const before = JSON.stringify(widget)
  const first = getDashboardComponentExample(widget)
  first.rows[0].value = -1
  first.fieldMap.value = 'unknown'
  first.notes.push('modified')
  assert.equal(JSON.stringify(widget), before)
  assert.equal(sample('line-chart').rows[0].value, 86)
  assert.equal(sample('bar-chart').fieldMap.value, 'value')
  assert.equal(sample('line-chart').notes.includes('modified'), false)
})

test('甘特样例通过真实日期与依赖绘制模型，里程碑状态有明确显示', () => {
  const { rows, fieldMap } = sample('gantt-chart')
  const model = buildGanttModel(rows, fieldMap)
  assert.equal(model.tasks.length, 2)
  assert.ok(model.tasks.every(task => task.plan && task.actual && task.plan.w > 0 && task.actual.w > 0))
  assert.equal(model.links.length, 1)
  assert.equal(model.links[0].key, 'T1-T2')
  assert.equal(model.tasks[1].progress, 60)
  const { widget } = fixture('milestone-timeline')
  const milestones = runtime.milestoneRows(widget)
  assert.equal(milestones[0].done, true)
  assert.equal(milestones[1].active, true)
})

test('地图样例名称与实际内置底图对应，坐标位于所属区域，时间轴含多个组', async () => {
  const geo = JSON.parse(await fs.readFile(new URL('../../ruoyi-backend/ruoyi-admin/src/main/resources/dashboard/maps/demo-region.json', import.meta.url), 'utf8'))
  const regions = new Map(geo.features.map(feature => [feature.properties.name, feature.geometry.coordinates[0]]))
  const inside = (name, longitude, latitude) => {
    const boundary = regions.get(name)
    assert.ok(boundary, name)
    const xs = boundary.map(point => point[0]), ys = boundary.map(point => point[1])
    assert.ok(longitude > Math.min(...xs) && longitude < Math.max(...xs))
    assert.ok(latitude > Math.min(...ys) && latitude < Math.max(...ys))
  }
  for (const type of ['map-chart', 'map-bar', 'map-heat', 'map-ranking', 'map-flow', 'map-timeline']) {
    const { example, widget } = fixture(type)
    const data = dashboardMapData(widget, example.rows)
    assert.equal(example.mapRef, '/dashboard/assets/map/demo-region.json')
    for (const row of example.rows) {
      if (row.fromName) { inside(row.fromName, row.fromLongitude, row.fromLatitude); inside(row.toName, row.toLongitude, row.toLatitude) }
      else inside(row.label, row.longitude, row.latitude)
    }
    if (type === 'map-timeline') {
      const option = buildDashboardMapOption(widget, 'example-map', data)
      assert.equal(option.options.length, 2)
    }
  }
})

test('散点和日历样例进入真实图表选项，数值与日期保持正确', () => {
  const scatter = fixture('scatter')
  const points = runtime.buildOption(scatter.widget, scatter.example.rows).series[0].data
  assert.equal(points[0][0], 10)
  assert.equal(points[0][1], 30)
  const calendar = fixture('calendar-chart')
  const option = runtime.buildOption(calendar.widget, calendar.example.rows)
  assert.equal(option.series[0].data[0].value[0], '2026-09-05')
  assert.equal(option.series[0].data[0].value[1], 28)
  assert.equal(sample('radar').rows.length >= 3, true)
})

test('表格样例足以滚动，默认排名使用数值第二字段，图文样例映射有内容', () => {
  const { widget, example } = fixture('rank-table')
  assert.equal(Object.keys(example.rows[0])[1], 'score')
  runtime.widgetStates[widget.id].rows = [...example.rows].reverse()
  const ranked = runtime.widgetRows(widget)
  assert.equal(ranked[0].score, 96)
  assert.equal(ranked.at(-1).score, 68)
  const table = sample('table')
  assert.equal(dashboardTableScrollMetrics({ contentHeight: table.rows.length * 34, viewportHeight: 6 * 34, rowCount: table.rows.length, secondsPerRow: 2 }).overflow, true)
  const access = fixture('access-list')
  assert.equal(runtime.accessListValue(access.widget, access.example.rows[0], 'title'), '张某')
  assert.equal(runtime.accessStatusSuccess(access.widget, access.example.rows[0]), true)
  assert.equal(runtime.accessStatusSuccess(access.widget, access.example.rows[1]), false)
})

test('实时样例和自定义内容说明区分展示数组与真实订阅/沙箱数据协议', () => {
  assert.match(sample('realtime-list').notes.join(' '), /WEBSOCKET/)
  assert.match(sample('realtime-list').notes.join(' '), /不能证明/)
  assert.match(sample('custom-html').notes.join(' '), /JLink\.onData/)
  assert.equal(sample('custom-html').fields.length, 2)
  assert.match(sample('custom-html').configuration, /项目运行正常/)
})
