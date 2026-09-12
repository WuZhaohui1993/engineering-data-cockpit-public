<template>
  <el-select :model-value="modelValue" filterable placeholder="请选择时区" @update:model-value="$emit('update:modelValue', $event)">
    <el-option v-for="item in options" :key="item.value" :label="item.label" :value="item.value" />
  </el-select>
</template>
<script setup>
import { computed } from 'vue'
import { timezoneOptions } from '@/utils/dashboardIntegrationPresentation'
const props = defineProps({ modelValue: { type: String, default: 'Asia/Shanghai' } })
defineEmits(['update:modelValue'])
const options = computed(() => timezoneOptions(props.modelValue))
</script>
