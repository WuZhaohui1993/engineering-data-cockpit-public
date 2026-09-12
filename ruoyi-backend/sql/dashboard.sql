-- 工程数据驾驶舱大屏模块初始化脚本
-- 只新增大屏业务表和演示配置，不修改基础系统表结构。

CREATE TABLE IF NOT EXISTS dashboard_page (
  page_id              bigint(20)      NOT NULL AUTO_INCREMENT COMMENT '页面主键',
  page_code            varchar(64)     NOT NULL COMMENT '页面编码',
  page_name            varchar(100)    NOT NULL COMMENT '页面名称',
  folder_id             bigint(20)      NOT NULL DEFAULT 0 COMMENT '页面文件夹主键，0 表示根目录',
  status               char(1)         NOT NULL DEFAULT '0' COMMENT '状态（0正常 1停用）',
  is_deleted            char(1)         NOT NULL DEFAULT '0' COMMENT '回收站标记（0否 1是）',
  delete_by             varchar(64)    DEFAULT NULL COMMENT '删除人',
  delete_time           datetime       DEFAULT NULL COMMENT '删除时间',
  current_revision_id  bigint(20)      DEFAULT NULL COMMENT '当前运行版本',
  create_by            varchar(64)     DEFAULT '' COMMENT '创建者',
  create_time          datetime        DEFAULT NULL COMMENT '创建时间',
  update_by            varchar(64)     DEFAULT '' COMMENT '更新者',
  update_time          datetime        DEFAULT NULL COMMENT '更新时间',
  remark               varchar(500)    DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (page_id),
  UNIQUE KEY uk_dashboard_page_code (page_code),
  KEY idx_dashboard_page_folder (folder_id),
  KEY idx_dashboard_page_deleted (is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='轻量大屏页面';

CREATE TABLE IF NOT EXISTS dashboard_page_folder (
  folder_id             bigint(20)      NOT NULL AUTO_INCREMENT COMMENT '文件夹主键',
  folder_code           varchar(64)     NOT NULL COMMENT '文件夹编码',
  folder_name           varchar(100)    NOT NULL COMMENT '文件夹名称',
  parent_id             bigint(20)      NOT NULL DEFAULT 0 COMMENT '父文件夹主键，0 表示根目录',
  sort_order            int             NOT NULL DEFAULT 0 COMMENT '排序',
  status                char(1)         NOT NULL DEFAULT '0' COMMENT '状态（0正常 1停用）',
  create_by             varchar(64)    DEFAULT '' COMMENT '创建者',
  create_time           datetime       DEFAULT NULL COMMENT '创建时间',
  update_by             varchar(64)    DEFAULT '' COMMENT '更新者',
  update_time           datetime       DEFAULT NULL COMMENT '更新时间',
  remark                varchar(500)   DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (folder_id),
  UNIQUE KEY uk_dashboard_page_folder_code (folder_code),
  KEY idx_dashboard_page_folder_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='轻量大屏页面文件夹';

CREATE TABLE IF NOT EXISTS dashboard_resource_folder (
  folder_id             bigint(20)      NOT NULL AUTO_INCREMENT COMMENT '资源文件夹主键',
  folder_code           varchar(64)     NOT NULL COMMENT '文件夹编码',
  folder_name           varchar(100)    NOT NULL COMMENT '文件夹名称',
  parent_id             bigint(20)      NOT NULL DEFAULT 0 COMMENT '父文件夹主键，0 表示根目录',
  sort_order            int             NOT NULL DEFAULT 0 COMMENT '排序',
  status                char(1)         NOT NULL DEFAULT '0' COMMENT '状态（0正常 1停用）',
  create_by             varchar(64)     DEFAULT '' COMMENT '创建者',
  create_time           datetime        DEFAULT NULL COMMENT '创建时间',
  update_by             varchar(64)     DEFAULT '' COMMENT '更新者',
  update_time           datetime        DEFAULT NULL COMMENT '更新时间',
  remark                varchar(500)    DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (folder_id),
  UNIQUE KEY uk_dashboard_resource_folder_code (folder_code),
  KEY idx_dashboard_resource_folder_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='轻量大屏资源文件夹';

CREATE TABLE IF NOT EXISTS dashboard_asset (
  asset_id             bigint(20)      NOT NULL AUTO_INCREMENT COMMENT '资源主键',
  asset_code           varchar(64)     NOT NULL COMMENT '资源编码',
  asset_name           varchar(100)    NOT NULL COMMENT '资源名称',
  folder_id            bigint(20)      NOT NULL DEFAULT 0 COMMENT '资源文件夹主键，0 表示根目录',
  asset_type           varchar(20)     NOT NULL COMMENT 'IMAGE/VIDEO',
  category             varchar(64)     NOT NULL DEFAULT '' COMMENT '资源分类',
  resource_path        varchar(500)    NOT NULL COMMENT '平台资源路径',
  mime_type            varchar(100)    DEFAULT NULL COMMENT '媒体类型',
  file_size            bigint(20)      DEFAULT NULL COMMENT '文件大小',
  status               char(1)         NOT NULL DEFAULT '0' COMMENT '状态（0正常 1停用）',
  create_by            varchar(64)     DEFAULT '' COMMENT '创建者',
  create_time          datetime        DEFAULT NULL COMMENT '创建时间',
  update_by            varchar(64)     DEFAULT '' COMMENT '更新者',
  update_time          datetime        DEFAULT NULL COMMENT '更新时间',
  remark               varchar(500)    DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (asset_id),
  UNIQUE KEY uk_dashboard_asset_code (asset_code),
  KEY idx_dashboard_asset_folder (folder_id),
  KEY idx_dashboard_asset_type (asset_type),
  KEY idx_dashboard_asset_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='轻量大屏图库和媒体资源';

CREATE TABLE IF NOT EXISTS dashboard_map_resource (
  map_id               bigint(20)      NOT NULL AUTO_INCREMENT COMMENT '地图主键',
  map_code             varchar(64)     NOT NULL COMMENT '地图编码',
  map_name             varchar(100)    NOT NULL COMMENT '地图名称',
  folder_id            bigint(20)      NOT NULL DEFAULT 0 COMMENT '资源文件夹主键，0 表示根目录',
  geojson_json         longtext        NOT NULL COMMENT 'GeoJSON 内容',
  status               char(1)         NOT NULL DEFAULT '0' COMMENT '状态（0正常 1停用）',
  create_by            varchar(64)     DEFAULT '' COMMENT '创建者',
  create_time          datetime        DEFAULT NULL COMMENT '创建时间',
  update_by            varchar(64)     DEFAULT '' COMMENT '更新者',
  update_time          datetime        DEFAULT NULL COMMENT '更新时间',
  remark               varchar(500)    DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (map_id),
  UNIQUE KEY uk_dashboard_map_code (map_code),
  KEY idx_dashboard_map_folder (folder_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='轻量大屏地图 GeoJSON 资源';

-- 兼容旧库：为图库、视频和地图补齐统一资源文件夹字段。
SET @dashboard_asset_folder_column_exists := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'dashboard_asset' AND column_name = 'folder_id'
);
SET @dashboard_asset_folder_alter_sql := IF(
  @dashboard_asset_folder_column_exists = 0,
  'ALTER TABLE dashboard_asset ADD COLUMN folder_id bigint(20) NOT NULL DEFAULT 0 COMMENT ''资源文件夹主键，0 表示根目录'' AFTER asset_name, ADD KEY idx_dashboard_asset_folder (folder_id)',
  'SELECT 1'
);
PREPARE dashboard_asset_folder_alter_stmt FROM @dashboard_asset_folder_alter_sql;
EXECUTE dashboard_asset_folder_alter_stmt;
DEALLOCATE PREPARE dashboard_asset_folder_alter_stmt;

SET @dashboard_map_folder_column_exists := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'dashboard_map_resource' AND column_name = 'folder_id'
);
SET @dashboard_map_folder_alter_sql := IF(
  @dashboard_map_folder_column_exists = 0,
  'ALTER TABLE dashboard_map_resource ADD COLUMN folder_id bigint(20) NOT NULL DEFAULT 0 COMMENT ''资源文件夹主键，0 表示根目录'' AFTER map_name, ADD KEY idx_dashboard_map_folder (folder_id)',
  'SELECT 1'
);
PREPARE dashboard_map_folder_alter_stmt FROM @dashboard_map_folder_alter_sql;
EXECUTE dashboard_map_folder_alter_stmt;
DEALLOCATE PREPARE dashboard_map_folder_alter_stmt;

-- 兼容已经初始化过的数据库：补齐页面文件夹和回收站字段。
SET @dashboard_page_folder_column_exists := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'dashboard_page' AND column_name = 'folder_id'
);
SET @dashboard_page_folder_alter_sql := IF(
  @dashboard_page_folder_column_exists = 0,
  'ALTER TABLE dashboard_page ADD COLUMN folder_id bigint(20) NOT NULL DEFAULT 0 COMMENT ''页面文件夹主键，0 表示根目录'' AFTER page_name',
  'SELECT 1'
);
PREPARE dashboard_page_folder_alter_stmt FROM @dashboard_page_folder_alter_sql;
EXECUTE dashboard_page_folder_alter_stmt;
DEALLOCATE PREPARE dashboard_page_folder_alter_stmt;

SET @dashboard_page_deleted_column_exists := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'dashboard_page' AND column_name = 'is_deleted'
);
SET @dashboard_page_deleted_alter_sql := IF(
  @dashboard_page_deleted_column_exists = 0,
  'ALTER TABLE dashboard_page ADD COLUMN is_deleted char(1) NOT NULL DEFAULT ''0'' COMMENT ''回收站标记（0否 1是）'' AFTER status',
  'SELECT 1'
);
PREPARE dashboard_page_deleted_alter_stmt FROM @dashboard_page_deleted_alter_sql;
EXECUTE dashboard_page_deleted_alter_stmt;
DEALLOCATE PREPARE dashboard_page_deleted_alter_stmt;

SET @dashboard_page_delete_by_column_exists := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'dashboard_page' AND column_name = 'delete_by'
);
SET @dashboard_page_delete_by_alter_sql := IF(
  @dashboard_page_delete_by_column_exists = 0,
  'ALTER TABLE dashboard_page ADD COLUMN delete_by varchar(64) DEFAULT NULL COMMENT ''删除人'' AFTER is_deleted',
  'SELECT 1'
);
PREPARE dashboard_page_delete_by_alter_stmt FROM @dashboard_page_delete_by_alter_sql;
EXECUTE dashboard_page_delete_by_alter_stmt;
DEALLOCATE PREPARE dashboard_page_delete_by_alter_stmt;

SET @dashboard_page_delete_time_column_exists := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'dashboard_page' AND column_name = 'delete_time'
);
SET @dashboard_page_delete_time_alter_sql := IF(
  @dashboard_page_delete_time_column_exists = 0,
  'ALTER TABLE dashboard_page ADD COLUMN delete_time datetime DEFAULT NULL COMMENT ''删除时间'' AFTER delete_by',
  'SELECT 1'
);
PREPARE dashboard_page_delete_time_alter_stmt FROM @dashboard_page_delete_time_alter_sql;
EXECUTE dashboard_page_delete_time_alter_stmt;
DEALLOCATE PREPARE dashboard_page_delete_time_alter_stmt;

CREATE TABLE IF NOT EXISTS dashboard_page_revision (
  revision_id          bigint(20)      NOT NULL AUTO_INCREMENT COMMENT '版本主键',
  page_id              bigint(20)      NOT NULL COMMENT '页面主键',
  version_no           int             NOT NULL COMMENT '页面内版本号',
  status               varchar(20)     NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PREVIEWED/PUBLISHED/ARCHIVED',
  schema_json          longtext        NOT NULL COMMENT '页面 JSON',
  schema_hash          varchar(64)     NOT NULL COMMENT '页面 JSON SHA-256',
  publish_note         varchar(500)    DEFAULT NULL COMMENT '发布说明',
  create_by            varchar(64)     DEFAULT '' COMMENT '创建者',
  create_time          datetime        DEFAULT NULL COMMENT '创建时间',
  update_by            varchar(64)     DEFAULT '' COMMENT '更新者',
  update_time          datetime        DEFAULT NULL COMMENT '更新时间',
  publish_by           varchar(64)     DEFAULT '' COMMENT '发布者',
  publish_time         datetime        DEFAULT NULL COMMENT '发布时间',
  PRIMARY KEY (revision_id),
  UNIQUE KEY uk_dashboard_revision_version (page_id, version_no),
  KEY idx_dashboard_revision_page (page_id),
  CONSTRAINT fk_dashboard_revision_page FOREIGN KEY (page_id) REFERENCES dashboard_page (page_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='轻量大屏页面版本';

CREATE TABLE IF NOT EXISTS dashboard_share (
  share_id             bigint(20)      NOT NULL AUTO_INCREMENT COMMENT '分享主键',
  page_id              bigint(20)      NOT NULL COMMENT '页面主键',
  revision_id          bigint(20)      NOT NULL COMMENT '绑定的发布版本',
  version_mode         varchar(24)     NOT NULL DEFAULT 'FIXED' COMMENT 'FIXED固定版本/FOLLOW_PUBLISHED跟随发布',
  token_hash           char(64)        NOT NULL COMMENT '分享令牌 SHA-256',
  share_token          varchar(128)    DEFAULT NULL COMMENT '授权用户可重新复制的分享令牌',
  expires_at           datetime        DEFAULT NULL COMMENT '过期时间，NULL 表示永久有效',
  status               varchar(20)     NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/REVOKED',
  created_by           varchar(64)     DEFAULT '' COMMENT '创建者',
  created_at           datetime        DEFAULT NULL COMMENT '创建时间',
  last_access_at       datetime        DEFAULT NULL COMMENT '最近访问时间',
  PRIMARY KEY (share_id),
  UNIQUE KEY uk_dashboard_share_token (token_hash),
  KEY idx_dashboard_share_page (page_id),
  CONSTRAINT fk_dashboard_share_page FOREIGN KEY (page_id) REFERENCES dashboard_page (page_id) ON DELETE CASCADE,
  CONSTRAINT fk_dashboard_share_revision FOREIGN KEY (revision_id) REFERENCES dashboard_page_revision (revision_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='轻量大屏只读分享';

-- 一个分享令牌可以授权多个已发布页面；固定版本使用成员绑定，跟随发布只改变解析版本，不扩大成员集合。
-- 旧版 dashboard_share 记录通过下方回填语句自动成为只有入口页的集合。
CREATE TABLE IF NOT EXISTS dashboard_share_page (
  share_id             bigint(20)      NOT NULL COMMENT '分享主键',
  page_id              bigint(20)      NOT NULL COMMENT '授权页面主键',
  revision_id          bigint(20)      NOT NULL COMMENT '授权页面发布版本',
  is_entry             char(1)         NOT NULL DEFAULT '0' COMMENT '是否为分享入口（0否 1是）',
  sort_order           int             NOT NULL DEFAULT 0 COMMENT '页面排序',
  created_at           datetime        DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (share_id, page_id),
  KEY idx_dashboard_share_page_page (page_id),
  CONSTRAINT fk_dashboard_share_page_share FOREIGN KEY (share_id) REFERENCES dashboard_share (share_id) ON DELETE CASCADE,
  CONSTRAINT fk_dashboard_share_page_revision FOREIGN KEY (revision_id) REFERENCES dashboard_page_revision (revision_id) ON DELETE CASCADE,
  CONSTRAINT fk_dashboard_share_page_target FOREIGN KEY (page_id) REFERENCES dashboard_page (page_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='分享授权页面集合';

-- 幂等回填历史单页面分享；不会覆盖用户后来调整过的集合成员。
INSERT IGNORE INTO dashboard_share_page (share_id, page_id, revision_id, is_entry, sort_order, created_at)
SELECT share_id, page_id, revision_id, '1', 0, created_at
FROM dashboard_share;

-- 兼容旧库：永久分享使用 NULL 过期时间。
ALTER TABLE dashboard_share MODIFY COLUMN expires_at datetime NULL COMMENT '过期时间，NULL 表示永久有效';

-- 兼容旧库：新分享保存随机令牌，便于有分享查看权限的用户重新复制地址。
-- 历史记录只保存过哈希，无法反向恢复地址，保持 NULL。
SET @dashboard_share_token_column_exists := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'dashboard_share' AND column_name = 'share_token'
);
SET @dashboard_share_token_alter_sql := IF(
  @dashboard_share_token_column_exists = 0,
  'ALTER TABLE dashboard_share ADD COLUMN share_token varchar(128) DEFAULT NULL COMMENT ''授权用户可重新复制的分享令牌'' AFTER token_hash',
  'SELECT 1'
);
PREPARE dashboard_share_token_alter_stmt FROM @dashboard_share_token_alter_sql;
EXECUTE dashboard_share_token_alter_stmt;
DEALLOCATE PREPARE dashboard_share_token_alter_stmt;

-- 旧链接继续固定原版本；新建链接由服务端显式写入 FOLLOW_PUBLISHED。
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

-- 数据源和推送接入使用各自目录；数据集继续保留 dashboard_dataset_group 及 group_code。
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

CREATE TABLE IF NOT EXISTS dashboard_data_source (
  data_source_id       bigint(20)      NOT NULL AUTO_INCREMENT COMMENT '数据源主键',
  source_code          varchar(64)     NOT NULL COMMENT '数据源编码',
  source_name          varchar(100)    NOT NULL COMMENT '数据源名称',
  folder_id            bigint(20)      NOT NULL DEFAULT 0 COMMENT '数据源文件夹主键，0 表示未分类',
  source_type          varchar(20)     NOT NULL COMMENT 'MYSQL/HTTP/WEBSOCKET',
  config_json          longtext        NOT NULL COMMENT '不含密码和令牌的连接配置',
  secret_ciphertext    longtext        DEFAULT NULL COMMENT '服务端加密的密码或令牌',
  status               varchar(20)     NOT NULL DEFAULT 'ACTIVE' COMMENT 'DRAFT/ACTIVE/DISABLED',
  create_by            varchar(64)     DEFAULT '' COMMENT '创建者',
  create_time          datetime        DEFAULT NULL COMMENT '创建时间',
  update_by            varchar(64)     DEFAULT '' COMMENT '更新者',
  update_time          datetime        DEFAULT NULL COMMENT '更新时间',
  remark               varchar(500)    DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (data_source_id),
  UNIQUE KEY uk_dashboard_data_source_code (source_code),
  KEY idx_dashboard_data_source_folder (folder_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='轻量大屏服务端数据源登记';

CREATE TABLE IF NOT EXISTS dashboard_dataset_group (
  group_id             bigint(20)      NOT NULL AUTO_INCREMENT COMMENT '数据集分组主键',
  group_code           varchar(64)     NOT NULL COMMENT '分组编码',
  group_name           varchar(100)    NOT NULL COMMENT '分组名称',
  parent_id            bigint(20)      NOT NULL DEFAULT 0 COMMENT '父文件夹主键，0 表示根目录',
  sort_order           int             NOT NULL DEFAULT 0 COMMENT '排序',
  create_by            varchar(64)     DEFAULT '' COMMENT '创建者',
  create_time          datetime        DEFAULT NULL COMMENT '创建时间',
  update_by            varchar(64)     DEFAULT '' COMMENT '更新者',
  update_time          datetime        DEFAULT NULL COMMENT '更新时间',
  remark               varchar(500)    DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (group_id),
  UNIQUE KEY uk_dashboard_dataset_group_code (group_code),
  KEY idx_dashboard_dataset_group_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='轻量大屏数据集分组';

-- 兼容旧库：数据集分组按统一文件夹方式支持父子层级。
SET @dashboard_dataset_group_parent_column_exists := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'dashboard_dataset_group' AND column_name = 'parent_id'
);
SET @dashboard_dataset_group_parent_alter_sql := IF(
  @dashboard_dataset_group_parent_column_exists = 0,
  'ALTER TABLE dashboard_dataset_group ADD COLUMN parent_id bigint(20) NOT NULL DEFAULT 0 COMMENT ''父文件夹主键，0 表示根目录'' AFTER group_name, ADD KEY idx_dashboard_dataset_group_parent (parent_id)',
  'SELECT 1'
);
PREPARE dashboard_dataset_group_parent_alter_stmt FROM @dashboard_dataset_group_parent_alter_sql;
EXECUTE dashboard_dataset_group_parent_alter_stmt;
DEALLOCATE PREPARE dashboard_dataset_group_parent_alter_stmt;

CREATE TABLE IF NOT EXISTS dashboard_dataset (
  dataset_id           bigint(20)      NOT NULL AUTO_INCREMENT COMMENT '数据集主键',
  dataset_code         varchar(64)     NOT NULL COMMENT '数据集编码',
  dataset_name         varchar(100)    NOT NULL COMMENT '数据集名称',
  group_code           varchar(64)     NOT NULL DEFAULT '' COMMENT '数据集分组编码',
  data_type            varchar(20)     NOT NULL COMMENT 'SQL/API/JSON/WEBSOCKET',
  config_json          longtext        NOT NULL COMMENT '数据集配置 JSON',
  field_schema_json    longtext        DEFAULT NULL COMMENT '字段定义 JSON',
  param_schema_json    longtext        DEFAULT NULL COMMENT '参数定义 JSON',
  status               varchar(20)     NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/ACTIVE/DISABLED',
  timeout_seconds      int             NOT NULL DEFAULT 10 COMMENT '超时秒数',
  refresh_seconds      int             NOT NULL DEFAULT 30 COMMENT '刷新秒数',
  last_test_at         datetime        DEFAULT NULL COMMENT '最近测试时间',
  last_test_status     varchar(30)     DEFAULT NULL COMMENT '最近测试状态',
  create_by            varchar(64)     DEFAULT '' COMMENT '创建者',
  create_time          datetime        DEFAULT NULL COMMENT '创建时间',
  update_by            varchar(64)     DEFAULT '' COMMENT '更新者',
  update_time          datetime        DEFAULT NULL COMMENT '更新时间',
  remark               varchar(500)    DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (dataset_id),
  UNIQUE KEY uk_dashboard_dataset_code (dataset_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='轻量大屏数据集';

-- 兼容已经初始化过的数据库：MariaDB/MySQL 版本差异较大，使用元数据判断后再补列。
SET @dashboard_dataset_group_column_exists := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'dashboard_dataset' AND column_name = 'group_code'
);
SET @dashboard_dataset_group_alter_sql := IF(
  @dashboard_dataset_group_column_exists = 0,
  'ALTER TABLE dashboard_dataset ADD COLUMN group_code varchar(64) NOT NULL DEFAULT '''' COMMENT ''数据集分组编码'' AFTER dataset_name',
  'SELECT 1'
);
PREPARE dashboard_dataset_group_alter_stmt FROM @dashboard_dataset_group_alter_sql;
EXECUTE dashboard_dataset_group_alter_stmt;
DEALLOCATE PREPARE dashboard_dataset_group_alter_stmt;

-- 大屏菜单（固定 ID 便于本地重复初始化）。
-- 带页面主键的设计/运行页按平台规范由前端 dynamicRoutes 管理，
-- 不在 sys_menu 中维护带参数的隐藏菜单，避免菜单管理与活动菜单高亮失真。
DELETE FROM sys_role_menu WHERE menu_id IN (2003, 2004);
DELETE FROM sys_menu WHERE menu_id IN (2003, 2004);

INSERT IGNORE INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
VALUES
  (2000, '大屏管理', 0, 4, 'dashboard', NULL, NULL, 'Dashboard', 1, 0, 'M', '0', '0', '', 'chart', 'admin', NOW(), '轻量大屏管理'),
  (2001, '页面管理', 2000, 1, 'page', 'dashboard/page/index', NULL, 'DashboardPage', 1, 0, 'C', '0', '0', 'dashboard:page:list', 'list', 'admin', NOW(), '大屏页面管理'),
  (2002, '数据集管理', 2000, 2, 'dataset', 'dashboard/dataset/index', NULL, 'DashboardDataset', 1, 0, 'C', '0', '0', 'dashboard:dataset:list', 'chart', 'admin', NOW(), '大屏数据集管理');

INSERT IGNORE INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
VALUES
  (2005, '资源管理', 2000, 3, 'resource', 'dashboard/resource/index', NULL, 'DashboardResource', 1, 0, 'C', '0', '0', 'dashboard:resource:list', 'radio', 'admin', NOW(), '图库和地图资源');

-- INSERT IGNORE 不会修正历史环境中的排序和图标，因此显式收口现有菜单。
UPDATE sys_menu SET order_num = 3, icon = 'radio' WHERE menu_id = 2005;

INSERT IGNORE INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
VALUES
  (2100, '页面新增', 2001, 1, '#', NULL, NULL, NULL, 1, 0, 'F', '0', '0', 'dashboard:page:add', '#', 'admin', NOW(), ''),
  (2101, '页面修改', 2001, 2, '#', NULL, NULL, NULL, 1, 0, 'F', '0', '0', 'dashboard:page:edit', '#', 'admin', NOW(), ''),
  (2102, '页面删除', 2001, 3, '#', NULL, NULL, NULL, 1, 0, 'F', '0', '0', 'dashboard:page:delete', '#', 'admin', NOW(), ''),
  (2103, '页面预览', 2001, 4, '#', NULL, NULL, NULL, 1, 0, 'F', '0', '0', 'dashboard:page:preview', '#', 'admin', NOW(), ''),
  (2104, '页面发布', 2001, 5, '#', NULL, NULL, NULL, 1, 0, 'F', '0', '0', 'dashboard:page:publish', '#', 'admin', NOW(), ''),
  (2105, '页面回滚', 2001, 6, '#', NULL, NULL, NULL, 1, 0, 'F', '0', '0', 'dashboard:page:rollback', '#', 'admin', NOW(), ''),
  (2106, '回收站查看', 2001, 7, '#', NULL, NULL, NULL, 1, 0, 'F', '0', '0', 'dashboard:page:recycle', '#', 'admin', NOW(), ''),
  (2107, '页面恢复', 2001, 8, '#', NULL, NULL, NULL, 1, 0, 'F', '0', '0', 'dashboard:page:restore', '#', 'admin', NOW(), ''),
  (2108, '彻底删除', 2001, 9, '#', NULL, NULL, NULL, 1, 0, 'F', '0', '0', 'dashboard:page:purge', '#', 'admin', NOW(), ''),
  (2109, '文件夹管理', 2001, 10, '#', NULL, NULL, NULL, 1, 0, 'F', '0', '0', 'dashboard:page:folder', '#', 'admin', NOW(), ''),
  (2110, '数据集编辑', 2002, 1, '#', NULL, NULL, NULL, 1, 0, 'F', '0', '0', 'dashboard:dataset:edit', '#', 'admin', NOW(), ''),
  (2111, '数据集测试', 2002, 2, '#', NULL, NULL, NULL, 1, 0, 'F', '0', '0', 'dashboard:dataset:test', '#', 'admin', NOW(), ''),
  (2112, '分享查看', 2001, 11, '#', NULL, NULL, NULL, 1, 0, 'F', '0', '0', 'dashboard:share:list', '#', 'admin', NOW(), ''),
  (2113, '创建分享', 2001, 12, '#', NULL, NULL, NULL, 1, 0, 'F', '0', '0', 'dashboard:share:create', '#', 'admin', NOW(), ''),
  (2114, '撤销分享', 2001, 13, '#', NULL, NULL, NULL, 1, 0, 'F', '0', '0', 'dashboard:share:revoke', '#', 'admin', NOW(), '');

INSERT IGNORE INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
VALUES
  (2118, '配置页面菜单', 2001, 14, '#', NULL, NULL, NULL, 1, 0, 'F', '0', '0', 'dashboard:page:menu', '#', 'admin', NOW(), '将已发布页面注册为若依菜单');

UPDATE sys_menu SET order_num = 11 WHERE menu_id = 2112;
UPDATE sys_menu SET order_num = 12 WHERE menu_id = 2113;
UPDATE sys_menu SET order_num = 13 WHERE menu_id = 2114;

INSERT IGNORE INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
VALUES
  (2115, '资源编辑', 2005, 1, '#', NULL, NULL, NULL, 1, 0, 'F', '0', '0', 'dashboard:resource:edit', '#', 'admin', NOW(), ''),
  (2116, '资源删除', 2005, 2, '#', NULL, NULL, NULL, 1, 0, 'F', '0', '0', 'dashboard:resource:delete', '#', 'admin', NOW(), ''),
  (2117, '资源文件夹', 2005, 3, '#', NULL, NULL, NULL, 1, 0, 'F', '0', '0', 'dashboard:resource:folder', '#', 'admin', NOW(), '');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 1, menu_id FROM sys_menu WHERE menu_id IN (2000, 2001, 2002, 2005, 2100, 2101, 2102, 2103, 2104, 2105, 2106, 2107, 2108, 2109, 2110, 2111, 2112, 2113, 2114, 2115, 2116, 2117, 2118);
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 2, menu_id FROM sys_menu WHERE menu_id IN (2000, 2001, 2002, 2112, 2106);

INSERT IGNORE INTO dashboard_dataset_group
  (group_code, group_name, sort_order, create_by, create_time, remark)
VALUES
  ('labor-hik', '业务数据', 10, 'admin', NOW(), '业务来源示例'),
  ('demo', '设计器示例', 20, 'admin', NOW(), '仅用于本地样例和自动化测试');

-- 清理上一轮仅用于演示的 visitor 样例（仅匹配固定编码和备注，不触碰用户自建页面/数据集）。
DELETE FROM dashboard_page
 WHERE page_code = 'visitor-overview' AND remark = '首个本地真实数据源验证页面';
DELETE FROM dashboard_dataset
 WHERE dataset_code IN ('visitor-total', 'visitor-by-status', 'visitor-recent', 'visitor-summary-api', 'visitor-live')
   AND create_by = 'admin';

-- 首版来源登记：外部业务规范化数据和受控 API 示例。
-- 真实数据库地址、账号和 API Token 只通过环境变量注入；未完成现场盘点前不写死凭证。
INSERT IGNORE INTO dashboard_dataset
  (dataset_id, dataset_code, dataset_name, group_code, data_type, config_json, field_schema_json, param_schema_json, status, timeout_seconds, refresh_seconds, create_by, create_time, remark)
VALUES
  (1, 'labor-hik-total', '考勤事件总数', 'labor-hik', 'SQL', '{"dataSourceCode":"labor","sql":"SELECT COUNT(*) AS total FROM hik_attendance_event WHERE pro_code = :projectCode"}', '[{"name":"total","title":"事件总数","type":"number"}]', '[{"name":"projectCode","title":"项目编码","type":"STRING","required":true}]', 'ACTIVE', 10, 30, 'admin', NOW(), '读取外部业务规范化事件，只读账号'),
  (2, 'labor-hik-by-direction', '进出方向分布', 'labor-hik', 'SQL', '{"dataSourceCode":"labor","sql":"SELECT direction, COUNT(*) AS count FROM hik_attendance_event WHERE pro_code = :projectCode GROUP BY direction ORDER BY direction"}', '[{"name":"direction","title":"方向","type":"string"},{"name":"count","title":"数量","type":"number"}]', '[{"name":"projectCode","title":"项目编码","type":"STRING","required":true}]', 'ACTIVE', 10, 30, 'admin', NOW(), '字段口径以外部业务事件表为准'),
  (3, 'labor-hik-recent', '最近事件（脱敏）', 'labor-hik', 'SQL', '{"dataSourceCode":"labor","sql":"SELECT event_id AS eventId, hik_person_id AS hikPersonId, CONCAT(LEFT(person_name, 1), ''*'') AS personName, event_time AS eventTime, direction, check_location AS checkLocation, match_status AS matchStatus FROM hik_attendance_event WHERE pro_code = :projectCode ORDER BY event_time DESC LIMIT 20"}', '[{"name":"eventId","title":"事件编号","type":"string"},{"name":"hikPersonId","title":"人员编号","type":"string"},{"name":"personName","title":"姓名（脱敏）","type":"string","mask":true},{"name":"eventTime","title":"事件时间","type":"datetime"},{"name":"direction","title":"方向","type":"string"},{"name":"checkLocation","title":"位置","type":"string"},{"name":"matchStatus","title":"匹配状态","type":"string"}]', '[{"name":"projectCode","title":"项目编码","type":"STRING","required":true}]', 'ACTIVE', 10, 30, 'admin', NOW(), '姓名在 SQL 层脱敏，不展示证件号'),
  (4, 'labor-hik-events-api', '事件 API', 'labor-hik', 'API', '{"endpointCode":"labor.hik.events","method":"GET","response":{"rowsPath":"data.items","totalPath":"data.total"}}', '[{"name":"id","title":"记录 ID","type":"number"},{"name":"eventId","title":"事件编号","type":"string"},{"name":"personName","title":"姓名","type":"string","mask":true},{"name":"eventTime","title":"事件时间","type":"datetime"},{"name":"direction","title":"方向","type":"string"},{"name":"matchStatus","title":"匹配状态","type":"string"},{"name":"total","title":"总记录数（虚拟字段）","type":"number","show":false}]', '[{"name":"page","title":"页码","type":"NUMBER","default":"0"},{"name":"size","title":"每页条数","type":"NUMBER","default":"20"}]', 'ACTIVE', 10, 30, 'admin', NOW(), '调用外部受控 API，项目范围由来源服务端强制执行'),
  (5, 'labor-hik-live', '事件实时通道', 'labor-hik', 'WEBSOCKET', '{"sourceType":"API","endpointCode":"labor.hik.events","method":"GET","response":{"rowsPath":"data.items","totalPath":"data.total"}}', '[{"name":"eventId","title":"事件编号","type":"string"},{"name":"personName","title":"姓名","type":"string","mask":true},{"name":"eventTime","title":"事件时间","type":"datetime"},{"name":"direction","title":"方向","type":"string"}]', '[{"name":"page","title":"页码","type":"NUMBER","default":"0"},{"name":"size","title":"每页条数","type":"NUMBER","default":"20"}]', 'ACTIVE', 10, 10, 'admin', NOW(), '平台 WebSocket 代理外部事件 API，未接入时显示未接入'),
  (6, 'design-json-demo', '设计器 JSON 示例', 'demo', 'JSON', '{"payload":[{"name":"示例一","value":18},{"name":"示例二","value":12}],"rowsPath":""}', '[{"name":"name","title":"名称","type":"string"},{"name":"value","title":"数值","type":"number"}]', '[]', 'ACTIVE', 10, 60, 'admin', NOW(), '仅用于验证 JSON 数据集能力');

UPDATE dashboard_dataset SET group_code = 'labor-hik'
WHERE dataset_code IN ('labor-hik-total', 'labor-hik-by-direction', 'labor-hik-recent', 'labor-hik-events-api', 'labor-hik-live')
  AND (group_code IS NULL OR group_code = '');
UPDATE dashboard_dataset SET group_code = 'demo'
WHERE dataset_code = 'design-json-demo' AND (group_code IS NULL OR group_code = '');

INSERT IGNORE INTO dashboard_page
  (page_id, page_code, page_name, status, current_revision_id, create_by, create_time, remark)
VALUES
  (1, 'labor-hik-overview', '业务考勤数据示例大屏', '0', 1, 'admin', NOW(), '外部业务来源示例页面');

INSERT IGNORE INTO dashboard_page_revision
  (revision_id, page_id, version_no, status, schema_json, schema_hash, publish_note, create_by, create_time, publish_by, publish_time)
VALUES
  (1, 1, 1, 'PUBLISHED', '{"schemaVersion":"1.0","canvas":{"width":1920,"height":1080,"scaleMode":"contain","backgroundColor":"#101827","theme":"dark"},"refresh":{"enabled":true,"seconds":30},"widgets":[{"id":"labor-hik-total-card","type":"metric-card","layout":{"x":48,"y":32,"w":360,"h":150,"z":1},"binding":{"datasetCode":"labor-hik-total","fieldMap":{"value":"total"}},"style":{"title":"考勤事件总数","unit":"条","color":"#4fd1c5"},"interaction":{"clickAction":"none"}},{"id":"labor-hik-direction-chart","type":"bar-chart","layout":{"x":48,"y":220,"w":820,"h":360,"z":1},"binding":{"datasetCode":"labor-hik-by-direction","fieldMap":{"category":"direction","value":"count"}},"style":{"title":"进出方向分布"},"interaction":{"clickAction":"none"}},{"id":"labor-hik-recent-table","type":"table","layout":{"x":900,"y":220,"w":900,"h":360,"z":1},"binding":{"datasetCode":"labor-hik-recent","fieldMap":{}},"style":{"title":"最近事件（脱敏）"},"interaction":{"clickAction":"none"}},{"id":"labor-hik-live-list","type":"realtime-list","layout":{"x":48,"y":610,"w":820,"h":260,"z":1},"binding":{"datasetCode":"labor-hik-live","fieldMap":{}},"style":{"title":"实时事件（WebSocket）"},"interaction":{"clickAction":"none"}},{"id":"labor-hik-api-card","type":"metric-card","layout":{"x":900,"y":32,"w":360,"h":150,"z":1},"binding":{"datasetCode":"labor-hik-events-api","fieldMap":{"value":"total"}},"style":{"title":"API 事件数","unit":"条"},"interaction":{"clickAction":"none"}},{"id":"design-json-chart","type":"pie-chart","layout":{"x":900,"y":610,"w":900,"h":260,"z":1},"binding":{"datasetCode":"design-json-demo","fieldMap":{"category":"name","value":"value"}},"style":{"title":"JSON 数据集示例"},"interaction":{"clickAction":"none"}}]}', SHA2('{"schemaVersion":"1.0","canvas":{"width":1920,"height":1080,"scaleMode":"contain","backgroundColor":"#101827","theme":"dark"},"refresh":{"enabled":true,"seconds":30},"widgets":[{"id":"labor-hik-total-card","type":"metric-card","layout":{"x":48,"y":32,"w":360,"h":150,"z":1},"binding":{"datasetCode":"labor-hik-total","fieldMap":{"value":"total"}},"style":{"title":"考勤事件总数","unit":"条","color":"#4fd1c5"},"interaction":{"clickAction":"none"}},{"id":"labor-hik-direction-chart","type":"bar-chart","layout":{"x":48,"y":220,"w":820,"h":360,"z":1},"binding":{"datasetCode":"labor-hik-by-direction","fieldMap":{"category":"direction","value":"count"}},"style":{"title":"进出方向分布"},"interaction":{"clickAction":"none"}},{"id":"labor-hik-recent-table","type":"table","layout":{"x":900,"y":220,"w":900,"h":360,"z":1},"binding":{"datasetCode":"labor-hik-recent","fieldMap":{}},"style":{"title":"最近事件（脱敏）"},"interaction":{"clickAction":"none"}},{"id":"labor-hik-live-list","type":"realtime-list","layout":{"x":48,"y":610,"w":820,"h":260,"z":1},"binding":{"datasetCode":"labor-hik-live","fieldMap":{}},"style":{"title":"实时事件（WebSocket）"},"interaction":{"clickAction":"none"}},{"id":"labor-hik-api-card","type":"metric-card","layout":{"x":900,"y":32,"w":360,"h":150,"z":1},"binding":{"datasetCode":"labor-hik-events-api","fieldMap":{"value":"total"}},"style":{"title":"API 事件数","unit":"条"},"interaction":{"clickAction":"none"}},{"id":"design-json-chart","type":"pie-chart","layout":{"x":900,"y":610,"w":900,"h":260,"z":1},"binding":{"datasetCode":"design-json-demo","fieldMap":{"category":"name","value":"value"}},"style":{"title":"JSON 数据集示例"},"interaction":{"clickAction":"none"}}]}', 256), '初始业务来源示例页面', 'admin', NOW(), 'admin', NOW());

-- 同步更新已存在的样例展示文案；保留固定编码以兼容已有页面绑定。
UPDATE dashboard_dataset_group SET group_name = '业务数据', remark = '业务来源示例'
WHERE group_code = 'labor-hik' AND (group_name LIKE '%海康%' OR group_name LIKE '%labor%');
UPDATE dashboard_dataset SET
  dataset_name = CASE dataset_code
    WHEN 'labor-hik-total' THEN '考勤事件总数'
    WHEN 'labor-hik-by-direction' THEN '进出方向分布'
    WHEN 'labor-hik-recent' THEN '最近事件（脱敏）'
    WHEN 'labor-hik-events-api' THEN '事件 API'
    WHEN 'labor-hik-live' THEN '事件实时通道'
    ELSE dataset_name END,
  remark = REPLACE(REPLACE(REPLACE(REPLACE(remark, 'labor 海康', '外部业务'), 'labor ', '外部'), '海康', '业务'), 'labor', '外部')
WHERE dataset_code IN ('labor-hik-total', 'labor-hik-by-direction', 'labor-hik-recent', 'labor-hik-events-api', 'labor-hik-live');
UPDATE dashboard_page SET page_name = '业务考勤数据示例大屏', remark = '外部业务来源示例页面'
WHERE page_code = 'labor-hik-overview' AND (page_name LIKE '%海康%' OR remark LIKE '%labor%' OR remark LIKE '%海康%');
UPDATE dashboard_page_revision
SET schema_json = REPLACE(REPLACE(REPLACE(REPLACE(schema_json, '海康考勤事件总数', '考勤事件总数'), '最近海康事件', '最近事件'), '海康实时事件', '实时事件'), '海康 API 事件数', 'API 事件数'),
    schema_hash = SHA2(schema_json, 256)
WHERE revision_id = 1 AND page_id = 1 AND (schema_json LIKE '%海康%' OR schema_json LIKE '%labor%');

-- 示例页面默认声明本地项目过滤器，避免 SQL 数据集因缺少必填 projectCode 而显示配置错误。
-- 仅在初始化样例尚未声明过滤器时补齐，不覆盖后续设计器修改。
UPDATE dashboard_page_revision
SET schema_json = JSON_SET(schema_json, '$.filters', JSON_ARRAY(JSON_OBJECT(
  'id', 'project-code', 'label', '项目编码', 'parameter', 'projectCode', 'type', 'STRING', 'defaultValue', 'demo',
  'targetWidgetIds', JSON_ARRAY('labor-hik-total-card', 'labor-hik-direction-chart', 'labor-hik-recent-table')
))),
    schema_hash = SHA2(schema_json, 256)
WHERE revision_id = 1 AND page_id = 1 AND JSON_EXTRACT(schema_json, '$.filters') IS NULL;
