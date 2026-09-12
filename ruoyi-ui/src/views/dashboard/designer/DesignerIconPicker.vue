<template>
  <el-dialog
    :model-value="modelValue"
    title="图标库"
    width="min(900px, calc(100vw - 32px))"
    class="designer-icon-picker-dialog"
    append-to-body
    destroy-on-close
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
    @opened="focusSelectedIcon"
  >
    <div class="icon-library">
      <div class="icon-library__filters">
        <el-input
          v-model="query"
          class="icon-library__search"
          :prefix-icon="Search"
          placeholder="搜索图标名称、编码或分类"
          aria-label="搜索图标"
          clearable
        />
        <el-select v-model="source" class="icon-library__source" aria-label="图标来源">
          <el-option label="全部来源" value="all" />
          <el-option label="内置图标" value="builtin" />
          <el-option label="资源管理" value="resource" />
        </el-select>
        <el-tooltip content="刷新资源图标" placement="top">
          <el-button :icon="Refresh" :loading="loading" aria-label="刷新资源图标" @click="refreshAssets" />
        </el-tooltip>
      </div>

      <el-tabs v-model="category" class="icon-library__tabs">
        <el-tab-pane v-for="tab in categories" :key="tab.value" :name="tab.value">
          <template #label>
            <span>{{ tab.label }} <span class="icon-library__count">{{ categoryCount(tab.value) }}</span></span>
          </template>
        </el-tab-pane>
      </el-tabs>

      <div ref="viewport" v-loading="loading" class="icon-library__viewport" element-loading-text="正在加载资源图标">
        <ul v-if="pageItems.length" class="icon-library__grid" aria-label="可选图标">
          <li v-for="item in pageItems" :key="item.key">
            <button
              type="button"
              class="icon-library__item"
              :class="{ 'is-selected': draftKey === item.key, 'is-unavailable': failedImages[item.key] }"
              :aria-pressed="draftKey === item.key"
              :aria-label="`${item.label}，${kindLabel(item.kind)}${failedImages[item.key] ? '，图片加载失败' : ''}`"
              :title="item.label"
              @click="draftKey = item.key"
            >
              <span class="icon-library__thumbnail" :class="{ 'is-image': item.kind !== 'builtin' }">
                <el-icon v-if="item.kind === 'builtin'" class="icon-library__symbol"><component :is="item.icon" /></el-icon>
                <span v-else-if="failedImages[item.key]" class="icon-library__broken">
                  <el-icon><Picture /></el-icon><span>加载失败</span>
                </span>
                <img v-else :src="item.url" :alt="item.label" loading="lazy" @error="markFailed(item.key)" />
                <span v-if="item.kind === 'gif'" class="icon-library__gif">GIF</span>
                <span v-if="draftKey === item.key" class="icon-library__check"><el-icon><Check /></el-icon></span>
              </span>
              <span class="icon-library__name">{{ item.label }}</span>
            </button>
          </li>
        </ul>
        <el-empty v-else :image-size="72" :description="emptyDescription">
          <el-button v-if="query || source !== 'all' || category !== 'all'" @click="clearFilters">清除筛选</el-button>
        </el-empty>
      </div>

      <div class="icon-library__list-footer">
        <span class="icon-library__total" aria-live="polite">共 {{ filteredItems.length }} 个图标</span>
        <el-pagination
          v-if="filteredItems.length > pageSize"
          v-model:current-page="page"
          :page-size="pageSize"
          :total="filteredItems.length"
          :pager-count="5"
          layout="prev, pager, next"
        />
      </div>
      <p class="icon-library__hint">自定义 PNG / SVG / GIF 可在资源管理上传后刷新。选择图标后点击“确认选择”应用。</p>
    </div>

    <template #footer>
      <div class="icon-library__footer">
        <div v-if="draftItem" class="icon-library__selection">
          <div class="icon-library__selected-preview" :class="{ 'is-image': draftItem.kind !== 'builtin' }">
            <el-icon v-if="draftItem.kind === 'builtin'"><component :is="draftItem.icon" /></el-icon>
            <el-icon v-else-if="failedImages[draftItem.key]"><Picture /></el-icon>
            <img v-else :key="draftItem.key" :src="draftItem.url" :alt="draftItem.label" :data-icon-key="draftItem.key" @error="markFailed($event.currentTarget.dataset.iconKey)" />
          </div>
          <div class="icon-library__selected-info">
            <strong :title="draftItem.label">{{ draftItem.label }}</strong>
            <span>{{ failedImages[draftItem.key] ? '图片加载失败，请刷新或选择其他图标' : selectionDescription }}</span>
          </div>
        </div>
        <span v-else class="icon-library__hint">请选择一个图标</span>
        <div class="icon-library__actions">
          <el-button @click="emit('update:modelValue', false)">取消</el-button>
          <el-button type="primary" :disabled="!draftItem || Boolean(failedImages[draftItem.key])" @click="confirmSelection">确认选择</el-button>
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { Check, Picture, Refresh, Search } from '@element-plus/icons-vue'
import { dashboardIconOptions } from '@/utils/dashboardIcons'
import { dashboardLibraryImages, dashboardIconImageUrl } from '@/utils/dashboardIconLibrary'
import { dashboardResourceUrl } from '@/utils/dashboard'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  selectedIconName: { type: String, default: 'star' },
  selectedImageRef: { type: String, default: '' },
  assets: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
})
const emit = defineEmits(['update:modelValue', 'select', 'refresh'])

const query = ref('')
const source = ref('all')
const category = ref('all')
const page = ref(1)
const pageSize = 48
const draftKey = ref('')
const failedImages = ref({})
const viewport = ref(null)
const categories = [
  { value: 'all', label: '全部' },
  { value: 'builtin', label: '平台图标' },
  { value: 'static', label: '静态图标' },
  { value: 'gif', label: 'GIF 动态图标' },
]

function imageKind(item) {
  return item.kind === 'gif' || /\.gif(?:[?#]|$)/i.test(item.resourcePath || '') || /^image\/gif(?:;|$)/i.test(item.mimeType || item.contentType || '') ? 'gif' : 'static'
}

const items = computed(() => {
  const result = dashboardIconOptions.map(item => ({
    key: `builtin:${item.value}`, kind: 'builtin', iconName: item.value,
    label: item.label, icon: item.icon, category: '平台图标', source: 'builtin', code: item.value,
  }))
  const imagePaths = new Set()
  function addImage(item, imageSource) {
    const imageRef = String(item.resourcePath || '').trim()
    const url = dashboardIconImageUrl(imageRef, dashboardResourceUrl)
    if (!url || imagePaths.has(imageRef)) return
    imagePaths.add(imageRef)
    result.push({
      key: `image:${imageRef}`, kind: imageKind(item), imageRef, url,
      label: String(item.label || item.assetName || imageRef.split('/').pop() || '未命名图标'),
      category: String(item.category || item.folderName || (imageSource === 'builtin' ? '内置图标' : '自定义图标')),
      source: imageSource, code: String(item.assetCode || item.key || ''),
    })
  }
  dashboardLibraryImages.forEach(item => addImage(item, 'builtin'))
  props.assets.filter(item => item && (!item.assetType || String(item.assetType).toUpperCase() === 'IMAGE')).forEach(item => addImage(item, 'resource'))
  // Keep a saved uploaded icon visible even if its asset listing is still loading.
  if (props.selectedImageRef && !imagePaths.has(props.selectedImageRef)) {
    addImage({ resourcePath: props.selectedImageRef, category: '当前图片' }, 'resource')
  }
  return result
})

const searchedItems = computed(() => {
  const terms = query.value.trim().toLocaleLowerCase().split(/\s+/).filter(Boolean)
  return items.value.filter(item => {
    if (source.value !== 'all' && source.value !== item.source) return false
    const text = `${item.label} ${item.code} ${item.category} ${kindLabel(item.kind)} ${item.imageRef || ''}`.toLocaleLowerCase()
    return terms.every(term => text.includes(term))
  })
})
const filteredItems = computed(() => searchedItems.value.filter(item => category.value === 'all' || item.kind === category.value))
const pageItems = computed(() => filteredItems.value.slice((page.value - 1) * pageSize, page.value * pageSize))
const draftItem = computed(() => items.value.find(item => item.key === draftKey.value))
const emptyDescription = computed(() => query.value.trim() ? '没有找到匹配的图标' : source.value === 'resource' ? '暂无符合条件的资源图标' : '该分类暂无图标')
const selectionDescription = computed(() => {
  const item = draftItem.value
  if (!item) return ''
  const format = item.kind === 'builtin' ? '支持修改颜色' : (item.imageRef.match(/\.([a-z0-9]+)(?:[?#]|$)/i)?.[1] || '图片').toUpperCase()
  return `${kindLabel(item.kind)} · ${format} · ${item.source === 'builtin' ? '内置' : '资源管理'}`
})

function kindLabel(kind) {
  return kind === 'builtin' ? '平台图标' : kind === 'gif' ? 'GIF 动态图标' : '静态图标'
}
function categoryCount(value) {
  return value === 'all' ? searchedItems.value.length : searchedItems.value.filter(item => item.kind === value).length
}
function clearFilters() {
  query.value = ''
  source.value = 'all'
  category.value = 'all'
  page.value = 1
}
function markFailed(key) {
  failedImages.value = { ...failedImages.value, [key]: true }
}
function refreshAssets() {
  failedImages.value = {}
  emit('refresh')
}
async function focusSelectedIcon() {
  await nextTick()
  const selected = viewport.value?.querySelector('.icon-library__item.is-selected')
  selected?.focus({ preventScroll: true })
  selected?.scrollIntoView({ block: 'nearest', inline: 'nearest' })
}
function confirmSelection() {
  const item = draftItem.value
  if (!item || failedImages.value[item.key]) return
  emit('select', item.kind === 'builtin'
    ? { kind: 'builtin', iconName: item.iconName, label: item.label }
    : { kind: 'image', imageRef: item.imageRef, label: item.label })
  emit('update:modelValue', false)
}

watch([query, source, category], () => { page.value = 1 }, { flush: 'sync' })
watch(() => filteredItems.value.length, length => {
  page.value = Math.min(page.value, Math.max(1, Math.ceil(length / pageSize)))
})
watch(() => props.modelValue, visible => {
  if (!visible) return
  clearFilters()
  failedImages.value = {}
  const selectedKey = props.selectedImageRef ? `image:${props.selectedImageRef}` : `builtin:${props.selectedIconName}`
  draftKey.value = items.value.some(item => item.key === selectedKey) ? selectedKey : 'builtin:star'
  const selectedIndex = filteredItems.value.findIndex(item => item.key === draftKey.value)
  page.value = Math.max(1, Math.floor(selectedIndex / pageSize) + 1)
}, { immediate: true })
</script>

<style scoped>
:global(.designer-icon-picker-dialog) {
  display: flex;
  flex-direction: column;
  max-height: 88vh;
}

:global(.designer-icon-picker-dialog .el-dialog__body) {
  min-height: 0;
  overflow: auto;
}

.icon-library {
  display: flex;
  flex-direction: column;
  min-width: 0;
  color: var(--el-text-color-regular);
}

.icon-library__filters {
  display: flex;
  align-items: center;
  gap: 10px;
}

.icon-library__search { flex: 1; min-width: 0; }
.icon-library__source { flex: 0 0 140px; }
.icon-library__filters > .el-button { margin-left: 0; }
.icon-library__tabs { margin-top: 12px; }
.icon-library__tabs :deep(.el-tabs__header) { margin-bottom: 12px; }
.icon-library__tabs :deep(.el-tabs__content) { display: none; }
.icon-library__count { margin-left: 4px; color: var(--el-text-color-secondary); font-size: 12px; font-weight: 400; }

.icon-library__viewport {
  height: clamp(180px, calc(88vh - 330px), 408px);
  overflow: auto;
  overscroll-behavior: contain;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--el-border-radius-base);
  background: var(--el-fill-color-extra-light);
}

.icon-library__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(92px, 1fr));
  gap: 10px;
  list-style: none;
  padding: 12px;
  margin: 0;
}

.icon-library__grid > li { min-width: 0; }
.icon-library__item {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 7px;
  padding: 8px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--el-border-radius-base);
  background: var(--el-fill-color-blank);
  color: var(--el-text-color-regular);
  font: inherit;
  cursor: pointer;
  transition: border-color 0.15s, background-color 0.15s;
}

.icon-library__item:hover { border-color: var(--el-color-primary-light-5); }
.icon-library__item:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: 2px; }
.icon-library__item.is-selected { border-color: var(--el-color-primary); background: var(--el-color-primary-light-9); }
.icon-library__thumbnail { display: flex; align-items: center; justify-content: center; position: relative; height: 58px; border-radius: 3px; }
.icon-library__symbol { font-size: 32px; color: var(--el-color-primary); }
.icon-library__thumbnail img { display: block; width: 48px; height: 48px; object-fit: contain; }
.is-image {
  background-color: var(--el-fill-color-blank);
  background-image: conic-gradient(var(--el-fill-color-light) 25%, transparent 0 50%, var(--el-fill-color-light) 0 75%, transparent 0);
  background-size: 12px 12px;
}
.icon-library__name { display: -webkit-box; min-height: 36px; overflow: hidden; -webkit-box-orient: vertical; -webkit-line-clamp: 2; overflow-wrap: anywhere; font-size: var(--el-font-size-extra-small); line-height: 18px; text-align: center; }
.icon-library__gif { position: absolute; top: 0; left: 0; padding: 1px 4px; border-radius: 3px; background: var(--el-fill-color); color: var(--el-text-color-secondary); font-size: 10px; line-height: 15px; }
.icon-library__check { position: absolute; right: -4px; top: -4px; display: flex; align-items: center; justify-content: center; width: 17px; height: 17px; border-radius: 50%; background: var(--el-color-primary); color: var(--el-color-white); }
.icon-library__broken { display: flex; flex-direction: column; align-items: center; gap: 4px; font-size: 11px; color: var(--el-text-color-placeholder); }
.icon-library__broken > .el-icon { font-size: 23px; }
.icon-library__list-footer { display: flex; align-items: center; justify-content: space-between; gap: 8px; min-height: 38px; padding-top: 6px; }
.icon-library__total { color: var(--el-text-color-secondary); font-size: var(--el-font-size-extra-small); white-space: nowrap; }
.icon-library__hint { margin: 8px 0 0; font-size: var(--el-font-size-extra-small); line-height: 1.6; color: var(--el-text-color-secondary); }
.icon-library__footer { display: flex; align-items: center; justify-content: space-between; gap: 20px; padding-top: 14px; border-top: 1px solid var(--el-border-color-lighter); text-align: left; }
.icon-library__selection { display: flex; align-items: center; gap: 12px; min-width: 0; flex: 1; }
.icon-library__selected-preview { display: flex; align-items: center; justify-content: center; flex: 0 0 56px; height: 56px; border: 1px solid var(--el-border-color-lighter); border-radius: var(--el-border-radius-base); color: var(--el-color-primary); }
.icon-library__selected-preview > .el-icon { font-size: 36px; }
.icon-library__selected-preview img { width: 48px; height: 48px; object-fit: contain; }
.icon-library__selected-info { display: flex; flex-direction: column; gap: 5px; min-width: 0; }
.icon-library__selected-info strong { display: -webkit-box; overflow: hidden; -webkit-box-orient: vertical; -webkit-line-clamp: 2; overflow-wrap: anywhere; color: var(--el-text-color-primary); font-size: var(--el-font-size-base); line-height: 1.5; font-weight: 500; }
.icon-library__selected-info > span { color: var(--el-text-color-secondary); font-size: var(--el-font-size-extra-small); line-height: 1.5; }
.icon-library__actions { display: flex; flex: 0 0 auto; gap: 10px; }
.icon-library__actions > .el-button { margin-left: 0; }

@media (max-width: 600px) {
  .icon-library__filters { flex-wrap: wrap; }
  .icon-library__search { flex-basis: 100%; }
  .icon-library__source { flex: 1; }
  .icon-library__grid { grid-template-columns: repeat(auto-fill, minmax(80px, 1fr)); gap: 8px; padding: 8px; }
  .icon-library__tabs :deep(.el-tabs__item) { padding-right: 12px; padding-left: 12px; }
  .icon-library__footer { flex-wrap: wrap; gap: 12px; }
  .icon-library__selection { flex-basis: 100%; }
  .icon-library__actions { margin-left: auto; }
  .icon-library__viewport { height: clamp(140px, calc(88vh - 440px), 340px); }
  .icon-library__list-footer { flex-wrap: wrap; }
}
</style>
