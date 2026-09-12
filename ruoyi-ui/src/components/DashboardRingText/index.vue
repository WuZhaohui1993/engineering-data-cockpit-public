<template>
  <div class="dashboard-ring-text" :class="{ gradient: options.ringTextGradient === true }" :style="dashboardRingTextStyle(options)">
    <div class="ring-text-orbit" :class="{ 'orbit-hidden': options.ringTextShowOrbit === false }">
      <span v-for="(item, index) in items" :key="index" :style="dashboardRingTextItemStyle(index, items.length)">{{ item }}</span>
    </div>
    <strong>{{ options.ringTextCenterText || centerText }}</strong>
  </div>
</template>

<script setup>
import { dashboardRingTextStyle, dashboardRingTextItemStyle } from '@/utils/dashboardPresentation'
defineProps({ items: { type: Array, default: () => [] }, options: { type: Object, default: () => ({}) }, centerText: { type: String, default: '工程驾驶舱' } })
</script>

<style scoped>
.dashboard-ring-text { position: relative; width: 100%; height: 100%; min-height: 0; flex: 1; display: flex; align-items: center; justify-content: center; color: var(--accent, #35d4b0); transform: skewY(var(--ring-tilt)); overflow: hidden; }
.dashboard-ring-text > strong { position: relative; max-width: 70%; font-size: var(--widget-font-size, 16px); font-weight: var(--widget-font-weight, 400); text-align: center; overflow-wrap: anywhere; }
.ring-text-orbit { position: absolute; inset: var(--ring-inset); border: 1px dashed color-mix(in srgb, currentColor 45%, transparent); border-radius: 50%; animation: ring-text-spin var(--ring-duration) linear infinite; animation-direction: var(--ring-direction); animation-play-state: var(--ring-play-state); }
.ring-text-orbit.orbit-hidden { border-color: transparent; }
.ring-text-orbit span { position: absolute; transform: translate(-50%, -50%); white-space: nowrap; font-size: var(--widget-font-size, 16px); font-weight: var(--widget-font-weight, 400); }
.gradient .ring-text-orbit span, .gradient > strong { background: linear-gradient(90deg, var(--accent, #35d4b0), #5b8ff9, #e7ab47); background-clip: text; -webkit-background-clip: text; color: transparent; }
@keyframes ring-text-spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
</style>
