<template>
  <el-tooltip v-if="count > 0" :content="`有 ${count} 项接入告警待处理`" placement="bottom">
    <button class="integration-alert-button" aria-label="查看接入告警" @click="router.push({path:'/dashboard/integration',query:{tab:'operations'}})">
      <el-badge :value="count" :max="99"><el-icon><Warning /></el-icon></el-badge>
    </button>
  </el-tooltip>
</template>
<script setup>
import { onMounted, onBeforeUnmount, ref } from 'vue';
import { useRouter } from 'vue-router';
import { Warning } from '@element-plus/icons-vue';
import { getIntegrationMetrics } from '@/api/dashboardIntegration';
const count=ref(0),router=useRouter();
let timer,closed=false,busy=false;
async function refresh() {
  if(busy||closed) return;
  busy=true;
  try { const result=await getIntegrationMetrics();if(!closed) count.value=Number(result.data?.openAlerts||0); }
  catch { /* 保留已知告警计数，下一轮恢复 */ }
  finally {busy=false;}
}
onMounted(()=>{refresh();timer=setInterval(refresh,30000);});
onBeforeUnmount(()=>{closed=true;clearInterval(timer);});
</script>
<style scoped>
.integration-alert-button { border: 0; background: transparent; color: var(--el-color-warning); font-size: 20px; cursor: pointer; padding: 0 12px; }
</style>
