import ipaddress
import os
import sqlite3
from pathlib import Path
from typing import Literal

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field
from starlette.concurrency import run_in_threadpool

from ai05.infrastructure.community import CommunityStore
from ai05.infrastructure.events import EventError
from ai05.interfaces.http import LimitsMiddleware


class Enrollment(BaseModel):
    model_config = ConfigDict(extra="forbid")
    device_id: str = Field(pattern=r"^[a-f0-9]{32}$")
    secret_hex: str = Field(pattern=r"^[a-f0-9]{64}$")
    challenge: str = Field(pattern=r"^[a-f0-9]{64}$")
    nonce: int = Field(ge=0, le=16777215, strict=True)


class Sender(BaseModel):
    model_config = ConfigDict(extra="forbid")
    phone: str = Field(pattern=r"^\+[1-9][0-9]{7,14}$")


class Report(Sender):
    category: Literal["spam", "phishing", "advertising"]


def create_app(directory: Path | None = None) -> FastAPI:
    store = CommunityStore(
        directory or Path(os.environ.get("O1BRAIN_COMMUNITY_DATA", "private/community"))
    )
    app = FastAPI(
        title="O1Brain Community", docs_url=None, redoc_url=None, openapi_url=None
    )
    app.add_middleware(LimitsMiddleware)
    trust_cloudflare = os.environ.get("O1BRAIN_TRUST_CLOUDFLARE", "0") == "1"

    def peer(request: Request) -> str:
        if trust_cloudflare:
            try:
                return str(
                    ipaddress.ip_address(request.headers.get("cf-connecting-ip", ""))
                )
            except ValueError:
                raise EventError("INVALID_PEER", 400) from None
        if request.client is None:
            raise EventError("INVALID_PEER", 400)
        return request.client.host

    @app.exception_handler(EventError)
    async def event_error(request: Request, exc: EventError) -> JSONResponse:
        return JSONResponse({"error": {"code": exc.code}}, status_code=exc.status)

    @app.exception_handler(RequestValidationError)
    async def invalid(request: Request, exc: RequestValidationError) -> JSONResponse:
        return JSONResponse({"error": {"code": "INVALID_REQUEST"}}, status_code=422)

    @app.exception_handler(sqlite3.Error)
    async def unavailable(request: Request, exc: sqlite3.Error) -> JSONResponse:
        return JSONResponse({"error": {"code": "STORAGE_UNAVAILABLE"}}, status_code=503)

    @app.get("/health/live")
    def health() -> dict[str, str]:
        with store.connect() as db:
            db.execute("SELECT 1 FROM devices LIMIT 1")
        return {"status": "ok"}

    @app.post("/v1/challenge")
    def challenge(request: Request) -> dict[str, str | int]:
        return store.challenge(peer(request))

    @app.post("/v1/enroll")
    def enroll(request: Request, payload: Enrollment) -> dict[str, str]:
        return store.enroll(
            peer(request),
            payload.device_id,
            payload.secret_hex,
            payload.challenge,
            payload.nonce,
        )

    async def operation(
        request: Request, payload: Sender, category: str | None = None
    ) -> dict:
        return await run_in_threadpool(
            store.operate,
            dict(request.headers),
            request.url.path,
            await request.body(),
            payload.phone,
            category,
        )

    @app.post("/v1/reports")
    async def report(request: Request, payload: Report) -> dict:
        return await operation(request, payload, payload.category)

    @app.post("/v1/reports/retract")
    async def retract(request: Request, payload: Sender) -> dict:
        return await operation(request, payload)

    @app.post("/v1/reputation")
    async def reputation(request: Request, payload: Sender) -> dict:
        return await operation(request, payload)

    return app
