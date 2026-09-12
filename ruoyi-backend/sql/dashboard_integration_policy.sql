-- 公共运维保留策略；接入基础/operations/data_management 表和菜单已存在后执行。
-- 可重复执行；不删除现有日志，不修改接入方期限，不扩大普通角色权限。
CREATE TABLE IF NOT EXISTS dashboard_integration_policy (
  policy_key varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  config_json varchar(1000) NOT NULL,
  update_by varchar(64) DEFAULT '',
  update_time datetime NOT NULL,
  PRIMARY KEY (policy_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='接入公共日志保留策略';

-- 缺项沿用后端安全默认值；保留环境变量定义的审计保留期，不插入覆盖策略。
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT 2133,'接入保留策略配置',2006,17,'#','F','0','0','dashboard:integration:policy','#','admin',NOW(),'公共日志保留策略，不包含业务记录期限'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id=2133)
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='dashboard:integration:policy');
UPDATE sys_menu SET menu_name='接入保留策略配置',parent_id=2006,order_num=17,path='#',menu_type='F',visible='0',status='0',icon='#'
WHERE menu_id=2133 AND perms='dashboard:integration:policy';
INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
SELECT 1,menu_id FROM sys_menu WHERE perms='dashboard:integration:policy';
