<template>
<div>
<section class="source-manager dashboard-management-tree-layout">
  <DataFolderSidebar
    v-model="sourceQuery.folderId"
    v-model:include-children="sourceQuery.includeChildren"
    :folders="sourceFolders"
    scope="source"
    title="数据源文件夹"
    system-folder
    :can-manage="checkPermi(['dashboard:dataset:edit'])"
    @change="searchSources"
    @refresh="refreshSources"
    @manage="folderManagerOpen = true"
  />
  <div class="dashboard-management-main">
    <ManagementToolbar title="数据源" :total="sourceDialog.total" noun="个数据源" :selection-count="selectedSourceIds.length" help-module="sources" :loading="sourceDialog.loading" @search="searchSources" @reset="resetSourceQuery" @refresh="refreshSources">
      <template #filters>
        <el-form-item label="搜索"><el-input v-model="sourceQuery.keyword" clearable placeholder="编码或名称" /></el-form-item>
        <el-form-item label="类型"><el-select v-model="sourceQuery.type" clearable placeholder="全部类型"><el-option label="关系数据库（MySQL）" value="MYSQL" /><el-option label="接口数据（HTTP）" value="HTTP" /><el-option label="实时数据（WebSocket）" value="WEBSOCKET" /></el-select></el-form-item>
        <el-form-item label="环境"><el-select v-model="sourceQuery.environment" clearable placeholder="全部环境"><el-option label="测试" value="TEST" /><el-option label="沙箱" value="SANDBOX" /><el-option label="生产" value="PRODUCTION" /></el-select></el-form-item>
        <el-form-item label="状态"><el-select v-model="sourceQuery.status" clearable placeholder="全部状态"><el-option v-for="status in ['READY', 'ACTIVE', 'DRAFT', 'DISABLED', 'NOT_CONNECTED']" :key="status" :label="sourceStatusLabel(status)" :value="status" /></el-select></el-form-item>
      </template>
      <template #selection><el-button v-hasPermi="['dashboard:dataset:edit']" :icon="FolderOpened" @click="folderMoveOpen = true">移动</el-button></template>
      <template #primary><DataManagementCreateButton kind="source" @click="openSourceCreate" /></template>
    </ManagementToolbar>
    <el-alert type="info" :closable="false" show-icon title="列表展示来源摘要；有编辑权限的用户可在配置表单中查看完整地址、账号和凭证。系统内置数据源由平台配置管理。" class="source-alert" />
    <section class="dashboard-management-table">
      <el-table v-loading="sourceDialog.loading" :data="sourceDialog.rows" row-key="code" @selection-change="selectedSourceIds = $event.map(row => row.dataSourceId)">
        <el-table-column v-if="checkPermi(['dashboard:dataset:edit'])" type="selection" width="48" :selectable="isMovableSource" />
        <el-table-column prop="name" label="数据源" min-width="220"><template #default="{ row }"><strong>{{ row.name }}</strong><small class="source-code">{{ row.code }}</small></template></el-table-column>
        <el-table-column label="文件夹" min-width="140" show-overflow-tooltip><template #default="{ row }">{{ sourceFolderName(row) }}</template></el-table-column>
        <el-table-column label="类型" min-width="150"><template #default="{ row }">{{ sourceTypeLabel(row.type) }}</template></el-table-column>
        <el-table-column label="环境" width="100"><template #default="{ row }">{{ sourceEnvironmentLabel(row.environment) }}</template></el-table-column>
        <el-table-column prop="host" label="主机" min-width="140" />
        <el-table-column label="状态" width="120"><template #default="{ row }"><el-tag :type="['READY', 'ACTIVE'].includes(row.status) ? 'success' : row.status === 'DISABLED' ? 'info' : 'warning'">{{ sourceStatusLabel(row.status) }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="275" fixed="right"><template #default="{ row }">
          <el-button v-hasPermi="['dashboard:dataset:test']" link type="primary" :loading="sourceDialog.testing === row.code" @click="testSource(row)">{{ row.type === 'HTTP' ? '测试接入' : '连接测试' }}</el-button>
          <el-button v-if="row.type === 'HTTP' && row.dataSourceId" v-hasPermi="['dashboard:dataset:list']" link type="primary" @click="emit('open-endpoints', row)">接口管理</el-button>
          <el-button v-if="isMovableSource(row)" v-hasPermi="['dashboard:dataset:edit']" link @click="editSource(row)">编辑</el-button>
          <el-button v-if="isMovableSource(row)" v-hasPermi="['dashboard:dataset:edit']" link type="danger" @click="removeSource(row)">删除</el-button>
        </template></el-table-column>
        <template #empty><DataManagementEmptyState v-if="!sourceDialog.loading" kind="source" description="暂无符合条件的数据源" /></template>
      </el-table>
      <pagination v-show="sourceDialog.total > 0" v-model:page="sourceQuery.pageNum" v-model:limit="sourceQuery.pageSize" :total="sourceDialog.total" @pagination="loadSources" />
    </section>
  </div>
</section>
<DataFolderManager v-model="folderManagerOpen" scope="source" :folders="sourceFolders" :default-parent-id="sourceQuery.folderId > 0 ? sourceQuery.folderId : 0" @changed="refreshSources" />
<DataFolderMoveDialog v-model="folderMoveOpen" scope="source" :folders="sourceFolders" :ids="selectedSourceIds" :default-folder-id="sourceQuery.folderId > 0 ? sourceQuery.folderId : 0" @moved="refreshSources" />

    <el-dialog
      v-model="sourceEditor.open"
      @closed="sourceEditor.form.secret = ''; sourceEditor.form.resolvedSecret = ''; sourceEditor.originalSecret = ''"
      :title="sourceEditor.editing ? '编辑数据源' : '新增数据源'"
      width="900px"
      destroy-on-close
    >
      <el-form
        ref="sourceFormRef"
        :model="sourceEditor.form"
        :rules="sourceRules"
        label-position="top"
        class="source-editor-form"
      >
        <el-alert
          v-if="sourceEditor.editing"
          type="warning"
          :closable="false"
          show-icon
          title="已保存的配置和凭证会在本表单中回显。修改后保存生效，凭证留空保持原值。"
          class="source-edit-alert"
        />
        <div class="source-editor-grid">
          <el-form-item label="数据源编码" prop="sourceCode"
            ><el-input
              v-model="sourceEditor.form.sourceCode"
              :disabled="sourceEditor.editing"
              placeholder="例如 business-source" /></el-form-item
          ><el-form-item label="数据源名称" prop="sourceName"
            ><el-input
              v-model="sourceEditor.form.sourceName"
              placeholder="便于数据集选择" /></el-form-item
          ><el-form-item label="所属文件夹" prop="folderId">
            <el-tree-select v-model="sourceEditor.form.folderId" :data="sourceFolderOptions" check-strictly filterable default-expand-all />
          </el-form-item><el-form-item label="类型" prop="sourceType"
            ><el-select
              v-model="sourceEditor.form.sourceType"
              @change="changeSourceType"
              ><el-option label="关系数据库（MySQL）" value="MYSQL" /><el-option
                label="接口数据（HTTP）"
                value="HTTP" /><el-option
                label="实时数据（WebSocket）"
                value="WEBSOCKET" /></el-select></el-form-item
          ><el-form-item label="状态"
            ><el-select v-model="sourceEditor.form.status"
              ><el-option label="草稿" value="DRAFT" /><el-option
                label="启用"
                value="ACTIVE" /><el-option
                label="停用"
                value="DISABLED" /></el-select
          ></el-form-item>
        </div>
        <el-form-item
          v-if="sourceEditor.form.sourceType === 'MYSQL'"
          label="数据库连接地址（JDBC）"
          prop="jdbcUrl"
          ><el-input
            v-model="sourceEditor.form.jdbcUrl"
            placeholder="jdbc:mysql://host:3306/database"
        /></el-form-item>
        <div
          v-if="sourceEditor.form.sourceType === 'MYSQL'"
          class="source-editor-grid"
        >
          <el-form-item label="数据库用户名" prop="username"
            ><el-input v-model="sourceEditor.form.username" /></el-form-item
          ><el-form-item label="数据库密码"
            ><el-input
              v-model="sourceEditor.form.secret"
              type="text"
              :placeholder="
                sourceEditor.hasSecret
                  ? '已保存凭证；留空保持不变'
                  : '请输入密码'
              "
          /></el-form-item>
        </div>
        <template v-else-if="sourceEditor.form.sourceType === 'HTTP'">
          <div class="source-editor-grid">
          <el-form-item
            label="接口基础地址（HTTP）"
            prop="baseUrl"
            ><el-input
              v-model="sourceEditor.form.baseUrl"
              placeholder="https://api.example.com 或 http://api.example.com:8080"
              @change="fillSourceAllowlist" /></el-form-item
          ><el-form-item label="网络策略"
            ><el-select v-model="sourceEditor.form.networkProfile" @change="fillSourceAllowlist"
              ><el-option label="公网加密访问（HTTPS）" value="PUBLIC_HTTPS" /><el-option
                label="公网访问（HTTP/HTTPS）"
                value="PUBLIC_HTTP" /><el-option
                label="批准的专网/隧道"
                value="PRIVATE_LINK" /></el-select></el-form-item
          ><el-form-item label="认证方式"
            ><el-select v-model="sourceEditor.form.authProvider"
              ><el-option label="无认证（公开只读）" value="NO_AUTH" /><el-option
                label="访问令牌（Bearer）"
                value="BEARER" /><el-option label="接口密钥（API Key）" value="API_KEY" /><el-option
                label="通用签名（HMAC-SHA256）"
                value="HMAC_V1" /><el-option
                label="应用标识与毫秒时间戳签名（HMAC）"
                value="HMAC_APPKEY_TIMESTAMP_V1" /></el-select></el-form-item
          ><el-form-item label="认证身份标识"
            ><el-input
              v-model="sourceEditor.form.identity"
              placeholder="HMAC AppKey 或已登记身份" /></el-form-item
          ><el-form-item label="凭证引用"
            ><el-input
              v-model="sourceEditor.form.credentialRef"
              placeholder="密钥服务引用或轮换标识" /></el-form-item
          ><el-form-item label="凭证明文"
            ><el-input
              v-model="sourceEditor.form.secret"
              type="text"
              :placeholder="
                sourceEditor.hasSecret
                  ? '已保存凭证；留空保持不变'
                  : '服务端加密保存'
              "
          /></el-form-item>
          </div>
          <div class="source-editor-grid">
            <el-form-item label="主机白名单" :required="sourceEditor.form.networkProfile === 'PUBLIC_HTTP'">
              <el-input v-model="sourceEditor.form.allowedHostsText" placeholder="api.example.com，多个用逗号分隔" @input="sourceNetworkDefaults.allowedHostsText = undefined" />
            </el-form-item>
            <el-form-item label="批准的地址范围（CIDR）">
              <el-input v-model="sourceEditor.form.allowedCidrsText" placeholder="10.10.0.0/16，专网策略必填" />
            </el-form-item>
            <el-form-item label="端口白名单" :required="sourceEditor.form.networkProfile === 'PUBLIC_HTTP'">
              <el-input v-model="sourceEditor.form.allowedPortsText" placeholder="HTTP 通常为 80，HTTPS 为 443；按实际端口填写" @input="sourceNetworkDefaults.allowedPortsText = undefined" />
            </el-form-item>
            <el-form-item label="来源时区">
              <TimezoneSelect v-model="sourceEditor.form.timezone" />
            </el-form-item>
            <el-form-item label="请求超时">
              <el-input-number v-model="sourceEditor.form.timeoutSeconds" :min="1" :max="3600" controls-position="right" />
            </el-form-item>
            <el-form-item label="并发上限">
              <el-input-number v-model="sourceEditor.form.concurrencyLimit" :min="1" :max="100" controls-position="right" />
            </el-form-item>
          </div>
          <div class="source-editor-grid">
            <el-form-item label="来源环境"><el-select v-model="sourceEditor.form.environment"><el-option label="测试" value="TEST" /><el-option label="沙箱" value="SANDBOX" /><el-option label="生产" value="PRODUCTION" /></el-select></el-form-item>
            <el-form-item label="每秒请求上限"><el-input-number v-model="sourceEditor.form.rateLimit" :min="1" :max="1000" controls-position="right" /></el-form-item>
          </div>
          <el-form-item v-if="sourceEditor.form.credentialRef" label="当前引用凭证"><el-input :model-value="sourceEditor.form.credentialResolved === false ? '引用暂未解析，请核对服务端配置' : sourceEditor.form.resolvedSecret" readonly autocomplete="off" /></el-form-item>
          <p class="field-hint">凭证引用可填写 ENV:变量名（须经服务端白名单允许）或 FILE:挂载文件名；挂载文件更新后自动使用新凭证。不同环境应登记独立来源。</p>
          <p class="field-hint">默认使用公网 HTTPS；无 HTTPS 的第三方可选“公网访问（HTTP/HTTPS）”，并填写主机和端口白名单。公网策略均拒绝内网目标；专网/隧道需填写批准的地址范围。所有策略均检查主机、端口和解析地址。</p>
        </template>
        <div v-else class="source-editor-grid">
          <el-form-item label="实时连接地址（WebSocket）" prop="baseUrl">
            <el-input v-model="sourceEditor.form.baseUrl" placeholder="wss://approved.example" />
          </el-form-item>
          <el-form-item label="实时连接路径（WebSocket）" prop="path">
            <el-input v-model="sourceEditor.form.path" placeholder="/stream" />
          </el-form-item>
          <el-form-item label="访问令牌">
            <el-input
              v-model="sourceEditor.form.secret"
              type="text"
              :placeholder="sourceEditor.hasSecret ? '已保存凭证；留空保持不变' : '可选，服务端加密保存'"
            />
          </el-form-item>
        </div>
        <el-divider content-position="left">数据保留</el-divider>
        <p class="field-hint" v-if="sourceEditor.form.sourceType === 'HTTP'">业务数据：接口取数不自动保存业务历史。临时缓存：在下属接口中配置“缓存最新响应”及有效秒数。</p>
        <p class="field-hint" v-else-if="sourceEditor.form.sourceType === 'WEBSOCKET'">业务数据：实时消息只在内存中暂存，不写入业务历史。临时缓存：新消息覆盖当前值，无人读取约 2 分钟后回收连接，重启后不恢复消息。</p>
        <p class="field-hint" v-else>业务数据保留由所连接的数据库管理，平台查询不会自动复制或清理该数据库的业务记录。</p>
        <p class="field-hint">日志及报文：公共日志期限在“接入运维 → 保留策略”配置；新接口测试、数据集测试日志不保存请求或返回正文。</p>
        <el-form-item label="备注"
          ><el-input
            v-model="sourceEditor.form.remark"
            type="textarea"
            :rows="2"
            maxlength="500"
            show-word-limit
        /></el-form-item>
        <el-alert
          type="warning"
          :closable="false"
          show-icon
          title="配置表单可查看凭证明文；数据库仍加密保存，页面组件仅引用来源编码。"
        />
      </el-form>
      <template #footer
        ><el-button @click="sourceEditor.open = false">取消</el-button
        ><el-button
          type="primary"
          :loading="sourceEditor.saving"
          @click="submitSource"
          >保存数据源</el-button
        ></template
      >
    </el-dialog>

</div>
</template>
<script setup>
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { FolderOpened } from '@element-plus/icons-vue';
import ManagementToolbar from '@/components/DashboardManagement/ManagementToolbar.vue';
import DataManagementCreateButton from '@/components/DashboardDataManagement/CreateButton.vue';
import { checkPermi } from '@/utils/permission';
import { listDataFolders } from '@/api/dashboardDataFolder';
import { buildDataFolderOptions } from '@/utils/dashboardDataFolder';
import { fillHttpSourceAllowlist, httpSourceNetworkError, splitSourceList } from '@/utils/dashboardDataSourceNetwork';
import DataFolderSidebar from '@/components/DashboardDataManagement/DataFolderSidebar.vue';
import DataFolderManager from '@/components/DashboardDataManagement/DataFolderManager.vue';
import DataFolderMoveDialog from '@/components/DashboardDataManagement/DataFolderMoveDialog.vue';
import DataManagementEmptyState from '@/components/DashboardDataManagement/EmptyState.vue';
import TimezoneSelect from '@/components/DashboardDataManagement/TimezoneSelect.vue';
import { listDashboardDataSources, pageDashboardDataSources, testDashboardDataSource, getDashboardDataSourceConfiguration, addDashboardDataSource, updateDashboardDataSource, delDashboardDataSource } from '@/api/dashboard';
const emit = defineEmits(['loaded', 'open-endpoints']);
const sourceDialog = reactive({ rows: [], total: 0, loading: false, testing: '' });
const sourceQuery = reactive({ pageNum: 1, pageSize: 20, keyword: '', type: '', environment: '', status: '', folderId: null, includeChildren: false });
const sourceFolders = ref([]);
const sourceFolderOptions = computed(() => buildDataFolderOptions(sourceFolders.value));
const folderManagerOpen = ref(false);
const folderMoveOpen = ref(false);
const selectedSourceIds = ref([]);
let sourceRequestId = 0, sourceOptionsRequestId = 0;
const sourceFormRef = ref();
const sourceEditor = reactive({ open: false, editing: false, saving: false, hasSecret: false, originalSecret: "", form: {} });
let sourceNetworkDefaults = {};
const sourceRules = {
 sourceCode: [{ required: true, message: '编码不能为空', trigger: 'blur' }],
 sourceName: [{ required: true, message: '名称不能为空', trigger: 'blur' }],
 sourceType: [{ required: true, message: '请选择类型', trigger: 'change' }],
};
async function loadSources() {
  const requestId = ++sourceRequestId;
  sourceDialog.loading = true;
  selectedSourceIds.value = [];
  // 固定本次查询快照，后续输入和翻页不会改写正在发送的参数。
  const params = { ...sourceQuery, folderId: sourceQuery.folderId ?? undefined };
  try {
    let response = await pageDashboardDataSources(params);
    if (requestId !== sourceRequestId) return;
    const lastPage = Math.max(1, Math.ceil(Number(response.total || 0) / params.pageSize));
    if (params.pageNum > lastPage) {
      params.pageNum = lastPage;
      sourceQuery.pageNum = lastPage;
      response = await pageDashboardDataSources(params);
      if (requestId !== sourceRequestId) return;
    }
    sourceDialog.rows = response.rows || [];
    sourceDialog.total = Number(response.total || 0);
  } finally {
    if (requestId === sourceRequestId) sourceDialog.loading = false;
  }
}
async function loadSourceOptions() {
  const requestId = ++sourceOptionsRequestId;
  const response = await listDashboardDataSources();
  // 接口选择器只接收完整来源，分页列表和目录筛选不得污染其选项。
  if (requestId === sourceOptionsRequestId) emit('loaded', response.data || response.rows || []);
}
async function loadSourceFolders() {
  const response = await listDataFolders('source');
  sourceFolders.value = response.data || response.rows || [];
  if (sourceQuery.folderId > 0 && !sourceFolders.value.some(folder => Number(folder.folderId) === Number(sourceQuery.folderId))) {
    sourceQuery.folderId = null;
    sourceQuery.pageNum = 1;
  }
}
async function refreshSources() {
  await Promise.all([loadSourceFolders(), loadSourceOptions()]);
  return loadSources();
}
function searchSources() {
  sourceQuery.pageNum = 1;
  return loadSources();
}
function resetSourceQuery() {
  Object.assign(sourceQuery, { keyword: '', type: '', environment: '', status: '' });
  return searchSources();
}
function isMovableSource(row) {
  return Number(row.dataSourceId) > 0 && row.systemSource !== true && Number(row.folderId) !== -1;
}
function sourceFolderName(row) {
  if (!isMovableSource(row)) return '系统内置';
  return row.folderName || sourceFolders.value.find(folder => Number(folder.folderId) === Number(row.folderId))?.folderName || '未分类';
}
function sourceEnvironmentLabel(value) {
  return { TEST: '测试', SANDBOX: '沙箱', PRODUCTION: '生产' }[value] || '—';
}
function testSource(row) {
  if (row.type === "HTTP" && row.dataSourceId) { emit("open-endpoints", row); return; }
  sourceDialog.testing = row.code;
  testDashboardDataSource(row.code)
    .then((res) => {
      const data = res.data || res;

      ElMessage[data.quality === "SUCCESS" ? "success" : "warning"](
        data.quality === "SUCCESS"
          ? `数据源连接成功（${data.latencyMs} ms）`
          : data.message || "数据源连接失败",
      );
    })
    .finally(() => {
      sourceDialog.testing = "";
    });
}
function emptySourceForm() {
  return {
    sourceCode: "",
    sourceName: "",
    folderId: 0,
    sourceType: "MYSQL",
    status: "DRAFT",
    jdbcUrl: "",
    username: "",
    baseUrl: "",
    path: "/",
    method: "GET",
    environment: "TEST",
    rateLimit: 20,
    networkProfile: "PUBLIC_HTTPS",
    allowedHostsText: "",
    allowedCidrsText: "",
    allowedPortsText: "",
    authProvider: "NO_AUTH",
    identity: "",
    credentialRef: "",
    timezone: "Asia/Shanghai",
    timeoutSeconds: 10,
    concurrencyLimit: 20,
    secret: "",
    remark: "",
  };
}
function openSourceCreate() {
  sourceEditor.editing = false;
  sourceEditor.hasSecret = false;
  sourceEditor.originalSecret = "";
  sourceNetworkDefaults = {};
  sourceEditor.form = { ...emptySourceForm(), folderId: sourceQuery.folderId > 0 ? sourceQuery.folderId : 0 };
  sourceEditor.open = true;
}
function editSource(row) {
  sourceEditor.saving = false;
  getDashboardDataSourceConfiguration(row.code).then((res) => {
    const data = res.data || res;
    const config = parseJson(data.configJson, {});
    sourceEditor.editing = true;
    sourceEditor.hasSecret = Boolean(data.hasSecret);
    sourceEditor.originalSecret = data.secret || "";
    sourceNetworkDefaults = {};
    sourceEditor.form = {
      ...emptySourceForm(),
      dataSourceId: data.dataSourceId,
      folderId: Number(data.folderId ?? row.folderId) || 0,
      sourceCode: data.sourceCode,
      sourceName: data.sourceName,
      sourceType: data.sourceType,
      status: data.status,
      remark: data.remark || "",
      jdbcUrl: config.jdbcUrl || "",
      username: config.username || "",
      baseUrl: config.baseUrl || "",
      secret: data.secret || "",
      resolvedSecret: data.resolvedSecret || "",
      credentialResolved: data.credentialResolved,
      path: config.path || "",
      method: config.method || "GET",
      environment: config.environment || "TEST",
      rateLimit: Number(config.rateLimit) || 20,
      networkProfile: config.networkProfile || "PUBLIC_HTTPS",
      allowedHostsText: (config.allowedHosts || []).join(","),
      allowedCidrsText: (config.allowedCidrs || []).join(","),
      allowedPortsText: (config.allowedPorts || []).join(","),
      authProvider: config.authProvider || "NO_AUTH",
      identity: config.identity || "",
      credentialRef: config.credentialRef || "",
      timezone: config.timezone || "Asia/Shanghai",
      timeoutSeconds: Number(config.timeoutSeconds) || 10,
      concurrencyLimit: Number(config.concurrencyLimit) || 20,
    };
    sourceEditor.open = true;
  });
}
function changeSourceType() {
  if (sourceEditor.form.sourceType === "HTTP" && !sourceEditor.form.path)
    sourceEditor.form.path = "/";
}
function fillSourceAllowlist() {
  sourceNetworkDefaults = fillHttpSourceAllowlist(sourceEditor.form, sourceNetworkDefaults);
}
function submitSource() {
  sourceFormRef.value.validate((valid) => {
    if (!valid) return;
    const form = sourceEditor.form;
    const isCreate = !sourceEditor.editing;
    if (form.sourceType === "MYSQL") {
      const touched = Boolean(form.jdbcUrl?.trim() || form.username?.trim() || form.secret?.trim());
      if ((isCreate || touched) && !(form.jdbcUrl?.trim() && form.username?.trim()))
        return ElMessage.warning("请填写完整的 MySQL 连接地址和用户名");
    } else if (isCreate && !(form.baseUrl?.trim() && form.path?.trim())) {
      return ElMessage.warning("请填写完整的服务地址和默认路径");
    }
    if (form.sourceType === "HTTP") {
      if (!form.authProvider) return ElMessage.warning("请选择认证方式");
      if (["HMAC_V1", "HMAC_APPKEY_TIMESTAMP_V1"].includes(form.authProvider) && !form.identity?.trim())
        return ElMessage.warning("HMAC 来源必须填写认证身份标识");
      const networkError = httpSourceNetworkError(form);
      if (networkError) return ElMessage.warning(networkError);
    }
    const payload = {
      dataSourceId: form.dataSourceId,
      folderId: Number(form.folderId) || 0,
      sourceCode: form.sourceCode.trim(),
      sourceName: form.sourceName.trim(),
      sourceType: form.sourceType,
      status: form.status,
      secret: form.secret && form.secret !== sourceEditor.originalSecret ? form.secret : undefined,
      remark: form.remark,
    };
    const mysqlConnectionFilled = Boolean(form.jdbcUrl?.trim() && form.username?.trim());
    if (form.sourceType === "MYSQL" && mysqlConnectionFilled) {
      payload.configJson = JSON.stringify({ jdbcUrl: form.jdbcUrl.trim(), username: form.username.trim() });
    } else if (form.sourceType === "HTTP") {
      const ports = splitSourceList(form.allowedPortsText).map((value) => Number(value));
      const config = {
        path: form.path.trim(),
        method: form.method,
        environment: form.environment,
        rateLimit: form.rateLimit,
        networkProfile: form.networkProfile,
        allowedHosts: splitSourceList(form.allowedHostsText),
        allowedCidrs: splitSourceList(form.allowedCidrsText),
        allowedPorts: ports,
        authProvider: form.authProvider,
        identity: String(form.identity || "").trim(),
        credentialRef: String(form.credentialRef || "").trim(),
        timezone: String(form.timezone || "Asia/Shanghai").trim(),
        timeoutSeconds: form.timeoutSeconds,
        concurrencyLimit: form.concurrencyLimit,
      };
      if (form.baseUrl?.trim()) config.baseUrl = form.baseUrl.trim();
      payload.configJson = JSON.stringify(config);
    } else if (form.sourceType === "WEBSOCKET") {
      const config = { path: form.path.trim(), method: form.method };
      if (form.baseUrl?.trim()) config.baseUrl = form.baseUrl.trim();
      payload.configJson = JSON.stringify(config);
    }
    sourceEditor.saving = true;
    const action = sourceEditor.editing
      ? updateDashboardDataSource(payload)
      : addDashboardDataSource(payload);
    action
      .then(() => {
        ElMessage.success("数据源已保存");
        sourceEditor.open = false;
        refreshSources();
      })
      .finally(() => {
        sourceEditor.saving = false;
      });
  });
}

function removeSource(row) {
  ElMessageBox.confirm(
    `确定删除数据源“${row.name}”吗？删除前请确认没有数据集引用。`,
    "删除数据源",
    { type: "warning" },
  )
    .then(() =>
      delDashboardDataSource(row.dataSourceId).then(() => {
        ElMessage.success("数据源已删除");
        refreshSources();
      }),
    )
    .catch(() => {});
}
function sourceTypeLabel(value) { return { MYSQL: "关系数据库（MySQL）", HTTP: "接口数据（HTTP）", WEBSOCKET: "实时数据（WebSocket）" }[value] || `数据来源（${value || "未设置"}）`; }
function sourceStatusLabel(value) {
  return (
    {
      READY: "已配置",
      ACTIVE: "已启用",
      DRAFT: "草稿",
      DISABLED: "已停用",
      NOT_CONNECTED: "未接入",
    }[value] || "未知"
  );
}
function parseJson(value, fallback) {
  try {
    const result = typeof value === "string" ? JSON.parse(value || "") : value;
    return result ?? fallback;
  } catch {
    return fallback;
  }
}
onMounted(refreshSources);
defineExpose({ refresh: refreshSources, openCreate: openSourceCreate });
</script>
<style scoped>
.source-alert { margin-bottom: 16px; }
.source-code { display: block; color: var(--el-text-color-secondary); margin-top: 4px; }
.source-editor-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 18px; margin-top: 16px; }
.source-editor-form :deep(.el-select), .source-editor-form :deep(.el-tree-select), .source-editor-form :deep(.el-input-number) { width: 100%; }
.field-hint { color: var(--el-text-color-secondary); line-height: 1.6; }
@media (max-width: 760px) { .source-editor-grid { grid-template-columns: 1fr; } }
</style>
