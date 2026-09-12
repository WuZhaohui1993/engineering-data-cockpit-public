/**
 * 正式运行、生成分享和菜单配置都要求启用中的发布版本。列表必须传入后端
 * /page/published 目录；详情可使用 revisions，不能把草稿版本号当成发布状态。
 */
export function isDashboardPageRunnable(page, publishedPages) {
  if (!hasDashboardPublishedVersion(page, publishedPages)) return false
  if (String(page.status) !== '0' || String(page.isDeleted ?? '0') !== '0') return false
  return true
}

export function getDashboardPublishedRevision(page, publishedPages) {
  if (!page || !Number.isSafeInteger(Number(page.pageId)) || Number(page.pageId) <= 0) return null
  if (Array.isArray(publishedPages)) {
    return publishedPages.find((item) => String(item.pageId) === String(page.pageId)) || null
  }
  if (Array.isArray(page.revisions)) {
    const published = page.revisions.filter((revision) => revision.status === 'PUBLISHED')
    return published.find((revision) => page.currentRevisionId != null && String(revision.revisionId) === String(page.currentRevisionId))
      || published.sort((left, right) => Number(right.versionNo) - Number(left.versionNo) || Number(right.revisionId) - Number(left.revisionId))[0]
      || null
  }
  return page.currentRevisionStatus === 'PUBLISHED'
    ? { revisionId: page.currentRevisionId, versionNo: page.currentVersionNo, status: 'PUBLISHED' }
    : null
}

export function hasDashboardPublishedVersion(page, publishedPages) {
  return getDashboardPublishedRevision(page, publishedPages) !== null
}
