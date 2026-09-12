<template>
  <el-tooltip v-if="guide && compact" content="使用说明" placement="top"><el-button text :icon="QuestionFilled" :aria-label="`${guide.title}使用说明`" @click="showHelp" /></el-tooltip>
  <el-button v-else-if="guide" text type="primary" :icon="QuestionFilled" :aria-label="`${guide.title}使用说明`" @click="showHelp">使用说明</el-button>
  <el-drawer v-if="guide" v-model="open" :title="`${guide.title}使用说明`" :size="'min(720px, 100vw)'" append-to-body destroy-on-close>
    <article class="management-help" :aria-label="`${guide.title}操作指南`">
      <p class="help-intro">{{ guide.summary }}</p>
      <el-tabs v-model="section" class="help-tabs" stretch>
        <el-tab-pane label="操作指南" name="steps">
          <ol class="help-steps">
            <li v-for="item in guide.steps" :key="item[0]">
              <h3>{{ item[0] }}</h3>
              <p>{{ item[1] }}</p>
            </li>
          </ol>
          <div v-if="guide.example" class="help-example"><h3>{{ guide.example.title }}</h3><pre>{{ guide.example.code }}</pre></div>
          <section class="help-result"><h3>完成后检查</h3><p>{{ guide.result }}</p></section>
        </el-tab-pane>
        <el-tab-pane label="字段与状态" name="fields">
          <dl class="help-fields"><template v-for="item in guide.fields" :key="item[0]"><dt>{{ item[0] }}</dt><dd>{{ item[1] }}</dd></template></dl>
        </el-tab-pane>
        <el-tab-pane label="常见问题" name="questions">
          <dl class="help-questions"><template v-for="item in guide.questions" :key="item[0]"><dt>{{ item[0] }}</dt><dd>{{ item[1] }}</dd></template></dl>
        </el-tab-pane>
      </el-tabs>
      <p class="help-permissions">操作按钮按当前账号权限显示；没有相应入口时，请联系管理员核对该模块授权。</p>
    </article>
    <template #footer><el-button type="primary" @click="open = false">知道了</el-button></template>
  </el-drawer>
</template>

<script setup>
import { computed, onDeactivated, ref, watch } from 'vue';
import { QuestionFilled } from '@element-plus/icons-vue';
import { getDashboardManagementHelp } from '@/utils/dashboardManagementHelp';

const props = defineProps({ module: { type: String, required: true }, compact: Boolean });
const open = ref(false);
const section = ref('steps');
const guide = computed(() => getDashboardManagementHelp(props.module));
function showHelp() { section.value = 'steps'; open.value = true; }
watch(() => props.module, () => { open.value = false; section.value = 'steps'; });
onDeactivated(() => { open.value = false; });
</script>

<style scoped>
.management-help { color: var(--el-text-color-regular); font-size: var(--el-font-size-base); line-height: 1.75; overflow-wrap: anywhere; }
.help-intro { margin: 0 0 18px; color: var(--el-text-color-secondary); }
.help-tabs :deep(.el-tabs__header) { margin-bottom: 20px; }
.management-help h3 { margin: 0 0 6px; color: var(--el-text-color-primary); font-size: var(--el-font-size-base); font-weight: 600; }
.management-help p { margin-top: 0; }
.help-steps { margin: 0; padding-left: 24px; }
.help-steps li { padding-left: 6px; margin-bottom: 22px; }
.help-steps li::marker { color: var(--el-color-primary); font-weight: 600; }
.help-steps p, .help-result p { margin-bottom: 0; }
.help-result { padding-top: 18px; border-top: 1px solid var(--el-border-color-lighter); }
.help-fields { display: grid; grid-template-columns: minmax(100px, 140px) minmax(0, 1fr); margin: 0; }
.help-fields dt, .help-fields dd { padding: 14px 0; border-bottom: 1px solid var(--el-border-color-lighter); }
.help-fields dt { padding-right: 18px; }
.management-help dt { color: var(--el-text-color-primary); font-weight: 600; }
.management-help dd { margin: 0; }
.help-questions { margin: 0; }
.help-questions dt { margin-bottom: 6px; }
.help-questions dd { margin-bottom: 24px; }
.help-example { margin: 20px 0; }
.help-example pre { padding: 12px; background: var(--el-fill-color-light); border-radius: var(--el-border-radius-base); white-space: pre-wrap; line-height: 1.6; }
.help-permissions { margin: 24px 0 0; color: var(--el-text-color-secondary); font-size: 12px; }
@media (max-width: 560px) {
  .help-fields { grid-template-columns: minmax(0, 1fr); }
  .help-fields dt { padding-bottom: 4px; border: 0; }
  .help-fields dd { padding-top: 0; }
}
</style>
