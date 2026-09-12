<template>
  <el-dialog v-model="visible" title="从接收记录创建数据集" width="760px" destroy-on-close>
    <el-form label-position="top" @submit.prevent="submit">
      <el-form-item label="数据集编码"><el-input v-model="form.datasetCode" maxlength="64" /></el-form-item>
      <el-form-item label="数据集名称"><el-input v-model="form.datasetName" maxlength="100" /></el-form-item>
      <el-form-item label="目标数据集文件夹">
        <el-tree-select v-model="targetFolderId" :data="datasetFolderOptions" :loading="foldersLoading" check-strictly filterable default-expand-all class="folder-select" />
        <p class="field-hint">在数据集目录中独立归档；可选择“未分类”。</p>
      </el-form-item>
      <el-form-item :label="projectScopes.length ? '外部项目编码（按接入方范围选择）' : '外部项目编码（可选）'">
        <el-select v-if="projectScopes.length" v-model="form.projectCode" placeholder="请选择允许的外部项目" filterable>
          <el-option v-for="code in projectScopes" :key="code" :label="code" :value="code" />
        </el-select>
        <el-input v-else v-model="form.projectCode" placeholder="来源没有项目划分时留空" />
        <p class="field-hint">这是来源系统的数据分组标识，不关联平台项目档案。未限制范围时，留空读取该接入方的全部标准记录；填写则只读取对应外部项目。</p>
      </el-form-item>
      <el-form-item><el-switch v-model="form.realtime" active-text="处理完成后实时更新页面（WebSocket）" /></el-form-item>
      <p>数据集默认包含业务主键、外部项目编码和业务时间，可选择接收记录中的其他字段。</p>
      <div v-for="(field,index) in form.fields" :key="index" class="field-row">
        <el-input v-model="field.name" placeholder="输出字段编码" aria-label="输出字段编码" />
        <el-input v-model="field.sourcePath" placeholder="标准数据字段路径" aria-label="标准数据字段路径" />
        <el-select v-model="field.type"><el-option label="文本" value="string" /><el-option label="数值" value="number" /><el-option label="整数" value="integer" /><el-option label="时间" value="datetime" /><el-option label="布尔" value="boolean" /></el-select>
        <el-button @click="form.fields.splice(index,1)">删除</el-button>
      </div>
      <el-button :disabled="form.fields.length >= 50" @click="form.fields.push({ name:'',sourcePath:'',type:'string' })">添加字段</el-button>
      <el-alert title="创建后为草稿。请在数据集管理中测试并启用，再绑定到页面组件。" type="info" :closable="false" class="hint" />
    </el-form>
    <template #footer><el-button @click="visible=false">取消</el-button><el-button type="primary" :loading="saving" :disabled="foldersLoading || !foldersReady" @click="submit">创建数据集</el-button></template>
  </el-dialog>
</template>
<script setup>
import { computed, ref, reactive } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { createInboundDataset } from '@/api/dashboardIntegration';
import { listDataFolders } from '@/api/dashboardDataFolder';
import { buildDataFolderOptions } from '@/utils/dashboardDataFolder';
import { integrationProjectScope } from '@/utils/dashboardIntegrationPresentation';
const visible=ref(false),saving=ref(false),integrationId=ref(null),router=useRouter();
const projectScopes=ref([]);
const datasetFolders=ref([]),targetFolderId=ref(0),foldersLoading=ref(false),foldersReady=ref(false);
const datasetFolderOptions=computed(() => buildDataFolderOptions(datasetFolders.value));
const form=reactive({ datasetCode:'',datasetName:'',groupCode:'',projectCode:'',realtime:true,fields:[] });
async function open(row) {
  integrationId.value=row.integrationId;
  targetFolderId.value=0;
  Object.assign(form,{datasetCode:row.integrationCode+'-records',datasetName:row.integrationName+'记录',groupCode:'',projectCode:'',realtime:true,fields:[]});
  projectScopes.value=integrationProjectScope(row);
  form.projectCode=projectScopes.value[0]||'';
  visible.value=true;
  foldersLoading.value=true;
  foldersReady.value=false;
  datasetFolders.value=[];
  try {
    const response=await listDataFolders('dataset');
    datasetFolders.value=response.data||response.rows||[];
    foldersReady.value=true;
  } finally { foldersLoading.value=false; }
}
async function submit() {
  if(foldersLoading.value||!foldersReady.value) return;
  if(!form.datasetCode.trim()||!form.datasetName.trim()) return ElMessage.warning('请填写数据集编码和名称');
  if(projectScopes.value.length&&!projectScopes.value.includes(form.projectCode)) return ElMessage.warning('请选择允许的外部项目编码');
  if(form.projectCode.trim()&&!/^[A-Za-z0-9._-]{1,128}$/.test(form.projectCode.trim())) return ElMessage.warning('外部项目编码只能包含字母、数字、点、下划线和短横线');
  const folder=datasetFolders.value.find(item => Number(item.folderId)===Number(targetFolderId.value));
  if(targetFolderId.value>0&&!folder) return ElMessage.warning('目标文件夹已变更，请重新打开弹窗选择');
  form.groupCode=folder?.groupCode||folder?.folderCode||'';
  saving.value=true;
  try { await createInboundDataset(integrationId.value,{...form}); visible.value=false; ElMessage.success('数据集草稿已创建'); router.push({ path: '/dashboard/integration', query: { tab: 'datasets' } }); }
  finally { saving.value=false; }
}
defineExpose({open});
</script>
<style scoped>
.folder-select { width: 100%; }
.field-row { display: grid; grid-template-columns: 1fr 1.2fr 100px auto; gap: 12px; margin-bottom: 12px; }
.hint { margin-top: 16px; }
.field-hint { margin: 6px 0 0; color: var(--el-text-color-secondary); font-size: var(--el-font-size-small); line-height: 1.6; }
</style>
