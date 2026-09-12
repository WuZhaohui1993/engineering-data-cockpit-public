<template>
  <iframe v-if="url" :src="url" title="平台内部运行页" referrerpolicy="no-referrer"
    sandbox="allow-scripts allow-same-origin" loading="lazy" class="dashboard-embedded-page" />
  <div v-else class="dashboard-embedded-empty">请选择其他大屏的运行页；不支持外部地址或循环嵌入。</div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { dashboardEmbeddedPageUrl } from '@/utils/dashboardEmbeddedPage'
const props = defineProps({ src: { type: String, default: '' } })
const route = useRoute()
const url = computed(() => typeof window !== 'undefined' && window.self !== window.top ? '' : dashboardEmbeddedPageUrl(props.src, route.fullPath))
</script>

<style scoped>
.dashboard-embedded-page { display: block; width: 100%; height: 100%; min-height: 0; border: 0; }
.dashboard-embedded-empty { display: grid; place-content: center; height: 100%; padding: 12px; color: #9cabbc; font-size: 12px; text-align: center; }
</style>
