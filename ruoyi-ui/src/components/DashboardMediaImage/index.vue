<template>
  <img v-if="src" :src="src" :alt="alt" @error="failed = true; release()" />
  <span v-else class="media-placeholder" role="img" :aria-label="failed ? '图片暂不可用' : '图片加载中'">{{ failed ? '图片暂不可用' : '加载中' }}</span>
</template>
<script setup>
import { ref, watch, onBeforeUnmount } from 'vue';
import { dashboardResourceUrl } from '@/utils/dashboard';
import { issueDashboardMediaRef, issueDashboardShareMediaRef, fetchDashboardMediaBlob } from '@/api/dashboard';
const props = defineProps({ value: { type: [String, Number], default: '' }, context: { type: Object, required: true }, alt: { type: String, default: '' } });
const emit = defineEmits(['expired']);
const src = ref(''), failed = ref(false);
let generation = 0, blobUrl = '', timer;
function release() { clearTimeout(timer); if (blobUrl) URL.revokeObjectURL(blobUrl); blobUrl = ''; src.value = ''; }
watch(() => JSON.stringify([props.value, props.context]), async () => {
  const current = ++generation; release(); failed.value = false;
  const value = String(props.value || '').trim();
  const local = dashboardResourceUrl(value);
  if (local) { src.value = local; return; }
  if (!/^[A-Za-z0-9_-]{32,128}$/.test(value)) { failed.value = true; return; }
  const context = { ...props.context };
  const body = { candidateRef: value, pageId: context.pageId, revisionId: context.revisionId, widgetId: context.widgetId, datasetCode: context.datasetCode };
  try {
    const response = context.shareToken
      ? await issueDashboardShareMediaRef(context.shareToken, body, context.pageCode)
      : await issueDashboardMediaRef(body);
    if (current !== generation) return;
    const reference = response.data || response;
    const blob = await fetchDashboardMediaBlob(reference.mediaRef, context.shareToken, context.pageCode);
    if (current !== generation) return;
    if (!(blob instanceof Blob) || !/^image\/(png|jpeg|gif|webp)$/.test(blob.type)) throw new Error('媒体响应类型不受支持');
    blobUrl = URL.createObjectURL(blob); src.value = blobUrl;
    const expiresAt = new Date(reference.expiresAt).getTime();
    if (Number.isFinite(expiresAt)) timer = setTimeout(() => { release(); emit('expired'); }, Math.max(1, expiresAt - Date.now()));
  } catch { if (current === generation) { failed.value = true; release(); } }
}, { immediate: true });
onBeforeUnmount(() => { generation++; release(); });
</script>
<style scoped>
.media-placeholder { display: inline-flex; align-items: center; justify-content: center; overflow: hidden; font-size: 10px; color: inherit; opacity: .65; }
</style>
