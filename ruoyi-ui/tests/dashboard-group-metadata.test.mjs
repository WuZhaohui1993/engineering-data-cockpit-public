import test from 'node:test'
import assert from 'node:assert/strict'
import { normalizeDashboardGroups, copyDashboardGroupMetadata } from '../src/utils/dashboardGroupMetadata.js'

const widgets = (...ids) => ids.map(groupId => ({ groupId }))

test('旧页面无组合元数据时按组合首次出现顺序补全名称，并默认折叠', () => {
  assert.deepEqual(normalizeDashboardGroups(widgets('b', '', 'a', 'b')), {
    b: { name: '组合 1', collapsed: true },
    a: { name: '组合 2', collapsed: true },
  })
})

test('保留有效名称和显式展开状态，清理不存在的组合', () => {
  assert.deepEqual(normalizeDashboardGroups(widgets('b', 'a'), {
    a: { name: '  设备信息  ', collapsed: true, ignored: 'unused' },
    b: { name: '进度指标', collapsed: false },
    orphan: { name: '已解组', collapsed: false },
  }), {
    b: { name: '进度指标', collapsed: false },
    a: { name: '设备信息', collapsed: true },
  })
})

test('空页面和无效组件输入不保留孤立元数据', () => {
  for (const input of [undefined, null, {}, '', [], [null, {}, { groupId: '  ' }, { groupId: 1 }, { groupId: false }, { groupId: {} }]]) {
    assert.deepEqual(normalizeDashboardGroups(input, { orphan: { name: '孤立组合' } }), {})
  }
})

test('无效元数据、名称及折叠值均使用安全默认值', () => {
  const expected = { g: { name: '组合 1', collapsed: true } }
  for (const groups of [undefined, null, false, 'text', [], { g: null }, { g: [] }, { g: 'name' }]) {
    assert.deepEqual(normalizeDashboardGroups(widgets('g'), groups), expected)
  }
  for (const name of [undefined, null, '', '   ', false, 12, {}, []]) {
    assert.deepEqual(normalizeDashboardGroups(widgets('g'), { g: { name } }), expected)
  }
  for (const collapsed of [undefined, null, 0, '', 'false', {}, []]) {
    assert.deepEqual(normalizeDashboardGroups(widgets('g'), { g: { collapsed } }), expected)
  }
})

test('组合名称修剪空白并限制长度为 100', () => {
  assert.equal(normalizeDashboardGroups(widgets('g'), { g: { name: `  ${'指'.repeat(120)}  ` } }).g.name, '指'.repeat(100))
})

test('特殊组合 ID 使用自有数据属性，不读取继承元数据或修改对象原型', () => {
  const ids = ['__proto__', 'constructor', 'toString']
  const groups = Object.fromEntries(ids.map(id => [id, { name: `组合 ${id}`, collapsed: false }]))
  const result = normalizeDashboardGroups(widgets(...ids), groups)
  assert.equal(Object.getPrototypeOf(result), Object.prototype)
  for (const id of ids) {
    assert.ok(Object.hasOwn(result, id))
    assert.deepEqual(result[id], { name: `组合 ${id}`, collapsed: false })
  }
  assert.deepEqual(normalizeDashboardGroups(widgets('g'), Object.create({ g: { name: '继承名称', collapsed: false } })), {
    g: { name: '组合 1', collapsed: true },
  })
})

test('规范化不修改原组件和元数据，结果可以单独编辑', () => {
  const sourceWidgets = Object.freeze([Object.freeze({ id: 'a', groupId: 'g' })])
  const sourceGroups = Object.freeze({ g: Object.freeze({ name: '进度', collapsed: false }) })
  const result = normalizeDashboardGroups(sourceWidgets, sourceGroups)
  result.g.name = '新名称'
  result.g.collapsed = true
  assert.deepEqual(sourceGroups, { g: { name: '进度', collapsed: false } })
  assert.deepEqual(sourceWidgets, [{ id: 'a', groupId: 'g' }])
})

test('复制接受 Map 和普通对象，仅返回独立的目标组合元数据', () => {
  const groups = Object.freeze({
    g: Object.freeze({ name: '核心指标', collapsed: false }),
    h: Object.freeze({ name: '环境监测', collapsed: true }),
  })
  const expected = {
    gCopy: { name: '核心指标 副本', collapsed: false },
    hCopy: { name: '环境监测 副本', collapsed: true },
  }
  for (const mapping of [{ g: 'gCopy', h: 'hCopy' }, new Map([['g', 'gCopy'], ['h', 'hCopy']])]) {
    const result = copyDashboardGroupMetadata(groups, mapping)
    assert.deepEqual(result, expected)
    result.gCopy.name = '复制件重命名'
    result.gCopy.collapsed = true
    assert.deepEqual(groups.g, { name: '核心指标', collapsed: false })
  }
})

test('旧组合复制补齐默认名称，长名称保留副本后缀', () => {
  assert.deepEqual(copyDashboardGroupMetadata(undefined, new Map([['g', 'newG']])), {
    newG: { name: '组合 1 副本', collapsed: true },
  })
  const name = copyDashboardGroupMetadata({ g: { name: '指'.repeat(100) } }, { g: 'newG' }).newG.name
  assert.equal(name, `${'指'.repeat(97)} 副本`)
  assert.equal(name.length, 100)
})

test('空或无效复制映射返回空对象，不让同 ID 的副本覆盖源组合', () => {
  for (const mapping of [undefined, null, 1, 'g', [], {}, new Map(), { g: 'g' }, { g: null }, { g: '' }, { ' ': 'newG' }, new Map([[{}, 'newG'], ['g', {}]])]) {
    assert.deepEqual(copyDashboardGroupMetadata({ g: { name: '原组合' } }, mapping), {})
  }
})

test('复制特殊键也保持对象原型及源组元数据隔离', () => {
  const groups = JSON.parse('{"__proto__":{"name":"特殊组合","collapsed":false}}')
  const result = copyDashboardGroupMetadata(groups, new Map([['__proto__', 'constructor']]))
  assert.equal(Object.getPrototypeOf(result), Object.prototype)
  assert.ok(Object.hasOwn(result, 'constructor'))
  assert.deepEqual(result.constructor, { name: '特殊组合 副本', collapsed: false })
  result.constructor.name = '改名'
  assert.equal(groups.__proto__.name, '特殊组合')
  const protoResult = copyDashboardGroupMetadata({ g: { name: '源组合' } }, { g: '__proto__' })
  assert.equal(Object.getPrototypeOf(protoResult), Object.prototype)
  assert.ok(Object.hasOwn(protoResult, '__proto__'))
  assert.deepEqual(protoResult.__proto__, { name: '源组合 副本', collapsed: true })
})
