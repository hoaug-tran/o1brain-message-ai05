#!/bin/sh
set -eu
cd "$(dirname "$0")"
uv run --offline --no-sync alembic upgrade head
exec uv run --offline --no-sync uvicorn ai05.interfaces.http:app --host 127.0.0.1 --port 8000 --no-access-log
