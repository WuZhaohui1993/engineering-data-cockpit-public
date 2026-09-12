<template>
  <section class="management-toolbar" :aria-label="`${title}管理工具栏`">
    <div class="management-toolbar-head">
      <div class="management-toolbar-summary">
        <strong class="management-toolbar-title">{{ title }}</strong>
        <span v-if="selectionCount" class="management-toolbar-count">已选 {{ selectionCount }} {{ noun }}</span>
        <span v-else-if="total != null" class="management-toolbar-count">共 {{ total }} {{ noun }}</span>
        <div v-if="selectionCount && $slots.selection" class="management-toolbar-selection"><slot name="selection" /></div>
        <div v-else-if="activeFilter" class="management-toolbar-filter">
          <span :title="activeFilter">{{ activeFilter }}</span>
          <el-tooltip content="清除筛选" placement="top"><el-button text :icon="Close" aria-label="清除筛选" @click="emit('clear-filter')" /></el-tooltip>
        </div>
      </div>
      <div class="management-toolbar-actions">
        <slot name="secondary" />
        <DashboardManagementHelp v-if="helpModule" :module="helpModule" compact />
        <el-tooltip content="刷新" placement="top"><el-button text :icon="Refresh" :loading="loading" aria-label="刷新" @click="emit('refresh')" /></el-tooltip>
        <el-dropdown v-if="actions.length" trigger="click" @command="emit('action', $event)">
          <el-button text :icon="MoreFilled" aria-label="更多操作" />
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item v-for="action in actions" :key="action.key" :command="action.key" :icon="action.icon" :disabled="action.disabled" :divided="action.divided">{{ action.label }}</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <div v-if="$slots.primary" class="management-toolbar-primary"><slot name="primary" /></div>
      </div>
    </div>
    <el-form v-if="$slots.filters" class="management-toolbar-query" :inline="true" @submit.prevent="emit('search')">
      <div class="management-toolbar-fields"><slot name="filters" /></div>
      <div class="management-toolbar-query-actions">
        <el-button type="primary" native-type="submit" :icon="Search">查询</el-button>
        <el-tooltip content="重置查询" placement="top"><el-button :icon="RefreshLeft" aria-label="重置查询" @click="emit('reset')" /></el-tooltip>
      </div>
    </el-form>
  </section>
</template>

<script setup>
import { Close, MoreFilled, Refresh, RefreshLeft, Search } from '@element-plus/icons-vue'
import DashboardManagementHelp from './Help.vue'

defineProps({
  title: { type: String, required: true },
  total: { type: Number, default: null },
  noun: { type: String, default: '项' },
  selectionCount: { type: Number, default: 0 },
  activeFilter: { type: String, default: '' },
  helpModule: { type: String, default: '' },
  actions: { type: Array, default: () => [] },
  loading: Boolean,
})
const emit = defineEmits(['search', 'reset', 'refresh', 'action', 'clear-filter'])
</script>

<style scoped>
.management-toolbar {
  container: management-toolbar / inline-size;
  min-width: 0;
  margin-bottom: var(--dashboard-page-gap, 14px);
  padding: 12px 14px;
  border: 1px solid var(--el-border-color-light);
  border-radius: var(--el-border-radius-base);
  background: var(--el-bg-color);
  color: var(--el-text-color-regular);
}
.management-toolbar-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; min-width: 0; }
.management-toolbar-summary { display: flex; align-items: center; gap: 12px; min-width: 0; flex: 1; }
.management-toolbar-title { font-size: var(--el-font-size-base); color: var(--el-text-color-primary); white-space: nowrap; font-weight: 600; }
.management-toolbar-count { white-space: nowrap; font-size: var(--el-font-size-small); color: var(--el-text-color-secondary); }
.management-toolbar-selection { flex-shrink: 0; }
.management-toolbar-selection :deep(.el-button) { margin: 0; }
.management-toolbar-filter { display: flex; align-items: center; gap: 2px; min-width: 0; max-width: 260px; color: var(--el-color-primary); font-size: var(--el-font-size-small); }
.management-toolbar-filter > span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.management-toolbar-filter :deep(.el-button) { padding-inline: 4px; flex-shrink: 0; }
.management-toolbar-actions { display: flex; align-items: center; gap: 4px; flex: 0 0 auto; white-space: nowrap; }
.management-toolbar-actions :deep(.el-button) { margin: 0; }
.management-toolbar-actions > :deep(.el-button), .management-toolbar-actions :deep(.el-dropdown > .el-button) { padding-inline: 8px; }
.management-toolbar-primary { display: flex; align-items: center; gap: 8px; margin-left: 6px; }
.management-toolbar-primary :deep(.el-upload) { display: inline-flex; }
.management-toolbar-query { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 12px; align-items: start; margin-top: 12px; padding-top: 12px; border-top: 1px solid var(--el-border-color-lighter); }
.management-toolbar-fields { display: flex; align-items: center; gap: 12px; min-width: 0; }
.management-toolbar-fields :deep(.el-form-item) { flex: 0 1 185px; margin: 0; min-width: 0; }
.management-toolbar-fields :deep(.el-form-item.filter-wide) { flex: 1 1 250px; max-width: 360px; }
.management-toolbar-fields :deep(.el-form-item__label) { padding-right: 8px; white-space: nowrap; font-size: var(--el-font-size-base); color: var(--el-text-color-regular); }
.management-toolbar-fields :deep(.el-form-item__content) { min-width: 0; }
.management-toolbar-fields :deep(.el-input), .management-toolbar-fields :deep(.el-select), .management-toolbar-fields :deep(.el-tree-select), .management-toolbar-fields :deep(.el-date-editor) { width: 100%; min-width: 0; }
.management-toolbar-query-actions { display: flex; align-items: center; gap: 6px; white-space: nowrap; }
.management-toolbar-query-actions :deep(.el-button) { margin: 0; }
.management-toolbar-query-actions > :deep(.el-button:last-child) { padding-inline: 10px; }
@container management-toolbar (max-width: 720px) {
  .management-toolbar-fields { gap: 8px; }
  .management-toolbar-fields :deep(.el-form-item__label) { position: absolute; width: 1px; height: 1px; overflow: hidden; clip-path: inset(50%); padding: 0; }
  .management-toolbar-summary { gap: 8px; }
  .management-toolbar-filter { max-width: 150px; }
}
@container management-toolbar (max-width: 520px) {
  .management-toolbar-head { align-items: flex-start; }
  .management-toolbar-summary { display: grid; grid-template-columns: auto 1fr; gap: 4px 8px; }
  .management-toolbar-selection, .management-toolbar-filter { grid-column: 1 / -1; }
  .management-toolbar-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .management-toolbar-fields :deep(.el-form-item:first-child) { grid-column: 1 / -1; max-width: none; }
  .management-toolbar-query { gap: 8px; }
  .management-toolbar-query-actions { flex-direction: column; align-items: stretch; }
}
</style>
