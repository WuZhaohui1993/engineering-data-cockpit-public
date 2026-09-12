-- 分享版本模式增量迁移，可重复执行。不修改旧令牌、版本绑定、期限或授权成员。
-- 先应用本脚本，再启动包含版本模式功能的后端。
-- 已有链接使用 FIXED；新建默认 FOLLOW_PUBLISHED 由服务端显式写入。
SET @dashboard_share_version_mode_exists := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'dashboard_share' AND column_name = 'version_mode'
);
SET @dashboard_share_version_mode_sql := IF(
  @dashboard_share_version_mode_exists = 0,
  'ALTER TABLE dashboard_share ADD COLUMN version_mode varchar(24) NOT NULL DEFAULT ''FIXED'' COMMENT ''FIXED固定版本/FOLLOW_PUBLISHED跟随发布'' AFTER revision_id',
  'SELECT 1'
);
PREPARE dashboard_share_version_mode_stmt FROM @dashboard_share_version_mode_sql;
EXECUTE dashboard_share_version_mode_stmt;
DEALLOCATE PREPARE dashboard_share_version_mode_stmt;
