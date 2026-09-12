<template>
  <div class="dashboard-map-preview">
    <div ref="element" class="dashboard-map-preview-chart"></div>
    <span v-if="message" class="dashboard-map-preview-message">{{ message }}</span>
  </div>
</template>

<script setup>
import { ref, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import * as echarts from 'echarts'
import { dashboardResourceUrl } from '@/utils/dashboard'
import { buildDashboardMapOption, dashboardMapData } from '@/utils/dashboardMapOptions'

const props = defineProps({
  widget: { type: Object, required: true },
  rows: { type: Array, default: () => [] },
  palette: { type: Array, default: () => ['#35d4b0', '#5b8ff9'] },
  formatValue: { type: Function, default: (_field, value) => value == null ? '' : String(value) },
})
const element = ref()
const message = ref('')
let chart, resizeObserver, controller, sequence = 0, cachedRef, cachedGeoJson

async function render() {
  const current = ++sequence
  controller?.abort()
  const mapRef = props.widget.style?.mapRef
  if (!mapRef) {
    chart?.clear()
    message.value = '请选择地图资源'
    return
  }
  try {
    if (mapRef !== cachedRef) {
      const url = dashboardResourceUrl(mapRef)
      if (!url) throw new Error('地图资源路径不受支持')
      controller = new AbortController()
      message.value = '地图资源加载中…'
      const response = await fetch(url, { headers: { Accept: 'application/geo+json, application/json' }, credentials: 'same-origin', signal: controller.signal })
      if (!response.ok) throw new Error(`地图资源加载失败（${response.status}）`)
      const geoJson = await response.json()
      if (!Array.isArray(geoJson?.features)) throw new Error('地图 GeoJSON 结构不合法')
      if (current !== sequence) return
      cachedGeoJson = geoJson
      cachedRef = mapRef
    }
    await nextTick()
    if (current !== sequence || !element.value) return
    const mapName = `dashboard-preview-map-${props.widget.id}`
    echarts.registerMap(mapName, cachedGeoJson)
    chart ||= echarts.init(element.value)
    const palette = props.widget.style?.chartConfig?.colors?.length ? props.widget.style.chartConfig.colors : props.palette
    chart.setOption(buildDashboardMapOption(props.widget, mapName, dashboardMapData(props.widget, props.rows, props.formatValue), palette), true)
    message.value = ''
    chart.resize()
  } catch (error) {
    if (current !== sequence || error.name === 'AbortError') return
    chart?.clear()
    message.value = error.message || '地图资源加载失败'
  }
}
onMounted(() => {
  resizeObserver = new ResizeObserver(() => chart?.resize())
  resizeObserver.observe(element.value)
  render()
})
watch(() => [props.widget, props.rows, props.palette], render, { deep: true })
onBeforeUnmount(() => {
  sequence++
  controller?.abort()
  resizeObserver?.disconnect()
  chart?.dispose()
})
</script>

<style scoped>
.dashboard-map-preview { position: relative; width: 100%; height: 100%; min-height: 80px; }
.dashboard-map-preview-chart { position: absolute; inset: 0; }
.dashboard-map-preview-message { position: absolute; inset: 0; display: grid; place-items: center; padding: 12px; color: inherit; font-size: 12px; text-align: center; }
</style>
