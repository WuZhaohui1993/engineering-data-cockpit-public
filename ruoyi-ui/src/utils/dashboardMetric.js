/**
 * 为指标值保留配置的小数位；未配置时保持现有显示结果。
 * 空值和非数值原样返回，避免把无数据、状态文字或布尔值显示为 0。
 */
export function formatDashboardMetricValue(value, decimalPlaces) {
  if (
    decimalPlaces === undefined ||
    decimalPlaces === null ||
    (typeof decimalPlaces !== 'number' && typeof decimalPlaces !== 'string') ||
    (typeof decimalPlaces === 'string' && !decimalPlaces.trim())
  ) return value;

  const configuredPlaces = Number(decimalPlaces);
  if (!Number.isFinite(configuredPlaces)) return value;
  if (
    value === undefined ||
    value === null ||
    (typeof value !== 'number' && typeof value !== 'string') ||
    (typeof value === 'string' && !value.trim())
  ) return value;

  const numericValue = Number(value);
  if (!Number.isFinite(numericValue)) return value;
  const places = Math.max(0, Math.min(6, Math.trunc(configuredPlaces)));
  return numericValue.toFixed(places);
}

/** Apply configured scale before formatting once, preserving missing values and precision. */
export function formatDashboardMetricDisplay(value, style = {}) {
  if (value === null || value === undefined || typeof value === 'boolean' ||
    (typeof value !== 'number' && typeof value !== 'string') ||
    (typeof value === 'string' && !value.trim()) || !Number.isFinite(Number(value))) return value;
  const config = style.chartConfig || {};
  const [factor, suffix] = { thousand: [1000, '千'], 'ten-thousand': [10000, '万'], million: [1000000, '百万'] }[config.valueScale] || [1, ''];
  const places = style.valueDecimalPlaces;
  return `${formatDashboardMetricValue(Number(value) / factor, places)}${suffix}`;
}
