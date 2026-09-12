<template>
  <span
    class="dashboard-icon"
    :class="{ 'dashboard-icon--framed': !imageMode && options.iconFrame, 'dashboard-icon--image': imageMode }"
    :style="iconStyle"
    role="img"
    :aria-label="imageUnavailable ? `${name || '图标'}：图片加载失败` : name || '图标'"
  >
    <img
      v-if="imageMode && !imageUnavailable"
      :key="imageUrl"
      class="dashboard-icon__image"
      :src="imageUrl"
      alt=""
      draggable="false"
      @error="handleImageError"
    />
    <svg v-else-if="imageMode" class="dashboard-icon__placeholder" viewBox="0 0 24 24" aria-hidden="true">
      <title>图片加载失败</title>
      <rect x="3" y="3" width="18" height="18" rx="2" />
      <circle cx="8" cy="8" r="1.5" />
      <path d="m4 18 5-5 3 3 3-5 5 7" />
    </svg>
    <component :is="platformIcon" v-else class="dashboard-icon__vector" aria-hidden="true" />
  </span>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { dashboardIconMap } from '@/utils/dashboardIcons'
import { dashboardResourceUrl } from '@/utils/dashboard'
import { dashboardIconImageUrl } from '@/utils/dashboardIconLibrary'

const props = defineProps({
  options: { type: Object, default: () => ({}) },
  name: { type: String, default: '' },
})

const imageRef = computed(() => String(props.options.imageRef || '').trim())
const imageMode = computed(() => Boolean(imageRef.value))
const imageUrl = computed(() => dashboardIconImageUrl(imageRef.value, dashboardResourceUrl))
const failedUrl = ref('')
const imageUnavailable = computed(() => imageMode.value && (!imageUrl.value || failedUrl.value === imageUrl.value))
const platformIcon = computed(() => Object.hasOwn(dashboardIconMap, props.options.iconName)
  ? dashboardIconMap[props.options.iconName]
  : dashboardIconMap.star)
const iconStyle = computed(() => {
  const size = Number(props.options.fontSize)
  return { '--dashboard-icon-size': `${Number.isFinite(size) && size > 0 ? size : 32}px` }
})

watch([imageRef, imageUrl], () => { failedUrl.value = '' }, { flush: 'sync' })

function handleImageError(event) {
  // A previous image can finish loading after the selection has changed.
  const source = event.currentTarget?.getAttribute('src')
  if (source && source === imageUrl.value) failedUrl.value = source
}
</script>

<style scoped>
.dashboard-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  width: 1em;
  height: 1em;
  box-sizing: content-box;
  vertical-align: middle;
  color: var(--accent, #35d4b0);
  font-size: var(--widget-font-size, var(--dashboard-icon-size, 32px));
  line-height: 1;
}
.dashboard-icon--framed {
  padding: 6px;
  border: 1px solid currentColor;
  border-radius: 50%;
}
.dashboard-icon__image,
.dashboard-icon__vector,
.dashboard-icon__placeholder {
  display: block;
  width: 100%;
  height: 100%;
}
.dashboard-icon__image { object-fit: contain; }
.dashboard-icon__placeholder {
  color: #91a4b8;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.5;
  stroke-linecap: round;
  stroke-linejoin: round;
}
</style>
