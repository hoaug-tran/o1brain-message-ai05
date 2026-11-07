import hashlib
import json
import secrets
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from ai05.infrastructure.community import CommunityStore
from ai05.infrastructure.events import EventError, signature
from ai05.interfaces.community import create_app


def proof(challenge):
    for nonce in range(16777216):
        if hashlib.sha256(f"{challenge}:{nonce}".encode()).digest().startswith(b"\0\0"):
            return nonce
    raise AssertionError("No proof found")


def enroll(store, peer="127.0.0.1"):
    secret = secrets.token_hex(32)
    identity = {
        "device_id": hashlib.sha256(bytes.fromhex(secret)).hexdigest()[:32],
        "secret_hex": secret,
    }
    token = store.challenge(peer)["challenge"]
    store.enroll(peer, identity["device_id"], secret, token, proof(token))
    return identity


def signed(identity, path, payload, nonce=None, timestamp=None):
    body = json.dumps(payload, separators=(",", ":")).encode()
    timestamp = timestamp or datetime.now(UTC).isoformat()
    nonce = nonce or secrets.token_hex(16)
    key = secrets.token_hex(16)
    device = identity["device_id"]
    return body, {
        "Content-Type": "application/json",
        "X-Device-ID": device,
        "X-Timestamp": timestamp,
        "X-Nonce": nonce,
        "X-Idempotency-Key": key,
        "X-Signature": signature(
            bytes.fromhex(identity["secret_hex"]),
            device,
            "POST",
            path,
            timestamp,
            nonce,
            key,
            body,
        ),
    }


def post(client, identity, path, payload):
    body, headers = signed(identity, path, payload)
    return client.post(path, content=body, headers=headers)


def test_shared_reports_deduplicate_retract_revoke_and_preserve_privacy(tmp_path: Path):
    store = CommunityStore(tmp_path)
    phone = "+84901234567"
    with TestClient(create_app(tmp_path)) as client:
        devices = []
        for _ in range(3):
            secret = secrets.token_hex(32)
            identity = {
                "device_id": hashlib.sha256(bytes.fromhex(secret)).hexdigest()[:32],
                "secret_hex": secret,
            }
            challenge = client.post("/v1/challenge").json()
            payload = {
                **identity,
                "challenge": challenge["challenge"],
                "nonce": proof(challenge["challenge"]),
            }
            response = client.post("/v1/enroll", json=payload)
            assert response.status_code == 200
            assert response.json()["device_id"] == identity["device_id"]
            assert client.post("/v1/enroll", json=payload).status_code == 401
            devices.append(identity)
        for index, device in enumerate(devices):
            for _ in range(2):
                response = post(
                    client, device, "/v1/reports", {"phone": phone, "category": "spam"}
                )
                assert response.status_code == 200
                assert response.json()["reporters"] == index + 1
        assert response.json()["status"] == "reported"
        result = post(client, devices[0], "/v1/reputation", {"phone": phone}).json()
        assert result["categories"] == {"spam": 3}
        assert result["own_category"] == "spam"
        result = post(
            client, devices[0], "/v1/reports", {"phone": phone, "category": "phishing"}
        ).json()
        assert result["reporters"] == 3 and result["categories"]["spam"] == 2
        result = post(
            client, devices[0], "/v1/reports/retract", {"phone": phone}
        ).json()
        assert result["reporters"] == 2 and result["own_category"] is None
        store.revoke(devices[1]["device_id"])
        assert (
            post(client, devices[1], "/v1/reputation", {"phone": phone}).status_code
            == 401
        )
        assert (
            post(client, devices[2], "/v1/reputation", {"phone": phone}).json()[
                "reporters"
            ]
            == 1
        )
    assert phone.encode() not in store.database.read_bytes()
    with store.connect() as db:
        columns = {row[1] for row in db.execute("PRAGMA table_info(reports)")}
        assert columns == {"device", "phone", "category", "created"}
    assert CommunityStore(tmp_path).phone_digest(phone) == store.phone_digest(phone)


def test_authentication_replay_tamper_bounds_and_retention(tmp_path, caplog):
    store = CommunityStore(tmp_path)
    identity = enroll(store)
    phone = "+84901234567"
    payload = {"phone": phone, "category": "spam"}
    path = "/v1/reports"
    with TestClient(create_app(tmp_path)) as client:
        body, headers = signed(identity, path, payload)
        assert client.post(path, content=body, headers=headers).status_code == 200
        assert client.post(path, content=body, headers=headers).status_code == 409
        assert (
            client.post(
                path, content=body.replace(b"spam", b"ham"), headers=headers
            ).status_code
            == 422
        )
        assert (
            client.post("/v1/reputation", content=body, headers=headers).status_code
            == 422
        )
        tampered = json.dumps({"phone": "+84901234568", "category": "spam"}).encode()
        assert client.post(path, content=tampered, headers=headers).status_code == 401
        expired, old_headers = signed(
            identity, path, payload, timestamp="2020-01-01T00:00:00Z"
        )
        assert (
            client.post(path, content=expired, headers=old_headers).status_code == 401
        )
        assert client.post(path, json=payload).status_code == 401
        assert (
            post(client, identity, path, {**payload, "text": "OTP-private"}).status_code
            == 422
        )
        assert (
            post(client, identity, path, {**payload, "phone": "0901234567"}).status_code
            == 422
        )
        assert client.post(path, content=b"x" * 8193).status_code == 413
        with store.connect() as db:
            db.execute("UPDATE reports SET created=?", (int(time.time()) - 91 * 86400,))
        assert (
            post(client, identity, "/v1/reputation", {"phone": phone}).json()[
                "reporters"
            ]
            == 0
        )
        assert phone not in caplog.text and "OTP-private" not in caplog.text


def test_enrollment_atomicity_quota_expiry_and_revocation(tmp_path):
    store = CommunityStore(tmp_path)
    token = store.challenge("127.0.0.1")["challenge"]
    nonce = proof(token)
    secret = secrets.token_hex(32)
    device = hashlib.sha256(bytes.fromhex(secret)).hexdigest()[:32]

    def register():
        try:
            store.enroll("127.0.0.1", device, secret, token, nonce)
            return True
        except EventError:
            return False

    with ThreadPoolExecutor(max_workers=4) as pool:
        assert sum(pool.map(lambda _: register(), range(4))) == 1
    expired = store.challenge("127.0.0.1")["challenge"]
    with store.connect() as db:
        db.execute("UPDATE enrollment_challenges SET expires=0")
    with pytest.raises(EventError):
        store.enroll("127.0.0.1", device, secret, expired, proof(expired))
    token = store.challenge("127.0.0.1")["challenge"]
    with pytest.raises(EventError, match="INVALID_CHALLENGE"):
        store.enroll("127.0.0.2", device, secret, token, proof(token))
    store.revoke(device)
    with pytest.raises(EventError, match="DEVICE_REVOKED"):
        store.enroll("127.0.0.1", device, secret, token, proof(token))
    identity = enroll(store)
    with store.connect() as db:
        db.execute(
            "INSERT INTO quotas VALUES (?, ?, 200)",
            (identity["device_id"], int(time.time()) // 86400),
        )
    with TestClient(create_app(tmp_path)) as client:
        assert (
            post(
                client, identity, "/v1/reputation", {"phone": "+84901234567"}
            ).status_code
            == 429
        )
        assert client.get("/health/live").json() == {"status": "ok"}


def test_challenge_quota_and_invalid_proof(tmp_path):
    with TestClient(create_app(tmp_path)) as client:
        challenge = client.post("/v1/challenge").json()["challenge"]
        secret = secrets.token_hex(32)
        identity = hashlib.sha256(bytes.fromhex(secret)).hexdigest()[:32]
        invalid = next(
            i
            for i in range(10)
            if not hashlib.sha256(f"{challenge}:{i}".encode())
            .digest()
            .startswith(b"\0\0")
        )
        assert (
            client.post(
                "/v1/enroll",
                json={
                    "device_id": identity,
                    "secret_hex": secret,
                    "challenge": challenge,
                    "nonce": invalid,
                },
            ).status_code
            == 401
        )
        for _ in range(29):
            assert client.post("/v1/challenge").status_code == 200
        assert client.post("/v1/challenge").status_code == 429


def test_existing_data_never_silently_gets_new_key(tmp_path):
    store = CommunityStore(tmp_path)
    (tmp_path / "phone.key").unlink()
    with pytest.raises(ValueError, match="key missing"):
        CommunityStore(tmp_path)
    assert store.database.exists()


def test_newer_schema_is_rejected(tmp_path):
    store = CommunityStore(tmp_path)
    with store.connect() as db:
        db.execute("PRAGMA user_version=99")
    with pytest.raises(ValueError, match="Unsupported"):
        CommunityStore(tmp_path)


def test_direct_clients_cannot_spoof_proxy_ip_for_enrollment(tmp_path):
    with TestClient(create_app(tmp_path)) as client:
        for index in range(30):
            assert (
                client.post(
                    "/v1/challenge",
                    headers={"CF-Connecting-IP": f"203.0.113.{index + 1}"},
                ).status_code
                == 200
            )
        assert (
            client.post(
                "/v1/challenge", headers={"CF-Connecting-IP": "203.0.113.99"}
            ).status_code
            == 429
        )
