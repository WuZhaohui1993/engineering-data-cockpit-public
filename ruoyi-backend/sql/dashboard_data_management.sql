-- 单一“数据管理”入口：迁移菜单层次，不改变任何后台操作权限字符串。
UPDATE sys_menu SET menu_name='数据管理',order_num=2,icon='chart',
  update_by='admin',update_time=NOW(),remark='数据集、数据源、推送接入和运维的统一入口'
WHERE menu_id=2006 AND component='dashboard/integration/index';

-- 保留原数据集列表权限及角色授权，将可导航菜单转为按钮权限。
UPDATE sys_menu SET menu_name='数据集查看',parent_id=2006,menu_type='F',
  path='#',component=NULL,query=NULL,route_name=NULL,icon='#',order_num=1,
  update_by='admin',update_time=NOW()
WHERE menu_id=2002 AND perms='dashboard:dataset:list';
UPDATE sys_menu SET parent_id=2006,order_num=CASE menu_id WHEN 2110 THEN 2 WHEN 2111 THEN 3 END,
  update_by='admin',update_time=NOW()
WHERE menu_id IN (2110,2111) AND perms IN ('dashboard:dataset:edit','dashboard:dataset:test');
UPDATE sys_menu SET order_num=menu_id-2116,update_by='admin',update_time=NOW()
WHERE parent_id=2006 AND menu_id BETWEEN 2120 AND 2132;

-- 仅补已具备数据集查看权限的角色的入口；不增加入站、密钥、重放或运维权限。
INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
SELECT role_id,2006 FROM sys_role_menu WHERE menu_id=2002;
