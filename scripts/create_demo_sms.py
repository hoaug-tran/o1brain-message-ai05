import argparse
import json
import secrets
from datetime import UTC, datetime
from pathlib import Path
from uuid import uuid4

import httpx

from ai05.infrastructure.events import signature


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Provision a local synthetic-only source or send one signed synthetic event."
    )
    parser.add_argument("--provision", action="store_true")
    parser.add_argument("--source", default="local-judge-demo")
    parser.add_argument(
        "--keystore", type=Path, default=Path("private/device_keys.json")
    )
    parser.add_argument("--url", default="http://127.0.0.1:8000")
    parser.add_argument("--delete", action="store_true")
    args = parser.parse_args()
    if args.provision:
        if args.keystore.exists():
            raise SystemExit(
                "Keystore exists; refusing overwrite. Reuse existing source or review rotation manually."
            )
        args.keystore.parent.mkdir(parents=True, exist_ok=True)
        args.keystore.write_text(
            json.dumps(
                {
                    args.source: {
                        "secret_hex": secrets.token_hex(32),
                        "allowed_source_kind": "synthetic_demo",
                        "enabled": True,
                    }
                },
                indent=2,
            ),
            encoding="utf-8",
        )
        print(
            "Created local synthetic-only credentials; keep private and never bundle."
        )
        return
    if not args.url.startswith("http://127.0.0.1:"):
        raise SystemExit("Synthetic demo client limited to localhost")
    records = json.loads(args.keystore.read_text(encoding="utf-8"))
    secret = bytes.fromhex(records[args.source]["secret_hex"])
    timestamp = datetime.now(UTC).isoformat()
    nonce = secrets.token_hex(16)
    key = str(uuid4())
    method, path = (
        ("DELETE", "/api/v1/events") if args.delete else ("POST", "/api/v1/sms-events")
    )
    payload = {
        "event_id": str(uuid4()),
        "source_kind": "synthetic_demo",
        "message_origin": "synthetic",
        "received_at_utc": timestamp,
        "text": "Synthetic demo: claim your free prize today",
        "language_hint": "en",
    }
    body = b"" if args.delete else json.dumps(payload, separators=(",", ":")).encode()
    headers = {
        "Content-Type": "application/json",
        "X-Device-ID": args.source,
        "X-Timestamp": timestamp,
        "X-Nonce": nonce,
        "X-Idempotency-Key": key,
        "X-Signature": signature(
            secret, args.source, method, path, timestamp, nonce, key, body
        ),
    }
    response = httpx.request(
        method, args.url + path, content=body, headers=headers, timeout=10
    )
    response.raise_for_status()
    print(json.dumps(response.json(), indent=2))


if __name__ == "__main__":
    main()
