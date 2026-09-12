#!/usr/bin/env python3
"""仅在 test-dashboard-retention.sh 创建的临时本地库上做真实 API/清理回归。"""
import json
import os
import re
import secrets
import subprocess
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DB = os.environ['RETENTION_TEST_DATABASE']
assert re.fullmatch(r'cockpit_retention_test_[0-9_]+', DB)
assert os.environ.get('MYSQL_HOST', '127.0.0.1') in ('localhost', '127.0.0.1')
BASE = 'http://127.0.0.1:' + os.environ.get('RETENTION_TEST_PORT', '18088')
TOKEN = ''

def sql(statement):
    env = dict(os.environ, MYSQL_PWD=os.environ['MYSQL_PASSWORD'])
    result = subprocess.run(['mysql', '--protocol=TCP', '-h'+os.environ.get('MYSQL_HOST', '127.0.0.1'),
        '-P'+os.environ.get('MYSQL_PORT', '3306'), '-u'+os.environ.get('MYSQL_USERNAME', 'root'),
        '-NBe', statement, DB], env=env, text=True, capture_output=True)
    if result.returncode:
        raise AssertionError('临时库 SQL 失败: ' + result.stderr[:300])
    return result.stdout.strip()

def request(method, path, body=None, secret=None):
    raw = None if body is None else json.dumps(body, ensure_ascii=False, separators=(',', ':')).encode()
    headers = {'Content-Type': 'application/json'}
    if TOKEN: headers['Authorization'] = 'Bearer ' + TOKEN
    if secret: headers.update({'Authorization': 'Bearer ' + secret, 'X-Key-Id': 'retention-key'})
    req = urllib.request.Request(BASE + path, data=raw, method=method, headers=headers)
    try: response = urllib.request.urlopen(req, timeout=20)
    except urllib.error.HTTPError as error: response = error
    return response.status, json.loads(response.read())

def manage(method, path, body=None):
    status, result = request(method, path, body)
    assert status == 200 and result.get('code') == 200, (path, result.get('msg'))
    return result.get('data', result)

def wait_until(check, seconds=90):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if check(): return
        time.sleep(1)
    raise AssertionError('等待接收/保留期维护超时')

defaults = (ROOT / 'scripts/test-local-stack.sh').read_text()
username = 'retention-admin'
password = os.environ.get('TEST_ADMIN_PASSWORD') or re.search(r'TEST_ADMIN_PASSWORD:-([^}]+)', defaults)[1]
# 只创建临时库中的合成管理员，不复制真实用户；明文仅经过进程内存/标准输入。
hashed = subprocess.run(['htpasswd', '-niB', '-C', '10', username], input=password+'\n',
    text=True, capture_output=True, check=True).stdout.strip().split(':', 1)[1]
assert re.fullmatch(r'\$2[aby]\$10\$[A-Za-z0-9./]{53}', hashed)
sql("INSERT INTO sys_user(user_id,user_name,nick_name,password,status,del_flag,create_by,create_time) VALUES(1,'retention-admin','保留期测试管理员','"+hashed+"','0','0','retention-test',NOW())")
status, login = request('POST', '/login', {'username': username, 'password': password, 'code': '', 'uuid': ''})
assert status == 200 and login.get('code') == 200, '临时系统登录失败'
TOKEN = login['token']
suffix = str(int(time.time()))

def create(code, policy, raw_days=1):
    item = manage('POST', '/dashboard/integration', {
        'integrationCode': code, 'integrationName': '保留期隔离回归 ' + code,
        'profile': 'EVENT', 'environment': 'TEST', 'schemaVersion': '1.0', 'endpointCode': 'events',
        'networkProfile': 'PRIVATE_LINK', 'timezone': 'Asia/Shanghai', 'allowedIps': ['127.0.0.1/32'],
        'status': 'DRAFT', 'maxBatchItems': 100, 'maxBodyBytes': 65536, 'rateLimit': 60,
        'concurrencyLimit': 5, 'rawRetentionDays': raw_days,
        'configJson': json.dumps(dict(policy, allowDerivedIdempotency=True))})
    ident = item['integrationId']
    secret = secrets.token_urlsafe(32)
    manage('POST', f'/dashboard/integration/{ident}/keys',
        {'keyId': 'retention-key', 'provider': 'BEARER', 'secret': secret})
    manage('POST', f'/dashboard/integration/{ident}/status', {'status': 'ACTIVE'})
    return ident, secret

def event(key, value=1):
    return {'eventId': key, 'occurredAt': datetime.now(timezone.utc).isoformat(), 'data': {'value': value}}

def push(code, secret, message, items):
    body = {'messageId': message, 'schemaVersion': '1.0', 'items': items}
    status, response = request('POST', f'/open-api/v1/integrations/{code}/events', body, secret)
    assert status in (200, 202), response.get('code')
    wait_until(lambda: sql("SELECT status FROM dashboard_integration_batch WHERE platform_request_id='" + response['requestId'] + "'") == 'ACCEPTED')
    return body, response, int(sql("SELECT batch_id FROM dashboard_integration_batch WHERE platform_request_id='" + response['requestId'] + "'"))

code = 'retention-' + suffix
ident, secret = create(code, {'successRetentionDays': 1, 'recordRetentionDays': 1, 'retainRawBody': True})
legacy_code = 'retention-legacy-' + suffix
legacy_id, legacy_secret = create(legacy_code, {'retainRawBody': True}, raw_days=0)
expired_items = [event('expired-1'), event('expired-2', 2)]
original, original_receipt, original_id = push(code, secret, 'original', expired_items)
_, _, duplicate_id = push(code, secret, 'duplicate-batch', expired_items)
_, _, dead_id = push(code, secret, 'dead-batch', [event('protected-dead')])
_, _, pending_id = push(code, secret, 'pending-batch', [event('protected-pending')])
_, _, legacy_batch = push(legacy_code, legacy_secret, 'legacy-batch', [event('legacy-record')])
manage('POST', f'/dashboard/integration/{ident}/status', {'status': 'PAUSED'})
assert sql(f'SELECT (raw_body_ciphertext IS NULL) FROM dashboard_integration_batch WHERE batch_id={legacy_batch}') == '1'
assert sql(f'SELECT (payload_ciphertext IS NULL) FROM dashboard_integration_message WHERE batch_id={legacy_batch}') == '1'

# 只给本脚本在隔离库创建的行模拟时间流逝。失败、处理中保持原文用于恢复验证。
sql(f"UPDATE dashboard_integration_batch SET received_at=NOW()-INTERVAL 3 DAY, processed_at=NOW()-INTERVAL 2 DAY, expires_at=NOW()-INTERVAL 1 DAY WHERE integration_id IN ({ident},{legacy_id})")
sql(f"UPDATE dashboard_integration_message SET received_at=NOW()-INTERVAL 3 DAY, processed_at=NOW()-INTERVAL 2 DAY WHERE integration_id IN ({ident},{legacy_id})")
sql(f"UPDATE dashboard_integration_record SET received_at=NOW()-INTERVAL 3 DAY, updated_at=NOW()-INTERVAL 2 DAY WHERE integration_id IN ({ident},{legacy_id})")
sql(f"UPDATE dashboard_integration_message SET status='DEAD_LETTER' WHERE batch_id={dead_id}")
sql(f"UPDATE dashboard_integration_batch SET status='FAILED',accepted_count=0,rejected_count=1 WHERE batch_id={dead_id}")
sql(f"UPDATE dashboard_integration_message SET status='PROCESSING' WHERE batch_id={pending_id}")
sql(f"UPDATE dashboard_integration_batch SET status='PROCESSING',accepted_count=0,processing_count=1 WHERE batch_id={pending_id}")
for index in range(105):
    sql(f"INSERT INTO dashboard_integration_batch(integration_id,endpoint_code,normalized_idempotency_key,message_id,body_hash,platform_request_id,schema_version,status,duplicate_count,response_json,received_at,processed_at) VALUES({ident},'events','bounded-{index}','bounded-{index}',REPEAT('a',64),'retention-bounded-{suffix}-{index}','1.0','ACCEPTED',1,'{{}}',NOW()-INTERVAL 3 DAY,NOW()-INTERVAL 2 DAY)")

print('已建立隔离回归：成功、重复、失败、处理中、旧配置及超过一轮限额的数据。', flush=True)
wait_until(lambda: sql(f'SELECT (details_expired_at IS NOT NULL) FROM dashboard_integration_batch WHERE batch_id={original_id}') == '1')
assert sql(f'SELECT COUNT(*) FROM dashboard_integration_batch WHERE batch_id={duplicate_id}') == '0'
assert sql(f"SELECT COUNT(*) FROM dashboard_integration_record WHERE integration_id={ident} AND business_key IN ('expired-1','expired-2')") == '0'
assert sql(f"SELECT COUNT(*) FROM dashboard_integration_message WHERE batch_id={original_id} AND normalized_json IS NULL AND payload_ciphertext IS NULL AND payload_hash IS NOT NULL") == '2'
remaining = int(sql(f"SELECT COUNT(*) FROM dashboard_integration_batch WHERE integration_id={ident} AND message_id LIKE 'bounded-%'"))
assert 0 < remaining < 105, ('清理未遵守一轮限额', remaining)
for bid in (dead_id, pending_id):
    assert sql(f'SELECT (raw_body_ciphertext IS NOT NULL) FROM dashboard_integration_batch WHERE batch_id={bid}') == '1'
    assert sql(f'SELECT (normalized_json IS NOT NULL AND payload_ciphertext IS NOT NULL) FROM dashboard_integration_message WHERE batch_id={bid}') == '1'
assert sql(f'SELECT COUNT(*) FROM dashboard_integration_record WHERE integration_id={legacy_id}') == '1'
assert sql(f'SELECT (normalized_json IS NOT NULL) FROM dashboard_integration_message WHERE batch_id={legacy_batch}') == '1'
detail = manage('GET', f'/dashboard/integration/batch/{original_id}')
assert detail['detailExpired'] is True and detail['items'] == [] and detail['acceptedCount'] == 2
manage('POST', f'/dashboard/integration/{ident}/status', {'status': 'ACTIVE'})
status, receipt = request('POST', f'/open-api/v1/integrations/{code}/events', original, secret)
assert status == 200 and receipt['requestId'] == original_receipt['requestId']
assert receipt['code'] == 'ACCEPTED' and receipt['accepted'] == 2 and receipt['detailExpired'] is True
changed = json.loads(json.dumps(original)); changed['items'][0]['data']['value'] = 999
status, receipt = request('POST', f'/open-api/v1/integrations/{code}/events', changed, secret)
assert status == 409 and receipt['code'] == 'IDEMPOTENCY_CONFLICT'
_, duplicate_receipt, _ = push(code, secret, 'after-expiry', expired_items)
assert duplicate_receipt['duplicates'] == 2 and duplicate_receipt['accepted'] == 0
assert sql(f"SELECT COUNT(*) FROM dashboard_integration_record WHERE integration_id={ident} AND business_key IN ('expired-1','expired-2')") == '0'
changed['messageId'] = 'business-conflict'
changed['items'] = changed['items'][:1]
status, receipt = request('POST', f'/open-api/v1/integrations/{code}/events', changed, secret)
assert status == 409 and receipt['items'][0]['code'] == 'BUSINESS_KEY_CONFLICT', (status, receipt.get('code'))
dead_message = int(sql(f'SELECT integration_message_id FROM dashboard_integration_message WHERE batch_id={dead_id}'))
manage('POST', f'/dashboard/integration/dead-letters/{dead_message}/replay', {'reason': '隔离回归：保留期后仍可恢复'})
wait_until(lambda: sql(f'SELECT status FROM dashboard_integration_message WHERE integration_message_id={dead_message}') == 'ACCEPTED')
assert int(sql(f'SELECT version FROM dashboard_integration_signal WHERE integration_id={ident}')) > 0
assert int(sql(f"SELECT COUNT(*) FROM dashboard_integration_audit WHERE resource_code='{code}' AND category='INBOUND_RETENTION'")) >= 1
print('已通过：正文清理、去重不复活、原回执/冲突、旧配置保留、失败保护与死信恢复。', flush=True)
wait_until(lambda: sql(f"SELECT COUNT(*) FROM dashboard_integration_batch WHERE integration_id={ident} AND message_id LIKE 'bounded-%'") == '0', 90)
summary = {'success': True, 'integrationCode': code, 'integrationId': ident, 'expiredBatchId': original_id,
           'legacyIntegrationId': legacy_id, 'originalRequestId': original_receipt['requestId'],
           'boundedRemainingAfterFirstPass': remaining, 'originalRecordResurrected': False}
(ROOT / '.local/retention-test/summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2))
print('真实 MySQL 与 API 保留期回归通过；第二轮已清完剩余过期重复批次。', flush=True)
