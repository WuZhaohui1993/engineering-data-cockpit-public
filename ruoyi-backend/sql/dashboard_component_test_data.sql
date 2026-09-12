-- 大屏组件测试数据集
--
-- 用途：为设计器和运行态的全部数据驱动组件提供可重复、无外部依赖的测试数据。
-- 边界：仅写入 component-test 分组及 test-component-* 固定编码，不代表真实业务口径。

START TRANSACTION;

INSERT INTO dashboard_dataset_group
  (group_code, group_name, sort_order, create_by, create_time, update_by, update_time, remark)
VALUES
  ('component-test', '大屏组件测试', 30, 'admin', NOW(), 'admin', NOW(), '仅用于本地组件渲染、绑定和交互测试')
ON DUPLICATE KEY UPDATE
  group_name = VALUES(group_name),
  sort_order = VALUES(sort_order),
  update_by = 'admin',
  update_time = NOW(),
  remark = VALUES(remark);

INSERT INTO dashboard_dataset
  (dataset_code, dataset_name, group_code, data_type, config_json, field_schema_json, param_schema_json,
   status, timeout_seconds, refresh_seconds, create_by, create_time, update_by, update_time, remark)
VALUES
  (
    'test-component-kpi',
    '组件测试-指标与状态',
    'component-test',
    'JSON',
    '{"mode":"STATIC","payload":[{"label":"项目综合完成率","value":82.6,"suffix":"%","compareValue":3.8,"compareLabel":"较上月","compareState":"up","targetValue":90,"color":"#35d4b0","statusLabel":"运行正常"}],"rowsPath":"","rowLimit":10}',
    '[{"name":"label","title":"指标名称","type":"string","show":true},{"name":"value","title":"当前值","type":"number","show":true,"aggregate":"avg"},{"name":"suffix","title":"单位","type":"string","show":true},{"name":"compareValue","title":"对比值","type":"number","show":true},{"name":"compareLabel","title":"对比说明","type":"string","show":true},{"name":"compareState","title":"变化方向","type":"string","show":true},{"name":"targetValue","title":"目标值","type":"number","show":true},{"name":"color","title":"状态颜色","type":"string","show":true},{"name":"statusLabel","title":"状态名称","type":"string","show":true}]',
    '[]', 'ACTIVE', 10, 60, 'admin', NOW(), 'admin', NOW(),
    '大屏组件测试专用：指标卡、翻牌、统计、仪表盘、进度图和颜色块'
  ),
  (
    'test-component-series',
    '组件测试-分类与多系列',
    'component-test',
    'JSON',
    '{"mode":"STATIC","payload":[{"category":"一月","sequence":1,"actualValue":58,"planValue":62,"lastYearValue":49,"rate":61.7},{"category":"二月","sequence":2,"actualValue":64,"planValue":66,"lastYearValue":55,"rate":65.2},{"category":"三月","sequence":3,"actualValue":71,"planValue":70,"lastYearValue":60,"rate":69.8},{"category":"四月","sequence":4,"actualValue":68,"planValue":74,"lastYearValue":63,"rate":72.4},{"category":"五月","sequence":5,"actualValue":79,"planValue":78,"lastYearValue":69,"rate":77.1},{"category":"六月","sequence":6,"actualValue":86,"planValue":82,"lastYearValue":73,"rate":81.6},{"category":"七月","sequence":7,"actualValue":91,"planValue":88,"lastYearValue":80,"rate":86.3},{"category":"八月","sequence":8,"actualValue":95,"planValue":94,"lastYearValue":84,"rate":90.5},{"category":"九月","sequence":9,"actualValue":88,"planValue":96,"lastYearValue":82,"rate":87.2},{"category":"十月","sequence":10,"actualValue":102,"planValue":100,"lastYearValue":91,"rate":93.8},{"category":"十一月","sequence":11,"actualValue":108,"planValue":106,"lastYearValue":96,"rate":96.4},{"category":"十二月","sequence":12,"actualValue":116,"planValue":112,"lastYearValue":103,"rate":98.1}],"rowsPath":"","rowLimit":100}',
    '[{"name":"category","title":"月份","type":"string","show":true},{"name":"sequence","title":"序号/X 值","type":"number","show":true,"sortable":true},{"name":"actualValue","title":"实际值","type":"number","show":true,"sortable":true,"aggregate":"sum"},{"name":"planValue","title":"计划值","type":"number","show":true,"sortable":true,"aggregate":"sum"},{"name":"lastYearValue","title":"上年值","type":"number","show":true,"sortable":true,"aggregate":"sum"},{"name":"rate","title":"完成率","type":"number","show":true,"sortable":true,"aggregate":"avg"}]',
    '[]', 'ACTIVE', 10, 60, 'admin', NOW(), 'admin', NOW(),
    '大屏组件测试专用：分类图表、多系列图表、散点图、文字云和环形文字'
  ),
  (
    'test-component-calendar',
    '组件测试-日历热力',
    'component-test',
    'JSON',
    '{"mode":"STATIC","payload":[{"date":"2026-08-01","value":12},{"date":"2026-08-02","value":18},{"date":"2026-08-03","value":9},{"date":"2026-08-04","value":26},{"date":"2026-08-05","value":31},{"date":"2026-08-06","value":22},{"date":"2026-08-07","value":15},{"date":"2026-08-08","value":38},{"date":"2026-08-09","value":34},{"date":"2026-08-10","value":28},{"date":"2026-08-11","value":41},{"date":"2026-08-12","value":36},{"date":"2026-08-13","value":19},{"date":"2026-08-14","value":24},{"date":"2026-08-15","value":47},{"date":"2026-08-16","value":43},{"date":"2026-08-17","value":29},{"date":"2026-08-18","value":33},{"date":"2026-08-19","value":52},{"date":"2026-08-20","value":48},{"date":"2026-08-21","value":37},{"date":"2026-08-22","value":21},{"date":"2026-08-23","value":17},{"date":"2026-08-24","value":39},{"date":"2026-08-25","value":45},{"date":"2026-08-26","value":56},{"date":"2026-08-27","value":51},{"date":"2026-08-28","value":42},{"date":"2026-08-29","value":35},{"date":"2026-08-30","value":27},{"date":"2026-08-31","value":49}],"rowsPath":"","rowLimit":100}',
    '[{"name":"date","title":"日期","type":"date","show":true},{"name":"value","title":"事件数","type":"number","show":true,"aggregate":"sum"}]',
    '[]', 'ACTIVE', 10, 60, 'admin', NOW(), 'admin', NOW(),
    '大屏组件测试专用：日历热力图，固定使用 2026 年 8 月完整月份'
  ),
  (
    'test-component-table',
    '组件测试-表格与轮播',
    'component-test',
    'JSON',
    '{"mode":"STATIC","payload":[{"id":1,"score":98.5,"recordNo":"GC-202608-001","projectName":"主装置区管廊安装","area":"示例一区","owner":"张工","status":"正常","progress":96.8,"amount":1285600.5,"eventTime":"2026-08-27T08:12:36+08:00","active":true,"contact":"13800138001"},{"id":2,"score":95.2,"recordNo":"GC-202608-002","projectName":"罐区设备基础施工","area":"示例二区","owner":"李工","status":"正常","progress":91.3,"amount":986200,"eventTime":"2026-08-27T08:25:10+08:00","active":true,"contact":"13800138002"},{"id":3,"score":92.7,"recordNo":"GC-202608-003","projectName":"控制室电气安装","area":"示例三区","owner":"王工","status":"关注","progress":87.6,"amount":765430.75,"eventTime":"2026-08-27T08:41:22+08:00","active":true,"contact":"13800138003"},{"id":4,"score":89.1,"recordNo":"GC-202608-004","projectName":"消防管网试压","area":"示例四区","owner":"赵工","status":"正常","progress":83.4,"amount":542000,"eventTime":"2026-08-27T09:03:18+08:00","active":true,"contact":"13800138004"},{"id":5,"score":86.8,"recordNo":"GC-202608-005","projectName":"钢结构防腐施工","area":"示例一区","owner":"刘工","status":"预警","progress":78.9,"amount":438880,"eventTime":"2026-08-27T09:18:45+08:00","active":false,"contact":"13800138005"},{"id":6,"score":84.6,"recordNo":"GC-202608-006","projectName":"地下管线回填","area":"示例二区","owner":"陈工","status":"正常","progress":75.2,"amount":369500,"eventTime":"2026-08-27T09:36:09+08:00","active":true,"contact":"13800138006"},{"id":7,"score":81.3,"recordNo":"GC-202608-007","projectName":"仪表桥架敷设","area":"示例三区","owner":"杨工","status":"关注","progress":71.8,"amount":312600,"eventTime":"2026-08-27T09:52:31+08:00","active":true,"contact":"13800138007"},{"id":8,"score":78.9,"recordNo":"GC-202608-008","projectName":"道路基层施工","area":"示例四区","owner":"周工","status":"正常","progress":68.5,"amount":287300,"eventTime":"2026-08-27T10:07:26+08:00","active":true,"contact":"13800138008"},{"id":9,"score":75.4,"recordNo":"GC-202608-009","projectName":"临时用电整改","area":"示例一区","owner":"吴工","status":"预警","progress":62.1,"amount":168900,"eventTime":"2026-08-27T10:26:54+08:00","active":false,"contact":"13800138009"},{"id":10,"score":72.8,"recordNo":"GC-202608-010","projectName":"排水沟砌筑","area":"示例二区","owner":"郑工","status":"正常","progress":58.7,"amount":152400,"eventTime":"2026-08-27T10:44:08+08:00","active":true,"contact":"13800138010"},{"id":11,"score":68.6,"recordNo":"GC-202608-011","projectName":"设备单机试运","area":"示例三区","owner":"孙工","status":"关注","progress":52.3,"amount":121000,"eventTime":"2026-08-27T11:05:17+08:00","active":true,"contact":"13800138011"},{"id":12,"score":64.2,"recordNo":"GC-202608-012","projectName":"保温材料进场验收","area":"示例四区","owner":"马工","status":"预警","progress":46.9,"amount":98500,"eventTime":"2026-08-27T11:28:39+08:00","active":false,"contact":"13800138012"}],"rowsPath":"","rowLimit":100}',
    '[{"name":"id","title":"ID","type":"number","show":false,"sortable":true},{"name":"score","title":"综合得分","type":"number","show":true,"sortable":true,"aggregate":"avg"},{"name":"recordNo","title":"记录编号","type":"string","show":true,"sortable":true},{"name":"projectName","title":"项目事项","type":"string","show":true,"sortable":true},{"name":"area","title":"区域","type":"string","show":true,"sortable":true},{"name":"owner","title":"负责人","type":"string","show":true},{"name":"status","title":"状态","type":"string","show":true,"sortable":true},{"name":"progress","title":"进度","type":"number","show":true,"sortable":true,"aggregate":"avg"},{"name":"amount","title":"金额","type":"number","show":true,"sortable":true,"aggregate":"sum"},{"name":"eventTime","title":"更新时间","type":"datetime","show":true,"sortable":true},{"name":"active","title":"是否有效","type":"boolean","show":true},{"name":"contact","title":"联系电话","type":"string","show":true,"mask":true}]',
    '[]', 'ACTIVE', 10, 60, 'admin', NOW(), 'admin', NOW(),
    '大屏组件测试专用：普通表格、高级表格、轮播表格、卡片轮播和排名表'
  ),
  (
    'test-component-alerts',
    '组件测试-告警事件',
    'component-test',
    'JSON',
    '{"mode":"STATIC","payload":[{"id":1,"level":"紧急","title":"高处作业未系安全带","location":"示例一区 / 管廊 A 段","status":"待处置","eventTime":"2026-08-27T10:42:15+08:00","description":"视频巡检发现人员防护不规范"},{"id":2,"level":"重要","title":"临时用电箱门未关闭","location":"示例二区 / 罐区北侧","status":"处理中","eventTime":"2026-08-27T10:18:36+08:00","description":"现场责任人已接收整改任务"},{"id":3,"level":"一般","title":"材料堆放超出划定区域","location":"示例三区 / 材料堆场","status":"待处置","eventTime":"2026-08-27T09:56:08+08:00","description":"需要重新设置隔离和标识"},{"id":4,"level":"重要","title":"动火票即将到期","location":"示例四区 / 设备框架","status":"处理中","eventTime":"2026-08-27T09:31:44+08:00","description":"剩余有效时间不足一小时"},{"id":5,"level":"一般","title":"扬尘监测值接近阈值","location":"示例一区 / 东门","status":"已关闭","eventTime":"2026-08-27T09:05:27+08:00","description":"洒水降尘后复测恢复正常"},{"id":6,"level":"紧急","title":"受限空间监护人离岗","location":"示例二区 / 地下池体","status":"已关闭","eventTime":"2026-08-27T08:46:19+08:00","description":"作业已暂停并完成重新交底"},{"id":7,"level":"重要","title":"起重作业警戒区人员进入","location":"示例三区 / 装卸区","status":"处理中","eventTime":"2026-08-27T08:22:53+08:00","description":"现场已清场并补充警戒人员"},{"id":8,"level":"一般","title":"消防通道临时占用","location":"示例四区 / 西侧道路","status":"待处置","eventTime":"2026-08-27T07:58:11+08:00","description":"待转移周转材料"}],"rowsPath":"","rowLimit":100}',
    '[{"name":"id","title":"ID","type":"number","show":false},{"name":"level","title":"等级","type":"string","show":true,"sortable":true},{"name":"title","title":"告警标题","type":"string","show":true},{"name":"location","title":"位置","type":"string","show":true},{"name":"status","title":"处置状态","type":"string","show":true,"sortable":true},{"name":"eventTime","title":"告警时间","type":"datetime","show":true,"sortable":true},{"name":"description","title":"告警说明","type":"string","show":true}]',
    '[]', 'ACTIVE', 10, 30, 'admin', NOW(), 'admin', NOW(),
    '大屏组件测试专用：告警列表及表格长文本、状态和时间字段'
  ),
  (
    'test-component-realtime',
    '组件测试-实时事件通道',
    'component-test',
    'WEBSOCKET',
    '{"mode":"POLL_BRIDGE","sourceType":"JSON","payload":[{"id":"EVT-20260827-001","sequence":1001,"eventType":"人员入场","message":"张工通过示例一区东门","status":"成功","eventTime":"2026-08-27T10:30:12+08:00"},{"id":"EVT-20260827-002","sequence":1002,"eventType":"车辆识别","message":"陕A12345进入示例二区","status":"成功","eventTime":"2026-08-27T10:31:08+08:00"},{"id":"EVT-20260827-003","sequence":1003,"eventType":"设备告警","message":"示例三区扬尘监测值偏高","status":"关注","eventTime":"2026-08-27T10:32:26+08:00"},{"id":"EVT-20260827-004","sequence":1004,"eventType":"作业票更新","message":"示例四区动火票完成审批","status":"成功","eventTime":"2026-08-27T10:33:45+08:00"},{"id":"EVT-20260827-005","sequence":1005,"eventType":"质量验收","message":"管廊 A 段焊口复检通过","status":"成功","eventTime":"2026-08-27T10:35:19+08:00"}],"rowsPath":"","subscribe":{"channel":"component.events"},"connection":{"heartbeatSeconds":20,"reconnectLimit":5},"rowLimit":100}',
    '[{"name":"id","title":"事件编号","type":"string","show":true},{"name":"sequence","title":"序号","type":"number","show":true,"sortable":true},{"name":"eventType","title":"事件类型","type":"string","show":true},{"name":"message","title":"事件内容","type":"string","show":true},{"name":"status","title":"状态","type":"string","show":true},{"name":"eventTime","title":"事件时间","type":"datetime","show":true,"sortable":true}]',
    '[]', 'ACTIVE', 10, 5, 'admin', NOW(), 'admin', NOW(),
    '大屏组件测试专用：由平台 WebSocket 入口轮询内置 JSON，不连接外部系统'
  ),
  (
    'test-component-map-points',
    '组件测试-地图区域与点位',
    'component-test',
    'JSON',
    '{"mode":"STATIC","payload":[{"label":"示例一区","value":86,"longitude":101,"latitude":31},{"label":"示例二区","value":72,"longitude":103,"latitude":31},{"label":"示例三区","value":94,"longitude":101,"latitude":33},{"label":"示例四区","value":63,"longitude":103,"latitude":33}],"rowsPath":"","rowLimit":100}',
    '[{"name":"label","title":"区域名称","type":"string","show":true},{"name":"value","title":"区域数值","type":"number","show":true,"sortable":true},{"name":"longitude","title":"经度","type":"number","show":true},{"name":"latitude","title":"纬度","type":"number","show":true}]',
    '[]', 'ACTIVE', 10, 60, 'admin', NOW(), 'admin', NOW(),
    '大屏组件测试专用：与内置 demo-region GeoJSON 的区域名称和坐标严格匹配'
  ),
  (
    'test-component-map-flows',
    '组件测试-地图飞线与时间轴',
    'component-test',
    'JSON',
    '{"mode":"STATIC","payload":[{"fromName":"示例一区","toName":"示例二区","fromLongitude":101,"fromLatitude":31,"toLongitude":103,"toLatitude":31,"value":32,"group":"08:00"},{"fromName":"示例二区","toName":"示例四区","fromLongitude":103,"fromLatitude":31,"toLongitude":103,"toLatitude":33,"value":24,"group":"08:00"},{"fromName":"示例三区","toName":"示例一区","fromLongitude":101,"fromLatitude":33,"toLongitude":101,"toLatitude":31,"value":18,"group":"08:00"},{"fromName":"示例一区","toName":"示例四区","fromLongitude":101,"fromLatitude":31,"toLongitude":103,"toLatitude":33,"value":41,"group":"12:00"},{"fromName":"示例四区","toName":"示例三区","fromLongitude":103,"fromLatitude":33,"toLongitude":101,"toLatitude":33,"value":27,"group":"12:00"},{"fromName":"示例二区","toName":"示例三区","fromLongitude":103,"fromLatitude":31,"toLongitude":101,"toLatitude":33,"value":35,"group":"16:00"},{"fromName":"示例三区","toName":"示例四区","fromLongitude":101,"fromLatitude":33,"toLongitude":103,"toLatitude":33,"value":22,"group":"16:00"}],"rowsPath":"","rowLimit":100}',
    '[{"name":"fromName","title":"起点名称","type":"string","show":true},{"name":"toName","title":"终点名称","type":"string","show":true},{"name":"fromLongitude","title":"起点经度","type":"number","show":true},{"name":"fromLatitude","title":"起点纬度","type":"number","show":true},{"name":"toLongitude","title":"终点经度","type":"number","show":true},{"name":"toLatitude","title":"终点纬度","type":"number","show":true},{"name":"value","title":"流量","type":"number","show":true},{"name":"group","title":"时间分组","type":"string","show":true}]',
    '[]', 'ACTIVE', 10, 60, 'admin', NOW(), 'admin', NOW(),
    '大屏组件测试专用：飞线地图和时间轴飞线，坐标位于内置 demo-region 范围'
  ),
  (
    'test-component-weather',
    '组件测试-天气预报',
    'component-test',
    'JSON',
    '{"mode":"STATIC","payload":[{"city":"示例城市","temperature":31.6,"condition":"多云转阵雨","humidity":78,"wind":"东南风 3 级","forecastTime":"2026-08-27T11:00:00+08:00"}],"rowsPath":"","rowLimit":10}',
    '[{"name":"city","title":"城市","type":"string","show":true},{"name":"temperature","title":"温度","type":"number","show":true},{"name":"condition","title":"天气","type":"string","show":true},{"name":"humidity","title":"湿度","type":"number","show":true},{"name":"wind","title":"风力","type":"string","show":true},{"name":"forecastTime","title":"预报时间","type":"datetime","show":true}]',
    '[]', 'ACTIVE', 10, 300, 'admin', NOW(), 'admin', NOW(),
    '大屏组件测试专用：静态天气样本，不代表实时天气'
  ),
  (
    'test-component-filtered',
    '组件测试-参数过滤',
    'component-test',
    'SQL',
    '{"dataSourceCode":"master","sql":"SELECT dataset_id AS id, dataset_name AS datasetName, dataset_code AS datasetCode FROM dashboard_dataset WHERE dataset_name LIKE CONCAT(''%'', :keyword, ''%'') ORDER BY dataset_id","rowLimit":100}',
    '[{"name":"id","title":"ID","type":"number","show":false},{"name":"datasetName","title":"数据集名称","type":"string","show":true},{"name":"datasetCode","title":"数据集编码","type":"string","show":true}]',
    '[{"name":"keyword","title":"关键词","type":"STRING","required":true,"default":"组件"}]', 'ACTIVE', 10, 60, 'admin', NOW(), 'admin', NOW(),
    '大屏组件测试专用：只读查询 dashboard_dataset，用于验证页面过滤器参数实际改变返回行数'
  ),
  (
    'test-component-empty',
    '组件测试-空数据状态',
    'component-test',
    'JSON',
    '{"mode":"STATIC","payload":[],"rowsPath":"","rowLimit":100}',
    '[{"name":"category","title":"分类","type":"string","show":true},{"name":"value","title":"数值","type":"number","show":true}]',
    '[]', 'ACTIVE', 10, 60, 'admin', NOW(), 'admin', NOW(),
    '大屏组件测试专用：用于验证 NO_DATA 和空态，不应显示为数值 0'
  )
ON DUPLICATE KEY UPDATE
  dataset_name = VALUES(dataset_name),
  group_code = VALUES(group_code),
  data_type = VALUES(data_type),
  config_json = VALUES(config_json),
  field_schema_json = VALUES(field_schema_json),
  param_schema_json = VALUES(param_schema_json),
  status = VALUES(status),
  timeout_seconds = VALUES(timeout_seconds),
  refresh_seconds = VALUES(refresh_seconds),
  update_by = 'admin',
  update_time = NOW(),
  remark = VALUES(remark);

COMMIT;

SELECT dataset_code, dataset_name, data_type, status
FROM dashboard_dataset
WHERE dataset_code IN (
  'test-component-kpi',
  'test-component-series',
  'test-component-calendar',
  'test-component-table',
  'test-component-alerts',
  'test-component-realtime',
  'test-component-map-points',
  'test-component-map-flows',
  'test-component-weather',
  'test-component-filtered',
  'test-component-empty'
)
ORDER BY dataset_code;
