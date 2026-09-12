#!/usr/bin/env node
// 本地业务演示：管理 API 配置接入方，实际推送和回执查询由 curl 完成。
import assert from 'node:assert/strict'
import { createHash, createHmac, randomBytes } from 'node:crypto'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { spawnSync } from 'node:child_process'
import { setTimeout as delay } from 'node:timers/promises'
import { connectDashboard, root } from './lib/dashboard-local-api.mjs'

const args = process.argv.slice(2)
if (args.includes('--help')) {
  console.log('新建演示并推送：node scripts/simulate-inbound-push.mjs\n重发原始第一批：node scripts/simulate-inbound-push.mjs --repeat .local/push-demo/<运行编号>\n仅允许本地 API；管理登录复用 dashboard-local-api.mjs。密钥仅存在内存中。')
  process.exit(0)
}
if (args.length && !(args.length === 2 && args[0] === '--repeat')) {
  throw new Error('参数不支持，请使用 --help')
}

const titles = {
  pointCode: '测点编码', pointName: '测点名称', area: '施工区域',
  pm25: 'PM2.5（微克/立方米）', pm10: 'PM10（微克/立方米）', noise: '噪声（分贝）',
  temperature: '温度（摄氏度）', humidity: '相对湿度（%）',
  monitorStatus: '监测状态', alarmReason: '模拟异常原因', dataLabel: '数据标识',
}
const numericFields = new Set(['pm25', 'pm10', 'noise', 'temperature', 'humidity'])
const runId = new Date().toISOString().replace(/\D/g, '').slice(0, 14) + '-' + randomBytes(3).toString('hex')
const directory = args.length ? resolve(root, args[1]) : resolve(root, '.local/push-demo', runId)
mkdirSync(directory, { recursive: true, mode: 0o700 })
const save = (name, value) => writeFileSync(resolve(directory, name), JSON.stringify(value, null, 2) + '\n', { mode: 0o600 })
// 当前 RuoYi 请求包装器会去掉物理换行；发送紧凑 JSON，保证签名与接收字节一致。
const savePayload = (name, value) => writeFileSync(resolve(directory, name), JSON.stringify(value), { mode: 0o600 })
const { request, base } = await connectDashboard()
const manage = async (path, method = 'GET', data) => (await request(path, method, data)).data
let manifest
let secret

function signedCurl(method, path, filename = null, idempotency = '', invalidSignature = false) {
  const body = filename ? readFileSync(resolve(directory, filename)) : Buffer.alloc(0)
  const timestamp = String(Math.floor(Date.now() / 1000))
  const nonce = randomBytes(18).toString('hex')
  const digest = createHash('sha256').update(body).digest('hex')
  const canonical = [method, path, manifest.integrationCode, manifest.keyId, timestamp, nonce,
    idempotency, '1.0', digest].join('\n')
  const signature = invalidSignature ? 'invalid' : createHmac('sha256', secret).update(canonical).digest('base64')
  const headers = {
    'Content-Type': 'application/json', 'X-Integration-Id': manifest.integrationCode,
    'X-Key-Id': manifest.keyId, 'X-App-Key': manifest.identityValue,
    'X-Timestamp': timestamp, 'X-Nonce': nonce, 'X-Schema-Version': '1.0',
    'X-Signature': signature, ...(idempotency ? { 'Idempotency-Key': idempotency } : {}),
  }
  // curl 配置通过标准输入传入；凭证、签名不出现在命令行参数或证据文件中。
  const config = Object.entries(headers).map(([key, value]) => `header = "${key}: ${value}"`).join('\n')
  const command = ['--disable', '--silent', '--show-error', '--noproxy', '*', '--connect-timeout', '5',
    '--max-time', '20', '--config', '-', '--request', method, '--url', base + path,
    '--write-out', '\n%{http_code}', ...(filename ? ['--data-binary', '@' + resolve(directory, filename)] : [])]
  const result = spawnSync('curl', command, { input: config + '\n', encoding: 'utf8', maxBuffer: 2 * 1024 * 1024 })
  if (result.error || result.status !== 0) throw new Error('curl 执行失败；检查本地服务及 curl 安装')
  const split = result.stdout.lastIndexOf('\n')
  return { httpStatus: Number(result.stdout.slice(split + 1)), body: JSON.parse(result.stdout.slice(0, split)) }
}

async function waitAccepted(messageId) {
  const path = `/open-api/v1/integrations/${manifest.integrationCode}/messages/${encodeURIComponent(messageId)}`
  for (let attempt = 0; attempt < 30; attempt++) {
    const result = signedCurl('GET', path)
    assert.equal(result.httpStatus, 200, '签名回执查询失败')
    if (result.body.status === 'ACCEPTED') return result
    if (!['RECEIVED', 'VALIDATED', 'PROCESSING'].includes(result.body.status)) {
      throw new Error('批次未成功处理：' + result.body.status)
    }
    await delay(500)
  }
  throw new Error('回执尚未处理完成；演示资源及已有证据已保留')
}

async function verifyDataset() {
  const result = await manage(`/dashboard/dataset/${manifest.datasetId}/test`, 'POST', {})
  assert.equal(result.quality, 'SUCCESS', '数据集查询未成功')
  assert.equal(result.rows.length, 6, '标准记录应为两批共 6 条')
  const rows = new Map(result.rows.map(row => [row.businessKey, row]))
  for (const filename of ['batch-1.json', 'batch-2.json']) {
    for (const item of JSON.parse(readFileSync(resolve(directory, filename), 'utf8')).items) {
      const row = rows.get(item.eventId)
      assert.ok(row, '缺少测点采样记录')
      assert.equal(row.projectCode, manifest.projectCode)
      for (const [field, value] of Object.entries(item.data)) {
        assert.equal(numericFields.has(field) ? Number(row[field]) : row[field], value, `字段 ${field} 不一致`)
      }
    }
  }
  return result
}

async function main() {
  if (args.length) {
    manifest = JSON.parse(readFileSync(resolve(directory, 'manifest.json'), 'utf8'))
    assert.equal(manifest.base, base, '重发必须使用原本地服务')
    const key = await manage(`/dashboard/integration/key/${manifest.integrationKeyId}/configuration`)
    secret = key.secret
    assert.ok(secret, '接入密钥不可用')
    const original = JSON.parse(readFileSync(resolve(directory, 'batch-1-receipt.json'), 'utf8'))
    const replay = signedCurl('POST', manifest.pushPath, 'batch-1.json', manifest.firstIdempotencyKey)
    assert.ok([200, 202].includes(replay.httpStatus), '重复推送未被接受')
    assert.equal(replay.body.requestId, original.body.requestId, '重复推送应返回原回执')
    const dataset = await verifyDataset()
    save('repeat-verification.json', { checkedAt: new Date().toISOString(), replay, rowCount: dataset.rows.length })
    console.log(JSON.stringify({ result: '原批次重发通过，记录未增加', httpStatus: replay.httpStatus,
      requestId: replay.body.requestId, rowCount: dataset.rows.length }, null, 2))
    return
  }

  manifest = { runId, base, scenario: '施工现场环境监测设备主动推送（模拟数据）',
    integrationCode: `demo-env-${runId}`, projectCode: 'demo-construction-site',
    datasetCode: `demo-env-data-${runId}`, keyId: `demo-key-${runId}`, identityValue: 'demo-environment-gateway' }
  manifest.pushPath = `/open-api/v1/integrations/${manifest.integrationCode}/events`
  manifest.firstIdempotencyKey = `demo-${runId}-batch-1`
  secret = randomBytes(32).toString('base64url')
  const integration = await manage('/dashboard/integration', 'POST', {
    integrationCode: manifest.integrationCode, integrationName: `施工现场环境监测推送演示 ${runId}`,
    environment: 'TEST', profile: 'EVENT', schemaVersion: '1.0', endpointCode: 'events',
    projectScope: [manifest.projectCode], networkProfile: 'PRIVATE_LINK', allowedIps: ['127.0.0.1/32'],
    timezone: 'Asia/Shanghai', maxBatchItems: 100, maxBodyBytes: 65536, rateLimit: 60,
    concurrencyLimit: 3, rawRetentionDays: 7, status: 'DRAFT', remark: '命令行演示，全部为模拟数据。',
    configJson: JSON.stringify({ timestampUnit: 'SECONDS', clockSkewSeconds: 300,
      nonceRequired: true, idempotencyRequired: true, requireSchemaHeader: true,
      processingMode: 'ASYNC', itemErrorMode: 'PARTIAL', maxAttempts: 2, deadLetterAfter: 3,
      canonicalProfile: 'METHOD_PATH_INTEGRATION_KEY_TIMESTAMP_NONCE_IDEMPOTENCY_SCHEMA_BODY_V1',
      signatureEncoding: 'BASE64', unknownFieldMode: 'REJECT', messageIdRequired: true, businessTimeRequired: true }),
  })
  manifest.integrationId = integration.integrationId
  save('manifest.json', manifest)
  const key = await manage(`/dashboard/integration/${manifest.integrationId}/keys`, 'POST', {
    keyId: manifest.keyId, provider: 'HMAC_V1', identityValue: manifest.identityValue, secret,
    remark: '本地演示密钥，明文仅在内存中使用',
  })
  manifest.integrationKeyId = key.integrationKeyId
  save('manifest.json', manifest)
  await manage(`/dashboard/integration/${manifest.integrationId}/status`, 'POST', { status: 'ACTIVE' })
  console.log(`测试接入方已创建：${manifest.integrationCode}`)

  const points = [
    { pointCode: 'ENV-001', pointName: '土方施工区监测点', area: '土方施工区' },
    { pointCode: 'ENV-002', pointName: '钢结构加工区监测点', area: '钢结构加工区' },
    { pointCode: 'ENV-003', pointName: '项目办公区监测点', area: '项目办公区' },
  ]
  const readings = [[[28, 62, 58], [31, 70, 64], [19, 42, 49]], [[86, 186, 68], [35, 76, 78], [22, 48, 51]]]
  const now = Date.now()
  const batches = []
  for (let batch = 0; batch < 2; batch++) {
    const envelope = { messageId: `demo-${runId}-batch-${batch + 1}`, schemaVersion: '1.0',
      projectCode: manifest.projectCode, items: points.map((point, index) => {
        const [pm25, pm10, noise] = readings[batch][index]
        // 仅用于演示的阈值，不代表现场法规标准，也不调用现场联动设备。
        const alarmReason = pm10 > 150 ? '模拟扬尘偏高' : noise > 75 ? '模拟噪声偏高' : '无'
        return { eventId: `sample-${runId}-${batch + 1}-${point.pointCode}`,
          occurredAt: new Date(now - (1 - batch) * 300000).toISOString(),
          data: { ...point, pm25, pm10, noise, temperature: 28.5 + index, humidity: 56 + index,
            monitorStatus: alarmReason === '无' ? '正常' : '模拟异常', alarmReason, dataLabel: '模拟数据' } }
      }) }
    const filename = `batch-${batch + 1}.json`
    savePayload(filename, envelope)
    const receipt = signedCurl('POST', manifest.pushPath, filename, envelope.messageId)
    save(`batch-${batch + 1}-receipt.json`, receipt)
    assert.ok([200, 202].includes(receipt.httpStatus), `第 ${batch + 1} 批推送失败：${receipt.body.code}`)
    const final = await waitAccepted(envelope.messageId)
    assert.equal(final.body.accepted, 3)
    assert.equal(final.body.processing, 0)
    save(`batch-${batch + 1}-status.json`, final)
    batches.push({ messageId: envelope.messageId, receipt, final })
    console.log(`第 ${batch + 1} 批：curl HTTP ${receipt.httpStatus} → ${final.body.status}，成功入库 ${final.body.accepted} 条`)
  }

  const replay = signedCurl('POST', manifest.pushPath, 'batch-1.json', manifest.firstIdempotencyKey)
  save('duplicate-receipt.json', replay)
  assert.ok([200, 202].includes(replay.httpStatus))
  assert.equal(replay.body.requestId, batches[0].receipt.body.requestId)
  const changed = JSON.parse(readFileSync(resolve(directory, 'batch-1.json'), 'utf8'))
  changed.items[0].data.pm10 = 999
  savePayload('conflict.json', changed)
  const conflict = signedCurl('POST', manifest.pushPath, 'conflict.json', manifest.firstIdempotencyKey)
  save('conflict-receipt.json', conflict)
  assert.equal(conflict.httpStatus, 409)
  assert.equal(conflict.body.code, 'IDEMPOTENCY_CONFLICT')
  const invalid = signedCurl('POST', manifest.pushPath, 'batch-1.json', manifest.firstIdempotencyKey, true)
  save('invalid-signature-receipt.json', invalid)
  assert.equal(invalid.httpStatus, 401)
  assert.equal(invalid.body.code, 'SIGNATURE_INVALID')

  const dataset = await manage(`/dashboard/integration/operations/inbound/${manifest.integrationId}/dataset`, 'POST', {
    datasetCode: manifest.datasetCode, datasetName: `施工现场环境监测推送演示 ${runId}`,
    projectCode: manifest.projectCode, realtime: false,
    fields: Object.keys(titles).map(name => ({ name, sourcePath: name, type: numericFields.has(name) ? 'number' : 'string' })),
  })
  manifest.datasetId = dataset.datasetId
  save('manifest.json', manifest)
  const fieldSchema = JSON.parse(dataset.fieldSchemaJson).map(field => ({ ...field, title: titles[field.name] || field.title }))
  await manage('/dashboard/dataset', 'PUT', { datasetId: manifest.datasetId, fieldSchemaJson: JSON.stringify(fieldSchema) })
  const checked = await verifyDataset()
  save('dataset-preview.json', checked)
  await manage('/dashboard/dataset', 'PUT', { datasetId: manifest.datasetId, status: 'ACTIVE' })
  const ledger = await manage(`/dashboard/integration/batches?integrationId=${manifest.integrationId}&limit=100`)
  save('ledger.json', ledger)
  assert.equal(ledger.length, 2, '重复/无效推送不应新增批次')
  const summary = { result: '通过', ...manifest, acceptedBatches: 2, acceptedRecords: 6,
    duplicate: { httpStatus: replay.httpStatus, originalReceiptReturned: true },
    conflict: { httpStatus: conflict.httpStatus, code: conflict.body.code },
    invalidSignature: { httpStatus: invalid.httpStatus, code: invalid.body.code },
    datasetQuality: checked.quality, datasetStatus: 'ACTIVE', evidenceDirectory: directory,
    managementUrl: 'http://127.0.0.1:5173/dashboard/integration', finishedAt: new Date().toISOString() }
  save('summary.json', summary)
  console.log(JSON.stringify(summary, null, 2))
}

main().catch(error => {
  console.error('演示未完成：' + error.message)
  console.error('已有演示资源与证据保留在：' + directory)
  process.exitCode = 1
})
