<template>
  <div v-if="!nodes.length" class="layer-tree-empty">
    <el-icon><Search /></el-icon><span>没有匹配图层</span>
  </div>
  <div v-else class="designer-layer-tree" role="tree" aria-label="页面图层" aria-multiselectable="true">
    <div
      v-for="node in nodes"
      :key="node.key"
      class="layer-tree-node"
      role="treeitem"
      tabindex="0"
      :aria-label="node.name"
      :aria-selected="isNodeSelected(node)"
      :aria-expanded="node.groupId ? node.expanded : undefined"
      @keydown="handleNodeKeydown($event, node)"
    >
      <div
        class="layer-tree-row"
        :class="{
          active: isNodeSelected(node),
          partial: node.groupId && !isNodeSelected(node) && node.members.some(isWidgetSelected),
          hidden: isNodeHidden(node),
          dragging: node.members.some(widget => widget.id === dragId),
        }"
        :draggable="renamingId !== node.groupId || !node.groupId"
        @dragstart.stop="emit('drag-start', $event, node.widget, false)"
        @dragend.stop="emit('drag-end')"
        @dragover.prevent.stop
        @drop.prevent.stop="emit('drop', node.widget, $event, false)"
        @click.stop="selectNode(node, $event)"
      >
        <el-tooltip v-if="node.groupId" :content="normalizedKeyword ? '搜索时自动展开组合' : (node.expanded ? '收起组合' : '展开组合')">
          <button
            type="button"
            class="layer-tree-action layer-tree-toggle"
            :aria-label="node.expanded ? '收起组合' : '展开组合'"
            :aria-expanded="node.expanded"
            :disabled="Boolean(normalizedKeyword)"
            @click.stop="toggleNode(node)"
          ><el-icon><component :is="node.expanded ? ArrowDown : ArrowRight" /></el-icon></button>
        </el-tooltip>
        <span v-else class="layer-tree-drag" aria-hidden="true"><el-icon><Rank /></el-icon></span>
        <span class="layer-tree-type" :class="node.groupId ? 'is-group' : `tone-${widgetMeta(node.widget).tone}`" aria-hidden="true">
          <el-icon><component :is="node.groupId ? (node.expanded ? FolderOpened : Folder) : widgetMeta(node.widget).icon" /></el-icon>
        </span>
        <el-input
          v-if="node.groupId && renamingId === node.groupId"
          :ref="setRenameInput"
          v-model="renameValue"
          class="layer-tree-rename"
          maxlength="100"
          aria-label="组合名称"
          @click.stop
          @dblclick.stop
          @keydown="handleRenameKeydown"
          @blur="commitRename"
        />
        <span v-else class="layer-tree-name" :title="node.name" @dblclick.stop="node.groupId && startRename(node)">{{ node.name }}</span>
        <span v-if="node.groupId && renamingId !== node.groupId" class="layer-tree-count" :title="`${node.members.length} 个组件`">{{ node.members.length }}</span>
        <el-tooltip v-if="node.groupId && renamingId !== node.groupId" content="重命名组合">
          <button type="button" class="layer-tree-action layer-tree-edit" aria-label="重命名组合" @click.stop="startRename(node)">
            <el-icon><EditPen /></el-icon>
          </button>
        </el-tooltip>
        <el-tooltip :content="visibilityLabel(node)">
          <button type="button" class="layer-tree-action" :aria-label="visibilityLabel(node)" @click.stop="emit('toggle-visible', node.widget, false)">
            <el-icon><component :is="hasHiddenMember(node) ? View : Hide" /></el-icon>
          </button>
        </el-tooltip>
        <el-tooltip :content="lockLabel(node)">
          <button type="button" class="layer-tree-action" :aria-label="lockLabel(node)" @click.stop="emit('toggle-locked', node.widget, false)">
            <el-icon><component :is="isNodeLocked(node) ? Unlock : Lock" /></el-icon>
          </button>
        </el-tooltip>
      </div>
      <div v-if="node.groupId && node.expanded" class="layer-tree-children" role="group" :aria-label="`${node.name}的组件`">
        <div
          v-for="widget in node.visibleMembers"
          :key="widget.id"
          class="layer-tree-row layer-tree-member"
          :class="{
            active: isWidgetSelected(widget),
            'member-editing': memberEditId === widget.id,
            hidden: widget.state?.visible === false,
            dragging: dragId === widget.id,
          }"
          role="treeitem"
          tabindex="0"
          :aria-label="widgetName(widget)"
          :aria-selected="isWidgetSelected(widget)"
          draggable="true"
          @dragstart.stop="emit('drag-start', $event, widget, true)"
          @dragend.stop="emit('drag-end')"
          @dragover.prevent.stop
          @drop.prevent.stop="emit('drop', widget, $event, true)"
          @click.stop="emit('select-widget', widget, $event)"
          @keydown="handleMemberKeydown($event, widget)"
        >
          <span class="layer-tree-drag" aria-hidden="true"><el-icon><Rank /></el-icon></span>
          <span class="layer-tree-type" :class="`tone-${widgetMeta(widget).tone}`" aria-hidden="true">
            <el-icon><component :is="widgetMeta(widget).icon" /></el-icon>
          </span>
          <span class="layer-tree-name" :title="widgetName(widget)">{{ widgetName(widget) }}</span>
          <el-tooltip :content="widget.state?.visible === false ? '显示组件' : '隐藏组件'">
            <button
              type="button"
              class="layer-tree-action"
              :aria-label="widget.state?.visible === false ? '显示组件' : '隐藏组件'"
              @click.stop="emit('toggle-visible', widget, true)"
            ><el-icon><component :is="widget.state?.visible === false ? View : Hide" /></el-icon></button>
          </el-tooltip>
          <el-tooltip :content="widget.state?.locked ? '解锁组件' : '锁定组件'">
            <button
              type="button"
              class="layer-tree-action"
              :aria-label="widget.state?.locked ? '解锁组件' : '锁定组件'"
              @click.stop="emit('toggle-locked', widget, true)"
            ><el-icon><component :is="widget.state?.locked ? Unlock : Lock" /></el-icon></button>
          </el-tooltip>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { ArrowDown, ArrowRight, EditPen, Folder, FolderOpened, Hide, Lock, Rank, Search, Unlock, View } from '@element-plus/icons-vue'

const props = defineProps({
  widgets: { type: Array, default: () => [] },
  groups: { type: Object, default: () => ({}) },
  selectedIds: { type: Array, default: () => [] },
  memberEditId: { type: String, default: '' },
  keyword: { type: String, default: '' },
  dragId: { type: String, default: '' },
  meta: { type: Function, default: () => ({ label: '组件', icon: Rank, tone: 'slate' }) },
})
const emit = defineEmits(['select-widget', 'select-group', 'rename-group', 'toggle-group', 'toggle-visible', 'toggle-locked', 'drag-start', 'drag-end', 'drop'])
const renamingId = ref('')
const renameValue = ref('')
const renameInput = ref(null)

const widgetMeta = widget => props.meta(widget.type) || { label: '组件', icon: Rank, tone: 'slate' }
const widgetName = widget => widget.name || widget.style?.title || widgetMeta(widget).label || '组件'
const normalizedKeyword = computed(() => props.keyword.trim().toLowerCase())
const matchesWidget = (widget, keyword) => [widgetName(widget), widget.style?.title, widgetMeta(widget).label, widget.id].some(value => String(value || '').toLowerCase().includes(keyword))

// 组件数据保持平铺，树结构仅用于图层展示，不改变画布坐标或层级。
const nodes = computed(() => {
  const roots = []
  const groupNodes = new Map()
  const ordered = [...props.widgets].sort((a, b) => (Number(b.layout?.z) || 0) - (Number(a.layout?.z) || 0))
  for (const widget of ordered) {
    const groupId = widget.groupId || ''
    if (groupId && groupNodes.has(groupId)) {
      groupNodes.get(groupId).members.push(widget)
      continue
    }
    const group = groupId ? props.groups[groupId] : null
    const node = {
      key: groupId ? `group:${groupId}` : `widget:${widget.id}`,
      groupId,
      name: groupId ? group?.name || '组合' : widgetName(widget),
      widget,
      members: [widget],
      expanded: groupId ? group?.collapsed === false : false,
    }
    if (groupId) groupNodes.set(groupId, node)
    roots.push(node)
  }
  const keyword = normalizedKeyword.value
  return roots.flatMap(node => {
    const groupMatches = node.groupId && node.name.toLowerCase().includes(keyword)
    const visibleMembers = !keyword || groupMatches ? node.members : node.members.filter(widget => matchesWidget(widget, keyword))
    if (!visibleMembers.length) return []
    return [{ ...node, visibleMembers, expanded: node.groupId ? Boolean(keyword) || node.expanded : false }]
  })
})

const isWidgetSelected = widget => props.selectedIds.includes(widget.id)
const isNodeSelected = node => node.members.every(isWidgetSelected) && (!node.groupId || !node.members.some(widget => widget.id === props.memberEditId))
const isNodeHidden = node => node.members.every(widget => widget.state?.visible === false)
const hasHiddenMember = node => node.members.some(widget => widget.state?.visible === false)
const isNodeLocked = node => node.members.some(widget => widget.state?.locked)
const visibilityLabel = node => `${hasHiddenMember(node) ? '显示' : '隐藏'}${node.groupId ? '组合' : '组件'}`
const lockLabel = node => `${isNodeLocked(node) ? '解锁' : '锁定'}${node.groupId ? '组合' : '组件'}`

function selectNode(node, event) {
  if (node.groupId) emit('select-group', node.groupId, event)
  else emit('select-widget', node.widget, event)
}

function toggleNode(node) {
  if (!normalizedKeyword.value) emit('toggle-group', node.groupId)
}

function setRenameInput(input) {
  renameInput.value = input
}

async function startRename(node) {
  renamingId.value = node.groupId
  renameValue.value = node.name
  await nextTick()
  renameInput.value?.focus()
  renameInput.value?.select()
}

function commitRename() {
  const groupId = renamingId.value
  const name = renameValue.value.trim().slice(0, 100)
  renamingId.value = ''
  if (groupId && name && name !== (props.groups[groupId]?.name || '组合')) emit('rename-group', groupId, name)
}

function cancelRename() {
  renamingId.value = ''
  renameValue.value = ''
}

function handleRenameKeydown(event) {
  event.stopPropagation()
  // 输入法使用回车确认、Esc 取消候选时，尚未提交到 v-model 的文字应继续留在输入框中。
  if (event.isComposing || event.keyCode === 229) return
  if (event.key === 'Enter' || event.key === 'Escape') {
    event.preventDefault()
    if (event.key === 'Enter') commitRename()
    else cancelRename()
  }
}

function handleTreeNavigation(event) {
  if (!['ArrowUp', 'ArrowDown', 'Home', 'End'].includes(event.key)) return false
  event.preventDefault()
  event.stopPropagation()
  const tree = event.currentTarget.closest('[role="tree"]')
  const items = Array.from(tree?.querySelectorAll('[role="treeitem"]') || [])
  const index = items.indexOf(event.currentTarget)
  if (index < 0) return true
  const nextIndex = event.key === 'Home' ? 0 : event.key === 'End' ? items.length - 1
    : Math.max(0, Math.min(items.length - 1, index + (event.key === 'ArrowUp' ? -1 : 1)))
  items[nextIndex]?.focus()
  return true
}

function handleNodeKeydown(event, node) {
  if (event.target !== event.currentTarget) return
  if (handleTreeNavigation(event)) return
  if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault()
    event.stopPropagation()
    selectNode(node, event)
  } else if (node.groupId && event.key === 'F2') {
    event.preventDefault()
    event.stopPropagation()
    startRename(node)
  } else if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
    event.preventDefault()
    event.stopPropagation()
    if (!node.groupId) return
    const shouldExpand = event.key === 'ArrowRight'
    if (shouldExpand !== node.expanded && !normalizedKeyword.value) emit('toggle-group', node.groupId)
    else if (shouldExpand && node.expanded) event.currentTarget.querySelector('[role="group"] [role="treeitem"]')?.focus()
  }
}

function handleMemberKeydown(event, widget) {
  if (event.target !== event.currentTarget) return
  if (handleTreeNavigation(event)) return
  if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault()
    event.stopPropagation()
    emit('select-widget', widget, event)
  } else if (event.key === 'ArrowLeft') {
    event.preventDefault()
    event.stopPropagation()
    event.currentTarget.closest('[role="group"]')?.parentElement?.focus()
  } else if (event.key === 'ArrowRight') {
    event.preventDefault()
    event.stopPropagation()
  }
}

watch(() => props.widgets.map(widget => widget.groupId), groupIds => {
  if (renamingId.value && !groupIds.includes(renamingId.value)) cancelRename()
})
</script>

<style scoped>
.designer-layer-tree {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
  color: var(--el-text-color-regular);
}
.layer-tree-node {
  min-width: 0;
  border-radius: var(--el-border-radius-base);
  outline: none;
}
.layer-tree-row {
  display: flex;
  align-items: center;
  gap: 3px;
  min-width: 0;
  min-height: var(--dashboard-inspector-control-height, var(--el-component-size, 32px));
  padding: 2px 3px;
  border: 1px solid transparent;
  border-radius: var(--el-border-radius-base);
  cursor: pointer;
  outline: none;
}
.layer-tree-row:hover {
  background: var(--el-fill-color-light);
}
.layer-tree-row.active {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}
.layer-tree-node:focus-visible > .layer-tree-row,
.layer-tree-row:focus-visible,
.layer-tree-row.member-editing {
  border-color: var(--el-color-primary);
}
.layer-tree-row.partial {
  border-color: var(--el-color-primary-light-5);
}
.layer-tree-row.hidden {
  opacity: 0.55;
}
.layer-tree-row.dragging {
  opacity: 0.4;
}
.layer-tree-drag,
.layer-tree-type {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  color: var(--el-text-color-placeholder);
  font-size: var(--el-font-size-extra-small);
}
.layer-tree-drag {
  width: 1.5em;
}
.layer-tree-type {
  width: 1.75em;
  height: 1.75em;
  border-radius: var(--el-border-radius-small);
  background: var(--el-fill-color);
  color: var(--el-text-color-secondary);
}
.layer-tree-type.is-group,
.layer-tree-type.tone-blue,
.layer-tree-type.tone-teal,
.layer-tree-type.tone-cyan {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}
.layer-tree-type.tone-amber,
.layer-tree-type.tone-orange {
  color: var(--el-color-warning);
  background: var(--el-color-warning-light-9);
}
.layer-tree-type.tone-red,
.layer-tree-type.tone-pink {
  color: var(--el-color-danger);
  background: var(--el-color-danger-light-9);
}
.layer-tree-type.tone-green {
  color: var(--el-color-success);
  background: var(--el-color-success-light-9);
}
.layer-tree-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  font-size: var(--el-font-size-extra-small);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.layer-tree-count {
  flex: 0 0 auto;
  color: var(--el-text-color-secondary);
  font-size: var(--el-font-size-extra-small);
  font-variant-numeric: tabular-nums;
}
.layer-tree-action {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  width: 1.5em;
  min-height: 1.5em;
  padding: 1px;
  border: 0;
  border-radius: var(--el-border-radius-small);
  background: transparent;
  color: var(--el-text-color-secondary);
  font-size: var(--el-font-size-extra-small);
  cursor: pointer;
}
.layer-tree-action:hover,
.layer-tree-action:focus-visible {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-8);
}
.layer-tree-action:disabled {
  color: var(--el-text-color-placeholder);
  background: transparent;
  cursor: default;
}
.layer-tree-action:focus-visible {
  outline: 1px solid var(--el-color-primary);
  outline-offset: 1px;
}
.layer-tree-edit {
  opacity: 0;
}
.layer-tree-row:hover .layer-tree-edit,
.layer-tree-node:focus-within > .layer-tree-row .layer-tree-edit,
.layer-tree-edit:focus-visible {
  opacity: 1;
}
.layer-tree-rename {
  flex: 1;
  min-width: 0;
}
.layer-tree-rename :deep(.el-input__wrapper) {
  padding-inline: 5px;
}
.layer-tree-children {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-left: 12px;
  padding-left: 5px;
  border-left: 1px solid var(--el-border-color-light);
}
.layer-tree-empty {
  display: flex;
  align-items: center;
  flex-direction: column;
  gap: 8px;
  padding: 30px 0;
  color: var(--el-text-color-placeholder);
  font-size: var(--el-font-size-extra-small);
}
.layer-tree-empty .el-icon {
  font-size: 24px;
}
</style>
