export const FOLLOW_PUBLISHED_SHARE_MODE = 'FOLLOW_PUBLISHED'
export const FIXED_SHARE_MODE = 'FIXED'

// 已有链接缺少字段时保留历史固定版本语义；新建默认值由创建表单单独设置。
export function normalizeDashboardShareVersionMode(value) {
  return value === FOLLOW_PUBLISHED_SHARE_MODE ? FOLLOW_PUBLISHED_SHARE_MODE : FIXED_SHARE_MODE
}

export function dashboardShareVersionModeLabel(value) {
  return normalizeDashboardShareVersionMode(value) === FOLLOW_PUBLISHED_SHARE_MODE ? '跟随当前发布' : '固定版本'
}

export function dashboardShareVersionModeHint(value) {
  return normalizeDashboardShareVersionMode(value) === FOLLOW_PUBLISHED_SHARE_MODE
    ? '已授权页面发布或回滚后，分享画面跟随当前发布版本；草稿修改不生效，也不会自动增加可访问页面。'
    : '固定创建或切换模式时各授权页面的发布配置，适合评审；绑定的数据集仍按当前数据更新，不是数据快照。'
}

export function dashboardShareVersionModeConfirmation(value) {
  return normalizeDashboardShareVersionMode(value) === FOLLOW_PUBLISHED_SHARE_MODE
    ? '改为跟随当前发布后，此链接及已打开的分享画面会跟随已授权页面的发布和回滚。分享地址、有效期及可访问页面集合保持不变；全部授权页面须启用且有发布版本。'
    : '切为固定版本后，将固定各授权页面此刻的当前发布配置，后续发布和回滚不再自动跟随。分享地址、有效期及可访问页面集合保持不变；绑定数据仍会更新，全部授权页面须启用且有发布版本。'
}
