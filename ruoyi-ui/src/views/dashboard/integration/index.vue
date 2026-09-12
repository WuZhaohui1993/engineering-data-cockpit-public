<template>
  <div class="integration-page app-container dashboard-management-page">
    <el-empty v-if="!allowedTabs.length" description="尚未授予数据管理功能权限，请联系管理员。" />

    <div v-if="allowedTabs.length" class="integration-workspace">
    <el-tabs v-model="activeTab" class="integration-tabs dashboard-management-tabs">
      <el-tab-pane v-if="canSources" name="datasets" lazy>
        <template #label><span class="tab-label"><el-icon><DataAnalysis /></el-icon>数据集</span></template>
        <DatasetManager ref="datasetManager" embedded :active="activeTab === 'datasets'" @manage-sources="selectTab('outbound')" />
      </el-tab-pane>
      <el-tab-pane v-if="canSources" name="outbound" lazy>
        <template #label>
          <span class="tab-label"><el-icon><Link /></el-icon>数据源</span>
        </template>
        <DataSourceManager ref="sourceManager" v-show="!selectedSourceCode" @loaded="sourceRows = $event" @open-endpoints="openSourceEndpoints" />
        <section v-if="selectedSourceCode" class="panel-section">
          <ManagementToolbar title="取数接口" :total="endpointTotal" noun="个接口" help-module="endpoints" :loading="endpointLoading || sourceLoading" :actions="endpointToolbarActions" @search="searchEndpoints" @reset="resetEndpointQuery" @refresh="loadEndpoints" @action="handleEndpointToolbarAction">
            <template #filters>
              <el-form-item label="数据源" class="filter-wide"><el-select v-model="selectedSourceCode" filterable placeholder="请选择来源" @change="changeEndpointSource"><el-option v-for="source in httpSources" :key="source.code" :label="`${source.name}（${source.code}）`" :value="source.code" /></el-select></el-form-item>
              <el-form-item label="搜索"><el-input v-model="endpointQuery.keyword" clearable placeholder="编码或名称" /></el-form-item>
              <el-form-item label="状态"><el-select v-model="endpointQuery.status" clearable placeholder="全部状态"><el-option label="草稿" value="DRAFT" /><el-option label="启用" value="ACTIVE" /><el-option label="停用" value="DISABLED" /></el-select></el-form-item>
            </template>
            <template #primary><DataManagementCreateButton kind="endpoint" @click="openEndpointCreate" /></template>
            <template #secondary><el-tooltip content="返回数据源列表"><el-button :icon="Back" circle aria-label="返回数据源列表" @click="returnToSources" /></el-tooltip></template>
          </ManagementToolbar>
          <el-alert v-if="!httpSources.length && !sourceLoading" type="warning" :closable="false" show-icon title="请返回数据源列表，登记并启用接口数据源（HTTP）。" />
          <section class="dashboard-management-table">
<el-table v-loading="endpointLoading || sourceLoading" :data="endpointRows" row-key="endpointId">
            <el-table-column label="取数接口" min-width="245">
              <template #default="scope">
                <div class="identity-cell">
                  <span class="identity-mark outbound-mark"><el-icon><Link /></el-icon></span>
                  <div><strong>{{ scope.row.endpointName }}</strong><small>{{ scope.row.endpointCode }}</small></div>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="请求" min-width="230">
              <template #default="scope"><div class="stack-cell"><span><el-tag effect="plain">{{ requestMethodLabel(scope.row.method) }}</el-tag> {{ scope.row.path }}</span><small>{{ requestTypeLabel(scope.row.requestContentType) }} → {{ responseTypeLabel(scope.row.responseType) }}</small></div></template>
            </el-table-column>
            <el-table-column label="认证" width="150">
              <template #default="scope"><div class="stack-cell"><span>{{ authProviderLabel(scope.row.authProvider) }}</span><small>{{ scope.row.hasCredential ? '已配置凭证' : '未配置凭证' }}</small></div></template>
            </el-table-column>
            <el-table-column label="最近测试" min-width="180">
              <template #default="scope"><div class="stack-cell"><span :class="qualityTone(scope.row.lastTestStatus)">{{ qualityLabel(scope.row.lastTestStatus) }}</span><small>{{ formatDateTime(scope.row.lastTestAt, '尚未测试') }}</small></div></template>
            </el-table-column>
            <el-table-column label="状态" width="105">
              <template #default="scope"><el-tag :type="endpointStatusTone(scope.row.status)">{{ endpointStatusLabel(scope.row.status) }}</el-tag></template>
            </el-table-column>
            <el-table-column label="操作" width="330" fixed="right">
              <template #default="scope">
                <div class="row-actions">
                  <el-button v-hasPermi="['dashboard:integration:test']" link type="success" :loading="endpointTesting === scope.row.endpointCode" @click="testEndpoint(scope.row)">测试</el-button>
                  <el-button v-hasPermi="['dashboard:integration:edit']" link type="primary" @click="openEndpointEdit(scope.row)">编辑</el-button>
                  <el-button v-if="scope.row.status === 'ACTIVE'" v-hasPermi="['dashboard:integration:edit']" link type="warning" @click="changeEndpointStatus(scope.row, 'DISABLED')">停用</el-button>
                  <el-button v-else v-hasPermi="['dashboard:integration:edit']" link type="success" @click="changeEndpointStatus(scope.row, 'ACTIVE')">启用</el-button>
                  <el-button v-hasPermi="['dashboard:integration:delete']" link type="danger" @click="removeEndpoint(scope.row)">删除</el-button>
                </div>
              </template>
            </el-table-column>
          <template #empty><DataManagementEmptyState v-if="selectedSourceCode && !endpointLoading && !endpointRows.length" kind="endpoint" description="当前来源尚未登记接口" /></template>
</el-table>
<pagination v-show="endpointTotal > 0" v-model:page="endpointQuery.pageNum" v-model:limit="endpointQuery.pageSize" :total="endpointTotal" @pagination="loadEndpoints" />
</section>

        </section>
      </el-tab-pane>
      <el-tab-pane v-if="canInbound" name="inbound">
        <template #label>
          <span class="tab-label"><el-icon><Promotion /></el-icon>推送接入</span>
        </template>
        <el-tabs v-model="activeInboundTab" class="inbound-tabs">
        <el-tab-pane v-if="canConfigureInbound" name="integrations" label="接入方配置">
        <section class="panel-section dashboard-management-tree-layout">
          <DataFolderSidebar
            v-model="integrationQuery.folderId"
            v-model:include-children="integrationQuery.includeChildren"
            :folders="integrationFolders"
            scope="integration"
            title="推送接入文件夹"
            :can-manage="checkPermi(['dashboard:integration:edit', 'dashboard:integration:delete'])"
            @change="searchIntegrations"
            @refresh="refreshInbound"
            @manage="integrationFolderManagerOpen = true"
          />
          <div class="dashboard-management-main">
          <ManagementToolbar title="接入方配置" :total="integrationTotal" noun="个接入方" :selection-count="selectedIntegrationIds.length" help-module="inbound" :loading="integrationLoading" @search="searchIntegrations" @reset="resetIntegrationQuery" @refresh="refreshInbound">
            <template #filters>
              <el-form-item label="搜索"><el-input v-model="integrationQuery.keyword" clearable placeholder="编码或名称" /></el-form-item>
              <el-form-item label="环境"><el-select v-model="integrationQuery.environment" clearable placeholder="全部环境"><el-option label="沙箱" value="SANDBOX" /><el-option label="测试" value="TEST" /><el-option label="生产" value="PRODUCTION" /></el-select></el-form-item>
              <el-form-item label="状态"><el-select v-model="integrationQuery.status" clearable placeholder="全部状态"><el-option v-for="item in integrationStatuses" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
            </template>
            <template #selection><el-button v-hasPermi="['dashboard:integration:edit']" :icon="FolderOpened" @click="integrationFolderMoveOpen = true">移动</el-button></template>
            <template #primary><DataManagementCreateButton kind="integration" @click="openIntegrationCreate" /></template>
          </ManagementToolbar>

          <section class="dashboard-management-table">
<el-table v-loading="integrationLoading" :data="integrations" row-key="integrationId" @selection-change="selectedIntegrationIds = $event.map(row => row.integrationId)">
            <el-table-column v-if="checkPermi(['dashboard:integration:edit'])" type="selection" width="48" />
            <el-table-column label="接入方" min-width="240">
              <template #default="scope">
                <div class="identity-cell">
                  <span class="identity-mark inbound-mark"><el-icon><Promotion /></el-icon></span>
                  <div>
                    <strong>{{ scope.row.integrationName }}</strong>
                    <small>{{ scope.row.integrationCode }}</small>
                  </div>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="文件夹" min-width="140" show-overflow-tooltip><template #default="{ row }">{{ integrationFolderName(row) }}</template></el-table-column>
            <el-table-column label="环境" width="100">
              <template #default="scope">{{ environmentLabel(scope.row.environment) }}</template>
            </el-table-column>
            <el-table-column label="消息类型" min-width="190">
              <template #default="scope"><el-tag effect="plain">{{ profileLabel(scope.row.profile) }}</el-tag></template>
            </el-table-column>
            <el-table-column label="接口 / 报文版本" min-width="170">
              <template #default="scope">
                <div class="stack-cell"><span>{{ scope.row.endpointCode }}</span><small>v{{ scope.row.schemaVersion }}</small></div>
              </template>
            </el-table-column>
            <el-table-column label="安全策略" min-width="170">
              <template #default="scope">
                <div class="stack-cell"><span>{{ networkProfileLabel(scope.row.networkProfile) }}</span><small>{{ scope.row.hasAllowedIp ? '已限制来源地址' : '未限制来源地址' }}</small></div>
              </template>
            </el-table-column>
            <el-table-column label="密钥" width="105">
              <template #default="scope"><span :class="scope.row.activeKeyCount ? 'metric-good' : 'metric-warn'">{{ scope.row.activeKeyCount || 0 }} 个启用</span></template>
            </el-table-column>
            <el-table-column label="状态" width="110">
              <template #default="scope"><el-tag :type="integrationStatusTone(scope.row.status)">{{ integrationStatusLabel(scope.row.status) }}</el-tag></template>
            </el-table-column>
            <el-table-column label="操作" width="300" fixed="right">
              <template #default="scope">
                <div class="row-actions"><el-button v-if="checkPermi(['dashboard:integration:view']) && checkPermi(['dashboard:dataset:edit'])" link type="primary" @click="inboundDatasetDialog.open(scope.row)">创建数据集</el-button>
                  <el-button v-hasPermi="['dashboard:integration:view']" link type="primary" @click="viewIntegration(scope.row)">详情</el-button>
                  <el-button v-hasPermi="['dashboard:integration:key']" link type="primary" @click="openKeys(scope.row)">密钥</el-button>
                  <el-button v-hasPermi="['dashboard:integration:edit']" link type="primary" @click="openIntegrationEdit(scope.row)">编辑</el-button>
                  <el-dropdown
                    v-if="canChangeIntegrationStatus(scope.row)"
                    trigger="click"
                    @command="(command) => handleIntegrationCommand(command, scope.row)"
                  >
                    <el-button link type="primary">更多<el-icon><MoreFilled /></el-icon></el-button>
                    <template #dropdown>
                      <el-dropdown-menu>
                        <el-dropdown-item
                          v-if="(scope.row.status === 'DRAFT' || scope.row.status === 'PAUSED') && checkPermi(['dashboard:integration:edit'])"
                          command="active"
                        >启用</el-dropdown-item>
                        <el-dropdown-item
                          v-if="scope.row.status === 'ACTIVE' && checkPermi(['dashboard:integration:pause'])"
                          command="pause"
                        >暂停</el-dropdown-item>
                        <el-dropdown-item
                          v-if="scope.row.status !== 'REVOKED' && checkPermi(['dashboard:integration:revoke'])"
                          command="revoke"
                        >吊销</el-dropdown-item>
                        <el-dropdown-item
                          v-if="(scope.row.status === 'DRAFT' || scope.row.status === 'REVOKED') && checkPermi(['dashboard:integration:delete'])"
                          command="delete"
                        >删除</el-dropdown-item>
                      </el-dropdown-menu>
                    </template>
                  </el-dropdown>
                </div>
              </template>
            </el-table-column>
          <template #empty><DataManagementEmptyState v-if="!integrationLoading && !integrations.length" kind="integration" description="暂无符合条件的接入方" /></template>
</el-table>
<pagination v-show="integrationTotal > 0" v-model:page="integrationQuery.pageNum" v-model:limit="integrationQuery.pageSize" :total="integrationTotal" @pagination="loadIntegrations" />
</section>

          </div>
        </section>
      </el-tab-pane>



      <el-tab-pane v-if="checkPermi(['dashboard:integration:view'])" name="batches">
        <template #label>
          <span class="tab-label"><el-icon><Tickets /></el-icon>接收台账</span>
        </template>
        <section class="panel-section">
          <ManagementToolbar title="接收台账" :total="batchTotal" noun="个批次" help-module="batches" :loading="batchLoading" @search="searchBatches" @reset="resetBatchQuery" @refresh="loadBatches">
            <template #filters>
              <el-form-item v-if="canConfigureInbound" label="接入方" class="filter-wide"><el-select v-model="batchQuery.integrationId" clearable filterable placeholder="全部接入方"><el-option v-for="item in integrationOptions" :key="item.integrationId" :label="`${item.integrationName}（${item.integrationCode}）`" :value="item.integrationId" /></el-select></el-form-item>
              <el-form-item label="状态"><el-select v-model="batchQuery.status" clearable placeholder="全部状态"><el-option v-for="item in batchStatuses" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
            </template>
          </ManagementToolbar>
          <section class="dashboard-management-table">
<el-table v-loading="batchLoading" :data="batchRows" row-key="batchId">
            <el-table-column label="平台请求编号（ID）" min-width="250"><template #default="scope"><code class="request-id">{{ scope.row.platformRequestId }}</code></template></el-table-column>
            <el-table-column label="接入方 / 接口" min-width="190"><template #default="scope"><div class="stack-cell"><span>{{ integrationName(scope.row.integrationId) }}</span><small>{{ scope.row.endpointCode }}</small></div></template></el-table-column>
            <el-table-column label="幂等标识" min-width="190"><template #default="scope"><span class="ellipsis-value" :title="scope.row.normalizedIdempotencyKey">{{ scope.row.normalizedIdempotencyKey }}</span></template></el-table-column>
            <el-table-column label="处理统计" min-width="220"><template #default="scope"><div class="batch-metrics"><span class="metric-good">成功 {{ scope.row.acceptedCount || 0 }}</span><span>处理中 {{ scope.row.processingCount || 0 }}</span><span class="metric-warn">拒绝 {{ scope.row.rejectedCount || 0 }}</span><span>重复 {{ scope.row.duplicateCount || 0 }}</span></div></template></el-table-column>
            <el-table-column label="状态" width="120"><template #default="scope"><el-tag :type="batchStatusTone(scope.row.status)">{{ batchStatusLabel(scope.row.status) }}</el-tag></template></el-table-column>
            <el-table-column label="接收时间" width="180"><template #default="scope">{{ formatDateTime(scope.row.receivedAt) }}</template></el-table-column>
            <el-table-column label="操作" width="100" fixed="right"><template #default="scope"><el-button v-hasPermi="['dashboard:integration:view']" link type="primary" @click="openBatch(scope.row)">查看明细</el-button></template></el-table-column>
          <template #empty><el-empty v-if="!batchLoading && !batchRows.length" description="暂无接收批次" /></template>
</el-table>
<pagination v-show="batchTotal > 0" v-model:page="batchQuery.pageNum" v-model:limit="batchQuery.pageSize" :total="batchTotal" @pagination="loadBatches" />
</section>

        </section>
      </el-tab-pane>

      <el-tab-pane v-if="checkPermi(['dashboard:integration:deadletter'])" name="dead">
        <template #label>
          <span class="tab-label"><el-icon><Warning /></el-icon>失败消息（死信）</span>
        </template>
        <section class="panel-section">
          <ManagementToolbar title="失败消息（死信）" :total="deadTotal" noun="条死信" help-module="dead" :loading="deadLoading" @search="searchDeadLetters" @reset="resetDeadQuery" @refresh="loadDeadLetters">
            <template #filters>
              <el-form-item v-if="canConfigureInbound" label="接入方" class="filter-wide"><el-select v-model="deadQuery.integrationId" clearable filterable placeholder="全部接入方"><el-option v-for="item in integrationOptions" :key="item.integrationId" :label="`${item.integrationName}（${item.integrationCode}）`" :value="item.integrationId" /></el-select></el-form-item>
              <el-form-item label="搜索"><el-input v-model="deadQuery.keyword" clearable placeholder="业务主键或错误码" /></el-form-item>
            </template>
          </ManagementToolbar>
          <el-alert type="warning" :closable="false" show-icon title="重放会重新进入异步标准化流程；请先确认来源报文和业务主键，操作会写入后台审计日志。" />
          <section class="dashboard-management-table">
<el-table v-loading="deadLoading" :data="deadRows" row-key="integrationMessageId">
            <el-table-column label="消息" min-width="240"><template #default="scope"><div class="stack-cell"><span>{{ profileLabel(scope.row.profile) }} / {{ scope.row.businessKey }}</span><small>{{ integrationName(scope.row.integrationId) }} · 批次 {{ scope.row.batchId }}</small></div></template></el-table-column>
            <el-table-column label="外部项目编码" width="160"><template #default="scope">{{ scope.row.projectCode || '未填写' }}</template></el-table-column>
            <el-table-column label="错误" min-width="220"><template #default="scope"><div class="stack-cell"><span class="quality-error">{{ integrationResultLabel(scope.row.errorCode || 'PROCESSING_FAILED') }}</span><small>{{ scope.row.errorMessage || '标准化处理失败' }}</small></div></template></el-table-column>
            <el-table-column label="重试次数" width="100" prop="retryCount" />
            <el-table-column label="接收时间" width="180"><template #default="scope">{{ formatDateTime(scope.row.receivedAt) }}</template></el-table-column>
            <el-table-column label="操作" width="100" fixed="right"><template #default="scope"><el-button v-hasPermi="['dashboard:integration:replay']" link type="primary" @click="replayDeadLetter(scope.row)">重放</el-button></template></el-table-column>
          <template #empty><el-empty v-if="!deadLoading && !deadRows.length" description="暂无死信消息" /></template>
</el-table>
<pagination v-show="deadTotal > 0" v-model:page="deadQuery.pageNum" v-model:limit="deadQuery.pageSize" :total="deadTotal" @pagination="loadDeadLetters" />
</section>

        </section>
      </el-tab-pane>
        </el-tabs>
      </el-tab-pane>
      <el-tab-pane v-if="canMonitor" name="operations">
        <template #label><span class="tab-label"><el-icon><Monitor /></el-icon>接入运维</span></template>
        <IntegrationOperations ref="operationsPanel" v-if="activeTab === 'operations'" />
      </el-tab-pane>
    </el-tabs>
    </div>
    <InboundDatasetDialog ref="inboundDatasetDialog" />
    <DataFolderManager v-model="integrationFolderManagerOpen" scope="integration" :folders="integrationFolders" :default-parent-id="integrationQuery.folderId > 0 ? integrationQuery.folderId : 0" @changed="refreshInbound" />
    <DataFolderMoveDialog v-model="integrationFolderMoveOpen" scope="integration" :folders="integrationFolders" :ids="selectedIntegrationIds" :default-folder-id="integrationQuery.folderId > 0 ? integrationQuery.folderId : 0" @moved="refreshInbound" />

    <el-dialog v-model="integrationDialog.open" :title="integrationDialog.editing ? '编辑入站接入方' : '新增入站接入方'" width="820px" destroy-on-close>
      <el-form ref="integrationFormRef" :model="integrationDialog.form" :rules="integrationRules" label-position="top" class="editor-form">
        <div class="form-grid form-grid-3">
          <el-form-item label="接入方编码" prop="integrationCode"><el-input v-model="integrationDialog.form.integrationCode" :disabled="integrationDialog.editing" placeholder="例如 partner-events" /></el-form-item>
          <el-form-item label="接入方名称" prop="integrationName"><el-input v-model="integrationDialog.form.integrationName" placeholder="合作方或系统名称" /></el-form-item>
          <el-form-item label="所属文件夹" prop="folderId"><el-tree-select v-model="integrationDialog.form.folderId" :data="integrationFolderOptions" check-strictly filterable default-expand-all /></el-form-item>
          <el-form-item label="环境" prop="environment"><el-select v-model="integrationDialog.form.environment"><el-option label="沙箱" value="SANDBOX" /><el-option label="测试" value="TEST" /><el-option label="生产" value="PRODUCTION" /></el-select></el-form-item>
          <el-form-item label="消息类型" prop="profile"><el-select v-model="integrationDialog.form.profile"><el-option v-for="item in profiles" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
          <el-form-item label="报文版本（Schema）" prop="schemaVersion"><el-input v-model="integrationDialog.form.schemaVersion" placeholder="例如 1.0" /></el-form-item>
          <el-form-item label="接收接口编码（endpoint）" prop="endpointCode"><el-input v-model="integrationDialog.form.endpointCode" placeholder="例如 events" /></el-form-item>
          <el-form-item label="网络策略" prop="networkProfile"><el-select v-model="integrationDialog.form.networkProfile"><el-option label="公网加密访问（HTTPS）" value="PUBLIC_HTTPS" /><el-option label="受控专网 / 隧道" value="PRIVATE_LINK" /></el-select></el-form-item>
          <el-form-item label="时区" prop="timezone"><TimezoneSelect v-model="integrationDialog.form.timezone" /></el-form-item>
          <el-form-item label="状态" prop="status"><el-select v-model="integrationDialog.form.status"><el-option v-for="item in integrationStatuses" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
        </div>
        <div class="form-tip integration-explanation">{{ profileDescription(integrationDialog.form.profile) }}</div>
        <div class="form-tip integration-explanation">网络策略说明部署方式：公网需配置加密入口；专网需预先建立网络或隧道。推送入口仍检查签名和来源地址，选择策略不会自动建立连接或开启加密。时区请选择来源业务时间采用的标准时区，国内通常使用中国标准时间。</div>
        <div class="form-grid form-grid-4">
          <el-form-item label="批次条数上限"><el-input-number v-model="integrationDialog.form.maxBatchItems" :min="1" :max="500" controls-position="right" /></el-form-item>
          <el-form-item label="请求体上限（字节）"><el-input-number v-model="integrationDialog.form.maxBodyBytes" :min="1024" :max="16777216" controls-position="right" /></el-form-item>
          <el-form-item label="每秒请求数"><el-input-number v-model="integrationDialog.form.rateLimit" :min="1" :max="1000" controls-position="right" /></el-form-item>
          <el-form-item label="并发上限"><el-input-number v-model="integrationDialog.form.concurrencyLimit" :min="1" :max="200" controls-position="right" /></el-form-item>
        </div>
        <div class="form-grid form-grid-2">
          <el-form-item label="外部项目范围（可选）" prop="projectScopeText"><el-input v-model="integrationDialog.form.projectScopeText" placeholder="来源有项目划分时填写；多个编码用逗号分隔" /></el-form-item>
          <el-form-item label="来源地址白名单（IP / CIDR）" prop="allowedIpsText"><el-input v-model="integrationDialog.form.allowedIpsText" placeholder="例如 203.0.113.10,203.0.113.0/24；留空表示不限制" /></el-form-item>
        </div>
        <div class="form-tip integration-explanation">平台没有项目档案；外部项目范围仅用于来源数据分组和过滤。不需要区分项目时留空，数据仍按接入方隔离。</div>
        <el-divider content-position="left">业务数据保留</el-divider>
        <div v-if="integrationRetention.parseError" class="form-tip retention-error">{{ integrationRetention.parseError }}，请先修正下方高级接入策略，再调整保留时间。</div>
        <div class="form-grid form-grid-2">
          <el-form-item label="业务记录保留天数" :error="integrationRetention.errors.recordRetentionDays">
            <el-input-number :model-value="integrationRetention.recordRetentionDays" :min="0" :max="3650" :precision="0" controls-position="right" :disabled="!!integrationRetention.parseError" @update:model-value="setIntegrationRetention('recordRetentionDays', $event)" />
            <div class="form-tip">0 表示长期保留。按平台记录更新时间到期删除，重复推送不延长期限；已删除记录不再用于大屏查询。</div>
          </el-form-item>
        </div>
        <el-divider content-position="left">报文与处理明细</el-divider>
        <div class="form-grid form-grid-2">
          <el-form-item label="保存原始报文" :error="integrationRetention.errors.retainRawBody">
            <el-switch :model-value="integrationRetention.retainRawBody" :disabled="!!integrationRetention.parseError" @update:model-value="setIntegrationRetention('retainRawBody', $event)" />
            <div class="form-tip">用于排障时回看接收正文，默认关闭。关闭不影响标准业务记录保存。</div>
          </el-form-item>
          <el-form-item label="原文保留天数" prop="rawRetentionDays">
            <el-input-number :key="`raw-retention-${integrationRetention.retainRawBody}-${!!integrationRetention.parseError}`" v-model="integrationDialog.form.rawRetentionDays" :min="0" :max="3650" :precision="0" controls-position="right" :disabled="!integrationRetention.retainRawBody || !!integrationRetention.parseError" />
            <div class="form-tip">0 表示不保存原文；开启保存且天数大于 0 才会留存，按接收时间到期清理。</div>
          </el-form-item>
          <el-form-item label="成功明细保留天数" :error="integrationRetention.errors.successRetentionDays">
            <el-input-number :model-value="integrationRetention.successRetentionDays" :min="0" :max="3650" :precision="0" controls-position="right" :disabled="!!integrationRetention.parseError" @update:model-value="setIntegrationRetention('successRetentionDays', $event)" />
            <div class="form-tip">0 表示长期保留。到期后压缩成功批次回执、清除成功消息正文；无消息引用的纯重复成功批次可删除。</div>
          </el-form-item>
        </div>
        <div class="form-tip integration-explanation">清理仅处理最终成功的数据，未完成、失败和死信不自动清理。初始批次、业务主键及内容摘要等必要去重信息继续保留，防止已到期旧数据因重复推送重新入库。成功明细和业务记录期限会作用于已有数据；原文以接收时登记的到期时间为准，各类独立计时。</div>
        <div class="form-tip integration-explanation">访问日志、接入告警的公共保留期限在“接入运维 → 保留策略”中统一配置。</div>
        <el-collapse class="integration-policy">
          <el-collapse-item title="高级接入策略（JSON，通常保留默认）" name="policy">
            <div class="form-tip">这里配置传输和处理规则，不配置监测字段或告警阈值。只有时间戳单位、签名契约、整批处理或重试约定不同，才需要修改。数据保留控件与此处 JSON 同步，其他策略保持原值。</div>
            <el-form-item label="接入策略（JSON，不填写密钥）" prop="configJson">
              <el-input v-model="integrationDialog.form.configJson" type="textarea" :rows="9" spellcheck="false" />
            </el-form-item>
            <el-table :data="integrationPolicyHelp" max-height="320">
              <el-table-column label="策略" min-width="220"><template #default="{ row }">{{ row[0] }}（{{ row[1] }}）</template></el-table-column>
              <el-table-column label="作用与修改场景" min-width="340"><template #default="{ row }">{{ row[2] }}</template></el-table-column>
            </el-table>
          </el-collapse-item>
        </el-collapse>
        <el-form-item label="备注"><el-input v-model="integrationDialog.form.remark" type="textarea" :rows="2" maxlength="500" show-word-limit /></el-form-item>
      </el-form>
      <template #footer><el-button @click="integrationDialog.open = false">取消</el-button><el-button type="primary" :loading="integrationDialog.saving" @click="submitIntegration">保存接入方</el-button></template>
    </el-dialog>

    <el-dialog v-model="integrationDetailDialog.open" title="接入方详情" width="720px" destroy-on-close>
      <el-descriptions v-if="integrationDetailDialog.row" :column="2" border>
        <el-descriptions-item label="接入方">{{ integrationDetailDialog.row.integrationName }}（{{ integrationDetailDialog.row.integrationCode }}）</el-descriptions-item>
        <el-descriptions-item label="状态"><el-tag :type="integrationStatusTone(integrationDetailDialog.row.status)">{{ integrationStatusLabel(integrationDetailDialog.row.status) }}</el-tag></el-descriptions-item>
        <el-descriptions-item label="环境">{{ environmentLabel(integrationDetailDialog.row.environment) }}</el-descriptions-item>
        <el-descriptions-item label="消息类型">{{ profileLabel(integrationDetailDialog.row.profile) }}</el-descriptions-item>
        <el-descriptions-item label="报文版本 / 接收接口">v{{ integrationDetailDialog.row.schemaVersion }} / {{ integrationDetailDialog.row.endpointCode }}</el-descriptions-item>
        <el-descriptions-item label="时区">{{ timezoneLabel(integrationDetailDialog.row.timezone) }}</el-descriptions-item>
        <el-descriptions-item label="网络策略">{{ networkProfileLabel(integrationDetailDialog.row.networkProfile) }}</el-descriptions-item>
        <el-descriptions-item label="外部项目范围">{{ (integrationDetailDialog.row.projectScope || []).join('、') || '不限制' }}</el-descriptions-item>
        <el-descriptions-item label="容量">{{ integrationDetailDialog.row.maxBatchItems }} 条 / {{ integrationDetailDialog.row.maxBodyBytes }} 字节</el-descriptions-item>
        <el-descriptions-item label="限流 / 并发">{{ integrationDetailDialog.row.rateLimit }} / {{ integrationDetailDialog.row.concurrencyLimit }}</el-descriptions-item>
        <el-descriptions-item label="启用密钥">{{ integrationDetailDialog.row.activeKeyCount || 0 }} 个</el-descriptions-item>
        <el-descriptions-item label="更新时间">{{ formatDateTime(integrationDetailDialog.row.updateTime) }}</el-descriptions-item>
        <el-descriptions-item label="原始报文">{{ integrationRetentionSummary(integrationDetailDialog.row, 'rawRetentionDays') }}</el-descriptions-item>
        <el-descriptions-item label="成功明细">{{ integrationRetentionSummary(integrationDetailDialog.row, 'successRetentionDays') }}</el-descriptions-item>
        <el-descriptions-item label="业务记录" :span="2">{{ integrationRetentionSummary(integrationDetailDialog.row, 'recordRetentionDays') }}</el-descriptions-item>
        <el-descriptions-item label="策略 JSON" :span="2"><pre class="detail-json">{{ prettyJson(integrationDetailDialog.row.configJson) }}</pre></el-descriptions-item>
        <el-descriptions-item label="备注" :span="2">{{ integrationDetailDialog.row.remark || '—' }}</el-descriptions-item>
      </el-descriptions>
      <el-skeleton v-else :rows="5" animated />
    </el-dialog>

    <el-dialog v-model="keyDialog.open" @closed="keyDialog.secretResult = ''; keyDialog.form.secret = ''" :title="`密钥管理 · ${keyDialog.integration?.integrationName || ''}`" width="900px" destroy-on-close>
      <div class="key-toolbar"><span>有密钥管理权限的用户可查看已保存的密钥明文。</span><el-button v-hasPermi="['dashboard:integration:key']" type="primary" @click="keyDialog.createOpen = !keyDialog.createOpen"><el-icon><Plus /></el-icon>{{ keyDialog.createOpen ? '取消新增' : '新增密钥' }}</el-button></div>
      <el-alert v-if="keyDialog.secretResult" class="secret-alert" type="warning" :closable="false" show-icon title="当前密钥明文"><template #default><div class="secret-row"><code>{{ keyDialog.secretResult }}</code><el-button type="primary" plain @click="copyText(keyDialog.secretResult, '密钥已复制')">复制密钥</el-button></div></template></el-alert>
      <el-form v-if="keyDialog.createOpen" ref="keyFormRef" :model="keyDialog.form" :rules="keyRules" label-position="top" class="key-create-form">
        <div class="form-grid form-grid-4">
          <el-form-item label="密钥标识（Key ID）" prop="keyId"><el-input v-model="keyDialog.form.keyId" placeholder="例如 partner-key-2026" /></el-form-item>
          <el-form-item label="认证方式" prop="provider"><el-select v-model="keyDialog.form.provider"><el-option label="消息签名（HMAC-SHA256）" value="HMAC_V1" /><el-option label="接口密钥（API Key）" value="API_KEY" /><el-option label="访问令牌（Bearer）" value="BEARER" /></el-select></el-form-item>
          <el-form-item v-if="keyDialog.form.provider === 'HMAC_V1'" label="身份标识" prop="identityValue"><el-input v-model="keyDialog.form.identityValue" placeholder="合作方应用标识" /></el-form-item>
          <el-form-item label="密钥明文" prop="secret"><el-input v-model="keyDialog.form.secret" type="text" autocomplete="off" placeholder="请输入或生成随机密钥"><template #append><el-button @click="generateSecret">生成</el-button></template></el-input></el-form-item>
          <el-form-item label="生效时间"><el-date-picker v-model="keyDialog.form.validFrom" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" placeholder="立即生效" /></el-form-item>
          <el-form-item label="失效时间"><el-date-picker v-model="keyDialog.form.validTo" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" placeholder="长期有效" /></el-form-item>
        </div>
        <el-form-item label="备注"><el-input v-model="keyDialog.form.remark" maxlength="500" /></el-form-item>
        <div class="dialog-actions"><el-button type="primary" :loading="keyDialog.saving" @click="submitKey">保存密钥</el-button></div>
      </el-form>
      <el-table v-loading="keyDialog.loading" :data="keyDialog.rows" row-key="integrationKeyId">
        <el-table-column prop="keyId" label="密钥标识（Key ID）" min-width="180" />
        <el-table-column label="认证方式" width="130"><template #default="scope">{{ authProviderLabel(scope.row.provider) }}</template></el-table-column>
        <el-table-column prop="identityValue" label="身份标识" min-width="170"><template #default="scope">{{ scope.row.identityValue || '—' }}</template></el-table-column>
        <el-table-column label="有效期" min-width="230"><template #default="scope">{{ formatDateTime(scope.row.validFrom, '立即') }} ～ {{ formatDateTime(scope.row.validTo, '长期') }}</template></el-table-column>
        <el-table-column label="最近使用" width="180"><template #default="scope">{{ formatDateTime(scope.row.lastUsedAt, '尚未使用') }}</template></el-table-column>
        <el-table-column label="状态" width="100"><template #default="scope"><el-tag :type="scope.row.status === 'ACTIVE' ? 'success' : 'info'">{{ scope.row.status === 'ACTIVE' ? '启用' : '已吊销' }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="190"><template #default="scope"><el-button v-hasPermi="['dashboard:integration:key']" link type="primary" @click="revealKey(scope.row)">查看明文</el-button><el-button v-if="scope.row.status === 'ACTIVE'" v-hasPermi="['dashboard:integration:key']" link type="danger" @click="revokeKey(scope.row)">吊销</el-button></template></el-table-column>
      </el-table>
      <el-empty v-if="!keyDialog.loading && !keyDialog.rows.length" description="尚未创建密钥" />
    </el-dialog>

    <el-dialog v-model="endpointDialog.open" :title="endpointDialog.editing ? '编辑接口' : '新增接口'" width="860px" destroy-on-close>
      <el-form ref="endpointFormRef" :model="endpointDialog.form" :rules="endpointRules" label-position="top" class="editor-form">
        <el-alert v-if="!selectedSourceCode" type="warning" :closable="false" show-icon title="请先在页面上方选择 HTTP 数据源。" />
        <div class="form-grid form-grid-3">
          <el-form-item label="所属数据源"><el-input :model-value="sourceLabel(selectedSourceCode)" disabled /></el-form-item>
          <el-form-item label="接口编码（endpoint）" prop="endpointCode"><el-input v-model="endpointDialog.form.endpointCode" :disabled="endpointDialog.editing" placeholder="例如 records" /></el-form-item>
          <el-form-item label="接口名称" prop="endpointName"><el-input v-model="endpointDialog.form.endpointName" placeholder="例如 项目记录查询" /></el-form-item>
          <el-form-item label="相对路径" prop="path"><el-input v-model="endpointDialog.form.path" placeholder="/v1/records/{projectCode}" /></el-form-item>
          <el-form-item label="请求方法" prop="method"><el-select v-model="endpointDialog.form.method"><el-option label="获取（GET）" value="GET" /><el-option label="提交（POST）" value="POST" /></el-select></el-form-item>
          <el-form-item label="请求内容类型" prop="requestContentType"><el-select v-model="endpointDialog.form.requestContentType"><el-option label="无请求体" value="NONE" /><el-option label="结构化报文（JSON）" value="JSON" /><el-option label="表单" value="FORM_URLENCODED" /></el-select></el-form-item>
          <el-form-item label="响应类型" prop="responseType"><el-select v-model="endpointDialog.form.responseType"><el-option label="结构化数据（JSON）" value="JSON" /><el-option label="二进制媒体" value="BINARY_MEDIA" /></el-select></el-form-item>
          <el-form-item label="认证方式" prop="authProvider"><el-select v-model="endpointDialog.form.authProvider"><el-option label="继承数据源" value="INHERIT" /><el-option label="无认证（仅公开只读）" value="NO_AUTH" /><el-option label="访问令牌（Bearer）" value="BEARER" /><el-option label="接口密钥（API Key）" value="API_KEY" /><el-option label="通用签名（HMAC-SHA256）" value="HMAC_V1" /><el-option label="应用标识与毫秒时间戳签名（HMAC）" value="HMAC_APPKEY_TIMESTAMP_V1" /></el-select></el-form-item>
          <el-form-item label="契约版本" prop="contractVersion"><el-input v-model="endpointDialog.form.contractVersion" placeholder="1.0" /></el-form-item>
          <el-form-item label="接口状态" prop="status"><el-select v-model="endpointDialog.form.status"><el-option label="草稿" value="DRAFT" /><el-option label="启用" value="ACTIVE" /><el-option label="停用" value="DISABLED" /></el-select></el-form-item>
          <el-form-item label="凭证引用"><el-input v-model="endpointDialog.form.credentialRef" placeholder="可选的密钥服务引用" /></el-form-item>
          <el-form-item label="凭证明文"><el-input v-model="endpointDialog.form.secret" type="text" autocomplete="off" :placeholder="endpointDialog.form.hasCredential ? '留空保持原凭证' : '服务端加密保存'" /></el-form-item>
          <el-form-item v-if="endpointDialog.editing" label="当前生效凭证"><el-input :model-value="endpointDialog.form.credentialResolved === false ? '引用暂未解析，请核对来源或接口配置' : endpointDialog.form.effectiveSecret" readonly autocomplete="off" /></el-form-item>
        </div>
        <HttpCachePolicyEditor v-model="endpointDialog.form.configJson" :response-type="endpointDialog.form.responseType" />
        <el-form-item label="响应与认证策略（JSON，不填写凭证）" prop="configJson"><el-input v-model="endpointDialog.form.configJson" type="textarea" :rows="11" spellcheck="false" /><div class="form-tip">结构化数据至少配置记录路径（rowsPath）；业务成功判断可使用响应模式（envelopeMode）、成功标记路径（successPath）或状态码路径（codePath）；表单参数由数据集参数声明提供。</div></el-form-item>
        <el-form-item label="备注"><el-input v-model="endpointDialog.form.remark" type="textarea" :rows="2" maxlength="500" show-word-limit /></el-form-item>
      </el-form>
      <template #footer><el-button @click="endpointDialog.open = false">取消</el-button><el-button type="primary" :loading="endpointDialog.saving" @click="submitEndpoint">保存接口</el-button></template>
    </el-dialog>

    <el-dialog v-model="endpointTestDialog.open" :title="endpointTestDialog.result ? '接口测试结果' : '接口测试参数'" width="900px" destroy-on-close>
      <div v-if="!endpointTestDialog.result" class="test-parameter-panel">
        <el-alert type="info" :closable="false" show-icon title="测试参数只用于本次服务端连接测试，不会保存到接口配置。" />
        <el-form label-position="top" class="editor-form">
          <el-form-item label="参数 JSON">
            <el-input v-model="endpointTestDialog.paramsText" type="textarea" :rows="10" spellcheck="false" placeholder='例如 {"projectCode":"demo"}' />
            <div class="form-tip">路径变量使用同名字段；表单字段必须已在接口的表单字段配置（formFields）中登记。</div>
          </el-form-item>
        </el-form>
      </div>
      <div v-else class="test-result-panel">
        <div class="test-summary-grid">
          <div><span>质量状态</span><strong :class="qualityTone(endpointTestDialog.result.quality)">{{ qualityLabel(endpointTestDialog.result.quality) }}</strong></div>
          <div><span>请求编号（ID）</span><code>{{ endpointTestDialog.result.requestId || '—' }}</code></div>
          <div><span>响应行数</span><strong>{{ endpointTestDialog.result.rowCount ?? endpointTestDialog.result.rows?.length ?? 0 }}</strong></div>
          <div><span>耗时</span><strong>{{ endpointTestDialog.result.latencyMs ?? '—' }} ms</strong></div>
        </div>
        <el-alert v-if="endpointTestDialog.result.message" :type="endpointTestDialog.result.quality === 'SUCCESS' ? 'success' : 'warning'" :closable="false" show-icon :title="endpointTestDialog.result.message" />
        <el-table v-if="endpointTestDialog.result.rows?.length" :data="endpointTestDialog.result.rows.slice(0, 50)" max-height="420">
          <el-table-column v-for="column in testColumns" :key="column" :prop="column" :label="column" min-width="150" show-overflow-tooltip />
        </el-table>
        <pre v-else class="detail-json">{{ prettyJson(endpointTestDialog.result) }}</pre>
      </div>
      <template #footer>
        <el-button @click="endpointTestDialog.open = false">关闭</el-button>
        <el-button v-if="!endpointTestDialog.result" type="primary" :loading="endpointTestDialog.testing" @click="submitEndpointTest">执行测试</el-button>
        <el-button v-else-if="endpointTestNeedsParams(endpointTestDialog.row)" type="primary" plain @click="resetEndpointTest">修改参数重试</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="batchDialog.open" title="接收批次明细" width="980px" destroy-on-close>
      <el-descriptions v-if="batchDialog.row" :column="3" border>
        <el-descriptions-item label="平台请求编号（ID）" :span="2"><code class="request-id">{{ batchDialog.row.platformRequestId }}</code></el-descriptions-item>
        <el-descriptions-item label="状态"><el-tag :type="batchStatusTone(batchDialog.row.status)">{{ batchStatusLabel(batchDialog.row.status) }}</el-tag></el-descriptions-item>
        <el-descriptions-item label="接入方">{{ integrationName(batchDialog.row.integrationId) }}</el-descriptions-item>
        <el-descriptions-item label="接口">{{ batchDialog.row.endpointCode }}</el-descriptions-item>
        <el-descriptions-item label="报文版本（Schema）">v{{ batchDialog.row.schemaVersion }}</el-descriptions-item>
        <el-descriptions-item label="幂等标识" :span="2">{{ batchDialog.row.normalizedIdempotencyKey }}</el-descriptions-item>
        <el-descriptions-item label="接收时间">{{ formatDateTime(batchDialog.row.receivedAt) }}</el-descriptions-item>
        <el-descriptions-item label="处理统计" :span="3"><div class="batch-metrics"><span class="metric-good">成功 {{ batchDialog.row.acceptedCount || 0 }}</span><span>处理中 {{ batchDialog.row.processingCount || 0 }}</span><span class="metric-warn">拒绝 {{ batchDialog.row.rejectedCount || 0 }}</span><span>重复 {{ batchDialog.row.duplicateCount || 0 }}</span></div></el-descriptions-item>
      </el-descriptions>
      <el-divider content-position="left">逐项处理结果</el-divider>
      <el-alert v-if="integrationBatchDetailsExpired(batchDialog.row)" type="info" :closable="false" show-icon title="成功明细已到期，当前为摘要，计数不受影响" :description="`明细清理时间：${formatDateTime(batchDialog.row.detailsExpiredAt)}。必要去重摘要继续保留。`" />
      <el-table v-else :data="batchDialog.row?.items || []" max-height="480">
        <el-table-column prop="index" label="序号" width="80" />
        <el-table-column prop="businessKey" label="业务主键" min-width="220" />
        <el-table-column prop="status" label="状态" width="130"><template #default="scope"><el-tag :type="messageStatusTone(scope.row.status)">{{ messageStatusLabel(scope.row.status) }}</el-tag></template></el-table-column>
        <el-table-column label="错误说明（错误码）" min-width="200"><template #default="{ row }">{{ integrationResultLabel(row.code) }}</template></el-table-column>
        <template #empty><el-empty v-if="batchDialog.row" description="批次没有逐项记录" /></template>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import ManagementToolbar from "@/components/DashboardManagement/ManagementToolbar.vue";
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { formatDashboardDateTime } from "@/utils/dashboard";
import { checkPermi } from "@/utils/permission";
import DataManagementCreateButton from "@/components/DashboardDataManagement/CreateButton.vue";
import DataManagementEmptyState from "@/components/DashboardDataManagement/EmptyState.vue";
import DatasetManager from "../dataset/index.vue";
import { allowedDataManagementTabs, allowedInboundDataTabs, resolveDataManagementTab, resolveInboundDataTab, dataManagementTabQuery } from "@/utils/dashboardDataManagement";
import IntegrationOperations from "./components/IntegrationOperations.vue";
import InboundDatasetDialog from "./components/InboundDatasetDialog.vue";
import HttpCachePolicyEditor from "./components/HttpCachePolicyEditor.vue";
import DataSourceManager from "./components/DataSourceManager.vue";
import TimezoneSelect from "@/components/DashboardDataManagement/TimezoneSelect.vue";
import { integrationProfiles as profiles, profileDescription, profileLabel, networkProfileLabel, timezoneLabel, requestMethodLabel, integrationPolicyHelp, integrationResultLabel } from "@/utils/dashboardIntegrationPresentation";
import { inspectIntegrationRetention, updateIntegrationRetention, validateIntegrationRetention, integrationRetentionDays, integrationRetentionSummary, integrationBatchDetailsExpired } from "@/utils/dashboardIntegrationRetention";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  Back,
  FolderOpened,
  CircleCheck,
  Clock,
  Connection,
  CopyDocument,
  DataAnalysis,
  Delete,
  Document,
  Key,
  Link,
  Lock,
  MoreFilled,
  Monitor,
  Plus,
  Promotion,
  Refresh,
  Search,
  Tickets,
  Warning,
} from "@element-plus/icons-vue";
import {
  addDashboardIntegration,
  addDashboardIntegrationEndpoint,
  addDashboardIntegrationKey,
  deleteDashboardIntegration,
  deleteDashboardIntegrationEndpoint,
  getDashboardIntegration,
  getIntegrationEndpointConfiguration,
  getIntegrationKeyConfiguration,
  getDashboardIntegrationBatch,
  pageDashboardIntegrationBatches,
  pageDashboardIntegrationDeadLetters,
  pageDashboardIntegrationEndpoints,
  listDashboardIntegrationKeys,
  listDashboardIntegrations,
  pageDashboardIntegrations,
  replayDashboardIntegrationDeadLetter,
  revokeDashboardIntegrationKey,
  testDashboardIntegrationEndpoint,
  updateDashboardIntegration,
  updateDashboardIntegrationEndpoint,
  updateDashboardIntegrationStatus,
} from "@/api/dashboardIntegration";
import { listDashboardDataSources } from "@/api/dashboard";
import { listDataFolders } from "@/api/dashboardDataFolder";
import { buildDataFolderOptions } from "@/utils/dashboardDataFolder";
import DataFolderSidebar from "@/components/DashboardDataManagement/DataFolderSidebar.vue";
import DataFolderManager from "@/components/DashboardDataManagement/DataFolderManager.vue";
import DataFolderMoveDialog from "@/components/DashboardDataManagement/DataFolderMoveDialog.vue";

const route = useRoute();
const router = useRouter();
const datasetManager = ref();
const sourceManager = ref();
const operationsPanel = ref();
const inboundDatasetDialog = ref();
const canSources = computed(() => checkPermi(["dashboard:dataset:list"]));
const canConfigureInbound = computed(() => checkPermi(["dashboard:integration:list"]));
const canMonitor = computed(() => checkPermi(["dashboard:integration:monitor"]));
const allowedTabs = computed(() => allowedDataManagementTabs(checkPermi));
const allowedInboundTabs = computed(() => allowedInboundDataTabs(checkPermi));
const canInbound = computed(() => allowedInboundTabs.value.length > 0);
const activeTab = ref(resolveDataManagementTab(route.query.tab, allowedTabs.value, route.query.focus));
const activeInboundTab = ref(resolveInboundDataTab(route.query.tab, allowedInboundTabs.value, route.query.inboundTab));
const integrations = ref([]);
const integrationTotal = ref(0);
const integrationOptions = ref([]);
const integrationLoading = ref(false);
const integrationQuery = reactive({ pageNum: 1, pageSize: 20, keyword: "", environment: "", status: "", folderId: null, includeChildren: false });
const integrationFolders = ref([]);
const integrationFolderOptions = computed(() => buildDataFolderOptions(integrationFolders.value));
const integrationFolderManagerOpen = ref(false);
const integrationFolderMoveOpen = ref(false);
const selectedIntegrationIds = ref([]);
let integrationRequestId = 0, integrationOptionsRequestId = 0, sourceOptionsRequestId = 0;
const sourceRows = ref([]);
const sourceLoading = ref(false);
const selectedSourceCode = ref("");
const endpointRows = ref([]);
const endpointTotal = ref(0);
const endpointQuery = reactive({ pageNum: 1, pageSize: 20, keyword: '', status: '' });
const endpointToolbarActions = [{ key: 'reload-sources', label: '刷新来源', icon: Refresh }];
let endpointRequestId = 0, batchRequestId = 0, deadRequestId = 0;
const endpointLoading = ref(false);
const endpointTesting = ref("");
const batchRows = ref([]);
const batchTotal = ref(0);
const batchLoading = ref(false);
const batchQuery = reactive({ pageNum: 1, pageSize: 20, integrationId: "", status: "" });
const deadRows = ref([]);
const deadTotal = ref(0);
const deadLoading = ref(false);
const deadQuery = reactive({ pageNum: 1, pageSize: 20, integrationId: "", keyword: "" });

const integrationFormRef = ref();
const integrationDialog = reactive({ open: false, editing: false, saving: false, form: emptyIntegration() });
const integrationRetention = computed(() => inspectIntegrationRetention(integrationDialog.form.configJson));
const integrationDetailDialog = reactive({ open: false, row: null });
const keyFormRef = ref();
const keyDialog = reactive({ open: false, createOpen: false, loading: false, saving: false, integration: null, rows: [], secretResult: "", form: emptyKey() });
const endpointFormRef = ref();
const endpointDialog = reactive({ open: false, editing: false, saving: false, form: emptyEndpoint() });
const endpointTestDialog = reactive({ open: false, result: null, row: null, paramsText: "{}", testing: false });
const batchDialog = reactive({ open: false, row: null });

const integrationStatuses = [
  { value: "DRAFT", label: "草稿" },
  { value: "ACTIVE", label: "启用" },
  { value: "PAUSED", label: "暂停" },
  { value: "REVOKED", label: "已吊销" },
];
const batchStatuses = [
  { value: "PROCESSING", label: "处理中" },
  { value: "ACCEPTED", label: "已接受" },
  { value: "PARTIAL", label: "部分成功" },
  { value: "FAILED", label: "失败" },
  { value: "CONFLICT", label: "冲突" },
];
const integrationRules = {
  integrationCode: [{ required: true, message: "请输入接入方编码", trigger: "blur" }],
  integrationName: [{ required: true, message: "请输入接入方名称", trigger: "blur" }],
  environment: [{ required: true, message: "请选择环境", trigger: "change" }],
  profile: [{ required: true, message: "请选择消息类型", trigger: "change" }],
  schemaVersion: [{ required: true, message: "请输入报文版本", trigger: "blur" }],
  endpointCode: [{ required: true, message: "请输入接口编码", trigger: "blur" }],
  timezone: [{ required: true, message: "请选择时区", trigger: "change" }],
  configJson: [{ validator: validateIntegrationPolicyField, trigger: "blur" }],
  rawRetentionDays: [{ validator: validateRawRetentionField, trigger: "blur" }],
};
const keyRules = {
  keyId: [{ required: true, message: "请输入密钥标识", trigger: "blur" }],
  provider: [{ required: true, message: "请选择认证方式", trigger: "change" }],
  identityValue: [{ required: true, message: "签名认证必须填写身份标识（HMAC）", trigger: "blur" }],
  secret: [{ required: true, message: "请输入密钥明文", trigger: "blur" }],
};
const endpointRules = {
  endpointCode: [{ required: true, message: "请输入接口编码", trigger: "blur" }],
  endpointName: [{ required: true, message: "请输入接口名称", trigger: "blur" }],
  path: [{ required: true, message: "请输入相对路径", trigger: "blur" }],
  method: [{ required: true, message: "请选择请求方法", trigger: "change" }],
  requestContentType: [{ required: true, message: "请选择请求类型", trigger: "change" }],
  responseType: [{ required: true, message: "请选择响应类型", trigger: "change" }],
  authProvider: [{ required: true, message: "请选择认证方式", trigger: "change" }],
  configJson: [{ required: true, message: "请输入接口策略（JSON）", trigger: "blur" }],
};

const httpSources = computed(() => sourceRows.value.filter((item) => String(item.type || "").toUpperCase() === "HTTP"));
const testColumns = computed(() => {
  const rows = endpointTestDialog.result?.rows || [];
  const names = new Set();
  rows.slice(0, 20).forEach((row) => Object.keys(row || {}).forEach((name) => names.add(name)));
  return [...names].slice(0, 30);
});

function emptyIntegration() {
  return {
    integrationId: null,
    folderId: 0,
    integrationCode: "",
    integrationName: "",
    environment: "TEST",
    profile: "EVENT",
    schemaVersion: "1.0",
    endpointCode: "events",
    networkProfile: "PUBLIC_HTTPS",
    timezone: "Asia/Shanghai",
    maxBatchItems: 500,
    maxBodyBytes: 1048576,
    rateLimit: 10,
    concurrencyLimit: 20,
    rawRetentionDays: 30,
    status: "DRAFT",
    projectScopeText: "",
    allowedIpsText: "",
    configJson: defaultIntegrationConfig(),
    remark: "",
  };
}

function defaultIntegrationConfig() {
  return JSON.stringify(
    {
      timestampUnit: "SECONDS",
      clockSkewSeconds: 300,
      nonceRequired: true,
      idempotencyRequired: true,
      allowDerivedIdempotency: false,
      requireSchemaHeader: false,
      processingMode: "ASYNC",
      itemErrorMode: "PARTIAL",
      maxAttempts: 3,
      deadLetterAfter: 5,
      retainRawBody: false,
      successRetentionDays: 30,
      recordRetentionDays: 0,
      canonicalProfile: "METHOD_PATH_INTEGRATION_KEY_TIMESTAMP_NONCE_IDEMPOTENCY_SCHEMA_BODY_V1",
      signatureEncoding: "BASE64",
      apiKeyHeader: "X-Api-Key",
      allowedOrigins: [],
      unknownFieldMode: "REJECT",
      messageIdRequired: true,
      businessTimeRequired: true,
      projectTenantMustMatch: false,
    },
    null,
    2,
  );
}

function emptyKey() {
  return { keyId: "", provider: "HMAC_V1", identityValue: "", secret: "", validFrom: "", validTo: "", remark: "" };
}

function emptyEndpoint() {
  return {
    endpointId: null,
    endpointCode: "",
    endpointName: "",
    path: "/",
    method: "GET",
    requestContentType: "NONE",
    responseType: "JSON",
    authProvider: "INHERIT",
    credentialRef: "",
    secret: "",
    hasCredential: false,
    contractVersion: "1.0",
    status: "DRAFT",
    configJson: defaultEndpointConfig(),
    remark: "",
  };
}

function defaultEndpointConfig() {
  return JSON.stringify({ envelopeMode: "HTTP_ONLY", rowsPath: "", rowLimit: 1000, maxResponseBytes: 524288, maxAttempts: 2, backoffMs: 100, rowErrorMode: "PARTIAL" }, null, 2);
}

function unwrap(response, fallback = null) {
  if (response && Object.prototype.hasOwnProperty.call(response, "data")) return response.data ?? fallback;
  return response ?? fallback;
}

// 每个列表独立记录请求序号和参数快照；删除、移动导致末页为空时回到有效页。
async function readValidPage(requestPage, querySnapshot, isCurrent, updatePage) {
  let response = await requestPage({ ...querySnapshot });
  if (!isCurrent()) return null;
  const lastPage = Math.max(1, Math.ceil(Number(response.total || 0) / querySnapshot.pageSize));
  if (querySnapshot.pageNum > lastPage) {
    querySnapshot.pageNum = lastPage;
    updatePage(lastPage);
    response = await requestPage({ ...querySnapshot });
    if (!isCurrent()) return null;
  }
  return response;
}

async function loadIntegrations() {
  const requestId = ++integrationRequestId;
  integrationLoading.value = true;
  selectedIntegrationIds.value = [];
  const params = { ...integrationQuery, folderId: integrationQuery.folderId ?? undefined };
  try {
    const response = await readValidPage(pageDashboardIntegrations, params,
      () => requestId === integrationRequestId, page => { integrationQuery.pageNum = page; });
    if (!response) return;
    integrations.value = response.rows || [];
    integrationTotal.value = Number(response.total || 0);
  } finally {
    if (requestId === integrationRequestId) integrationLoading.value = false;
  }
}
function searchIntegrations() {
  integrationQuery.pageNum = 1;
  return loadIntegrations();
}
async function loadIntegrationOptions() {
  const requestId = ++integrationOptionsRequestId;
  const response = await listDashboardIntegrations();
  // 台账与死信的接入方选择使用完整接口，不能从当前分页行派生。
  if (requestId === integrationOptionsRequestId) integrationOptions.value = unwrap(response, []);
}
async function refreshInbound() {
  const response = await listDataFolders('integration');
  integrationFolders.value = unwrap(response, []);
  if (integrationQuery.folderId > 0 && !integrationFolders.value.some(folder => Number(folder.folderId) === Number(integrationQuery.folderId))) {
    integrationQuery.folderId = null;
    integrationQuery.pageNum = 1;
  }
  return loadIntegrations();
}
function integrationFolderName(row) {
  return row.folderName || integrationFolders.value.find(folder => Number(folder.folderId) === Number(row.folderId))?.folderName || '未分类';
}

function openSourceEndpoints(source) {
  selectedSourceCode.value = source.code;
  return changeEndpointSource();
}
function changeEndpointSource() {
  endpointRows.value = [];
  endpointTotal.value = 0;
  Object.assign(endpointQuery, { pageNum: 1, keyword: '', status: '' });
  return loadEndpoints();
}
function returnToSources() {
  selectedSourceCode.value = '';
  return loadEndpoints();
}
function handleEndpointToolbarAction(action) {
  if (action === 'reload-sources') return refreshOutbound();
}
async function loadSources() {
  const requestId = ++sourceOptionsRequestId;
  sourceLoading.value = true;
  try {
    const response = await listDashboardDataSources();
    if (requestId !== sourceOptionsRequestId) return;
    sourceRows.value = Array.isArray(unwrap(response, [])) ? unwrap(response, []) : [];
    if (!httpSources.value.some(item => item.code === selectedSourceCode.value)) selectedSourceCode.value = '';
    return loadEndpoints();
  } finally {
    if (requestId === sourceOptionsRequestId) sourceLoading.value = false;
  }
}
async function loadEndpoints() {
  const requestId = ++endpointRequestId;
  const sourceCode = selectedSourceCode.value;
  if (!sourceCode) {
    endpointRows.value = [];
    endpointTotal.value = 0;
    endpointLoading.value = false;
    return;
  }
  endpointLoading.value = true;
  const params = { ...endpointQuery };
  try {
    const response = await readValidPage(query => pageDashboardIntegrationEndpoints(sourceCode, query), params,
      () => requestId === endpointRequestId && sourceCode === selectedSourceCode.value,
      page => { endpointQuery.pageNum = page; });
    if (!response) return;
    endpointRows.value = response.rows || [];
    endpointTotal.value = Number(response.total || 0);
  } finally {
    if (requestId === endpointRequestId) endpointLoading.value = false;
  }
}
function searchEndpoints() {
  endpointQuery.pageNum = 1;
  return loadEndpoints();
}
function resetEndpointQuery() {
  Object.assign(endpointQuery, { keyword: '', status: '' });
  return searchEndpoints();
}
async function loadBatches() {
  const requestId = ++batchRequestId;
  batchLoading.value = true;
  const params = { ...batchQuery, integrationId: batchQuery.integrationId || undefined, status: batchQuery.status || undefined };
  try {
    const response = await readValidPage(pageDashboardIntegrationBatches, params,
      () => requestId === batchRequestId, page => { batchQuery.pageNum = page; });
    if (!response) return;
    batchRows.value = response.rows || [];
    batchTotal.value = Number(response.total || 0);
  } finally {
    if (requestId === batchRequestId) batchLoading.value = false;
  }
}
function searchBatches() {
  batchQuery.pageNum = 1;
  return loadBatches();
}
async function loadDeadLetters() {
  const requestId = ++deadRequestId;
  deadLoading.value = true;
  const params = { ...deadQuery, integrationId: deadQuery.integrationId || undefined, keyword: deadQuery.keyword || undefined };
  try {
    const response = await readValidPage(pageDashboardIntegrationDeadLetters, params,
      () => requestId === deadRequestId, page => { deadQuery.pageNum = page; });
    if (!response) return;
    deadRows.value = response.rows || [];
    deadTotal.value = Number(response.total || 0);
  } finally {
    if (requestId === deadRequestId) deadLoading.value = false;
  }
}
function searchDeadLetters() {
  deadQuery.pageNum = 1;
  return loadDeadLetters();
}

function selectTab(name) {
  activeTab.value = resolveDataManagementTab(name, allowedTabs.value);
}

function handleNavigationChange() {
  const name = activeTab.value;
  if (!allowedTabs.value.includes(name)) return;
  const query = dataManagementTabQuery(route.query, name, activeInboundTab.value);
  if (route.query.tab !== query.tab || route.query.inboundTab !== query.inboundTab || route.query.focus === 'source') {
    router.replace({ path: "/dashboard/integration", query, hash: route.hash });
  }
  if (name === "outbound") sourceManager.value?.refresh();
  if (name !== "inbound" || !allowedInboundTabs.value.includes(activeInboundTab.value)) return;
  if (activeInboundTab.value === "integrations") refreshInbound();
  if (activeInboundTab.value === "batches") { if (canConfigureInbound.value) loadIntegrationOptions(); loadBatches(); }
  if (activeInboundTab.value === "dead") { if (canConfigureInbound.value) loadIntegrationOptions(); loadDeadLetters(); }
}

function refreshOutbound() {
  return loadSources();
}

function resetIntegrationQuery() {
  Object.assign(integrationQuery, { keyword: "", environment: "", status: "" });
  return searchIntegrations();
}

function resetBatchQuery() {
  Object.assign(batchQuery, { integrationId: "", status: "" });
  return searchBatches();
}

function resetDeadQuery() {
  Object.assign(deadQuery, { integrationId: "", keyword: "" });
  return searchDeadLetters();
}

function openIntegrationCreate() {
  integrationDialog.editing = false;
  integrationDialog.form = { ...emptyIntegration(), folderId: integrationQuery.folderId > 0 ? integrationQuery.folderId : 0 };
  integrationDialog.open = true;
}

function openIntegrationEdit(row) {
  integrationDialog.editing = true;
  integrationDialog.form = integrationFormFrom(row);
  integrationDialog.open = true;
}

function integrationFormFrom(row) {
  const data = { ...emptyIntegration(), ...row, folderId: Number(row.folderId) || 0 };
  data.projectScopeText = (row.projectScope || []).join(",");
  data.allowedIpsText = (row.allowedIps || []).join(",");
  data.configJson = prettyJson(row.configJson || {});
  return data;
}

function setIntegrationRetention(key, value) {
  try {
    integrationDialog.form.configJson = updateIntegrationRetention(integrationDialog.form.configJson, key, value);
    integrationFormRef.value?.clearValidate('configJson');
  } catch (error) {
    ElMessage.warning(error.message);
  }
}

function validateIntegrationPolicyField(_rule, value, callback) {
  try {
    validateIntegrationRetention(value);
    callback();
  } catch (error) {
    callback(error);
  }
}

function validateRawRetentionField(_rule, value, callback) {
  try {
    integrationRetentionDays(value, 'rawRetentionDays');
    callback();
  } catch (error) {
    callback(error);
  }
}

function viewIntegration(row) {
  integrationDetailDialog.row = null;
  integrationDetailDialog.open = true;
  getDashboardIntegration(row.integrationId).then((response) => {
    integrationDetailDialog.row = unwrap(response, row);
  });
}

function submitIntegration() {
  integrationFormRef.value?.validate((valid) => {
    if (!valid) return;
    let config;
    try {
      config = validateIntegrationRetention(integrationDialog.form.configJson);
      integrationRetentionDays(integrationDialog.form.rawRetentionDays, 'rawRetentionDays');
    } catch (error) {
      ElMessage.warning(error.message);
      return;
    }
    const form = integrationDialog.form;
    const payload = {
      integrationId: form.integrationId || undefined,
      folderId: Number(form.folderId) || 0,
      integrationCode: String(form.integrationCode || "").trim(),
      integrationName: String(form.integrationName || "").trim(),
      environment: form.environment,
      profile: form.profile,
      schemaVersion: String(form.schemaVersion || "").trim(),
      endpointCode: String(form.endpointCode || "").trim(),
      networkProfile: form.networkProfile,
      timezone: String(form.timezone || "").trim(),
      maxBatchItems: form.maxBatchItems,
      maxBodyBytes: form.maxBodyBytes,
      rateLimit: form.rateLimit,
      concurrencyLimit: form.concurrencyLimit,
      rawRetentionDays: form.rawRetentionDays,
      status: form.status,
      projectScope: splitText(form.projectScopeText),
      allowedIps: splitText(form.allowedIpsText),
      configJson: JSON.stringify(config),
      remark: form.remark,
    };
    integrationDialog.saving = true;
    const action = integrationDialog.editing ? updateDashboardIntegration(payload) : addDashboardIntegration(payload);
    action.then(() => {
      ElMessage.success("接入方已保存");
      integrationDialog.open = false;
      return loadIntegrations();
    }).finally(() => {
      integrationDialog.saving = false;
    });
  });
}

function canChangeIntegrationStatus(row) {
  return (["DRAFT", "PAUSED"].includes(row.status) && checkPermi(["dashboard:integration:edit"]))
    || (row.status === "ACTIVE" && checkPermi(["dashboard:integration:pause"]))
    || (row.status !== "REVOKED" && checkPermi(["dashboard:integration:revoke"]))
    || (["DRAFT", "REVOKED"].includes(row.status) && checkPermi(["dashboard:integration:delete"]));
}

function handleIntegrationCommand(command, row) {
  if (command === "delete") return removeIntegration(row);
  const target = command === "active" ? "ACTIVE" : command === "pause" ? "PAUSED" : "REVOKED";
  const title = target === "ACTIVE" ? "启用接入方" : target === "PAUSED" ? "暂停接入方" : "吊销接入方";
  const message = target === "REVOKED" ? `确定吊销接入方“${row.integrationName}”吗？吊销后外部推送会被拒绝。` : `确定${title}“${row.integrationName}”吗？`;
  ElMessageBox.confirm(message, title, { type: target === "REVOKED" ? "warning" : "info" })
    .then(() => updateDashboardIntegrationStatus(row.integrationId, target))
    .then(() => {
      ElMessage.success(`${title}成功`);
      return loadIntegrations();
    })
    .catch(() => {});
}

function removeIntegration(row) {
  ElMessageBox.confirm(`确定删除接入方“${row.integrationName}”吗？`, "删除接入方", { type: "warning" })
    .then(() => deleteDashboardIntegration(row.integrationId))
    .then(() => {
      ElMessage.success("接入方已删除");
      return loadIntegrations();
    })
    .catch(() => {});
}

function openKeys(row) {
  keyDialog.integration = row;
  keyDialog.open = true;
  keyDialog.createOpen = false;
  keyDialog.secretResult = "";
  keyDialog.form = emptyKey();
  loadKeys();
}

function loadKeys() {
  if (!keyDialog.integration) return Promise.resolve();
  keyDialog.loading = true;
  return listDashboardIntegrationKeys(keyDialog.integration.integrationId)
    .then((response) => {
      keyDialog.rows = Array.isArray(unwrap(response, [])) ? unwrap(response, []) : [];
    })
    .finally(() => {
      keyDialog.loading = false;
    });
}

function submitKey() {
  keyFormRef.value?.validate((valid) => {
    if (!valid) return;
    const form = keyDialog.form;
    if (form.validFrom && form.validTo && new Date(form.validFrom.replace(" ", "T")) >= new Date(form.validTo.replace(" ", "T"))) {
      ElMessage.warning("失效时间必须晚于生效时间");
      return;
    }
    keyDialog.saving = true;
    addDashboardIntegrationKey(keyDialog.integration.integrationId, {
      keyId: String(form.keyId || "").trim(),
      provider: form.provider,
      identityValue: String(form.identityValue || "").trim(),
      secret: form.secret,
      validFrom: form.validFrom || undefined,
      validTo: form.validTo || undefined,
      remark: form.remark,
    }).then((response) => {
      const result = unwrap(response, {});
      keyDialog.secretResult = result.secret || form.secret;
      keyDialog.form = emptyKey();
      keyDialog.createOpen = false;
      ElMessage.success("密钥已创建，请立即保存明文");
      return loadKeys();
    }).finally(() => {
      keyDialog.saving = false;
    });
  });
}

function revokeKey(row) {
  ElMessageBox.confirm(`确定吊销密钥“${row.keyId}”吗？吊销后无法恢复。`, "吊销密钥", { type: "warning" })
    .then(() => revokeDashboardIntegrationKey(row.integrationKeyId))
    .then(() => {
      ElMessage.success("密钥已吊销");
      return loadKeys();
    })
    .catch(() => {});
}

function generateSecret() {
  const bytes = new Uint8Array(32);
  if (window.crypto?.getRandomValues) window.crypto.getRandomValues(bytes);
  else for (let index = 0; index < bytes.length; index += 1) bytes[index] = Math.floor(Math.random() * 256);
  keyDialog.form.secret = btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function openEndpointCreate() {
  if (!selectedSourceCode.value) {
    ElMessage.warning("请先选择 HTTP 数据源");
    return;
  }
  endpointDialog.editing = false;
  endpointDialog.form = emptyEndpoint();
  endpointDialog.open = true;
}

async function openEndpointEdit(row) {
  const result = await getIntegrationEndpointConfiguration(row.endpointId);
  const data = unwrap(result, row);
  endpointDialog.editing = true;
  endpointDialog.form = { ...emptyEndpoint(), ...data, configJson: prettyJson(data.configJson || {}), secret: data.secret || "", originalSecret: data.secret || "" };
  endpointDialog.open = true;
}

async function revealKey(row) {
  const result = await getIntegrationKeyConfiguration(row.integrationKeyId);
  keyDialog.secretResult = unwrap(result, {}).secret || "";
}

function submitEndpoint() {
  if (!selectedSourceCode.value) {
    ElMessage.warning("请先选择 HTTP 数据源");
    return;
  }
  endpointFormRef.value?.validate((valid) => {
    if (!valid) return;
    let config;
    try {
      config = parseJson(endpointDialog.form.configJson, "接口配置");
    } catch (error) {
      ElMessage.warning(error.message);
      return;
    }
    const form = endpointDialog.form;
    const payload = {
      endpointId: form.endpointId || undefined,
      endpointCode: String(form.endpointCode || "").trim(),
      endpointName: String(form.endpointName || "").trim(),
      path: String(form.path || "").trim(),
      method: form.method,
      requestContentType: form.requestContentType,
      responseType: form.responseType,
      authProvider: form.authProvider,
      credentialRef: String(form.credentialRef || "").trim(),
      contractVersion: String(form.contractVersion || "1.0").trim(),
      status: form.status,
      configJson: JSON.stringify(config),
      remark: form.remark,
    };
    if (String(form.secret || "").trim() && form.secret !== form.originalSecret) payload.secret = form.secret;
    endpointDialog.saving = true;
    const action = endpointDialog.editing ? updateDashboardIntegrationEndpoint(selectedSourceCode.value, payload) : addDashboardIntegrationEndpoint(selectedSourceCode.value, payload);
    action.then(() => {
      ElMessage.success("接口已保存");
      endpointDialog.open = false;
      return loadEndpoints();
    }).finally(() => {
      endpointDialog.saving = false;
    });
  });
}

function testEndpoint(row) {
  if (endpointTestNeedsParams(row)) {
    endpointTestDialog.row = row;
    endpointTestDialog.result = null;
    endpointTestDialog.paramsText = JSON.stringify(endpointTestParameterTemplate(row), null, 2);
    endpointTestDialog.open = true;
    return;
  }
  runEndpointTest(row, {});
}

function endpointTestNeedsParams(row) {
  return String(row?.requestContentType || "").toUpperCase() === "FORM_URLENCODED"
    || /\{[A-Za-z_][A-Za-z0-9_]*\}|\$\{[A-Za-z_][A-Za-z0-9_]*\}/.test(String(row?.path || ""));
}

function endpointTestParameterTemplate(row) {
  const result = {};
  const config = parseJson(row?.configJson, {});
  (Array.isArray(config.formFields) ? config.formFields : []).forEach((name) => {
    if (/^[A-Za-z_][A-Za-z0-9_]{0,63}$/.test(String(name || ""))) result[name] = "";
  });
  const path = String(row?.path || "");
  for (const match of path.matchAll(/\{([A-Za-z_][A-Za-z0-9_]*)\}|\$\{([A-Za-z_][A-Za-z0-9_]*)\}/g)) {
    result[match[1] || match[2]] ??= "";
  }
  return result;
}

function submitEndpointTest() {
  let params;
  try {
    params = parseJson(endpointTestDialog.paramsText, "endpoint 测试参数");
    if (!params || Array.isArray(params) || typeof params !== "object") throw new Error("endpoint 测试参数必须是 JSON 对象");
  } catch (error) {
    ElMessage.warning(error.message);
    return;
  }
  runEndpointTest(endpointTestDialog.row, params);
}

function resetEndpointTest() {
  endpointTestDialog.result = null;
}

function runEndpointTest(row, params) {
  endpointTestDialog.row = row;
  endpointTestDialog.testing = true;
  endpointTesting.value = row.endpointCode;
  testDashboardIntegrationEndpoint(selectedSourceCode.value, row.endpointCode, params)
    .then((response) => {
      endpointTestDialog.result = unwrap(response, {});
      endpointTestDialog.open = true;
      return loadEndpoints();
    })
    .finally(() => {
      endpointTesting.value = "";
      endpointTestDialog.testing = false;
    });
}

function changeEndpointStatus(row, status) {
  const label = status === "ACTIVE" ? "启用" : "停用";
  ElMessageBox.confirm(`确定${label}接口“${row.endpointName}”吗？`, `${label}接口`, { type: status === "ACTIVE" ? "info" : "warning" })
    .then(() => updateDashboardIntegrationEndpoint(selectedSourceCode.value, { endpointId: row.endpointId, status }))
    .then(() => {
      ElMessage.success(`接口已${label}`);
      return loadEndpoints();
    })
    .catch(() => {});
}

function removeEndpoint(row) {
  ElMessageBox.confirm(`确定删除接口“${row.endpointName}”吗？被数据集引用时无法删除。`, "删除接口", { type: "warning" })
    .then(() => deleteDashboardIntegrationEndpoint(row.endpointId))
    .then(() => {
      ElMessage.success("接口已删除");
      return loadEndpoints();
    })
    .catch(() => {});
}

function openBatch(row) {
  batchDialog.row = null;
  batchDialog.open = true;
  getDashboardIntegrationBatch(row.batchId).then((response) => {
    batchDialog.row = unwrap(response, row);
  });
}

function replayDeadLetter(row) {
  ElMessageBox.prompt("可填写本次重放原因（可选）", "重放死信", { inputPlaceholder: "例如：上游已修正字段格式" })
    .then(({ value }) => replayDashboardIntegrationDeadLetter(row.integrationMessageId, value || "人工重放"))
    .then(() => {
      ElMessage.success("死信已重新进入处理队列");
      return loadDeadLetters();
    })
    .catch(() => {});
}

function sourceLabel(code) {
  const source = httpSources.value.find((item) => item.code === code);
  return source ? `${source.name}（${source.code}）` : code || "未选择";
}

function integrationName(id) {
  return integrationOptions.value.find((item) => String(item.integrationId) === String(id))?.integrationName || `接入方 ${id}`;
}

function splitText(value) {
  return String(value || "").split(/[,，\n]/).map((item) => item.trim()).filter(Boolean);
}

function parseJson(value, label) {
  try {
    const parsed = JSON.parse(String(value || "{}"));
    if (!parsed || Array.isArray(parsed) || typeof parsed !== "object") throw new Error();
    return parsed;
  } catch {
    throw new Error(`${label}必须是 JSON 对象`);
  }
}

function prettyJson(value) {
  if (value == null || value === "") return "{}";
  if (typeof value === "string") {
    try { return JSON.stringify(JSON.parse(value), null, 2); } catch { return value; }
  }
  try { return JSON.stringify(value, null, 2); } catch { return String(value); }
}

function formatDateTime(value, fallback = "—") {
  return formatDashboardDateTime(value, fallback);
}

function copyText(value, message) {
  if (!value) return;
  if (navigator.clipboard?.writeText) navigator.clipboard.writeText(value).then(() => ElMessage.success(message));
  else {
    const input = document.createElement("textarea");
    input.value = value;
    input.style.position = "fixed";
    input.style.opacity = "0";
    document.body.appendChild(input);
    input.select();
    document.execCommand("copy");
    document.body.removeChild(input);
    ElMessage.success(message);
  }
}

function environmentLabel(value) { return { SANDBOX: "沙箱", TEST: "测试", PRODUCTION: "生产" }[value] || value || "—"; }
function integrationStatusLabel(value) { return integrationStatuses.find((item) => item.value === value)?.label || value || "—"; }
function integrationStatusTone(value) { return { ACTIVE: "success", PAUSED: "warning", REVOKED: "info", DRAFT: "primary" }[value] || "primary"; }
function requestTypeLabel(value) { return { NONE: "无请求体", JSON: "结构化请求（JSON）", FORM_URLENCODED: "表单请求" }[value] || value || "—"; }
function responseTypeLabel(value) { return value === "BINARY_MEDIA" ? "二进制媒体" : "结构化数据（JSON）"; }
function authProviderLabel(value) { return { INHERIT: "继承数据源", NO_AUTH: "无认证", BEARER: "访问令牌（Bearer）", API_KEY: "接口密钥（API Key）", HMAC_V1: "通用签名（HMAC）", HMAC_APPKEY_TIMESTAMP_V1: "应用标识与时间戳签名（HMAC）" }[value] || value || "—"; }
function endpointStatusLabel(value) { return { ACTIVE: "启用", DISABLED: "停用", DRAFT: "草稿" }[value] || value || "—"; }
function endpointStatusTone(value) { return value === "ACTIVE" ? "success" : value === "DISABLED" ? "info" : "warning"; }
function qualityLabel(value) { return { SUCCESS: "成功", NO_DATA: "无数据", PARTIAL: "部分成功", TRUNCATED: "已截断", INVALID_DATA: "数据无效", INVALID_CONFIG: "配置无效", AUTH_ERROR: "鉴权失败", TIMEOUT: "超时", RATE_LIMITED: "已限流", SOURCE_ERROR: "来源错误", NOT_CONNECTED: "未连接", CIRCUIT_OPEN: "断路器开启" }[value] || value || "未测试"; }
function qualityTone(value) { return ["SUCCESS", "NO_DATA"].includes(value) ? "quality-success" : ["PARTIAL", "TRUNCATED"].includes(value) ? "quality-warn" : value ? "quality-error" : "muted"; }
function batchStatusLabel(value) { return { PROCESSING: "处理中", ACCEPTED: "已接受", PARTIAL: "部分成功", FAILED: "失败", CONFLICT: "冲突", RECEIVED: "已接收" }[value] || value || "—"; }
function batchStatusTone(value) { return value === "ACCEPTED" ? "success" : value === "PROCESSING" ? "warning" : value === "PARTIAL" ? "warning" : "danger"; }
function messageStatusLabel(value) { return { RECEIVED: "已接收", PROCESSING: "处理中", ACCEPTED: "已接受", DUPLICATE: "重复", REJECTED: "已拒绝", FAILED: "待重试", DEAD_LETTER: "死信", CONFLICT: "冲突" }[value] || value || "—"; }
function messageStatusTone(value) { return { ACCEPTED: "success", PROCESSING: "warning", RECEIVED: "warning", DUPLICATE: "info", REJECTED: "danger", FAILED: "danger", DEAD_LETTER: "danger", CONFLICT: "danger" }[value] || "primary"; }

watch([activeTab, activeInboundTab], handleNavigationChange);
watch(() => [route.query.tab, route.query.inboundTab, route.query.focus, allowedTabs.value.join(","), allowedInboundTabs.value.join(",")], () => {
  activeTab.value = resolveDataManagementTab(route.query.tab, allowedTabs.value, route.query.focus);
  activeInboundTab.value = resolveInboundDataTab(route.query.tab, allowedInboundTabs.value, route.query.inboundTab);
});

onMounted(handleNavigationChange);
</script>

<style scoped>
.integration-page {
  min-height: calc(100vh - 84px);
  padding: var(--dashboard-page-padding);
  color: var(--el-text-color-primary);
}

.integration-explanation { margin: 0 0 16px; line-height: 1.7; }
.integration-policy { margin-bottom: 18px; }
.integration-policy .form-tip { margin-bottom: 12px; }
.inbound-tabs > :deep(.el-tabs__header) { margin-bottom: 16px; }
.retention-error { margin-bottom: 12px; color: var(--el-color-danger); }

.section-toolbar,
.key-toolbar,
.secret-row,
.dialog-actions,
.row-actions,
.batch-metrics,
.toolbar-actions,
.tab-label {
  display: flex;
  align-items: center;
}

.row-actions {
  gap: 4px;
  flex-wrap: wrap;
}

.security-alert {
  margin-bottom: 18px;
}

.integration-tabs > :deep(.el-tabs__header) {
  margin-bottom: 0;
}



.toolbar-actions {
  flex-wrap: wrap;
  gap: 8px;
}

.toolbar-actions > :deep(.el-button + .el-button) {
  margin-left: 0;
}

.tab-label {
  gap: 6px;
}

.panel-section {
  min-height: 480px;
  padding: 0;
}

.section-toolbar {
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 16px;
}

.section-toolbar :deep(.el-form-item) {
  margin-bottom: 0;
}

.record-count {
  flex: 0 0 auto;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.identity-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}

.identity-cell strong,
.identity-cell small,
.stack-cell > span,
.stack-cell small {
  display: block;
}

.identity-cell small,
.stack-cell small,
.form-tip,
.source-code {
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.identity-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: 10px;
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.inbound-mark {
  color: var(--el-color-success);
  background: var(--el-color-success-light-9);
}

.outbound-mark {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.metric-good,
.quality-success {
  color: var(--el-color-success);
}

.metric-warn,
.quality-warn {
  color: var(--el-color-warning);
}

.quality-error {
  color: var(--el-color-danger);
}

.muted {
  color: var(--el-text-color-secondary);
}

.batch-metrics {
  gap: 10px;
  flex-wrap: wrap;
  font-size: 12px;
}

.request-id,
.detail-json {
  font-family: var(--el-font-family-monospace, monospace);
  font-size: 12px;
}

.request-id {
  color: var(--el-color-primary);
  word-break: break-all;
}

.ellipsis-value {
  display: block;
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.editor-form :deep(.el-input-number),
.editor-form :deep(.el-select),
.editor-form :deep(.el-tree-select),
.editor-form :deep(.el-date-editor),
.key-create-form :deep(.el-select),
.key-create-form :deep(.el-date-editor) {
  width: 100%;
}

.form-grid {
  display: grid;
  gap: 0 16px;
}

.form-grid-2 { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.form-grid-3 { grid-template-columns: repeat(3, minmax(0, 1fr)); }
.form-grid-4 { grid-template-columns: repeat(4, minmax(0, 1fr)); }

.form-tip {
  line-height: 1.5;
}

.key-toolbar {
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.secret-alert {
  margin-bottom: 16px;
}

.secret-row {
  gap: 12px;
  margin-top: 6px;
}

.secret-row code {
  flex: 1;
  padding: 8px 10px;
  overflow-wrap: anywhere;
  color: var(--el-text-color-primary);
  background: var(--el-fill-color-light);
  border-radius: 4px;
}

.dialog-actions {
  justify-content: flex-end;
  margin: 0 0 16px;
}

.detail-json {
  max-height: 220px;
  margin: 0;
  padding: 10px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--el-text-color-primary);
  background: var(--el-fill-color-light);
  border-radius: 4px;
}

.test-summary-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.test-summary-grid > div {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
  padding: 12px;
  background: var(--el-fill-color-light);
  border-radius: 6px;
}

.test-summary-grid span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.test-summary-grid code {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 1100px) {
  .form-grid-4,
  .form-grid-3 { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .test-summary-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (max-width: 760px) {
  .section-toolbar { align-items: flex-start; flex-direction: column; }
  .section-toolbar { gap: 8px; }
  .section-toolbar :deep(.el-form) { width: 100%; }
  .form-grid-2,
  .form-grid-3,
  .form-grid-4,
  .test-summary-grid { grid-template-columns: 1fr; }
  .record-count { align-self: flex-end; }
}
</style>
