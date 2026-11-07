import hashlib
import hmac
import json
import re
import threading
import time
from datetime import datetime
from pathlib import Path

from sqlalchemy import (
    Column,
    Float,
    Integer,
    MetaData,
    String,
    Table,
    UniqueConstraint,
    create_engine,
    delete,
    insert,
    select,
    text,
)
from sqlalchemy.engine import Connection
from sqlalchemy.exc import SQLAlchemyError

from ai05.application import predict
from ai05.infrastructure.models import ModelRegistry

SCHEMA = MetaData()
EVENTS = Table(
    "sms_events",
    SCHEMA,
    Column("source_id", String, primary_key=True),
    Column("event_id", String, primary_key=True),
    Column("source_kind", String, nullable=False),
    Column("message_origin", String, nullable=False),
    Column("predicted_label", String, nullable=False),
    Column("policy_status", String, nullable=False),
    Column("model_id", String, nullable=False),
    Column("model_version", String, nullable=False),
    Column("score", Float, nullable=False),
    Column("score_kind", String, nullable=False),
    Column("expires_at", Integer, nullable=False),
    Column("created_at", Integer, nullable=False),
)
RECEIPTS = Table(
    "idempotency_receipts",
    SCHEMA,
    Column("source_id", String, primary_key=True),
    Column("idempotency_key", String, primary_key=True),
    Column("body_hmac", String, nullable=False),
    Column("response", String, nullable=False),
    Column("event_id", String, nullable=False),
    Column("expires_at", Integer, nullable=False),
    UniqueConstraint("source_id", "event_id"),
)
NONCES = Table(
    "nonces",
    SCHEMA,
    Column("source_id", String, primary_key=True),
    Column("nonce", String, primary_key=True),
    Column("expires_at", Integer, nullable=False),
)


class EventError(ValueError):
    def __init__(self, code: str, status: int) -> None:
        self.code = code
        self.status = status
        super().__init__(code)


def canonical(
    source: str,
    method: str,
    path: str,
    timestamp: str,
    nonce: str,
    key: str,
    body: bytes,
) -> bytes:
    return "\n".join(
        (
            "v1",
            source,
            method,
            path,
            timestamp,
            nonce,
            key,
            hashlib.sha256(body).hexdigest(),
        )
    ).encode()


def signature(
    secret: bytes,
    source: str,
    method: str,
    path: str,
    timestamp: str,
    nonce: str,
    key: str,
    body: bytes,
) -> str:
    return (
        "v1="
        + hmac.new(
            secret,
            canonical(source, method, path, timestamp, nonce, key, body),
            hashlib.sha256,
        ).hexdigest()
    )


class EventStore:
    def __init__(self, database_url: str, keystore: Path) -> None:
        self.engine = create_engine(
            database_url, connect_args={"check_same_thread": False, "timeout": 10}
        )

        self.lock = threading.Lock()
        self.keystore = keystore
        self.ready: bool = False
        try:
            with self.engine.connect() as connection:
                self.ready = (
                    connection.execute(
                        text("SELECT version_num FROM alembic_version")
                    ).scalar_one()
                    == "0001"
                )
        except SQLAlchemyError:
            self.ready = False

    def credentials(self, source: str) -> bytes:
        try:
            records = json.loads(self.keystore.read_text(encoding="utf-8"))
            record = records[source]
            if (
                record["allowed_source_kind"] != "synthetic_demo"
                or not record["enabled"]
            ):
                raise ValueError("Disabled or forbidden source")
            secret = bytes.fromhex(record["secret_hex"])
            if len(secret) != 32:
                raise ValueError("Invalid source secret")
            return secret
        except (OSError, KeyError, ValueError, TypeError):
            raise EventError("UNAUTHORIZED_SOURCE", 401) from None

    def authenticate(
        self, headers: dict[str, str], method: str, path: str, body: bytes
    ) -> tuple[str, str, str, bytes]:
        source = headers.get("x-device-id", "")
        timestamp = headers.get("x-timestamp", "")
        nonce = headers.get("x-nonce", "")
        key = headers.get("x-idempotency-key", "")
        supplied = headers.get("x-signature", "")
        if (
            not all(
                re.fullmatch(r"[A-Za-z0-9_-]{8,128}", value)
                for value in (source, nonce, key)
            )
            or len(timestamp) > 40
        ):
            raise EventError("UNAUTHORIZED_SOURCE", 401)
        secret = self.credentials(source)
        try:
            parsed = datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
            if parsed.tzinfo is None or abs(time.time() - parsed.timestamp()) > 300:
                raise ValueError("Expired timestamp")
        except (ValueError, OverflowError):
            raise EventError("UNAUTHORIZED_SOURCE", 401) from None
        expected = signature(secret, source, method, path, timestamp, nonce, key, body)
        if not re.fullmatch(r"v1=[0-9a-f]{64}", supplied) or not hmac.compare_digest(
            expected, supplied
        ):
            raise EventError("UNAUTHORIZED_SOURCE", 401)
        return source, nonce, key, secret

    def cleanup(self, connection: Connection, now: int) -> None:
        for table in (EVENTS, RECEIPTS, NONCES):
            connection.execute(delete(table).where(table.c.expires_at <= now))

    def ingest(
        self,
        headers: dict[str, str],
        body: bytes,
        payload: dict,
        registry: ModelRegistry,
    ) -> dict:
        source, nonce, key, secret = self.authenticate(
            headers, "POST", "/api/v1/sms-events", body
        )
        if (
            payload["source_kind"] != "synthetic_demo"
            or payload["message_origin"] != "synthetic"
        ):
            raise EventError("FORBIDDEN_SOURCE_KIND", 403)
        now = int(time.time())
        body_hmac = hmac.new(secret, b"receipt\0" + body, hashlib.sha256).hexdigest()
        if not self.lock.acquire(blocking=False):
            raise EventError("INGEST_BUSY", 503)
        try:
            with self.engine.begin() as connection:
                self.cleanup(connection, now)
                if connection.execute(
                    select(NONCES).where(
                        NONCES.c.source_id == source, NONCES.c.nonce == nonce
                    )
                ).first():
                    raise EventError("REPLAY_DETECTED", 409)
                connection.execute(
                    insert(NONCES).values(
                        source_id=source, nonce=nonce, expires_at=now + 600
                    )
                )
                existing = (
                    connection.execute(
                        select(RECEIPTS).where(
                            RECEIPTS.c.source_id == source,
                            RECEIPTS.c.idempotency_key == key,
                        )
                    )
                    .mappings()
                    .first()
                )
                if existing:
                    if not hmac.compare_digest(existing["body_hmac"], body_hmac):
                        raise EventError("IDEMPOTENCY_CONFLICT", 409)
                    response = json.loads(existing["response"])
                    response["is_duplicate"] = True
                    return response
                if connection.execute(
                    select(RECEIPTS).where(
                        RECEIPTS.c.source_id == source,
                        RECEIPTS.c.event_id == payload["event_id"],
                    )
                ).first():
                    raise EventError("EVENT_ID_CONFLICT", 409)
                count = connection.execute(
                    select(EVENTS.c.event_id)
                    .where(EVENTS.c.source_id == source)
                    .limit(200)
                ).all()
                if len(count) >= 200:
                    raise EventError("EVENT_QUOTA_EXCEEDED", 429)
                prediction = predict(
                    registry, payload["text"], payload["language_hint"]
                )
                classification = prediction["classification"]
                model = prediction["model"]
                policy = prediction["policy"]
                connection.execute(
                    insert(EVENTS).values(
                        source_id=source,
                        event_id=payload["event_id"],
                        source_kind="synthetic_demo",
                        message_origin="synthetic",
                        predicted_label=classification["label"],
                        policy_status=policy["status"],
                        model_id=model["id"],
                        model_version=model["version"],
                        score=classification["score"],
                        score_kind=classification["score_kind"],
                        created_at=now,
                        expires_at=now + 3600,
                    )
                )
                response = {
                    "event_id": payload["event_id"],
                    "status": "accepted",
                    "is_duplicate": False,
                    "model_id": model["id"],
                    "prediction_id": prediction["prediction_id"],
                    "alert_policy": policy["status"],
                    "persisted_raw_text": False,
                    "source_kind": "synthetic_demo",
                }
                connection.execute(
                    insert(RECEIPTS).values(
                        source_id=source,
                        idempotency_key=key,
                        event_id=payload["event_id"],
                        body_hmac=body_hmac,
                        response=json.dumps(response),
                        expires_at=now + 3600,
                    )
                )
                return response
        finally:
            self.lock.release()

    def list_demo(self) -> list[dict]:
        now = int(time.time())
        with self.lock, self.engine.begin() as connection:
            self.cleanup(connection, now)
            rows = (
                connection.execute(
                    select(EVENTS)
                    .where(EVENTS.c.source_kind == "synthetic_demo")
                    .order_by(EVENTS.c.created_at.desc(), EVENTS.c.event_id)
                    .limit(50)
                )
                .mappings()
                .all()
            )
            return [
                {key: value for key, value in dict(row).items() if key != "source_id"}
                for row in rows
            ]

    def authenticated_operation(
        self,
        headers: dict[str, str],
        method: str,
        path: str,
        body: bytes,
        payload: dict | None,
        registry: ModelRegistry,
    ) -> dict:
        source, nonce, _, _ = self.authenticate(headers, method, path, body)
        now = int(time.time())
        if not self.lock.acquire(blocking=False):
            raise EventError("SOURCE_BUSY", 503)
        try:
            with self.engine.begin() as connection:
                self.cleanup(connection, now)
                if connection.execute(
                    select(NONCES).where(
                        NONCES.c.source_id == source, NONCES.c.nonce == nonce
                    )
                ).first():
                    raise EventError("REPLAY_DETECTED", 409)
                connection.execute(
                    insert(NONCES).values(
                        source_id=source, nonce=nonce, expires_at=now + 600
                    )
                )
                if method == "GET":
                    rows = (
                        connection.execute(
                            select(EVENTS)
                            .where(EVENTS.c.source_id == source)
                            .order_by(EVENTS.c.created_at.desc(), EVENTS.c.event_id)
                            .limit(50)
                        )
                        .mappings()
                        .all()
                    )
                    return {
                        "events": [
                            {
                                key: value
                                for key, value in dict(row).items()
                                if key != "source_id"
                            }
                            for row in rows
                        ]
                    }
                if payload is None:
                    raise EventError("INVALID_REQUEST", 400)
                return predict(
                    registry,
                    payload["text"],
                    payload["language_hint"],
                    registry.active
                    if payload["model_id"] == "english_improved"
                    else payload["model_id"],
                )
        finally:
            self.lock.release()

    def delete_own(self, headers: dict[str, str], body: bytes) -> dict[str, int]:
        source, nonce, _, _ = self.authenticate(
            headers, "DELETE", "/api/v1/events", body
        )
        now = int(time.time())
        with self.lock, self.engine.begin() as connection:
            self.cleanup(connection, now)
            if connection.execute(
                select(NONCES).where(
                    NONCES.c.source_id == source, NONCES.c.nonce == nonce
                )
            ).first():
                raise EventError("REPLAY_DETECTED", 409)
            connection.execute(
                insert(NONCES).values(
                    source_id=source, nonce=nonce, expires_at=now + 600
                )
            )
            result = connection.execute(
                delete(EVENTS).where(EVENTS.c.source_id == source)
            )

            return {"deleted": result.rowcount}

    def close(self) -> None:
        self.engine.dispose()
