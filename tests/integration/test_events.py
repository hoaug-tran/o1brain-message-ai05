import json
import secrets
import time
from datetime import UTC, datetime, timedelta
from pathlib import Path
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import select

from ai05.config import Settings
from ai05.infrastructure.events import EVENTS, NONCES, RECEIPTS, SCHEMA, signature
from ai05.interfaces.http import create_app

SECRET = b"a" * 32
SOURCE = "test-device-one"
OTHER = "test-device-two"
PATH = "/api/v1/sms-events"


def payload() -> dict:
    return {
        "event_id": str(uuid4()),
        "source_kind": "synthetic_demo",
        "message_origin": "synthetic",
        "received_at_utc": datetime.now(UTC).isoformat(),
        "text": "Synthetic message for safe testing",
        "language_hint": "en",
    }


def signed(
    body: bytes,
    key: str | None = None,
    nonce: str | None = None,
    source: str = SOURCE,
    method: str = "POST",
    path: str = PATH,
    timestamp: str | None = None,
) -> dict:
    timestamp = timestamp or datetime.now(UTC).isoformat()
    nonce = nonce or secrets.token_hex(16)
    key = key or str(uuid4())
    return {
        "Content-Type": "application/json",
        "X-Device-ID": source,
        "X-Timestamp": timestamp,
        "X-Nonce": nonce,
        "X-Idempotency-Key": key,
        "X-Signature": signature(
            SECRET, source, method, path, timestamp, nonce, key, body
        ),
    }


@pytest.fixture
def client(tmp_path: Path):
    keystore = tmp_path / "keys.json"
    keystore.write_text(
        json.dumps(
            {
                source: {
                    "secret_hex": SECRET.hex(),
                    "enabled": True,
                    "allowed_source_kind": "synthetic_demo",
                }
                for source in (SOURCE, OTHER)
            }
        )
    )
    settings = Settings(
        database_url=f"sqlite:///{(tmp_path / 'events.db').as_posix()}",
        hmac_keystore_path=keystore,
    )
    app = create_app(settings=settings)
    with TestClient(app) as session:
        store = app.state.events

        SCHEMA.create_all(store.engine)
        store.ready = True
        yield session


def test_signed_real_inference_idempotency_and_replay(client: TestClient) -> None:
    body = json.dumps(payload()).encode()
    key, nonce = str(uuid4()), secrets.token_hex(16)
    headers = signed(body, key, nonce)
    first = client.post(PATH, content=body, headers=headers)
    assert first.status_code == 200
    assert not first.json()["persisted_raw_text"]
    assert not first.json()["is_duplicate"]
    assert client.post(PATH, content=body, headers=headers).status_code == 409
    duplicate = client.post(PATH, content=body, headers=signed(body, key))
    assert duplicate.status_code == 200 and duplicate.json()["is_duplicate"]
    assert duplicate.json()["prediction_id"] == first.json()["prediction_id"]
    changed = json.dumps(payload()).encode()
    assert (
        client.post(PATH, content=changed, headers=signed(changed, key)).status_code
        == 409
    )
    assert client.post(PATH, content=body, headers=signed(body)).status_code == 409
    feed = client.get("/api/v1/demo/events").json()
    assert len(feed["events"]) == 1
    assert feed["events"][0]["source_kind"] == "synthetic_demo"
    assert "text" not in json.dumps(feed) and "source_id" not in json.dumps(feed)


def test_unauthorized_forgery_expiry_and_no_body_echo(
    client: TestClient, caplog
) -> None:
    data = payload()
    data["text"] = "secret-OTP-123456"
    body = json.dumps(data).encode()
    assert (
        client.post(
            PATH, content=body, headers={"Content-Type": "application/json"}
        ).status_code
        == 401
    )
    headers = signed(body)
    headers["X-Signature"] = "v1=" + "0" * 64
    assert client.post(PATH, content=body, headers=headers).status_code == 401
    assert (
        client.post(
            PATH, content=body, headers=signed(body, source="unknown-device")
        ).status_code
        == 401
    )
    expired = (datetime.now(UTC) - timedelta(seconds=301)).isoformat()
    assert (
        client.post(
            PATH, content=body, headers=signed(body, timestamp=expired)
        ).status_code
        == 401
    )
    for field, value in (
        ("source_kind", "android_receiver"),
        ("message_origin", "real_test"),
    ):
        forged = dict(data, **{field: value})
        raw = json.dumps(forged).encode()
        assert client.post(PATH, content=raw, headers=signed(raw)).status_code == 403
    assert "secret-OTP-123456" not in caplog.text
    assert client.get("/api/v1/demo/events").json()["events"] == []


def test_scoped_delete_and_expiration(client: TestClient) -> None:
    for source in (SOURCE, OTHER):
        body = json.dumps(payload()).encode()
        assert (
            client.post(
                PATH, content=body, headers=signed(body, source=source)
            ).status_code
            == 200
        )
    headers = signed(b"", method="DELETE", path="/api/v1/events")
    response = client.request("DELETE", "/api/v1/events", headers=headers)
    assert response.json()["deleted"] == 1
    assert len(client.get("/api/v1/demo/events").json()["events"]) == 1
    assert (
        client.request("DELETE", "/api/v1/events", headers=headers).status_code == 409
    )
    store = client.app.state.events
    with store.engine.begin() as connection:
        connection.execute(EVENTS.update().values(expires_at=int(time.time()) - 1))
    assert client.get("/api/v1/demo/events").json()["events"] == []


def test_inference_failure_rolls_back_receipt_and_nonce(client: TestClient) -> None:
    data = payload()
    data["language_hint"] = "vi"
    body = json.dumps(data).encode()
    assert client.post(PATH, content=body, headers=signed(body)).status_code == 400
    store = client.app.state.events
    with store.engine.begin() as connection:
        for table in (EVENTS, RECEIPTS, NONCES):
            assert connection.execute(select(table)).all() == []


def test_bad_security_settings() -> None:
    for values in (
        {"raw_sms_retention_enabled": True},
        {"real_sms_ingest_enabled": True},
        {"database_url": "postgresql://elsewhere"},
    ):
        with pytest.raises(ValueError):
            Settings(**values)
    assert Settings(env="public").env == "public"
    for values in (
        {"raw_sms_retention_enabled": True},
        {"real_sms_ingest_enabled": True},
    ):
        with pytest.raises(ValueError):
            Settings(env="public", **values)


def test_authenticated_prediction_and_owner_list(client: TestClient) -> None:
    body = json.dumps({"text": "Synthetic test greeting"}).encode()
    path = "/api/v1/predictions"
    assert (
        client.post(
            path, content=body, headers={"Content-Type": "application/json"}
        ).status_code
        == 401
    )
    response = client.post(path, content=body, headers=signed(body, path=path))
    assert response.status_code == 200
    assert response.json()["model"]["id"] == "english_char_svm"
    event = json.dumps(payload()).encode()
    assert client.post(PATH, content=event, headers=signed(event)).status_code == 200
    for source, count in ((SOURCE, 1), (OTHER, 0)):
        headers = signed(b"", source=source, method="GET", path="/api/v1/events")
        response = client.get("/api/v1/events", headers=headers)
        assert response.status_code == 200
        assert len(response.json()["events"]) == count
