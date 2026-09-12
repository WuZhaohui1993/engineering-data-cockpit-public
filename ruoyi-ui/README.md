# 工程数据驾驶舱前端

Vue 3、Vite、Element Plus 和 ECharts 应用，复用 RuoYi 管理布局、认证与权限。

主要入口：`src/views/dashboard/`（管理页、设计器、运行态）、`src/components/Dashboard*/`（大屏组件）、`src/api/dashboard.js`（接口）、`src/router/index.js`（路由）、`tests/`（Node 测试）。

在本目录安装与构建：

```bash
pnpm install --frozen-lockfile
npm run build:prod
node --test tests/*.test.mjs
```

本地启动、代理配置和浏览器验收见[本地测试指南](../docs/本地测试指南.md)；功能操作见[示例数据说明](../docs/examples/README.md)，设计约束见[大屏设计器设计](../docs/大屏设计器设计.md)。
