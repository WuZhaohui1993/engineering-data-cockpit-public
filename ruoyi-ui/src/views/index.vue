<template>
  <div class="app-container home">
    <section class="welcome-panel">
      <div class="welcome-copy">
        <p class="eyebrow">工程数据管理平台</p>
        <h1>工程数据驾驶舱</h1>
        <p class="welcome-text">
          统一管理项目数据、指标、可视化页面和运行资源，为项目管理提供清晰、可追溯的展示入口。
        </p>
        <div class="welcome-actions">
          <el-button type="primary" icon="Monitor" @click="go('/dashboard/page')">
            进入大屏管理
          </el-button>
          <el-button plain icon="DataAnalysis" @click="go('/dashboard/integration?tab=datasets')">
            查看数据集
          </el-button>
        </div>
      </div>
      <div class="welcome-summary" aria-label="平台能力概览">
        <div class="summary-item">
          <span class="summary-label">页面</span>
          <strong>配置与发布</strong>
        </div>
        <div class="summary-item">
          <span class="summary-label">数据</span>
          <strong>统一登记</strong>
        </div>
        <div class="summary-item">
          <span class="summary-label">访问</span>
          <strong>权限可控</strong>
        </div>
      </div>
    </section>

    <div class="section-heading">
      <div>
        <p class="eyebrow">工作入口</p>
        <h2>从数据登记到大屏展示</h2>
      </div>
      <span class="section-note">按需选择业务模块</span>
    </div>

    <el-row :gutter="20" class="entry-grid">
      <el-col :xs="24" :sm="12" :lg="8">
        <el-card shadow="never" class="entry-card">
          <div class="entry-icon page-icon"><el-icon><Monitor /></el-icon></div>
          <div class="entry-content">
            <h3>页面管理</h3>
            <p>创建页面、维护版本并发布可视化展示内容。</p>
            <el-button link type="primary" @click="go('/dashboard/page')">打开页面管理 <el-icon><ArrowRight /></el-icon></el-button>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :lg="8">
        <el-card shadow="never" class="entry-card">
          <div class="entry-icon data-icon"><el-icon><DataAnalysis /></el-icon></div>
          <div class="entry-content">
            <h3>数据管理</h3>
            <p>统一维护数据集、数据来源和接入处理记录。</p>
            <el-button link type="primary" @click="go('/dashboard/integration?tab=datasets')">打开数据管理 <el-icon><ArrowRight /></el-icon></el-button>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :lg="8">
        <el-card shadow="never" class="entry-card">
          <div class="entry-icon resource-icon"><el-icon><Picture /></el-icon></div>
          <div class="entry-content">
            <h3>资源管理</h3>
            <p>集中维护页面使用的图片、视频和地图资源。</p>
            <el-button link type="primary" @click="go('/dashboard/resource')">打开资源管理 <el-icon><ArrowRight /></el-icon></el-button>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <section class="process-panel">
      <div class="section-heading process-heading">
        <div>
          <p class="eyebrow">使用流程</p>
          <h2>建立可复用的展示链路</h2>
        </div>
      </div>
      <div class="process-list">
        <div class="process-item">
          <span class="process-number">01</span>
          <div><h3>登记数据</h3><p>明确来源、字段和访问参数。</p></div>
        </div>
        <div class="process-item">
          <span class="process-number">02</span>
          <div><h3>配置页面</h3><p>绑定组件与字段，完成页面布局。</p></div>
        </div>
        <div class="process-item">
          <span class="process-number">03</span>
          <div><h3>发布运行</h3><p>通过版本管理提供稳定的展示入口。</p></div>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ArrowRight, DataAnalysis, Monitor, Picture } from '@element-plus/icons-vue'

const router = useRouter()

function go(path) {
  router.push(path)
}
</script>

<style scoped lang="scss">
.home {
  min-height: 100%;
  padding: 28px 30px 40px;
  background: var(--el-bg-color-page);
}

.welcome-panel {
  display: flex;
  align-items: stretch;
  justify-content: space-between;
  gap: 32px;
  min-height: 240px;
  padding: 34px 38px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 14px;
  background: var(--el-bg-color-overlay);
  box-shadow: var(--el-box-shadow-light);
}

.welcome-copy {
  max-width: 700px;
}

.eyebrow {
  margin: 0 0 10px;
  color: var(--el-color-primary);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.16em;
}

h1,
h2,
h3,
p {
  margin-top: 0;
}

h1 {
  margin-bottom: 16px;
  color: var(--el-text-color-primary);
  font-size: clamp(30px, 4vw, 46px);
  line-height: 1.15;
  letter-spacing: -0.03em;
}

.welcome-text {
  max-width: 620px;
  margin-bottom: 24px;
  color: var(--el-text-color-regular);
  font-size: 16px;
  line-height: 1.8;
}

.welcome-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.welcome-summary {
  display: grid;
  align-content: center;
  min-width: 250px;
  padding: 8px 0 8px 32px;
  border-left: 1px solid var(--el-border-color-lighter);
}

.summary-item {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 20px;
  padding: 14px 0;
  border-bottom: 1px solid var(--el-border-color-extra-light);
}

.summary-item:last-child {
  border-bottom: 0;
}

.summary-label,
.section-note {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.summary-item strong {
  color: var(--el-text-color-primary);
  font-size: 15px;
}

.section-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  margin: 34px 0 16px;
}

.section-heading .eyebrow {
  margin-bottom: 6px;
}

h2 {
  margin-bottom: 0;
  color: var(--el-text-color-primary);
  font-size: 22px;
}

.entry-grid {
  margin-bottom: 30px;
}

.entry-card {
  height: 100%;
  border-color: var(--el-border-color-lighter);
  background: var(--el-bg-color-overlay);
}

.entry-card :deep(.el-card__body) {
  display: flex;
  gap: 16px;
  min-height: 132px;
  padding: 24px;
}

.entry-icon {
  display: flex;
  flex: 0 0 44px;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: 12px;
  font-size: 22px;
}

.page-icon {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.data-icon {
  color: var(--el-color-success);
  background: var(--el-color-success-light-9);
}

.resource-icon {
  color: var(--el-color-warning);
  background: var(--el-color-warning-light-9);
}

.entry-content {
  min-width: 0;
}

.entry-content h3 {
  margin-bottom: 8px;
  color: var(--el-text-color-primary);
  font-size: 17px;
}

.entry-content p {
  min-height: 44px;
  margin-bottom: 8px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.7;
}

.process-panel {
  padding: 4px 24px 26px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 14px;
  background: var(--el-bg-color-overlay);
}

.process-heading {
  margin: 24px 0 18px;
}

.process-list {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 22px;
}

.process-item {
  display: flex;
  gap: 14px;
  padding: 16px 0;
  border-top: 1px solid var(--el-border-color-extra-light);
}

.process-number {
  color: var(--el-color-primary);
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.08em;
}

.process-item h3 {
  margin-bottom: 6px;
  color: var(--el-text-color-primary);
  font-size: 15px;
}

.process-item p {
  margin-bottom: 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.6;
}

@media (max-width: 800px) {
  .home {
    padding: 20px;
  }

  .welcome-panel {
    display: block;
    padding: 26px 24px;
  }

  .welcome-summary {
    min-width: 0;
    margin-top: 28px;
    padding: 12px 0 0;
    border-top: 1px solid var(--el-border-color-lighter);
    border-left: 0;
  }

  .process-list {
    grid-template-columns: 1fr;
    gap: 0;
  }
}
</style>
