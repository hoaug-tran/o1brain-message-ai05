import hashlib
import hmac
import re
import secrets
import sqlite3
import time
from collections.abc import Iterator
from contextlib import contextmanager
from datetime import datetime
from pathlib import Path

from ai05.infrastructure.events import EventError, signature


class CommunityStore:
    def __init__(self, directory: Path) -> None:
        directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.database = directory / "community.db"
        key_path = directory / "phone.key"
        if self.database.exists() and not key_path.exists():
            raise ValueError("Community key missing; restore matching backup")
        try:
            with key_path.open("xb") as handle:
                key_path.chmod(0o600)
                handle.write(secrets.token_bytes(32))
        except FileExistsError:
            pass
        self.key = key_path.read_bytes()
        if len(self.key) != 32:
            raise ValueError("Invalid community key")
        with self.connect() as db:
            if db.execute("PRAGMA user_version").fetchone()[0] not in {0, 1, 2}:
                raise ValueError("Unsupported community database version")
            db.executescript("""
                CREATE TABLE IF NOT EXISTS devices (
                    id TEXT PRIMARY KEY, secret TEXT NOT NULL,
                    enabled INTEGER NOT NULL DEFAULT 1);
                CREATE TABLE IF NOT EXISTS enrollment_challenges (
                    digest TEXT PRIMARY KEY, peer TEXT NOT NULL, expires INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS enrollment_quotas (
                    peer TEXT NOT NULL, bucket INTEGER NOT NULL, kind TEXT NOT NULL,
                    count INTEGER NOT NULL, PRIMARY KEY(peer,bucket,kind));
                DROP TABLE IF EXISTS invites;
                CREATE TABLE IF NOT EXISTS reports (
                    device TEXT NOT NULL, phone TEXT NOT NULL,
                    category TEXT NOT NULL, created INTEGER NOT NULL,
                    PRIMARY KEY (device, phone));
                CREATE INDEX IF NOT EXISTS report_phone ON reports(phone);
                CREATE TABLE IF NOT EXISTS nonces (
                    device TEXT NOT NULL, nonce TEXT NOT NULL, expires INTEGER NOT NULL,
                    PRIMARY KEY(device, nonce));
                CREATE TABLE IF NOT EXISTS quotas (
                    device TEXT NOT NULL, bucket INTEGER NOT NULL, count INTEGER NOT NULL,
                    PRIMARY KEY(device, bucket));
                PRAGMA user_version=2;
            """)
        self.database.chmod(0o600)

    @contextmanager
    def connect(self) -> Iterator[sqlite3.Connection]:
        db = sqlite3.connect(self.database, timeout=5, isolation_level="IMMEDIATE")
        try:
            db.execute("PRAGMA foreign_keys=ON")
            with db:
                yield db
        finally:
            db.close()

    def peer_digest(self, peer: str) -> str:
        return hmac.new(self.key, b"peer\0" + peer.encode(), hashlib.sha256).hexdigest()

    def enrollment_quota(
        self, db: sqlite3.Connection, peer: str, kind: str, bucket: int, limit: int
    ) -> None:
        row = db.execute(
            "SELECT count FROM enrollment_quotas WHERE peer=? AND bucket=? AND kind=?",
            (peer, bucket, kind),
        ).fetchone()
        if row and row[0] >= limit:
            raise EventError("ENROLLMENT_QUOTA", 429)
        db.execute(
            "INSERT INTO enrollment_quotas VALUES (?, ?, ?, 1) ON CONFLICT(peer,bucket,kind) DO UPDATE SET count=count+1",
            (peer, bucket, kind),
        )

    def challenge(self, peer: str) -> dict[str, str | int]:
        now = int(time.time())
        token = secrets.token_hex(32)
        digest = self.peer_digest(peer)
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            db.execute("DELETE FROM enrollment_challenges WHERE expires < ?", (now,))
            db.execute(
                "DELETE FROM enrollment_quotas WHERE (kind='challenge' AND bucket < ?) OR (kind='device' AND bucket < ?)",
                (now // 3600 - 1, now // 86400 - 1),
            )
            self.enrollment_quota(db, digest, "challenge", now // 3600, 30)
            if (
                db.execute("SELECT COUNT(*) FROM enrollment_challenges").fetchone()[0]
                >= 10000
            ):
                raise EventError("ENROLLMENT_BUSY", 503)
            db.execute(
                "INSERT INTO enrollment_challenges VALUES (?, ?, ?)",
                (hashlib.sha256(token.encode()).hexdigest(), digest, now + 300),
            )
        return {"challenge": token, "zero_bytes": 2, "expires_in": 300}

    def enroll(
        self, peer: str, device: str, secret: str, challenge: str, nonce: int
    ) -> dict[str, str]:
        if (
            not re.fullmatch(r"[0-9a-f]{32}", device)
            or not re.fullmatch(r"[0-9a-f]{64}", secret)
            or not re.fullmatch(r"[0-9a-f]{64}", challenge)
            or not 0 <= nonce <= 16777215
        ):
            raise EventError("INVALID_ENROLLMENT", 422)
        if hashlib.sha256(bytes.fromhex(secret)).hexdigest()[:32] != device:
            raise EventError("INVALID_DEVICE", 422)
        if (
            not hashlib.sha256(f"{challenge}:{nonce}".encode())
            .digest()
            .startswith(b"\0\0")
        ):
            raise EventError("INVALID_PROOF", 401)
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            used = db.execute(
                "DELETE FROM enrollment_challenges WHERE digest=? AND peer=? AND expires>=?",
                (
                    hashlib.sha256(challenge.encode()).hexdigest(),
                    self.peer_digest(peer),
                    int(time.time()),
                ),
            )
            if used.rowcount != 1:
                raise EventError("INVALID_CHALLENGE", 401)
            existing = db.execute(
                "SELECT secret, enabled FROM devices WHERE id=?", (device,)
            ).fetchone()
            if existing:
                if not existing[1] or not hmac.compare_digest(existing[0], secret):
                    raise EventError("DEVICE_REVOKED", 403)
            else:
                self.enrollment_quota(
                    db, self.peer_digest(peer), "device", int(time.time()) // 86400, 20
                )
                db.execute(
                    "INSERT INTO devices(id, secret) VALUES (?, ?)", (device, secret)
                )
        return {"device_id": device}

    def revoke(self, device: str) -> None:
        with self.connect() as db:
            db.execute("UPDATE devices SET enabled=0 WHERE id=?", (device,))
            db.execute("DELETE FROM reports WHERE device=?", (device,))

    def phone_digest(self, phone: str) -> str:
        if not re.fullmatch(r"\+[1-9][0-9]{7,14}", phone):
            raise EventError("INVALID_PHONE", 422)
        return hmac.new(self.key, phone.encode(), hashlib.sha256).hexdigest()

    def operate(
        self,
        headers: dict[str, str],
        path: str,
        body: bytes,
        phone: str,
        category: str | None = None,
    ) -> dict:
        digest = self.phone_digest(phone)
        device = headers.get("x-device-id", "")
        nonce = headers.get("x-nonce", "")
        timestamp = headers.get("x-timestamp", "")
        supplied = headers.get("x-signature", "")
        request_key = headers.get("x-idempotency-key", "")
        if (
            not all(
                re.fullmatch(r"[A-Za-z0-9_-]{8,128}", s)
                for s in (device, nonce, request_key)
            )
            or len(timestamp) > 40
        ):
            raise EventError("UNAUTHORIZED", 401)
        now = int(time.time())
        try:
            parsed = datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
            if parsed.tzinfo is None or abs(parsed.timestamp() - now) > 300:
                raise ValueError("Expired")
        except (ValueError, OverflowError):
            raise EventError("UNAUTHORIZED", 401) from None
        if path not in {"/v1/reports", "/v1/reports/retract", "/v1/reputation"}:
            raise EventError("INVALID_PATH", 400)
        if category is not None and category not in {"spam", "phishing", "advertising"}:
            raise EventError("INVALID_CATEGORY", 422)
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute(
                "SELECT secret FROM devices WHERE id=? AND enabled=1", (device,)
            ).fetchone()
            if row is None:
                raise EventError("UNAUTHORIZED", 401)
            expected = signature(
                bytes.fromhex(row[0]),
                device,
                "POST",
                path,
                timestamp,
                nonce,
                request_key,
                body,
            )
            if not re.fullmatch(
                r"v1=[0-9a-f]{64}", supplied
            ) or not hmac.compare_digest(expected, supplied):
                raise EventError("UNAUTHORIZED", 401)
            db.execute("DELETE FROM nonces WHERE expires < ?", (now,))
            db.execute("DELETE FROM quotas WHERE bucket < ?", (now // 86400 - 1,))
            db.execute("DELETE FROM reports WHERE created < ?", (now - 90 * 86400,))
            try:
                db.execute(
                    "INSERT INTO nonces VALUES (?, ?, ?)", (device, nonce, now + 600)
                )
            except sqlite3.IntegrityError:
                raise EventError("REPLAY_DETECTED", 409) from None
            bucket = now // 86400
            db.execute(
                "INSERT INTO quotas VALUES (?, ?, 1) ON CONFLICT(device,bucket) DO UPDATE SET count=count+1",
                (device, bucket),
            )
            if (
                db.execute(
                    "SELECT count FROM quotas WHERE device=? AND bucket=?",
                    (device, bucket),
                ).fetchone()[0]
                > 200
            ):
                raise EventError("QUOTA_EXCEEDED", 429)
            if path == "/v1/reports":
                if category is None:
                    raise EventError("INVALID_CATEGORY", 422)
                db.execute(
                    "INSERT INTO reports VALUES (?, ?, ?, ?) ON CONFLICT(device,phone) DO UPDATE SET category=excluded.category,created=excluded.created",
                    (device, digest, category, now),
                )
            elif path == "/v1/reports/retract":
                db.execute(
                    "DELETE FROM reports WHERE device=? AND phone=?", (device, digest)
                )
            counts = dict(
                db.execute(
                    "SELECT category, COUNT(*) FROM reports WHERE phone=? GROUP BY category",
                    (digest,),
                )
            )
            own = db.execute(
                "SELECT category FROM reports WHERE phone=? AND device=?",
                (digest, device),
            ).fetchone()
        total = sum(counts.values())
        return {
            "reporters": total,
            "categories": counts,
            "own_category": own[0] if own else None,
            "status": "reported" if total >= 3 else "insufficient_reports",
            "retention_days": 90,
        }
