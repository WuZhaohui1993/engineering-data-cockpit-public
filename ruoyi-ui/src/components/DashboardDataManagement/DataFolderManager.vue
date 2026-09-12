<template>
  <el-dialog v-model="visible" :title="`${scopeLabel}文件夹`" width="780px" append-to-body destroy-on-close>
    <div class="folder-manager-toolbar">
      <span>文件夹用于整理归档；删除前需移出其中内容并清空子文件夹。</span>
      <el-button v-if="canCreate" type="primary" plain :icon="Plus" @click="openCreate(defaultParentId)">新建文件夹</el-button>
    </div>
    <el-table :data="treeRows" row-key="folderId" default-expand-all>
      <el-table-column prop="folderName" label="文件夹名称" min-width="230" show-overflow-tooltip />
      <el-table-column prop="sortOrder" label="排序" width="90" />
      <el-table-column v-if="canCreate || canEdit || canDelete" label="操作" width="240">
        <template #default="{ row }">
          <el-button v-if="canCreate" link type="primary" @click="openCreate(row.folderId)">新建子文件夹</el-button>
          <el-button v-if="canEdit" link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button v-if="canDelete" link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty><el-empty description="尚未创建文件夹" /></template>
    </el-table>
    <template #footer><el-button @click="visible = false">关闭</el-button></template>
  </el-dialog>

  <el-dialog v-model="editor.open" :title="editor.editing ? '编辑文件夹' : '新建文件夹'" width="480px" append-to-body destroy-on-close>
    <el-form ref="formRef" :model="editor.form" :rules="rules" label-position="top" @submit.prevent>
      <el-form-item label="文件夹名称" prop="folderName">
        <el-input v-model="editor.form.folderName" maxlength="100" placeholder="请输入业务分类名称" @keyup.enter="submit" />
      </el-form-item>
      <el-form-item label="父文件夹" prop="parentId">
        <el-tree-select v-model="editor.form.parentId" :data="parentOptions" check-strictly :render-after-expand="false" default-expand-all filterable style="width: 100%" />
      </el-form-item>
      <el-form-item label="显示顺序" prop="sortOrder">
        <el-input-number v-model="editor.form.sortOrder" :min="0" :max="999999" :precision="0" controls-position="right" style="width: 100%" />
      </el-form-item>
      <el-form-item label="备注"><el-input v-model="editor.form.remark" type="textarea" :rows="2" maxlength="500" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="editor.open = false">取消</el-button>
      <el-button type="primary" :loading="editor.saving" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { checkPermi } from '@/utils/permission'
import { addDataFolder, updateDataFolder, deleteDataFolder } from '@/api/dashboardDataFolder'
import { buildDataFolderOptions, normalizeDataFolders, dataFolderScopeLabels, dataFolderCreatePermissions, dataFolderEditPermissions, dataFolderDeletePermissions } from '@/utils/dashboardDataFolder'

const props = defineProps({
  modelValue: Boolean,
  scope: { type: String, required: true },
  folders: { type: Array, default: () => [] },
  defaultParentId: { type: [Number, String], default: 0 },
})
const emit = defineEmits(['update:modelValue', 'changed'])
const visible = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const scopeLabel = computed(() => dataFolderScopeLabels[props.scope] || '')
const canCreate = computed(() => checkPermi([dataFolderCreatePermissions[props.scope]]))
const canEdit = computed(() => checkPermi([dataFolderEditPermissions[props.scope]]))
const canDelete = computed(() => checkPermi([dataFolderDeletePermissions[props.scope]]))
const formRef = ref()
const editor = reactive({ open: false, editing: false, saving: false, form: {} })
const rules = {
  folderName: [{ required: true, whitespace: true, message: '请输入文件夹名称', trigger: 'blur' }],
  parentId: [{ required: true, message: '请选择父文件夹', trigger: 'change' }],
}
const treeRows = computed(() => {
  const folders = new Map(normalizeDataFolders(props.folders).map(folder => [folder.folderId, folder]))
  function row(node) { return { ...folders.get(node.folderId), children: node.children?.map(row) || [] } }
  return buildDataFolderOptions(props.folders, { includeRoot: false }).map(row)
})
const parentOptions = computed(() => buildDataFolderOptions(props.folders, {
  rootLabel: '顶层文件夹', excludedId: editor.editing ? editor.form.folderId : null,
}))

function openCreate(parentId = 0) {
  if (!canCreate.value) return
  editor.editing = false
  editor.form = { folderName: '', parentId: Number(parentId) > 0 ? Number(parentId) : 0, sortOrder: 0, remark: '' }
  editor.open = true
}

function openEdit(row) {
  if (!canEdit.value) return
  editor.editing = true
  editor.form = {
    folderId: row.folderId, folderCode: row.folderCode, groupCode: row.groupCode,
    folderName: row.folderName, parentId: row.parentId || 0, sortOrder: row.sortOrder || 0, remark: row.remark || '',
  }
  editor.open = true
}

async function submit() {
  if (!(editor.editing ? canEdit.value : canCreate.value) || editor.saving || !await formRef.value?.validate().catch(() => false)) return
  editor.saving = true
  try {
    const payload = { ...editor.form, folderName: editor.form.folderName.trim(), parentId: Number(editor.form.parentId || 0) }
    await (editor.editing ? updateDataFolder(props.scope, payload) : addDataFolder(props.scope, payload))
    ElMessage.success('文件夹已保存')
    editor.open = false
    emit('changed')
  } catch {
    // 请求封装已显示服务端校验结果，保留表单供用户调整。
  } finally {
    editor.saving = false
  }
}

async function remove(row) {
  if (!canDelete.value) return
  try {
    await ElMessageBox.confirm(`确定删除文件夹“${row.folderName}”吗？其中有内容或子文件夹时无法删除。`, '删除文件夹', { type: 'warning' })
  } catch { return }
  try {
    await deleteDataFolder(props.scope, row.folderId)
    ElMessage.success('文件夹已删除')
    emit('changed')
  } catch {
    // 非空目录等拒绝信息由请求封装提示，目录仍留在列表中。
  }
}
</script>

<style scoped>
.folder-manager-toolbar { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; margin-bottom: 16px; }
.folder-manager-toolbar > span { flex: 1; min-width: 220px; line-height: 1.6; color: var(--el-text-color-secondary); font-size: var(--el-font-size-base); }
</style>
