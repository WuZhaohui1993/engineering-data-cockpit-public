-- 工程数据驾驶舱第三方数据接入 MVP 迁移
-- 只新增接入业务表和菜单，不删除或覆盖既有页面、数据集和来源。

-- 一个 HTTP 来源可以登记多个受控 endpoint。旧的 dashboard_data_source.config_json
-- 仍保留作为兼容来源配置；新 API 数据集使用 sourceCode + endpointCode 复合引用。
CREATE TABLE IF NOT EXISTS dashboard_data_source_endpoint (
  endpoint_id            bigint(20)      NOT NULL AUTO_INCREMENT COMMENT 'HTTP endpoint 主键',
  data_source_id         bigint(20)      NOT NULL COMMENT '所属数据源主键',
  endpoint_code          varchar(64)     NOT NULL COMMENT '来源内稳定 endpoint 编码',
  endpoint_name          varchar(100)    NOT NULL COMMENT 'endpoint 名称',
  path                   varchar(512)    NOT NULL COMMENT '受控相对路径',
  method                 varchar(10)     NOT NULL DEFAULT 'GET' COMMENT 'GET/POST',
  request_content_type   varchar(32)     NOT NULL DEFAULT 'NONE' COMMENT 'NONE/JSON/FORM_URLENCODED',
  response_type          varchar(32)     NOT NULL DEFAULT 'JSON' COMMENT 'JSON/BINARY_MEDIA',
  auth_provider          varchar(48)     NOT NULL DEFAULT 'INHERIT' COMMENT '认证 provider',
  credential_ref         varchar(128)    DEFAULT NULL COMMENT '外部密钥引用',
  config_json            longtext        NOT NULL COMMENT '成功规则、参数和容量策略 JSON',
  credential_ciphertext  longtext        DEFAULT NULL COMMENT 'endpoint 级加密凭证',
  contract_version       varchar(64)     DEFAULT '1.0' COMMENT '契约版本',
  status                 varchar(20)     NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/ACTIVE/DISABLED',
  last_test_at           datetime        DEFAULT NULL COMMENT '最近测试时间',
  last_test_status       varchar(30)     DEFAULT NULL COMMENT '最近测试质量',
  last_request_id        varchar(64)     DEFAULT NULL COMMENT '最近平台请求 ID',
  last_success_at        datetime        DEFAULT NULL COMMENT '最近成功时间',
  consecutive_failures   int             NOT NULL DEFAULT 0 COMMENT '连续失败次数',
  last_error_code        varchar(48)     DEFAULT NULL COMMENT '最近错误码',
  create_by              varchar(64)     DEFAULT '',
  create_time            datetime        DEFAULT NULL,
  update_by              varchar(64)     DEFAULT '',
  update_time            datetime        DEFAULT NULL,
  remark                 varchar(500)    DEFAULT NULL,
  PRIMARY KEY (endpoint_id),
  UNIQUE KEY uk_dashboard_endpoint_source_code (data_source_id, endpoint_code),
  KEY idx_dashboard_endpoint_source (data_source_id),
  KEY idx_dashboard_endpoint_status (status),
  CONSTRAINT fk_dashboard_endpoint_source FOREIGN KEY (data_source_id)
    REFERENCES dashboard_data_source (data_source_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='第三方 HTTP endpoint 登记';

-- 入站接入方登记。integrationCode 与出站 sourceCode 使用不同命名空间。
CREATE TABLE IF NOT EXISTS dashboard_integration (
  integration_id        bigint(20)      NOT NULL AUTO_INCREMENT COMMENT '接入方主键',
  integration_code      varchar(64)     NOT NULL COMMENT '入站接入方编码',
  integration_name      varchar(100)    NOT NULL COMMENT '接入方名称',
  folder_id             bigint(20)      NOT NULL DEFAULT 0 COMMENT '推送接入文件夹主键，0 表示未分类',
  environment           varchar(20)     NOT NULL DEFAULT 'TEST' COMMENT 'SANDBOX/TEST/PRODUCTION',
  profile               varchar(32)     NOT NULL DEFAULT 'EVENT' COMMENT 'EVENT/SNAPSHOT/RECORD/FILE_NOTIFICATION',
  schema_version        varchar(64)     NOT NULL DEFAULT '1.0' COMMENT '当前 schema 版本',
  endpoint_code         varchar(64)     NOT NULL DEFAULT 'events' COMMENT '入站接口编码',
  project_scope_json    longtext        DEFAULT NULL COMMENT '项目/组织范围 JSON',
  config_json           longtext        NOT NULL COMMENT '时间窗、批量、限流和处理策略 JSON',
  network_profile       varchar(32)     NOT NULL DEFAULT 'PUBLIC_HTTPS' COMMENT '网络策略',
  allowed_ip_json       longtext        DEFAULT NULL COMMENT '允许的 IP/CIDR JSON',
  timezone              varchar(64)     NOT NULL DEFAULT 'Asia/Shanghai' COMMENT '来源时区',
  max_batch_items       int             NOT NULL DEFAULT 500 COMMENT '单批条数上限',
  max_body_bytes        int             NOT NULL DEFAULT 1048576 COMMENT '请求体上限',
  rate_limit            int             NOT NULL DEFAULT 10 COMMENT '每秒请求数',
  concurrency_limit     int             NOT NULL DEFAULT 20 COMMENT '并发上限',
  raw_retention_days    int             NOT NULL DEFAULT 30 COMMENT '原始体保留天数',
  status                varchar(20)     NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/ACTIVE/PAUSED/REVOKED',
  create_by             varchar(64)     DEFAULT '',
  create_time           datetime        DEFAULT NULL,
  update_by             varchar(64)     DEFAULT '',
  update_time           datetime        DEFAULT NULL,
  remark                varchar(500)    DEFAULT NULL,
  PRIMARY KEY (integration_id),
  UNIQUE KEY uk_dashboard_integration_code (integration_code),
  KEY idx_dashboard_integration_folder (folder_id),
  KEY idx_dashboard_integration_status (status),
  KEY idx_dashboard_integration_environment (environment)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='第三方入站接入方';

CREATE TABLE IF NOT EXISTS dashboard_integration_key (
  integration_key_id    bigint(20)      NOT NULL AUTO_INCREMENT COMMENT '接入密钥主键',
  integration_id        bigint(20)      NOT NULL COMMENT '接入方主键',
  key_id                varchar(128)    NOT NULL COMMENT '认证主体标识',
  provider              varchar(48)     NOT NULL COMMENT 'API_KEY/BEARER/HMAC_V1',
  identity_value        varchar(255)    DEFAULT NULL COMMENT 'HMAC 身份标识',
  credential_ref        varchar(128)    DEFAULT NULL COMMENT '外部密钥引用',
  secret_ciphertext     longtext        NOT NULL COMMENT '加密密钥',
  valid_from            datetime        DEFAULT NULL,
  valid_to              datetime        DEFAULT NULL,
  status                varchar(20)     NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/REVOKED',
  last_used_at          datetime        DEFAULT NULL,
  create_by             varchar(64)     DEFAULT '',
  create_time           datetime        DEFAULT NULL,
  revoke_by             varchar(64)     DEFAULT NULL,
  revoke_time           datetime        DEFAULT NULL,
  remark                varchar(500)    DEFAULT NULL,
  PRIMARY KEY (integration_key_id),
  UNIQUE KEY uk_dashboard_integration_key (integration_id, key_id),
  KEY idx_dashboard_integration_key_status (integration_id, status),
  CONSTRAINT fk_dashboard_integration_key FOREIGN KEY (integration_id)
    REFERENCES dashboard_integration (integration_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='第三方入站认证密钥';

CREATE TABLE IF NOT EXISTS dashboard_integration_nonce (
  nonce_id              bigint(20)      NOT NULL AUTO_INCREMENT,
  integration_id        bigint(20)      NOT NULL,
  key_id                varchar(128)    NOT NULL,
  nonce                 varchar(256)    NOT NULL,
  expires_at            datetime        NOT NULL,
  created_at            datetime        NOT NULL,
  PRIMARY KEY (nonce_id),
  UNIQUE KEY uk_dashboard_integration_nonce (integration_id, key_id, nonce),
  KEY idx_dashboard_integration_nonce_expire (expires_at),
  CONSTRAINT fk_dashboard_integration_nonce FOREIGN KEY (integration_id)
    REFERENCES dashboard_integration (integration_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='入站签名 nonce 防重放台账';

CREATE TABLE IF NOT EXISTS dashboard_integration_batch (
  batch_id                    bigint(20)      NOT NULL AUTO_INCREMENT COMMENT '接收批次主键',
  integration_id              bigint(20)      NOT NULL,
  endpoint_code               varchar(64)     NOT NULL,
  normalized_idempotency_key  varchar(256)    NOT NULL,
  message_id                  varchar(256)    DEFAULT NULL,
  external_request_id         varchar(256)    DEFAULT NULL,
  body_hash                   char(64)        NOT NULL,
  platform_request_id         varchar(64)     NOT NULL,
  schema_version              varchar(64)     NOT NULL,
  status                      varchar(24)     NOT NULL DEFAULT 'RECEIVED' COMMENT 'RECEIVED/VALIDATED/PROCESSING/ACCEPTED/PARTIAL/FAILED/CONFLICT',
  accepted_count              int             NOT NULL DEFAULT 0,
  processing_count            int             NOT NULL DEFAULT 0,
  duplicate_count             int             NOT NULL DEFAULT 0,
  rejected_count              int             NOT NULL DEFAULT 0,
  retryable_count             int             NOT NULL DEFAULT 0,
  response_json               longtext        DEFAULT NULL COMMENT '最近回执 JSON',
  raw_body_ciphertext         longtext        DEFAULT NULL COMMENT '按保留策略加密保存的原始报文',
  error_code                  varchar(48)     DEFAULT NULL,
  error_message               varchar(500)    DEFAULT NULL,
  received_at                 datetime        NOT NULL,
  processed_at                datetime        DEFAULT NULL,
  expires_at                  datetime        DEFAULT NULL,
  details_expired_at           datetime        DEFAULT NULL COMMENT '成功明细清理时间，保留去重及回执摘要',
  PRIMARY KEY (batch_id),
  UNIQUE KEY uk_dashboard_integration_batch_idempotency (integration_id, endpoint_code, normalized_idempotency_key),
  KEY idx_dashboard_integration_batch_message (integration_id, message_id),
  KEY idx_dashboard_integration_batch_request (platform_request_id),
  KEY idx_dashboard_integration_batch_status (integration_id, status),
  KEY idx_inbound_retention_success (integration_id, status, details_expired_at, processed_at),
  KEY idx_inbound_retention_raw (integration_id, status, expires_at),
  CONSTRAINT fk_dashboard_integration_batch FOREIGN KEY (integration_id)
    REFERENCES dashboard_integration (integration_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='第三方入站接收批次';

CREATE TABLE IF NOT EXISTS dashboard_integration_message (
  integration_message_id    bigint(20)      NOT NULL AUTO_INCREMENT,
  batch_id                  bigint(20)      NOT NULL,
  integration_id            bigint(20)      NOT NULL,
  profile                   varchar(32)     NOT NULL,
  business_key              varchar(256)    NOT NULL,
  item_index                int             NOT NULL DEFAULT 0,
  project_code              varchar(128)    DEFAULT NULL,
  business_time             datetime        DEFAULT NULL,
  payload_ciphertext        longtext        DEFAULT NULL,
  payload_hash              char(64)        DEFAULT NULL,
  normalized_json            longtext        DEFAULT NULL,
  status                    varchar(24)     NOT NULL DEFAULT 'RECEIVED' COMMENT 'RECEIVED/PROCESSING/ACCEPTED/DUPLICATE/REJECTED/FAILED/DEAD_LETTER/CONFLICT',
  retry_count               int             NOT NULL DEFAULT 0,
  error_code                varchar(48)     DEFAULT NULL,
  error_message             varchar(500)    DEFAULT NULL,
  received_at               datetime        NOT NULL,
  processed_at              datetime        DEFAULT NULL,
  PRIMARY KEY (integration_message_id),
  UNIQUE KEY uk_dashboard_integration_business_key (integration_id, profile, business_key),
  KEY idx_dashboard_integration_message_batch (batch_id),
  KEY idx_dashboard_integration_message_status (integration_id, status),
  CONSTRAINT fk_dashboard_integration_message_batch FOREIGN KEY (batch_id)
    REFERENCES dashboard_integration_batch (batch_id) ON DELETE CASCADE,
  CONSTRAINT fk_dashboard_integration_message_integration FOREIGN KEY (integration_id)
    REFERENCES dashboard_integration (integration_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='第三方入站消息台账';

CREATE TABLE IF NOT EXISTS dashboard_integration_record (
  record_id                 bigint(20)      NOT NULL AUTO_INCREMENT,
  integration_id            bigint(20)      NOT NULL,
  environment               varchar(20)     NOT NULL,
  profile                   varchar(32)     NOT NULL,
  business_key              varchar(256)    NOT NULL,
  project_code              varchar(128)    DEFAULT NULL,
  data_json                 longtext        NOT NULL COMMENT '标准化数据 JSON',
  source_business_time      datetime        DEFAULT NULL,
  source_updated_at         datetime        DEFAULT NULL,
  received_at               datetime        NOT NULL,
  updated_at                datetime        NOT NULL,
  version_no                bigint(20)      NOT NULL DEFAULT 1,
  status                    varchar(24)     NOT NULL DEFAULT 'ACTIVE',
  PRIMARY KEY (record_id),
  UNIQUE KEY uk_dashboard_integration_record (integration_id, profile, business_key),
  KEY idx_dashboard_integration_record_project (integration_id, project_code),
  KEY idx_dashboard_integration_record_time (integration_id, source_business_time),
  KEY idx_inbound_record_retention (integration_id, updated_at),
  CONSTRAINT fk_dashboard_integration_record FOREIGN KEY (integration_id)
    REFERENCES dashboard_integration (integration_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='第三方入站标准化数据';

CREATE TABLE IF NOT EXISTS dashboard_integration_media_ref (
  media_ref                 char(64)        NOT NULL COMMENT '高熵不透明媒体引用',
  source_code               varchar(64)     NOT NULL,
  endpoint_code             varchar(64)     NOT NULL,
  dataset_code              varchar(64)     DEFAULT NULL COMMENT '候选引用所属数据集',
  media_profile             varchar(32)     NOT NULL DEFAULT 'IMAGE',
  business_key              varchar(256)    DEFAULT NULL,
  form_params_ciphertext    longtext        NOT NULL,
  page_id                   bigint(20)      DEFAULT NULL,
  revision_id               bigint(20)      DEFAULT NULL,
  share_id                  bigint(20)      DEFAULT NULL,
  created_at                datetime        NOT NULL,
  expires_at                datetime        NOT NULL,
  status                    varchar(20)     NOT NULL DEFAULT 'ACTIVE' COMMENT 'CANDIDATE/CONSUMED/ACTIVE/REVOKED',
  PRIMARY KEY (media_ref),
  KEY idx_dashboard_media_ref_expire (expires_at),
  KEY idx_dashboard_media_ref_page (page_id, revision_id),
  KEY idx_dashboard_media_ref_share (share_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='第三方媒体不透明引用';

-- 兼容已执行过早期接入脚本的本地/测试库。
SET @dashboard_media_dataset_column_exists := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 'dashboard_integration_media_ref'
    AND column_name = 'dataset_code'
);
SET @dashboard_media_dataset_alter_sql := IF(
  @dashboard_media_dataset_column_exists = 0,
  'ALTER TABLE dashboard_integration_media_ref ADD COLUMN dataset_code varchar(64) DEFAULT NULL COMMENT ''候选引用所属数据集'' AFTER endpoint_code',
  'SELECT 1'
);
PREPARE dashboard_media_dataset_alter_stmt FROM @dashboard_media_dataset_alter_sql;
EXECUTE dashboard_media_dataset_alter_stmt;
DEALLOCATE PREPARE dashboard_media_dataset_alter_stmt;

-- 第三方接入管理菜单仅授权管理员角色；普通角色权限不自动扩大。
INSERT IGNORE INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
VALUES
  (2006, '第三方接入', 2000, 4, 'integration', 'dashboard/integration/index', NULL, 'DashboardIntegration', 1, 0, 'C', '0', '0', 'dashboard:integration:list', 'link', 'admin', NOW(), '第三方数据接入与处理台账');

INSERT IGNORE INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
VALUES
  (2120, '接入方新增', 2006, 1, '#', NULL, NULL, NULL, 1, 0, 'F', '0', '0', 'dashboard:integration:add', '#', 'admin', NOW(), ''),
  (2121, '接入方修改', 2006, 2, '#', NULL, NULL, NULL, 1, 0, 'F', '0', '0', 'dashboard:integration:edit', '#', 'admin', NOW(), ''),
  (2122, '接入方查看', 2006, 3, '#', NULL, NULL, NULL, 1, 0, 'F', '0', '0', 'dashboard:integration:view', '#', 'admin', NOW(), ''),
  (2123, '密钥管理', 2006, 4, '#', NULL, NULL, NULL, 1, 0, 'F', '0', '0', 'dashboard:integration:key', '#', 'admin', NOW(), ''),
  (2124, '接入方测试', 2006, 5, '#', NULL, NULL, NULL, 1, 0, 'F', '0', '0', 'dashboard:integration:test', '#', 'admin', NOW(), ''),
  (2125, '接入方暂停', 2006, 6, '#', NULL, NULL, NULL, 1, 0, 'F', '0', '0', 'dashboard:integration:pause', '#', 'admin', NOW(), ''),
  (2126, '接入方吊销', 2006, 7, '#', NULL, NULL, NULL, 1, 0, 'F', '0', '0', 'dashboard:integration:revoke', '#', 'admin', NOW(), ''),
  (2127, '死信查看', 2006, 8, '#', NULL, NULL, NULL, 1, 0, 'F', '0', '0', 'dashboard:integration:deadletter', '#', 'admin', NOW(), ''),
  (2128, '死信重放', 2006, 9, '#', NULL, NULL, NULL, 1, 0, 'F', '0', '0', 'dashboard:integration:replay', '#', 'admin', NOW(), ''),
  (2129, '接入方删除', 2006, 10, '#', NULL, NULL, NULL, 1, 0, 'F', '0', '0', 'dashboard:integration:delete', '#', 'admin', NOW(), '');

-- INSERT IGNORE 只负责补缺；以下 UPDATE 同步修正已经存在但内容过期的同号菜单。
UPDATE sys_menu
SET menu_name = '第三方接入', parent_id = 2000, order_num = 4,
    path = 'integration', component = 'dashboard/integration/index', query = NULL,
    route_name = 'DashboardIntegration', is_frame = 1, is_cache = 0,
    menu_type = 'C', visible = '0', status = '0',
    perms = IF(perms='dashboard:access:list',perms,'dashboard:integration:list'), icon = 'link',
    update_by = 'admin', update_time = NOW(), remark = '第三方数据接入与处理台账'
WHERE menu_id = 2006;

UPDATE sys_menu
SET parent_id = 2006,
    menu_name = CASE menu_id
      WHEN 2120 THEN '接入方新增' WHEN 2121 THEN '接入方修改'
      WHEN 2122 THEN '接入方查看' WHEN 2123 THEN '密钥管理'
      WHEN 2124 THEN '接入方测试' WHEN 2125 THEN '接入方暂停'
      WHEN 2126 THEN '接入方吊销' WHEN 2127 THEN '死信查看'
      WHEN 2128 THEN '死信重放' WHEN 2129 THEN '接入方删除' END,
    order_num = menu_id - 2119, path = '#', component = NULL, query = NULL,
    route_name = NULL, is_frame = 1, is_cache = 0, menu_type = 'F',
    visible = '0', status = '0',
    perms = CASE menu_id
      WHEN 2120 THEN 'dashboard:integration:add'
      WHEN 2121 THEN 'dashboard:integration:edit'
      WHEN 2122 THEN 'dashboard:integration:view'
      WHEN 2123 THEN 'dashboard:integration:key'
      WHEN 2124 THEN 'dashboard:integration:test'
      WHEN 2125 THEN 'dashboard:integration:pause'
      WHEN 2126 THEN 'dashboard:integration:revoke'
      WHEN 2127 THEN 'dashboard:integration:deadletter'
      WHEN 2128 THEN 'dashboard:integration:replay'
      WHEN 2129 THEN 'dashboard:integration:delete' END,
    icon = '#', update_by = 'admin', update_time = NOW()
WHERE menu_id BETWEEN 2120 AND 2129;

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 1, menu_id FROM sys_menu WHERE menu_id IN (2006,2120,2121,2122,2123,2124,2125,2126,2127,2128,2129);
