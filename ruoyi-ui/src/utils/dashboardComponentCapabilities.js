import { dashboardTitleText } from './dashboardPresentation.js'

// 属性面板使用实际渲染类型判断能力；隐藏控件不删除已保存的配置。
const chartTypes = new Set(['line-chart', 'bar-chart', 'area-chart', 'pie-chart', 'ring-chart', 'gauge', 'progress', 'funnel', 'radar', 'scatter', 'pictorial-chart', 'treemap-chart', 'calendar-chart', 'bar3d-chart'])
const customTypes = new Set(['line-chart', 'bar-chart', 'area-chart', 'pie-chart', 'ring-chart', 'gauge', 'progress', 'funnel', 'radar', 'scatter'])
const axes = new Set(['line-chart', 'bar-chart', 'area-chart', 'scatter', 'pictorial-chart', 'bar3d-chart'])
const legends = new Set(['line-chart', 'bar-chart', 'area-chart', 'pie-chart', 'ring-chart', 'funnel', 'radar'])
const noData = new Set(['text', 'rich-text', 'icon', 'button', 'image', 'video', 'iframe', 'current-time', 'border', 'decoration', 'tabs', 'filter-form', 'designer-form', 'online-form'])
const singleValue = new Set(['metric-card', 'number-flip', 'statistics', 'gauge', 'progress', 'weather', 'color-block'])
const ownControls = new Set(['filter-form', 'designer-form', 'online-form', 'tabs', 'video', 'iframe', 'custom-html'])
const textTypes = new Set(['metric-card', 'number-flip', 'text', 'rich-text', 'icon', 'button', 'weather', 'current-time', 'color-block', 'table', 'rank-table', 'carousel-table', 'advanced-table', 'alert-list', 'realtime-list', 'carousel', 'ring-text', 'filter-form', 'designer-form', 'online-form', 'tabs', 'gantt-chart', 'statistics'])

export function dashboardComponentCapabilities(widget = {}) {
  const type = widget.type === 'custom-chart'
    ? (customTypes.has(widget.style?.customChartType) ? widget.style.customChartType : 'bar-chart')
    : widget.type
  const chart = chartTypes.has(type)
  const imageIcon = type === 'icon' && Boolean(widget.style?.imageRef)
  return {
    effectiveType: type,
    chart,
    data: Boolean(type) && !noData.has(type),
    rowLimit: Boolean(type) && !noData.has(type) && !singleValue.has(type),
    interaction: Boolean(type) && !ownControls.has(type),
    dataInteraction: Boolean(type) && !noData.has(type) && !ownControls.has(type),
    multiSeries: ['line-chart', 'bar-chart', 'area-chart', 'radar'].includes(type),
    legend: legends.has(type),
    axis: axes.has(type),
    grid: axes.has(type),
    center: ['pie-chart', 'ring-chart', 'radar'].includes(type),
    markLine: axes.has(type),
    smooth: ['line-chart', 'area-chart'].includes(type),
    stack: ['line-chart', 'area-chart', 'bar-chart'].includes(type),
    typography: textTypes.has(type),
    textAlign: textTypes.has(type) && !['ring-text', 'gantt-chart', 'weather', 'carousel'].includes(type),
    verticalAlign: ['metric-card', 'number-flip', 'statistics', 'text', 'rich-text', 'icon', 'button', 'current-time', 'color-block', 'filter-form', 'designer-form', 'online-form', 'tabs'].includes(type),
    accent: !imageIcon && !['border', 'image', 'video', 'iframe', 'custom-html', 'pie-chart', 'ring-chart', 'funnel', 'treemap-chart', 'calendar-chart', 'bar3d-chart', 'word-cloud'].includes(type) && !String(type).startsWith('map-') && (!chart || (!(widget.style?.chartConfig?.colors?.length) && !(widget.style?.chartConfig?.colorsText?.trim()) && !(widget.binding?.fieldMap?.valueFields?.length > 1))),
  }
}

export function dashboardPropertyVisible(widget, path) {
  const c = dashboardComponentCapabilities(widget)
  const s = widget?.style || {}
  const chart = s.chartConfig || {}
  const button = c.effectiveType === 'button'
  const imageButton = button && s.titleImageEnabled === true
  const statistics = c.effectiveType === 'statistics'
  const statisticsImage = statistics && s.titleImageEnabled === true && Boolean(dashboardTitleText(s.titleImageRef))
  if (statistics && path === 'style.title') return true
  if (statistics && /^style.stats(LabelPosition|LabelSize|LabelColor|Layout)$/.test(path)) return s.titleVisible !== false && !statisticsImage
  if (statistics && (/^style.title(Align|VerticalAlign|FontSize|FontWeight|Color|Padding)/.test(path) || /^style.subtitle/.test(path)) && !statisticsImage) return false
  if (button && (/^style.title(?!Image)/.test(path) || /^style.subtitle/.test(path))) return false
  if (imageButton && (['style.buttonFill', 'style.buttonShape', 'style.backgroundColor', 'style.backgroundTransparent', 'style.embeddedMode', 'style.borderTransparent', 'style.borderRadius'].includes(path) || /^style.border(Color|Style|Width|Opacity)$/.test(path) || /^style.contentPadding/.test(path))) return false
  if (path === 'binding.rowLimit') return c.rowLimit
  if (path === 'style.iconFrame') return c.effectiveType === 'icon' && !s.imageRef
  if (path === 'style.qualityVisible') return c.data
  if (['style.color', 'style.useSystemPalette'].includes(path)) return c.accent
  if (path === 'style.fontSize') return c.typography && c.effectiveType !== 'statistics'
  if (path === 'style.fontWeight') return c.typography && c.effectiveType !== 'icon'
  if (path === 'style.textAlign') return c.textAlign
  if (path === 'style.contentVerticalAlign') return c.verticalAlign
  if (path === 'style.backgroundColor') return !s.backgroundTransparent && !s.embeddedMode
  if (path === 'style.backgroundTransparent') return !s.embeddedMode
  if (path === 'style.borderTransparent') return !s.embeddedMode
  if (/^style.border(Color|Style|Width|Opacity)$/.test(path)) return !s.borderTransparent && !s.embeddedMode
  if (/^style.flipCell/.test(path)) return s.flipSplitDigits === true
  if (/^style.stats(Up|Down)Color$/.test(path)) return s.statsShowCompare !== false
  if (path === 'style.accessAvatarSize') return s.accessShowAvatar !== false
  if (path === 'style.timelineDateFormat') return s.timelineShowDates !== false || s.timelineShowEndDate === true
  if (path === 'style.ringTextDirection') return Number(s.ringTextSpeed ?? 20) > 0
  if (path === 'style.mapAreaColor') return !s.mapAreaGradient
  if (/^style.mapLabel/.test(path)) return s.mapShowLabels === true
  if (/^style.mapShadow(Color|OffsetX|OffsetY)$/.test(path)) return Number(s.mapShadowBlur) > 0
  if (path === 'style.titleImageAlign') return (button || s.titleVisible !== false) && s.titleImageFit === 'contain' && Boolean(dashboardTitleText(s.titleImageRef))
  if (path === 'style.titleImageHeight') return !button && s.titleVisible !== false && Boolean(dashboardTitleText(s.titleImageRef))
  if (path === 'style.titleImageFit') return (button || s.titleVisible !== false) && Boolean(dashboardTitleText(s.titleImageRef))
  if (/^style.subtitle(Color|FontSize|FontWeight)$/.test(path)) return s.titleVisible !== false && Boolean(dashboardTitleText(s.subtitle))
  if (/^style.title(Align|VerticalAlign|FontSize|FontWeight|Color|Padding)/.test(path) || path === 'style.subtitle' || path === 'style.title') return s.titleVisible !== false
  if (path === 'style.chartConfig.legend.position') return c.legend && c.effectiveType !== 'ring-chart'
  if (/^style.ringLegend/.test(path)) {
    if (!chart.legend?.show) return false
    if (path === 'style.ringLegendColumns') return chart.legend.orient === 'vertical'
    if (path === 'style.ringLegendSuffix') return s.ringLegendShowValue === true
  }
  if (/^style.chartConfig.[xy]Axis.(name|axisLineShow|label)/.test(path)) return chart[path.includes('.xAxis.') ? 'xAxis' : 'yAxis']?.show !== false
  if (/^style.chartConfig.[xy]Axis.splitLineColor$/.test(path)) return chart[path.includes('.xAxis.') ? 'xAxis' : 'yAxis']?.splitLineShow === true
  return true
}
