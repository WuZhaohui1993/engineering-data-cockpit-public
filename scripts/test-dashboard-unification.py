#!/usr/bin/env python3
"""只对保留期脚本创建的隔离库和回环模拟上游执行统一接入回归。"""
import json
import os
import re
import subprocess
import time
import urllib.request
import urllib.error
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DB = os.environ['RETENTION_TEST_DATABASE']
assert re.fullmatch(r'cockpit_retention_test_[0-9_]+', DB)
assert os.environ.get('MYSQL_HOST', '127.0.0.1') in ('127.0.0.1', 'localhost')
BASE = 'http://127.0.0.1:' + os.environ.get('RETENTION_TEST_PORT', '18088')
HTTP_PORT = os.environ.get('INTEGRATION_TEST_HTTP_PORT', '18888')
WS_PORT = os.environ.get('INTEGRATION_TEST_WS_PORT', '18889')
TOKEN = ''
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))

def sql(statement):
    env = dict(os.environ, MYSQL_PWD=os.environ['MYSQL_PASSWORD'])
    result = subprocess.run(['mysql', '--protocol=TCP', '--default-character-set=utf8mb4',
        '-h'+os.environ.get('MYSQL_HOST', '127.0.0.1'), '-P'+os.environ.get('MYSQL_PORT', '3306'),
        '-u'+os.environ.get('MYSQL_USERNAME', 'root'), '-NBe', statement, DB], env=env, text=True, capture_output=True)
    assert result.returncode == 0, '隔离库 SQL 失败: ' + result.stderr[:200]
    return result.stdout.strip()

def request(method, path, body=None, token=None):
    auth = TOKEN if token is None else token
    headers = {'Content-Type': 'application/json'}
    if auth: headers['Authorization'] = 'Bearer ' + auth
    req = urllib.request.Request(BASE+path, headers=headers, method=method,
        data=None if body is None else json.dumps(body).encode())
    try: response = OPENER.open(req, timeout=30)
    except urllib.error.HTTPError as error: response = error
    with response: return json.load(response)

def manage(method, path, body=None, token=None):
    result = request(method, path, body, token)
    assert result.get('code') == 200, (path, result.get('msg'))
    return result.get('data', result)

def wait_until(check, seconds=40):
    deadline = time.monotonic()+seconds
    while time.monotonic()<deadline:
        if check(): return
        time.sleep(0.5)
    raise AssertionError('统一接入回归等待超时')

defaults = (ROOT/'scripts/test-local-stack.sh').read_text()
password = os.environ.get('TEST_ADMIN_PASSWORD') or re.search(r'TEST_ADMIN_PASSWORD:-([^}]+)', defaults)[1]
if sql("SELECT COUNT(*) FROM sys_user WHERE user_id=1") == '0':
    hashed = subprocess.run(['htpasswd', '-niB', '-C', '10', 'retention-admin'], input=password+'\n',
        text=True, capture_output=True, check=True).stdout.strip().split(':', 1)[1]
    assert re.fullmatch(r'\$2[aby]\$10\$[A-Za-z0-9./]{53}', hashed)
    sql("INSERT INTO sys_user(user_id,user_name,nick_name,password,status,del_flag,create_time,pwd_update_date) VALUES(1,'retention-admin','统一接入测试管理员','"+hashed+"','0','0',NOW(),NOW())")
    sql("INSERT IGNORE INTO sys_user_role(user_id,role_id) VALUES(1,1)")
    hashed = ''
TOKEN = manage('POST', '/login', {'username': 'retention-admin', 'password': password, 'code': '', 'uuid': ''}, '')['token']
summary = {}
policy_path = '/dashboard/integration/operations/policy'
policy = manage('GET', policy_path)
assert policy == {'auditRetentionDays': 30, 'resolvedAlertRetentionDays': 30, 'testLogRetentionDays': 30, 'testResponseBodyStored': False}
new_policy = {'auditRetentionDays': 2, 'resolvedAlertRetentionDays': 3, 'testLogRetentionDays': 1}
for invalid in [None, '', '30', 1.5, -1, 0, 3651, True]:
    bad = dict(new_policy, testLogRetentionDays=invalid)
    assert request('PUT', policy_path, bad).get('code') != 200
assert request('PUT', policy_path, dict(new_policy, testResponseBodyStored=True)).get('code') != 200
manage('PUT', policy_path, new_policy)
assert manage('GET', policy_path)['testLogRetentionDays'] == 1

# 最小权限角色：允许监控但不能修改公共策略；台账角色没有接入方列表权限。
for ident, username, permission in [(900002, 'monitor-only', 'dashboard:integration:monitor'), (900003, 'ledger-only', 'dashboard:integration:view')]:
    sql(f"INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag) VALUES({ident},'{username}','{username}',9,'0','0')")
    sql(f"INSERT INTO sys_user(user_id,user_name,nick_name,password,status,del_flag,create_time,pwd_update_date) SELECT {ident},'{username}','统一接入只读验收',password,'0','0',NOW(),NOW() FROM sys_user WHERE user_id=1")
    sql(f"INSERT INTO sys_user_role(user_id,role_id) VALUES({ident},{ident})")
    sql(f"INSERT INTO sys_role_menu(role_id,menu_id) SELECT {ident},menu_id FROM sys_menu WHERE menu_id IN(2000,2006) OR perms='{permission}'")
    auth = manage('POST', '/login', {'username': username, 'password': password, 'code': '', 'uuid': ''}, '')['token']
    if username == 'monitor-only':
        assert manage('GET', policy_path, token=auth)['testResponseBodyStored'] is False
        assert request('PUT', policy_path, new_policy, token=auth).get('code') in (401, 403)
        manage('GET', '/dashboard/integration/operations/resources', token=auth)
    else:
        manage('GET', '/dashboard/integration/batches/page', token=auth)
        assert request('GET', '/dashboard/integration/list', token=auth).get('code') in (401, 403)
    manage('POST', '/logout', token=auth)
password = ''
assert sql("SELECT COUNT(*) FROM sys_role_menu rm JOIN sys_menu m ON rm.menu_id=m.menu_id WHERE m.perms='dashboard:integration:policy' AND rm.role_id<>1") == '0'
summary['policyAndPermissionChecks'] = True

# 模拟 HTTP 来源，用带点号的合法编码覆盖测试日志清理路径。
source_code = 'unification.source.v1'
source = manage('POST', '/dashboard/source', {'sourceCode': source_code, 'sourceName': '统一接入HTTP验收', 'sourceType': 'HTTP', 'status': 'ACTIVE',
    'configJson': json.dumps({'baseUrl': 'http://127.0.0.1:'+HTTP_PORT, 'path': '/data', 'method': 'GET', 'networkProfile': 'PRIVATE_LINK', 'allowedHosts': ['127.0.0.1'], 'allowedCidrs': ['127.0.0.1/32'],
        'allowedPorts': [int(HTTP_PORT)], 'authProvider': 'NO_AUTH', 'environment': 'TEST', 'timezone': 'Asia/Shanghai', 'timeoutSeconds': 5, 'rateLimit': 50, 'concurrencyLimit': 10})})
endpoint_code = 'data.v1'
endpoint_policy = {'rowsPath': 'rows', 'cacheSeconds': 0, 'sharedFetchSeconds': 0, 'staleIfErrorSeconds': 0}
ep_path = '/dashboard/integration/endpoints/'+source_code
endpoint = manage('POST', ep_path, {'endpointCode': endpoint_code, 'endpointName': '无正文留存测试', 'path': '/data', 'method': 'GET',
    'requestContentType': 'NONE', 'responseType': 'JSON', 'authProvider': 'NO_AUTH', 'status': 'DRAFT', 'configJson': json.dumps(endpoint_policy)})
eid = endpoint['endpointId']
test_path = ep_path+'/'+endpoint_code+'/test'
assert manage('POST', test_path, {})['quality'] == 'SUCCESS'
manage('PUT', ep_path, {'endpointId': eid, 'status': 'ACTIVE'})
dataset = manage('POST', '/dashboard/dataset', {'datasetCode': 'unification-http-data', 'datasetName': 'HTTP无正文日志验收', 'dataType': 'API', 'status': 'DRAFT',
    'configJson': json.dumps({'sourceCode': source_code, 'endpointCode': endpoint_code}),
    'fieldSchemaJson': json.dumps([{'name': 'city', 'type': 'string'}, {'name': 'temperature', 'type': 'number'}, {'name': 'condition', 'type': 'string'}]),
    'paramSchemaJson': '[]', 'refreshSeconds': 10, 'timeoutSeconds': 5})
did = dataset['datasetId']; dataset_test = f'/dashboard/dataset/{did}/test'
tested = manage('POST', dataset_test, {})
assert tested['quality'] == 'SUCCESS', {k: tested.get(k) for k in ['quality', 'message']}
assert sql(f"SELECT COUNT(*) FROM dashboard_integration_snapshot WHERE source_code='{source_code}'") == '0'
for path in [test_path, dataset_test]:
    wait_until(lambda: int(sql(f"SELECT COUNT(*) FROM sys_oper_log WHERE oper_url='{path}'")) > 0)
    assert sql(f"SELECT COUNT(*) FROM sys_oper_log WHERE oper_url='{path}' AND (COALESCE(json_result,'')<>'' OR COALESCE(oper_param,'')<>'')") == '0'

manage('PUT', ep_path, {'endpointId': eid, 'status': 'DISABLED'})
enabled_cache = dict(endpoint_policy, sharedFetchSeconds=30)
manage('PUT', ep_path, {'endpointId': eid, 'configJson': json.dumps(enabled_cache)})
assert manage('POST', test_path, {})['quality'] == 'SUCCESS'
manage('PUT', ep_path, {'endpointId': eid, 'status': 'ACTIVE'})
preview_path = '/dashboard/dataset/unification-http-data/preview'
for _ in range(2): assert manage('POST', preview_path, {})['quality'] == 'SUCCESS'
assert sql(f"SELECT COUNT(*) FROM dashboard_integration_snapshot WHERE source_code='{source_code}'") == '1'
assert sql(f"SELECT COUNT(*) FROM dashboard_integration_snapshot WHERE source_code='{source_code}' AND payload_ciphertext LIKE '%TEST_BODY_MUST_NOT_BE_LOGGED%'") == '0'
summary['httpCacheAndBodyLogChecks'] = True

# 真实 WebSocket 握手、消息和受控断线，核对只发布运行元数据。
ws_code = 'unification-ws'
manage('POST', '/dashboard/source', {'sourceCode': ws_code, 'sourceName': '统一实时连接验收', 'sourceType': 'WEBSOCKET', 'status': 'ACTIVE',
    'configJson': json.dumps({'baseUrl': 'ws://127.0.0.1:'+WS_PORT, 'path': '/stream'})})
ws_dataset = manage('POST', '/dashboard/dataset', {'datasetCode': 'unification-ws-data', 'datasetName': 'WS运维验收', 'dataType': 'WEBSOCKET', 'status': 'DRAFT',
    'configJson': json.dumps({'endpointCode': ws_code, 'sourceType': 'WEBSOCKET', 'message': {'rowsPath': 'rows'}, 'connection': {'heartbeatSeconds': 1, 'reconnectLimit': 5}}),
    'fieldSchemaJson': '[{"name":"value","type":"number"},{"name":"note","type":"string"}]', 'paramSchemaJson': '[]', 'refreshSeconds': 5, 'timeoutSeconds': 5})
tested_ws = manage('POST', f"/dashboard/dataset/{ws_dataset['datasetId']}/test", {})
assert tested_ws['quality'] == 'SUCCESS', {k: tested_ws.get(k) for k in ['quality', 'message']}
status_path = '/dashboard/integration/operations/websockets?resourceCode='+ws_code
wait_until(lambda: manage('GET', status_path).get('messageCount', 0)>0)
ws_status = manage('GET', status_path)
assert ws_status['scope'] == 'CURRENT_INSTANCE' and ws_status['connectionCount'] == 1
encoded = json.dumps(ws_status)
for forbidden in ['WS_BODY_MUST_NOT_BE_LOGGED', 'ws://', 'authorization', 'subscription', 'payload']:
    assert forbidden not in encoded
with OPENER.open('http://127.0.0.1:'+HTTP_PORT+'/disconnect', timeout=5) as response: response.read()
wait_until(lambda: any(row.get('reconnectCount', 0)>0 for row in manage('GET', status_path).get('connections', [])))
wait_until(lambda: manage('GET', status_path).get('connectedCount', 0)==1)
wait_until(lambda: int(sql("SELECT COUNT(*) FROM dashboard_integration_audit WHERE category='WEBSOCKET'"))>0)
assert sql(f"SELECT COUNT(*) FROM dashboard_integration_snapshot WHERE source_code='{ws_code}'") == '0'
assert request('GET', status_path, token='').get('code') in (401, 403)
summary['websocketConnectionAndReconnect'] = True

# 审计、告警、指标都精确区分方向和来源。
for category, code in [('OUTBOUND', source_code), ('WEBSOCKET', ws_code)]:
    suffix = '?category='+category+'&resourceCode='+code
    data = manage('GET', '/dashboard/integration/operations/audits'+suffix)
    assert data['rows'] and all(row['category']==category for row in data['rows'])
    assert all(row['resourceCode']==code or row['resourceCode'].startswith(code+'/') for row in data['rows'])
    manage('GET', '/dashboard/integration/operations/metrics'+suffix)
    manage('GET', '/dashboard/integration/operations/alerts'+suffix)
options = manage('GET', '/dashboard/integration/operations/resources')
assert any(row['category']=='WEBSOCKET' and row['resourceCode']==ws_code for row in options)
assert request('GET', '/dashboard/integration/operations/metrics?category=INVALID').get('code') != 200
summary['scopedMonitoring'] = True

# 公共清理只删除到期审计、已确认告警和指定测试日志，保留未确认告警与其他业务日志。
sql("INSERT INTO dashboard_integration_audit(category,resource_code,outcome,created_at) VALUES('OUTBOUND','cleanup-old','SUCCESS',NOW()-INTERVAL 4 DAY),('OUTBOUND','cleanup-recent','SUCCESS',NOW())")
sql("INSERT INTO dashboard_integration_alert(alert_key,category,resource_code,error_code,status,first_at,last_at) VALUES('cleanup-ack','OUTBOUND','cleanup','TEST','ACKNOWLEDGED',NOW()-INTERVAL 4 DAY,NOW()-INTERVAL 4 DAY),('cleanup-open','OUTBOUND','cleanup','TEST','OPEN',NOW()-INTERVAL 4 DAY,NOW()-INTERVAL 4 DAY)")
for tag, path in [('cleanup-dataset', dataset_test), ('cleanup-endpoint', test_path), ('keep-other', '/system/user/1'), ('keep-similar', test_path+'/extra')]:
    sql(f"INSERT INTO sys_oper_log(title,request_method,oper_url,json_result,oper_time) VALUES('{tag}','POST','{path}','old-test-body',NOW()-INTERVAL 2 DAY)")
wait_until(lambda: sql("SELECT COUNT(*) FROM dashboard_integration_audit WHERE resource_code='cleanup-old'") == '0')
wait_until(lambda: sql("SELECT COUNT(*) FROM sys_oper_log WHERE title IN ('cleanup-dataset','cleanup-endpoint')") == '0')
assert sql("SELECT COUNT(*) FROM dashboard_integration_alert WHERE alert_key='cleanup-ack'") == '0'
assert sql("SELECT COUNT(*) FROM dashboard_integration_alert WHERE alert_key='cleanup-open'") == '1'
assert sql("SELECT COUNT(*) FROM sys_oper_log WHERE title IN ('keep-other','keep-similar')") == '2'
assert sql("SELECT COUNT(*) FROM dashboard_integration_audit WHERE resource_code='cleanup-recent'") == '1'
summary['boundedCleanupScope'] = True
manage('PUT', policy_path, {'auditRetentionDays': 30, 'resolvedAlertRetentionDays': 30, 'testLogRetentionDays': 30})
summary.update(success=True, httpSourceCode=source_code, websocketSourceCode=ws_code, datasetId=did,
    websocketDatasetId=ws_dataset['datasetId'], monitorUsername='monitor-only', ledgerUsername='ledger-only')
(ROOT/'.local/retention-test/unification-summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2))
manage('POST', '/logout')
print('统一接入真实回归通过：权限、HTTP缓存、无正文日志、WS消息/重连、方向来源筛选及保留期清理。', flush=True)
