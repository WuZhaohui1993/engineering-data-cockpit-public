-- 接入整合：只增加运行状态表，旧来源、数据集编码及对外路径保持兼容。
CREATE TABLE IF NOT EXISTS dashboard_integration_lease (
  lease_key varchar(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  owner varchar(64) NOT NULL,
  expires_at datetime(3) NOT NULL,
  PRIMARY KEY (lease_key), KEY idx_lease_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS dashboard_integration_snapshot (
  snapshot_key varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  source_code varchar(64) NOT NULL,
  endpoint_code varchar(64) NOT NULL,
  payload_ciphertext longtext NOT NULL,
  captured_at datetime(3) NOT NULL,
  fresh_until datetime(3) NOT NULL,
  expires_at datetime(3) NOT NULL,
  PRIMARY KEY (snapshot_key), KEY idx_snapshot_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS dashboard_integration_audit (
  audit_id bigint NOT NULL AUTO_INCREMENT,
  category varchar(32) NOT NULL,
  resource_code varchar(128) NOT NULL,
  request_id varchar(64) DEFAULT '',
  outcome varchar(48) NOT NULL,
  actor varchar(64) DEFAULT '',
  byte_count bigint NOT NULL DEFAULT 0,
  duration_ms bigint NOT NULL DEFAULT 0,
  created_at datetime(3) NOT NULL,
  PRIMARY KEY (audit_id), KEY idx_audit_time (created_at), KEY idx_audit_resource (resource_code, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS dashboard_integration_alert (
  alert_id bigint NOT NULL AUTO_INCREMENT,
  alert_key varchar(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  category varchar(32) NOT NULL DEFAULT 'OUTBOUND',
  resource_code varchar(128) NOT NULL,
  error_code varchar(48) NOT NULL,
  occurrences bigint NOT NULL DEFAULT 1,
  status varchar(16) NOT NULL DEFAULT 'OPEN',
  first_at datetime(3) NOT NULL,
  last_at datetime(3) NOT NULL,
  acknowledged_by varchar(64) DEFAULT '',
  PRIMARY KEY (alert_id), UNIQUE KEY uk_alert_key (alert_key), KEY idx_alert_time (last_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS dashboard_integration_signal (
  integration_id bigint NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  updated_at datetime(3) NOT NULL,
  PRIMARY KEY (integration_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 将导航权限与入站管理权限分离。迁移原权限到按钮，保持现有角色的后台操作权限。
INSERT IGNORE INTO sys_menu (menu_id,menu_name,parent_id,order_num,path,menu_type,visible,status,perms,icon,create_by,create_time)
VALUES (2130,'推送接入列表',2006,11,'#','F','0','0','dashboard:integration:list','#','admin',NOW()),
       (2131,'接入运维查看',2006,12,'#','F','0','0','dashboard:integration:monitor','#','admin',NOW()),
       (2132,'接入告警确认',2006,13,'#','F','0','0','dashboard:integration:ack','#','admin',NOW());
INSERT IGNORE INTO sys_role_menu (role_id,menu_id)
SELECT rm.role_id,2130 FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id=rm.menu_id
WHERE m.menu_id=2006 AND m.perms='dashboard:integration:list';
UPDATE sys_menu SET menu_name='数据接入管理', perms='dashboard:access:list', update_by='admin',update_time=NOW()
WHERE menu_id=2006;
-- 仅补导航资格；原有来源、endpoint、密钥、重放的接口权限均不扩大。
INSERT IGNORE INTO sys_role_menu (role_id,menu_id)
SELECT role_id,2006 FROM sys_role_menu WHERE menu_id=2002;
INSERT IGNORE INTO sys_role_menu (role_id,menu_id) VALUES (1,2130),(1,2131),(1,2132);
