export const fullscreenScaleOptions = [
  {
    value: 'contain',
    label: '自适应完整显示（contain）',
    description: '按屏幕比例自动缩放，完整显示所有内容；比例不一致时保留留边。',
  },
  {
    value: 'cover',
    label: '自适应铺满（cover）',
    description: '按统一比例自适应放大，优先铺满屏幕宽或高；超出方向可滚动查看，组件不会变形。',
  },
  {
    value: 'stretch',
    label: '拉伸铺满（stretch）',
    description: '按屏幕宽高分别缩放，不留边也不裁剪；文字和图形可能变形。',
  },
]

export function normalizeFullscreenScaleMode(canvas = {}) {
  if (fullscreenScaleOptions.some(({ value }) => value === canvas.fullscreenScaleMode)) {
    return canvas.fullscreenScaleMode
  }
  // 缺少配置的历史页面沿用上一版全屏行为；新页面明确保存 contain。
  return canvas.scaleMode === 'stretch' ? 'stretch' : 'cover'
}

export function calculateFullscreenScale(canvasWidth, canvasHeight, viewportWidth, viewportHeight, mode) {
  if (![canvasWidth, canvasHeight, viewportWidth, viewportHeight]
    .every(value => Number.isFinite(value) && value > 0)) {
    return { x: 1, y: 1 }
  }
  const x = viewportWidth / canvasWidth
  const y = viewportHeight / canvasHeight
  if (mode === 'stretch') return { x, y }
  const scale = mode === 'cover' ? Math.max(x, y) : Math.min(x, y)
  return { x: scale, y: scale }
}
