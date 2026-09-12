<template>
  <el-dialog v-model="visible" title="移动到文件夹" width="460px" append-to-body destroy-on-close>
    <p class="folder-move-description">将选中的 {{ ids.length }} 个{{ scopeLabel }}移动到：</p>
    <el-tree-select v-model="targetFolderId" :data="folderOptions" check-strictly :render-after-expand="false" default-expand-all filterable placeholder="请选择目标文件夹" style="width: 100%" />
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button v-if="canEdit" type="primary" :loading="saving" :disabled="!ids.length || targetFolderId == null" @click="submit">确定移动</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { moveDataFolderItems } from '@/api/dashboardDataFolder'
import { checkPermi } from '@/utils/permission'
import { buildDataFolderOptions, dataFolderScopeLabels, dataFolderMovePermissions } from '@/utils/dashboardDataFolder'

const props = defineProps({
  modelValue: Boolean,
  scope: { type: String, required: true },
  folders: { type: Array, default: () => [] },
  ids: { type: Array, default: () => [] },
  defaultFolderId: { type: [Number, String], default: 0 },
  resourceType: { type: String, default: '' },
})
const emit = defineEmits(['update:modelValue', 'moved'])
const visible = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const targetFolderId = ref(0)
const saving = ref(false)
const scopeLabel = computed(() => props.scope === 'resource' && props.resourceType === 'map' ? '地图' : dataFolderScopeLabels[props.scope] || '')
const canEdit = computed(() => checkPermi([dataFolderMovePermissions[props.scope]]))
const folderOptions = computed(() => buildDataFolderOptions(props.folders))

watch(() => props.modelValue, open => {
  if (open) targetFolderId.value = Number(props.defaultFolderId) > 0 ? Number(props.defaultFolderId) : 0
})

async function submit() {
  if (!canEdit.value || saving.value || !props.ids.length || targetFolderId.value == null) return
  saving.value = true
  try {
    await moveDataFolderItems(props.scope, {
      ids: props.ids,
      folderId: Number(targetFolderId.value),
      ...(props.scope === 'resource' ? { resourceType: props.resourceType } : {}),
    })
    ElMessage.success('已移动到目标文件夹')
    visible.value = false
    emit('moved')
  } catch {
    // 请求封装已提示失败；保留目标目录，允许重新选择后重试。
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.folder-move-description { margin: 0 0 14px; color: var(--el-text-color-regular); line-height: 1.6; }
</style>
