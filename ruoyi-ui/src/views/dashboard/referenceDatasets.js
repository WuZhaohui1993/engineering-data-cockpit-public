/**
 * 六张参考图的演示业务数据。
 * 页面只通过 DATASET 绑定引用这些记录；由系统接口登记为数据集管理中的 JSON 数据集。
 * 截图中带文字的数值照录；无标注曲线采样与甘特日期仅用于复现示意图形。
 */
const COLORS = { blue: '#1687ff', cyan: '#0ad1ef', teal: '#19c7b7', green: '#22bf79', amber: '#ffc32e', orange: '#ff8b24', red: '#fa493e' };
const data = [];
const titles = {
  name: '名称', value: '数值', percent: '占比', color: '颜色', unit: '单位', displayValue: '显示值',
  code: '编号', subject: '事项', status: '状态', date: '日期', planned: '计划', actual: '实际',
  completed: '完成', total: '总量', entered: '入场', exited: '出场', present: '在场人数',
  actualProgress: '实际进度', plannedProgress: '计划进度', presentPeople: '在场人数', qualityOpen: '质量待闭环',
  videoOnline: '视频在线', videoTotal: '视频总数', aiEvents: '七日AI事件', deviation: '进度偏差',
  plannedTasks: '计划任务', completedTasks: '已完成任务', warnings: '偏差预警', deliveryRate: '交付率',
  delivered: '已交付', deliveryTotal: '交付总量', pendingDelivery: '待交付', missingItems: '缺失清单',
  uninstalled: '未安装', installing: '安装中', discipline: '专业', progress: '完成率', stage: '阶段',
  id: '序号', task: '任务名称', start: '基准开始', end: '基准结束', actualStart: '实际开始', actualEnd: '实际结束', dependencies: '依赖任务',
  inspections: '检查记录', issues: '质量问题', found: '发现', closed: '已关闭', closureRate: '闭环率', rectifying: '整改中', overdue: '逾期未闭环',
  openIssues: '开放问题', registered: '登记', handled: '已处理', processing: '处理中', pendingReview: '待复核',
  limit: '时限', area: '区域', owner: '责任单位', source: '来源', time: '时间', result: '处理结果',
  hazards: '隐患总数', risks: '风险总数', permits: '作业票总数', activePermits: '执行作业票', trainingTotal: '应培训', trainingCompleted: '培训完成', trainingPending: '待完成', trainingRate: '培训完成率',
  enteredToday: '今日入场', exitedToday: '今日出场', pendingVisitors: '访客待审', vehiclesIn: '车辆进场', vehiclesOut: '车辆出场', vehiclesPresent: '场内车辆', speedReviews: '超速待复核',
  patrolPlanned: '计划巡检', patrolCompleted: '完成巡检', patrolRunning: '执行中巡检', patrolRate: '巡检完成率',
  online: '在线', offline: '离线', healthRecords: '健康记录', healthRate: '记录完整率', healthPending: '记录待完善', environmentOnline: '环境在线', environmentTotal: '环境总数', environmentEvents: '今日环境事件', environmentClosed: '环境事件闭环',
  pm25: 'PM2.5', pm10: 'PM10', noise: '噪声', temperature: '温度', threshold: '项目预警线',
  helmet: '安全帽', harness: '安全带', intrusion: '闯入', fire: '烟火', x: '横向位置', y: '纵向位置', icon: '图标', label: '标签',
};
function add(suffix, name, rows, fieldTitles = {}) {
  const keys = [...new Set(rows.flatMap(row => Object.keys(row)))];
  const fields = keys.map(key => ({ name: key, title: fieldTitles[key] || titles[key] || key, type: rows.some(row => typeof row[key] === 'number') ? 'number' : 'string', show: !['color', 'icon', 'x', 'y'].includes(key), sortable: false, aggregate: 'none', mask: false, dictCode: '' }));
  data.push({ code: `xinghua-${suffix}`, name, rows, fields });
}
function ring(suffix, name, entries) {
  add(suffix, name, entries.map(([name, value, percent, color]) => ({ name, value, percent, color })));
}
function pairs(suffix, title, names, first, second, a = 'planned', b = 'actual') {
  add(suffix, title, names.map((name, i) => ({ name, [a]: first[i], [b]: second[i] })));
}
function progress(suffix, title, rows) {
  add(suffix, title, rows.map(([name, completed, total, value]) => ({ name, completed, total, value })));
}

// 共用口径。共用数据集使六页的相同指标保持一致。
pairs('progress-trend', '累计进度S曲线', ['4月', '5月', '6月', '7月', '8月', '9月'], [18, 26, 36, 47, 56, 64], [16, 24, 33, 43, 52, 61]);
ring('task-status', '任务执行状态', [['完成', 198, 60.7, COLORS.green], ['进行中', 104, 31.9, COLORS.blue], ['未开始', 24, 7.4, COLORS.orange]]);
ring('personnel-type', '人员结构', [['管理', 82, 28.4, COLORS.blue], ['作业', 180, 62.3, COLORS.cyan], ['访客', 27, 9.3, COLORS.orange]]);
add('personnel-company', '参建单位在场分布', [{ name: 'EPC-A', value: 104, percent: 36.0 }, { name: 'EPC-B', value: 86, percent: 29.8 }, { name: '施工 C', value: 61, percent: 21.1 }, { name: '监理 D', value: 38, percent: 13.1 }]);
add('environment-current', '环境实时监测', [['PM2.5', 13, 'μg/m³'], ['PM10', 24, 'μg/m³'], ['噪声', 44.3, 'dB'], ['温度', 28.8, '℃'], ['湿度', 40.7, '%RH'], ['风速', 1.1, 'm/s']].map(([name, value, unit]) => ({ name, value, unit })));
ring('ai-categories', '七日AI事件分类', [['未戴安全帽', 7, 50.0, COLORS.blue], ['未系安全带', 4, 28.6, COLORS.cyan], ['区域闯入', 2, 14.3, COLORS.amber], ['烟火识别', 1, 7.1, COLORS.red]]);
pairs('personnel-hourly', '人员分时出入趋势', Array.from({ length: 24 }, (_, i) => `${String(i).padStart(2, '0')}:00`), [3, 4, 4, 12, 38, 58, 100, 108, 94, 107, 100, 116, 78, 105, 120, 101, 78, 102, 76, 59, 38, 37, 24, 14], [2, 3, 5, 10, 17, 40, 65, 76, 62, 68, 80, 77, 104, 72, 86, 92, 81, 74, 42, 35, 17, 12, 7, 4], 'entered', 'exited');

// 项目概况。
add('overview-kpis', '项目概况指标', [{ actualProgress: 61.0, plannedProgress: 64.0, presentPeople: 289, qualityOpen: 44, videoOnline: 42, videoTotal: 44, aiEvents: 14, qualityClosed: 142, qualityTotal: 186, qualityRate: 76.3, hazardClosed: 46, hazardTotal: 64, hazardRate: 71.9, taskTotal: 326, enteredToday: 731, exitedToday: 442 }], { qualityClosed: '质量已关闭', qualityTotal: '质量问题总数', qualityRate: '质量闭环率', hazardClosed: '隐患已闭环', hazardTotal: '隐患总数', hazardRate: '隐患闭环率', taskTotal: '总任务' });
add('overview-archive', '项目档案', [{ name: '项目地点', value: '榆神工业区清水工业园' }, { name: '占地', value: '1587.3亩' }, { name: '合同原计划', value: '2025年3月—2027年6月' }, { name: '项目编码', value: '24187e' }]);
add('overview-capacity', '建设规模（设计产能）', [{ name: '合成氨', value: 50, displayValue: '50万吨/年' }, { name: '硝酸', value: 72, displayValue: '2×36万吨/年' }, { name: '硝酸铵', value: 86, displayValue: '86万吨/年' }, { name: '硝基复合肥', value: 50, displayValue: '50万吨/年' }]);
ring('overview-quality-closure', '质量闭环', [['已关闭', 142, 76.3, COLORS.cyan], ['待闭环', 44, 23.7, '#19364a']]);
ring('overview-hazard-closure', '隐患闭环', [['已闭环', 46, 71.9, COLORS.orange], ['待闭环', 18, 28.1, '#19364a']]);
add('overview-major-items', '重点事项', [
  ['JD01', '设备到货跟踪', '跟踪中'], ['ZL02', '焊接记录', '待复核'], ['AQ03', '吊装交底', '待确认'], ['HJ04', '环境事件', '已处置'], ['JC05', '视频离线', '处理中'], ['PX06', '入场培训', '进行中'], ['JL07', '监理见证', '待确认'], ['SJ08', '交付清单', '校核中'],
].map(([code, subject, status]) => ({ code, subject, status })));
add('overview-map-labels', '项目空间区域标注', [['煤气化净化', 37, 14], ['合成氨', 66, 15], ['硝酸硝铵', 31, 47], ['储运', 85, 47], ['公辅', 47, 76]].map(([name, x, y]) => ({ name, x, y })));

// 进度管理。
add('progress-kpis', '进度管理指标', [{ plannedProgress: 64.0, actualProgress: 61.0, deviation: -3.0, plannedTasks: 326, completedTasks: 198, warnings: 12, deliveryRate: 74.0, delivered: 740, deliveryTotal: 1000, pendingDelivery: 260, missingItems: 7, generalWarnings: 8, majorWarnings: 4, pendingReports: 2, baselineVersion: 'V1', currentVersion: 'V3', baselineDate: '2026-09-01', currentDate: '2026-09-01', reportDeadline: '09-05' }], { generalWarnings: '一般偏差', majorWarnings: '重点偏差', pendingReports: '周报待提交', baselineVersion: '基准计划', currentVersion: '当前计划', baselineDate: '基准计划日期', currentDate: '当前计划日期', reportDeadline: '周报截止' });
add('progress-quantities', '专业工程量状态', [{ name: '桩基', total: 1000, unit: '根', uninstalled: 20, installing: 60, completed: 920 }, { name: '地管', total: 10000, unit: '米', uninstalled: 1800, installing: 1400, completed: 6800 }, { name: '设备', total: 500, unit: '台', uninstalled: 110, installing: 120, completed: 270 }, { name: '工艺管道', total: 20000, unit: '米', uninstalled: 10000, installing: 5600, completed: 4400 }]);
add('progress-gantt', '关键路径与基准计划', [
  { id: '1', task: '桩基施工', start: '2026-04-01', end: '2026-05-12', actualStart: '2026-04-01', actualEnd: '2026-06-25', progress: 100, dependencies: '', status: '已完成' },
  { id: '2', task: '地管安装', start: '2026-04-05', end: '2026-06-12', actualStart: '2026-05-01', actualEnd: '2026-07-15', progress: 68, dependencies: '1', status: '进行中' },
  { id: '3', task: '钢结构安装', start: '2026-04-15', end: '2026-06-25', actualStart: '2026-05-10', actualEnd: '2026-08-01', progress: 75, dependencies: '2', status: '进行中' },
  { id: '4', task: '设备安装', start: '2026-04-25', end: '2026-08-01', actualStart: '2026-06-20', actualEnd: '2026-09-15', progress: 54, dependencies: '3', status: '进行中' },
  { id: '5', task: '管廊安装', start: '2026-05-05', end: '2026-08-15', actualStart: '2026-05-25', actualEnd: '2026-09-20', progress: 45, dependencies: '4', status: '进行中' },
  { id: '6', task: '工艺管道', start: '2026-05-25', end: '2026-09-01', actualStart: '2026-06-20', actualEnd: '2026-09-25', progress: 22, dependencies: '5', status: '进行中' },
  { id: '7', task: '电气仪表', start: '2026-06-25', end: '2026-10-20', actualStart: '2026-08-01', actualEnd: '2026-10-25', progress: 30, dependencies: '6', status: '进行中' },
  { id: '8', task: '试车准备', start: '2026-08-10', end: '2026-11-15', actualStart: '2026-09-15', actualEnd: '2026-11-20', progress: 0, dependencies: '7', status: '未开始' },
]);
add('progress-deviations', '专业偏差（加权口径）', [['桩基', 0], ['地管', -2.0], ['设备', -4.0], ['工艺管道', -6.0], ['电仪', -3.0]].map(([name, value]) => ({ name, value })));
progress('progress-delivery', '交付进度分类', [['模型', 160, 210, 76], ['智能P&ID', 210, 280, 75], ['文档', 230, 300, 76], ['工程数据', 140, 210, 67]]);
ring('progress-delivery-ring', '整体交付率', [['已交付', 740, 74.0, COLORS.cyan], ['待交付', 260, 26.0, '#22435c']]);
pairs('progress-weekly', '近六周计划执行', ['07-25~07-31', '08-01~08-07', '08-08~08-14', '08-15~08-21', '08-22~08-28', '08-29~09-04'], [20, 24, 22, 28, 26, 30], [18, 22, 20, 24, 25, 27]);
add('progress-alerts', '偏差预警分类', [{ name: '一般偏差', value: 8 }, { name: '重点偏差', value: 4 }]);
add('progress-versions', '计划版本与周报', [{ name: '基准计划', value: 'V1', date: '2026-09-01' }, { name: '当前计划', value: 'V3', date: '2026-09-01' }, { name: '周报待提交', value: '2份', date: '截至 09-05' }]);
add('progress-objects', '工程对象计划明细', [
  ['PILE-01', '桩基', '08-20', '08-19', 100, '已完成'], ['PILE-02', '桩基', '08-25', '08-25', 100, '已完成'], ['UG-101', '地管', '09-10', '—', 75, '进行中'], ['UG-102', '地管', '09-18', '—', 60, '进行中'], ['E-201', '设备', '09-08', '—', 85, '进行中'], ['E-202', '设备', '09-20', '—', 45, '进行中'], ['PL-301', '管道', '09-25', '—', 30, '进行中'], ['PL-302', '管道', '09-30', '—', 20, '进行中'],
].map(([code, discipline, planned, actual, progress, status]) => ({ code, discipline, planned, actual, progress, status })));
add('progress-milestones', '里程碑跟踪', [
  ['设计', '设计启动', '2026-02-10', '已完成'], ['设计', '设计完成', '2026-04-30', '已完成'], ['采购', '采购启动', '2026-05-05', '已完成'], ['采购', '主设备到货', '2026-07-20', '已完成'], ['土建', '土建开工', '2026-04-15', '已完成'], ['土建', '土建完成', '2026-08-10', '未开始'], ['安装', '安装开工', '2026-07-15', '进行中'], ['安装', '安装完成', '2026-11-10', '进行中'], ['交付', '资料交付', '2026-11-25', '未开始'], ['交付', '竣工交付', '2026-12-20', '未开始'], ['试车', '单机试车', '2027-01-10', '未开始'], ['试车', '联动试车', '2027-02-20', '未开始'],
].map(([stage, name, date, status]) => ({ stage, name, date, status })));
data.find(dataset => dataset.code === 'xinghua-progress-milestones').fields.find(field => field.name === 'date').type = 'date';

// 质量管理。
add('quality-kpis', '质量管理指标', [{ inspections: 368, issues: 186, closed: 142, closureRate: 76.3, rectifying: 16, overdue: 3, openIssues: 44, registered: 18, handled: 12, processing: 4, pendingReview: 28 }]);
ring('quality-status', '质量问题状态', [['已关闭', 142, 76.3, COLORS.green], ['整改中', 16, 8.6, COLORS.amber], ['待复核', 28, 15.1, COLORS.blue]]);
pairs('quality-weekly', '近七周质量检查与整改趋势', ['第29周', '第30周', '第31周', '第32周', '第33周', '第34周', '第35周'], [18, 22, 16, 20, 14, 17, 12], [12, 18, 15, 17, 13, 16, 14], 'found', 'closed');
ring('quality-aging', '开放问题账龄', [['0–3天', 9, 20.5, COLORS.green], ['4–7天', 18, 40.9, COLORS.amber], ['8天以上', 17, 38.6, COLORS.blue]]);
add('quality-discipline', '专业问题分布', [['焊接', 54], ['管道', 38], ['土建', 32], ['设备', 28], ['电仪', 22], ['材料', 12]].map(([name, value]) => ({ name, value })));
progress('quality-tests', '检测计划执行率', [['无损', 148, 160, 92.5], ['理化', 42, 50, 84.0], ['PMI', 31, 40, 77.5], ['试压', 12, 20, 60.0]]);
ring('quality-nonconformity', '不合格品处理', [['已处理', 12, 66.7, COLORS.teal], ['处理中', 4, 22.2, COLORS.amber], ['待复核', 2, 11.1, COLORS.red]]);
add('quality-causes', '不合格原因分布', [['材料', 6], ['尺寸', 5], ['资料', 4], ['其他', 3]].map(([name, value]) => ({ name, value })));
add('quality-records', '过程记录统计', [['隐蔽', 24], ['设备', 39], ['组对焊接', 86], ['返修', 18], ['热处理', 26], ['无损', 74], ['理化', 42], ['PMI', 31], ['试压', 12]].map(([name, value]) => ({ name, value })));
progress('quality-traceability', '质量追溯与交付', [['对象关联', 312, 368, 84.8], ['附件完整', 352, 368, 95.7], ['已交付', 286, 368, 77.7]]);
add('quality-details', '质量问题明细', [['Q-024', '焊口', '整改中', '3天'], ['Q-023', '设备找正', '整改中', '2天'], ['Q-022', '材料资料', '整改中', '逾期1天'], ['Q-021', '隐蔽验收', '已关闭', '—'], ['Q-020', '焊口复验', '待复核', '1天'], ['Q-019', '设备基础', '待复核', '2天'], ['Q-018', '管道试压', '已关闭', '—'], ['Q-017', '安装记录', '已关闭', '—']].map(([code, subject, status, limit]) => ({ code, subject, status, limit })));
add('quality-flow', '整改闭环', [['发现', 186], ['分派', 186], ['整改', 16], ['复核', 28], ['归档', 142]].map(([name, value]) => ({ name, value })));

// 安全管理。
add('safety-kpis', '安全管理指标', [{ presentPeople: 289, hazards: 64, closed: 46, closureRate: 71.9, risks: 24, permits: 80, activePermits: 12, aiEvents: 14, trainingTotal: 289, trainingCompleted: 254, trainingPending: 35, trainingRate: 87.9 }]);
ring('safety-risks', '风险等级分布', [['高风险', 3, 12.5, COLORS.red], ['中风险', 8, 33.3, COLORS.amber], ['一般风险', 13, 54.2, COLORS.blue]]);
ring('safety-closure', '隐患整改闭环', [['已闭环', 46, 71.9, COLORS.blue], ['整改中', 12, 18.8, COLORS.amber], ['待复核', 6, 9.4, COLORS.cyan]]);
progress('safety-training', '在场人员入场教育', [['入场教育', 254, 289, 87.9], ['资质审核', 241, 289, 83.4], ['机具准入', 247, 289, 85.5]]);
pairs('safety-weekly', '近七日隐患发现与闭环', ['08/30', '08/31', '09/01', '09/02', '09/03', '09/04', '09/05'], [8, 10, 7, 12, 9, 11, 7], [6, 7, 5, 8, 7, 8, 5], 'found', 'closed');
ring('safety-permits', '作业票状态', [['申请中', 6, 7.5, COLORS.blue], ['审批中', 4, 5.0, COLORS.amber], ['执行中', 12, 15.0, '#72c41f'], ['已关闭', 58, 72.5, COLORS.cyan]]);
add('safety-work-types', '执行作业类型', [['动火', 4, COLORS.red], ['吊装', 3, COLORS.orange], ['高处', 2, COLORS.blue], ['受限空间', 1, '#854ae3'], ['临电', 1, COLORS.cyan], ['动土', 1, COLORS.teal]].map(([name, value, color]) => ({ name, value, color })));
add('safety-details', '重点作业与隐患处理', [['ZY-108', '装置区', '动火', '施工二部', '执行中'], ['ZY-107', '管廊区', '吊装', '施工二部', '执行中'], ['ZY-106', '储运区', '受限空间', '施工三部', '审批中'], ['ZY-105', '公辅区', '高处', '施工一部', '执行中'], ['YH-024', '仓储区', '通道清理', '施工三部', '整改中'], ['YH-023', '管廊区', '临边防护', '施工二部', '待复核'], ['YH-022', '临设区', '临电检查', '施工一部', '已闭环'], ['YH-021', '装置区', '作业隔离', '施工二部', '已闭环']].map(([code, area, subject, owner, status], i) => ({ id: i + 1, code, area, subject, owner, status })));
add('safety-ai-trend', '七日AI事件复核趋势', ['08/30', '08/31', '09/01', '09/02', '09/03', '09/04', '09/05'].map((name, i) => ({ name, helmet: [3, 5, 4, 7, 3, 4, 4][i], harness: [1, 1, 2, 4, 2, 3, 2][i], intrusion: [1, 1, 1, 1, 1, 1, 1][i], fire: [2, 2, 1, 3, 2, 4, 4][i] })));
add('safety-workflow', '安全管控联动', ['申请', 'JSA', '交底', '审批', '执行', '关闭'].map((name, i) => ({ name, value: i + 1, status: '已配置' })));
add('safety-linkages', '安全联动核验', ['资质核验', '门禁联动', '机具准入'].map(name => ({ name, status: '已启用' })));
add('safety-map-labels', '现场风险区域与作业标注', [['装置区', 44, 7, '区域'], ['管廊区', 77, 11, '区域'], ['储运区', 9, 30, '区域'], ['公辅区', 91, 51, '区域'], ['仓储区', 43, 84, '区域'], ['临设区', 80, 89, '区域'], ['吊装作业', 42, 43, '中风险'], ['动火作业', 78, 48, '中风险']].map(([name, x, y, status]) => ({ name, x, y, status })));

// 智慧工地。
add('smart-kpis', '智慧工地指标', [{ enteredToday: 731, exitedToday: 442, presentPeople: 289, pendingVisitors: 6, videoOnline: 42, videoTotal: 44, aiEvents: 14, vehiclesIn: 126, vehiclesOut: 118, vehiclesPresent: 8, speedReviews: 2, patrolPlanned: 20, patrolCompleted: 18, patrolRunning: 2, patrolRate: 90.0 }]);
add('smart-access-trend', '今日入场趋势', [20, 10, 8, 18, 16, 22, 35, 30, 25, 34, 45, 60, 72, 96, 101, 140, 145, 165, 171, 148, 105, 84, 66, 58, 66, 91, 109, 131, 142, 135, 115, 135, 155, 178, 159, 143, 138, 119, 92, 93, 78, 66, 42, 17].map((value, i) => ({ name: `${String(Math.floor(i / 3)).padStart(2, '0')}:${['00', '20', '40'][i % 3]}`, value })));
add('smart-vehicles', '车辆通行与测速', [{ name: '进场数量', value: 126, unit: '辆' }, { name: '出场数量', value: 118, unit: '辆' }, { name: '场内车辆', value: 8, unit: '辆' }, { name: '超速待复核', value: 2, unit: '起' }]);
pairs('smart-vehicle-trend', '近7日车辆通行趋势', ['08/30', '08/31', '09/01', '09/02', '09/03', '09/04', '09/05'], [92, 98, 112, 118, 124, 127, 126], [87, 91, 105, 110, 117, 121, 118], 'entered', 'exited');
add('smart-personnel-trend', '人员分时出入趋势', ['02:00', '04:00', '08:00', '10:00', '12:00', '14:00'].map((name, i) => ({ name, entered: [22, 64, 168, 198, 159, 121][i], exited: [18, 42, 105, 128, 99, 51][i], present: [4, 26, 89, 159, 219, 289][i] })));
ring('smart-patrol', '智能巡检任务', [['已完成', 18, 90.0, COLORS.cyan], ['执行中', 2, 10.0, '#22435c']]);
add('smart-devices', '设备与数据接入', [{ name: '摄像机', value: '42路', online: '42路', offline: '2路', status: '在线' }, { name: '环境监测', value: '8套', online: '8套', offline: '0套', status: '在线' }, { name: '门禁系统', value: '数据同步', online: '', offline: '', status: '同步正常' }, { name: '车辆系统', value: '数据同步', online: '', offline: '', status: '同步正常' }, { name: '三维定位', value: '数据同步', online: '', offline: '', status: '同步正常' }]);
add('smart-events', '现场事件与巡检记录', [['14:18', '装置区', '安全帽识别', '视频AI', '待复核'], ['14:15', '南门', '访客申请', '实名门禁', '待审核'], ['14:08', '管廊区', '通道巡访', '四足机器人', '执行中'], ['13:56', '主干道', '速度异常', '车辆测速', '待复核'], ['13:42', '储罐区', '全景异常', '无人机', '已完成'], ['13:30', '公辅区', '点位巡查', '移动摄像', '已完成'], ['13:18', '仓储区', '设备巡查', '视频巡检', '已完成'], ['13:06', '北门', '通行同步', '实名门禁', '已完成']].map(([time, area, subject, source, status]) => ({ time, area, subject, source, status })));
add('smart-map-labels', '智慧工地地图点位', [['北门', 55, 7, '门禁'], ['南西门', 13, 72, '门禁'], ['南东门', 84, 73, '门禁'], ['AI事件', 58, 44, '关注'], ['摄像机01', 8, 57, '视频'], ['摄像机02', 43, 30, '视频'], ['摄像机03', 86, 29, '视频'], ['摄像机04', 89, 57, '视频'], ['摄像机05', 58, 85, '视频']].map(([name, x, y, status]) => ({ name, x, y, status })));

// HSE管理。
add('hse-kpis', 'HSE管理指标', [{ presentPeople: 289, healthRecords: 280, healthRate: 96.9, healthPending: 9, environmentOnline: 8, environmentTotal: 8, environmentEvents: 6, environmentClosed: 5, processing: 1, trainingTotal: 289, trainingCompleted: 254, trainingPending: 35, trainingRate: 87.9, pm25: 13, pm10: 24, noise: 44.3, temperature: 28.8 }]);
ring('hse-health', '健康记录状态', [['登记', 280, 96.9, COLORS.cyan], ['待完善', 9, 3.1, COLORS.amber]]);
progress('hse-health-records', '健康记录明细', [['入场记录', 289, 289, 100], ['检测记录', 277, 289, 95.8], ['培训记录', 285, 289, 98.6], ['复核记录', 280, 289, 96.9]]);
progress('hse-training', '在场HSE培训', [['入场教育', 282, 289, 97.6], ['岗位风险', 246, 289, 85.1], ['环境保护', 233, 289, 80.6], ['应急处置', 240, 289, 83.0]]);
add('hse-pm-trend', '24小时PM2.5与PM10趋势', Array.from({ length: 25 }, (_, i) => ({ name: String(i).padStart(2, '0'), pm25: [29, 24, 24, 29, 33, 31, 25, 25, 25, 31, 31, 26, 26, 31, 34, 28, 28, 33, 30, 27, 26, 23, 25, 21, 13][i], pm10: [59, 52, 50, 54, 57, 57, 57, 49, 50, 59, 61, 54, 51, 56, 59, 61, 59, 63, 62, 67, 63, 53, 50, 56, 24][i], threshold: 90 })));
add('hse-noise-trend', '24小时噪声趋势', [57, 56, 55, 61, 62, 61, 64, 59, 66, 62, 64, 65, 60, 61, 62, 55, 54, 56, 58, 51, 47, 50, 49, 53, 54, 56, 51, 63, 50, 52, 49, 55, 51, 45, 43, 42, 41, 44.3].map((value, i, list) => ({ name: String(Math.round(i * 24 / (list.length - 1))).padStart(2, '0'), value, threshold: 90 })));
add('hse-points', '环境点位状态', [['南门', '关注', 16, 48.6, 28.1], ['北门', '正常', 12, 42.1, 27.5], ['主干道', '正常', 13, 44.3, 28.8], ['临设区', '正常', 11, 41.0, 28.2], ['仓储区', '正常', 10, 39.2, 27.9], ['管廊区', '正常', 12, 43.6, 28.0], ['装置区', '正常', 14, 45.7, 29.1], ['公辅区', '正常', 9, 40.9, 27.6]].map(([name, status, pm25, noise, temperature], i) => ({ id: i + 1, name, status, pm25, noise, temperature })));
add('hse-treatment-types', '现场处置类型', [['降尘', 3], ['噪声核查', 2], ['环境巡查', 1]].map(([name, value]) => ({ name, value })));
add('hse-events', '监测与联动记录', [['14:18', '南门', '噪声预警关注', '处置中'], ['13:52', '临设区', '扬尘恢复', '已归档'], ['12:40', '主干道', '降尘联动', '已归档'], ['11:26', '仓储区', '环境巡查', '已归档'], ['10:08', '装置区', '噪声复核', '已归档'], ['09:44', '管廊区', '降尘联动', '已归档'], ['08:50', '北门', '气象上传', '正常记录'], ['08:30', '公辅区', '人员记录同步', '正常记录']].map(([time, area, subject, result]) => ({ time, area, subject, result })));
add('hse-workflow', 'HSE处置联动', [['监测触发', 6], ['视频复核', 6], ['现场处置', 1], ['结果复核', 5], ['归档', 5]].map(([name, value]) => ({ name, value })));
add('hse-map-labels', '环境与人员监测点位', [['北门', 40, 12, '环境点位'], ['临设区', 9, 39, '环境点位'], ['管廊区', 42, 40, '环境点位'], ['装置区', 76, 42, '环境点位'], ['仓储区', 28, 56, '环境点位'], ['主干道', 60, 60, '环境点位'], ['公辅区', 37, 85, '环境点位'], ['南门', 14, 77, '关注'], ['人员01', 24, 43, '健康监测'], ['人员02', 42, 59, '健康监测'], ['人员03', 71, 62, '健康监测'], ['人员04', 57, 85, '健康监测']].map(([name, x, y, status]) => ({ name, x, y, status })));

export const referenceDatasets = data;
export const referenceDatasetByCode = Object.fromEntries(data.map(dataset => [dataset.code, dataset]));
export const referenceDatasetGroup = { groupCode: 'xinghua-reference', groupName: '六页演示数据', sortOrder: 40, remark: '截图复现使用的 JSON 演示业务数据，可在数据集管理中维护' };
export function referenceDatasetPayload(dataset) {
  return {
    datasetCode: dataset.code, datasetName: dataset.name, groupCode: referenceDatasetGroup.groupCode,
    dataType: 'JSON', status: 'ACTIVE', timeoutSeconds: 10, refreshSeconds: 60,
    configJson: JSON.stringify({ mode: 'STATIC', payload: dataset.rows, rowsPath: '', rowLimit: 1000 }),
    fieldSchemaJson: JSON.stringify(dataset.fields), paramSchemaJson: '[]',
    remark: '参考图演示数据；不代表真实现场数据。所有数值在本 JSON 数据集维护。',
  };
}
