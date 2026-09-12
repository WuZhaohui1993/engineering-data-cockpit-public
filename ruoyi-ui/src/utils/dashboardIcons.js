import { defineComponent, h } from 'vue'
import {
  Star, Monitor, Edit, WarningFilled, TrendCharts, UserFilled, Setting,
  Clock, Document, VideoCamera, CircleCheck, Tools, FolderOpened,
  Location, Cloudy, FirstAidKit, WindPower, Van, Position,
  Cpu, Bell, Search,
} from '@element-plus/icons-vue'

const lineIcon = (name, paths) => defineComponent({ name, setup: () => () => h('svg', { viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', 'stroke-width': 1.8, 'stroke-linecap': 'round', 'stroke-linejoin': 'round' }, paths.map(d => h('path', { d }))) })
const ThermometerIcon = lineIcon('DashboardThermometer', ['M9 14.5V5a3 3 0 0 1 6 0v9.5a5 5 0 1 1-6 0Z', 'M12 8v10', 'M11 18h2', 'M17 6h3M17 10h2'])
const ShieldIcon = lineIcon('DashboardShield', ['M12 2 3 6v6c0 5 9 10 9 10s9-5 9-10V6Z', 'm8 12 3 3 5-6'])
const HelmetIcon = lineIcon('DashboardHelmet', ['M4 16v-3a8 8 0 0 1 16 0v3', 'M2 16h20v4H2Z', 'M10 4v7M14 4v7'])

// The palette and both renderers share the same controlled icon catalog.
export const dashboardIconOptions = [
  ['star', '星标', Star], ['monitor', '监控', Monitor], ['edit', '编辑', Edit],
  ['warning', '告警', WarningFilled], ['trend', '趋势', TrendCharts], ['user', '人员', UserFilled],
  ['setting', '设置', Setting], ['clock', '时钟', Clock], ['document', '记录', Document],
  ['video', '视频', VideoCamera], ['shield', '安全', ShieldIcon], ['check', '完成', CircleCheck],
  ['tools', '施工工具', Tools], ['folder', '档案', FolderOpened], ['location', '点位', Location],
  ['cloud', '环境', Cloudy], ['temperature', '温度', ThermometerIcon], ['wind', '风速', WindPower],
  ['truck', '车辆', Van], ['drone', '巡检', Position], ['helmet', '防护', HelmetIcon],
  ['health', '健康', FirstAidKit], ['ai', '智能分析', Cpu], ['bell', '通知', Bell], ['search', '检查', Search],
].map(([value, label, icon]) => ({ value, label, icon }))

export const dashboardIconMap = Object.fromEntries(dashboardIconOptions.map(item => [item.value, item.icon]))
