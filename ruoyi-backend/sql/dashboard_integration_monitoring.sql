-- 接入运维分类。先执行 dashboard_integration_operations.sql；可重复执行。
SET @dashboard_monitor_category_exists := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='dashboard_integration_alert' AND column_name='category'
);
SET @dashboard_monitor_category_sql := IF(@dashboard_monitor_category_exists=0,
  'ALTER TABLE dashboard_integration_alert ADD COLUMN category varchar(32) NOT NULL DEFAULT ''OUTBOUND'' AFTER alert_key',
  'SELECT 1');
PREPARE dashboard_monitor_stmt FROM @dashboard_monitor_category_sql;
EXECUTE dashboard_monitor_stmt;
DEALLOCATE PREPARE dashboard_monitor_stmt;

-- 历史已实现的告警类型具有明确归属；不推测无法识别的来源或媒体摘要。
UPDATE dashboard_integration_alert SET category='INBOUND' WHERE error_code='DEAD_LETTER' AND category='OUTBOUND';
UPDATE dashboard_integration_alert SET category='WEBSOCKET' WHERE LEFT(error_code,3)='WS_' AND category='OUTBOUND';
-- 滚动升级或旧/新进程同时产生过告警时，保留两条历史记录和各自计数；不合并或删除。
UPDATE dashboard_integration_alert legacy
LEFT JOIN dashboard_integration_alert categorized
  ON categorized.alert_key=SHA2(CONCAT(legacy.category,':',legacy.resource_code,':',legacy.error_code),256)
SET legacy.alert_key=SHA2(CONCAT(legacy.category,':',legacy.resource_code,':',legacy.error_code),256)
WHERE legacy.alert_key=SHA2(CONCAT(legacy.resource_code,':',legacy.error_code),256)
  AND categorized.alert_id IS NULL;

-- 仅还能关联到受控媒体引用的历史审计可归属来源；已清理引用的旧摘要保持原值。
UPDATE dashboard_integration_audit a
JOIN dashboard_integration_media_ref r ON a.resource_code=SHA2(r.media_ref,256)
SET a.resource_code=CONCAT(r.source_code,'/',r.endpoint_code)
WHERE a.category='MEDIA';

SET @dashboard_monitor_audit_index_exists := (
  SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema=DATABASE() AND table_name='dashboard_integration_audit' AND index_name='idx_audit_category_time'
);
SET @dashboard_monitor_index_sql := IF(@dashboard_monitor_audit_index_exists=0,
  'ALTER TABLE dashboard_integration_audit ADD INDEX idx_audit_category_time(category,created_at)', 'SELECT 1');
PREPARE dashboard_monitor_stmt FROM @dashboard_monitor_index_sql;
EXECUTE dashboard_monitor_stmt;
DEALLOCATE PREPARE dashboard_monitor_stmt;

SET @dashboard_monitor_alert_index_exists := (
  SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema=DATABASE() AND table_name='dashboard_integration_alert' AND index_name='idx_alert_category_time'
);
SET @dashboard_monitor_index_sql := IF(@dashboard_monitor_alert_index_exists=0,
  'ALTER TABLE dashboard_integration_alert ADD INDEX idx_alert_category_time(category,last_at)', 'SELECT 1');
PREPARE dashboard_monitor_stmt FROM @dashboard_monitor_index_sql;
EXECUTE dashboard_monitor_stmt;
DEALLOCATE PREPARE dashboard_monitor_stmt;
