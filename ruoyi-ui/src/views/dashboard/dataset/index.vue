<template>
  <div class="dataset-page dashboard-management-tree-layout" :class="{ 'dataset-embedded': embedded, 'dashboard-management-page': !embedded }">
    <DataFolderSidebar
      v-model="query.folderId"
      v-model:include-children="query.includeChildren"
      scope="dataset"
      :folders="groups"
      :can-manage="canEditDatasets"
      @change="handleDatasetFilter"
      @refresh="refreshDatasetFilters"
      @manage="openGroups"
    />
    <div class="dashboard-management-main">
      <div class="content-inner">
    <ManagementToolbar
      title="数据集"
      :total="total"
      noun="个"
      :selection-count="selectedDatasetIds.length"
      :active-filter="activeDatasetFilterLabel"
      help-module="datasets"
      :loading="loading"
      :actions="canEditDatasets ? [{ key: 'sources', label: '管理数据源', icon: Link }] : []"
      @action="openSources"
      @search="handleDatasetFilter"
      @reset="resetQuery"
      @refresh="refresh"
      @clear-filter="clearDatasetFilter"
    >
      <template #filters>
        <el-form-item label="搜索" class="filter-wide"
          ><el-input
            v-model="query.keyword"
            placeholder="编码或名称"
            clearable
            ><template #prefix
              ><el-icon><Search /></el-icon></template></el-input
        ></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="query.dataType" clearable placeholder="全部类型" @change="handleDatasetFilter">
            <el-option v-for="item in types" :key="item.value" :label="typeLabel(item.value)" :value="item.value" />
          </el-select>
        </el-form-item>
      </template>
      <template #selection><el-button v-if="canEditDatasets" plain :icon="FolderOpened" aria-label="移动到文件夹" @click="moveDialogOpen = true">移动</el-button></template>
      <template #primary><DataManagementCreateButton kind="dataset" @click="openCreate" /></template>
    </ManagementToolbar>

    <section class="dataset-table-wrap dashboard-management-table">
      <el-table
        ref="datasetTableRef"
        v-loading="loading"
        :data="rows"
        row-key="datasetId"
        class="dataset-table"
        @selection-change="selectedDatasetIds = $event.map(row => row.datasetId)"
      >
        <el-table-column v-if="canEditDatasets" type="selection" width="48" />
        <el-table-column label="数据集" min-width="270"
          ><template #default="scope"
            ><div class="dataset-name-cell">
              <span class="type-mark" :class="typeTone(scope.row.dataType)"
                ><el-icon
                  ><component :is="typeIcon(scope.row.dataType)" /></el-icon
              ></span>
              <div>
                <strong>{{ scope.row.datasetName }}</strong
                ><small>{{ scope.row.datasetCode }}</small>
              </div>
            </div></template
          ></el-table-column
        >
        <el-table-column label="文件夹" min-width="130"
          ><template #default="scope"
            ><span class="group-name">{{
              groupName(scope.row.groupCode) || "未分类"
            }}</span></template
          ></el-table-column
        >
        <el-table-column prop="dataType" label="类型" min-width="160"
          ><template #default="scope"
            ><el-tag
              effect="plain"
              :class="`tag-${String(scope.row.dataType).toLowerCase()}`"
              >{{ typeLabel(scope.row.dataType) }}</el-tag
            ></template
          ></el-table-column
        >
        <el-table-column prop="status" label="状态" width="105"
          ><template #default="scope"
            ><span class="status-text"
              ><i :class="`dot-${String(scope.row.status).toLowerCase()}`"></i
              >{{ statusLabel(scope.row.status) }}</span
            ></template
          ></el-table-column
        >
        <el-table-column label="最近测试" min-width="180"
          ><template #default="scope"
            ><div
              class="test-state"
              :class="testTone(scope.row.lastTestStatus)"
            >
              <span>{{ qualityLabel(scope.row.lastTestStatus) }}</span
              ><small>{{
                formatDashboardDateTime(scope.row.lastTestAt, "尚未测试")
              }}</small>
            </div></template
          ></el-table-column
        >
        <el-table-column label="更新时间" width="180"
          ><template #default="scope">{{
            formatDashboardDateTime(scope.row.updateTime)
          }}</template></el-table-column
        >
        <el-table-column label="操作" width="210" fixed="right"
          ><template #default="scope"
            ><el-button
              v-hasPermi="['dashboard:dataset:edit']"
              link
              type="primary"
              @click="edit(scope.row)"
              >编辑</el-button
            ><el-button
              v-hasPermi="['dashboard:dataset:test']"
              link
              type="success"
              @click="test(scope.row)"
              >测试</el-button
            ><el-button
              v-hasPermi="['dashboard:dataset:edit']"
              link
              type="danger"
              @click="remove(scope.row)"
              >删除</el-button
            ></template
          ></el-table-column
        >
      <template #empty><DataManagementEmptyState v-if="!loading" kind="dataset" description="暂无符合条件的数据集" /></template>
      </el-table>
      <pagination
        v-show="total > 0"
        v-model:page="query.pageNum"
        v-model:limit="query.pageSize"
        :total="total"
        @pagination="load"
      />
    </section>
      </div>
    </div>

    <DataFolderManager
      v-model="groupDialogOpen"
      scope="dataset"
      :folders="groups"
      :default-parent-id="query.folderId > 0 ? query.folderId : 0"
      @changed="refreshDatasetFilters"
    />
    <DataFolderMoveDialog
      v-model="moveDialogOpen"
      scope="dataset"
      :folders="groups"
      :ids="selectedDatasetIds"
      :default-folder-id="query.folderId > 0 ? query.folderId : 0"
      @moved="load"
    />

    <el-dialog
      v-model="dialog.open"
      :title="dialog.editing ? '编辑数据集' : '新增数据集'"
      width="980px"
      top="4vh"
      class="dataset-dialog"
      destroy-on-close
    >
      <el-form
        ref="formRef"
        :model="dialog.form"
        :rules="rules"
        label-position="top"
        class="dataset-form"
      >
        <div class="form-grid identity-grid">
          <el-form-item label="数据集编码" prop="datasetCode"
            ><el-input
              v-model="dialog.form.datasetCode"
              :disabled="dialog.editing"
              placeholder="例如 attendance-events" /></el-form-item
          ><el-form-item label="数据集名称" prop="datasetName"
            ><el-input
              v-model="dialog.form.datasetName"
              placeholder="便于设计器识别的名称" /></el-form-item
          ><el-form-item label="数据类型" prop="dataType"
            ><el-select v-model="dialog.form.dataType" @change="changeType"
              ><el-option
                v-for="item in types"
                :key="item.value"
                :label="item.label"
                :value="item.value" /></el-select></el-form-item
          ><el-form-item label="文件夹"
            ><el-tree-select
              v-model="dialog.form.groupCode"
              :data="datasetFolderOptions"
              :empty-values="[null, undefined]"
              check-strictly
              :render-after-expand="false"
              default-expand-all
              filterable
              placeholder="未分类"
              style="width: 100%"
            /></el-form-item
          ><el-form-item label="状态"
            ><el-select v-model="dialog.form.status"
              ><el-option label="草稿" value="DRAFT" /><el-option
                label="启用"
                value="ACTIVE"
                :disabled="!canActivateDataset" /><el-option
                label="停用"
                value="DISABLED" /></el-select
          ></el-form-item>
        </div>
        <div class="form-section source-section">
          <div class="form-section-head">
            <div>
              <span class="section-eyebrow">来源配置</span>
              <h3>来源配置</h3>
            </div>
            <el-tag
              effect="plain"
              :class="`tag-${String(dialog.form.dataType).toLowerCase()}`"
              >{{ typeLabel(dialog.form.dataType) }}</el-tag
            >
          </div>
          <div v-if="dialog.form.dataType === 'SQL'" class="source-grid">
            <el-form-item label="只读数据源编码" prop="sourceRef"
              ><el-select
                v-model="editor.sourceRef"
                filterable
                allow-create
                default-first-option
                clearable
                placeholder="选择服务端登记的数据源"
                ><el-option
                  v-for="source in sourceDialog.rows.filter(
                    (item) => String(item.type || '').toUpperCase() === 'MYSQL',
                  )"
                  :key="source.code"
                  :label="`${source.name} · ${source.code}`"
                  :value="source.code" /></el-select></el-form-item
            ><el-form-item label="查询限制"
              ><div class="inline-fields">
                <el-input-number
                  v-model="dialog.form.timeoutSeconds"
                  :min="1"
                  :max="3600"
                  controls-position="right"
                /><span>秒超时</span
                ><el-input-number
                  v-model="dialog.form.rowLimit"
                  :min="1"
                  :max="dialog.form.dataType === 'API' ? 1000 : 10000"
                  controls-position="right"
                /><span>行上限</span>
              </div></el-form-item
            ><el-form-item label="查询语句（SQL）" class="full-field"
              ><el-input
                v-model="editor.sql"
                type="textarea"
                :rows="8"
                spellcheck="false"
                placeholder="只允许 SELECT / WITH；支持 :param 或 ${param} 安全绑定变量"
              />
              <p class="field-hint">
                `${param}` 会在服务端转换为预编译参数；例如 LIKE '%${name}%'
                不会执行字符串拼接。
              </p></el-form-item
            >
          </div>
          <div v-else-if="dialog.form.dataType === 'API'" class="source-grid">
            <el-form-item label="接口数据源（HTTP）"
              ><el-select
                v-model="editor.sourceCode"
                filterable
                clearable
                placeholder="选择已登记的 HTTP 数据源"
                @change="handleApiSourceChange"
                ><el-option
                  v-for="source in sourceDialog.rows.filter(
                    (item) => String(item.type || '').toUpperCase() === 'HTTP',
                  )"
                  :key="source.code"
                  :label="`${source.name} · ${source.code}`"
                  :value="source.code" /></el-select></el-form-item
            ><el-form-item label="已登记取数接口"
              ><el-select
                v-model="editor.endpointCode"
                filterable
                clearable
                :disabled="!editor.sourceCode"
                :loading="apiEndpointLoading"
                placeholder="选择来源下的取数接口"
                ><el-option
                  v-for="endpoint in apiEndpointRows"
                  :key="endpoint.endpointCode"
                  :label="`${endpoint.endpointName} · ${endpoint.endpointCode}`"
                  :value="endpoint.endpointCode" /></el-select></el-form-item
            ><el-form-item label="请求方法"
              ><el-input
                :model-value="selectedApiEndpoint?.method || '由接口契约决定'"
                disabled /></el-form-item
            ><el-form-item label="响应行路径"
              ><el-input
                v-model="editor.rowsPath"
                placeholder="例如 data.rows，留空表示数组" /></el-form-item
            ><el-form-item label="总数路径"
              ><el-input
                v-model="editor.totalPath"
                placeholder="可选，例如 data.total" /></el-form-item
            ><el-form-item label="媒体引用投影 JSON（可选）" class="full-field">
              <el-input
                v-model="editor.mediaProjectionsText"
                type="textarea"
                :rows="5"
                spellcheck="false"
                placeholder='[{"outputField":"pictureRef","endpointCode":"event-picture","businessKeyPath":"id","formFieldMappings":{"picUri":"picUri","serverIndexCode":"serverIndexCode"}}]'
              />
              <p class="field-hint">平台从同一条来源记录提取业务主键和媒体参数，只向浏览器返回不透明候选引用；原始路径和服务器编码不会进入页面。</p>
            </el-form-item
            ><el-alert
              class="full-field"
              type="info"
              :closable="false"
              show-icon
              title="页面只能引用同一 HTTP 数据源下已登记的取数接口；方法、地址、凭证、请求头和超时由服务端契约决定。"
            />
          </div>
          <div v-else-if="dialog.form.dataType === 'JSON'" class="source-grid">
            <el-form-item label="数据模式（JSON）"
              ><el-radio-group v-model="editor.mode"
                ><el-radio-button label="STATIC">静态内容（JSON）</el-radio-button
                ><el-radio-button label="URL"
                  >受控数据地址（JSON）</el-radio-button
                ></el-radio-group
              ></el-form-item
            ><el-form-item
              v-if="editor.mode === 'URL'"
              label="服务端 JSON 来源编码"
              ><el-input
                v-model="editor.urlRef"
                placeholder="已登记的 JSON 来源编码" /></el-form-item
            ><el-form-item v-else label="最大行数"
              ><el-input-number
                v-model="dialog.form.rowLimit"
                :min="1"
                :max="10000"
                controls-position="right" /></el-form-item
            ><el-form-item label="数据路径"
              ><el-input
                v-model="editor.rowsPath"
                placeholder="留空表示 payload 本身" /></el-form-item
            ><el-form-item v-if="editor.mode === 'STATIC'" label="数据内容（JSON）" class="full-field"
              ><el-input
                v-model="editor.payloadText"
                type="textarea"
                :rows="8"
                spellcheck="false"
                placeholder='例如 [{"name":"一号区域","value":18}]'
              />
              <div class="inline-fields">
                <el-button @click="formatJsonPayload">校验并格式化</el-button>
                <span>支持对象或数组；数据路径需指向存在的对象或数组。</span>
              </div>
            </el-form-item>
          </div>
          <div v-else class="source-grid">
            <el-form-item label="服务端实时来源编码" prop="endpointCode"
              ><el-select
                v-if="editor.sourceType === 'WEBSOCKET'"
                v-model="editor.endpointCode"
                filterable
                clearable
                placeholder="选择 WebSocket 数据源"
                ><el-option
                  v-for="source in sourceDialog.rows.filter(
                    (item) =>
                      String(item.type || '').toUpperCase() === 'WEBSOCKET',
                  )"
                  :key="source.code"
                  :label="`${source.name} · ${source.code}`"
                  :value="source.code" /></el-select
              ><el-input
                v-else
                v-model="editor.endpointCode"
                placeholder="例如 business.events" /></el-form-item
            ><el-form-item label="来源模式"
              ><el-select v-model="editor.sourceType"
                ><el-option
                  label="原生实时连接（WebSocket）"
                  value="WEBSOCKET" /><el-option
                  label="接口轮询桥接（API）"
                  value="API" /><el-option
                  label="查询轮询桥接（SQL）"
                  value="SQL" /><el-option
                  label="数据轮询桥接（JSON）"
                  value="JSON" /></el-select></el-form-item
            ><el-form-item label="订阅频道"
              ><el-input
                v-model="editor.channel"
                placeholder="由服务端模板决定" /></el-form-item
            ><el-form-item label="事件数据路径"
              ><el-input
                v-model="editor.rowsPath"
                placeholder="例如 data.rows" /></el-form-item
            ><el-form-item label="连接参数"
              ><div class="inline-fields">
                <el-input-number
                  v-model="editor.heartbeatSeconds"
                  :min="5"
                  :max="300"
                  controls-position="right"
                /><span>秒心跳</span
                ><el-input-number
                  v-model="editor.reconnectLimit"
                  :min="0"
                  :max="20"
                  controls-position="right"
                /><span>次重连</span>
              </div></el-form-item
            ><el-alert
              class="full-field"
              type="info"
              :closable="false"
              show-icon
              title="浏览器只连接平台 WebSocket 入口，外部地址、访问令牌、订阅报文由服务端登记并代理；选择原生 WebSocket 时 endpointCode 必须是 WebSocket 数据源编码。"
            />
          </div>
        </div>

        <div class="form-section">
          <div class="form-section-head">
            <div>
              <span class="section-eyebrow">字段定义</span>
              <h3>字段定义</h3>
            </div>
            <el-button text type="primary" @click="addField"
              ><el-icon><Plus /></el-icon>添加字段</el-button
            >
          </div>
          <p class="form-hint">
            字段定义用于设计器映射、排序、显示和统计；首次测试成功后服务端会自动补齐缺失字段。需要展示
            使用系统字典标签时，可填写字典编码。
          </p>
          <div v-if="!dialog.fields.length" class="inline-empty">
            尚未声明字段，测试成功后会自动解析，也可以手工添加。
          </div>
          <div
            v-for="(field, index) in dialog.fields"
            :key="field.id || index"
            class="schema-row field-schema-row"
          >
            <el-input v-model="field.name" placeholder="字段名" /><el-input
              v-model="field.title"
              placeholder="显示标题"
            /><el-input
              v-model="field.sourcePath"
              placeholder="来源路径（默认同名）"
            /><el-select v-model="field.type" placeholder="类型"
              ><el-option label="字符串" value="string" /><el-option
                label="数字"
                value="number" /><el-option
                label="小数"
                value="decimal" /><el-option
                label="整数"
                value="integer" /><el-option
                label="枚举"
                value="enum" /><el-option
                label="结构化报文（JSON）"
                value="json" /><el-option
                label="日期时间"
                value="datetime" /><el-option
                label="日期"
                value="date" /><el-option
                label="布尔"
                value="boolean" /></el-select
            ><el-checkbox v-model="field.show">显示</el-checkbox
            ><el-checkbox v-model="field.sortable">可排序</el-checkbox
            ><el-select v-model="field.aggregate" placeholder="统计"
              ><el-option label="无" value="none" /><el-option
                label="求和"
                value="sum" /><el-option label="平均" value="avg" /><el-option
                label="最大"
                value="max" /><el-option label="最小" value="min" /></el-select
            ><el-select v-model="field.mask" placeholder="脱敏"
              ><el-option label="不脱敏" value="" /><el-option
                label="手机号"
                value="PHONE" /><el-option
                label="邮箱"
                value="EMAIL" /><el-option
                label="部分隐藏"
                value="PARTIAL" /></el-select
            ><el-input
              v-model="field.dictCode"
              placeholder="字典编码"
            /><el-button
              text
              type="danger"
              @click="dialog.fields.splice(index, 1)"
              ><el-icon><Delete /></el-icon
            ></el-button>
          </div>
        </div>

        <div class="form-section">
          <div class="form-section-head">
            <div>
              <span class="section-eyebrow">参数定义</span>
              <h3>参数定义</h3>
            </div>
            <el-button text type="primary" @click="addParam"
              ><el-icon><Plus /></el-icon>添加参数</el-button
            >
          </div>
          <p class="form-hint">
            参数只能传给服务端声明的 SQL/API/WebSocket
            来源；服务端会做类型、必填和白名单校验，不能用于拼接 URL 或脚本。
          </p>
          <div v-if="!dialog.params.length" class="inline-empty">
            暂无参数。需要时间范围或组织过滤时在这里声明。
          </div>
          <div
            v-for="(param, index) in dialog.params"
            :key="param.id || index"
            class="schema-row param-schema-row"
          >
            <el-input v-model="param.name" placeholder="参数名" /><el-input
              v-model="param.title"
              placeholder="显示标题"
            /><el-select v-model="param.type" placeholder="类型"
              ><el-option label="字符串" value="STRING" /><el-option
                label="日期"
                value="DATE" /><el-option
                label="日期时间"
                value="DATETIME" /><el-option
                label="数字"
                value="NUMBER" /><el-option label="小数" value="DECIMAL" /><el-option
                label="整数"
                value="INTEGER" /><el-option label="枚举" value="ENUM" /><el-option
                label="布尔"
                value="BOOLEAN" /></el-select
            ><el-select v-model="param.in" placeholder="参数位置"
              ><el-option label="查询参数" value="query" /><el-option
                label="路径参数"
                value="path" /><el-option
                label="请求头"
                value="header" /><el-option
                label="结构化请求体（JSON）"
                value="json" /><el-option
                label="表单字段"
                value="form" /></el-select
            ><el-checkbox v-model="param.required">必填</el-checkbox
            ><el-input v-model="param.default" placeholder="默认值" /><el-input
              v-if="param.in === 'header'"
              v-model="param.header"
              placeholder="请求头名称"
            /><el-button
              text
              type="danger"
              @click="dialog.params.splice(index, 1)"
              ><el-icon><Delete /></el-icon
            ></el-button>
          </div>
        </div>

        <el-collapse class="advanced-config"
          ><el-collapse-item
            title="高级配置 JSON（仅用于保留兼容字段）"
            name="advanced"
            ><el-input
              v-model="dialog.form.configJson"
              type="textarea"
              :rows="6"
              spellcheck="false"
            />
            <p class="form-hint">
              保存时会根据上方可视化配置生成
              JSON。不要在这里添加函数、脚本、任意外部地址或凭证。
            </p></el-collapse-item
          ></el-collapse
        >
        <div class="form-grid limits-grid">
          <el-form-item label="刷新间隔（秒）"
            ><el-input-number
              v-model="dialog.form.refreshSeconds"
              :min="5"
              :max="3600"
              controls-position="right" /></el-form-item
          ><el-form-item label="备注"
            ><el-input
              v-model="dialog.form.remark"
              placeholder="数据来源和使用说明"
          /></el-form-item>
        </div>
      </el-form>
      <template #footer
        ><el-button @click="dialog.open = false">取消</el-button
        ><el-button :loading="dialog.saving" @click="submit(false)"
          >保存数据集</el-button
        ><el-button
          type="primary"
          :loading="dialog.saving"
          @click="submit(true)"
          >保存并解析字段</el-button
        ></template
      >
    </el-dialog>

    <el-dialog
      v-model="testDialog.open"
      title="数据集测试"
      width="900px"
      class="test-dialog"
      ><div v-if="testDialog.params.length" class="test-params">
        <div class="test-params-title">测试参数</div>
        <div class="test-param-grid">
          <el-form-item
            v-for="param in testDialog.params"
            :key="param.name"
            :label="param.title || param.name"
            :required="param.required"
            ><el-input
              v-model="param.value"
              :placeholder="
                param.default == null ? '请输入参数' : `默认：${param.default}`
              "
          /></el-form-item>
        </div>
        <el-button type="primary" :loading="testDialog.running" @click="runTest"
          >执行测试</el-button
        >
      </div>
      <div v-if="testDialog.result" class="test-result">
        <div class="test-summary">
          <div>
            <span class="summary-label">状态</span
            ><strong :class="testTone(testDialog.result.quality)">{{
              qualityLabel(testDialog.result.quality)
            }}</strong>
          </div>
          <div>
            <span class="summary-label">请求编号（ID）</span
            ><code>{{ testDialog.result.requestId || "-" }}</code>
          </div>
          <div>
            <span class="summary-label">抓取时间</span
            ><span>{{
              formatDashboardDateTime(testDialog.result.fetchedAt)
            }}</span>
          </div>
          <div>
            <span class="summary-label">数据行</span
            ><span>{{ testDialog.result.rows?.length || 0 }}</span>
          </div>
        </div>
        <el-alert
          v-if="testDialog.result.fieldsUpdated"
          type="success"
          :closable="false"
          title="已根据测试结果自动补齐字段定义；后续可在数据集编辑中调整标题、显示和统计属性。"
        /><el-alert
          v-else-if="testDialog.result.inferredFields?.length"
          type="info"
          :closable="false"
          title="测试结果已解析出字段，当前字段定义未发生变化。"
        /><el-alert
          v-if="testDialog.result.message"
          :type="
            testDialog.result.quality === 'SUCCESS' ? 'success' : 'warning'
          "
          :closable="false"
          :title="testDialog.result.message"
        /><el-table
          v-if="testDialog.result.rows?.length"
          :data="testDialog.result.rows.slice(0, 50)"
          max-height="380"
          ><el-table-column
            v-for="column in testResultColumns"
            :key="column.name"
            :prop="column.name"
            :label="column.title || column.name"
            min-width="140"
            show-overflow-tooltip
            ><template #default="scope">{{
              formatTestResultValue(scope.row[column.name], column)
            }}</template></el-table-column
        ></el-table>
        <pre v-else class="result-json">{{
          formatTestResultJson(testDialog.result)
        }}</pre>
      </div>
      <el-empty v-else description="暂无测试结果"
    /></el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import DataManagementCreateButton from "@/components/DashboardDataManagement/CreateButton.vue";
import ManagementToolbar from "@/components/DashboardManagement/ManagementToolbar.vue";
import DataManagementEmptyState from "@/components/DashboardDataManagement/EmptyState.vue";
import DataFolderSidebar from "@/components/DashboardDataManagement/DataFolderSidebar.vue";
import DataFolderManager from "@/components/DashboardDataManagement/DataFolderManager.vue";
import DataFolderMoveDialog from "@/components/DashboardDataManagement/DataFolderMoveDialog.vue";
import { listDataFolders } from "@/api/dashboardDataFolder";
import { buildDataFolderOptions, normalizeDataFolders, dataFolderName } from "@/utils/dashboardDataFolder";
import { checkPermi } from "@/utils/permission";
import {
  DataBoard,
  DataLine,
  Delete,
  FolderOpened,
  Link,
  Plus,
  Search,
  TrendCharts,
} from "@element-plus/icons-vue";
import {
  addDashboardDataset,
  delDashboardDataset,
  getDashboardDataset,
  listDashboardDataSources,
  listDashboardDatasets,
  testDashboardDataset,
  updateDashboardDataset,
} from "@/api/dashboard";
import { listDashboardIntegrationEndpoints } from "@/api/dashboardIntegration";
import {
  formatDashboardDateTime,
  formatDashboardFieldValue,
} from "@/utils/dashboard";
import { buildDashboardJsonDatasetConfig, validateDashboardStaticJson } from "@/utils/dashboardJsonDataset";

const props = defineProps({ embedded: Boolean, active: { type: Boolean, default: true } });
const emit = defineEmits(["manage-sources"]);

const types = [
  { value: "SQL", label: "查询数据集（SQL）", icon: Link, tone: "teal" },
  { value: "API", label: "接口数据集（API）", icon: Link, tone: "blue" },
  { value: "JSON", label: "结构化数据集（JSON）", icon: DataBoard, tone: "violet" },
  {
    value: "WEBSOCKET",
    label: "实时数据集（WebSocket）",
    icon: TrendCharts,
    tone: "green",
  },
];
const typeMap = Object.fromEntries(types.map((item) => [item.value, item]));
const route = useRoute();
const router = useRouter();
const loading = ref(false);
let datasetRequestId = 0;
const rows = ref([]);
const total = ref(0);
const query = reactive({
  pageNum: 1,
  pageSize: 10,
  keyword: "",
  dataType: "",
  folderId: null,
  includeChildren: false,
});
const formRef = ref();
const dialog = reactive({
  open: false,
  editing: false,
  saving: false,
  legacyApi: false,
  form: {},
  fields: [],
  params: [],
});
const editor = reactive({
  sourceRef: "",
  sourceCode: "",
  sql: "",
  endpointCode: "",
  sourceType: "API",
  method: "GET",
  rowsPath: "",
  totalPath: "",
  mode: "STATIC",
  urlRef: "",
  payloadText: "[]",
  channel: "",
  heartbeatSeconds: 30,
  reconnectLimit: 5,
});
const testDialog = reactive({
  open: false,
  result: null,
  row: null,
  params: [],
  running: false,
});
const sourceDialog = reactive({
  open: false,
  loading: false,
  testing: "",
  rows: [],
});
const apiEndpointRows = ref([]);
const apiEndpointLoading = ref(false);
const groups = ref([]);
const groupDialogOpen = ref(false);
const moveDialogOpen = ref(false);
const datasetTableRef = ref();
const selectedDatasetIds = ref([]);
const canEditDatasets = computed(() => checkPermi(["dashboard:dataset:edit"]));
const datasetFolderOptions = computed(() => buildDataFolderOptions(groups.value, { valueKey: "groupCode" }));
const rules = {
  datasetCode: [{ required: true, message: "编码不能为空", trigger: "blur" }],
  datasetName: [{ required: true, message: "名称不能为空", trigger: "blur" }],
  dataType: [{ required: true, message: "请选择类型", trigger: "change" }],
  sourceCode: [{ required: true, message: "请选择 HTTP 数据源", trigger: "change" }],
};
const selectedApiEndpoint = computed(() =>
  apiEndpointRows.value.find((item) => item.endpointCode === editor.endpointCode),
);
const canActivateDataset = computed(
  () => dialog.editing && ["SUCCESS", "NO_DATA"].includes(dialog.form.lastTestStatus),
);

const activeDatasetFilterLabel = computed(() => {
  const labels = [];
  if (query.folderId != null) labels.push(dataFolderName(groups.value, query.folderId));
  if (query.dataType) labels.push(typeLabel(query.dataType));
  return labels.join(" · ");
});

const testResultColumns = computed(() => {
  const firstRow = testDialog.result?.rows?.[0];
  if (!firstRow || typeof firstRow !== "object" || Array.isArray(firstRow))
    return [];
  const definitions = [
    ...parseJsonArray(testDialog.result?.fieldSchema),
    ...parseJsonArray(testDialog.result?.fieldSchemaJson),
    ...parseJsonArray(testDialog.row?.fieldSchemaJson),
    ...parseJsonArray(testDialog.result?.inferredFields),
  ];
  const fields = new Map();
  definitions.forEach((field) => {
    if (field?.name && !fields.has(field.name)) fields.set(field.name, field);
  });
  return Object.keys(firstRow).map((name) => ({
    ...(fields.get(name) || {}),
    name,
    title: fields.get(name)?.title || name,
  }));
});

const currentType = computed(() => typeMap[dialog.form.dataType] || types[0]);
function typeLabel(value) {
  return { SQL: "查询（SQL）", API: "接口（API）", JSON: "数据（JSON）", WEBSOCKET: "实时（WebSocket）" }[value] || "未知类型";
}
function typeTone(value) {
  return typeMap[value]?.tone || "slate";
}
function typeIcon(value) {
  return typeMap[value]?.icon || DataLine;
}
function groupName(code) {
  return groups.value.find((item) => item.groupCode === code)?.folderName || "";
}
function statusLabel(value) {
  return { DRAFT: "草稿", ACTIVE: "启用", DISABLED: "停用" }[value] || "未知";
}
function qualityLabel(value) {
  return (
    {
      SUCCESS: "测试成功",
      NO_DATA: "无数据",
      NOT_CONNECTED: "未接入",
      INVALID_DATA: "数据异常",
      TIMEOUT: "超时",
      AUTH_ERROR: "鉴权失败",
      RATE_LIMITED: "请求受限",
      SOURCE_ERROR: "来源失败",
      CONNECT_ERROR: "连接失败",
      DISABLED: "已停用",
    }[value] || "未测试"
  );
}
function testTone(value) {
  return ["SUCCESS"].includes(value)
    ? "success"
    : ["NO_DATA", "NOT_CONNECTED"].includes(value)
      ? "muted"
      : "danger";
}
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
function parseJsonArray(value) {
  const result = parseJson(value, []);
  return Array.isArray(result) ? result : [];
}
function formatTestResultValue(value, field = {}) {
  if (value === "") return "";
  const displayValue = formatDashboardFieldValue(value, field, "-");
  return typeof displayValue === "object"
    ? JSON.stringify(displayValue)
    : String(displayValue);
}
function formatTestResultJson(value) {
  if (!value) return "";
  return JSON.stringify(
    value,
    (key, item) => {
      if (typeof item !== "string" && !(item instanceof Date)) return item;
      const keyType = /(?:date)$/i.test(key)
        ? { type: "date" }
        : /(?:time|at|timestamp)$/i.test(key)
          ? { type: "datetime" }
          : {};
      return formatDashboardFieldValue(item, keyType, item);
    },
    2,
  );
}
function load() {
  const requestId = ++datasetRequestId;
  const params = { ...query };
  loading.value = true;
  selectedDatasetIds.value = [];
  datasetTableRef.value?.clearSelection();
  return listDashboardDatasets(params)
    .then((res) => {
      if (requestId !== datasetRequestId) return;
      rows.value = res.rows || [];
      total.value = res.total || 0;
    })
    .finally(() => {
      if (requestId === datasetRequestId) loading.value = false;
    });
}
function loadGroups() {
  return listDataFolders("dataset").then((res) => {
    groups.value = normalizeDataFolders(res.data || res.rows || []);
    if (query.folderId > 0 && !groups.value.some(folder => folder.folderId === Number(query.folderId))) {
      query.folderId = null;
      query.pageNum = 1;
    }
  });
}
async function refreshDatasetFilters() {
  await loadGroups();
  return load();
}
function handleDatasetFilter() {
  query.pageNum = 1;
  return load();
}
function clearDatasetFilter() {
  query.dataType = "";
  query.folderId = null;
  query.includeChildren = false;
  return handleDatasetFilter();
}
function resetQuery() {
  query.keyword = "";
  clearDatasetFilter();
}
function emptyForm() {
  return {
    datasetCode: "",
    datasetName: "",
    groupCode: "",
    dataType: "SQL",
    status: "DRAFT",
    configJson: "{}",
    fieldSchemaJson: "[]",
    paramSchemaJson: "[]",
    timeoutSeconds: 10,
    refreshSeconds: 30,
    rowLimit: 1000,
    remark: "",
  };
}
function openCreate() {
  dialog.editing = false;
  dialog.legacyApi = false;
  dialog.form = emptyForm();
  dialog.form.groupCode = groups.value.find(folder => folder.folderId === Number(query.folderId))?.groupCode || "";
  dialog.fields = [];
  dialog.params = [];
  Object.assign(editor, {
    sourceRef: "labor-readonly",
    sourceCode: "",
    sql: "SELECT 1 AS value",
    endpointCode: "",
    sourceType: "API",
    method: "GET",
    rowsPath: "",
    totalPath: "",
    mediaProjectionsText: "[]",
    mode: "STATIC",
    urlRef: "",
    payloadText: '[\n  { "name": "示例", "value": 18 }\n]',
    channel: "",
    heartbeatSeconds: 30,
    reconnectLimit: 5,
  });
  apiEndpointRows.value = [];
  dialog.open = true;
}
function edit(row) {
  dialog.editing = true;
  dialog.form = { ...row, groupCode: row.groupCode || "" };
  dialog.fields = parseJson(row.fieldSchemaJson, []).map((item) => ({
    id: `${Date.now()}-${Math.random()}`,
    ...item,
  }));
  dialog.params = parseJson(row.paramSchemaJson, []).map((item) => ({
    id: `${Date.now()}-${Math.random()}`,
    ...item,
  }));
  const config = parseJson(row.configJson, {});
  dialog.legacyApi =
    String(row.dataType || "").toUpperCase() === "API" &&
    !config.sourceCode &&
    Boolean(config.endpointCode || config.endpointRef);
  dialog.form.rowLimit = Number(config.rowLimit) || 1000;
  Object.assign(editor, {
    sourceRef: config.dataSourceCode || config.sourceRef || "",
    sourceCode: config.sourceCode || "",
    sql: config.sql || "",
    endpointCode: config.endpointCode || config.endpointRef || "",
    sourceType: config.sourceType || "API",
    method: config.method || "GET",
    rowsPath: config.rowsPath || config.response?.rowsPath || "",
    totalPath: config.totalPath || config.response?.totalPath || "",
    mediaProjectionsText: JSON.stringify(config.mediaProjections || [], null, 2),
    mode: String(config.mode || "STATIC").toUpperCase(),
    urlRef: config.urlRef || config.jsonSourceRef || "",
    payloadText: JSON.stringify(Object.hasOwn(config, "payload") ? config.payload : [], null, 2),
    channel: config.subscribe?.channel || config.channel || "",
    heartbeatSeconds: config.connection?.heartbeatSeconds || 30,
    reconnectLimit: config.connection?.reconnectLimit ?? 5,
  });
  if (dialog.form.dataType === "API") {
    apiEndpointRows.value = [];
    if (editor.sourceCode) loadApiEndpoints(editor.sourceCode);
  }
  dialog.open = true;
}
function changeType() {
  if (dialog.form.dataType === "SQL" && !editor.sql)
    editor.sql = "SELECT 1 AS value";
  if (dialog.form.dataType === "JSON" && !editor.payloadText)
    editor.payloadText = "[]";
  apiEndpointRows.value = [];
  if (dialog.form.dataType === "API" && editor.sourceCode)
    loadApiEndpoints(editor.sourceCode);
}

function handleApiSourceChange(sourceCode) {
  editor.endpointCode = "";
  apiEndpointRows.value = [];
  if (sourceCode) loadApiEndpoints(sourceCode);
}

function loadApiEndpoints(sourceCode = editor.sourceCode) {
  if (!sourceCode) return Promise.resolve();
  apiEndpointLoading.value = true;
  return listDashboardIntegrationEndpoints(sourceCode)
    .then((res) => {
      apiEndpointRows.value = res.data || res.rows || [];
    })
    .finally(() => {
      apiEndpointLoading.value = false;
    });
}
function addField() {
  dialog.fields.push({
    id: `${Date.now()}-${Math.random()}`,
    name: "",
    title: "",
    sourcePath: "",
    type: "string",
    show: true,
    sortable: false,
    aggregate: "none",
    mask: "",
    dictCode: "",
  });
}
function addParam() {
  dialog.params.push({
    id: `${Date.now()}-${Math.random()}`,
    name: "",
    title: "",
    type: "STRING",
    in: "query",
    header: "",
    required: false,
    default: "",
  });
}
function buildConfig() {
  if (dialog.form.dataType === "SQL")
    return { dataSourceCode: editor.sourceRef, sql: editor.sql };
  if (dialog.form.dataType === "API") {
    if (editor.sourceCode) {
      const mediaProjections = JSON.parse(editor.mediaProjectionsText || "[]");
      if (!Array.isArray(mediaProjections))
        throw new Error("媒体引用投影必须是 JSON 数组");
      const config = {
          sourceCode: editor.sourceCode,
          endpointCode: editor.endpointCode,
          response: {
            rowsPath: editor.rowsPath,
            totalPath: editor.totalPath,
          },
        };
      if (mediaProjections.length) config.mediaProjections = mediaProjections;
      return config;
    }
    return {
      endpointCode: editor.endpointCode,
      method: editor.method,
      response: { rowsPath: editor.rowsPath, totalPath: editor.totalPath },
    };
  }
  if (dialog.form.dataType === "JSON")
    return buildDashboardJsonDatasetConfig({ ...editor, configJson: dialog.form.configJson });
  return {
    mode: "POLL_BRIDGE",
    sourceType: editor.sourceType || "API",
    endpointCode: editor.endpointCode,
    dataSourceCode: editor.sourceRef,
    subscribe: { channel: editor.channel },
    message: { rowsPath: editor.rowsPath },
    connection: {
      heartbeatSeconds: editor.heartbeatSeconds,
      reconnectLimit: editor.reconnectLimit,
    },
  };
}
function formatJsonPayload() {
  try {
    const value = validateDashboardStaticJson(editor.payloadText, editor.rowsPath);
    editor.payloadText = JSON.stringify(value.payload, null, 2);
    editor.rowsPath = value.rowsPath;
    ElMessage.success("JSON 校验通过");
  } catch (error) {
    ElMessage.error(error.message);
  }
}
function submit(parseFields = false) {
  if (dialog.saving) return;
  return formRef.value.validate((valid) => {
    if (!valid) return;
    if (dialog.form.dataType === "SQL" && !editor.sql.trim())
      return ElMessage.warning("SQL 查询不能为空");
    if (
      dialog.form.dataType === "API" &&
      (!editor.endpointCode || (!editor.sourceCode && !dialog.legacyApi))
    )
      return ElMessage.warning("接口数据集（API）必须选择 接口数据源（HTTP）和取数接口");
    let configJson;
    try {
      const config = buildConfig();
      config.rowLimit = dialog.form.rowLimit;
      configJson = JSON.stringify(config);
      if (configJson.length > 512 * 1024) throw new Error("数据集配置不能超过 512 KB");
    } catch (error) {
      return ElMessage.error(error.message);
    }
    const payload = {
      ...dialog.form,
      configJson,
      fieldSchemaJson: JSON.stringify(
        dialog.fields.map(({ id, ...item }) => item),
      ),
      paramSchemaJson: JSON.stringify(
        dialog.params.map(({ id, ...item }) => item),
      ),
    };
    const editing = dialog.editing;
    const currentId = dialog.form.datasetId;
    dialog.saving = true;
    const action = editing
      ? updateDashboardDataset(payload)
      : addDashboardDataset(payload);
    return action
      .then(async (response) => {
        const saved = response?.data || response || {};
        const datasetId = currentId || saved.datasetId;
        ElMessage.success(
          parseFields ? "数据集已保存，正在解析字段" : "数据集已保存",
        );
        dialog.open = false;
        await load();
        if (parseFields && datasetId) {
          const refreshed = await getDashboardDataset(datasetId);
          test(refreshed?.data || refreshed);
        }
      })
      .finally(() => {
        dialog.saving = false;
      });
  });
}
function test(row) {
  testDialog.row = row;
  testDialog.result = null;
  testDialog.params = parseJson(row.paramSchemaJson, []).map((param) => ({
    ...param,
    value: param.default == null ? "" : String(param.default),
  }));
  testDialog.open = true;
  if (!testDialog.params.length) runTest();
}
function runTest() {
  if (!testDialog.row || testDialog.running) return;
  const params = Object.fromEntries(
    testDialog.params
      .filter(
        (param) =>
          param.value !== "" &&
          param.value !== null &&
          param.value !== undefined,
      )
      .map((param) => [param.name, param.value]),
  );
  testDialog.running = true;
  testDashboardDataset(testDialog.row.datasetId, params)
    .then((res) => {
      testDialog.result = res.data || res;
      load();
    })
    .finally(() => {
      testDialog.running = false;
    });
}
function openGroups() {
  if (!canEditDatasets.value) return;
  groupDialogOpen.value = true;
  loadGroups();
}
function openSources() {
  if (props.embedded) { emit("manage-sources"); return; }
  router.push({ path: "/dashboard/integration", query: { tab: "outbound" } });
}
function refreshSources() {
  sourceDialog.loading = true;
  return listDashboardDataSources()
    .then((res) => {
      sourceDialog.rows = res.data || res.rows || [];
    })
    .finally(() => {
      sourceDialog.loading = false;
    });
}
function remove(row) {
  ElMessageBox.confirm(`确定删除数据集“${row.datasetName}”吗？`, "删除数据集", {
    type: "warning",
  })
    .then(() =>
      delDashboardDataset(row.datasetId).then(() => {
        ElMessage.success("数据集已删除");
        load();
      }),
    )
    .catch(() => {});
}
function refresh() { return Promise.all([refreshDatasetFilters(), refreshSources()]); }
watch(() => props.active, active => { if (active) refresh().catch(() => {}); });
defineExpose({ refresh, openCreate });

onMounted(() => {
  refresh()
    .then(() => {
      if (route.query.focus === "source") openSources();
      else if (route.query.focus === "create") openCreate();
    })
    .catch(() => {});
});
</script>

<style scoped>
.dataset-page {
  min-height: calc(100vh - 84px);
  padding: var(--dashboard-page-padding);
  background: var(--el-bg-color-page, #f4f7fb);
  color: var(--el-text-color-primary, #1b2737);
}
.dataset-page.tree-sidebar-manage-wrap {
  padding: 0;
  background: var(--el-bg-color-page, #f4f7fb);
}
.dataset-page .tree-sidebar-content {
  background: var(--el-bg-color-page, #f4f7fb);
}
.dataset-page .tree-sidebar-content .content-inner {
  padding: var(--dashboard-page-padding);
}
.dataset-filter-summary {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.dataset-filter-summary :deep(.el-tag) {
  max-width: 150px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.dataset-page :deep(.tree-sidebar) {
  background: var(--el-bg-color, #fff);
  border-color: var(--el-border-color-light, #e8eaed);
}
.dataset-page :deep(.tree-header) {
  background: var(--el-bg-color, #fff);
  border-color: var(--el-border-color-light, #e8eaed);
}
.dataset-page :deep(.tree-title),
.dataset-page :deep(.tree-node) {
  color: var(--el-text-color-primary, #303133);
}
.dataset-page :deep(.node-label) {
  overflow: hidden;
  text-overflow: ellipsis;
}
.dataset-hero {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  margin: 2px 0 26px;
}
.hero-actions {
  display: flex;
  gap: 10px;
}
.eyebrow,
.section-eyebrow {
  color: #a1adbb;
  font-size: 10px;
  letter-spacing: 0.14em;
  font-weight: 700;
}
.dataset-hero h1 {
  margin: 7px 0 5px;
  font-size: 27px;
  letter-spacing: -0.03em;
}
.dataset-hero p {
  margin: 0;
  color: #8b97a6;
  font-size: 12px;
}
.dataset-toolbar,
.dataset-table-wrap {
  background: #fff;
  border: 1px solid #e5ebf2;
  border-radius: 10px;
}
.dataset-toolbar {
  padding: 17px 20px 5px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}
.dataset-toolbar :deep(.el-form-item) {
  margin-bottom: 12px;
}
.dataset-toolbar :deep(.el-form-item__label) {
  color: #7b8796;
  font-size: 11px;
}
.dataset-count {
  color: #9da8b5;
  font-size: 11px;
  align-self: center;
  margin-bottom: 12px;
}
.dataset-table-wrap {
  min-height: 390px;
  padding: 0 13px 10px;
}
.dataset-table {
  color: #4a586a;
}
.dataset-table :deep(th.el-table__cell) {
  height: 48px;
  color: #929dac;
  font-size: 11px;
  font-weight: 600;
  background: #fff;
}
.dataset-table :deep(td.el-table__cell) {
  height: 67px;
  border-bottom-color: #eff2f5;
}
.dataset-name-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}
.dataset-name-cell strong {
  display: block;
  color: #263448;
  font-size: 13px;
  font-weight: 650;
}
.dataset-name-cell small {
  display: block;
  color: #a2adba;
  font-size: 10px;
  margin-top: 4px;
}
.type-mark {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.type-mark.icon-teal,
.type-mark.teal {
  color: #0c9a85;
  background: #dcf8f0;
}
.type-mark.blue {
  color: #3679d5;
  background: #e6efff;
}
.type-mark.violet {
  color: #7e5bca;
  background: #efe9ff;
}
.type-mark.green {
  color: #269a5a;
  background: #e1f7e9;
}
.type-mark.slate {
  color: #6d7a8b;
  background: #edf1f5;
}
.dataset-table :deep(.el-tag) {
  border-radius: 5px;
  font-size: 10px;
}
.tag-sql {
  color: #0a927d;
  border-color: #a8e4d4;
  background: #effcf8;
}
.tag-api {
  color: #3977cd;
  border-color: #bdd6fa;
  background: #f2f7ff;
}
.tag-json {
  color: #7959c5;
  border-color: #d2c4f7;
  background: #f8f5ff;
}
.tag-websocket {
  color: #278d55;
  border-color: #bde8ca;
  background: #f1fcf4;
}
.status-text {
  color: #627185;
  font-size: 11px;
  display: flex;
  align-items: center;
  gap: 6px;
}
.status-text i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  display: inline-block;
}
.dot-active {
  background: #2bb791;
}
.dot-draft {
  background: #e2a23b;
}
.dot-disabled {
  background: #d87373;
}
.test-state {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 11px;
}
.test-state small {
  color: #a5afbb;
  font-size: 10px;
}
.test-state.success span {
  color: #22a57f;
}
.test-state.muted span {
  color: #a3813c;
}
.test-state.danger span {
  color: #d56969;
}
.table-empty {
  height: 320px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 7px;
  color: #9ba8b6;
  font-size: 12px;
}
.table-empty .el-icon {
  font-size: 30px;
  color: #b8c4d0;
}
.table-empty small {
  color: #b5bec9;
  font-size: 10px;
  margin-bottom: 8px;
}
.dataset-form {
  padding: 2px 5px 12px;
}
.form-grid {
  display: grid;
  gap: 14px;
}
.identity-grid {
  grid-template-columns: 1.2fr 1.5fr 1fr 1fr;
}
.dataset-form :deep(.el-form-item) {
  margin-bottom: 13px;
}
.dataset-form :deep(.el-form-item__label) {
  color: #788596;
  font-size: 11px;
  line-height: 20px;
  padding: 0 0 5px;
}
.form-section {
  border-top: 1px solid #eef1f4;
  padding-top: 18px;
  margin-top: 8px;
}
.source-section {
  background: #fbfcfe;
  border: 1px solid #e7ecf2;
  border-radius: 8px;
  padding: 16px 17px 6px;
}
.form-section-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 13px;
}
.form-section-head h3 {
  margin: 5px 0 0;
  color: #273447;
  font-size: 14px;
}
.source-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 18px;
}
.full-field {
  grid-column: 1 / -1;
}
.inline-fields {
  display: flex;
  align-items: center;
  gap: 7px;
  color: #8d99a7;
  font-size: 10px;
}
.inline-fields .el-input-number {
  width: 115px;
}
.form-hint {
  margin: -2px 0 12px;
  color: #9aa6b3;
  font-size: 10px;
}
.inline-empty {
  color: #a4afbc;
  border: 1px dashed #dbe2ea;
  padding: 12px;
  border-radius: 5px;
  font-size: 11px;
}
.schema-row {
  display: grid;
  grid-template-columns: 1.1fr 1.1fr 0.9fr 68px 68px 80px 68px 28px;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}
.param-schema-row {
  grid-template-columns: 1fr 1fr 0.9fr 1fr 55px 1fr 1fr 28px;
}
.field-schema-row :deep(.el-select) {
  min-width: 0;
}
.schema-row :deep(.el-input__wrapper),
.schema-row :deep(.el-select__wrapper) {
  min-height: 30px;
}
.advanced-config {
  margin-top: 19px;
  border-top: 1px solid #eef1f4;
  border-bottom: 0;
}
.advanced-config :deep(.el-collapse-item__header) {
  color: #7f8c9b;
  font-size: 11px;
}
.advanced-config :deep(.el-collapse-item__wrap) {
  border-bottom: 0;
}
.limits-grid {
  grid-template-columns: 180px 1fr;
  margin-top: 12px;
}
.test-params {
  background: #fbfcfe;
  border: 1px solid #e7edf3;
  border-radius: 7px;
  padding: 14px 16px;
  margin-bottom: 14px;
}
.test-param-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0 14px;
}
.test-params :deep(.el-form-item) {
  margin-bottom: 10px;
}
.test-params :deep(.el-form-item__label) {
  color: #7b8796;
  font-size: 10px;
}
.test-result {
  min-height: 220px;
}
.test-summary {
  display: grid;
  grid-template-columns: 1fr 1.5fr 1.4fr 0.7fr;
  gap: 14px;
  padding: 14px 16px;
  background: #f7fafc;
  border: 1px solid #e7edf3;
  border-radius: 7px;
  margin-bottom: 14px;
  font-size: 12px;
  color: #536276;
}
.test-summary > div {
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.summary-label {
  color: #99a5b2;
  font-size: 10px;
}
.test-summary strong.success {
  color: #1da27e;
}
.test-summary code {
  color: #66778a;
  font-size: 10px;
}
.result-json {
  background: #172332;
  color: #d8e7f3;
  padding: 16px;
  border-radius: 6px;
  max-height: 400px;
  overflow: auto;
  font-size: 11px;
}
.source-code {
  display: block;
  margin-top: 3px;
  color: #9ca9b6;
  font-size: 10px;
}
.source-alert {
  margin-bottom: 12px;
}
@media (max-width: 900px) {
  .dataset-page :deep(.tree-sidebar:not(.collapsed)) {
    min-width: 180px;
  }
  .dataset-page .tree-sidebar-content .content-inner {
    padding: 18px 12px;
  }
  .dataset-page {
    padding: 18px 12px;
  }
  .dataset-hero {
    align-items: flex-start;
    gap: 16px;
    flex-direction: column;
  }
  .identity-grid,
  .source-grid,
  .limits-grid,
  .test-param-grid {
    grid-template-columns: 1fr;
  }
  .schema-row,
  .param-schema-row {
    grid-template-columns: 1fr 1fr;
  }
  .test-summary {
    grid-template-columns: 1fr 1fr;
  }
  .dataset-toolbar {
    flex-direction: column;
    align-items: stretch;
  }
}
.field-schema-row {
  grid-template-columns: 1.1fr 1.1fr 1.2fr 0.9fr 68px 68px 80px 90px 1fr 28px;
}
.source-dialog-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 0 0 12px;
  color: #8996a5;
  font-size: 11px;
}
.source-editor-form {
  padding: 6px 8px 12px;
}
.source-edit-alert {
  margin-bottom: 12px;
}
.source-editor-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 18px;
}
.source-editor-form :deep(.el-form-item) {
  margin-bottom: 15px;
}
.source-editor-form :deep(.el-form-item__label) {
  color: #788596;
  font-size: 11px;
  line-height: 20px;
  padding-bottom: 5px;
}
.source-editor-form :deep(.el-alert) {
  margin-top: 5px;
}
.source-editor-form :deep(.el-input__wrapper),
.source-editor-form :deep(.el-select__wrapper) {
  min-height: 32px;
}
.source-editor-form :deep(.el-input-number),
.source-editor-form :deep(.el-select) {
  width: 100%;
}
.group-name {
  display: inline-flex;
  align-items: center;
  min-height: 24px;
  padding: 0 8px;
  border-radius: 999px;
  color: #5f7186;
  background: #f2f5f8;
  font-size: 10px;
}
.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-bottom: 7px;
}
.toolbar-actions .dataset-count {
  align-self: auto;
  margin: 0 6px 0 0;
}
@media (max-width: 700px) {
  .source-editor-grid {
    grid-template-columns: 1fr;
  }
}
</style>
