import { dashboardComponentCapabilities } from './dashboardComponentCapabilities.js'

// Read-only examples. They are deliberately independent of saved pages and datasets.
const labels = {
  'metric-card': '指标卡片', 'number-flip': '数字翻牌', statistics: '统计概览', table: '数据表格',
  'advanced-table': '高级表格', 'access-list': '图文记录列表', 'carousel-table': '轮播表格', carousel: '卡片轮播',
  'gantt-chart': '计划甘特图', 'milestone-timeline': '里程碑时间轴', 'rank-table': '排名表', 'alert-list': '告警列表', 'realtime-list': '实时列表',
  'line-chart': '折线图', 'bar-chart': '柱状图', 'custom-chart': '通用图表', 'pie-chart': '饼图', 'ring-chart': '环形图',
  gauge: '仪表盘', progress: '进度图', funnel: '漏斗图', radar: '雷达图', scatter: '散点图', 'area-chart': '面积图',
  'pictorial-chart': '象形图', 'treemap-chart': '矩形树图', 'calendar-chart': '日历热力图', 'word-cloud': '文字云', 'bar3d-chart': '3D 柱形图',
  'map-chart': '受控地图', 'map-flow': '飞线地图', 'map-bar': '柱形地图', 'map-heat': '热力地图', 'map-ranking': '柱形排名地图',
  'map-timeline': '时间轴飞线', weather: '天气预报', 'current-time': '当前时间', 'ring-text': '轨道环形文字', 'color-block': '颜色块',
  'filter-form': '查询表单', 'designer-form': '设计器表单', 'online-form': '在线表单（查询）', tabs: '选项卡', text: '文本',
  'rich-text': '富文本', icon: '图标', button: '按钮', image: '图片', video: '视频', iframe: '内嵌页面',
  'custom-html': '自定义内容', border: '边框', decoration: '装饰线',
}

const roleLabels = {
  category: '分类 / 维度字段', value: '数值字段', valueFields: '多系列数值字段', label: '名称字段', suffix: '单位字段',
  compareValue: '对比值字段', compareLabel: '对比标签字段', compareState: '升降状态字段',
  title: '主标题字段', subtitle: '副标题字段', company: '单位字段', avatar: '头像资源字段', status: '状态字段', time: '时间字段',
  id: '任务编号字段', task: '任务名称字段', start: '计划开始日期字段', end: '计划结束日期字段',
  actualStart: '实际开始日期字段', actualEnd: '实际结束日期字段', progress: '完成进度字段', dependencies: '依赖任务编号字段',
  startDate: '开始日期字段', endDate: '结束日期字段', longitude: '经度字段', latitude: '纬度字段',
  fromName: '起点名称字段', toName: '终点名称字段', fromLongitude: '起点经度字段', fromLatitude: '起点纬度字段',
  toLongitude: '终点经度字段', toLatitude: '终点纬度字段', group: '时间分组字段',
  city: '城市字段', temperature: '温度字段', condition: '天气字段',
}

const seriesRows = [
  { category: '施工一区', value: 86, planValue: 90 },
  { category: '施工二区', value: 74, planValue: 85 },
  { category: '施工三区', value: 92, planValue: 95 },
]
const tableRows = Array.from({ length: 8 }, (_, index) => ({
  name: `施工${['一', '二', '三', '四', '五', '六', '七', '八'][index]}部`,
  score: 96 - index * 4, status: index % 3 === 2 ? '待整改' : '正常',
}))
const mapRows = [
  { label: '示例一区', value: 86, longitude: 101, latitude: 31 },
  { label: '示例二区', value: 72, longitude: 103, latitude: 31 },
  { label: '示例三区', value: 94, longitude: 101, latitude: 33 },
]
const flowRows = [
  { fromName: '示例一区', toName: '示例二区', fromLongitude: 101, fromLatitude: 31, toLongitude: 103, toLatitude: 31, value: 32, group: '08:00' },
  { fromName: '示例二区', toName: '示例三区', fromLongitude: 103, fromLatitude: 31, toLongitude: 101, toLatitude: 33, value: 24, group: '12:00' },
]
const mapRef = '/dashboard/assets/map/demo-region.json'
const identity = keys => Object.fromEntries(keys.map(key => [key, key]))
const commonSeries = { rows: seriesRows, fieldMap: { category: 'category', value: 'value' } }
const examples = {}
const dataExample = (type, purpose, spec) => { examples[type] = { purpose, rows: [], fieldMap: {}, displayFields: [], notes: [], ...spec } }
const configExample = (type, purpose, source, notes, configuration = null) => {
  examples[type] = { purpose, source, rows: [], fieldMap: {}, displayFields: [], notes, configuration }
}

dataExample('metric-card', '从第一行读取一个指标值。', {
  rows: [{ value: 68.5 }], fieldMap: { value: 'value' }, notes: ['数值声明为 number；单位在样式中设置，不把“68.5%”作为数值。', '多行数据不会自动汇总，需在数据集中先计算业务口径。'],
})
dataExample('number-flip', '将一个数值显示为数字翻牌。', {
  rows: [{ value: 186 }], fieldMap: { value: 'value' }, notes: ['从第一行取值；前导补零、最少位数和单格外观在样式中设置。'],
})
dataExample('statistics', '显示指标标题、数值、单位和对比状态。', {
  rows: [{ label: '累计完成产值', value: 12.8, suffix: '亿元', compareValue: 5.2, compareLabel: '较上月', compareState: 'up' }],
  fieldMap: identity(['label', 'value', 'suffix', 'compareValue', 'compareLabel', 'compareState']),
  notes: ['value 和 compareValue 使用数字；升降状态使用 up（上升）或 down（下降）。', '标题、单位与对比说明使用文本；是否显示对比在样式中控制。', '统计只展示一个标题；隐藏后仍可编辑手工标题。标题和单位映射有值时优先，空值或缺失时回退手工配置。'],
})
for (const [type, purpose, notes] of [
  ['table', '按所选字段展示业务明细，可连续自动滚动。', ['先设置展示字段顺序；自动滚动在样式中开启，数据超过可见行数才滚动。', '样例含 8 行，适合核对滚动、固定表头和鼠标悬停暂停。']],
  ['advanced-table', '展示带表头、序号和斑马纹的明细表。', ['数据按行组织；行高、序号、表头和自动滚动在样式中设置。']],
  ['carousel-table', '按设定间隔轮换一组表格行。', ['组件展示条数需小于数据行数才会轮换；例如样例 8 行，展示 3 行。']],
  ['carousel', '轮播展示数据卡片。', ['每行代表一张卡片；轮播方向、间隔和序号在样式中设置。']],
  ['rank-table', '按数值从高到低排列记录。', ['当前界面没有独立的排名字段选择器；将名称放在原始数据第一字段，分数放在第二字段。', '展示字段顺序只影响显示，不改变默认按原始第二字段排序的规则。']],
]) dataExample(type, purpose, { rows: tableRows, displayFields: ['name', 'score', 'status'], notes })

dataExample('access-list', '展示人员或车辆进出场的图文记录。', {
  rows: [{ title: '张某', subtitle: '木工', company: '示例施工单位', status: '进场', time: '2026-09-06 08:30:00' }, { title: '李某', subtitle: '电工', company: '示例施工单位', status: '出场', time: '2026-09-06 09:10:00' }],
  fieldMap: identity(['title', 'subtitle', 'company', 'status', 'time']),
  notes: ['该样例未附头像资源，先在样式关闭“显示头像”。有平台图片资源后再新增并映射 avatar 字段。', '按“进场 / 出场”配置绿色与中性状态文字；时间建议声明为 datetime。'],
})
dataExample('gantt-chart', '对照任务计划日期、实际日期和前后依赖。', {
  rows: [
    { id: 'T1', name: '基础施工', plannedStart: '2026-09-01', plannedEnd: '2026-09-15', actualStart: '2026-09-02', actualEnd: '2026-09-16', progress: 100, dependencies: '', status: '已完成' },
    { id: 'T2', name: '主体施工', plannedStart: '2026-09-16', plannedEnd: '2026-09-30', actualStart: '2026-09-17', actualEnd: '2026-09-29', progress: 60, dependencies: 'T1', status: '进行中' },
  ],
  fieldMap: { id: 'id', task: 'name', start: 'plannedStart', end: 'plannedEnd', actualStart: 'actualStart', actualEnd: 'actualEnd', progress: 'progress', dependencies: 'dependencies', status: 'status' },
  notes: ['日期使用 YYYY-MM-DD，结束日期不得早于开始；时间轴范围需要包含这些日期。', 'id 必须唯一；后置任务 dependencies 填前置 id，多个以英文逗号分隔。', '完成进度使用 0–100 的数字。进度与状态显示在任务提示中，日期条按日期区间绘制。最多显示前 100 个任务。'],
})
dataExample('milestone-timeline', '按阶段展示里程碑日期和完成状态。', {
  rows: [{ label: '设计完成', startDate: '2026-08-01', endDate: '2026-08-31', status: '已完成' }, { label: '基础完成', startDate: '2026-09-01', endDate: '2026-09-20', status: '进行中' }],
  fieldMap: identity(['label', 'startDate', 'endDate', 'status']),
  notes: ['日期使用 YYYY-MM-DD；“已完成”显示完成状态，“进行中”显示当前阶段，其他文本按未完成显示。'],
})
dataExample('alert-list', '逐行显示告警事件、等级和处置状态。', {
  rows: [{ level: '紧急', title: '设备温度异常', location: '示例一区', status: '待处置', eventTime: '2026-09-06 09:00:00' }, { level: '一般', title: '材料堆放待整理', location: '示例二区', status: '已关闭', eventTime: '2026-09-06 09:05:00' }],
  displayFields: ['level', 'title', 'location', 'status', 'eventTime'], notes: ['事件时间声明为 datetime；展示字段由数据集字段定义与组件选择共同决定。'],
})
dataExample('realtime-list', '通过平台 WebSocket 通道持续更新事件列表。', {
  rows: [{ id: 'EVT-001', eventType: '人员入场', message: '示例一区门禁记录', status: '成功', eventTime: '2026-09-06 09:00:00' }, { id: 'EVT-002', eventType: '设备告警', message: '示例二区温度提示', status: '关注', eventTime: '2026-09-06 09:01:00' }],
  displayFields: ['eventType', 'message', 'status', 'eventTime'],
  notes: ['以下数组用于说明消息中的 rows 结构。选择已启用的 WEBSOCKET 数据集，才能验证实时订阅。', '本地 test-component-realtime 为平台轮询 JSON 的桥接样例；静态样例只能验证外观，不能证明现场实时来源接通。'],
})

for (const [type, purpose, note] of [
  ['line-chart', '比较随分类或时间变化的趋势。', '按分类顺序返回数据；时间轴时分类使用有效日期或时间。'],
  ['bar-chart', '比较不同分类的数值。', '分类用文本，数值用 number；需要多系列时选择 value 与 planValue。'],
  ['pie-chart', '用扇区表达各分类占比。', '使用非负数值，全部为 0 时不能得到有效占比。'],
  ['ring-chart', '用环形扇区表达分类占比。', '数值使用非负数；内外半径、图例数值和单位在样式中设置。'],
  ['funnel', '比较各阶段数量或转化规模。', '建议按业务阶段返回，并使用非负数；示例用于显示规模，不自动计算转化率。'],
  ['radar', '在多个维度上比较同量纲的数值。', '至少准备 3 个维度；多系列字段均声明为 number，量纲不同应先归一化。'],
  ['area-chart', '用面积表示趋势或累计变化。', '分类按业务顺序返回；堆叠前确认系列可以相加。'],
  ['pictorial-chart', '用图形长度比较数量。', '分类为文本、数量为数字；单位和图形外观在样式中配置。'],
  ['treemap-chart', '用矩形面积比较分类规模。', '当前样例是平级分类，数值使用非负数。'],
  ['word-cloud', '按权重突出关键词。', '分类字段为词语，数值为非负权重；文字长度过长会降低可读性。'],
  ['bar3d-chart', '用受控 3D 柱形效果比较分类数值。', '数据结构与柱状图一致；立体深度在样式中设置。'],
]) dataExample(type, purpose, { ...commonSeries, notes: [note] })
examples.funnel.rows = [{ category: '登记', value: 100 }, { category: '审核', value: 75 }, { category: '通过', value: 50 }]

for (const type of ['gauge', 'progress']) dataExample(type, type === 'gauge' ? '用仪表显示一个数值。' : '用进度形状显示完成度。', {
  rows: [{ value: 68.5 }], fieldMap: { value: 'value' }, notes: ['样例数值为 68.5，单位可设为 %。按业务检查刻度范围，不把“68.5%”字符串当作数值。'],
})
dataExample('scatter', '由两个数值字段确定每个点的位置。', {
  rows: [{ x: 10, y: 30 }, { x: 20, y: 55 }, { x: 35, y: 65 }], fieldMap: { category: 'x', value: 'y' },
  notes: ['维度字段对应 X 数值，数值字段对应 Y 数值；两列均声明为 number。样例首点应位于 (10, 30)。'],
})
dataExample('calendar-chart', '按日期显示每日数值热力。', {
  rows: [{ date: '2026-09-05', value: 28 }, { date: '2026-09-06', value: 42 }, { date: '2026-09-07', value: 17 }], fieldMap: { category: 'date', value: 'value' },
  notes: ['日期使用 YYYY-MM-DD，字段声明为 date；日历范围必须包含 2026 年 9 月，避免样例落在范围外。'],
})
for (const [type, purpose] of [
  ['map-chart', '在地图区域和点位上显示统计值。'], ['map-bar', '在地图点位上显示数量柱形。'],
  ['map-heat', '用点位数值表达空间热力。'], ['map-ranking', '在地图与排名中比较点位数量。'],
]) dataExample(type, purpose, {
  rows: mapRows, fieldMap: identity(['label', 'value', 'longitude', 'latitude']), mapRef,
  notes: ['先在样式 → 地图资源与显示中“使用内置示例”，底图路径见下方；只有坐标数据不能生成地图边界。', '样例名称和坐标已与该底图对应。经纬度必须为数字，经度在前、纬度在后；正式数据应与 GeoJSON 坐标系一致。'],
})
for (const type of ['map-flow', 'map-timeline']) dataExample(type, type === 'map-flow' ? '展示地点之间的连接与流动。' : '按时间分组播放不同批次的飞线。', {
  rows: flowRows, fieldMap: identity(['fromName', 'toName', 'fromLongitude', 'fromLatitude', 'toLongitude', 'toLatitude', 'value', ...(type === 'map-timeline' ? ['group'] : [])]), mapRef,
  notes: ['先在样式中选择内置示例地图，双方经纬度均为数字；起终点名称与底图区域对应。', type === 'map-timeline' ? '样例包含 08:00、12:00 两组，用于观察切换；group 用统一时间或批次格式。' : '每行代表一条起点到终点的连接；value 为流量或权重。'],
})
dataExample('weather', '显示一个城市的温度和天气文本。', {
  rows: [{ city: '示例城市', temperature: 28.5, condition: '多云' }], fieldMap: identity(['city', 'temperature', 'condition']),
  notes: ['温度必须为数字，℃ 等单位在样式设置；静态样例不是实时天气，需要真实更新时绑定受控数据集。'],
})
dataExample('ring-text', '沿轨道排列并旋转一组文字。', {
  rows: [{ label: '质量' }, { label: '进度' }, { label: '安全' }], fieldMap: { category: 'label' },
  notes: ['每行提供一段短文字；旋转速度和方向在样式中设置，最多取前 24 段文字。'],
})
dataExample('color-block', '用颜色与文字显示状态。', {
  rows: [{ color: '#22c55e', label: '运行正常' }], fieldMap: { value: 'color', label: 'label' },
  notes: ['颜色字段使用有效颜色值，如 #22c55e；此处“数值角色 value”应映射到颜色文本。', '也可不绑定数据，直接在样式设置颜色块颜色和状态文字。'],
})
dataExample('custom-html', '在隔离沙箱中显示自定义内容，可接收绑定数据。', {
  rows: [{ label: '完成率', value: 68.5 }],
  notes: ['可以不绑定数据，仅配置静态内容；需要数据时由 JLink.onData 接收 rows，普通字段映射不会自动生成页面。', 'JLink 数据接收需在页面预览或运行中验证；设计画布未注入数据桥接。下方配置参考只展示静态内容，不消费样例数据。', '最小内容示例可复制到样式的自定义内容中；外部网络、嵌套页面和存储访问仍受平台校验。'],
  configuration: '<style>.note{font-size:24px;color:#dceeff}</style><div class="note">项目运行正常</div>',
})

const formNotes = ['表单自身无需数据集。字段绑定页面参数，页面筛选需指定同名参数和目标数据组件。', '目标数据集先声明参数，例如 keyword；提交表单只查询数据，不写回业务系统。']
for (const [type, purpose] of [['filter-form', '输入条件并联动页面数据。'], ['designer-form', '用可视化配置的查询字段联动页面数据。'], ['online-form', '在线填写查询条件并更新关联数据组件。']])
  configExample(type, purpose, '在样式中配置表单字段，在页面属性中配置筛选与目标组件。', formNotes)
configExample('tabs', '在一个画布中切换内容面板。', '在样式中维护选项卡标题、内容及关联组件。', ['每个选项卡选择对应组件；不填写 widgetIds 时可展示该选项卡的文字内容。'])
configExample('current-time', '显示运行设备的本地日期和时间。', '在样式中选择时间格式。', ['不请求数据集；使用运行设备的系统时间。'], { timeFormat: 'YYYY-MM-DD HH:mm:ss' })
configExample('text', '显示标题或说明文字。', '在样式中填写文本内容。', ['数据来自手工文本配置；字体、颜色和对齐方式在样式中调整。'], { text: '工程项目综合管控中心' })
configExample('rich-text', '显示多行纯文本说明。', '在样式中填写富文本内容。', ['该组件按受控纯文本显示，不执行 HTML 标签和脚本。'], { text: '项目概况\n本页为演示内容' })
configExample('icon', '显示平台图标。', '在样式中选择平台图标。', ['使用图标选择器提供的实际图标，不能直接填外部图片地址。'], { iconName: 'star' })
configExample('button', '通过按钮触发页面联动或跳转。', '在样式中填写文字，在交互中选择动作和目标。', ['先选择真实目标页面或组件；未配置动作时只显示按钮外观。'], { text: '查看进度' })
configExample('image', '显示平台图片资源。', '先在资源管理上传或登记图片，再在组件中选择该资源。', ['缩放使用适合图片的 contain / cover；示例说明不附虚假的图片资源路径。'])
configExample('video', '播放平台允许的视频资源。', '先在资源管理登记视频，再选择视频和可选封面。', ['浏览器通常要求静音才能自动播放；现场视频可用性需要单独确认。'], { muted: true, autoplay: false, controls: true })
configExample('iframe', '嵌入已授权的平台内部大屏页面。', '在组件配置中选择平台内部页面。', ['保留目标页面登录权限；不能嵌入自身或形成循环。这里无需绑定数据集。'])
configExample('border', '作为组件或画布的装饰边框。', '在样式中配置边框颜色、线宽与透明度。', ['不读取业务数据；标题栏装饰优先使用资源管理中的标题栏切图。'])
configExample('decoration', '显示装饰或分割线。', '在样式中配置颜色、尺寸和外观。', ['只负责装饰，不读取数据集；用布局宽高控制占用区域。'])

export const dashboardComponentExampleTypes = Object.freeze(Object.keys(labels))

function fieldType(value) {
  if (typeof value === 'number') return 'number'
  if (typeof value === 'boolean') return 'boolean'
  if (/^\d{4}-\d{2}-\d{2}$/.test(String(value))) return 'date'
  if (/^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}/.test(String(value))) return 'datetime'
  return 'string'
}

export function getDashboardComponentExample(widget = {}) {
  const type = widget?.type || ''
  if (!Object.hasOwn(labels, type)) return null
  const capabilities = dashboardComponentCapabilities(widget)
  const effectiveType = type === 'custom-chart' ? capabilities.effectiveType : type
  const example = JSON.parse(JSON.stringify(examples[effectiveType]))
  const fields = Object.entries(example.fieldMap).flatMap(([role, mapped]) => (Array.isArray(mapped) ? mapped : [mapped]).map(field => ({
    role: roleLabels[role] || role, key: role, field, type: fieldType(example.rows[0]?.[field]),
  })))
  if (!fields.length) for (const field of example.displayFields) fields.push({ role: '展示字段', key: '', field, type: fieldType(example.rows[0]?.[field]) })
  if (type === 'custom-html') for (const field of Object.keys(example.rows[0])) fields.push({ role: 'JLink 数据字段', key: '', field, type: fieldType(example.rows[0][field]) })
  if (effectiveType === 'scatter') fields.forEach(item => { if (item.key === 'category') item.role = '维度字段（X 数值）'; if (item.key === 'value') item.role = '数值字段（Y 数值）' })
  if (effectiveType === 'calendar-chart' && fields[0]) fields[0].role = '日期字段'
  if (effectiveType === 'ring-text' && fields[0]) fields[0].role = '文字字段'
  if (effectiveType === 'radar' && fields[0]) fields[0].role = '维度字段'
  if (effectiveType === 'statistics') fields.forEach(item => { if (item.key === 'label') item.role = '统计标题字段'; if (item.key === 'suffix') item.role = '数值后缀字段' })
  if (effectiveType === 'color-block') { fields[0].role = '颜色字段'; fields[1].role = '状态文字字段' }
  return {
    ...example, type, effectiveType, label: labels[type], data: capabilities.data, fields,
    title: type === 'custom-chart' ? `${labels[type]} · ${labels[effectiveType]}` : labels[type],
    source: example.source || '共享数据在数据集管理中维护、解析字段并启用，再在组件“数据”页绑定。组件自身的静态数据只保存在当前页面配置中，不请求数据集。',
    multiSeries: capabilities.multiSeries ? ['value', 'planValue'].filter(field => Object.hasOwn(example.rows[0] || {}, field)) : [],
  }
}
