<template>
  <el-empty class="data-management-empty" :image-size="88">
    <template #description>
      <p>{{ description }}</p>
      <p v-if="canCreate" class="empty-hint">可调整筛选条件，或点击右上角“{{ action.label }}”开始配置。</p>
      <p v-else class="empty-hint">可调整筛选条件；需要新增配置时请联系管理员。</p>
    </template>
  </el-empty>
</template>
<script setup>
import { computed } from 'vue';
import { checkPermi } from '@/utils/permission';
import { dashboardCreateActions } from '@/utils/dashboardDataManagement';
const props = defineProps({ kind: { type: String, required: true }, description: { type: String, default: '暂无符合条件的记录' } });
const action = computed(() => dashboardCreateActions[props.kind]);
const canCreate = computed(() => action.value && checkPermi([action.value.permission]));
</script>
<style scoped>
.data-management-empty { padding: 24px 16px; }
.empty-hint { margin-top: 8px; color: var(--el-text-color-secondary); line-height: 1.6; }
</style>
