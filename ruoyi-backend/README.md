# 工程数据驾驶舱后端

RuoYi Spring Boot 服务，负责认证权限、系统管理、大屏配置、数据集执行与资源访问。

主要入口：`ruoyi-admin`（Web 应用）、`ruoyi-system`（系统和大屏业务）、`ruoyi-framework`（安全与配置）、`ruoyi-common`（公共能力）、`sql/`（数据库脚本）；代码生成和定时任务分别在 `ruoyi-generator`、`ruoyi-quartz`。

在本目录测试与打包：

```bash
mvn test
mvn -DskipTests package
```

运行产物为 `ruoyi-admin/target/ruoyi-admin.jar`。本地配置、初始化与启动见[本地测试指南](../docs/本地测试指南.md)；接口权限见[权限与接口设计](../docs/权限与接口设计.md)，来源契约见[数据集与数据源设计](../docs/数据集与数据源设计.md)。
