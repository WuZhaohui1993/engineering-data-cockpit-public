-- 数据管理目录增量迁移。须在 dashboard.sql、dashboard_integration.sql 之后执行，可重复执行。
-- 保留数据集既有 group_code、所有对象编码、连接配置、凭证、状态及测试记录；不调整菜单或角色权限。
CREATE TABLE IF NOT EXISTS dashboard_data_folder (
  folder_id            bigint(20)      NOT NULL AUTO_INCREMENT COMMENT '数据文件夹主键',
  scope                varchar(20)     NOT NULL COMMENT 'source/integration，目录按对象类型隔离',
  folder_code          varchar(64)     NOT NULL COMMENT '稳定文件夹编码',
  folder_name          varchar(100)    NOT NULL COMMENT '文件夹名称',
  parent_id            bigint(20)      NOT NULL DEFAULT 0 COMMENT '父文件夹主键，0 表示根目录',
  sort_order           int             NOT NULL DEFAULT 0 COMMENT '排序',
  create_by            varchar(64)     DEFAULT '',
  create_time          datetime        DEFAULT NULL,
  update_by            varchar(64)     DEFAULT '',
  update_time          datetime        DEFAULT NULL,
  remark               varchar(500)    DEFAULT NULL,
  PRIMARY KEY (folder_id),
  UNIQUE KEY uk_dashboard_data_folder_scope_code (scope, folder_code),
  KEY idx_dashboard_data_folder_parent (scope, parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据管理分类型文件夹';

SET @data_source_folder_sql := IF(
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema=DATABASE() AND table_name='dashboard_data_source' AND column_name='folder_id')=0,
  'ALTER TABLE dashboard_data_source ADD COLUMN folder_id bigint(20) NOT NULL DEFAULT 0 COMMENT ''数据源文件夹主键，0 表示未分类'' AFTER source_name',
  'SELECT 1');
PREPARE data_source_folder_stmt FROM @data_source_folder_sql;
EXECUTE data_source_folder_stmt;
DEALLOCATE PREPARE data_source_folder_stmt;

SET @data_source_folder_index_sql := IF(
  (SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema=DATABASE() AND table_name='dashboard_data_source' AND index_name='idx_dashboard_data_source_folder')=0,
  'ALTER TABLE dashboard_data_source ADD KEY idx_dashboard_data_source_folder (folder_id)',
  'SELECT 1');
PREPARE data_source_folder_index_stmt FROM @data_source_folder_index_sql;
EXECUTE data_source_folder_index_stmt;
DEALLOCATE PREPARE data_source_folder_index_stmt;

SET @integration_folder_sql := IF(
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema=DATABASE() AND table_name='dashboard_integration' AND column_name='folder_id')=0,
  'ALTER TABLE dashboard_integration ADD COLUMN folder_id bigint(20) NOT NULL DEFAULT 0 COMMENT ''推送接入文件夹主键，0 表示未分类'' AFTER integration_name',
  'SELECT 1');
PREPARE integration_folder_stmt FROM @integration_folder_sql;
EXECUTE integration_folder_stmt;
DEALLOCATE PREPARE integration_folder_stmt;

SET @integration_folder_index_sql := IF(
  (SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema=DATABASE() AND table_name='dashboard_integration' AND index_name='idx_dashboard_integration_folder')=0,
  'ALTER TABLE dashboard_integration ADD KEY idx_dashboard_integration_folder (folder_id)',
  'SELECT 1');
PREPARE integration_folder_index_stmt FROM @integration_folder_index_sql;
EXECUTE integration_folder_index_stmt;
DEALLOCATE PREPARE integration_folder_index_stmt;
