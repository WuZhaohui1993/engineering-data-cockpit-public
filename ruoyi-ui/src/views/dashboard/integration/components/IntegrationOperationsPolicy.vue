<template>
  <section class="operations-policy" aria-label="接入运维保留策略" v-loading="loading">
    <div class="policy-heading">
      <div>
        <h3>保留策略</h3>
        <p>全局配置，适用于所有接入来源，不受上方筛选影响。修改后按新期限分批清理已有日志。</p>
      </div>
      <el-button :icon="Refresh" :disabled="loading || saving" @click="load">重新读取</el-button>
    </div>
    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon />
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top" class="policy-form" @submit.prevent="save">
      <div class="policy-grid">
        <el-form-item v-for="field in integrationOperationsPolicyFields" :key="field.key" :label="field.label" :prop="field.key">
          <el-input-number :key="`${field.key}:${canEdit && loaded && !saving}`" v-model="form[field.key]" :min="1" :max="3650" :precision="0" controls-position="right" :disabled="!canEdit || !loaded || saving" />
          <div class="policy-help">{{ field.help }}</div>
        </el-form-item>
      </div>
      <div class="policy-explanation">
        <p>未确认告警继续保留；标准业务数据和推送原文仍在对应接入方的“数据保留”中配置。</p>
        <p>新测试不保存请求和返回正文，仅保留执行结果、耗时等排查信息；历史测试日志按配置期限清理。</p>
      </div>
      <div class="policy-actions">
        <el-button v-hasPermi="['dashboard:integration:policy']" type="primary" native-type="submit" :loading="saving" :disabled="!canEdit || !loaded || loading || !dirty">保存保留策略</el-button>
        <span v-if="!canEdit" class="policy-help">当前账号可查看策略，修改需要保留策略管理权限。</span>
        <span v-else-if="dirty" class="policy-help">有未保存的修改。</span>
      </div>
    </el-form>
  </section>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import { checkPermi } from '@/utils/permission';
import { integrationOperationsPolicyFields, validateIntegrationOperationsPolicy } from '@/utils/dashboardIntegrationPresentation';
import { getIntegrationOperationsPolicy, updateIntegrationOperationsPolicy } from '@/api/dashboardIntegration';

const loading = ref(false), saving = ref(false), loaded = ref(false), loadError = ref('');
const formRef = ref(), form = reactive({ auditRetentionDays: undefined, resolvedAlertRetentionDays: undefined, testLogRetentionDays: undefined });
const saved = ref({}), canEdit = computed(() => checkPermi(['dashboard:integration:policy']));
const dirty = computed(() => loaded.value && integrationOperationsPolicyFields.some(field => form[field.key] !== saved.value[field.key]));
const rules = Object.fromEntries(integrationOperationsPolicyFields.map(field => [field.key, [{
  trigger: 'blur', validator: (_rule, value, callback) => {
    if (typeof value !== 'number' || !Number.isInteger(value) || value < 1 || value > 3650) callback(new Error('请输入 1 至 3650 的整数'));
    else callback();
  },
}]]));
let generation = 0;

async function load() {
  const current = ++generation;
  loading.value = true;
  loadError.value = '';
  try {
    const response = await getIntegrationOperationsPolicy();
    if (current !== generation) return;
    const value = validateIntegrationOperationsPolicy(response.data);
    saved.value = { ...value };
    Object.assign(form, value);
    loaded.value = true;
    formRef.value?.clearValidate();
  } catch {
    if (current === generation) {
      loaded.value = false;
      loadError.value = '保留策略读取失败，请重新读取后再保存。';
    }
  } finally { if (current === generation) loading.value = false; }
}

async function save() {
  if (!canEdit.value || !loaded.value || loading.value || saving.value) return;
  let payload;
  try { payload = validateIntegrationOperationsPolicy(form); }
  catch (error) { ElMessage.warning(error.message); return; }
  if (formRef.value && !(await formRef.value.validate().catch(() => false))) return;
  if (!canEdit.value || !loaded.value || loading.value || saving.value) return;
  const current = ++generation;
  saving.value = true;
  loadError.value = '';
  try {
    const response = await updateIntegrationOperationsPolicy(payload);
    if (current !== generation) return;
    const value = validateIntegrationOperationsPolicy(response.data || payload);
    Object.assign(form, value);
    saved.value = { ...value };
    ElMessage.success('保留策略已保存，将按期限分批清理已有日志');
  } catch {
    if (current === generation) loadError.value = '保留策略保存失败，修改仍保留在表单中，请重试。';
  } finally { if (current === generation) saving.value = false; }
}

onMounted(load);
onBeforeUnmount(() => { generation++; });
</script>

<style scoped>
.operations-policy { min-width: 0; }
.policy-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 18px; }
.policy-heading h3 { margin: 0 0 8px; font-size: var(--el-font-size-base); font-weight: 600; color: var(--el-text-color-primary); }
.policy-heading p, .policy-help, .policy-explanation { margin: 0; color: var(--el-text-color-secondary); font-size: var(--el-font-size-small); line-height: 1.65; }
.policy-heading > .el-button { flex-shrink: 0; }
.policy-form { margin-top: 18px; }
.policy-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 20px; }
.policy-grid :deep(.el-form-item__content) { display: block; }
.policy-grid :deep(.el-input-number) { width: 100%; max-width: 280px; }
.policy-help { margin-top: 6px; }
.policy-explanation { padding: 12px 14px; background: var(--el-fill-color-light); border-radius: var(--el-border-radius-base); }
.policy-explanation p { margin: 0; }
.policy-explanation p + p { margin-top: 4px; }
.policy-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; margin-top: 18px; }
.policy-actions .policy-help { margin: 0; }
@media (max-width: 1000px) { .policy-grid { grid-template-columns: 1fr; gap: 0; } }
</style>
