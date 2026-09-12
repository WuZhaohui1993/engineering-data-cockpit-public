export function dashboardTableRule(rules, field, value) {
  if (!Array.isArray(rules)) return {}
  return rules.find(rule => rule?.field === field && (rule.equals === undefined || String(rule.equals) === String(value))) || {}
}
export function dashboardTableCellStyle(rules, field, value) {
  const rule = dashboardTableRule(rules, field, value)
  return { color: rule.color || undefined, background: rule.background || undefined, ...(rule.badge ? { display: 'inline-block', padding: '2px 8px', border: '1px solid currentColor', borderRadius: '3px', lineHeight: '1.15' } : {}) }
}
export function dashboardTableSuffix(rules, field, value) {
  return dashboardTableRule(rules, field, value).suffix || ''
}
