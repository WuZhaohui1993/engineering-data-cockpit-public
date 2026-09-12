#!/usr/bin/env node
// 向已手动配置的本地接入方推送；不创建接入方、密钥或数据集，不修改其配置与状态。
import assert from 'node:assert/strict'
import { createHash, createHmac, randomBytes } from 'node:crypto'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { spawnSync } from 'node:child_process'
import { setTimeout as delay } from 'node:timers/promises'
import { connectDashboard, root } from './lib/dashboard-local-api.mjs'

const args = process.argv.slice(2)
if (args.length !== 3 || args.some(value => !/^[A-Za-z0-9._-]{1,64}$/.test(value))) {
  console.error('用法：node scripts/push-configured-integration.mjs <接入方编码> <数据集编码> <项目编码>')
  process.exit(1)
}
const [integrationCode, datasetCode, projectCode] = args
const runId = new Date().toISOString().replace(/\D/g, '').slice(0, 14) + '-' + randomBytes(3).toString('hex')
const directory = resolve(root, '.local/push-demo', 'manual-' + runId)
mkdirSync(directory, { recursive: true, mode: 0o700 })
const save = (name, value) => writeFileSync(resolve(directory, name), JSON.stringify(value, null, 2) + '\n', { mode: 0o600 })

async function main() {
  const { request, base } = await connectDashboard()
  const manage = async (path, method = 'GET', data) => (await request(path, method, data)).data
  const integrations = await manage('/dashboard/integration/list?keyword=' + encodeURIComponent(integrationCode))
  const integration = integrations.find(row => row.integrationCode === integrationCode)
  assert.ok(integration, '指定接入方不存在')
  const detail = await manage('/dashboard/integration/' + integration.integrationId)
  assert.equal(detail.status, 'ACTIVE', '接入方尚未启用')
  assert.ok(['TEST', 'SANDBOX'].includes(detail.environment), '此模拟脚本仅用于测试或沙箱接入方')
  assert.equal(detail.profile, 'EVENT', '此环境监测演示需要 EVENT 模式')
  assert.ok(!detail.projectScope.length || detail.projectScope.includes(projectCode), '项目不在接入范围')
  const config = JSON.parse(detail.configJson)
  assert.equal(config.timestampUnit, 'SECONDS')
  assert.equal(config.canonicalProfile, 'METHOD_PATH_INTEGRATION_KEY_TIMESTAMP_NONCE_IDEMPOTENCY_SCHEMA_BODY_V1')
  assert.equal(config.signatureEncoding, 'BASE64')
  const datasets = await request('/dashboard/dataset/list?pageSize=100&keyword=' + encodeURIComponent(datasetCode))
  const dataset = datasets.rows.find(row => row.datasetCode === datasetCode)
  assert.ok(dataset, '指定数据集不存在')
  assert.equal(JSON.parse(dataset.configJson).integrationCode, integrationCode, '数据集关联的接入方不一致')
  const baseline = await manage(`/dashboard/dataset/${dataset.datasetId}/test`, 'POST', {})
  assert.ok(['SUCCESS', 'NO_DATA'].includes(baseline.quality), '数据集配置尚不可查询')
  const keys = (await manage(`/dashboard/integration/${integration.integrationId}/keys`))
    .filter(key => key.status === 'ACTIVE' && key.provider === 'HMAC_V1')
  assert.equal(keys.length, 1, '需要唯一启用的 HMAC 密钥，避免误选')
  const key = await manage(`/dashboard/integration/key/${keys[0].integrationKeyId}/configuration`)
  assert.ok(key.secret, '授权接口未返回可用密钥')
  const schema = detail.schemaVersion
  const pushPath = `/open-api/v1/integrations/${integrationCode}/${detail.endpointCode}`
  const configuration = { integrationId: integration.integrationId, integrationCode,
    integrationName: detail.integrationName, environment: detail.environment, profile: detail.profile,
    endpointCode: detail.endpointCode, schemaVersion: schema, projectScope: detail.projectScope,
    allowedIps: detail.allowedIps, maxBatchItems: detail.maxBatchItems, maxBodyBytes: detail.maxBodyBytes,
    rateLimit: detail.rateLimit, concurrencyLimit: detail.concurrencyLimit, rawRetentionDays: detail.rawRetentionDays,
    timezone: detail.timezone, integrationStatus: detail.status, policy: config,
    keyId: key.keyId, identityValue: key.identityValue, datasetId: dataset.datasetId, datasetCode,
    datasetName: dataset.datasetName, datasetStatus: dataset.status, projectCode, base, pushPath, runId }
  save('configuration.json', configuration)

  function curl(method, path, payloadFile, idempotency = '') {
    const body = payloadFile ? readFileSync(resolve(directory, payloadFile)) : Buffer.alloc(0)
    const timestamp = String(Math.floor(Date.now() / 1000)), nonce = randomBytes(18).toString('hex')
    const digest = createHash('sha256').update(body).digest('hex')
    const canonical = [method, path, integrationCode, key.keyId, timestamp, nonce, idempotency, schema, digest].join('\n')
    const headers = { 'Content-Type': 'application/json', 'X-Key-Id': key.keyId,
      'X-App-Key': key.identityValue, 'X-Integration-Id': integrationCode, 'X-Timestamp': timestamp,
      'X-Nonce': nonce, 'X-Schema-Version': schema,
      'X-Signature': createHmac('sha256', key.secret).update(canonical).digest('base64'),
      ...(idempotency ? { 'Idempotency-Key': idempotency } : {}) }
    const curlConfig = Object.entries(headers).map(([name, value]) => `header = "${name}: ${value}"`).join('\n') + '\n'
    const result = spawnSync('curl', ['--disable', '--silent', '--show-error', '--noproxy', '*',
      '--connect-timeout', '5', '--max-time', '20', '--config', '-', '--request', method,
      '--url', base + path, '--write-out', '\n%{http_code}',
      ...(payloadFile ? ['--data-binary', '@' + resolve(directory, payloadFile)] : [])],
    { input: curlConfig, encoding: 'utf8', maxBuffer: 2 * 1024 * 1024 })
    assert.ok(!result.error && result.status === 0, 'curl 请求失败')
    const split = result.stdout.lastIndexOf('\n')
    return { httpStatus: Number(result.stdout.slice(split + 1)), body: JSON.parse(result.stdout.slice(0, split)) }
  }

  const points = [['ENV-001', '土方施工区监测点', '土方施工区'],
    ['ENV-002', '钢结构加工区监测点', '钢结构加工区'], ['ENV-003', '项目办公区监测点', '项目办公区']]
  const readings = [[[28, 62, 58], [31, 70, 64], [19, 42, 49]], [[86, 186, 68], [35, 76, 78], [22, 48, 51]]]
  const expected = [], batches = [], now = Date.now()
  for (let batch = 0; batch < 2; batch++) {
    const messageId = `manual-${runId}-${batch + 1}`
    const items = points.map(([pointCode, pointName, area], index) => {
      const [pm25, pm10, noise] = readings[batch][index]
      const alarmReason = pm10 > 150 ? '模拟扬尘偏高' : noise > 75 ? '模拟噪声偏高' : '无'
      return { eventId: `${messageId}-${pointCode}`, occurredAt: new Date(now - (1 - batch) * 300000).toISOString(),
        data: { pointCode, pointName, area, pm25, pm10, noise, temperature: 28.5 + index, humidity: 56 + index,
          monitorStatus: alarmReason === '无' ? '正常' : '模拟异常', alarmReason, dataLabel: '模拟数据' } }
    })
    expected.push(...items)
    const filename = `batch-${batch + 1}.json`
    // 不添加物理换行，兼容当前 RuoYi 请求包装器的字节处理行为。
    writeFileSync(resolve(directory, filename), JSON.stringify({ messageId, schemaVersion: schema, projectCode, items }), { mode: 0o600 })
    const receipt = curl('POST', pushPath, filename, messageId)
    save(`batch-${batch + 1}-receipt.json`, receipt)
    assert.ok([200, 202].includes(receipt.httpStatus), '推送失败：' + receipt.body.code)
    let final
    for (let attempt = 0; attempt < 30; attempt++) {
      final = curl('GET', `/open-api/v1/integrations/${integrationCode}/messages/${messageId}?endpointCode=${detail.endpointCode}`)
      assert.equal(final.httpStatus, 200, '回执查询失败')
      if (final.body.status === 'ACCEPTED') break
      assert.ok(['RECEIVED', 'VALIDATED', 'PROCESSING'].includes(final.body.status), '异步处理未成功')
      await delay(500)
    }
    save(`batch-${batch + 1}-status.json`, final)
    assert.equal(final.body.status, 'ACCEPTED', '等待处理完成超时')
    assert.equal(final.body.accepted, 3)
    assert.equal(final.body.processing, 0)
    batches.push({ messageId, httpStatus: receipt.httpStatus, requestId: final.body.requestId,
      batchId: final.body.batchId, status: final.body.status, accepted: final.body.accepted })
    console.log(`第 ${batch + 1} 批：HTTP ${receipt.httpStatus} → ${final.body.status}，成功 ${final.body.accepted} 条，批次 ${final.body.batchId}`)
  }
  const duplicate = curl('POST', pushPath, 'batch-1.json', batches[0].messageId)
  save('duplicate-receipt.json', duplicate)
  assert.ok([200, 202].includes(duplicate.httpStatus))
  assert.equal(duplicate.body.requestId, batches[0].requestId, '重复推送未返回原回执')
  const checked = await manage(`/dashboard/dataset/${dataset.datasetId}/test`, 'POST', {})
  save('dataset-preview.json', checked)
  assert.equal(checked.quality, 'SUCCESS')
  assert.equal(checked.rows.length, baseline.rows.length + 6, '返回记录总数不符合预期')
  for (const item of expected) {
    const row = checked.rows.find(record => record.businessKey === item.eventId)
    assert.ok(row, '缺少本次业务记录')
    assert.equal(row.projectCode, projectCode)
    for (const [name, value] of Object.entries(item.data)) {
      assert.equal(typeof value === 'number' ? Number(row[name]) : row[name], value, `字段 ${name} 不一致`)
    }
  }
  const summary = { ...configuration, result: '通过', batches, acceptedRecords: 6,
    datasetRowCount: checked.rows.length, datasetQuality: checked.quality,
    duplicateHttpStatus: duplicate.httpStatus, duplicateOriginalReceipt: true,
    finishedAt: new Date().toISOString(), evidenceDirectory: directory }
  save('summary.json', summary)
  console.log(JSON.stringify({ result: summary.result, integrationCode, keyId: key.keyId,
    datasetCode, datasetId: dataset.datasetId, datasetStatus: dataset.status, datasetQuality: checked.quality,
    rows: checked.rows.length, duplicateHttpStatus: duplicate.httpStatus, evidenceDirectory: directory }, null, 2))
}

main().catch(error => {
  console.error('推送未完成：' + error.message)
  console.error('已保存证据：' + directory)
  process.exitCode = 1
})
