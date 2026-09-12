<template>
  <el-dialog
    :model-value="modelValue"
    :title="guide ? `${guide.title} · 数据与配置说明` : '组件数据与配置说明'"
    width="min(820px, 94vw)"
    append-to-body
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div v-if="guide" class="component-guide">
      <p class="guide-purpose">{{ guide.purpose }}</p>
      <el-alert :title="guide.data ? '支持数据集或组件静态数据' : '此组件无需绑定数据集'" :description="guide.source" type="info" :closable="false" show-icon />
      <template v-if="guide.data">
        <p class="guide-hint">以下均为虚构标准样例，仅供复制和练习。复制不会修改当前组件，也不会新建数据集。</p>
        <div class="guide-section">
          <h3>字段与类型</h3>
          <el-table :data="guide.fields" border>
            <el-table-column prop="role" label="组件字段角色" min-width="170" />
            <el-table-column prop="field" label="样例数据字段" min-width="150" />
            <el-table-column prop="type" label="字段类型" width="115" />
          </el-table>
          <p v-if="guide.multiSeries.length > 1" class="guide-hint">多系列可选择：{{ guide.multiSeries.join('、') }}，均声明为 number。</p>
          <p v-if="guide.displayFields.length" class="guide-hint">展示字段顺序：{{ guide.displayFields.join(' → ') }}</p>
        </div>
        <div class="guide-section">
          <div class="guide-section-title"><h3>标准数据样例</h3><el-button @click="copy(rowsText)">复制数据</el-button></div>
          <el-input :model-value="rowsText" type="textarea" :rows="9" readonly aria-label="标准 JSON 数据样例" />
          <p class="guide-hint">复制到 JSON 数据集的数据内容，或组件“静态数据”输入框。这里是数据行数组，不是页面配置 JSON。</p>
        </div>
        <div v-if="Object.keys(guide.fieldMap).length" class="guide-section">
          <div class="guide-section-title"><h3>字段映射</h3><el-button @click="copy(mappingText)">复制字段映射</el-button></div>
          <el-input :model-value="mappingText" type="textarea" :rows="Math.min(Object.keys(guide.fieldMap).length + 2, 7)" readonly aria-label="字段映射 JSON" />
          <p class="guide-hint">在“数据 → 字段映射”中按上表逐项选择；复制内容用于核对或配置文件中的 binding.fieldMap，不要粘贴到数据内容中。</p>
        </div>
        <p v-if="guide.mapRef" class="guide-resource">配套地图资源：<code>{{ guide.mapRef }}</code></p>
      </template>
      <div v-if="guide.configuration" class="guide-section">
        <div class="guide-section-title"><h3>配置参考</h3><el-button @click="copy(configurationText)">复制配置参考</el-button></div>
        <el-input :model-value="configurationText" type="textarea" :rows="4" readonly aria-label="组件配置参考" />
        <p class="guide-hint">按说明在对应样式中填写；这不是完整页面配置。</p>
      </div>
      <div class="guide-section">
        <h3>配置要点</h3>
        <ul><li v-for="note in guide.notes" :key="note">{{ note }}</li></ul>
      </div>
    </div>
    <el-empty v-else description="请先选择一个组件" />
    <template #footer><el-button @click="emit('update:modelValue', false)">关闭</el-button></template>
  </el-dialog>
</template>

<script setup>
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import { getDashboardComponentExample } from '@/utils/dashboardComponentExamples'

const props = defineProps({ modelValue: { type: Boolean, default: false }, widget: { type: Object, default: null } })
const emit = defineEmits(['update:modelValue'])
const guide = computed(() => getDashboardComponentExample(props.widget || {}))
const rowsText = computed(() => JSON.stringify(guide.value?.rows || [], null, 2))
const mappingText = computed(() => JSON.stringify(guide.value?.fieldMap || {}, null, 2))
const configurationText = computed(() => typeof guide.value?.configuration === 'string' ? guide.value.configuration : JSON.stringify(guide.value?.configuration || {}, null, 2))

async function copy(value) {
  try {
    await navigator.clipboard.writeText(value)
    ElMessage.success('已复制')
  } catch {
    ElMessage.warning('当前浏览器不允许自动复制，请在文本框中选中内容复制')
  }
}
</script>

<style scoped>
.component-guide { max-height: 65vh; overflow: auto; color: var(--el-text-color-primary); padding-right: 6px; }
.guide-purpose { margin: 0 0 14px; line-height: 1.7; }
.guide-hint { color: var(--el-text-color-secondary); line-height: 1.7; margin: 8px 0 0; }
.guide-section { margin-top: 20px; }
.guide-section h3 { margin: 0 0 10px; font-size: var(--el-font-size-base); font-weight: 600; }
.guide-section-title { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-bottom: 8px; }
.guide-section-title h3 { margin: 0; }
.guide-section ul { margin: 0; padding-left: 20px; }
.guide-section li { margin: 6px 0; line-height: 1.7; }
.guide-resource { line-height: 1.7; overflow-wrap: anywhere; }
.guide-resource code { color: var(--el-color-primary); }
.guide-section :deep(.el-textarea__inner) { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
</style>
