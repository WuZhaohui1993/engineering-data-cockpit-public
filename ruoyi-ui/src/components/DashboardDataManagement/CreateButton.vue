<template>
  <el-button v-if="canCreate" type="primary" :icon="Plus" :disabled="disabled" @click="$emit('click')">{{ action.label }}</el-button>
</template>
<script setup>
import { computed } from 'vue';
import { Plus } from '@element-plus/icons-vue';
import { checkPermi } from '@/utils/permission';
import { dashboardCreateActions } from '@/utils/dashboardDataManagement';
const props = defineProps({ kind: { type: String, required: true }, disabled: Boolean });
defineEmits(['click']);
const action = computed(() => dashboardCreateActions[props.kind]);
// 页签切换时重新计算权限，避免只在挂载时检查导致按钮沿用前一页签的权限。
const canCreate = computed(() => action.value && checkPermi([action.value.permission]));
</script>
