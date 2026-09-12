<template>
  <div ref="host" class="dashboard-gantt" :style="{ color: 'var(--accent, #dceeff)' }">
    <svg v-if="model.tasks.length" :viewBox="`0 0 ${width} ${height}`" role="img" aria-label="基准计划与实际进度甘特图">
      <rect :width="width" :height="model.top" :fill="options.ganttHeaderColor || '#102943'" />
      <text x="10" y="23">序号</text><text x="48" y="23">任务名称</text>
      <g v-for="month in model.months" :key="month.x">
        <path :d="`M${month.x} 0V${model.bottom}`" stroke="#1d4765" stroke-dasharray="3 4" />
        <text :x="month.x + 13" y="23">{{ month.label }}</text>
      </g>
      <g v-for="(task, index) in model.tasks" :key="task.id" class="gantt-task" role="button" tabindex="0" @click.stop="$emit('select', task.row)" @keydown.enter="$emit('select', task.row)">
        <title>{{ task.name }}：计划 {{ mapped(task.row, 'start', 'plannedStart') || '—' }} 至 {{ mapped(task.row, 'end', 'plannedEnd') || '—' }}；实际 {{ mapped(task.row, 'actualStart') || '—' }} 至 {{ mapped(task.row, 'actualEnd') || '—' }}；状态 {{ task.status || '—' }}；完成进度 {{ task.progress === null ? '—' : `${task.progress}%` }}</title>
        <rect x="0" :y="task.y" :width="width" :height="task.rowHeight" :fill="index % 2 ? '#11335135' : '#03132810'" />
        <path :d="`M0 ${task.y + task.rowHeight}H${width}`" stroke="#1a3e58" />
        <text x="16" :y="task.y + task.rowHeight * .64">{{ index + 1 }}</text>
        <text x="48" :y="task.y + task.rowHeight * .64" :textLength="task.name.length > 7 ? model.labelWidth - 58 : undefined" lengthAdjust="spacingAndGlyphs">{{ task.name }}</text>
        <rect v-if="task.plan" :x="task.plan.x" :y="task.y + task.rowHeight * .24" :width="task.plan.w" :height="Math.min(6, task.rowHeight * .17)" rx="2" :fill="options.ganttPlanColor || '#becbd2'" />
        <rect v-if="task.actual" :x="task.actual.x" :y="task.y + task.rowHeight * .55" :width="task.actual.w" :height="Math.min(9, task.rowHeight * .24)" rx="3" :fill="options.ganttActualColor || '#248eff'" />
      </g>
      <path :d="`M${model.labelWidth} 0V${model.bottom}`" stroke="#335b77" />
      <g v-if="options.ganttShowDependencies !== false" stroke="#ff754f" fill="none" stroke-width="1.2">
        <g v-for="link in model.links" :key="link.key"><polyline :points="link.points" /><path :d="`M${link.x + 5} ${link.y - 3}L${link.x} ${link.y}L${link.x + 5} ${link.y + 3}`" /></g>
      </g>
      <g v-if="model.currentX !== null" fill="#ff775c">
        <path :d="`M${model.currentX} 30V${model.bottom}`" stroke="#ff694e" stroke-dasharray="5 3" />
        <text :x="model.currentX" y="12" text-anchor="middle" font-weight="700">{{ options.ganttCurrentDate?.slice(5).replace('-', '/') }}</text>
      </g>
      <g :transform="`translate(${Math.max(model.labelWidth, width / 3 - 90)},${height - 10})`" font-size="12">
        <path d="M0 -4H26" :stroke="options.ganttPlanColor || '#becbd2'" stroke-width="3" /><text x="32">基准计划</text>
        <path d="M108 -4H134" :stroke="options.ganttActualColor || '#248eff'" stroke-width="5" /><text x="140">实际进度</text>
        <g v-if="options.ganttShowDependencies !== false"><path d="M216 -4H242" stroke="#ff754f" /><text x="248">任务依赖</text></g>
      </g>
    </svg>
    <div v-else class="gantt-empty">请绑定任务名称、基准日期和实际日期数据</div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { buildGanttModel } from '@/utils/dashboardGantt'
const props = defineProps({ rows: { type: Array, default: () => [] }, fieldMap: { type: Object, default: () => ({}) }, options: { type: Object, default: () => ({}) } })
defineEmits(['select'])
const host = ref(), width = ref(960), height = ref(360)
const model = computed(() => buildGanttModel(props.rows, props.fieldMap, props.options, width.value, height.value))
const mapped = (row, role, fallback = role) => row[props.fieldMap[role] || fallback]
let observer
onMounted(() => {
  observer = new ResizeObserver(() => { width.value = Math.max(200, host.value.clientWidth); height.value = Math.max(120, host.value.clientHeight) })
  observer.observe(host.value)
})
onBeforeUnmount(() => observer?.disconnect())
</script>

<style scoped>
.dashboard-gantt{width:100%;height:100%;min-height:0;flex:1;overflow:hidden;font-family:inherit}
svg{display:block;width:100%;height:100%;font-size:var(--widget-font-size,14px);font-weight:var(--widget-font-weight,400)}text{fill:currentColor}.gantt-task{cursor:pointer}.gantt-task:hover{filter:brightness(1.2)}.gantt-task:focus{outline:1px solid currentColor}.gantt-empty{display:flex;align-items:center;justify-content:center;height:100%;font-size:13px;color:#91abc2}
</style>
