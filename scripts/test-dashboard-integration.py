#!/usr/bin/env python3

import base64
import hashlib
import hmac
import http.server
import json
import os
import secrets
import struct
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import zlib


BACKEND = f"http://127.0.0.1:{os.environ.get('TEST_BACKEND_PORT', '18080')}"
MOCK_PORT = int(os.environ.get("TEST_MOCK_PORT", "18081"))
TOKEN = ""
OUTBOUND_IDENTITY = "integration-test-app"
OUTBOUND_SECRET = secrets.token_urlsafe(32)
RETRY_TIMESTAMPS = []
RETRY_COUNTS = {}


def png_chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)


def test_png():
    signature = b"\x89PNG\r\n\x1a\n"
    ihdr = png_chunk(b"IHDR", struct.pack(">IIBBBBB", 2, 2, 8, 6, 0, 0, 0))
    metadata = png_chunk(b"tEXt", b"Comment\x00integration-secret-metadata")
    raw = b"".join(b"\x00" + bytes((20, 100, 180, 255)) * 2 for _ in range(2))
    return signature + ihdr + metadata + png_chunk(b"IDAT", zlib.compress(raw)) + png_chunk(b"IEND", b"")


PNG = test_png()


class MockHandler(http.server.BaseHTTPRequestHandler):
    def authorized(self):
        timestamp = self.headers.get("X-Timestamp", "")
        supplied = self.headers.get("X-Signature", "")
        expected = base64.b64encode(
            hmac.new(OUTBOUND_SECRET.encode(), f"{OUTBOUND_IDENTITY}{timestamp}".encode(), hashlib.sha256).digest()
        ).decode()
        return (
            self.headers.get("X-App-Key") == OUTBOUND_IDENTITY
            and timestamp.isdigit()
            and hmac.compare_digest(supplied, expected)
        )

    def reject_auth(self):
        body = b"authentication failed"
        self.send_response(401)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if not self.authorized():
            self.reject_auth()
            return
        if self.path.startswith("/json/"):
            project = urllib.parse.unquote(self.path.split("/", 2)[2]).split("?", 1)[0]
            if project == "retry-demo":
                RETRY_TIMESTAMPS.append(self.headers.get("X-Timestamp", ""))
                RETRY_COUNTS[project] = RETRY_COUNTS.get(project, 0) + 1
                if RETRY_COUNTS[project] == 1:
                    body = b"temporary failure"
                    self.send_response(503)
                    self.send_header("Content-Type", "text/plain")
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)
                    return
            body = json.dumps({
                "success": True,
                "message": "ok",
                "data": [{
                    "id": "event-media-1",
                    "name": "联调样本",
                    "projectCode": project,
                    "picUri": "pictures/event-media-1.png",
                    "serverIndexCode": "server-1",
                }],
            }, ensure_ascii=False, separators=(",", ":")).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self.send_error(404)

    def do_POST(self):
        if not self.authorized():
            self.reject_auth()
            return
        if self.path != "/media":
            self.send_error(404)
            return
        size = int(self.headers.get("Content-Length", "0"))
        form = urllib.parse.parse_qs(self.rfile.read(size).decode(), keep_blank_values=True)
        if form.get("picUri", [""])[0] == "gif":
            body = base64.b64decode("R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==")
            content_type = "image/gif"
        elif form.get("serverIndexCode", [""])[0] != "server-1":
            body = b"invalid server"
            self.send_response(400)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        else:
            body = PNG
            content_type = "application/octet-stream"
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Disposition", 'attachment; filename="upstream-evil.svg"')
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_):
        pass


def request(method, path, data=None, token=True, raw=False, headers=None):
    payload = None if data is None else json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode()
    outgoing = {"Accept": "application/json"}
    if payload is not None:
        outgoing["Content-Type"] = "application/json"
    if token and TOKEN:
        outgoing["Authorization"] = f"Bearer {TOKEN}"
    if headers:
        outgoing.update(headers)
    req = urllib.request.Request(BACKEND + path, data=payload, method=method, headers=outgoing)
    try:
        response = urllib.request.urlopen(req, timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    body = response.read()
    if raw:
        return response.status, {key.lower(): value for key, value in response.headers.items()}, body
    try:
        parsed = json.loads(body)
    except Exception as error:
        raise AssertionError(f"{method} {path} returned non-JSON status={response.status}: {body[:200]!r}") from error
    return response.status, parsed


def management(method, path, data=None, expected_code=200):
    status, body = request(method, path, data)
    actual = body.get("code")
    if actual != expected_code:
        raise AssertionError(f"{method} {path}: expected code={expected_code}, status={status}, body={body}")
    return body.get("data")


def outbound_setup(suffix):
    source_code = f"mvp-source-{suffix}"
    records_code = "records"
    media_code = "event-media"
    source = management("POST", "/dashboard/source", {
        "sourceCode": source_code,
        "sourceName": "第三方接入端到端测试来源",
        "sourceType": "HTTP",
        "status": "ACTIVE",
        "configJson": json.dumps({
            "baseUrl": f"http://127.0.0.1:{MOCK_PORT}",
            "path": "/",
            "method": "GET",
            "networkProfile": "PRIVATE_LINK",
            "allowedHosts": ["127.0.0.1"],
            "allowedCidrs": ["127.0.0.1/32"],
            "allowedPorts": [MOCK_PORT],
            "authProvider": "HMAC_APPKEY_TIMESTAMP_V1",
            "identity": OUTBOUND_IDENTITY,
            "timeoutSeconds": 8,
            "concurrencyLimit": 2,
            "timezone": "Asia/Shanghai",
        }, separators=(",", ":")),
        "secret": OUTBOUND_SECRET,
        "remark": "自动化测试",
    })
    source_id = source["dataSourceId"]
    status, headers, raw_config = request("GET", f"/dashboard/source/{source_code}/configuration", raw=True)
    source_config = json.loads(raw_config)["data"]
    assert status == 200 and headers.get("cache-control") == "no-store"
    assert source_config.get("secret") == OUTBOUND_SECRET, "source credential roundtrip mismatch"
    assert json.loads(source_config["configJson"])["baseUrl"] == f"http://127.0.0.1:{MOCK_PORT}"

    assert "baseUrl" not in json.loads(source["configJson"])

    management("POST", f"/dashboard/integration/endpoints/{source_code}", {
        "endpointCode": "active-without-test",
        "endpointName": "非法直接启用",
        "path": "/json/demo",
        "method": "GET",
        "requestContentType": "NONE",
        "responseType": "JSON",
        "authProvider": "INHERIT",
        "status": "ACTIVE",
        "configJson": "{}",
    }, expected_code=500)

    records = management("POST", f"/dashboard/integration/endpoints/{source_code}", {
        "endpointCode": records_code,
        "endpointName": "记录查询",
        "path": "/json/{projectCode}",
        "method": "GET",
        "requestContentType": "NONE",
        "responseType": "JSON",
        "authProvider": "INHERIT",
        "contractVersion": "1.0",
        "status": "DRAFT",
        "configJson": json.dumps({
            "envelopeMode": "JSON_BOOLEAN",
            "successPath": "success",
            "successValue": True,
            "messagePath": "message",
            "rowsPath": "data",
            "requiredPaths": ["data"],
            "rowLimit": 100,
            "maxResponseBytes": 65536,
            "maxAttempts": 2,
            "backoffMs": 50,
            "canonicalProfile": "APP_KEY_TIMESTAMP_V1",
            "timestampUnit": "MILLISECONDS",
            "nonceRequired": False,
        }, separators=(",", ":")),
    })
    records_id = records["endpointId"]
    config = management("GET", f"/dashboard/integration/endpoints/item/{records_id}/configuration")
    assert config.get("effectiveSecret") == OUTBOUND_SECRET, "inherited endpoint credential mismatch"
    assert not config.get("secret"), "inherited credentials must not become endpoint-specific credentials"


    media = management("POST", f"/dashboard/integration/endpoints/{source_code}", {
        "endpointCode": media_code,
        "endpointName": "事件图片",
        "path": "/media",
        "method": "POST",
        "requestContentType": "FORM_URLENCODED",
        "responseType": "BINARY_MEDIA",
        "authProvider": "INHERIT",
        "contractVersion": "1.0",
        "status": "DRAFT",
        "configJson": json.dumps({
            "formFields": ["picUri", "serverIndexCode"],
            "mediaProfile": "IMAGE",
            "idempotent": True,
            "identity": OUTBOUND_IDENTITY,
            "canonicalProfile": "APP_KEY_TIMESTAMP_V1",
            "timestampUnit": "MILLISECONDS",
            "nonceRequired": False,
            "media": {
                "allowedMimeTypes": ["image/png", "image/jpeg"],
                "maxBytes": 65536,
                "ttlSeconds": 120,
                "maxWidth": 100,
                "maxHeight": 100,
                "maxPixels": 10000,
                "maxAnimationFrames": 1,
                "stripMetadata": True,
                "inline": True,
                "allowRange": False,
                "firstByteTimeoutSeconds": 5,
                "downloadTimeoutSeconds": 5,
                "concurrencyLimit": 1,
            },
        }, separators=(",", ":")),
    })
    media_id = media["endpointId"]

    tested_records = management("POST", f"/dashboard/integration/endpoints/{source_code}/{records_code}/test", {"projectCode": "demo"})
    assert tested_records["quality"] == "SUCCESS" and tested_records["rowCount"] == 1
    rejected_gif = management("POST", f"/dashboard/integration/endpoints/{source_code}/{media_code}/test", {
        "picUri": "gif",
        "serverIndexCode": "server-1",
    })
    assert rejected_gif["quality"] == "INVALID_DATA", rejected_gif
    tested_media = management("POST", f"/dashboard/integration/endpoints/{source_code}/{media_code}/test", {
        "picUri": "pictures/event-media-1.png",
        "serverIndexCode": "server-1",
    })
    assert tested_media["quality"] == "SUCCESS", tested_media
    assert tested_media["contentType"] == "image/png" and tested_media["width"] == 2 and tested_media["height"] == 2
    retried_records = management("POST", f"/dashboard/integration/endpoints/{source_code}/{records_code}/test", {"projectCode": "retry-demo"})
    assert retried_records["quality"] == "SUCCESS", retried_records
    assert len(RETRY_TIMESTAMPS) == 2 and len(set(RETRY_TIMESTAMPS)) == 2, RETRY_TIMESTAMPS
    management("PUT", f"/dashboard/integration/endpoints/{source_code}", {"endpointId": records_id, "status": "ACTIVE"})
    management("PUT", f"/dashboard/integration/endpoints/{source_code}", {"endpointId": media_id, "status": "ACTIVE"})
    management("PUT", f"/dashboard/integration/endpoints/{source_code}", {
        "endpointId": records_id,
        "path": "/json/changed/{projectCode}",
    }, expected_code=500)
    return source_code, source_id, records_id, media_id


def dataset_and_media(source_code, suffix):
    dataset_code = f"mvp-dataset-{suffix}"
    create = management("POST", "/dashboard/dataset", {
        "datasetCode": dataset_code,
        "datasetName": "第三方记录与媒体候选",
        "dataType": "API",
        "status": "DRAFT",
        "configJson": json.dumps({
            "sourceCode": source_code,
            "endpointCode": "records",
            "response": {"rowsPath": "data", "requiredPaths": ["data"], "rowErrorMode": "REJECT"},
            "rowLimit": 100,
            "mediaProjections": [{
                "outputField": "pictureRef",
                "endpointCode": "event-media",
                "businessKeyPath": "id",
                "formFieldMappings": {"picUri": "picUri", "serverIndexCode": "serverIndexCode"},
            }],
        }, separators=(",", ":")),
        "fieldSchemaJson": json.dumps([
            {"name": "eventId", "title": "事件编号", "sourcePath": "id", "type": "string", "required": True},
            {"name": "name", "title": "名称", "sourcePath": "name", "type": "string"},
            {"name": "projectCode", "title": "项目", "sourcePath": "projectCode", "type": "string"},
        ], ensure_ascii=False, separators=(",", ":")),
        "paramSchemaJson": json.dumps([
            {"name": "projectCode", "title": "项目编码", "type": "STRING", "in": "path", "required": True},
        ], ensure_ascii=False, separators=(",", ":")),
        "timeoutSeconds": 8,
        "refreshSeconds": 30,
    })
    dataset_id = create["datasetId"]
    undeclared = management("POST", f"/dashboard/dataset/{dataset_id}/test", {"projectCode": "demo", "unexpected": "blocked"})
    assert undeclared["quality"] == "INVALID_DATA", undeclared
    tested = management("POST", f"/dashboard/dataset/{dataset_id}/test", {"projectCode": "demo"})
    assert tested["quality"] == "SUCCESS" and tested["rows"][0]["pictureRef"]
    management("PUT", "/dashboard/dataset", {"datasetId": dataset_id, "status": "ACTIVE"})
    management("PUT", "/dashboard/dataset", {
        "datasetId": dataset_id,
        "fieldSchemaJson": json.dumps([
            {"name": "eventId", "sourcePath": "id", "type": "string", "required": True},
        ], separators=(",", ":")),
    }, expected_code=500)

    page_code = f"mvp-media-{suffix}"
    widget_id = "media-bound-widget"
    schema = {
        "schemaVersion": "1.0",
        "canvas": {"width": 1920, "height": 1080, "scaleMode": "contain", "backgroundColor": "#101827", "theme": "dark"},
        "refresh": {"enabled": True, "mode": "interval", "seconds": 60, "at": "08:00"},
        "widgets": [{
            "id": widget_id,
            "type": "text",
            "layout": {"x": 0, "y": 0, "w": 500, "h": 200},
            "binding": {"sourceType": "DATASET", "datasetCode": dataset_code, "params": {"projectCode": "demo"}},
            "style": {"text": "媒体联调"},
        }],
    }
    page = management("POST", "/dashboard/page", {
        "pageCode": page_code,
        "pageName": "媒体引用端到端测试",
        "schemaJson": json.dumps(schema, ensure_ascii=False, separators=(",", ":")),
    })
    page_id = page["pageId"]
    management("POST", f"/dashboard/page/{page_id}/publish", {})
    runtime = management("GET", f"/dashboard/page/{page_id}/runtime")
    revision_id = runtime["revisionId"]
    runtime_data = management("POST", "/dashboard/runtime/data", {
        "pageId": page_id,
        "revisionId": revision_id,
        "widgetId": widget_id,
        "datasetCode": dataset_code,
        "params": {"projectCode": "demo"},
        "filters": [],
    })
    assert runtime_data.get("rows"), {key: runtime_data.get(key) for key in ("quality", "message", "sourceErrorCode")}
    candidate = runtime_data["rows"][0]["pictureRef"]
    assert candidate and "picUri" not in runtime_data["rows"][0] and "serverIndexCode" not in runtime_data["rows"][0]
    management("POST", "/dashboard/runtime/media/ref", {
        "candidateRef": candidate,
        "datasetCode": dataset_code,
        "widgetId": "not-bound-widget",
        "pageId": page_id,
        "revisionId": revision_id,
    }, expected_code=500)
    issued = management("POST", "/dashboard/runtime/media/ref", {
        "candidateRef": candidate,
        "datasetCode": dataset_code,
        "widgetId": widget_id,
        "pageId": page_id,
        "revisionId": revision_id,
    })
    status, headers, image = request("GET", f"/dashboard/runtime/media/{issued['mediaRef']}", token=True, raw=True)
    assert status == 200 and headers.get("content-type", "").startswith("image/png")
    assert headers.get("x-content-type-options") == "nosniff"
    assert headers.get("content-disposition", "").startswith("inline; filename=\"media-")
    assert "upstream-evil" not in headers.get("content-disposition", "")
    assert b"integration-secret-metadata" not in image and len(image) < len(PNG)
    management("POST", "/dashboard/runtime/media/ref", {
        "candidateRef": candidate,
        "datasetCode": dataset_code,
        "widgetId": widget_id,
        "pageId": page_id,
        "revisionId": revision_id,
    }, expected_code=500)

    share = management("POST", f"/dashboard/page/{page_id}/shares", {"expiresHours": 1})
    share_token = share["token"]
    share_runtime_data = management("POST", f"/dashboard/public/share/{share_token}/runtime/data", {
        "pageId": page_id,
        "widgetId": widget_id,
        "datasetCode": dataset_code,
        "params": {"projectCode": "demo"},
        "filters": [],
    })
    share_candidate = share_runtime_data["rows"][0]["pictureRef"]
    assert share_candidate and share_candidate != candidate
    share_issued = management("POST", f"/dashboard/public/share/{share_token}/media/ref", {
        "candidateRef": share_candidate,
        "datasetCode": dataset_code,
        "widgetId": widget_id,
    }, expected_code=200)
    status, _, _ = request("GET", f"/dashboard/public/share/{share_token}/media/{share_issued['mediaRef']}", token=False, raw=True)
    assert status == 200
    management("DELETE", f"/dashboard/page/{page_id}/shares/{share['shareId']}")
    status, _, _ = request("GET", f"/dashboard/public/share/{share_token}/media/{share_issued['mediaRef']}", token=False, raw=True)
    assert status == 404
    return dataset_code, dataset_id, page_id


def signed_headers(secret, integration_code, key_id, path, body, idem, schema="1.0", nonce=None, timestamp=None):
    timestamp = timestamp or str(int(time.time()))
    nonce = nonce or secrets.token_urlsafe(18)
    digest = hashlib.sha256(body).hexdigest()
    canonical = "\n".join(["POST", path, integration_code, key_id, timestamp, nonce, idem, schema, digest])
    signature = base64.b64encode(hmac.new(secret.encode(), canonical.encode(), hashlib.sha256).digest()).decode()
    return {
        "Content-Type": "application/json",
        "X-Key-Id": key_id,
        "X-App-Key": "integration-test-app",
        "X-Timestamp": timestamp,
        "X-Nonce": nonce,
        "X-Signature": signature,
        "X-Schema-Version": schema,
        "X-Integration-Id": integration_code,
        "Idempotency-Key": idem,
    }


def inbound(suffix):
    integration_code = f"mvp-in-{suffix}"
    key_id = f"key-{suffix}"
    secret = secrets.token_urlsafe(32)
    integration = management("POST", "/dashboard/integration", {
        "integrationCode": integration_code,
        "integrationName": "第三方主动推送端到端测试",
        "environment": "TEST",
        "profile": "EVENT",
        "schemaVersion": "1.0",
        "endpointCode": "events",
        "networkProfile": "PUBLIC_HTTPS",
        "timezone": "Asia/Shanghai",
        "maxBatchItems": 10,
        "maxBodyBytes": 65536,
        "rateLimit": 100,
        "concurrencyLimit": 5,
        "rawRetentionDays": 1,
        "projectScope": ["demo"],
        "allowedIps": ["127.0.0.1/32"],
        "status": "DRAFT",
        "configJson": json.dumps({
            "timestampUnit": "SECONDS",
            "clockSkewSeconds": 300,
            "nonceRequired": True,
            "idempotencyRequired": True,
            "processingMode": "ASYNC",
            "itemErrorMode": "PARTIAL",
            "maxAttempts": 2,
            "deadLetterAfter": 3,
            "canonicalProfile": "METHOD_PATH_INTEGRATION_KEY_TIMESTAMP_NONCE_IDEMPOTENCY_SCHEMA_BODY_V1",
            "signatureEncoding": "BASE64",
            "unknownFieldMode": "REJECT",
            "messageIdRequired": True,
            "businessTimeRequired": True,
        }, separators=(",", ":")),
    })
    integration_id = integration["integrationId"]
    created_key = management("POST", f"/dashboard/integration/{integration_id}/keys", {
        "keyId": key_id,
        "provider": "HMAC_V1",
        "identityValue": "integration-test-app",
        "secret": secret,
    })
    revealed = management("GET", f"/dashboard/integration/key/{created_key['integrationKeyId']}/configuration")
    assert revealed.get("secret") == secret, "inbound credential roundtrip mismatch"
    management("POST", f"/dashboard/integration/{integration_id}/status", {"status": "ACTIVE"})
    path = f"/open-api/v1/integrations/{integration_code}/events"

    def deliver(envelope, idem, nonce=None, timestamp=None, signature_ok=True):
        body = json.dumps(envelope, ensure_ascii=False, separators=(",", ":")).encode()
        headers = signed_headers(secret, integration_code, key_id, path, body, idem,
                                 schema="1.0", nonce=nonce, timestamp=timestamp)
        if not signature_ok:
            headers["X-Signature"] = "invalid"
        req = urllib.request.Request(BACKEND + path, data=body, method="POST", headers=headers)
        try:
            response = urllib.request.urlopen(req, timeout=20)
        except urllib.error.HTTPError as error:
            response = error
        return response.status, json.loads(response.read())

    base_item = {"eventId": f"event-{suffix}-1", "projectCode": "demo", "occurredAt": "2026-09-09T02:00:00+08:00", "data": {"value": 1}}
    envelope = {"messageId": f"message-{suffix}-1", "schemaVersion": "1.0", "projectCode": "demo", "items": [base_item]}
    replay_nonce = secrets.token_urlsafe(18)
    status, first = deliver(envelope, f"idem-{suffix}-1", nonce=replay_nonce)
    assert status == 202 and first["processing"] == 1, (status, first)
    status, replayed_nonce = deliver(envelope, f"idem-{suffix}-1", nonce=replay_nonce)
    assert status == 401 and replayed_nonce["code"] == "REPLAYED_NONCE"
    status, duplicate_delivery = deliver(envelope, f"idem-{suffix}-1")
    assert status in (200, 202) and duplicate_delivery["requestId"] == first["requestId"]
    reordered_item = {
        "data": {"value": 1},
        "occurredAt": base_item["occurredAt"],
        "projectCode": "demo",
        "eventId": base_item["eventId"],
    }
    semantic_duplicate_envelope = {
        "messageId": f"message-{suffix}-order",
        "schemaVersion": "1.0",
        "projectCode": "demo",
        "items": [reordered_item],
    }
    status, semantic_duplicate = deliver(semantic_duplicate_envelope, f"idem-{suffix}-order")
    assert status == 200 and semantic_duplicate["duplicates"] == 1, (status, semantic_duplicate)
    changed_envelope = dict(envelope)
    changed_envelope["items"] = [dict(base_item, data={"value": 2})]
    status, idem_conflict = deliver(changed_envelope, f"idem-{suffix}-1")
    assert status == 409 and idem_conflict["code"] == "IDEMPOTENCY_CONFLICT"
    conflict_envelope = {"messageId": f"message-{suffix}-2", "schemaVersion": "1.0", "projectCode": "demo", "items": [dict(base_item, data={"value": 3})]}
    status, business_conflict = deliver(conflict_envelope, f"idem-{suffix}-2")
    assert status == 409 and business_conflict["code"] == "CONFLICT"
    status, replayed_business_conflict = deliver(conflict_envelope, f"idem-{suffix}-2")
    assert status == 409 and replayed_business_conflict["requestId"] == business_conflict["requestId"]
    invalid_envelope = {"messageId": f"message-{suffix}-3", "schemaVersion": "1.0", "projectCode": "demo", "items": [{"data": {"value": 1}}]}
    status, invalid = deliver(invalid_envelope, f"idem-{suffix}-3")
    assert status == 422 and invalid["code"] == "PARTIAL" and invalid["rejected"] == 1
    status, replayed_invalid = deliver(invalid_envelope, f"idem-{suffix}-3")
    assert status == 422 and replayed_invalid["requestId"] == invalid["requestId"]
    partial_envelope = {
        "messageId": f"message-{suffix}-partial",
        "schemaVersion": "1.0",
        "projectCode": "demo",
        "items": [dict(base_item, eventId=f"event-{suffix}-partial"), {"data": {"value": 1}}],
    }
    status, partial = deliver(partial_envelope, f"idem-{suffix}-partial")
    assert status == 202 and partial["code"] == "PARTIAL" and partial["processing"] == 1 and partial["rejected"] == 1
    status, bad_signature = deliver({"messageId": f"message-{suffix}-4", "schemaVersion": "1.0", "projectCode": "demo", "items": [dict(base_item, eventId=f"event-{suffix}-4")]}, f"idem-{suffix}-4", signature_ok=False)
    assert status == 401 and bad_signature["code"] == "SIGNATURE_INVALID"
    status, expired = deliver({"messageId": f"message-{suffix}-5", "schemaVersion": "1.0", "projectCode": "demo", "items": [dict(base_item, eventId=f"event-{suffix}-5")]}, f"idem-{suffix}-5", timestamp=str(int(time.time()) - 1000))
    assert status == 401 and expired["code"] == "TIMESTAMP_EXPIRED"

    time.sleep(0.5)
    query_path = f"/open-api/v1/integrations/{integration_code}/messages/{envelope['messageId']}"
    timestamp = str(int(time.time()))
    nonce = secrets.token_urlsafe(18)
    digest = hashlib.sha256(b"").hexdigest()
    canonical = "\n".join(["GET", query_path, integration_code, key_id, timestamp, nonce, "", "1.0", digest])
    signature = base64.b64encode(hmac.new(secret.encode(), canonical.encode(), hashlib.sha256).digest()).decode()
    status, queried = request("GET", query_path, token=False, headers={
        "X-Key-Id": key_id,
        "X-Timestamp": timestamp,
        "X-Nonce": nonce,
        "X-Signature": signature,
        "X-Schema-Version": "1.0",
        "X-Integration-Id": integration_code,
    })
    assert status == 200 and queried["status"] in ("ACCEPTED", "PROCESSING")
    return integration_id


def main():
    global TOKEN
    server = http.server.ThreadingHTTPServer(("127.0.0.1", MOCK_PORT), MockHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    suffix = secrets.token_hex(4)
    resources = {}
    try:
        _, login = request("POST", "/login", {"username": os.environ.get("TEST_ADMIN_USERNAME", "admin"), "password": os.environ.get("TEST_ADMIN_PASSWORD", "admin123"), "code": "", "uuid": ""}, token=False)
        assert login.get("code") == 200 and login.get("token")
        TOKEN = login["token"]
        source_code, source_id, records_id, media_id = outbound_setup(suffix)
        resources.update(source_code=source_code, source_id=source_id, endpoint_ids=[records_id, media_id])
        dataset_code, dataset_id, page_id = dataset_and_media(source_code, suffix)
        resources.update(dataset_code=dataset_code, dataset_id=dataset_id, page_id=page_id)
        integration_id = inbound(suffix)
        resources["integration_id"] = integration_id
        print(json.dumps({
            "outboundEndpointTests": "passed",
            "datasetLifecycle": "passed",
            "mediaAuthorizationAndSanitization": "passed",
            "inboundAuthenticationReplayIdempotencyConflict": "passed",
        }, ensure_ascii=False))
    finally:
        integration_id = resources.get("integration_id")
        if integration_id:
            try: management("POST", f"/dashboard/integration/{integration_id}/status", {"status": "REVOKED"})
            except Exception: pass
            try: management("DELETE", f"/dashboard/integration/{integration_id}")
            except Exception: pass
        page_id = resources.get("page_id")
        if page_id:
            try: management("DELETE", f"/dashboard/page/{page_id}")
            except Exception: pass
            try: management("DELETE", f"/dashboard/page/{page_id}/purge")
            except Exception: pass
        dataset_id = resources.get("dataset_id")
        if dataset_id:
            try: management("DELETE", f"/dashboard/dataset/{dataset_id}")
            except Exception: pass
        for endpoint_id in resources.get("endpoint_ids", []):
            try: management("DELETE", f"/dashboard/integration/endpoints/item/{endpoint_id}")
            except Exception: pass
        source_id = resources.get("source_id")
        if source_id:
            try: management("DELETE", f"/dashboard/source/{source_id}")
            except Exception: pass
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    main()
