<template>
  <div class="resource-page app-container dashboard-management-page">
    <el-tabs v-model="activeTab" class="resource-tabs dashboard-management-tabs dashboard-management-navigation" @tab-change="onResourceTabChange">
      <el-tab-pane name="assets"><template #label><span class="tab-label"><el-icon><Picture /></el-icon>图库与视频</span></template></el-tab-pane>
      <el-tab-pane name="maps"><template #label><span class="tab-label"><el-icon><Location /></el-icon>地图配置</span></template></el-tab-pane>
    </el-tabs>
    <div class="dashboard-management-tree-layout">
    <DataFolderSidebar
      v-model="currentResourceQuery.folderId"
      v-model:include-children="currentResourceQuery.includeChildren"
      scope="resource"
      :folders="folders"
      :all-label="activeTab === 'maps' ? '全部地图' : '全部图库与视频'"
      :can-manage="canManageFolders"
      @change="handleResourceFilter"
      @refresh="refreshResourceFilters"
      @manage="folderDialogOpen = true"
    />
    <div class="dashboard-management-main">
      <div v-show="activeTab === 'assets'">
        <ManagementToolbar
          title="图库与视频"
          :total="assetTotal"
          noun="个资源"
          :selection-count="selectedAssetIds.length"
          :active-filter="activeResourceFilterLabel"
          help-module="assets"
          :loading="assetLoading"
          @search="queryAssets"
          @reset="resetAssetQuery"
          @refresh="refreshResourceFilters"
          @clear-filter="clearResourceFilter"
        >
          <template #filters>
            <el-form-item label="搜索" class="filter-wide"><el-input v-model="assetQuery.keyword" clearable placeholder="编码、名称或分类" /></el-form-item>
            <el-form-item label="类型"><el-select v-model="assetQuery.assetType" clearable placeholder="全部类型" @change="queryAssets"><el-option label="图片" value="IMAGE" /><el-option label="视频" value="VIDEO" /></el-select></el-form-item>
          </template>
          <template #selection><el-button v-if="canMoveResources" plain @click="openResourceMove('asset')">移动到文件夹</el-button></template>
          <template #primary>
            <el-upload
              v-hasPermi="['dashboard:resource:edit']"
              :action="uploadUrl"
              :headers="uploadHeaders"
              :show-file-list="false"
              :accept="assetAccept"
              :before-upload="beforeAssetUpload"
              :on-success="handleAssetUpload"
              :on-error="handleUploadError"
            ><el-button type="primary" :icon="Upload">上传资源</el-button></el-upload>
          </template>
        </ManagementToolbar>
        <section class="resource-table-wrap dashboard-management-table">
          <el-table ref="assetTableRef" v-loading="assetLoading" :data="assets" row-key="assetId" @selection-change="selectedAssetIds = $event.map(row => row.assetId)">
            <el-table-column v-if="canMoveResources" type="selection" width="48" />
            <el-table-column label="预览" width="94"
              ><template #default="scope"
                ><button
                  type="button"
                  class="asset-preview"
                  :aria-label="`预览资源${scope.row.assetName}`"
                  @click="previewAsset(scope.row)"
                >
                  <img
                    v-if="scope.row.assetType === 'IMAGE'"
                    :src="dashboardResourceUrl(scope.row.resourcePath)"
                    :alt="scope.row.assetName"
                  /><el-icon v-else
                    ><VideoCamera
                  /></el-icon></button></template></el-table-column
            ><el-table-column label="资源" min-width="220"
              ><template #default="scope"
                ><strong>{{ scope.row.assetName }}</strong
                ><small>{{ scope.row.assetCode }}</small></template
              ></el-table-column
            ><el-table-column label="文件夹" width="140"
              ><template #default="scope"
                ><span class="folder-pill"
                  ><el-icon><Folder /></el-icon
                  >{{ scope.row.folderName || "未分类" }}</span
                ></template
              ></el-table-column
            ><el-table-column label="类型" width="90"
              ><template #default="scope"
                ><el-tag effect="plain">{{
                  scope.row.assetType === "IMAGE" ? "图片" : "视频"
                }}</el-tag></template
              ></el-table-column
            ><el-table-column prop="category" label="标签分类" width="120"
              ><template #default="scope">{{
                scope.row.category || "-"
              }}</template></el-table-column
            ><el-table-column
              prop="resourcePath"
              show-overflow-tooltip
              label="平台路径"
              min-width="250"
            /><el-table-column prop="status" label="状态" width="90"
              ><template #default="scope"
                ><el-tag
                  :type="scope.row.status === '0' ? 'success' : 'info'"
                  >{{ scope.row.status === "0" ? "正常" : "停用" }}</el-tag
                ></template
              ></el-table-column
            ><el-table-column label="操作" width="190" fixed="right"
              ><template #default="scope"
                ><el-button link type="primary" @click="previewAsset(scope.row)"
                  >预览</el-button
                ><el-button
                  v-hasPermi="['dashboard:resource:edit']"
                  link
                  type="primary"
                  @click="editAsset(scope.row)"
                  >编辑</el-button
                ><el-button
                  v-hasPermi="['dashboard:resource:delete']"
                  link
                  type="danger"
                  @click="removeAsset(scope.row)"
                  >删除</el-button
                ></template
              ></el-table-column
            ><template #empty><el-empty v-if="!assetLoading" description="暂无符合条件的图库或视频资源" :image-size="88" /></template></el-table>
          <pagination
            v-show="assetTotal > 0"
            v-model:page="assetQuery.pageNum"
            v-model:limit="assetQuery.pageSize"
            :total="assetTotal"
            @pagination="loadAssets"
          />
        </section>
      </div>
      <div v-show="activeTab === 'maps'">
        <ManagementToolbar
          title="地图配置"
          :total="mapTotal"
          noun="个地图"
          :selection-count="selectedMapIds.length"
          :active-filter="activeResourceFilterLabel"
          help-module="maps"
          :loading="mapLoading"
          @search="queryMaps"
          @reset="resetMapQuery"
          @refresh="refreshResourceFilters"
          @clear-filter="clearResourceFilter"
        >
          <template #filters>
            <el-form-item label="搜索" class="filter-wide"><el-input v-model="mapQuery.keyword" clearable placeholder="地图编码或名称" /></el-form-item>
          </template>
          <template #selection><el-button v-if="canMoveResources" plain @click="openResourceMove('map')">移动到文件夹</el-button></template>
          <template #primary><el-button v-hasPermi="['dashboard:resource:edit']" type="primary" :icon="Plus" @click="openMapCreate">新增地图</el-button></template>
        </ManagementToolbar>
        <section class="resource-table-wrap dashboard-management-table">
          <el-table ref="mapTableRef" v-loading="mapLoading" :data="maps" row-key="mapCode" @selection-change="selectedMapIds = $event.filter(isMovableMap).map(row => row.mapId)">
            <el-table-column v-if="canMoveResources" type="selection" width="48" :selectable="isMovableMap" />
            <el-table-column label="地图" min-width="240"
              ><template #default="scope"
                ><strong>{{ scope.row.mapName }}</strong
                ><small>{{ scope.row.mapCode }}</small></template
              ></el-table-column
            ><el-table-column label="文件夹" width="140"
              ><template #default="scope"
                ><span class="folder-pill"
                  ><el-icon><Folder /></el-icon
                  >{{ scope.row.folderName || "未分类" }}</span
                ></template
              ></el-table-column
            ><el-table-column label="来源" width="100"
              ><template #default="scope"
                ><el-tag :type="scope.row.builtin ? 'info' : 'success'">{{
                  scope.row.builtin ? "内置" : "已登记"
                }}</el-tag></template
              ></el-table-column
            ><el-table-column prop="jsonSize" label="GeoJSON 大小" width="130"
              ><template #default="scope">{{
                scope.row.builtin ? "-" : `${scope.row.jsonSize || 0} 字符`
              }}</template></el-table-column
            ><el-table-column
              prop="resourcePath"
              show-overflow-tooltip
              label="平台路径"
              min-width="280"
            /><el-table-column label="操作" width="200" fixed="right"
              ><template #default="scope"
                ><el-button link type="primary" @click="previewMap(scope.row)"
                  >预览</el-button
                ><el-button
                  v-if="!scope.row.builtin"
                  v-hasPermi="['dashboard:resource:edit']"
                  link
                  type="primary"
                  @click="editMap(scope.row)"
                  >编辑</el-button
                ><el-button
                  v-if="!scope.row.builtin"
                  v-hasPermi="['dashboard:resource:delete']"
                  link
                  type="danger"
                  @click="removeMap(scope.row)"
                  >删除</el-button
                ></template
              ></el-table-column
            ><template #empty><el-empty v-if="!mapLoading" description="暂无符合条件的地图资源" :image-size="88" /></template></el-table>
          <pagination
            v-show="mapTotal > 0"
            v-model:page="mapQuery.pageNum"
            v-model:limit="mapQuery.pageSize"
            :total="mapTotal"
            @pagination="loadMaps"
          />
        </section>
      </div>
      </div>
    </div>

    <DataFolderManager v-model="folderDialogOpen" scope="resource" :folders="folders" :default-parent-id="currentResourceQuery.folderId > 0 ? currentResourceQuery.folderId : 0" @changed="refreshResourceFilters" />
    <DataFolderMoveDialog v-model="moveDialog.open" scope="resource" :folders="folders" :ids="moveDialog.ids" :resource-type="moveDialog.resourceType" :default-folder-id="moveDialog.folderId" @moved="refreshResourceFilters" />
    <el-dialog
      v-model="assetPreview.open"
      :title="
        assetPreview.row
          ? `资源预览 · ${assetPreview.row.assetName}`
          : '资源预览'
      "
      width="min(920px, 92vw)"
      top="5vh"
      destroy-on-close
      @closed="closeAssetPreview"
      ><div v-if="assetPreview.row" class="preview-dialog">
        <div class="media-preview-stage">
          <div v-if="assetPreview.error" class="preview-empty">
            <el-icon><WarningFilled /></el-icon><strong>资源无法预览</strong
            ><span>{{ assetPreview.error }}</span>
          </div>
          <img
            v-else-if="assetPreview.row.assetType === 'IMAGE'"
            :src="dashboardResourceUrl(assetPreview.row.resourcePath)"
            :alt="assetPreview.row.assetName"
            @error="handleAssetPreviewError"
          /><video
            v-else
            :src="dashboardResourceUrl(assetPreview.row.resourcePath)"
            controls
            playsinline
            preload="metadata"
            @error="handleAssetPreviewError"
          >
            当前浏览器不支持视频预览。
          </video>
        </div>
        <div class="preview-meta">
          <span><b>文件夹</b>{{ assetPreview.row.folderName || "根目录" }}</span
          ><span
            ><b>类型</b
            >{{
              assetPreview.row.assetType === "IMAGE" ? "图片" : "视频"
            }}</span
          ><span><b>标签分类</b>{{ assetPreview.row.category || "-" }}</span
          ><span
            ><b>状态</b
            >{{ assetPreview.row.status === "0" ? "正常" : "停用" }}</span
          ><span class="preview-path"
            ><b>平台路径</b>{{ assetPreview.row.resourcePath }}</span
          >
        </div>
        <p
          v-if="
            assetPreview.row.assetType === 'VIDEO' &&
            isStreamingAsset(assetPreview.row)
          "
          class="preview-note"
        >
          M3U8 视频能否播放取决于当前浏览器是否原生支持该格式。
        </p>
      </div></el-dialog
    >
    <el-dialog
      v-model="mapPreview.open"
      :title="
        mapPreview.row ? `地图预览 · ${mapPreview.row.mapName}` : '地图预览'
      "
      width="min(960px, 94vw)"
      top="4vh"
      destroy-on-close
      @opened="handleMapPreviewOpened"
      @closed="closeMapPreview"
      ><div v-if="mapPreview.row" class="preview-dialog">
        <div
          class="map-preview-stage"
          v-loading="mapPreview.loading"
          element-loading-text="地图资源加载中"
        >
          <div ref="mapPreviewHost" class="map-preview-canvas"></div>
          <div v-if="mapPreview.error" class="preview-empty map-preview-empty">
            <el-icon><WarningFilled /></el-icon><strong>地图无法预览</strong
            ><span>{{ mapPreview.error }}</span>
          </div>
        </div>
        <div class="preview-meta map-preview-meta">
          <span
            ><b>来源</b
            >{{ mapPreview.row.builtin ? "内置地图" : "已登记地图" }}</span
          ><span><b>区域数量</b>{{ mapPreview.featureCount }}</span
          ><span class="preview-path"
            ><b>平台路径</b>{{ mapPreview.row.resourcePath }}</span
          >
        </div>
        <p v-if="!mapPreview.error" class="preview-note">
          可使用鼠标滚轮缩放地图，按住鼠标拖动查看其他区域。
        </p>
      </div></el-dialog
    >
    <el-dialog
      v-model="assetEditor.open"
      :title="assetEditor.editing ? '编辑资源' : '登记上传资源'"
      width="640px"
      destroy-on-close
      ><el-form
        ref="assetFormRef"
        :model="assetEditor.form"
        :rules="assetRules"
        label-position="top"
        ><div class="form-grid">
          <el-form-item label="资源编码" prop="assetCode"
            ><el-input
              v-model="assetEditor.form.assetCode"
              :disabled="assetEditor.editing"
              placeholder="例如 project-logo" /></el-form-item
          ><el-form-item label="资源名称" prop="assetName"
            ><el-input
              v-model="assetEditor.form.assetName"
              placeholder="设计器中显示的名称" /></el-form-item
          ><el-form-item label="文件夹"
            ><el-tree-select v-model="assetEditor.form.folderId" :data="folderOptions" check-strictly :render-after-expand="false" default-expand-all filterable style="width: 100%" /></el-form-item
          ><el-form-item label="类型" prop="assetType"
            ><el-select v-model="assetEditor.form.assetType"
              ><el-option label="图片" value="IMAGE" /><el-option
                label="视频"
                value="VIDEO" /></el-select></el-form-item
          ><el-form-item label="标签分类"
            ><el-input
              v-model="assetEditor.form.category"
              placeholder="例如 logo、背景、宣传"
          /></el-form-item>
        </div>
        <el-form-item label="平台资源路径" prop="resourcePath"
              show-overflow-tooltip
          ><el-input
            v-model="assetEditor.form.resourcePath"
            placeholder="上传后自动填充 /profile/ 路径"
          />
          <p class="field-hint">
            只允许平台 /profile/ 资源；不能填写外部 URL。
          </p></el-form-item
        ><el-form-item label="状态"
          ><el-select v-model="assetEditor.form.status"
            ><el-option label="正常" value="0" /><el-option
              label="停用"
              value="1" /></el-select></el-form-item
        ><el-form-item label="备注"
          ><el-input
            v-model="assetEditor.form.remark"
            type="textarea"
            :rows="2"
            maxlength="500" /></el-form-item></el-form
      ><template #footer
        ><el-button @click="assetEditor.open = false">取消</el-button
        ><el-button
          type="primary"
          :loading="assetEditor.saving"
          @click="submitAsset"
          >保存</el-button
        ></template
      ></el-dialog
    >
    <el-dialog
      v-model="mapEditor.open"
      :title="mapEditor.editing ? '编辑地图' : '新增地图'"
      width="900px"
      top="4vh"
      destroy-on-close
      ><el-form
        ref="mapFormRef"
        :model="mapEditor.form"
        :rules="mapRules"
        label-position="top"
        ><div class="form-grid">
          <el-form-item label="地图编码" prop="mapCode"
            ><el-input
              v-model="mapEditor.form.mapCode"
              :disabled="mapEditor.editing"
              placeholder="例如 xinghua-region" /></el-form-item
          ><el-form-item label="地图名称" prop="mapName"
            ><el-input
              v-model="mapEditor.form.mapName"
              placeholder="例如 项目区域" /></el-form-item
          ><el-form-item label="文件夹"
            ><el-tree-select v-model="mapEditor.form.folderId" :data="folderOptions" check-strictly :render-after-expand="false" default-expand-all filterable style="width: 100%" /></el-form-item
          ><el-form-item label="状态"
            ><el-select v-model="mapEditor.form.status"
              ><el-option label="正常" value="0" /><el-option
                label="停用"
                value="1" /></el-select
          ></el-form-item>
        </div>
        <el-form-item label="GeoJSON" prop="geojsonJson"
          ><el-input
            v-model="mapEditor.form.geojsonJson"
            type="textarea"
            :rows="18"
            spellcheck="false"
            placeholder='必须是 FeatureCollection JSON，例如 {"type":"FeatureCollection","features":[]}'
          />
          <p class="field-hint">
            服务端会校验
            JSON、FeatureCollection、大小上限和脚本内容；地图发布后通过
            /dashboard/assets/map/{mapCode}.json 只读访问。
          </p></el-form-item
        ><el-form-item label="备注"
          ><el-input
            v-model="mapEditor.form.remark"
            type="textarea"
            :rows="2"
            maxlength="500" /></el-form-item></el-form
      ><template #footer
        ><el-button @click="mapEditor.open = false">取消</el-button
        ><el-button
          type="primary"
          :loading="mapEditor.saving"
          @click="submitMap"
          >保存地图</el-button
        ></template
      ></el-dialog
    >
  </div>
</template>

<script setup>
import ManagementToolbar from "@/components/DashboardManagement/ManagementToolbar.vue";
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  reactive,
  ref,
} from "vue";
import * as echarts from "echarts";
import { ElMessage, ElMessageBox } from "element-plus";
import DataFolderSidebar from "@/components/DashboardDataManagement/DataFolderSidebar.vue";
import DataFolderManager from "@/components/DashboardDataManagement/DataFolderManager.vue";
import DataFolderMoveDialog from "@/components/DashboardDataManagement/DataFolderMoveDialog.vue";
import { listDataFolders } from "@/api/dashboardDataFolder";
import { buildDataFolderOptions, normalizeDataFolders, dataFolderName } from "@/utils/dashboardDataFolder";
import { checkPermi } from "@/utils/permission";
import {
  Folder,
  Plus,
  Upload,
  VideoCamera,
  WarningFilled,
  Picture,
  Location,
} from "@element-plus/icons-vue";
import {
  addDashboardAsset,
  addDashboardMap,
  delDashboardAsset,
  delDashboardMap,
  getDashboardMap,
  pageDashboardAssets,
  pageDashboardMaps,
  updateDashboardAsset,
  updateDashboardMap,
} from "@/api/dashboard";
import { getToken } from "@/utils/auth";
import { dashboardResourceUrl } from "@/utils/dashboard";

const activeTab = ref("assets");
const assets = ref([]);
const maps = ref([]);
const folders = ref([]);
const assetLoading = ref(false);
const mapLoading = ref(false);
const assetQuery = reactive({ pageNum: 1, pageSize: 10, keyword: "", assetType: "", folderId: null, includeChildren: false });
const assetTotal = ref(0);
let assetRequestId = 0;
const mapQuery = reactive({ pageNum: 1, pageSize: 10, keyword: "", folderId: null, includeChildren: false });
const mapTotal = ref(0);
let mapRequestId = 0;
const currentResourceQuery = computed(() => activeTab.value === "maps" ? mapQuery : assetQuery);
const folderOptions = computed(() => buildDataFolderOptions(folders.value));
const canManageFolders = computed(() => checkPermi(["dashboard:resource:folder"]));
const canMoveResources = computed(() => checkPermi(["dashboard:resource:edit"]));
const assetTableRef = ref();
const mapTableRef = ref();
const selectedAssetIds = ref([]);
const selectedMapIds = ref([]);
const uploadFolderIds = new Map();
const uploadUrl = `${import.meta.env.VITE_APP_BASE_API || "/dev-api"}/common/upload`;
const uploadHeaders = { Authorization: `Bearer ${getToken() || ""}` };
const assetAccept = ".png,.jpg,.jpeg,.webp,.gif,.svg,.mp4,.webm,.ogg,.m3u8";
const assetFormRef = ref();
const mapFormRef = ref();
const mapPreviewHost = ref();
const assetEditor = reactive({
  open: false,
  editing: false,
  saving: false,
  form: emptyAsset(),
});
const mapEditor = reactive({
  open: false,
  editing: false,
  saving: false,
  form: emptyMap(),
});
const folderDialogOpen = ref(false);
const moveDialog = reactive({ open: false, ids: [], folderId: 0, resourceType: "asset" });
const assetPreview = reactive({ open: false, row: null, error: "" });
const mapPreview = reactive({
  open: false,
  row: null,
  loading: false,
  error: "",
  featureCount: 0,
});
let mapPreviewChart = null;
let mapPreviewGeoJson = null;
let mapPreviewRequestId = 0;

const activeResourceFilterLabel = computed(() => {
  const labels = [];
  const current = currentResourceQuery.value;
  if (current.folderId != null) labels.push(dataFolderName(folders.value, current.folderId));
  if (activeTab.value === "assets" && assetQuery.assetType) labels.push(assetQuery.assetType === "IMAGE" ? "图片" : "视频");
  return labels.join(" · ");
});
const assetRules = {
  assetCode: [{ required: true, message: "资源编码不能为空", trigger: "blur" }],
  assetName: [{ required: true, message: "资源名称不能为空", trigger: "blur" }],
  assetType: [{ required: true, message: "请选择资源类型", trigger: "change" }],
  resourcePath: [
    { required: true, message: "平台资源路径不能为空", trigger: "blur" },
  ],
};
const mapRules = {
  mapCode: [{ required: true, message: "地图编码不能为空", trigger: "blur" }],
  mapName: [{ required: true, message: "地图名称不能为空", trigger: "blur" }],
  geojsonJson: [
    { required: true, message: "GeoJSON 不能为空", trigger: "blur" },
  ],
};
function emptyAsset() {
  return {
    assetCode: "",
    assetName: "",
    folderId: 0,
    assetType: "IMAGE",
    category: "",
    resourcePath: "",
    status: "0",
    remark: "",
  };
}
function emptyMap() {
  return {
    mapCode: "",
    mapName: "",
    folderId: 0,
    geojsonJson: '{\n  "type": "FeatureCollection",\n  "features": []\n}',
    status: "0",
    remark: "",
  };
}
function loadFolders() {
  return listDataFolders("resource").then((res) => {
    folders.value = normalizeDataFolders(res.data || res.rows || []);
    for (const current of [assetQuery, mapQuery]) {
      if (current.folderId > 0 && !folders.value.some(folder => folder.folderId === Number(current.folderId))) {
        current.folderId = null;
        current.pageNum = 1;
      }
    }
  });
}
async function refreshResourceFilters() {
  await loadFolders();
  return Promise.all([loadAssets(), loadMaps()]);
}
function loadAssets() {
  const requestId = ++assetRequestId;
  assetLoading.value = true;
  selectedAssetIds.value = [];
  assetTableRef.value?.clearSelection();
  return pageDashboardAssets({ ...assetQuery })
    .then((res) => {
      if (requestId !== assetRequestId) return;
      assetTotal.value = Number(res.total) || 0;
      const lastPage = Math.max(1, Math.ceil(assetTotal.value / assetQuery.pageSize));
      if (assetQuery.pageNum > lastPage) {
        assetQuery.pageNum = lastPage;
        return loadAssets();
      }
      assets.value = res.rows || [];
    })
    .finally(() => {
      if (requestId === assetRequestId) assetLoading.value = false;
    });
}
function loadMaps() {
  const requestId = ++mapRequestId;
  mapLoading.value = true;
  selectedMapIds.value = [];
  mapTableRef.value?.clearSelection();
  return pageDashboardMaps({ ...mapQuery })
    .then((res) => {
      if (requestId !== mapRequestId) return;
      mapTotal.value = Number(res.total) || 0;
      const lastPage = Math.max(1, Math.ceil(mapTotal.value / mapQuery.pageSize));
      if (mapQuery.pageNum > lastPage) {
        mapQuery.pageNum = lastPage;
        return loadMaps();
      }
      maps.value = res.rows || [];
    })
    .finally(() => {
      if (requestId === mapRequestId) mapLoading.value = false;
    });
}
function queryAssets() {
  assetQuery.pageNum = 1;
  return loadAssets();
}
function queryMaps() {
  mapQuery.pageNum = 1;
  return loadMaps();
}
function handleResourceFilter() {
  return activeTab.value === "maps" ? queryMaps() : queryAssets();
}
function onResourceTabChange() {
  selectedAssetIds.value = [];
  selectedMapIds.value = [];
  assetTableRef.value?.clearSelection();
  mapTableRef.value?.clearSelection();
}
function clearResourceFilter() {
  currentResourceQuery.value.folderId = null;
  currentResourceQuery.value.includeChildren = false;
  if (activeTab.value === "assets") assetQuery.assetType = "";
  return handleResourceFilter();
}
function resetAssetQuery() {
  assetQuery.keyword = "";
  return clearResourceFilter();
}
function resetMapQuery() {
  mapQuery.keyword = "";
  return clearResourceFilter();
}
function isMovableMap(row) {
  return !row.builtin && Number(row.mapId) > 0;
}
function openResourceMove(resourceType) {
  if (!canMoveResources.value) return;
  const ids = resourceType === "map" ? selectedMapIds.value : selectedAssetIds.value;
  if (!ids.length) return;
  moveDialog.ids = [...ids];
  moveDialog.resourceType = resourceType;
  const folderId = resourceType === "map" ? mapQuery.folderId : assetQuery.folderId;
  moveDialog.folderId = folderId > 0 ? folderId : 0;
  moveDialog.open = true;
}
function beforeAssetUpload(file) {
  const image = /^image\/(png|jpeg|webp|gif|svg\+xml)$/.test(file.type);
  const video =
    /^(video\/(mp4|webm|ogg)|application\/vnd.apple.mpegurl|application\/x-mpegurl)$/.test(
      file.type,
    ) || /\.(m3u8)$/i.test(file.name);
  if (!image && !video) {
    ElMessage.warning("仅支持 PNG/JPG/WEBP/GIF/SVG 或 MP4/WEBM/OGG/M3U8");
    return false;
  }
  if (file.size > 100 * 1024 * 1024) {
    ElMessage.warning("资源大小不能超过 100 MB");
    return false;
  }
  uploadFolderIds.set(file.uid, assetQuery.folderId > 0 ? assetQuery.folderId : 0);
  return true;
}
function handleAssetUpload(response, file) {
  const folderId = uploadFolderIds.get(file.uid) ?? (assetQuery.folderId > 0 ? assetQuery.folderId : 0);
  uploadFolderIds.delete(file.uid);
  if (response?.code !== 200 || !response.fileName) {
    ElMessage.error(response?.msg || "上传失败");
    return;
  }
  const isVideo =
    /^video\//.test(file.type) || /\.(mp4|webm|ogg|m3u8)$/i.test(file.name);
  assetEditor.editing = false;
  assetEditor.form = {
    ...emptyAsset(),
    folderId,
    assetCode: `${isVideo ? "video" : "image"}-${Date.now()}`,
    assetName: file.name.replace(/\.[^.]+$/, ""),
    assetType: isVideo ? "VIDEO" : "IMAGE",
    resourcePath: response.fileName,
    mimeType: file.type,
    fileSize: file.size,
  };
  assetEditor.open = true;
}
function handleUploadError(_error, file) {
  if (file) uploadFolderIds.delete(file.uid);
  ElMessage.error("上传失败，请检查登录状态和文件大小");
}
function previewAsset(row) {
  assetPreview.row = { ...row };
  assetPreview.error = dashboardResourceUrl(row.resourcePath)
    ? ""
    : "资源路径不受支持";
  assetPreview.open = true;
}
function closeAssetPreview() {
  assetPreview.row = null;
  assetPreview.error = "";
}
function handleAssetPreviewError() {
  assetPreview.error =
    assetPreview.row?.assetType === "IMAGE"
      ? "图片加载失败，请检查资源文件是否仍然存在。"
      : "视频加载失败，可能是文件不存在、编码格式不受支持或浏览器无法播放该格式。";
}
function isStreamingAsset(row) {
  return /\.m3u8(?:$|[?#])/i.test(String(row?.resourcePath || ""));
}
function editAsset(row) {
  assetEditor.editing = true;
  assetEditor.form = { ...emptyAsset(), ...row, folderId: Number(row.folderId) || 0 };
  assetEditor.open = true;
}
function submitAsset() {
  assetFormRef.value.validate((valid) => {
    if (!valid) return;
    assetEditor.saving = true;
    const action = assetEditor.editing
      ? updateDashboardAsset(assetEditor.form)
      : addDashboardAsset(assetEditor.form);
    action
      .then(() => {
        ElMessage.success("资源已保存");
        assetEditor.open = false;
        loadAssets();
      })
      .finally(() => {
        assetEditor.saving = false;
      });
  });
}
function removeAsset(row) {
  ElMessageBox.confirm(
    `确定删除资源“${row.assetName}”吗？已引用页面中的路径不会自动替换。`,
    "删除资源",
    { type: "warning" },
  )
    .then(() => delDashboardAsset(row.assetId))
    .then(() => {
      ElMessage.success("资源已删除");
      loadAssets();
    })
    .catch(() => {});
}
function openMapCreate() {
  mapEditor.editing = false;
  mapEditor.form = { ...emptyMap(), folderId: mapQuery.folderId > 0 ? mapQuery.folderId : 0 };
  mapEditor.open = true;
}
async function previewMap(row) {
  const requestId = ++mapPreviewRequestId;
  disposeMapPreview();
  mapPreviewGeoJson = null;
  mapPreview.row = { ...row };
  mapPreview.loading = true;
  mapPreview.error = "";
  mapPreview.featureCount = 0;
  mapPreview.open = true;
  try {
    const url = dashboardResourceUrl(row.resourcePath);
    if (!url) throw new Error("地图资源路径不受支持");
    const response = await fetch(url, {
      headers: { Accept: "application/geo+json, application/json" },
      credentials: "same-origin",
    });
    if (!response.ok) throw new Error(`地图资源加载失败（${response.status}）`);
    const geoJson = await response.json();
    if (
      !geoJson ||
      geoJson.type !== "FeatureCollection" ||
      !Array.isArray(geoJson.features)
    )
      throw new Error("地图 GeoJSON 结构不合法");
    const drawableFeatures = geoJson.features.filter((feature) =>
      ["Polygon", "MultiPolygon"].includes(feature?.geometry?.type),
    );
    if (!drawableFeatures.length) throw new Error("地图中没有可绘制的区域数据");
    if (requestId !== mapPreviewRequestId || !mapPreview.open) return;
    mapPreviewGeoJson = { ...geoJson, features: drawableFeatures };
    mapPreview.featureCount = drawableFeatures.length;
    mapPreview.loading = false;
    await nextTick();
    renderMapPreview();
  } catch (error) {
    if (requestId !== mapPreviewRequestId || !mapPreview.open) return;
    mapPreview.loading = false;
    mapPreview.error = error?.message || "地图资源加载失败";
  }
}
function handleMapPreviewOpened() {
  if (mapPreviewGeoJson && !mapPreview.error) renderMapPreview();
}
function renderMapPreview() {
  if (!mapPreviewHost.value || !mapPreviewGeoJson || !mapPreview.row) return;
  disposeMapPreview();
  const mapName = `resource-preview-${mapPreview.row.mapCode}-${mapPreviewRequestId}`;
  echarts.registerMap(mapName, mapPreviewGeoJson);
  mapPreviewChart = echarts.init(mapPreviewHost.value);
  mapPreviewChart.setOption({
    backgroundColor: "#0b1624",
    tooltip: {
      trigger: "item",
      formatter: (params) => params.name || "未命名区域",
    },
    series: [
      {
        type: "map",
        map: mapName,
        roam: true,
        layoutCenter: ["50%", "50%"],
        layoutSize: "88%",
        selectedMode: false,
        label: {
          show: mapPreview.featureCount <= 20,
          color: "#dcecf5",
          fontSize: 11,
        },
        itemStyle: {
          areaColor: "#173f5b",
          borderColor: "#55c9e8",
          borderWidth: 1,
        },
        emphasis: {
          label: { color: "#ffffff" },
          itemStyle: {
            areaColor: "#256f8d",
            borderColor: "#8ce4f5",
            borderWidth: 1.5,
          },
        },
      },
    ],
  });
}
function disposeMapPreview() {
  mapPreviewChart?.dispose?.();
  mapPreviewChart = null;
}
function closeMapPreview() {
  mapPreviewRequestId += 1;
  disposeMapPreview();
  mapPreviewGeoJson = null;
  mapPreview.row = null;
  mapPreview.loading = false;
  mapPreview.error = "";
  mapPreview.featureCount = 0;
}
function editMap(row) {
  if (row.builtin) {
    ElMessage.info(`内置地图路径：${row.resourcePath}`);
    return;
  }
  getDashboardMap(row.mapId).then((res) => {
    const data = res.data || res;
    mapEditor.editing = true;
    mapEditor.form = {
      ...emptyMap(),
      mapId: data.mapId,
      mapCode: data.mapCode,
      mapName: data.mapName,
      folderId: data.folderId || 0,
      geojsonJson: JSON.stringify(data.geojson || {}, null, 2),
      status: data.status || "0",
      remark: data.remark || "",
    };
    mapEditor.open = true;
  });
}
function submitMap() {
  mapFormRef.value.validate((valid) => {
    if (!valid) return;
    mapEditor.saving = true;
    const action = mapEditor.editing
      ? updateDashboardMap(mapEditor.form)
      : addDashboardMap(mapEditor.form);
    action
      .then(() => {
        ElMessage.success("地图已保存");
        mapEditor.open = false;
        loadMaps();
      })
      .finally(() => {
        mapEditor.saving = false;
      });
  });
}
function removeMap(row) {
  ElMessageBox.confirm(
    `确定删除地图“${row.mapName}”吗？已发布页面引用后会显示资源不可用。`,
    "删除地图",
    { type: "warning" },
  )
    .then(() => delDashboardMap(row.mapId))
    .then(() => {
      ElMessage.success("地图已删除");
      loadMaps();
    })
    .catch(() => {});
}
onMounted(() => {
  refreshResourceFilters().catch(() => {});
});
onBeforeUnmount(() => {
  mapPreviewRequestId += 1;
  disposeMapPreview();
});
</script>

<style scoped>
.resource-page :deep(.tree-sidebar) {
  background: var(--el-bg-color, #fff);
  border-color: var(--el-border-color-light, #e8eaed);
}
.resource-page :deep(.tree-header) {
  background: var(--el-bg-color, #fff);
  border-color: var(--el-border-color-light, #e8eaed);
}
.resource-table-wrap strong,
.resource-table-wrap small {
  display: block;
}
.resource-table-wrap strong {
  color: #2d3b4e;
  font-size: 13px;
}
.resource-table-wrap small {
  color: #9eabb9;
  font-size: 10px;
  margin-top: 4px;
}
.folder-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #5f7083;
  font-size: 11px;
}
.folder-pill .el-icon {
  color: #d3a24e;
}
.asset-preview {
  width: 54px;
  height: 38px;
  padding: 0;
  border: 1px solid #e5ebf2;
  border-radius: 5px;
  background: #f8fafc;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  color: #7e91a6;
  cursor: pointer;
  transition:
    border-color 0.18s,
    box-shadow 0.18s;
}
.asset-preview:hover,
.asset-preview:focus-visible {
  border-color: #409eff;
  box-shadow: 0 0 0 2px rgba(64, 158, 255, 0.12);
  outline: none;
}
.asset-preview img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.preview-dialog {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.media-preview-stage,
.map-preview-stage {
  position: relative;
  min-height: 420px;
  border: 1px solid #dce4ed;
  border-radius: 10px;
  overflow: hidden;
  background: #0b1624;
}
.media-preview-stage {
  display: flex;
  align-items: center;
  justify-content: center;
}
.media-preview-stage img,
.media-preview-stage video {
  display: block;
  max-width: 100%;
  max-height: 68vh;
  object-fit: contain;
}
.media-preview-stage video {
  width: 100%;
  height: 68vh;
  max-height: 560px;
  background: #05090f;
}
.preview-empty {
  position: absolute;
  inset: 0;
  z-index: 2;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 28px;
  color: #aebdca;
  text-align: center;
  background: #0b1624;
}
.preview-empty .el-icon {
  font-size: 34px;
  color: #e6a23c;
}
.preview-empty strong {
  color: #f0f5f8;
  font-size: 15px;
}
.preview-empty span {
  max-width: 560px;
  font-size: 12px;
  line-height: 1.6;
}
.preview-meta {
  display: flex;
  align-items: flex-start;
  gap: 10px 24px;
  flex-wrap: wrap;
  color: #5f6f80;
  font-size: 12px;
}
.preview-meta span {
  display: flex;
  gap: 7px;
}
.preview-meta b {
  color: #8a97a6;
  font-weight: 500;
}
.preview-path {
  min-width: 0;
  flex: 1 1 320px;
  word-break: break-all;
}
.preview-note {
  margin: 0;
  color: #8a97a6;
  font-size: 11px;
  line-height: 1.6;
}
.map-preview-canvas {
  width: 100%;
  height: 560px;
}
.map-preview-empty {
  background: rgba(11, 22, 36, 0.96);
}
.map-preview-meta {
  padding-top: 1px;
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 16px;
}
.field-hint {
  margin: 5px 0 0;
  color: #9aa6b4;
  font-size: 10px;
  line-height: 1.5;
}
.resource-page :deep(.el-form-item__label) {
  color: #788596;
  font-size: 11px;
}
.resource-page :deep(.el-tabs__item) {
  font-size: 12px;
}
.resource-page :deep(.el-tag) {
  font-size: 10px;
}
@media (max-width: 900px) {
  .resource-page :deep(.tree-sidebar:not(.collapsed)) {
    min-width: 180px;
  }
  .resource-page {
    padding: 18px 12px;
  }
  .form-grid {
    grid-template-columns: 1fr;
  }
  .resource-table-wrap {
    overflow: auto;
  }
}
</style>
