import test from 'node:test'
import assert from 'node:assert/strict'
import { nextTick } from 'vue'
import { createFunctionHarness, designerPath, readDashboardSfc } from './helpers/dashboard-property-audit.mjs'

const designer = await readDashboardSfc(designerPath)

function createClock() {
  let now = 0
  let nextId = 1
  const timers = new Map()
  return {
    now: () => now,
    pending: () => timers.size,
    window: {
      setTimeout(callback, delay) {
        const id = nextId++
        timers.set(id, { callback, due: now + delay })
        return id
      },
      clearTimeout(id) { timers.delete(id) },
    },
    async advance(duration) {
      const target = now + duration
      for (;;) {
        const scheduled = [...timers].sort((a, b) => a[1].due - b[1].due)[0]
        if (!scheduled || scheduled[1].due > target) break
        const [id, timer] = scheduled
        timers.delete(id)
        now = timer.due
        await timer.callback()
      }
      now = target
      await nextTick()
    },
  }
}

function makeWidget(id = 'chart') {
  return {
    id, type: 'bar-chart',
    binding: { sourceType: 'STATIC', fieldMap: { category: 'name', value: 'value' }, staticRows: [{ name: '产值', value: 20 }] },
    style: { color: '#123456', chartConfig: {} },
  }
}

async function createPreview(widgets = [makeWidget()]) {
  const clock = createClock()
  const schema = { canvas: { theme: 'light', palette: 'teal' }, widgets }
  const elements = Object.fromEntries(widgets.map(widget => [widget.id, { id: widget.id, width: 320, height: 200 }]))
  const created = []
  const updates = []
  const harness = await createFunctionHarness(designer, {
    schema, designChartElements: elements, designChartInstances: {}, designRenderTimer: null,
    chartTypes: new Set(['bar-chart', 'line-chart', 'word-cloud']),
    paletteOptions: [{ key: 'teal', colors: ['#35d4b0', '#5b8ff9'] }],
    nextTick, window: clock.window,
  })
  // Keep the real scheduler, renderer and option builder; only the canvas-backed
  // ECharts lifecycle is replaced so event timing and instance ownership are deterministic.
  harness.echarts = {
    init(element, theme, options) {
      const chart = {
        element, theme, options, disposed: false, disposeCalls: 0, updates: [], sizes: [],
        isDisposed() { return this.disposed },
        getDom() { return this.element },
        dispose() { this.disposed = true; this.disposeCalls += 1 },
        setOption(option, notMerge) {
          assert.equal(this.disposed, false, '不得更新已销毁的图表')
          const update = { time: clock.now(), option, notMerge, chart: this }
          this.updates.push(update)
          updates.push(update)
        },
        resize() { this.sizes.push([this.element.width, this.element.height]) },
      }
      created.push(chart)
      return chart
    },
  }
  return { harness, clock, schema, elements, created, updates }
}

test('持续拖色时每个已排队的帧仍会渲染，未停止输入就能看到最新颜色', async () => {
  const preview = await createPreview()
  const { harness, clock, schema, updates } = preview
  const widget = schema.widgets[0]
  for (let step = 0; step < 16; step += 1) {
    widget.style.color = `#${(0x100000 + step).toString(16)}`
    harness.scheduleDesignChartRender()
    assert.equal(clock.pending(), 1, '一帧内多次输入合并成一个待执行任务')
    await clock.advance(4)
    if (step === 3) {
      assert.equal(updates.length, 1, '输入仍每 4ms 发生时，首个 16ms 帧不能被推迟')
      assert.equal(updates[0].option.series[0].itemStyle.color, '#100003')
    }
  }
  assert.deepEqual(updates.map(update => update.time), [16, 32, 48, 64])
  assert.equal(updates.at(-1).option.series[0].itemStyle.color, '#10000f')
  assert.equal(preview.created.length, 1, '连续帧复用同一画布的图表实例')
  assert.equal(clock.pending(), 0)
  widget.style.color = '#abcdef'
  harness.scheduleDesignChartRender()
  await clock.advance(16)
  assert.equal(updates.at(-1).option.series[0].itemStyle.color, '#abcdef', '后续拖动可继续排帧')
})

test('实时颜色更新复用实例、替换完整选项并立即适配画布尺寸，禁用重复入场动画', async () => {
  const { harness, schema, elements, created, updates } = await createPreview()
  harness.renderDesignCharts()
  const chart = created[0]
  schema.widgets[0].style.color = '#abcdef'
  schema.widgets[0].style.chartConfig.label = { show: true, color: '#ee5577' }
  elements.chart.width = 640
  elements.chart.height = 360
  harness.renderDesignCharts()
  assert.equal(created.length, 1)
  assert.equal(harness.designChartInstances.chart, chart)
  assert.equal(chart.disposeCalls, 0)
  assert.equal(updates[1].option.series[0].itemStyle.color, '#abcdef')
  assert.equal(updates[1].option.series[0].label.color, '#ee5577')
  assert.ok(updates.every(update => update.notMerge === true), '保持完整选项替换，避免旧系列配置残留')
  assert.ok(updates.every(update => update.option.animation === false), '设计画布不能每次拖色都重播入场动画')
  assert.deepEqual(chart.sizes, [[320, 200], [640, 360]])
})

test('图表画布替换或实例已销毁时重新初始化，旧实例不再接受更新', async () => {
  const { harness, elements, created } = await createPreview()
  harness.renderDesignCharts()
  const first = created[0]
  elements.chart = { id: 'replacement', width: 500, height: 300 }
  harness.renderDesignCharts()
  assert.equal(first.disposeCalls, 1)
  assert.equal(first.updates.length, 1)
  assert.equal(created.length, 2)
  assert.equal(harness.designChartInstances.chart, created[1])
  assert.equal(created[1].getDom(), elements.chart)
  created[1].dispose()
  harness.renderDesignCharts()
  assert.equal(created.length, 3)
  assert.equal(created[1].updates.length, 1)
  assert.equal(harness.designChartInstances.chart, created[2])
  assert.equal(created[2].disposed, false)
})

test('移除或改成非图表的组件会销毁实例，文字云及无画布组件不会被初始化', async () => {
  const { harness, schema, elements, created } = await createPreview([makeWidget('removed'), makeWidget('changed'), makeWidget('kept')])
  harness.renderDesignCharts()
  const [removed, changed, kept] = created
  schema.widgets = schema.widgets.filter(widget => widget.id !== 'removed')
  schema.widgets.find(widget => widget.id === 'changed').type = 'text'
  schema.widgets.push({ ...makeWidget('cloud'), type: 'word-cloud' }, makeWidget('missing'))
  elements.cloud = { width: 320, height: 200 }
  harness.renderDesignCharts()
  assert.equal(removed.disposeCalls, 1)
  assert.equal(changed.disposeCalls, 1)
  assert.equal(kept.disposeCalls, 0)
  assert.equal(kept.updates.length, 2)
  assert.equal(created.length, 3)
  assert.deepEqual(Object.keys(harness.designChartInstances), ['kept'])
  harness.disposeDesignCharts()
  assert.equal(kept.disposeCalls, 1)
  assert.equal(Object.keys(harness.designChartInstances).length, 0)
})

test('直接渲染会清理待执行帧，避免过期任务重复刷新', async () => {
  const { harness, clock, updates } = await createPreview()
  harness.scheduleDesignChartRender()
  assert.equal(clock.pending(), 1)
  harness.renderDesignCharts()
  assert.equal(clock.pending(), 0)
  await clock.advance(32)
  assert.equal(updates.length, 1)
  harness.scheduleDesignChartRender()
  await clock.advance(16)
  assert.equal(updates.length, 2)
})
