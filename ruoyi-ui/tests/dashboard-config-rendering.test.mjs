import test from 'node:test'
import assert from 'node:assert/strict'
import {buildGanttModel,ganttDay} from '../src/utils/dashboardGantt.js'
import {mergeDashboardChartOption} from '../src/utils/dashboardChartOptions.js'
import {formatDashboardMetricValue as f} from '../src/utils/dashboardMetric.js'
test('甘特日期验证及跨月轴',()=>{
 assert.equal(ganttDay('2026-02-30'),null)
 assert.equal(ganttDay(''),null)
 const model=buildGanttModel([{name:'基准',plannedStart:'2026-04-01',plannedEnd:'2026-06-01'}],{},{ganttStart:'2026-04-01',ganttEnd:'2026-07-01'})
 assert.deepEqual(model.months.map(x=>x.label),['4月','5月','6月','7月'])
 assert.ok(model.tasks[0].plan.w>0);assert.equal(model.tasks[0].actual,null)
})
test('跨窗口和倒序日期不会越界，任务依赖跟随数据',()=>{
 const rows=[{id:'a',name:'土建',plannedStart:'2025-01-01',plannedEnd:'2026-05-01'},{id:'b',name:'设备',plannedStart:'2026-05-01',plannedEnd:'2027-01-01',dependencies:'a'}]
 const m=buildGanttModel(rows,{}, {ganttStart:'2026-04-01',ganttEnd:'2026-12-31',ganttCurrentDate:'2026-09-05'},960,360)
 assert.equal(m.links.length,1);assert.equal(m.tasks[0].plan.x,m.labelWidth);assert.ok(m.tasks[1].plan.x+m.tasks[1].plan.w<=960)
 assert.ok(m.currentX>m.labelWidth && m.currentX<960)
 rows[1].plannedEnd='2025-01-01';assert.equal(buildGanttModel(rows).tasks[1].plan,null)
})
test('横向柱样式保留JSON数据，更新分类同步',()=>{
 const base={xAxis:{type:'category',data:['焊接','设备']},yAxis:{type:'value'},series:[{type:'bar',data:[54,28],label:{show:true}}]}
 const custom={xAxis:{type:'value'},yAxis:{type:'category'},series:[{barWidth:14,itemStyle:{color:'#1687ff'}}]}
 const m=mergeDashboardChartOption(base,custom,true)
 assert.deepEqual(m.series[0].data,[54,28]);assert.deepEqual(m.yAxis.data,['焊接','设备']);assert.equal(m.xAxis.data,undefined)
 base.xAxis.data=['新增专业'];base.series[0].data=[3]
 assert.deepEqual(mergeDashboardChartOption(base,custom,true).yAxis.data,['新增专业'])
 assert.deepEqual(mergeDashboardChartOption(base,{series:[{data:[8]}]},true).series[0].data,[8])
})
test('小数位保持0、空值及非数值语义',()=>{
 for(const [v,p,w] of [[61,1,'61.0'],[0,1,'0.0'],[-3,1,'-3.0'],[undefined,1,undefined],[null,1,null],['',1,''],['暂无数据',1,'暂无数据'],[false,1,false],[61,undefined,61]]) assert.equal(f(v,p),w)
})
