<template>
  <section class="operations-view" aria-label="接入运维">
    <ManagementToolbar title="接入运维" :loading="loading || resourcesLoading" @search="search" @reset="reset" @refresh="refresh">
      <template #filters>
        <el-form-item label="接入类型">
          <el-select v-model="query.category" clearable placeholder="全部接入类型" aria-label="接入类型" @change="changeCategory">
            <el-option v-for="item in integrationOperationCategories" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="来源" class="filter-wide">
          <el-select :model-value="selectedResource" clearable filterable :loading="resourcesLoading" placeholder="全部来源或接入方" aria-label="来源或接入方" @change="changeResource">
            <el-option v-for="item in resourceOptions" :key="integrationOperationResourceKey(item)" :value="integrationOperationResourceKey(item)" :label="resourceOptionLabel(item)" />
          </el-select>
        </el-form-item>
        <el-form-item label="自动刷新"><el-switch v-model="autoRefresh" aria-label="自动刷新接入运维" /></el-form-item>
      </template>
    </ManagementToolbar>

    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon />
    <div class="overview-groups" :class="{ 'has-inbound': showInboundMetrics }" v-loading="loading && !lastUpdated">
      <section class="overview-group" :aria-label="overviewTitle">
        <h3>{{ overviewTitle }}</h3>
        <el-row :gutter="20" class="metrics">
          <el-col v-for="item in visibleRequestMetrics" :key="item.key" :span="24 / visibleRequestMetrics.length"><el-statistic :title="item.label" :value="metricValue(item.key)" /></el-col>
        </el-row>
      </section>
      <section v-if="showInboundMetrics" class="overview-group" aria-label="推送业务">
        <h3>推送业务</h3>
        <el-row :gutter="20" class="metrics">
          <el-col v-for="item in inboundMetrics" :key="item.key" :span="8"><el-statistic :title="item.label" :value="metricValue(item.key)" /></el-col>
        </el-row>
      </section>
    </div>
    <div class="overview-caption">
      <span>指标与记录按当前类型和来源筛选；请求统计不包含连接事件和保留期清理。</span>
      <span v-if="lastUpdated">最近更新：{{ formatDashboardDateTime(lastUpdated) }}</span>
    </div>

    <el-tabs v-model="tab" class="operations-tabs" @tab-change="changeTab">
      <el-tab-pane name="alerts"><template #label><span class="operations-tab-label"><el-icon><Warning /></el-icon>接入告警</span></template></el-tab-pane>
      <el-tab-pane name="audits"><template #label><span class="operations-tab-label"><el-icon><Document /></el-icon>访问审计</span></template></el-tab-pane>
      <el-tab-pane v-if="showConnections" name="connections"><template #label><span class="operations-tab-label"><el-icon><Connection /></el-icon>实时连接</span></template></el-tab-pane>
      <el-tab-pane name="policy"><template #label><span class="operations-tab-label"><el-icon><Setting /></el-icon>保留策略</span></template></el-tab-pane>
    </el-tabs>

    <IntegrationOperationsPolicy v-if="tab === 'policy'" />
    <template v-else-if="tab === 'connections'">
      <p class="operations-description">仅显示当前应用实例的 WebSocket 连接，累计数对应当前连接。连接空闲两分钟后释放，服务重启后重新计数。</p>
      <el-alert v-if="Number(websockets.eventDropCount || 0) || Number(websockets.eventPersistenceErrorCount || 0)" type="warning" :closable="false" show-icon :title="`当前实例的连接事件记录不完整：队列丢弃 ${Number(websockets.eventDropCount || 0)} 条，保存失败 ${Number(websockets.eventPersistenceErrorCount || 0)} 条。请检查后台日志。`" />
      <el-row :gutter="18" class="connection-metrics">
        <el-col v-for="item in connectionMetrics" :key="item.key" :xs="12" :sm="8" :lg="4"><el-statistic :title="item.label" :value="Number(websockets[item.key] || 0)" /></el-col>
      </el-row>
      <div class="table-caption">共 {{ Number(websockets.connectionCount || 0) }} 个连接；累计接收 {{ Number(websockets.messageCount || 0) }} 条消息，{{ formatIntegrationBytes(websockets.byteCount || 0) }}。</div>
      <section class="dashboard-management-table">
        <el-table v-loading="loading" :data="connectionRows" row-key="connectionId">
          <el-table-column label="来源" min-width="180" show-overflow-tooltip><template #default="{ row }">{{ displayResource(row.resourceCode, 'WEBSOCKET') }}</template></el-table-column>
          <el-table-column label="连接状态" width="115"><template #default="{ row }"><el-tag :type="integrationConnectionTag(row.status)">{{ integrationConnectionStatus(row.status) }}</el-tag></template></el-table-column>
          <el-table-column label="重连次数" prop="reconnectCount" width="100" />
          <el-table-column label="最近连接" min-width="180"><template #default="{ row }">{{ formatDashboardDateTime(row.connectedAt) }}</template></el-table-column>
          <el-table-column label="最近消息" min-width="180"><template #default="{ row }">{{ formatDashboardDateTime(row.lastMessageAt) }}</template></el-table-column>
          <el-table-column label="累计消息" prop="messageCount" width="110" />
          <el-table-column label="累计数据量" min-width="120"><template #default="{ row }">{{ formatIntegrationBytes(row.byteCount) }}</template></el-table-column>
          <el-table-column label="最近错误" min-width="180"><template #default="{ row }">{{ integrationResultLabel(row.lastErrorCode) }}</template></el-table-column>
          <template #empty><el-empty v-if="!loading" description="暂无实时连接；运行页使用 WebSocket 数据集后可在此查看" :image-size="88" /></template>
        </el-table>
        <pagination v-show="connectionTotal > 0" v-model:page="query.pageNum" v-model:limit="query.pageSize" :total="connectionTotal" @pagination="paginateConnections" />
      </section>
    </template>
    <template v-else>
      <div class="table-heading">
        <p class="operations-description">{{ tab === 'alerts' ? '先排查来源或处理失败原因，再确认告警。需要重放的推送消息请到“死信处理”操作。' : '记录主动取数、推送、实时连接、媒体访问和保留期清理的结果。推送接收类型同时包含该接入方的保留期清理审计。' }}</p>
        <span class="table-caption">共 {{ total }} 条记录</span>
      </div>
      <section class="dashboard-management-table">
        <el-table v-loading="loading" :data="rows" :row-key="tab === 'alerts' ? 'alertId' : 'auditId'">
          <el-table-column label="来源 / 接入方" min-width="200" show-overflow-tooltip><template #default="{ row }">{{ displayResource(row.resourceCode, row.category) }}</template></el-table-column>
          <el-table-column label="类型" width="125"><template #default="{ row }">{{ auditCategoryLabel(row.category) }}</template></el-table-column>
          <template v-if="tab === 'alerts'">
            <el-table-column label="错误说明（错误码）" min-width="220"><template #default="{ row }">{{ integrationResultLabel(row.errorCode) }}</template></el-table-column>
            <el-table-column label="发生次数" prop="occurrences" width="110" />
            <el-table-column label="状态" width="110"><template #default="{ row }"><el-tag :type="row.status === 'OPEN' ? 'danger' : 'info'">{{ row.status === 'OPEN' ? '待处理' : '已确认' }}</el-tag></template></el-table-column>
            <el-table-column label="最近发生" min-width="180"><template #default="{ row }">{{ formatDashboardDateTime(row.lastAt) }}</template></el-table-column>
            <el-table-column label="操作" width="100"><template #default="{ row }"><el-button v-if="row.status === 'OPEN'" v-hasPermi="['dashboard:integration:ack']" link type="primary" :loading="acknowledgingId === row.alertId" @click="acknowledge(row)">确认</el-button></template></el-table-column>
          </template>
          <template v-else>
            <el-table-column label="请求编号（ID）" prop="requestId" min-width="220" show-overflow-tooltip />
            <el-table-column label="结果" min-width="200"><template #default="{ row }">{{ integrationResultLabel(row.outcome) }}</template></el-table-column>
            <el-table-column label="数据量" min-width="110"><template #default="{ row }">{{ formatIntegrationBytes(row.byteCount) }}</template></el-table-column>
            <el-table-column label="耗时（毫秒）" prop="durationMs" width="130" />
            <el-table-column label="访问时间" min-width="180"><template #default="{ row }">{{ formatDashboardDateTime(row.createdAt) }}</template></el-table-column>
          </template>
          <template #empty><el-empty v-if="!loading" description="暂无符合条件的记录" :image-size="88" /></template>
        </el-table>
        <pagination v-show="total > 0" v-model:page="query.pageNum" v-model:limit="query.pageSize" :total="total" @pagination="load" />
      </section>
    </template>
  </section>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { Warning, Document, Connection, Setting } from '@element-plus/icons-vue';
import ManagementToolbar from '@/components/DashboardManagement/ManagementToolbar.vue';
import IntegrationOperationsPolicy from './IntegrationOperationsPolicy.vue';
import { formatDashboardDateTime } from '@/utils/dashboard';
import { checkPermi } from '@/utils/permission';
import { auditCategoryLabel, integrationResultLabel, integrationOperationCategories, integrationOperationResourceKey, integrationOperationResources, integrationConnectionStatus, integrationConnectionTag, formatIntegrationBytes } from '@/utils/dashboardIntegrationPresentation';
import { getIntegrationMetrics, listIntegrationAudits, listIntegrationAlerts, acknowledgeIntegrationAlert, listIntegrationOperationResources, getIntegrationWebSocketStatus } from '@/api/dashboardIntegration';

const metrics = ref({}), resources = ref([]), websockets = ref({ connections: [] });
const requestMetrics = [{ key: 'openAlerts', label: '待处理告警' }, { key: 'requests24h', label: '24 小时请求' }, { key: 'failures24h', label: '24 小时异常' }];
const inboundMetrics = [{ key: 'pending', label: '待处理消息' }, { key: 'deadLetters', label: '死信消息' }, { key: 'records', label: '标准业务记录' }];
const connectionMetrics = [{ key: 'connectionCount', label: '连接总数' }, { key: 'connectedCount', label: '已连接' }, { key: 'connectingCount', label: '连接中' }, { key: 'reconnectingCount', label: '重连中' }, { key: 'errorCount', label: '连接异常' }];
const tab = ref('alerts'), rows = ref([]), total = ref(0), loading = ref(false), resourcesLoading = ref(false), autoRefresh = ref(true);
const query = reactive({ pageNum: 1, pageSize: 20, category: '', resourceCode: '' });
const loadError = ref(''), lastUpdated = ref(null), acknowledgingId = ref(null);
const resourceOptions = computed(() => integrationOperationResources(resources.value, query.category));
const selectedResource = computed(() => {
  const row = resourceOptions.value.find(item => item.resourceCode === query.resourceCode);
  return row ? integrationOperationResourceKey(row) : '';
});
const showInboundMetrics = computed(() => !query.category || query.category === 'INBOUND');
const showConnections = computed(() => !query.category || query.category === 'WEBSOCKET');
const overviewTitle = computed(() => ({ WEBSOCKET: '连接活动', INBOUND_RETENTION: '清理活动' })[query.category] || '请求概况');
const visibleRequestMetrics = computed(() => query.category === 'WEBSOCKET'
  ? [{ key: 'openAlerts', label: '待处理告警' }, { key: 'websocketEvents24h', label: '24 小时连接事件' }]
  : query.category === 'INBOUND_RETENTION' ? [{ key: 'retentionRuns24h', label: '24 小时清理次数' }] : requestMetrics);
const connectionTotal = computed(() => Array.isArray(websockets.value.connections) ? websockets.value.connections.length : 0);
const connectionRows = computed(() => (websockets.value.connections || []).slice((query.pageNum - 1) * query.pageSize, query.pageNum * query.pageSize));
let timer, generation = 0, resourceGeneration = 0;

function metricValue(key) { return Number(metrics.value[key] || 0); }
function resourceOptionLabel(row) {
  const name = row.resourceName && row.resourceName !== row.resourceCode ? `${row.resourceName}（${row.resourceCode}）` : row.resourceCode;
  return `${name} · ${auditCategoryLabel(row.category)}`;
}
function displayResource(code, category) {
  const row = resources.value.find(item => item.category === category && (item.resourceCode === code
    || code?.startsWith(`${item.resourceCode}/`) || code?.startsWith(`${item.resourceCode}:`)));
  return row?.resourceName && row.resourceName !== code ? `${row.resourceName}（${code}）` : code || '—';
}

async function loadResources() {
  const current = ++resourceGeneration;
  resourcesLoading.value = true;
  try {
    const response = await listIntegrationOperationResources();
    if (current === resourceGeneration) resources.value = Array.isArray(response.data) ? response.data : [];
  } finally { if (current === resourceGeneration) resourcesLoading.value = false; }
}

async function load() {
  const current = ++generation;
  loading.value = true;
  loadError.value = '';
  const params = { ...query }, currentTab = tab.value;
  const filters = { category: params.category || undefined, resourceCode: params.resourceCode || undefined };
  try {
    const pageRequest = currentTab === 'alerts' ? listIntegrationAlerts : listIntegrationAudits;
    let [overview, result] = await Promise.all([
      getIntegrationMetrics(filters),
      currentTab === 'connections' ? getIntegrationWebSocketStatus({ resourceCode: filters.resourceCode })
        : currentTab === 'policy' ? Promise.resolve(null) : pageRequest({ ...params, ...filters }),
    ]);
    if (current !== generation) return;
    if (currentTab === 'connections') {
      websockets.value = result.data || { connections: [] };
      paginateConnections();
    } else if (currentTab !== 'policy') {
      const lastPage = Math.max(1, Math.ceil(Number(result.total || 0) / params.pageSize));
      if (params.pageNum > lastPage) {
        params.pageNum = lastPage;
        result = await pageRequest({ ...params, ...filters });
        if (current !== generation) return;
        query.pageNum = lastPage;
      }
      rows.value = Array.isArray(result.rows) ? result.rows : [];
      total.value = Number(result.total || 0);
    }
    metrics.value = overview.data || {};
    lastUpdated.value = new Date();
  } catch {
    if (current === generation) loadError.value = lastUpdated.value
      ? '接入运维加载失败，当前显示为最近一次成功结果，请刷新重试。'
      : '接入运维加载失败，请刷新重试。';
  } finally { if (current === generation) loading.value = false; }
}

function clearResults() {
  rows.value = [];
  total.value = 0;
  metrics.value = {};
  websockets.value = { connections: [] };
  lastUpdated.value = null;
}
function search() { query.pageNum = 1; return load(); }
function changeCategory() {
  query.resourceCode = '';
  if (tab.value === 'connections' && !showConnections.value) tab.value = 'alerts';
  clearResults();
  return search();
}
function changeResource(value) {
  const row = resources.value.find(item => integrationOperationResourceKey(item) === value);
  query.resourceCode = row?.resourceCode || '';
  if (row && !query.category) query.category = row.category;
  if (tab.value === 'connections' && !showConnections.value) tab.value = 'alerts';
  clearResults();
  return search();
}
function reset() { query.category = ''; query.resourceCode = ''; clearResults(); return search(); }
function changeTab() { query.pageNum = 1; rows.value = []; total.value = 0; return load(); }
function paginateConnections() { query.pageNum = Math.min(query.pageNum, Math.max(1, Math.ceil(connectionTotal.value / query.pageSize))); }
async function refresh() { await Promise.allSettled([loadResources(), load()]); }
async function acknowledge(row) {
  if (!checkPermi(['dashboard:integration:ack']) || acknowledgingId.value != null) return;
  acknowledgingId.value = row.alertId;
  try { await acknowledgeIntegrationAlert(row.alertId); ElMessage.success('告警已确认'); await load(); }
  finally { acknowledgingId.value = null; }
}

onMounted(() => {
  refresh();
  timer = setInterval(() => { if (autoRefresh.value && !loading.value) load(); }, 15000);
});
onBeforeUnmount(() => { generation++; resourceGeneration++; clearInterval(timer); });
defineExpose({ refresh });
</script>

<style scoped>
.operations-view { display: grid; grid-template-columns: minmax(0, 1fr); gap: 18px; min-width: 0; padding-top: 16px; }
.operations-view > :deep(.management-toolbar) { margin-bottom: 0; }
.overview-groups { display: grid; grid-template-columns: minmax(0, 1fr); gap: 22px; }
.overview-groups.has-inbound { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.overview-group { min-width: 0; padding: 16px 18px; border: 1px solid var(--el-border-color-light); border-radius: var(--el-border-radius-base); background: var(--el-bg-color); }
.overview-group h3 { margin: 0 0 14px; font-size: var(--el-font-size-base); color: var(--el-text-color-primary); font-weight: 600; }
.metrics, .connection-metrics { row-gap: 18px; }
.overview-caption, .table-caption { color: var(--el-text-color-secondary); font-size: var(--el-font-size-small); line-height: 1.6; }
.overview-caption { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 6px 18px; margin-top: -8px; }
.operations-tabs :deep(.el-tabs__header) { margin: 0; }
.operations-tabs :deep(.el-tabs__content) { display: none; }
.operations-tab-label { display: inline-flex; align-items: center; gap: 6px; }
.operations-description { margin: 0; color: var(--el-text-color-secondary); font-size: var(--el-font-size-base); line-height: 1.65; }
.table-heading { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; }
.table-heading .table-caption { flex-shrink: 0; }
.connection-metrics { padding: 2px 0; }
@media (max-width: 1000px) { .overview-groups.has-inbound { grid-template-columns: 1fr; } }
@media (max-width: 640px) { .table-heading { display: block; } .overview-group { padding: 12px; } }
</style>
