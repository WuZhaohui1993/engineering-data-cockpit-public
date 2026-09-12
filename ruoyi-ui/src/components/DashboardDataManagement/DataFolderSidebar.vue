<template>
  <div class="data-folder-sidebar dashboard-management-sidebar">
    <TreePanel
      ref="treeRef"
      :title="title || `${dataFolderScopeLabels[scope]}文件夹`"
      :title-icon="FolderOpened"
      :tree-data="treeData"
      :tree-props="{ children: 'children', label: 'label' }"
      node-key="value"
      search-placeholder="搜索文件夹"
      :storage-key="`dashboard-${scope}-sidebar-width`"
      :default-expand-all="true"
      @node-click="selectFolder"
      @refresh="emit('refresh')"
      @collapsed-change="collapsed = $event"
    >
      <template #actions>
        <el-tooltip v-if="canManage" content="管理文件夹" placement="top">
          <el-button class="folder-manage-button" text :icon="Setting" aria-label="管理文件夹" @click="emit('manage')" />
        </el-tooltip>
      </template>
      <template #node="{ node, data }">
        <span class="tree-node">
          <el-icon class="node-icon"><Lock v-if="data.value === -1" /><Folder v-else /></el-icon>
          <span class="node-label" :title="node.label">{{ node.label }}</span>
        </span>
      </template>
    </TreePanel>
    <div v-show="!collapsed" class="folder-filter-options">
      <el-checkbox :model-value="includeChildren" :disabled="Number(modelValue) <= 0 || modelValue == null" @change="toggleChildren">
        包含子文件夹
      </el-checkbox>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { Folder, FolderOpened, Lock, Setting } from '@element-plus/icons-vue'
import TreePanel from '@/components/TreePanel/index.vue'
import { buildDataFolderOptions, dataFolderScopeLabels } from '@/utils/dashboardDataFolder'

const props = defineProps({
  modelValue: { type: [Number, String], default: null },
  includeChildren: { type: Boolean, default: false },
  folders: { type: Array, default: () => [] },
  scope: { type: String, required: true },
  title: { type: String, default: '' },
  allLabel: { type: String, default: '' },
  systemFolder: Boolean,
  canManage: Boolean,
})
const emit = defineEmits(['update:modelValue', 'update:includeChildren', 'change', 'refresh', 'manage'])
const treeRef = ref()
const collapsed = ref(false)
const treeData = computed(() => [
  { value: 'all', label: props.allLabel || `全部${dataFolderScopeLabels[props.scope] || ''}` },
  { value: 0, label: '未分类' },
  ...(props.systemFolder ? [{ value: -1, label: '系统数据源' }] : []),
  ...buildDataFolderOptions(props.folders, { includeRoot: false }),
])

function selectFolder(node) {
  const folderId = node.value === 'all' ? null : node.value
  emit('update:modelValue', folderId)
  emit('change', folderId)
}

function toggleChildren(value) {
  emit('update:includeChildren', value)
  emit('change', props.modelValue)
}

watch([() => props.modelValue, treeData], () => {
  nextTick(() => treeRef.value?.setCurrentKey(props.modelValue == null || props.modelValue === '' ? 'all' : Number(props.modelValue)))
}, { immediate: true })
</script>

<style scoped>
.data-folder-sidebar { display: flex; flex: 0 0 auto; flex-direction: column; min-height: 0; overflow: hidden; }
.data-folder-sidebar :deep(.tree-sidebar) { flex: 1; min-height: 0; background: var(--el-bg-color); border-color: var(--el-border-color-light); }
.data-folder-sidebar :deep(.tree-header) { height: auto; min-height: var(--el-component-size); padding-block: 4px; background: var(--el-bg-color); border-color: var(--el-border-color-light); }
.data-folder-sidebar :deep(.tree-title), .data-folder-sidebar :deep(.tree-node) { color: var(--el-text-color-primary); font-size: var(--el-font-size-base); }
.data-folder-sidebar :deep(.tree-action-icon) { color: var(--el-text-color-secondary); }
.data-folder-sidebar :deep(.tree-action-icon:hover) { background: var(--el-color-primary-light-9); color: var(--el-color-primary); }
.data-folder-sidebar :deep(.el-tree-node__content) { height: var(--el-component-size); }
.data-folder-sidebar :deep(.el-tree-node__content:hover) { background: var(--el-fill-color-light); }
.data-folder-sidebar :deep(.node-icon) { color: var(--el-text-color-secondary); }
.data-folder-sidebar :deep(.node-label) { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tree-node { display: flex; align-items: center; gap: 5px; min-width: 0; overflow: hidden; }
.node-icon { flex-shrink: 0; }
.node-label { min-width: 0; }
.folder-manage-button { padding: 3px; height: auto; min-height: 0; }
.folder-filter-options { padding: 4px 10px; border-top: 1px solid var(--el-border-color-light); color: var(--el-text-color-regular); }
</style>
