<template>
  <section aria-label="HTTP 数据保留" class="cache-policy">
    <el-divider content-position="left">临时缓存</el-divider>
    <p class="policy-hint">接口数据用于实时展示，不自动保存业务历史。缓存按接口和查询参数保存最新一份加密响应。</p>
    <el-alert v-if="policy.error" :title="policy.error" type="error" :closable="false" show-icon />
    <div class="policy-grid">
      <el-form-item label="缓存最新响应">
        <el-switch :model-value="policy.enabled" :disabled="!!policy.error" aria-label="缓存最新响应" @update:model-value="update('enabled', $event)" />
      </el-form-item>
      <el-form-item label="缓存有效秒数">
        <el-input-number :key="`fresh-${policy.enabled}-${!!policy.error}`" :model-value="policy.freshSeconds" :disabled="!policy.enabled || !!policy.error" :min="0" :max="3600" :precision="0" controls-position="right" @update:model-value="update('freshSeconds', $event)" />
      </el-form-item>
      <el-form-item label="故障沿用缓存秒数">
        <el-input-number :key="`stale-${policy.enabled}-${!!policy.error}`" :model-value="policy.staleSeconds" :disabled="!policy.enabled || !!policy.error" :min="0" :max="86400" :precision="0" controls-position="right" @update:model-value="update('staleSeconds', $event)" />
      </el-form-item>
    </div>
    <p class="policy-hint">关闭后不再读写响应快照；已有快照按原到期时间分批清理。缓存有效期默认 1 秒；故障沿用为 0 时不返回过期缓存。</p>
    <p v-if="responseType === 'BINARY_MEDIA'" class="policy-hint">媒体内容另有临时文件与访问引用的保留规则，关闭响应快照不等于删除媒体文件。</p>
    <p class="policy-hint">请求与测试日志的保留天数在“接入运维 → 保留策略”统一配置；新测试日志不保存请求或返回正文。</p>
  </section>
</template>
<script setup>
import { computed } from 'vue';
import { ElMessage } from 'element-plus';
import { inspectHttpCachePolicy, updateHttpCachePolicy } from '@/utils/dashboardHttpCachePolicy';
const props = defineProps({ modelValue: { type: String, default: '{}' }, responseType: { type: String, default: 'JSON' } });
const emit = defineEmits(['update:modelValue']);
const policy = computed(() => inspectHttpCachePolicy(props.modelValue));
function update(field, value) {
  try { emit('update:modelValue', updateHttpCachePolicy(props.modelValue, field, value)); }
  catch (error) { ElMessage.error(error.message); }
}
</script>
<style scoped>
.cache-policy { margin-bottom: 20px; }
.policy-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 0 16px; }
.policy-grid :deep(.el-input-number) { width: 100%; }
.policy-hint { margin: 0 0 14px; color: var(--el-text-color-secondary); font-size: var(--el-font-size-small); line-height: 1.65; }
@media (max-width: 700px) { .policy-grid { grid-template-columns: minmax(0, 1fr); } }
</style>
