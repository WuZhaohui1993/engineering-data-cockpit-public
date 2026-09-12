<template>
  <div class="dashboard-tabs" @click.stop @pointerdown.stop>
    <div class="tabs-nav" role="tablist">
      <button v-for="(tab, index) in tabs" :key="tab.key || index" type="button" role="tab" :class="{ active: activeIndex === index }" :aria-selected="activeIndex === index" @click="select(index)">{{ tab.label || `选项 ${index + 1}` }}</button>
    </div>
    <div class="tabs-body" role="tabpanel">{{ tabs[activeIndex]?.content || fallback }}</div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
const props = defineProps({ tabs: { type: Array, default: () => [] }, modelValue: { type: Number, default: undefined }, fallback: { type: String, default: '' } })
const emit = defineEmits(['update:modelValue'])
const localIndex = ref(0)
const activeIndex = computed(() => Math.max(0, Math.min(props.modelValue ?? localIndex.value, props.tabs.length - 1)))
function select(index) { localIndex.value = index; emit('update:modelValue', index) }
</script>

<style scoped>
.dashboard-tabs { display: flex; flex-direction: column; width: 100%; height: 100%; min-height: 0; gap: 10px; font-size: var(--widget-font-size, 12px); font-weight: var(--widget-font-weight, 400); color: var(--accent, #35d4b0); }
.tabs-nav { display: flex; flex-wrap: wrap; justify-content: var(--widget-content-justify, var(--widget-align-items, center)); gap: 4px; border-bottom: 1px solid color-mix(in srgb, currentColor 20%, transparent); }
.tabs-nav button { padding: 6px 10px; border: 0; border-bottom: 2px solid transparent; background: transparent; color: color-mix(in srgb, currentColor 70%, transparent); font: inherit; cursor: pointer; }
.tabs-nav button.active { color: var(--accent, #35d4b0); border-bottom-color: currentColor; }
.tabs-body { flex: 1; min-height: 0; display: flex; align-items: var(--widget-content-align-y, center); justify-content: var(--widget-content-justify, var(--widget-align-items, center)); text-align: var(--widget-text-align, center); white-space: pre-wrap; overflow-wrap: anywhere; overflow: auto; }
</style>
