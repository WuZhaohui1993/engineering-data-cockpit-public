<template>
  <el-tooltip
    :content="selectedLabel"
    :disabled="!selectedLabel || expanded"
    :show-after="400"
    effect="light"
    placement="top-start"
    popper-class="designer-picker-label-tooltip"
  >
    <el-tree-select
      v-bind="$attrs"
      :model-value="modelValue"
      :data="data"
      :fit-input-width="false"
      popper-class="designer-picker-popper"
      @visible-change="expanded = $event"
    >
      <template #default="{ node }">
        <span class="designer-picker-node-label">{{ node.label }}</span>
      </template>
    </el-tree-select>
  </el-tooltip>
</template>

<script setup>
import { computed, ref } from 'vue'

defineOptions({ inheritAttrs: false })

const props = defineProps({
  modelValue: { type: [String, Number], default: undefined },
  data: { type: Array, default: () => [] },
})
const expanded = ref(false)

// 设计器的数据集和资源树共用 value / label，提示始终跟随当前选项。
const selectedLabel = computed(() => {
  const findLabel = nodes => {
    for (const node of nodes) {
      if (node.value === props.modelValue) return node.label
      const label = node.children && findLabel(node.children)
      if (label) return label
    }
    return ''
  }
  return findLabel(props.data) || String(props.modelValue ?? '')
})
</script>

<style>
/* 弹层传送到 body，使用专用类限制影响范围。 */
.el-select-dropdown.designer-picker-popper {
  width: min(560px, calc(100vw - 24px));
  max-width: calc(100vw - 24px);
}

.designer-picker-popper .el-tree-node__content {
  height: auto;
  min-height: var(--dashboard-inspector-control-height);
}

.designer-picker-popper .el-select-dropdown__item {
  min-width: 0;
  height: auto;
  padding-top: 5px;
  padding-bottom: 5px;
  line-height: 1.5;
  white-space: normal;
  overflow-wrap: anywhere;
}

.designer-picker-node-label {
  display: block;
  white-space: normal;
  overflow-wrap: anywhere;
}

.el-popper.designer-picker-label-tooltip {
  max-width: min(560px, calc(100vw - 24px));
  white-space: normal;
  overflow-wrap: anywhere;
}
</style>
