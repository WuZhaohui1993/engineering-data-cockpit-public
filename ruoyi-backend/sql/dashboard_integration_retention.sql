-- 推送保留期增量迁移；先执行 dashboard_integration.sql，可重复执行。
-- 仅增加清理标记与索引，不修改接入方策略、不删除现有数据。
SET @inbound_retention_ddl := IF(
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='dashboard_integration_batch' AND column_name='details_expired_at')=0,
  'ALTER TABLE dashboard_integration_batch ADD COLUMN details_expired_at datetime DEFAULT NULL COMMENT ''成功明细清理时间，保留去重及回执摘要''',
  'SELECT 1');
PREPARE inbound_retention_stmt FROM @inbound_retention_ddl;
EXECUTE inbound_retention_stmt;
DEALLOCATE PREPARE inbound_retention_stmt;

SET @inbound_retention_ddl := IF(
  (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='dashboard_integration_batch' AND index_name='idx_inbound_retention_success')=0,
  'ALTER TABLE dashboard_integration_batch ADD KEY idx_inbound_retention_success (integration_id,status,details_expired_at,processed_at)',
  'SELECT 1');
PREPARE inbound_retention_stmt FROM @inbound_retention_ddl;
EXECUTE inbound_retention_stmt;
DEALLOCATE PREPARE inbound_retention_stmt;

SET @inbound_retention_ddl := IF(
  (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='dashboard_integration_batch' AND index_name='idx_inbound_retention_raw')=0,
  'ALTER TABLE dashboard_integration_batch ADD KEY idx_inbound_retention_raw (integration_id,status,expires_at)',
  'SELECT 1');
PREPARE inbound_retention_stmt FROM @inbound_retention_ddl;
EXECUTE inbound_retention_stmt;
DEALLOCATE PREPARE inbound_retention_stmt;

SET @inbound_retention_ddl := IF(
  (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()
    AND table_name='dashboard_integration_record' AND index_name='idx_inbound_record_retention')=0,
  'ALTER TABLE dashboard_integration_record ADD KEY idx_inbound_record_retention (integration_id,updated_at)',
  'SELECT 1');
PREPARE inbound_retention_stmt FROM @inbound_retention_ddl;
EXECUTE inbound_retention_stmt;
DEALLOCATE PREPARE inbound_retention_stmt;
