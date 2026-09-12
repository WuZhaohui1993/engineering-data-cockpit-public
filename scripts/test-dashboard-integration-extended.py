#!/usr/bin/env python3
"""真实双后端/数据库/HTTP 回归；数据和故障触发器仅使用本轮随机编号。"""
import sys
sys.dont_write_bytecode = True
import importlib.util
import pathlib
import json
import os
import time
import secrets
import threading
import http.server
import urllib.parse
import urllib.request
import urllib.error
import subprocess
import concurrent.futures
import socket
import select
import signal
import io
import struct
import hashlib
import base64

spec = importlib.util.spec_from_file_location('baseline', pathlib.Path(__file__).with_name('test-dashboard-integration.py'))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
SECOND = f"http://127.0.0.1:{os.environ.get('TEST_SECOND_BACKEND_PORT', '18082')}"
COUNTS = {}
FAILURES = {}
STREAM_CONNECTIONS = 0
STREAM_PINGS = 0
KEYFILE = pathlib.Path(os.environ['TEST_CREDENTIAL_DIRECTORY']) / 'rotation-secret'
SUFFIX = secrets.token_hex(5)
TRIGGER = 'integration_fault_' + SUFFIX


def db(sql):
    env = os.environ.copy()
    env['MYSQL_PWD'] = env['MYSQL_PASSWORD']
    command = ['mysql', '--protocol=TCP', '-h', env.get('MYSQL_HOST', '127.0.0.1'), '-P', env.get('MYSQL_PORT', '3306'), '-u', env.get('MYSQL_USERNAME', 'root'), '-N', '-B', env.get('MYSQL_DATABASE', 'engineering_data_cockpit')]
    result = subprocess.run(command, input=sql, text=True, capture_output=True, env=env)
    if result.returncode:
        raise AssertionError('isolated SQL failed: ' + result.stderr[:400])
    return result.stdout.strip()


def call(method, path, data=None, base=None, auth=True, headers=None):
    raw = data if isinstance(data, bytes) else None if data is None else json.dumps(data, separators=(',', ':'), ensure_ascii=False).encode()
    hdrs = {'Content-Type': 'application/json', **(headers or {})}
    if auth: hdrs['Authorization'] = 'Bearer ' + m.TOKEN
    req = urllib.request.Request((base or m.BACKEND) + path, data=raw, method=method, headers=hdrs)
    try: response = urllib.request.urlopen(req, timeout=40)
    except urllib.error.HTTPError as error: response = error
    body = response.read()
    parsed = json.loads(body) if body else {}
    return response.status, parsed


def manage(method, path, data=None, base=None):
    status, result = call(method, path, data, base)
    assert status == 200 and result.get('code') == 200, (path, status, result.get('msg'))
    return result.get('data', result)


class Handler(m.MockHandler):
    def do_GET(self):
        if self.path == '/stream': return self.stream()
        if not self.authorized(): return self.reject_auth()
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        if path in ['/pages', '/cursor', '/repeat', '/snapshot']:
            COUNTS[path] = COUNTS.get(path, 0) + 1
            status = FAILURES.get(path, 200)
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            if status != 200:
                self.wfile.write(b'{"success":false}')
                return
            query = urllib.parse.parse_qs(parsed.query)
            page = int(query.get('pageNum', ['1'])[0])
            if path == '/cursor': page = int(query.get('cursor', ['1'])[0] or '1')
            if path == '/repeat': page = 1
            begin = (page-1)*2
            rows = [{'id': f'record-{i}', 'name': f'记录{i}', 'value': i} for i in range(begin, min(begin+2, 5))]
            self.wfile.write(json.dumps({'success': True, 'data': {'items': rows, 'total': 5, 'next': str(page+1) if begin+2 < 5 else None}}).encode())
            return
        return super().do_GET()

    def stream(self):
        global STREAM_CONNECTIONS, STREAM_PINGS
        STREAM_CONNECTIONS += 1
        connection_number=STREAM_CONNECTIONS
        key=self.headers.get('Sec-WebSocket-Key','')
        accept=base64.b64encode(hashlib.sha1((key+'258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest()).decode()
        self.protocol_version='HTTP/1.1'
        self.send_response(101);self.send_header('Upgrade','websocket');self.send_header('Connection','Upgrade');self.send_header('Sec-WebSocket-Accept',accept);self.end_headers()
        def frame(opcode,payload):
            assert len(payload)<126
            self.connection.sendall(bytes([0x80|opcode,len(payload)])+payload)
        def read_exact(n):
            data=b''
            while len(data)<n:
                chunk=self.connection.recv(n-len(data))
                if not chunk:raise EOFError()
                data+=chunk
            return data
        try:
            for number in range(40):
                frame(1,json.dumps({'data':[{'value':connection_number*100+number}]}).encode())
                if connection_number==1 and number==1:return
                if select.select([self.connection],[],[],.2)[0]:
                    header=read_exact(2);size=header[1]&127
                    if size==126:size=struct.unpack('>H',read_exact(2))[0]
                    if size==127:raise EOFError()
                    mask=read_exact(4) if header[1]&128 else None
                    payload=read_exact(size)
                    if mask:payload=bytes(value^mask[i%4] for i,value in enumerate(payload))
                    opcode=header[0]&15
                    if opcode==9:STREAM_PINGS+=1;frame(10,payload)
                    if opcode==8:return
        except (OSError,EOFError):pass
        finally:self.close_connection=True


class WsClient:
    """Small RFC6455 client for authenticating the actual local Spring WebSocket endpoint."""
    def __init__(self, url, origin):
        uri = urllib.parse.urlparse(url)
        self.sock = socket.create_connection((uri.hostname, uri.port), timeout=8)
        key = base64.b64encode(secrets.token_bytes(16)).decode()
        target = uri.path + ('?' + uri.query if uri.query else '')
        request = f'GET {target} HTTP/1.1\r\nHost: {uri.hostname}:{uri.port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\nOrigin: {origin}\r\n\r\n'
        self.sock.sendall(request.encode())
        self.file = self.sock.makefile('rb')
        first = self.file.readline()
        assert b'101' in first, ('websocket handshake rejected', first[:100])
        while self.file.readline() not in [b'\r\n', b'']: pass
    def read(self):
        while True:
            first = self.file.read(2)
            if len(first) != 2: raise AssertionError('WebSocket closed')
            opcode = first[0] & 15
            size = first[1] & 127
            if size == 126: size = struct.unpack('>H', self.file.read(2))[0]
            if size == 127: size = struct.unpack('>Q', self.file.read(8))[0]
            assert size <= 2 * 1024 * 1024
            payload = self.file.read(size)
            if opcode == 1: return json.loads(payload)
            if opcode == 8: raise AssertionError('WebSocket server rejected stream: ' + payload[2:].decode('utf-8', errors='replace'))
    def close(self):
        self.file.close(); self.sock.close()


def main():
    server = http.server.ThreadingHTTPServer(('127.0.0.1', m.MOCK_PORT), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    source_id = None; source_code = f'mvp-source-ops-{SUFFIX}'
    endpoint_ids = []; dataset_ids = []; page_ids = []; integration_id = None; websocket_source_id=None; restarted=None; restart_log=None
    KEYFILE.parent.mkdir(parents=True, exist_ok=True)
    KEYFILE.write_text(m.OUTBOUND_SECRET)
    KEYFILE.chmod(0o600)
    try:
        _, login = call('POST', '/login', {'username': os.environ.get('TEST_ADMIN_USERNAME', 'admin'), 'password': os.environ.get('TEST_ADMIN_PASSWORD', 'admin123'), 'code': '', 'uuid': ''}, auth=False)
        m.TOKEN = login['token']
        source = manage('POST', '/dashboard/source', {'sourceCode': source_code, 'sourceName': '接入整合隔离回归', 'sourceType': 'HTTP', 'status': 'ACTIVE', 'configJson': json.dumps({'baseUrl': f'http://127.0.0.1:{m.MOCK_PORT}', 'path': '/', 'method': 'GET', 'networkProfile': 'PRIVATE_LINK', 'allowedHosts': ['127.0.0.1'], 'allowedCidrs': ['127.0.0.1/32'], 'allowedPorts': [m.MOCK_PORT], 'authProvider': 'HMAC_APPKEY_TIMESTAMP_V1', 'identity': m.OUTBOUND_IDENTITY, 'credentialRef': 'FILE:rotation-secret', 'timezone': 'Asia/Shanghai', 'timeoutSeconds': 5, 'concurrencyLimit': 8, 'rateLimit': 1000})})
        source_id = source['dataSourceId']
        def endpoint(code, path, extra):
            config = {'envelopeMode': 'JSON_BOOLEAN', 'successPath': 'success', 'successValue': True, 'rowsPath': 'data.items', 'totalPath': 'data.total', 'maxAttempts': 1, 'canonicalProfile': 'APP_KEY_TIMESTAMP_V1', 'timestampUnit': 'MILLISECONDS', 'nonceRequired': False, 'sharedFetchSeconds': 1, **extra}
            result = manage('POST', f'/dashboard/integration/endpoints/{source_code}', {'endpointCode': code, 'endpointName': '隔离回归' + code, 'path': path, 'method': 'GET', 'requestContentType': 'NONE', 'responseType': 'JSON', 'authProvider': 'INHERIT', 'configJson': json.dumps(config), 'status': 'DRAFT'})
            endpoint_ids.append(result['endpointId'])
            tested = manage('POST', f'/dashboard/integration/endpoints/{source_code}/{code}/test', {})
            assert tested['quality'] in ['SUCCESS', 'TRUNCATED'], (code, tested.get('quality'), tested.get('message'))
            # Truncated endpoints must keep lifecycle gate; test a complete configuration before cap regression.
            if tested['quality'] == 'TRUNCATED':
                return result
            manage('PUT', f'/dashboard/integration/endpoints/{source_code}', {'endpointId': result['endpointId'], 'status': 'ACTIVE'})
            return result
        def dataset(code, endpoint_code, fields, base=None):
            result = manage('POST', '/dashboard/dataset', {'datasetCode': f'mvp-dataset-{code}-{SUFFIX}', 'datasetName': '整合回归' + code, 'dataType': 'API', 'status': 'DRAFT', 'configJson': json.dumps({'sourceCode': source_code, 'endpointCode': endpoint_code, 'rowLimit': 1000}), 'fieldSchemaJson': json.dumps(fields), 'paramSchemaJson': '[]', 'timeoutSeconds': 10, 'refreshSeconds': 30}, base)
            dataset_ids.append(result['datasetId'])
            return result
        def test(dataset, base=None):
            return manage('POST', f"/dashboard/dataset/{dataset['datasetId']}/test", {}, base)
        fields = [{'name': 'id', 'type': 'string'}, {'name': 'value', 'type': 'integer'}]
        page_policy = {'mode': 'PAGE', 'pageParam': 'pageNum', 'sizeParam': 'pageSize', 'pageSize': 2, 'maxPages': 5, 'maxRows': 1000}
        endpoint('paged', '/pages', {'cacheSeconds': 15, 'pagination': page_policy})
        first = dataset('first', 'paged', fields)
        second = dataset('second', 'paged', [{'name': 'name', 'type': 'string'}])
        before = COUNTS.get('/pages', 0)
        data1 = test(first)
        assert data1['quality'] == 'SUCCESS' and len(data1['rows']) == 5 and data1['pageCount'] == 3, data1.get('quality')
        data2 = test(second, SECOND)
        assert len(data2['rows']) == 5 and list(data2['rows'][0]) == ['name']
        assert COUNTS['/pages'] - before == 3, 'projections fetched duplicate upstream pages'
        assert data1['requestId'] == data2['requestId'] and data1['fetchedAt'] == data2['fetchedAt']
        print('分页与跨数据集、跨实例共享抓取：通过')
        endpoint('cursor', '/cursor', {'pagination': {**page_policy, 'mode': 'CURSOR', 'pageParam': 'cursor', 'nextCursorPath': 'data.next'}})
        cur = test(dataset('cursor', 'cursor', fields))
        assert len(cur['rows']) == 5 and cur['quality'] == 'SUCCESS'
        repeated = endpoint('repeated', '/repeat', {'pagination': page_policy})
        assert repeated['status'] == 'DRAFT'
        print('游标分页和重复页面终止门禁：通过')
        endpoint('snapshot', '/snapshot', {'cacheSeconds': 1, 'staleIfErrorSeconds': 60})
        snap = dataset('snapshot', 'snapshot', fields)
        normal = test(snap)
        time.sleep(1.2)
        FAILURES['/snapshot'] = 503
        stale = test(snap, SECOND)
        assert stale['quality'] == 'STALE' and stale['stale'] and stale['fetchedAt'] == normal['fetchedAt'], stale.get('quality')
        FAILURES['/snapshot'] = 401
        denied = test(snap)
        assert denied['quality'] == 'AUTH_ERROR' and not denied.get('rows'), denied.get('quality')
        FAILURES.pop('/snapshot')
        m.OUTBOUND_SECRET = secrets.token_urlsafe(32)
        temp = KEYFILE.with_suffix('.next'); temp.write_text(m.OUTBOUND_SECRET); temp.chmod(0o600); temp.replace(KEYFILE)
        rotated = test(snap, SECOND)
        assert rotated['quality'] == 'SUCCESS', rotated.get('quality')
        print('持久快照、故障过期标识、鉴权失败不回退、外部凭证轮换：通过')

        code = f'mvp-in-ops-{SUFFIX}'; key_id = 'key-' + SUFFIX; secret = secrets.token_urlsafe(32)
        integration = manage('POST', '/dashboard/integration', {'integrationCode': code, 'integrationName': '整合双实例推送', 'environment': 'TEST', 'profile': 'EVENT', 'schemaVersion': '1.0', 'endpointCode': 'events', 'networkProfile': 'PUBLIC_HTTPS', 'timezone': 'Asia/Shanghai', 'maxBatchItems': 20, 'maxBodyBytes': 65536, 'rateLimit': 1000, 'concurrencyLimit': 10, 'rawRetentionDays': 1, 'projectScope': ['demo'], 'allowedIps': ['127.0.0.1/32'], 'status': 'DRAFT', 'configJson': json.dumps({'timestampUnit': 'SECONDS', 'clockSkewSeconds': 300, 'nonceRequired': True, 'idempotencyRequired': True, 'processingMode': 'ASYNC', 'itemErrorMode': 'PARTIAL', 'maxAttempts': 1, 'deadLetterAfter': 1, 'canonicalProfile': 'METHOD_PATH_INTEGRATION_KEY_TIMESTAMP_NONCE_IDEMPOTENCY_SCHEMA_BODY_V1', 'signatureEncoding': 'BASE64', 'unknownFieldMode': 'REJECT', 'messageIdRequired': True, 'businessTimeRequired': True})})
        integration_id = integration['integrationId']
        manage('POST', f'/dashboard/integration/{integration_id}/keys', {'keyId': key_id, 'provider': 'HMAC_V1', 'identityValue': 'integration-test-app', 'secret': secret})
        manage('POST', f'/dashboard/integration/{integration_id}/status', {'status': 'ACTIVE'})
        path = f'/open-api/v1/integrations/{code}/events'
        def deliver(number, base):
            envelope = {'messageId': f'batch-{number}-{SUFFIX}', 'schemaVersion': '1.0', 'projectCode': 'demo', 'items': [{'eventId': f'event-{number}-{SUFFIX}', 'occurredAt': '2026-09-09 08:00:00', 'data': {'value': number, 'name': '测试'}}]}
            body = json.dumps(envelope, separators=(',', ':')).encode()
            headers = m.signed_headers(secret, code, key_id, path, body, f'idem-{number}-{SUFFIX}')
            return call('POST', path, body, base, False, headers)
        def wait_count(sql, expected, seconds=12):
            end = time.monotonic() + seconds
            while time.monotonic() < end:
                if db(sql) == str(expected): return
                time.sleep(.2)
            raise AssertionError('persistent state did not converge: ' + db(sql))
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            results = list(pool.map(lambda i: deliver(i, m.BACKEND if i % 2 else SECOND), range(8)))
        assert all(status in [200, 202] for status, _ in results), [(status, result.get('errorCode')) for status, result in results]
        wait_count(f'select count(*) from dashboard_integration_record where integration_id={integration_id}', 8)
        assert int(db(f'select count(*) from dashboard_integration_message where integration_id={integration_id} and status="ACCEPTED"')) == 8
        print('双实例并发接收与标准记录唯一性：通过')
        db(f"DELIMITER $$\nCREATE TRIGGER {TRIGGER} BEFORE INSERT ON dashboard_integration_record FOR EACH ROW BEGIN IF NEW.integration_id={integration_id} THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated injected processing failure'; END IF; END$$\nDELIMITER ;")
        deliver(99, SECOND)
        wait_count(f'select count(*) from dashboard_integration_message where integration_id={integration_id} and status="DEAD_LETTER"', 1)
        alerts = manage('GET', '/dashboard/integration/operations/alerts')
        assert any(item['resourceCode'] == code and item['errorCode'] == 'DEAD_LETTER' for item in alerts['rows'])
        message = int(db(f'select integration_message_id from dashboard_integration_message where integration_id={integration_id} and status="DEAD_LETTER"'))
        db(f'DROP TRIGGER {TRIGGER}')
        manage('POST', f'/dashboard/integration/dead-letters/{message}/replay', {'reason': '隔离故障已恢复'})
        wait_count(f'select count(*) from dashboard_integration_record where integration_id={integration_id}', 9)
        print('真实 SQL 故障形成死信、站内告警、授权重放与恢复：通过')
        downstream = manage('POST', f'/dashboard/integration/operations/inbound/{integration_id}/dataset', {'datasetCode': f'mvp-dataset-stream-{SUFFIX}', 'datasetName': '推送页面回归', 'projectCode': 'demo', 'realtime': True, 'fields': [{'name': 'value', 'sourcePath': 'value', 'type': 'number'}]})
        dataset_ids.append(downstream['datasetId'])
        checked = test(downstream)
        assert len(checked['rows']) == 9, (checked.get('quality'), checked.get('message'))
        manage('PUT', '/dashboard/dataset', {'datasetId': downstream['datasetId'], 'status': 'ACTIVE'})
        schema = {'schemaVersion': '1.0', 'canvas': {'width': 1920, 'height': 1080}, 'widgets': [{'id': 'stream-table', 'type': 'table', 'name': '接收记录', 'layout': {'x': 0, 'y': 0, 'w': 800, 'h': 400}, 'binding': {'sourceType': 'DATASET', 'datasetCode': downstream['datasetCode'], 'fieldMap': {}}, 'style': {'title': '接收记录'}}]}
        page = manage('POST', '/dashboard/page', {'pageCode': f'mvp-media-stream-{SUFFIX}', 'pageName': '接收推送页面回归', 'schemaJson': json.dumps(schema)})
        page_ids.append(page['pageId'])
        manage('POST', f"/dashboard/page/{page['pageId']}/publish", {})
        runtime = manage('GET', f"/dashboard/page/{page['pageId']}/runtime")
        url = SECOND.replace('http:', 'ws:') + '/dashboard/runtime/ws?' + urllib.parse.urlencode({'token': m.TOKEN, 'pageId': page['pageId'], 'widgetId': 'stream-table', 'datasetCode': downstream['datasetCode']})
        ws = WsClient(url, os.environ.get('DASHBOARD_WEBSOCKET_ALLOWED_ORIGIN', 'http://127.0.0.1:5173').split(',')[0])
        try:
            initial = ws.read(); assert len(initial['rows']) == 9
            started = time.monotonic(); deliver(100, m.BACKEND)
            updated = ws.read()
            assert len(updated['rows']) == 10 and time.monotonic() - started < 8, updated.get('quality')
        finally: ws.close()
        print('标准记录生成数据集、发布页面与跨实例 WebSocket 更新：通过')
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            duplicate_results=list(pool.map(lambda base:deliver(201,base),[m.BACKEND,SECOND]))
        assert all(status in (200,202) for status,_ in duplicate_results),[(status,result.get('code')) for status,result in duplicate_results]
        wait_count(f'select count(*) from dashboard_integration_record where integration_id={integration_id}',11)
        assert db(f"select count(*) from dashboard_integration_message where integration_id={integration_id} and business_key='event-201-{SUFFIX}'")=='1'
        print('相同幂等请求同时投递两实例，仅一条业务记录：通过')
        ws_source=manage('POST','/dashboard/source',{'sourceCode':f'mvp-source-ws-{SUFFIX}','sourceName':'实时长连接回归','sourceType':'WEBSOCKET','status':'ACTIVE','configJson':json.dumps({'baseUrl':f'ws://127.0.0.1:{m.MOCK_PORT}','path':'/stream'})})
        websocket_source_id=ws_source['dataSourceId']
        live=manage('POST','/dashboard/dataset',{'datasetCode':f'mvp-dataset-live-{SUFFIX}','datasetName':'上游长连接回归','dataType':'WEBSOCKET','status':'DRAFT','configJson':json.dumps({'endpointCode':f'mvp-source-ws-{SUFFIX}','sourceType':'WEBSOCKET','message':{'rowsPath':'data'},'connection':{'heartbeatSeconds':1,'reconnectLimit':5}}),'paramSchemaJson':'[]','fieldSchemaJson':'[{"name":"value","type":"number"}]','refreshSeconds':5})
        dataset_ids.append(live['datasetId'])
        opening=test(live);assert opening['rows'],opening.get('message')
        time.sleep(3)
        later=test(live);assert later['rows'][0]['value']>opening['rows'][0]['value'],later.get('quality')
        connections=STREAM_CONNECTIONS
        newest=test(live)
        assert STREAM_CONNECTIONS==connections and STREAM_CONNECTIONS>=2 and STREAM_PINGS>0,(STREAM_CONNECTIONS,STREAM_PINGS)
        print('上游 WebSocket 长连接复用、ping/pong 和断线重连：通过')
        metrics = manage('GET', '/dashboard/integration/operations/metrics')
        assert metrics['requests24h'] > 0
        audit = manage('GET', '/dashboard/integration/operations/audits?category=OUTBOUND')
        assert audit['total'] > 0
        print('接入运维指标、分页审计：通过')
        # 在接收提交后、消费前终止首实例；第二实例须从持久台账恢复。
        db(f"INSERT INTO dashboard_integration_lease(lease_key,owner,expires_at) VALUES('consume:{integration_id}','interruption-test',TIMESTAMPADD(SECOND,5,NOW(3))) ON DUPLICATE KEY UPDATE owner=VALUES(owner),expires_at=VALUES(expires_at)")
        status,_=deliver(200,m.BACKEND);assert status==202
        primary_pid=int(os.environ['TEST_PRIMARY_PID'])
        command=subprocess.check_output(['ps','-p',str(primary_pid),'-o','command='],text=True)
        assert 'engineering-data-cockpit' in command and f"ruoyi-admin-{os.environ['TEST_BACKEND_PORT']}-" in command
        os.kill(primary_pid,signal.SIGTERM)
        wait_count(f'select count(*) from dashboard_integration_record where integration_id={integration_id}',12,25)
        env=os.environ.copy();env.update(BACKEND_PORT=os.environ['TEST_BACKEND_PORT'],REDIS_DATABASE=os.environ.get('TEST_REDIS_DATABASE','15'),DASHBOARD_INTEGRATION_ALLOW_LOCAL_DEVELOPMENT_TARGETS='true',DASHBOARD_INTEGRATION_ALLOW_PLAIN_HTTP='true')
        restart_log=open(pathlib.Path(__file__).resolve().parents[1]/'.local/integration-consolidation/restarted-backend.log','w')
        restarted=subprocess.Popen([str(pathlib.Path(__file__).with_name('start-backend.sh').resolve())],env=env,stdout=restart_log,stderr=subprocess.STDOUT)
        for _ in range(80):
            try:
                with urllib.request.urlopen(m.BACKEND+'/captchaImage',timeout=1) as response:
                    if response.status==200:break
            except Exception:time.sleep(.2)
        else:raise AssertionError('restarted backend did not become healthy')
        assert int(db(f'select count(*) from dashboard_integration_record where integration_id={integration_id}'))==12
        print('接收后实例终止、另一实例恢复消费、原实例重启不重复处理：通过')
    finally:
        if restarted:
            restarted.terminate()
            try: restarted.wait(timeout=15)
            except subprocess.TimeoutExpired: restarted.kill();restarted.wait()
        if restart_log:restart_log.close()
        db(f'DROP TRIGGER IF EXISTS {TRIGGER}')
        for page_id in page_ids:
            db(f'delete from dashboard_page where page_id={int(page_id)}')
        for dataset_id in dataset_ids:
            db(f'delete from dashboard_dataset where dataset_id={int(dataset_id)}')
        if integration_id:
            db(f'delete from dashboard_integration_signal where integration_id={integration_id};delete from dashboard_integration where integration_id={integration_id};')
        for endpoint_id in endpoint_ids:
            db(f'delete from dashboard_data_source_endpoint where endpoint_id={int(endpoint_id)}')
        if websocket_source_id: db(f'delete from dashboard_data_source where data_source_id={int(websocket_source_id)}')
        if source_id: db(f'delete from dashboard_data_source where data_source_id={int(source_id)}')
        db(f"delete from dashboard_integration_snapshot where source_code='{source_code}';delete from dashboard_integration_alert where resource_code like '%{SUFFIX}%';delete from dashboard_integration_audit where resource_code like '%{SUFFIX}%';")
        KEYFILE.unlink(missing_ok=True)
        server.shutdown();server.server_close()

if __name__ == '__main__': main()
