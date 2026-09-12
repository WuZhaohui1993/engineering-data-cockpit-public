import test from 'node:test'
import assert from 'node:assert/strict'
import { integrationProjectScope, timezoneOptions, timezoneLabel } from '../src/utils/dashboardIntegrationPresentation.js'

test('项目范围读取真实管理接口数组，同时兼容旧格式，不修改原值', () => {
  const row = { projectScope: ['allowed'], projectScopeJson: '["stale"]' }
  assert.deepEqual(integrationProjectScope(row), ['allowed'])
  integrationProjectScope(row).push('other')
  assert.deepEqual(row.projectScope, ['allowed'])
  assert.deepEqual(integrationProjectScope({ projectScopeJson: '["legacy"]' }), ['legacy'])
  assert.deepEqual(integrationProjectScope({}), [])
})

test('时区选择提供中国标准时间和 UTC，旧时区别名回显不改变后续选项', () => {
  const standard = timezoneOptions()
  assert.equal(new Set(standard.map(item => item.value)).size, standard.length)
  assert.ok(standard.some(item => item.value === 'Asia/Shanghai'))
  assert.ok(standard.some(item => item.value === 'UTC'))
  assert.match(timezoneLabel('Asia/Shanghai'), /^中国标准时间/)
  assert.ok(timezoneOptions('+08:00').some(item => item.value === '+08:00'))
  assert.ok(!timezoneOptions().some(item => item.value === '+08:00'))
})
