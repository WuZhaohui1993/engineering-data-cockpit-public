<template>
  <div class="dashboard-page app-container dashboard-management-page">
    <div class="dashboard-management-tree-layout">
    <DataFolderSidebar
      v-model="query.folderId"
      v-model:include-children="query.includeChildren"
      scope="page"
      :folders="folders"
      :can-manage="canManageFolders"
      @change="handlePageFilter"
      @refresh="refreshPageFilters"
      @manage="folderDialogOpen = true"
    />
    <div class="dashboard-management-main">
    <ManagementToolbar
      title="页面管理"
      :total="total"
      noun="个页面"
      :selection-count="selectedPageIds.length"
      :active-filter="activePageFilterLabel"
      help-module="page"
      :actions="toolbarActions"
      :loading="loading"
      @search="handlePageFilter"
      @reset="resetQuery"
      @refresh="refreshPageFilters"
      @clear-filter="clearPageFilter"
      @action="handleToolbarAction"
    >
      <template #filters>
        <el-form-item label="搜索" class="filter-wide">
          <el-input v-model="query.keyword" placeholder="页面编码或名称" clearable />
        </el-form-item>
      </template>
      <template #selection>
        <el-button v-if="canMovePages" plain @click="openBatchMove">移动到文件夹</el-button>
      </template>
      <template #primary>
        <el-button v-hasPermi="['dashboard:page:add']" type="primary" :icon="Plus" @click="openCreate">新增页面</el-button>
      </template>
    </ManagementToolbar>
    <section class="page-table-wrap dashboard-management-table">
      <el-table ref="pageTableRef" v-loading="loading" :data="rows" row-key="pageId" @selection-change="selectedPageIds = $event.map(row => row.pageId)">
        <el-table-column v-if="canMovePages" type="selection" width="48" />
        <el-table-column label="页面" min-width="290"
          ><template #default="scope"
            ><div class="page-name-cell">
              <span class="page-mark"
                ><el-icon><Monitor /></el-icon
              ></span>
              <div>
                <strong>{{ scope.row.pageName }}</strong
                ><small>{{ scope.row.pageCode }}</small>
              </div>
            </div></template
          ></el-table-column
        >
        <el-table-column label="文件夹" width="140"
          ><template #default="scope"
            ><span class="folder-pill"
              ><el-icon><Folder /></el-icon
              >{{ scope.row.folderName || "未分类" }}</span
            ></template
          ></el-table-column
        >
        <el-table-column label="状态" width="110"
          ><template #default="scope"
            ><span class="status-text"
              ><i
                :class="
                  scope.row.status === '0' ? 'dot-active' : 'dot-disabled'
                "
              ></i
              >{{ scope.row.status === "0" ? "正常" : "已停用" }}</span
            ></template
          ></el-table-column
        >
        <el-table-column label="运行版本" width="120"
          ><template #default="scope"
            ><span
              v-if="pagePublishedRevision(scope.row)"
              class="version-pill"
              >V{{ pagePublishedRevision(scope.row).versionNo }}</span
            ><span v-else class="muted">未发布</span></template
          ></el-table-column
        >
        <el-table-column label="最近修改" width="180"
          ><template #default="scope">{{
            formatDashboardDateTime(scope.row.updateTime)
          }}</template></el-table-column
        >
        <el-table-column label="操作" width="190" fixed="right"
          ><template #default="scope"
            ><div class="page-actions"
            ><el-button
              v-hasPermi="['dashboard:page:edit']"
              link
              type="primary"
              @click="openDesigner(scope.row)"
              >设计</el-button
            ><el-button
              v-if="canUsePublishedPage(scope.row)"
              link type="success" @click="openRuntime(scope.row)"
              >运行</el-button
            ><el-dropdown
              v-if="hasMoreActions(scope.row)"
              trigger="click"
              @command="(command) => handlePageAction(command, scope.row)"
            >
              <el-button link type="primary"
                >更多<el-icon><MoreFilled /></el-icon
              ></el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item
                    v-if="canUsePublishedPage(scope.row) && checkPermi(['dashboard:page:menu'])"
                    command="menu"
                    >菜单配置</el-dropdown-item
                  >
                  <el-dropdown-item
                    v-if="canManagePageShares(scope.row) && checkPermi(['dashboard:share:list'])"
                    command="share"
                    >分享</el-dropdown-item
                  >
                  <el-dropdown-item command="versions">版本</el-dropdown-item>
                  <el-dropdown-item
                    v-if="checkPermi(['dashboard:page:edit'])"
                    command="move"
                    >移动</el-dropdown-item
                  >
                  <el-dropdown-item
                    v-if="checkPermi(['dashboard:page:add'])"
                    command="copy"
                    >复制</el-dropdown-item
                  >
                  <el-dropdown-item
                    v-if="checkPermi(['dashboard:page:edit'])"
                    command="status"
                    >{{ scope.row.status === "0" ? "停用" : "启用" }}</el-dropdown-item
                  >
                  <el-dropdown-item
                    v-if="checkPermi(['dashboard:page:delete'])"
                    command="delete"
                    >移入回收站</el-dropdown-item
                  >
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            </div></template
          ></el-table-column
        >
        <template #empty>
          <el-empty v-if="!loading" description="暂无符合条件的页面" :image-size="88">
            <el-button v-hasPermi="['dashboard:page:add']" type="primary" @click="openCreate">新增页面</el-button>
          </el-empty>
        </template>
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

    <el-dialog v-model="dialog.open" title="新增页面" width="460px">
      <el-form
        ref="formRef"
        :model="dialog.form"
        :rules="rules"
        label-width="90px"
      >
        <el-form-item label="页面编码" prop="pageCode"
          ><el-input
            v-model="dialog.form.pageCode"
            placeholder="例如 project-overview"
        /></el-form-item>
        <el-form-item label="页面名称" prop="pageName"
          ><el-input v-model="dialog.form.pageName" placeholder="页面名称"
        /></el-form-item>
        <el-form-item label="文件夹"
          ><el-tree-select
            v-model="dialog.form.folderId"
            :data="folderOptions"
            check-strictly
            :render-after-expand="false"
            default-expand-all
            filterable
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="备注"
          ><el-input v-model="dialog.form.remark" type="textarea" :rows="2"
        /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.open = false">取消</el-button>
        <el-button type="primary" :loading="dialog.saving" @click="submitCreate"
          >创建</el-button
        >
      </template>
    </el-dialog>
    <el-dialog
      v-model="menuConfigDialog.open"
      title="若依菜单配置"
      width="700px"
      destroy-on-close
      :close-on-click-modal="!menuConfigDialog.saving"
      :close-on-press-escape="!menuConfigDialog.saving"
      :show-close="!menuConfigDialog.saving"
    >
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="新菜单默认创建于“大屏管理”，不自动分配角色；已有菜单保留在菜单管理中设置的位置和属性。未分配角色且无子菜单时可删除。"
      />
      <el-descriptions
        v-if="menuConfigDialog.page"
        class="menu-config-descriptions"
        :column="1"
        border
      >
        <el-descriptions-item label="菜单名称">
          <div class="menu-config-value">
            <span>{{ menuConfigDialog.result?.menuName || menuConfigDialog.page.pageName }}</span>
            <el-button link type="primary" @click="copyMenuConfig('name')">复制</el-button>
          </div>
        </el-descriptions-item>
        <el-descriptions-item label="菜单位置">
          <span v-if="menuConfigDialog.result">{{ menuConfigDialog.result.parentId === 0 ? '顶级菜单' : `上级菜单编号：${menuConfigDialog.result.parentId}` }}</span>
          <span v-else>新建时放在“大屏管理”；已有菜单保留当前位置</span>
        </el-descriptions-item>
        <el-descriptions-item label="菜单类型">菜单（C）</el-descriptions-item>
        <el-descriptions-item label="路径">
          <div class="menu-config-value">
            <code>{{ menuConfigPath(menuConfigDialog.page) }}</code>
            <el-button link type="primary" @click="copyMenuConfig('path')">复制</el-button>
          </div>
        </el-descriptions-item>
        <el-descriptions-item label="组件">
          <div class="menu-config-value">
            <code>{{ menuConfigDialog.result?.component || "dashboard/runtime/index" }}</code>
            <el-button link type="primary" @click="copyMenuConfig('component')">复制</el-button>
          </div>
        </el-descriptions-item>
        <el-descriptions-item label="路由名称">
          <div class="menu-config-value">
            <code>{{ menuConfigRouteName(menuConfigDialog.page) }}</code>
            <el-button link type="primary" @click="copyMenuConfig('routeName')">复制</el-button>
          </div>
        </el-descriptions-item>
        <el-descriptions-item label="是否外链">否</el-descriptions-item>
        <el-descriptions-item label="权限字符">
          <div class="menu-config-value">
            <code>{{ menuConfigDialog.result?.perms ?? "dashboard:page:view" }}</code>
            <el-button link type="primary" @click="copyMenuConfig('perms')">复制</el-button>
          </div>
        </el-descriptions-item>
        <el-descriptions-item label="路由参数">
          <div class="menu-config-value"><code>{{ menuConfigQuery(menuConfigDialog.page) }}</code><el-button link type="primary" @click="copyMenuConfig('query')">复制</el-button></div>
        </el-descriptions-item>
        <el-descriptions-item label="独立运行地址">
          <div class="menu-config-value">
            <code>{{ menuConfigUrl(menuConfigDialog.page) }}</code>
            <el-button link type="primary" @click="copyMenuConfig('url')">复制</el-button>
          </div>
        </el-descriptions-item>
      </el-descriptions>
      <el-alert
        v-if="menuConfigDialog.result"
        class="menu-config-result"
        type="success"
        :closable="false"
        show-icon
        :title="`菜单已${menuConfigDialog.result.created ? '创建' : '更新'}。普通角色请在角色管理中授权，刷新登录态后生效。`"
      >
        <template #default>
          <div>菜单编号：{{ menuConfigDialog.result.menuId }}，路径：{{ menuConfigDialog.result.path }}</div>
          <div>当前发布版本：V{{ menuConfigDialog.result.versionNo }}</div>
        </template>
      </el-alert>
      <template #footer>
        <el-button :disabled="menuConfigDialog.saving" @click="menuConfigDialog.open = false">{{ menuConfigDialog.result ? '关闭' : '取消' }}</el-button>
        <el-button v-if="!menuConfigDialog.result" type="primary" :loading="menuConfigDialog.saving" @click="submitMenuConfig">确认配置</el-button>
      </template>
    </el-dialog>
    <DataFolderManager v-model="folderDialogOpen" scope="page" :folders="folders" :default-parent-id="query.folderId > 0 ? query.folderId : 0" @changed="refreshPageFilters" />
    <DataFolderMoveDialog v-model="moveDialog.open" scope="page" :folders="folders" :ids="moveDialog.ids" :default-folder-id="moveDialog.folderId" @moved="load" />
    <el-dialog
      v-model="recycleDialog.open"
      title="页面回收站"
      width="820px"
      destroy-on-close
    >
      <div class="recycle-toolbar">
        <el-input
          v-model="recycleDialog.query.keyword"
          clearable
          placeholder="搜索编码或名称"
          @keyup.enter="loadRecycle"
          ><template #prefix
            ><el-icon><Search /></el-icon></template
        ></el-input>
        <div>
          <el-button @click="loadRecycle">查询</el-button
          ><el-button
            v-hasPermi="['dashboard:page:purge']"
            type="danger"
            plain
            :disabled="!recycleDialog.rows.length"
            @click="purgeRecycle"
            >清空回收站</el-button
          >
        </div>
      </div>
      <el-table
        v-loading="recycleDialog.loading"
        :data="recycleDialog.rows"
        max-height="420"
      >
        <el-table-column label="页面" min-width="230"
          ><template #default="scope"
            ><div class="page-name-cell">
              <span class="page-mark deleted"
                ><el-icon><Delete /></el-icon
              ></span>
              <div>
                <strong>{{ scope.row.pageName }}</strong
                ><small>{{ scope.row.pageCode }}</small>
              </div>
            </div></template
          ></el-table-column
        >
        <el-table-column prop="folderName" label="原文件夹" min-width="130"
          ><template #default="scope">{{
            scope.row.folderName || "未分类"
          }}</template></el-table-column
        >
        <el-table-column label="移入时间" min-width="170"
          ><template #default="scope">{{
            formatDashboardDateTime(scope.row.deleteTime)
          }}</template></el-table-column
        >
        <el-table-column prop="deleteBy" label="操作人" width="110" />
        <el-table-column label="操作" width="150"
          ><template #default="scope"
            ><el-button
              v-hasPermi="['dashboard:page:restore']"
              link
              type="success"
              @click="restore(scope.row)"
              >恢复</el-button
            ><el-button
              v-hasPermi="['dashboard:page:purge']"
              link
              type="danger"
              @click="purge(scope.row)"
              >彻底删除</el-button
            ></template
          ></el-table-column
        >
      </el-table>
      <el-empty
        v-if="!recycleDialog.loading && !recycleDialog.rows.length"
        description="回收站为空"
      />
      <pagination
        v-show="recycleDialog.total > 0"
        v-model:page="recycleDialog.query.pageNum"
        v-model:limit="recycleDialog.query.pageSize"
        :total="recycleDialog.total"
        @pagination="loadRecycle"
      />
    </el-dialog>
    <el-dialog
      v-model="templateDialog.open"
      title="模板中心"
      width="820px"
      destroy-on-close
    >
      <div class="template-grid">
        <button
          v-for="template in templates"
          :key="template.key"
          class="template-card"
          :disabled="templateDialog.saving"
          @click="createFromTemplate(template)"
        >
          <div class="template-thumb" :class="`template-${template.key}`">
            <span v-for="n in template.blocks" :key="n"></span>
          </div>
          <strong>{{ template.name }}</strong>
          <p>{{ template.description }}</p>
          <small>{{ template.components }} 个组件 · 受控 JSON 配置</small>
        </button>
      </div>
      <p class="template-note">
        模板只复制页面结构，不复制数据源凭证；创建后仍需在设计器中确认字段映射、权限和发布版本。
      </p>
    </el-dialog>
    <el-dialog v-model="shareDialog.open" title="只读分享" width="760px">
      <div v-if="canUsePublishedPage(shareDialog.page)" class="share-create-row">
        <div class="share-pages-row">
          <span>版本模式</span>
          <el-radio-group v-model="shareDialog.versionMode" :disabled="shareDialog.creating">
            <el-radio-button value="FOLLOW_PUBLISHED">跟随当前发布</el-radio-button>
            <el-radio-button value="FIXED">固定版本</el-radio-button>
          </el-radio-group>
          <span class="share-pages-hint">{{ dashboardShareVersionModeHint(shareDialog.versionMode) }}</span>
        </div>
        <span>有效期</span
        ><el-select v-model="shareDialog.expiryMode" class="share-expiry-mode"
          ><el-option label="限时分享" value="TIMED" /><el-option
            label="永久分享"
            value="PERMANENT" /></el-select
        ><template v-if="shareDialog.expiryMode === 'TIMED'"
          ><el-input-number
            v-model="shareDialog.expiresValue"
            :min="1"
            :max="shareDialog.expiresUnit === 'DAY' ? 365 : 8760"
            controls-position="right" /><el-select
            v-model="shareDialog.expiresUnit"
            class="share-expiry-unit"
            ><el-option label="小时" value="HOUR" /><el-option
              label="天"
              value="DAY" /></el-select></template
        ><span v-else class="share-permanent-note">主动撤销前一直有效</span
        ><div class="share-pages-row">
          <span>可访问页面</span
          ><el-tree-select
            v-model="shareDialog.pageIds"
            class="share-pages-tree-select"
            :data="sharePageTree"
            node-key="id"
            multiple
            show-checkbox
            check-strictly
            check-on-click-node
            check-on-click-leaf
            filterable
            default-expand-all
            collapse-tags
            collapse-tags-tooltip
            :default-checked-keys="shareDialog.pageIds"
            :props="sharePageTreeProps"
            :loading="shareDialog.publishedLoading"
            empty-text="暂无已发布页面"
            placeholder="入口页（可选更多已发布页面）"
            @change="handleSharePageSelection"
          />
          <span
            v-if="shareDialog.publishedLoading"
            class="share-pages-loading"
            >正在加载已发布页面…</span
          ><span class="share-pages-hint"
            >入口页“{{ shareDialog.page?.pageName || "当前页面" }}”始终包含；选择其他已发布页面后，同一分享地址可在页面间跳转。</span
        ></div
        ><el-button
          v-hasPermi="['dashboard:share:create']"
          type="primary"
          :loading="shareDialog.creating"
          @click="createShare"
          >生成分享链接</el-button
        >
      </div>
      <el-alert
        v-if="shareDialog.url"
        type="success"
        :closable="false"
        show-icon
        title="链接已生成，可在下方分享列表中随时复制。"
        class="share-alert"
      />
      <div v-if="shareDialog.url" class="share-url-row">
        <el-input :model-value="shareDialog.url" readonly /><el-button
          @click="copyShareUrl"
          >复制链接</el-button
        >
      </div>
      <div class="share-list-title">已有分享</div>
      <el-table
        v-loading="shareDialog.loading"
        :data="shareDialog.rows"
        max-height="300"
        ><el-table-column
          prop="shareId"
          label="编号"
          width="75"
        /><el-table-column label="版本模式" width="145">
          <template #default="scope">
            <el-tag :type="normalizeDashboardShareVersionMode(scope.row.versionMode) === 'FOLLOW_PUBLISHED' ? 'success' : 'info'">
              {{ dashboardShareVersionModeLabel(scope.row.versionMode) }}
            </el-tag>
          </template>
        </el-table-column><el-table-column label="分享地址" min-width="300"
          ><template #default="scope"
            ><div v-if="shareAddress(scope.row)" class="share-address-cell">
              <el-input
                :model-value="shareAddress(scope.row)"
                readonly
              /><el-button link type="primary" @click="copyShareRow(scope.row, $event)"
                >复制</el-button
              >
            </div>
            <span v-else class="share-address-missing"
              >历史地址无法恢复，请重新生成</span
            ></template
          ></el-table-column
        ><el-table-column label="过期时间" min-width="180"
          ><template #default="scope"
            ><span :class="{ 'permanent-share': !scope.row.expiresAt }">{{
              scope.row.expiresAt
                ? formatDashboardDateTime(scope.row.expiresAt)
                : "永久有效"
            }}</span></template
          ></el-table-column
        ><el-table-column label="最近访问" min-width="180"
          ><template #default="scope">{{
            formatDashboardDateTime(scope.row.lastAccessAt, "尚未访问")
          }}</template></el-table-column
        ><el-table-column label="状态" width="90"
          ><template #default="scope"
            ><el-tag :type="shareStatus(scope.row).type">{{
              shareStatus(scope.row).label
            }}</el-tag></template
          ></el-table-column
        ><el-table-column label="操作" width="170" fixed="right"
          ><template #default="scope"
            ><el-button
              v-if="canChangeShareVersionMode(scope.row)"
              v-hasPermi="['dashboard:share:create']"
              link
              type="primary"
              :loading="shareDialog.changingMode === scope.row.shareId"
              :disabled="Boolean(shareDialog.changingMode) && shareDialog.changingMode !== scope.row.shareId"
              @click="changeShareVersionMode(scope.row)"
              >{{ normalizeDashboardShareVersionMode(scope.row.versionMode) === 'FOLLOW_PUBLISHED' ? '切为固定' : '改为跟随' }}</el-button
            ><el-button
              v-if="scope.row.status === 'ACTIVE'"
              v-hasPermi="['dashboard:share:revoke']"
              link
              type="danger"
              @click="revokeShare(scope.row)"
              >撤销</el-button
            ></template
          ></el-table-column
        ></el-table
      >
    </el-dialog>
    <el-drawer v-model="versionDrawer.open" title="页面版本" size="500px"
      ><div class="version-drawer-head">
        <strong>{{ versionDrawer.page?.pageName }}</strong
        ><span>{{ versionDrawer.page?.pageCode }}</span>
      </div>
      <div v-loading="versionDrawer.loading" class="version-list">
        <div
          v-for="version in versionDrawer.page?.revisions || []"
          :key="version.revisionId"
          class="version-row"
          :class="{
            current:
              String(version.revisionId) ===
              String(versionDrawer.page?.currentRevisionId),
          }"
        >
          <div>
            <div class="version-title">
              <span>版本 {{ version.versionNo }}</span
              ><el-tag
                :type="
                  version.status === 'PUBLISHED'
                    ? 'success'
                    : version.status === 'DRAFT'
                      ? 'warning'
                      : 'info'
                "
                >{{ revisionLabel(version.status) }}</el-tag
              ><el-tag
                v-if="
                  String(version.revisionId) ===
                  String(versionDrawer.page?.currentRevisionId)
                "
                effect="plain"
                >当前运行</el-tag
              >
            </div>
            <small
              >{{
                formatDashboardDateTime(
                  version.publishTime || version.createTime,
                )
              }}
              · {{ version.publishBy || version.createBy || "-" }}</small
            >
            <p>{{ version.publishNote || "无发布说明" }}</p>
          </div>
          <div class="version-actions">
            <el-button link type="primary" @click="previewVersion(version)"
              >预览</el-button
            ><el-button
              v-if="
                ['PUBLISHED', 'ARCHIVED'].includes(version.status) &&
                String(version.revisionId) !==
                  String(versionDrawer.page?.currentRevisionId)
              "
              v-hasPermi="['dashboard:page:rollback']"
              link
              type="warning"
              @click="rollback(version)"
              >回滚</el-button
            >
          </div>
        </div>
        <div v-if="!versionDrawer.page?.revisions?.length" class="drawer-empty">
          暂无版本
        </div>
      </div></el-drawer
    >
  </div>
</template>

<script setup>
import ManagementToolbar from "@/components/DashboardManagement/ManagementToolbar.vue";
import { computed, onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import DataFolderSidebar from "@/components/DashboardDataManagement/DataFolderSidebar.vue";
import DataFolderManager from "@/components/DashboardDataManagement/DataFolderManager.vue";
import DataFolderMoveDialog from "@/components/DashboardDataManagement/DataFolderMoveDialog.vue";
import { listDataFolders } from "@/api/dashboardDataFolder";
import { buildDataFolderOptions, normalizeDataFolders, dataFolderName } from "@/utils/dashboardDataFolder";
import { checkPermi } from "@/utils/permission";
import {
  Collection,
  Delete,
  Folder,
  MoreFilled,
  Monitor,
  Plus,
} from "@element-plus/icons-vue";
import {
  addDashboardPage,
  copyDashboardPage,
  configureDashboardPageMenu,
  createDashboardShare,
  delDashboardPage,
  getDashboardPage,
  listDashboardAssets,
  listDashboardDatasets,
  listDashboardPageRecycle,
  listDashboardPages,
  listPublishedDashboardPages,
  listDashboardShares,
  purgeDashboardPage,
  purgeDashboardPageRecycle,
  restoreDashboardPage,
  revokeDashboardShare,
  rollbackDashboardPage,
  updateDashboardPage,
  updateDashboardShareMode,
} from "@/api/dashboard";
import { formatDashboardDateTime } from "@/utils/dashboard";
import { copyTextWithFallback } from "@/utils/clipboard";
import { getDashboardPublishedRevision, hasDashboardPublishedVersion, isDashboardPageRunnable } from "@/utils/dashboardPageAvailability";
import { normalizeDashboardShareVersionMode, dashboardShareVersionModeLabel, dashboardShareVersionModeHint, dashboardShareVersionModeConfirmation } from "@/utils/dashboardShareMode";
import { resolveReferenceAssets } from "@/views/dashboard/referenceAssetCatalog";
import { referenceTemplates, referenceSchema } from "@/views/dashboard/referencePresets";

const router = useRouter();
const loading = ref(false);
let pageRequestId = 0;
const rows = ref([]);
const runnablePages = ref([]);
const publishedPageDetails = ref([]);
const checkingPublishedPages = new Set();
const total = ref(0);
const folders = ref([]);
const pageTableRef = ref();
const selectedPageIds = ref([]);
const canMovePages = computed(() => checkPermi(["dashboard:page:edit"]));
const canManageFolders = computed(() => checkPermi(["dashboard:page:add", "dashboard:page:edit", "dashboard:page:delete"]));
const folderOptions = computed(() => buildDataFolderOptions(folders.value));
const toolbarActions = computed(() => [
  ...(checkPermi(["dashboard:page:add"]) ? [{ key: "templates", label: "模板中心", icon: Collection }] : []),
  ...(checkPermi(["dashboard:page:recycle"]) ? [{ key: "recycle", label: "回收站", icon: Delete }] : []),
]);
const query = reactive({
  pageNum: 1,
  pageSize: 10,
  keyword: "",
  folderId: null,
  includeChildren: false,
});
const formRef = ref();
const dialog = reactive({
  open: false,
  saving: false,
  form: { pageCode: "", pageName: "", folderId: 0, remark: "" },
});
const folderDialogOpen = ref(false);
const moveDialog = reactive({ open: false, ids: [], folderId: 0 });
const recycleDialog = reactive({
  open: false,
  loading: false,
  rows: [],
  total: 0,
  query: { pageNum: 1, pageSize: 10, keyword: "" },
});
const versionDrawer = reactive({ open: false, loading: false, page: null });
const shareDialog = reactive({
  open: false,
  loading: false,
  creating: false,
  changingMode: null,
  page: null,
  rows: [],
  expiryMode: "TIMED",
  versionMode: "FOLLOW_PUBLISHED",
  expiresValue: 24,
  expiresUnit: "HOUR",
  pageIds: [],
  publishedPages: [],
  publishedLoading: false,
  url: "",
});
const menuConfigDialog = reactive({ open: false, page: null, saving: false, result: null });
const sharePageTreeProps = {
  value: "id",
  children: "children",
  label: "label",
  disabled: "disabled",
};
const templateDialog = reactive({ open: false, saving: false });
const templates = [
  {
    key: "blank",
    name: "空白画布",
    description: "从固定画布开始自由编排组件。",
    components: 0,
    blocks: 0,
  },
  {
    key: "labor-overview",
    name: "业务事件概览",
    description: "使用已登记业务数据集的示例布局。",
    components: 4,
    blocks: 4,
  },
  ...referenceTemplates,
];
const rules = {
  pageCode: [{ required: true, message: "页面编码不能为空", trigger: "blur" }],
  pageName: [{ required: true, message: "页面名称不能为空", trigger: "blur" }],
};
const sharePageTree = computed(() => {
  const root = {
    id: "share-page-folder-root",
    label: "根目录",
    isPage: false,
    disabled: true,
    children: [],
  };
  const folderNodes = new Map();
  const folderRows = Array.isArray(folders.value) ? folders.value : [];
  folderRows.forEach((folder) => {
    const folderId = Number(folder.folderId || 0);
    if (!folderId) return;
    folderNodes.set(folderId, {
      id: `share-page-folder-${folderId}`,
      label: folder.folderName || `文件夹 ${folderId}`,
      isPage: false,
      disabled: true,
      children: [],
    });
  });
  folderRows.forEach((folder) => {
    const folderId = Number(folder.folderId || 0);
    const node = folderNodes.get(folderId);
    if (!node) return;
    const parentId = Number(folder.parentId || 0);
    const parent = folderNodes.get(parentId) || root;
    parent.children.push(node);
  });
  (shareDialog.publishedPages || []).forEach((page) => {
    const folderId = Number(page.folderId || 0);
    const parent = folderNodes.get(folderId) || root;
    parent.children.push({
      id: Number(page.pageId),
      pageId: Number(page.pageId),
      label: publishedPageLabel(page),
      isPage: true,
      disabled: false,
    });
  });
  return [root];
});

const activePageFilterLabel = computed(() => query.folderId == null ? "" : dataFolderName(folders.value, query.folderId));

function load() {
  const requestId = ++pageRequestId;
  loading.value = true;
  selectedPageIds.value = [];
  pageTableRef.value?.clearSelection();
  const params = { ...query, keyword: query.keyword || undefined, folderId: query.folderId ?? undefined };
  return Promise.all([
    listDashboardPages(params),
    listPublishedDashboardPages({ keyword: params.keyword, folderId: params.folderId, includeChildren: params.includeChildren })
      .catch(() => ({ data: [] })),
  ])
    .then(async ([res, published]) => {
      if (requestId !== pageRequestId) return;
      const count = Number(res.total) || 0;
      const lastPage = Math.max(1, Math.ceil(count / params.pageSize));
      if (query.pageNum > lastPage) {
        query.pageNum = lastPage;
        return load();
      }
      // 停用页不在可运行目录中，仍按当前列表补取其历史发布详情以管理分享。
      const details = [];
      for (const row of (res.rows || []).filter((item) => String(item.status) === "1")) {
        if (requestId !== pageRequestId) return;
        try {
          const detail = await getDashboardPage(row.pageId);
          if (detail.data) details.push(detail.data);
        } catch { break; }
      }
      if (requestId !== pageRequestId) return;
      rows.value = res.rows || [];
      runnablePages.value = published.data || [];
      publishedPageDetails.value = details;
      total.value = count;
    })
    .finally(() => {
      if (requestId === pageRequestId) loading.value = false;
    });
}
function loadFolders() {
  return listDataFolders("page").then((res) => {
    folders.value = normalizeDataFolders(res.data || res.rows || []);
    if (query.folderId > 0 && !folders.value.some(folder => folder.folderId === Number(query.folderId))) {
      query.folderId = null;
      query.pageNum = 1;
    }
  });
}
async function refreshPageFilters() {
  await loadFolders();
  return load();
}
function handlePageFilter() {
  query.pageNum = 1;
  return load();
}
function clearPageFilter() {
  query.folderId = null;
  query.includeChildren = false;
  return handlePageFilter();
}
function handleToolbarAction(key) {
  if (key === "templates") templateDialog.open = true;
  else if (key === "recycle") openRecycle();
}
function openCreate() {
  dialog.form = {
    pageCode: "",
    pageName: "",
    folderId: query.folderId > 0 ? query.folderId : 0,
    remark: "",
  };
  dialog.open = true;
}
function resetQuery() {
  query.keyword = "";
  clearPageFilter();
}
function submitCreate() {
  formRef.value.validate((valid) => {
    if (!valid) return;
    dialog.saving = true;
    addDashboardPage(dialog.form)
      .then((res) => {
        ElMessage.success("页面已创建，请进入设计器编辑");
        dialog.open = false;
        load();
        openDesigner(res.data);
      })
      .finally(() => {
        dialog.saving = false;
      });
  });
}
async function templateSchema(key) {
  if (String(key).startsWith("reference-")) {
    const [resourceResult, datasetResult] = await Promise.all([listDashboardAssets(), listDashboardDatasets({ pageNum: 1, pageSize: 1000 })]);
    const assets = resolveReferenceAssets(resourceResult.data || resourceResult.rows || []);
    const schema = referenceSchema(String(key).slice("reference-".length), { assets });
    const datasetCodes = new Set((datasetResult.rows || []).map(row => row.datasetCode));
    if (schema.widgets.some(widget => (widget.type === "image" && !widget.style.imageRef) || (widget.style?.titleImageEnabled && !widget.style.titleImageRef))) throw new Error("参考模板需要的生成图片尚未登记，请先在资源管理上传配套素材");
    if (schema.widgets.some(widget => widget.binding?.datasetCode && !datasetCodes.has(widget.binding.datasetCode))) throw new Error("参考模板需要的 JSON 数据集尚未创建，请先在数据集管理登记配套数据");
    return schema;
  }
  const base = {
    schemaVersion: "1.0",
    canvas: {
      width: 1920,
      height: 1080,
      scaleMode: "contain",
      fullscreenScaleMode: "contain",
      theme: "dark",
      background: {
        color: "#0b1220",
        imageRef: "",
        position: "center",
        opacity: 1,
      },
      watermark: {
        enabled: false,
        text: "",
        fontSize: 22,
        color: "#d4deeb",
        rotate: -20,
        opacity: 0.12,
      },
    },
    refresh: { enabled: true, mode: "interval", seconds: 30, at: "08:00" },
    filters: [],
    widgets: [],
  };
  if (key !== "labor-overview") return base;
  base.widgets = [
    {
      id: "template-total",
      type: "metric-card",
      name: "事件总数",
      layout: { x: 48, y: 40, w: 360, h: 160, z: 1 },
      binding: {
        datasetCode: "labor-hik-total",
        fieldMap: { value: "total" },
        parameters: { projectCode: "" },
        filters: [],
        rowLimit: 1,
        refreshSeconds: 0,
      },
      style: { title: "事件总数", unit: "条", color: "#4fd1b0" },
      interaction: { onClick: "none" },
    },
    {
      id: "template-direction",
      type: "bar-chart",
      name: "进出方向分布",
      layout: { x: 48, y: 240, w: 820, h: 360, z: 2 },
      binding: {
        datasetCode: "labor-hik-by-direction",
        fieldMap: { category: "direction", value: "count" },
        parameters: { projectCode: "" },
        filters: [],
        rowLimit: 20,
        refreshSeconds: 0,
      },
      style: { title: "进出方向分布", color: "#5b8ff9" },
      interaction: { onClick: "none" },
    },
    {
      id: "template-recent",
      type: "table",
      name: "最近事件（脱敏）",
      layout: { x: 900, y: 240, w: 900, h: 360, z: 3 },
      binding: {
        datasetCode: "labor-hik-recent",
        fieldMap: {},
        parameters: { projectCode: "" },
        filters: [],
        rowLimit: 10,
        refreshSeconds: 0,
      },
      style: { title: "最近事件（脱敏）" },
      interaction: { onClick: "none" },
    },
    {
      id: "template-live",
      type: "realtime-list",
      name: "实时事件",
      layout: { x: 48, y: 640, w: 820, h: 260, z: 4 },
      binding: {
        datasetCode: "labor-hik-live",
        fieldMap: {},
        parameters: { projectCode: "" },
        filters: [],
        rowLimit: 10,
        refreshSeconds: 0,
      },
      style: { title: "实时事件" },
      interaction: { onClick: "none" },
    },
  ];
  return base;
}
function createFromTemplate(template) {
  if (templateDialog.saving) return;
  ElMessageBox.prompt("请输入页面名称", "从模板创建", {
    inputValue: template.name,
    inputValidator: (value) => (value?.trim() ? true : "名称不能为空"),
  })
    .then(async ({ value }) => {
      templateDialog.saving = true;
      const pageCode = `${template.key}-${Date.now()}`;
      return addDashboardPage({
        pageCode,
        pageName: value,
        folderId: query.folderId > 0 ? query.folderId : 0,
        remark: `由模板“${template.name}”创建`,
        schemaJson: JSON.stringify(await templateSchema(template.key)),
      })
        .then((res) => {
          templateDialog.open = false;
          ElMessage.success("模板页面已创建");
          load();
          if (res?.data?.pageId) openDesigner(res.data);
        })
        .finally(() => {
          templateDialog.saving = false;
        });
    })
    .catch((error) => { templateDialog.saving = false; if (error?.message) ElMessage.error(error.message); });
}
function openDesigner(row) {
  router.push(`/dashboard/designer/${row.pageId}`);
}
function canUsePublishedPage(row) {
  const current = rows.value.find((item) => item.pageId === row?.pageId) || row;
  return isDashboardPageRunnable(current, runnablePages.value);
}
function pagePublishedRevision(row) {
  const current = rows.value.find((item) => item.pageId === row?.pageId) || row;
  const detail = publishedPageDetails.value.find((item) => item.pageId === current?.pageId) || current;
  return getDashboardPublishedRevision(detail, String(current?.status) === "0" ? runnablePages.value : undefined);
}
function canManagePageShares(row) {
  const current = rows.value.find((item) => item.pageId === row?.pageId) || row;
  return Boolean(current && String(current.isDeleted ?? "0") === "0" && pagePublishedRevision(current));
}
async function ensureShareManagementPage(row) {
  const pageId = row?.pageId;
  if (checkingPublishedPages.has(pageId)) return null;
  if (!canManagePageShares(row)) {
    ElMessage.warning("页面尚未发布，暂无可管理的分享");
    return null;
  }
  checkingPublishedPages.add(pageId);
  try {
    const res = await getDashboardPage(pageId);
    const detail = res.data;
    if (!hasDashboardPublishedVersion(detail) || String(detail.isDeleted ?? "0") !== "0") {
      ElMessage.warning("页面已无发布版本，请刷新列表后重试");
      return null;
    }
    publishedPageDetails.value = [...publishedPageDetails.value.filter((item) => item.pageId !== pageId), detail];
    const current = rows.value.find((item) => item.pageId === pageId);
    if (current) Object.assign(current, { status: detail.status, isDeleted: detail.isDeleted });
    runnablePages.value = runnablePages.value.filter((item) => item.pageId !== pageId);
    if (isDashboardPageRunnable(detail)) runnablePages.value.push({ ...getDashboardPublishedRevision(detail), pageId });
    return detail;
  } catch {
    return null;
  } finally {
    checkingPublishedPages.delete(pageId);
  }
}
async function ensurePublishedPage(row) {
  const pageId = row?.pageId;
  if (checkingPublishedPages.has(pageId)) return false;
  if (!canUsePublishedPage(row)) {
    ElMessage.warning("页面尚未发布或已停用，请发布并启用后再操作");
    return false;
  }
  checkingPublishedPages.add(pageId);
  try {
    // 列表停留期间可能在其他页签停用或撤回页面；进入运行路由前再用
    // 轻量发布目录复核，避免已经失效的入口打开错误页。
    const res = await listPublishedDashboardPages({ keyword: row.pageCode });
    if (canUsePublishedPage(row) && isDashboardPageRunnable(row, res.data || [])) return true;
    runnablePages.value = runnablePages.value.filter((item) => item.pageId !== pageId);
    ElMessage.warning("页面尚未发布或已停用，请刷新列表后重试");
    return false;
  } catch {
    return false;
  } finally {
    checkingPublishedPages.delete(pageId);
  }
}
async function openRuntime(row) {
  if (!(await ensurePublishedPage(row))) return;
  router.push(`/dashboard/runtime/${row.pageId}`);
}
function hasMoreActions(row) {
  // “版本”是页面查看范围内的固定操作，因此更多菜单不会变成空菜单；
  // 其余菜单项仍由各自的权限条件按角色裁剪。
  return Boolean(row);
}
function handlePageAction(command, row) {
  const actions = {
    menu: openMenuConfig,
    share: openShare,
    versions: openVersions,
    move: openMove,
    copy: copyPage,
    status: toggleStatus,
    delete: remove,
  };
  return actions[command]?.(row);
}
function menuConfigPath(row) {
  if (menuConfigDialog.result) return menuConfigDialog.result.path;
  return `runtime/code/${encodeURIComponent(String(row?.pageCode || "").trim())}`;
}
function menuConfigQuery(row) {
  return menuConfigDialog.result?.query || JSON.stringify({ dashboardPageCode: row?.pageCode || "" });
}
function menuConfigRouteName(row) {
  if (menuConfigDialog.result) return menuConfigDialog.result.routeName;
  return `DashboardRuntime${row?.pageId || ""}`;
}
function menuConfigUrl(row) {
  const code = encodeURIComponent(String(row?.pageCode || "").trim());
  return `${window.location.origin}/dashboard/runtime/code/${code}`;
}
async function openMenuConfig(row) {
  if (menuConfigDialog.saving) return;
  if (!(await ensurePublishedPage(row))) return;
  menuConfigDialog.page = { ...row };
  menuConfigDialog.result = null;
  menuConfigDialog.open = true;
}
async function submitMenuConfig() {
  const row = menuConfigDialog.page;
  if (!menuConfigDialog.open || !row?.pageId || menuConfigDialog.saving || menuConfigDialog.result) return;
  if (!(await ensurePublishedPage(row))) return;
  if (!menuConfigDialog.open || menuConfigDialog.page !== row) return;
  menuConfigDialog.saving = true;
  return configureDashboardPageMenu(row.pageId)
    .then((res) => {
      if (!menuConfigDialog.open || menuConfigDialog.page !== row) return;
      menuConfigDialog.result = res?.data || res;
      ElMessage.success("菜单配置成功");
    })
    .catch(() => {})
    .finally(() => {
      menuConfigDialog.saving = false;
    });
}
function copyMenuConfig(field) {
  const row = menuConfigDialog.page;
  if (!row) return;
  const values = {
    name: menuConfigDialog.result?.menuName || row.pageName || row.pageCode,
    path: menuConfigPath(row),
    component: menuConfigDialog.result?.component || "dashboard/runtime/index",
    routeName: menuConfigRouteName(row),
    perms: menuConfigDialog.result?.perms ?? "dashboard:page:view",
    query: menuConfigQuery(row),
    url: menuConfigUrl(row),
  };
  const value = values[field];
  const result = navigator.clipboard?.writeText(value);
  if (result?.then)
    result
      .then(() => ElMessage.success("配置已复制"))
      .catch(() => ElMessage.info("请手工复制配置"));
  else ElMessage.info("请手工复制配置");
}
async function openShare(row) {
  const pageData = await ensureShareManagementPage(row);
  if (!pageData) return;
  shareDialog.page = pageData;
  shareDialog.versionMode = "FOLLOW_PUBLISHED";
  shareDialog.open = true;
  shareDialog.url = "";
  shareDialog.rows = [];
  shareDialog.pageIds = Number(row.pageId) > 0 ? [Number(row.pageId)] : [];
  shareDialog.publishedPages = [];
  shareDialog.publishedLoading = true;
  shareDialog.loading = true;
  return Promise.all([
    listDashboardShares(row.pageId),
    canUsePublishedPage(pageData) ? loadSharePublishedPages() : Promise.resolve([]),
  ])
    .then(([res, publishedPages]) => {
      shareDialog.rows = res.data || res.rows || [];
      // 同时检查已发布配置和当前草稿：设计器刚配置跳转但尚未再次发布时，
      // 分享入口也应提前带上该目标页面。
      const schema = [
        pageData.publishedSchema,
        pageData.currentSchema,
        pageData.draftSchema,
      ]
        .filter((item) => item != null)
        .map(parseDashboardSchema);
      const targetIds = collectPublishedTargetPageIds(
        schema,
        publishedPages,
      );
      shareDialog.pageIds = [
        ...new Set([
          ...shareDialog.pageIds,
          ...targetIds,
        ]),
      ];
      // TreeSelect 同步已发布页面树后重新触发一次 checked keys，确保异步加载完成后入口页仍显示为已选。
      shareDialog.pageIds = [...shareDialog.pageIds];
    })
    .finally(() => {
      shareDialog.loading = false;
      shareDialog.publishedLoading = false;
    });
}

function parseDashboardSchema(schema) {
  if (typeof schema !== "string") return schema;
  try {
    return JSON.parse(schema);
  } catch {
    return null;
  }
}

/**
 * 从页面配置中收集按钮跳转到的已发布页面。页面配置由设计器保存为
 * 任意嵌套对象，递归读取 interaction，兼容新字段与历史 target 路径。
 */
function collectPublishedTargetPageIds(schema, publishedPages) {
  if (!schema || typeof schema !== "object") return [];
  const rows = Array.isArray(publishedPages) ? publishedPages : [];
  const byId = new Map(
    rows.map((item) => [Number(item.pageId), Number(item.pageId)]),
  );
  const byCode = new Map(
    rows.map((item) => [String(item.pageCode || "").trim(), Number(item.pageId)]),
  );
  const ids = new Set();
  const visit = (value) => {
    if (!value || typeof value !== "object") return;
    if (Array.isArray(value)) {
      value.forEach(visit);
      return;
    }
    const interaction = value.interaction;
    if (interaction && typeof interaction === "object") {
      const mode = String(interaction.targetMode || "").toLowerCase();
      const code = String(interaction.targetPageCode || "").trim();
      const targetId = Number(interaction.targetPageId);
      const target = String(interaction.target || "").trim();
      if (mode === "published" || code || Number.isSafeInteger(targetId) || target) {
        if (code && byCode.has(code)) ids.add(byCode.get(code));
        if (Number.isSafeInteger(targetId) && byId.has(targetId)) ids.add(targetId);
        const legacyMatch = target.match(/\/dashboard\/runtime\/(?:code\/)?([^/?#]+)/i);
        if (legacyMatch) {
          let ref = legacyMatch[1];
          try {
            ref = decodeURIComponent(ref);
          } catch {
            // Ignore malformed legacy paths; the server still validates targets.
          }
          const numericId = Number(ref);
          if (Number.isSafeInteger(numericId) && byId.has(numericId)) ids.add(numericId);
          if (byCode.has(ref)) ids.add(byCode.get(ref));
        }
      }
    }
    Object.values(value).forEach(visit);
  };
  visit(schema);
  return [...ids].filter((id) => Number.isSafeInteger(id) && id > 0);
}

function publishedPageLabel(row) {
  const name = String(row?.pageName || row?.pageCode || "页面").trim();
  const code = String(row?.pageCode || "").trim();
  const version = row?.versionNo ?? row?.version ?? row?.currentVersionNo;
  const suffix = version === null || version === undefined || version === ""
    ? code
    : `${code} · V${version}`;
  return suffix ? `${name}（${suffix}）` : name;
}

function loadSharePublishedPages() {
  const apply = (res, fallback = false) => {
    const root = res?.data ?? res;
    const rows = Array.isArray(root)
      ? root
      : Array.isArray(res?.rows)
        ? res.rows
        : Array.isArray(root?.rows)
          ? root.rows
          : [];
    shareDialog.publishedPages = rows
      .filter((row) => {
        if (!fallback) return true;
        const status = String(row?.status ?? "0");
        const deleted = String(row?.isDeleted ?? "0");
        return (
          status === "0" &&
          deleted === "0" &&
          (row?.currentVersionNo ?? row?.versionNo ?? row?.version) != null
        );
      })
      .map((row) => ({
        ...row,
        pageId: Number(row?.pageId ?? row?.id ?? 0) || 0,
        folderId: Number(row?.folderId ?? 0) || 0,
        folderName: String(row?.folderName ?? "").trim(),
        pageCode: String(row?.pageCode ?? row?.code ?? "").trim(),
        pageName: String(
          row?.pageName ?? row?.name ?? row?.pageCode ?? "",
        ).trim(),
      }))
      .filter((row) => row.pageId > 0 && row.pageCode);
    return shareDialog.publishedPages;
  };
  return listPublishedDashboardPages({ pageNum: 1, pageSize: 1000 })
    .then((res) => apply(res))
    .catch(() =>
      listDashboardPages({ pageNum: 1, pageSize: 1000 })
        .then((res) => apply(res, true))
        .catch(() => {
          shareDialog.publishedPages = [];
          return [];
        }),
      );
}

function handleSharePageSelection(value) {
  const selectedIds = (Array.isArray(value) ? value : [])
    .map((id) => Number(id))
    .filter((id) => Number.isSafeInteger(id) && id > 0);
  const entryPageId = Number(shareDialog.page?.pageId || 0);
  if (entryPageId > 0 && !selectedIds.includes(entryPageId))
    selectedIds.unshift(entryPageId);
  shareDialog.pageIds = [...new Set(selectedIds)];
}

async function createShare() {
  if (!shareDialog.page || shareDialog.creating) return;
  const page = shareDialog.page;
  const request = {
    permanent: shareDialog.expiryMode === "PERMANENT",
    expiresValue: shareDialog.expiresValue,
    expiresUnit: shareDialog.expiresUnit,
    pageIds: (shareDialog.pageIds || []).filter((id) => Number(id) > 0),
    versionMode: normalizeDashboardShareVersionMode(shareDialog.versionMode || "FOLLOW_PUBLISHED"),
  };
  if (!(await ensurePublishedPage(page))) return;
  if (!shareDialog.open || shareDialog.page !== page) return;
  shareDialog.creating = true;
  return createDashboardShare(page.pageId, request)
    .then((res) => {
      if (!shareDialog.open || shareDialog.page !== page) return;
      const data = res.data || res;
      shareDialog.url = `${window.location.origin}/dashboard/share/${encodeURIComponent(data.token)}`;
      ElMessage.success("分享链接已生成");
      return listDashboardShares(page.pageId).then((list) => {
        if (!shareDialog.open || shareDialog.page !== page) return;
        shareDialog.rows = list.data || list.rows || [];
      });
    })
    .finally(() => {
      shareDialog.creating = false;
    });
}
function canChangeShareVersionMode(row) {
  return Boolean(row?.shareId && shareStatus(row).type === "success" && canUsePublishedPage(shareDialog.page));
}
async function changeShareVersionMode(row) {
  if (shareDialog.changingMode || !canChangeShareVersionMode(row)) return;
  const page = shareDialog.page;
  const targetMode = normalizeDashboardShareVersionMode(row.versionMode) === "FOLLOW_PUBLISHED" ? "FIXED" : "FOLLOW_PUBLISHED";
  shareDialog.changingMode = row.shareId;
  try {
    await ElMessageBox.confirm(dashboardShareVersionModeConfirmation(targetMode), `改为${dashboardShareVersionModeLabel(targetMode)}`, {
      type: "warning", confirmButtonText: "确认更改", cancelButtonText: "取消",
    });
    if (!shareDialog.open || shareDialog.page !== page || !canChangeShareVersionMode(row)) return;
    if (!(await ensurePublishedPage(page))) return;
    if (!shareDialog.open || shareDialog.page !== page) return;
    const response = await updateDashboardShareMode(page.pageId, row.shareId, targetMode);
    if (!shareDialog.open || shareDialog.page !== page) return;
    const data = response.data || {};
    Object.assign(row, { versionMode: data.versionMode || targetMode, ...(data.revisionId != null ? { revisionId: data.revisionId } : {}) });
    ElMessage.success("分享模式已更新，原链接继续使用");
    const list = await listDashboardShares(page.pageId);
    if (shareDialog.open && shareDialog.page === page) shareDialog.rows = list.data || list.rows || [];
  } catch {
    // 取消不改变原模式；接口失败由请求封装提示，服务端原子保留原绑定。
  } finally {
    shareDialog.changingMode = null;
  }
}
async function copyShareUrl(event) {
  if (!shareDialog.url) return;
  const input = event?.currentTarget?.closest(".share-url-row")?.querySelector("input");
  const result = await copyTextWithFallback(shareDialog.url, { input });
  if (result.copied) ElMessage.success("链接已复制");
  else ElMessage.info(result.selected ? "链接已选中，请按 Ctrl+C（Mac 使用 ⌘C）复制" : "复制未成功，请选中链接输入框中的内容复制");
}
function shareAddress(row) {
  return row?.token
    ? `${window.location.origin}/dashboard/share/${encodeURIComponent(row.token)}`
    : "";
}
async function copyShareRow(row, event) {
  const url = shareAddress(row);
  if (!url) return;
  const input = event?.currentTarget?.closest(".share-address-cell")?.querySelector("input");
  const result = await copyTextWithFallback(url, { input });
  if (result.copied) ElMessage.success("链接已复制");
  else ElMessage.info(result.selected ? "链接已选中，请按 Ctrl+C（Mac 使用 ⌘C）复制" : "复制未成功，请选中链接输入框中的内容复制");
}
function shareStatus(row) {
  if (row.status !== "ACTIVE") return { label: "已撤销", type: "info" };
  if (row.expiresAt && new Date(row.expiresAt).getTime() <= Date.now())
    return { label: "已过期", type: "warning" };
  return { label: "有效", type: "success" };
}
function revokeShare(row) {
  ElMessageBox.confirm("撤销后该链接立即失效，确定继续吗？", "撤销分享", {
    type: "warning",
  })
    .then(() => revokeDashboardShare(shareDialog.page.pageId, row.shareId))
    .then(() => {
      ElMessage.success("分享已撤销");
      openShare(shareDialog.page);
    })
    .catch(() => {});
}
function toggleStatus(row) {
  updateDashboardPage({
    pageId: row.pageId,
    status: row.status === "0" ? "1" : "0",
  }).then(() => {
    ElMessage.success("状态已更新");
    load();
  });
}
function copyPage(row) {
  ElMessageBox.prompt("请输入新页面名称", "复制页面", {
    inputValue: `${row.pageName} 副本`,
    inputValidator: (value) => (value?.trim() ? true : "名称不能为空"),
  })
    .then(({ value }) =>
      copyDashboardPage(row.pageId, {
        pageCode: `${row.pageCode}-copy-${Date.now()}`,
        pageName: value,
        remark: `复制自 ${row.pageCode}`,
      }),
    )
    .then(() => {
      ElMessage.success("页面已复制");
      load();
    })
    .catch(() => {});
}
function openVersions(row) {
  versionDrawer.open = true;
  versionDrawer.loading = true;
  getDashboardPage(row.pageId)
    .then((res) => {
      versionDrawer.page = res.data || {};
    })
    .finally(() => {
      versionDrawer.loading = false;
    });
}
function previewVersion(version) {
  const href = router.resolve({
    path: `/dashboard/runtime/${versionDrawer.page.pageId}`,
    query: { preview: 1, revisionId: version.revisionId },
  }).href;
  window.open(href, "_blank", "noopener,noreferrer");
}
function revisionLabel(value) {
  return (
    {
      DRAFT: "草稿",
      PUBLISHED: "已发布",
      ARCHIVED: "已归档",
      PREVIEWED: "预览",
    }[value] || "未知状态"
  );
}
function rollback(version) {
  ElMessageBox.confirm(
    `确定回滚到版本 ${version.versionNo} 吗？系统会生成一个新的发布版本。`,
    "版本回滚",
    { type: "warning" },
  )
    .then(() =>
      rollbackDashboardPage(versionDrawer.page.pageId, version.revisionId, {
        note: `页面列表回滚到版本 ${version.versionNo}`,
      }),
    )
    .then(() => {
      ElMessage.success("已生成回滚版本");
      openVersions(versionDrawer.page);
    })
    .catch(() => {});
}
function remove(row) {
  ElMessageBox.confirm(
    `页面“${row.pageName}”会移入回收站，历史版本和分享链接会保留，可从回收站恢复。`,
    "移入回收站",
    { type: "warning", confirmButtonText: "移入回收站" },
  )
    .then(() => {
      delDashboardPage(row.pageId).then(() => {
        ElMessage.success("页面已移入回收站");
        load();
      });
    })
    .catch(() => {});
}
function openMove(row) {
  if (!canMovePages.value) return;
  moveDialog.ids = [row.pageId];
  moveDialog.folderId = Number(row.folderId || 0);
  moveDialog.open = true;
}
function openBatchMove() {
  if (!canMovePages.value || !selectedPageIds.value.length) return;
  moveDialog.ids = [...selectedPageIds.value];
  moveDialog.folderId = query.folderId > 0 ? query.folderId : 0;
  moveDialog.open = true;
}
function openRecycle() {
  recycleDialog.open = true;
  recycleDialog.query.pageNum = 1;
  loadRecycle();
}
function loadRecycle() {
  recycleDialog.loading = true;
  listDashboardPageRecycle(recycleDialog.query)
    .then((res) => {
      recycleDialog.rows = res.rows || [];
      recycleDialog.total = res.total || 0;
    })
    .finally(() => {
      recycleDialog.loading = false;
    });
}
function restore(row) {
  ElMessageBox.confirm(
    `恢复页面“${row.pageName}”吗？页面会回到原文件夹。`,
    "恢复页面",
    { type: "info", confirmButtonText: "恢复" },
  )
    .then(() => restoreDashboardPage(row.pageId))
    .then(() => {
      ElMessage.success("页面已恢复");
      loadRecycle();
      load();
    })
    .catch(() => {});
}
function purge(row) {
  ElMessageBox.confirm(
    `彻底删除“${row.pageName}”后不可恢复，确认继续吗？`,
    "彻底删除",
    { type: "error", confirmButtonText: "彻底删除" },
  )
    .then(() => purgeDashboardPage(row.pageId))
    .then(() => {
      ElMessage.success("页面已彻底删除");
      loadRecycle();
    })
    .catch(() => {});
}
function purgeRecycle() {
  ElMessageBox.confirm(
    "清空回收站后所有页面、版本和分享记录都不可恢复，确认继续吗？",
    "清空回收站",
    { type: "error", confirmButtonText: "清空回收站" },
  )
    .then(() => purgeDashboardPageRecycle())
    .then(() => {
      ElMessage.success("回收站已清空");
      loadRecycle();
      load();
    })
    .catch(() => {});
}
onMounted(() => {
  refreshPageFilters().catch(() => {});
});
</script>

<style scoped>
.dashboard-page :deep(.tree-sidebar) {
  background: var(--el-bg-color, #fff);
  border-color: var(--el-border-color-light, #e8eaed);
}
.dashboard-page :deep(.tree-header) {
  background: var(--el-bg-color, #fff);
  border-color: var(--el-border-color-light, #e8eaed);
}
.dashboard-page :deep(.tree-title),
.dashboard-page :deep(.tree-node) {
  color: var(--el-text-color-primary, #303133);
}
.dashboard-page :deep(.node-label) {
  overflow: hidden;
  text-overflow: ellipsis;
}
.page-name-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}
.page-name-cell strong {
  display: block;
  color: #263448;
  font-size: 13px;
  font-weight: 650;
}
.page-name-cell small {
  display: block;
  color: #a2adba;
  font-size: 10px;
  margin-top: 4px;
}
.page-actions {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  white-space: nowrap;
}
.page-actions :deep(.el-button) {
  margin-left: 0;
}
.page-actions :deep(.el-icon) {
  margin-left: 3px;
  vertical-align: middle;
}
.page-mark {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #0c9a85;
  background: #dcf8f0;
}
.page-mark.deleted {
  color: #b7766f;
  background: #fff0ed;
}
.folder-pill {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 128px;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  color: #64758a;
  font-size: 11px;
}
.folder-pill .el-icon {
  color: #d3a24e;
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
.dot-disabled {
  background: #d87373;
}
.version-pill {
  color: #25836f;
  background: #effaf7;
  border: 1px solid #c6ebe1;
  border-radius: 4px;
  padding: 3px 7px;
  font-size: 10px;
}
.muted {
  color: #a4afbb;
  font-size: 11px;
}
.table-empty .el-icon {
  font-size: 30px;
  color: #b8c4d0;
}
.version-drawer-head {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 0 0 16px;
  border-bottom: 1px solid #eef1f4;
}
.version-drawer-head strong {
  color: #263448;
  font-size: 15px;
}
.version-drawer-head span {
  color: #9aa7b5;
  font-size: 11px;
}
.version-list {
  padding-top: 10px;
}
.version-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 4px;
  border-bottom: 1px solid #f0f2f5;
}
.version-row.current {
  background: #f4fbf9;
  margin: 0 -8px;
  padding-left: 12px;
  padding-right: 12px;
  border-radius: 6px;
}
.version-title {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #344256;
  font-size: 12px;
}
.version-row small {
  display: block;
  color: #a2adba;
  font-size: 10px;
  margin-top: 8px;
}
.version-row p {
  margin: 6px 0 0;
  color: #8896a5;
  font-size: 10px;
}
.version-actions {
  flex: 0 0 auto;
  display: flex;
  align-items: flex-start;
}
.drawer-empty {
  padding: 50px 0;
  text-align: center;
  color: #a2adba;
}
.recycle-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
  color: #8996a5;
  font-size: 11px;
}
.recycle-toolbar .el-input {
  width: 240px;
}
.recycle-toolbar > div {
  display: flex;
  gap: 8px;
}
.share-create-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  color: #6b7889;
  font-size: 12px;
  margin-bottom: 14px;
}
.menu-config-descriptions {
  margin-top: 16px;
}
.menu-config-descriptions :deep(.el-descriptions__label) {
  width: 110px;
  white-space: nowrap;
}
.menu-config-value {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-width: 0;
}
.menu-config-value code {
  overflow-wrap: anywhere;
  color: var(--el-text-color-primary);
  font-size: 12px;
}
.share-create-row .el-input-number {
  width: 130px;
}
.share-expiry-mode {
  width: 120px;
}
.share-expiry-unit {
  width: 90px;
}
.share-permanent-note {
  min-width: 150px;
  color: var(--el-text-color-secondary);
}
.share-pages-row {
  flex: 1 1 100%;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding-top: 4px;
}
.share-pages-tree-select {
  flex: 1 1 420px;
  min-width: 300px;
}
.share-pages-loading {
  color: var(--el-text-color-secondary);
  font-size: 11px;
}
.share-pages-hint {
  flex: 1 1 100%;
  color: var(--el-text-color-secondary);
  font-size: 11px;
  line-height: 18px;
}
.permanent-share {
  color: var(--el-color-success);
  font-weight: 600;
}
.share-alert {
  margin-bottom: 12px;
}
.share-url-row {
  display: flex;
  gap: 8px;
  margin-bottom: 18px;
}
.share-url-row .el-input {
  flex: 1;
}
.share-list-title {
  color: #4b5a6d;
  font-size: 13px;
  font-weight: 650;
  margin: 5px 0 10px;
}
.share-address-cell {
  display: flex;
  align-items: center;
  gap: 6px;
}
.share-address-cell .el-input {
  min-width: 220px;
}
.share-address-missing {
  color: var(--el-text-color-secondary);
  font-size: 11px;
}
.template-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}
.template-card {
  min-height: 240px;
  padding: 14px;
  border: 1px solid #e5ebf2;
  border-radius: 10px;
  background: #fff;
  text-align: left;
  cursor: pointer;
  transition:
    border-color 0.16s,
    box-shadow 0.16s,
    transform 0.16s;
}
.template-card:hover {
  border-color: #9ddccd;
  box-shadow: 0 8px 22px rgba(38, 79, 97, 0.1);
  transform: translateY(-2px);
}
.template-card:disabled {
  cursor: wait;
  opacity: 0.65;
}
.template-thumb {
  height: 142px;
  margin-bottom: 13px;
  padding: 13px;
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  grid-template-rows: repeat(3, 1fr);
  gap: 6px;
  border-radius: 7px;
  background: linear-gradient(145deg, #111e31, #1d3850);
}
.template-thumb span {
  min-width: 0;
  border: 1px solid rgba(96, 213, 188, 0.45);
  border-radius: 4px;
  background: linear-gradient(
    135deg,
    rgba(68, 212, 175, 0.32),
    rgba(44, 95, 137, 0.45)
  );
}
.template-thumb span:nth-child(1) {
  grid-column: span 1;
}
.template-thumb span:nth-child(2) {
  grid-column: span 3;
  grid-row: span 2;
}
.template-thumb span:nth-child(3) {
  grid-column: span 1;
  grid-row: span 2;
}
.template-thumb span:nth-child(4) {
  grid-column: span 3;
}
.template-blank {
  background: repeating-linear-gradient(
      0deg,
      #f2f5f8 0 1px,
      transparent 1px 28px
    ),
    repeating-linear-gradient(90deg, #f2f5f8 0 1px, transparent 1px 42px);
  border: 1px dashed #cbd6e0;
}
.template-blank span {
  display: none;
}
.template-card strong {
  display: block;
  color: #2d3b4e;
  font-size: 14px;
}
.template-card p {
  margin: 6px 0;
  color: #8793a2;
  font-size: 11px;
}
.template-card small {
  color: #a6b0bc;
  font-size: 10px;
}
.template-note {
  margin: 18px 0 0;
  color: #8a97a5;
  font-size: 11px;
  line-height: 1.6;
}
@media (max-width: 900px) {
  .dashboard-page :deep(.tree-sidebar:not(.collapsed)) {
    min-width: 180px;
  }
  .dashboard-page {
    padding: 18px 12px;
  }
  .recycle-toolbar {
    align-items: stretch;
    flex-direction: column;
  }
  .recycle-toolbar .el-input {
    width: 100%;
  }
  .recycle-toolbar > div {
    justify-content: flex-end;
  }
}
</style>
