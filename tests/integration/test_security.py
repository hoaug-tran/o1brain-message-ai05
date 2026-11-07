import asyncio
import json
import subprocess
import sys
from pathlib import Path

from fastapi.testclient import TestClient
from sqlalchemy import create_engine, inspect

from ai05.config import Settings
from ai05.infrastructure.events import SCHEMA
from ai05.interfaces.http import LimitsMiddleware, create_app


def test_unexpected_error_never_echoes_or_logs_sms(caplog) -> None:
    app = create_app()
    with TestClient(app, raise_server_exceptions=False) as client:

        def broken(*args):
            raise RuntimeError("private-OTP-991122")

        app.state.registry.classify = broken
        response = client.post(
            "/api/v1/demo/predict", json={"text": "private-OTP-991122"}
        )
        assert response.status_code == 500
        assert "private-OTP-991122" not in response.text + caplog.text


def test_migration_matches_runtime_schema(tmp_path: Path, monkeypatch) -> None:
    url = f"sqlite:///{(tmp_path / 'migrated.db').as_posix()}"
    monkeypatch.setenv("AI05_DATABASE_URL", url)
    result = subprocess.run(
        [sys.executable, "-m", "alembic", "upgrade", "head"],
        capture_output=True,
        text=True,
    )
    assert result.returncode == 0, result.stderr
    engine = create_engine(url)
    inspector = inspect(engine)
    for name, table in SCHEMA.tables.items():
        assert {column["name"] for column in inspector.get_columns(name)} == set(
            table.c.keys()
        )
    assert all(
        name not in {"text", "sender", "phone", "raw_text"}
        for table in SCHEMA.tables.values()
        for name in table.c.keys()
    )
    engine.dispose()


def test_chunked_body_limit_without_content_length() -> None:
    sent = []
    messages = iter(
        [
            {"type": "http.request", "body": b"x" * 5000, "more_body": True},
            {"type": "http.request", "body": b"x" * 4000, "more_body": False},
        ]
    )

    async def receive():
        return next(messages)

    async def send(message):
        sent.append(message)

    async def inner(scope, receive, send):
        raise AssertionError("Oversized body reached application")

    asyncio.run(LimitsMiddleware(inner)({"type": "http"}, receive, send))
    assert sent[0]["status"] == 413
    assert json.loads(sent[1]["body"])["error"]["code"] == "PAYLOAD_TOO_LARGE"


def test_oversized_unicode_and_control_rejection() -> None:
    with TestClient(create_app()) as client:
        response = client.post(
            "/api/v1/demo/predict",
            content=json.dumps({"text": "😀" * 1600}, ensure_ascii=False).encode(),
            headers={"Content-Type": "application/json"},
        )
        assert response.status_code == 400
        assert "😀" not in response.text


def test_missing_migration_feed_fails_without_fabricated_events(tmp_path: Path) -> None:
    settings = Settings(database_url=f"sqlite:///{(tmp_path / 'fresh.db').as_posix()}")
    with TestClient(create_app(settings=settings)) as client:
        assert client.get("/health/ready").status_code == 200
        assert client.get("/api/v1/demo/events").status_code == 503


def test_invalid_unicode_never_becomes_server_error():
    with TestClient(create_app()) as client:
        response = client.post(
            "/api/v1/demo/predict",
            content=b'{"text":"\\ud800"}',
            headers={"Content-Type": "application/json"},
        )
        assert response.status_code in {400, 422}
        assert "classification" not in response.json()
