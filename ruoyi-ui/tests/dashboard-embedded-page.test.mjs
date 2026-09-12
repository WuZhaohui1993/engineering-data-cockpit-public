import assert from 'node:assert/strict'
import test from 'node:test'
import { dashboardEmbeddedPageUrl } from '../src/utils/dashboardEmbeddedPage.js'

test('平台运行页嵌入保留认证路由并强制嵌入显示', () => {
  assert.equal(dashboardEmbeddedPageUrl('/dashboard/runtime/12?preview=1'), '/dashboard/runtime/12?preview=1&embedMode=1&embeddedDepth=1')
  assert.equal(dashboardEmbeddedPageUrl('/dashboard/runtime/code/demo-page'), '/dashboard/runtime/code/demo-page?embedMode=1&embeddedDepth=1')
})
test('不允许外部页面、管理页、路径穿越和递归嵌入', () => {
  for (const url of ['https://example.com', '//example.com', '/dashboard/designer/12', '/dashboard/runtime/../resource', '/dashboard/runtime/code/a%2fb', '/dashboard/runtime/1#test']) assert.equal(dashboardEmbeddedPageUrl(url), '')
  assert.equal(dashboardEmbeddedPageUrl('/dashboard/runtime/1', '/dashboard/runtime/1?preview=1'), '')
  assert.equal(dashboardEmbeddedPageUrl('/dashboard/runtime/2', '/dashboard/runtime/1?embeddedDepth=1'), '')
})
