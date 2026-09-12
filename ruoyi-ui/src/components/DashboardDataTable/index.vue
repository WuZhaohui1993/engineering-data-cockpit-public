<template>
  <div class="dashboard-data-table" :class="{ 'is-scrolling': scrolling }"
    @mouseenter="hovered = true" @mouseleave="hovered = false"
    @focusin="focused = true" @focusout="onFocusOut">
    <table ref="header" class="header-table" :style="{ width: tableWidth ? `${tableWidth}px` : '100%' }">
      <colgroup v-if="columnWidths.length"><col v-for="(width, index) in columnWidths" :key="index" :style="{ width: `${width}px` }" /></colgroup>
      <thead><tr><th v-for="column in columns" :key="column.name" scope="col">
        <button v-if="interactive && column.sortable" type="button" class="sort-button" @click.stop="$emit('sort', column)">
          {{ column.title || column.name }}<span v-if="sortState?.field === column.name">{{ sortState.direction === 'asc' ? ' ↑' : ' ↓' }}</span>
        </button>
        <template v-else>{{ column.title || column.name }}</template>
      </th></tr></thead>
    </table>
    <div ref="viewport" class="table-body-viewport">
      <div ref="track" class="table-body-track">
        <table v-for="copy in scrolling ? 2 : 1" :key="copy" :ref="el => { if (copy === 1) primary = el }"
          class="body-table" :aria-hidden="copy === 2 ? 'true' : undefined">
          <!-- Collapsed header contributes to native table column sizing without taking body height. -->
          <thead class="measuring-header" aria-hidden="true"><tr><th v-for="column in columns" :key="column.name">{{ column.title || column.name }}</th></tr></thead>
          <tbody><tr v-for="(row, index) in rows" :key="`${copy}-${index}`" @click.stop="interactive && $emit('row-click', row)">
            <td v-for="column in columns" :key="column.name">{{ formatValue(row[column.name], column) }}</td>
          </tr></tbody>
        </table>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { dashboardTableScrollMetrics } from '@/utils/dashboardTableScroll'

const props = defineProps({
  rows: { type: Array, default: () => [] },
  columns: { type: Array, default: () => [] },
  autoScroll: { type: Boolean, default: false },
  secondsPerRow: { type: [Number, String], default: 5 },
  sortState: { type: Object, default: () => ({}) },
  formatValue: { type: Function, default: value => value == null ? '—' : String(value) },
  interactive: { type: Boolean, default: true },
})
defineEmits(['sort', 'row-click'])
const header = ref(), viewport = ref(), track = ref(), primary = ref()
const scrolling = ref(false), hovered = ref(false), focused = ref(false)
const columnWidths = ref([]), tableWidth = ref(0)
const paused = computed(() => hovered.value || focused.value)
// Parent clock ticks and equal dataset refreshes may create new arrays. Compare
// their values so an unchanged table never restarts its animation each second.
const contentKey = computed(() => JSON.stringify([props.columns, props.rows]))
let animation, resizeObserver, frame = 0, disposed = false, resetPending = false
let lastDistance = 0, lastDuration = 0

function stop() {
  animation?.cancel()
  animation = undefined
  lastDistance = 0
  lastDuration = 0
}
function syncPaused() {
  if (!animation) return
  if (paused.value) animation.pause()
  else animation.play()
}
function onFocusOut(event) {
  focused.value = Boolean(event.currentTarget.contains(event.relatedTarget))
}
function queueMeasure(reset = false) {
  resetPending ||= reset
  if (frame || disposed) return
  frame = requestAnimationFrame(() => {
    frame = 0
    const shouldReset = resetPending
    resetPending = false
    measure(shouldReset)
  })
}
async function measure(reset) {
  if (!primary.value || !viewport.value || !track.value) return
  // Computed CSS sizes are unscaled and retain fractional pixels; screen-space
  // rectangles include the dashboard transform and would break loop alignment.
  const distance = parseFloat(getComputedStyle(primary.value).height) || primary.value.offsetHeight
  const width = parseFloat(getComputedStyle(primary.value).width) || primary.value.offsetWidth
  const cells = primary.value.tBodies[0]?.rows[0]?.cells || primary.value.tHead?.rows[0]?.cells || []
  const widths = Array.from(cells, cell => parseFloat(getComputedStyle(cell).width) || cell.offsetWidth)
  if (JSON.stringify(widths) !== JSON.stringify(columnWidths.value)) columnWidths.value = widths
  if (width !== tableWidth.value) tableWidth.value = width
  const metrics = dashboardTableScrollMetrics({ contentHeight: distance, viewportHeight: viewport.value.clientHeight, rowCount: props.rows.length, secondsPerRow: props.secondsPerRow })
  const enabled = props.autoScroll && metrics.overflow
  if (!enabled) {
    stop()
    scrolling.value = false
    return
  }
  if (animation && !reset && metrics.distance === lastDistance && metrics.duration === lastDuration) return
  const progress = !reset && animation && lastDuration ? (Number(animation.currentTime || 0) % lastDuration) / lastDuration : 0
  stop()
  scrolling.value = true
  await nextTick()
  if (disposed || !track.value) return
  lastDistance = metrics.distance
  lastDuration = metrics.duration
  animation = track.value.animate(
    [{ transform: 'translate3d(0, 0, 0)' }, { transform: `translate3d(0, -${metrics.distance}px, 0)` }],
    { duration: metrics.duration, iterations: Infinity, easing: 'linear' },
  )
  animation.currentTime = progress * metrics.duration
  syncPaused()
}
watch(contentKey, () => { stop(); queueMeasure(true) }, { flush: 'post' })
watch(() => [props.autoScroll, props.secondsPerRow], () => queueMeasure(), { flush: 'post' })
watch(paused, syncPaused)
onMounted(() => {
  resizeObserver = new ResizeObserver(() => queueMeasure())
  for (const element of [header.value, viewport.value, primary.value]) if (element) resizeObserver.observe(element)
  queueMeasure(true)
})
onBeforeUnmount(() => {
  disposed = true
  cancelAnimationFrame(frame)
  resizeObserver?.disconnect()
  stop()
})
</script>

<style scoped>
.dashboard-data-table { display: flex; flex-direction: column; width: 100%; height: 100%; min-width: 0; min-height: 0; overflow: hidden; color: var(--table-text-color, var(--accent, #cbd8e6)); font-size: var(--widget-font-size, 11px); font-weight: var(--widget-font-weight, 400); }
table { width: 100%; border-collapse: separate; border-spacing: 0; }
.header-table { flex: 0 0 auto; table-layout: fixed; }
th, td { box-sizing: border-box; text-align: var(--widget-text-align, left); padding: 7px var(--table-cell-padding, 6px); border-bottom: 1px solid rgba(135, 161, 184, .12); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 180px; line-height: 1.5; }
th { color: var(--table-header-color, #8497aa); background: var(--table-header-background, transparent); font-weight: 500; }
.table-body-viewport { flex: 1 1 0; min-height: 0; overflow: hidden; }
.table-body-track { width: 100%; }
.is-scrolling .table-body-track { will-change: transform; }
.measuring-header { visibility: collapse; }
tbody tr:hover { background: rgba(53, 212, 176, .05); }
.sort-button { display: block; width: 100%; border: 0; margin: 0; padding: 0; background: transparent; color: inherit; font: inherit; text-align: inherit; cursor: pointer; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sort-button:focus-visible { outline: 1px solid var(--accent, #35d4b0); outline-offset: -1px; }
</style>
