<template>
  <div class="dashboard-carousel" :class="{ vertical: options.carouselDirection === 'vertical', embedded: options.embeddedMode === true }">
    <div v-for="(row, index) in rows" :key="row.id || index" class="carousel-card" :class="{ highlighted: options.carouselHighlight && index === 0 }" @click.stop="$emit('select', row)">
      <b v-if="options.carouselShowIndex">{{ index + 1 }}</b>
      <span v-for="column in columns" :key="column.name"><em>{{ column.title || column.name }}</em>{{ formatValue(row[column.name], column) }}</span>
    </div>
    <div v-if="!rows.length" class="empty-hint">暂无数据</div>
  </div>
</template>

<script setup>
defineProps({ rows: { type: Array, default: () => [] }, columns: { type: Array, default: () => [] }, options: { type: Object, default: () => ({}) }, formatValue: { type: Function, default: value => value ?? '—' } })
defineEmits(['select'])
</script>

<style scoped>
.dashboard-carousel { width: 100%; height: 100%; min-height: 0; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; overflow: auto; color: var(--accent, #35d4b0); font-size: var(--widget-font-size, 12px); font-weight: var(--widget-font-weight, 400); }
.dashboard-carousel.vertical { grid-template-columns: 1fr; grid-auto-rows: minmax(min-content, 1fr); }
.carousel-card { min-width: 0; display: flex; flex-direction: column; gap: 4px; padding: 8px; border: 1px solid color-mix(in srgb, currentColor 25%, transparent); border-radius: 6px; background: color-mix(in srgb, currentColor 8%, transparent); text-align: left; }
.embedded .carousel-card { background: transparent; border-color: transparent; box-shadow: none; padding-inline: 0; }
.carousel-card.highlighted { border-color: currentColor; box-shadow: 0 0 0 1px color-mix(in srgb, currentColor 28%, transparent); }
.carousel-card > b { font-weight: inherit; }
.carousel-card span { display: flex; justify-content: space-between; gap: 6px; overflow-wrap: anywhere; }
.carousel-card em { opacity: .7; font-style: normal; }
.empty-hint { grid-column: 1 / -1; display: flex; align-items: center; justify-content: center; opacity: .65; }
</style>
