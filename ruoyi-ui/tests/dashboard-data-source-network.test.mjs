import test from 'node:test'
import assert from 'node:assert/strict'
import { fillHttpSourceAllowlist, httpSourceNetworkError, splitSourceList } from '../src/utils/dashboardDataSourceNetwork.js'
import { networkProfileLabel } from '../src/utils/dashboardIntegrationPresentation.js'

test('HTTP、HTTPS 与自定义端口按地址填入白名单', () => {
  for (const [baseUrl, port] of [
    ['http://api.example.com', '80'],
    ['https://api.example.com', '443'],
    ['http://api.example.com:8080/business', '8080'],
    ['https://api.example.com:8443/business', '8443'],
  ]) {
    const form = { baseUrl, allowedHostsText: '', allowedPortsText: '' }
    fillHttpSourceAllowlist(form)
    assert.equal(form.allowedHostsText, 'api.example.com')
    assert.equal(form.allowedPortsText, port)
  }
})

test('修改地址会更新自动填写值，保留手工修改和已保存白名单', () => {
  const form = { baseUrl: 'https://api.example.com', allowedHostsText: '', allowedPortsText: '' }
  let defaults = fillHttpSourceAllowlist(form)
  form.baseUrl = 'http://legacy.example.com:8080'
  defaults = fillHttpSourceAllowlist(form, defaults)
  assert.equal(form.allowedHostsText, 'legacy.example.com')
  assert.equal(form.allowedPortsText, '8080')

  form.allowedHostsText = 'legacy.example.com,backup.example.com'
  // 即使手工输入值恰好与上次自动值相同，也保持用户选择。
  defaults.allowedPortsText = undefined
  form.baseUrl = 'https://other.example.com:8443'
  fillHttpSourceAllowlist(form, defaults)
  assert.equal(form.allowedHostsText, 'legacy.example.com,backup.example.com')
  assert.equal(form.allowedPortsText, '8080')

  fillHttpSourceAllowlist(form, {})
  assert.equal(form.allowedHostsText, 'legacy.example.com,backup.example.com')
  assert.equal(form.allowedPortsText, '8080')
})

test('不完整地址或非 HTTP 地址不改写白名单', () => {
  for (const baseUrl of ['', 'http://', 'ftp://api.example.com']) {
    const form = { baseUrl, allowedHostsText: '', allowedPortsText: '' }
    fillHttpSourceAllowlist(form)
    assert.equal(form.allowedHostsText, '')
    assert.equal(form.allowedPortsText, '')
  }
})

test('PUBLIC_HTTP 明确要求主机和端口白名单，保留 HTTPS 与专网规则', () => {
  const form = { networkProfile: 'PUBLIC_HTTP', allowedHostsText: '', allowedPortsText: '' }
  assert.match(httpSourceNetworkError(form), /主机白名单/)
  form.allowedHostsText = 'api.example.com'
  assert.match(httpSourceNetworkError(form), /端口白名单/)
  form.allowedPortsText = '80，8080\n443'
  assert.equal(httpSourceNetworkError(form), '')
  assert.deepEqual(splitSourceList(form.allowedPortsText).map(Number), [80, 8080, 443])
  for (const port of ['0', '65536', '80.5', 'http']) {
    form.allowedPortsText = port
    assert.match(httpSourceNetworkError(form), /1-65535/)
  }
  assert.equal(httpSourceNetworkError({ networkProfile: 'PUBLIC_HTTPS' }), '')
  assert.match(httpSourceNetworkError({ networkProfile: 'PRIVATE_LINK' }), /CIDR/)
  assert.equal(httpSourceNetworkError({ networkProfile: 'PRIVATE_LINK', allowedCidrsText: '10.0.0.0/8', allowedPortsText: '8080' }), '')
  assert.equal(networkProfileLabel('PUBLIC_HTTP'), '公网访问（HTTP/HTTPS）')
})
