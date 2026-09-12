import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const apiSource = await readFile(new URL('../src/api/dashboard.js', import.meta.url), 'utf8')
const api = await import(`data:text/javascript;base64,${Buffer.from(apiSource.replace(
  "import request from '@/utils/request'", 'const request = options => options'
)).toString('base64')}`)
const controller = await readFile(new URL('../../ruoyi-backend/ruoyi-admin/src/main/java/com/ruoyi/web/controller/dashboard/DashboardController.java', import.meta.url), 'utf8')

test('数据源编码与分页或配置保留词相同时仍请求详情', () => {
  for (const code of ['page', 'list', 'configuration', 'detail', 'code', 'source.with-dots']) {
    assert.deepEqual(api.getDashboardDataSource(code), {
      url: `/dashboard/source/detail/code/${encodeURIComponent(code)}`, method: 'get'
    })
  }
  assert.equal(api.getDashboardDataSource('a b/c?d').url, '/dashboard/source/detail/code/a%20b%2Fc%3Fd')
})

test('后端详情别名沿用查看权限且保留旧路径', () => {
  assert.match(controller, /@PreAuthorize\("@ss\.hasPermi\('dashboard:dataset:list'\)"\)\s+@GetMapping\(\{"\/source\/detail\/code\/\{sourceCode\}", "\/source\/\{sourceCode\}"\}\)\s+public AjaxResult dataSource/)
  assert.match(controller, /@GetMapping\("\/source\/\{sourceCode\}\/configuration"\)/)
  assert.match(controller, /@GetMapping\("\/source\/page"\)/)
})

test('详情别名不会被其他数据源 GET 路径匹配', () => {
  const patterns = [...controller.matchAll(/@GetMapping\(([\s\S]*?)\)/g)]
    .flatMap(match => [...match[1].matchAll(/"(\/source[^"\s]*)"/g)].map(value => value[1]))
  const matches = (pattern, path) => {
    const regex = pattern.split('/').map(segment => segment.startsWith('{')
      ? '[^/]+'
      : segment.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('/')
    return new RegExp(`^${regex}$`).test(path)
  }
  for (const code of ['page', 'list', 'configuration', 'detail', 'code']) {
    const path = `/source/detail/code/${code}`
    assert.deepEqual(patterns.filter(pattern => matches(pattern, path)), ['/source/detail/code/{sourceCode}'])
  }
})
