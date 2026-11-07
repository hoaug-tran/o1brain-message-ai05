Set-Location $PSScriptRoot
uv run --offline --no-sync alembic upgrade head
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
uv run --offline --no-sync uvicorn ai05.interfaces.http:app --host 127.0.0.1 --port 8000 --no-access-log
