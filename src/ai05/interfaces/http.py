import asyncio
import json
import threading
import time
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from pathlib import Path
from uuid import uuid4

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse
from jinja2 import Environment, FileSystemLoader, select_autoescape
from sqlalchemy.exc import SQLAlchemyError
from starlette.exceptions import HTTPException
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from ai05.application import predict
from ai05.config import Settings
from ai05.domain import InvalidMessage, ModelUnavailable, UnsupportedLanguage
from ai05.infrastructure.events import EventError, EventStore
from ai05.infrastructure.models import ModelRegistry
from ai05.interfaces.schemas import (
    ComparisonResponse,
    EventRequest,
    PredictionRequest,
    PredictionResponse,
)

ROOT = Path(__file__).resolve().parents[3]


def error(code: str, message: str, status: int, request_id: str) -> JSONResponse:
    return JSONResponse(
        {
            "error": {
                "code": code,
                "message": message,
                "request_id": request_id,
                "details": None,
            }
        },
        status_code=status,
    )


class LimitsMiddleware:
    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        request_id = str(uuid4())
        scope["request_id"] = request_id
        body = bytearray()
        while True:
            try:
                message = await asyncio.wait_for(receive(), timeout=5)
            except TimeoutError:
                await error(
                    "REQUEST_TIMEOUT", "Request body timed out", 408, request_id
                )(scope, receive, send)
                return
            if message["type"] == "http.disconnect":
                return
            chunk = message.get("body", b"")
            if len(body) + len(chunk) > 8192:
                await error(
                    "PAYLOAD_TOO_LARGE", "Request exceeds 8192 bytes", 413, request_id
                )(scope, receive, send)
                return
            body.extend(chunk)
            if not message.get("more_body", False):
                break
        delivered = False

        async def bounded_receive() -> Message:
            nonlocal delivered
            if not delivered:
                delivered = True
                return {"type": "http.request", "body": bytes(body), "more_body": False}
            return await receive()

        response_started = False

        async def secure_send(message: Message) -> None:
            nonlocal response_started
            if message["type"] == "http.response.start":
                response_started = True
                message["headers"] += [
                    (b"x-request-id", request_id.encode()),
                    (b"x-content-type-options", b"nosniff"),
                    (b"referrer-policy", b"no-referrer"),
                    (b"cache-control", b"no-store"),
                    (
                        b"content-security-policy",
                        b"default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; frame-ancestors 'none'; base-uri 'none'",
                    ),
                ]
            await send(message)

        try:
            await self.app(scope, bounded_receive, secure_send)
        except Exception:
            if not response_started:
                await error("INTERNAL_ERROR", "Request failed", 500, request_id)(
                    scope, bounded_receive, secure_send
                )


def create_app(
    root: Path = ROOT,
    registry_path: Path | None = None,
    quota: int = 60,
    settings: Settings | None = None,
) -> FastAPI:
    settings = settings or Settings()

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        app.state.registry = ModelRegistry(registry_path or root / settings.registry)
        app.state.events = EventStore(
            settings.database_url, settings.hmac_keystore_path
        )
        try:
            yield
        finally:
            app.state.events.close()

    app = FastAPI(title="AI05 English SMS Research", version="0.1.0", lifespan=lifespan)
    app.add_middleware(LimitsMiddleware)
    slots = threading.BoundedSemaphore(2)
    clients: dict[str, tuple[int, int]] = {}

    @app.exception_handler(RequestValidationError)
    async def schema_error(
        request: Request, exc: RequestValidationError
    ) -> JSONResponse:
        return error(
            "INVALID_REQUEST",
            "Request schema invalid; only bounded English SMS inputs supported",
            422,
            request.scope["request_id"],
        )

    @app.exception_handler(HTTPException)
    async def http_error(request: Request, exc: HTTPException) -> JSONResponse:
        return error(
            "HTTP_ERROR",
            "Request unavailable",
            exc.status_code,
            request.scope["request_id"],
        )

    @app.exception_handler(Exception)
    async def internal_error(request: Request, exc: Exception) -> JSONResponse:
        return error(
            "INTERNAL_ERROR", "Request failed", 500, request.scope["request_id"]
        )

    def check_quota(request: Request) -> JSONResponse | None:
        ip = request.client.host if request.client else "local"
        minute = int(time.monotonic() // 60)
        previous, count = clients.get(ip, (minute, 0))
        count = count + 1 if previous == minute else 1
        if ip not in clients and len(clients) >= 1024:
            stale = [host for host, (window, _) in clients.items() if window != minute]
            for host in stale:
                del clients[host]
            if len(clients) >= 1024:
                return error(
                    "RATE_LIMIT_EXCEEDED",
                    "Demo capacity reached",
                    429,
                    request.scope["request_id"],
                )
        clients[ip] = (minute, count)
        if count > quota:
            return error(
                "RATE_LIMIT_EXCEEDED",
                "Demo quota exceeded; retry next minute",
                429,
                request.scope["request_id"],
            )
        return None

    async def infer(
        request: Request, body: PredictionRequest, compare: bool = False
    ) -> dict | JSONResponse:
        denied = check_quota(request)
        if denied is not None:
            return denied
        registry = request.app.state.registry
        if not registry.ready:
            return error(
                "MODEL_UNAVAILABLE",
                "Verified model unavailable",
                503,
                request.scope["request_id"],
            )

        def compute() -> dict:
            if not slots.acquire(blocking=False):
                raise ModelUnavailable("Inference busy")
            try:
                if compare:
                    return {
                        "input_id": str(uuid4()),
                        "dataset_scope": "expanded_english_experiment",
                        "comparisons": [
                            predict(
                                registry,
                                body.text,
                                body.language_hint,
                                "english_baseline",
                            ),
                            predict(
                                registry, body.text, body.language_hint, registry.active
                            ),
                        ],
                        "note": "Distinct models classified identical input; development-validation evidence only.",
                    }
                selected = (
                    registry.active
                    if body.model_id == "english_improved"
                    else body.model_id
                )
                return predict(registry, body.text, body.language_hint, selected)
            finally:
                slots.release()

        try:
            return await asyncio.wait_for(asyncio.to_thread(compute), timeout=3)
        except InvalidMessage:
            return error(
                "INVALID_MESSAGE",
                "Empty, oversized or invalid SMS text",
                400,
                request.scope["request_id"],
            )
        except UnsupportedLanguage:
            return error(
                "UNSUPPORTED_LANGUAGE",
                "Only English evaluated; not_evaluated for requested language",
                400,
                request.scope["request_id"],
            )
        except (ModelUnavailable, TimeoutError):
            return error(
                "MODEL_UNAVAILABLE",
                "Inference unavailable or timed out",
                503,
                request.scope["request_id"],
            )

    @app.get("/health/live")
    async def live() -> dict[str, str]:
        return {"status": "live"}

    @app.get("/health/ready", response_model=None)
    async def ready(request: Request) -> dict | JSONResponse:
        if not request.app.state.registry.ready:
            return error(
                "MODEL_UNAVAILABLE",
                "Verified model unavailable",
                503,
                request.scope["request_id"],
            )
        return {"status": "ready"}

    @app.get("/api/v1/models")
    async def models(request: Request) -> dict:
        registry = request.app.state.registry
        return {
            "models": [registry.public_info(model_id) for model_id in registry.models],
            "scope": "English only; no verified automatic language detection",
        }

    @app.get("/api/v1/models/active", response_model=None)
    async def active(request: Request) -> dict | JSONResponse:
        registry = request.app.state.registry
        if not registry.ready:
            return error(
                "MODEL_UNAVAILABLE",
                "Verified model unavailable",
                503,
                request.scope["request_id"],
            )
        return registry.public_info(registry.active)

    @app.post("/api/v1/demo/predict", response_model=PredictionResponse)
    async def prediction(
        request: Request, body: PredictionRequest
    ) -> dict | JSONResponse:
        return await infer(request, body)

    @app.post("/api/v1/demo/compare", response_model=ComparisonResponse)
    async def compare(request: Request, body: PredictionRequest) -> dict | JSONResponse:
        return await infer(request, body, compare=True)

    @app.get("/api/v1/demo/metrics", response_model=None)
    async def metrics(request: Request) -> dict | JSONResponse:
        registry = request.app.state.registry
        if not registry.ready:
            return error(
                "MODEL_UNAVAILABLE",
                "Verified metrics unavailable",
                503,
                request.scope["request_id"],
            )
        result = {
            "dataset_id": registry.comparison["dataset_id"],
            "split_id": registry.comparison["split_id"],
            "reports": {
                model_id: {
                    "metrics": report["metrics"],
                    "confusion": report["confusion"],
                    "support": report["support"],
                    "evaluation_scope": report["evaluation_scope"],
                }
                for model_id, report in registry.comparison["reports"].items()
            },
        }
        report_path = root / "results/report/evidence.json"
        if report_path.is_file():
            report = json.loads(report_path.read_text(encoding="utf-8"))
            result["research_reports"] = {
                name: {
                    **value,
                    "evaluation_scope": "expanded_grouped_development_holdout",
                }
                for name, value in report["reports"].items()
            }
        return result

    templates = Environment(
        loader=FileSystemLoader(root / "src/ai05/web"),
        autoescape=select_autoescape(["html"]),
    )

    @app.post("/api/v1/sms-events", response_model=None)
    async def ingest(request: Request, payload: EventRequest) -> dict | JSONResponse:
        denied = check_quota(request)
        if denied is not None:
            return denied
        store = request.app.state.events
        if not store.ready or not request.app.state.registry.ready:
            return error(
                "DEPENDENCY_UNAVAILABLE",
                "Event store or model not ready",
                503,
                request.scope["request_id"],
            )
        if payload.received_at_utc.tzinfo is None:
            return error(
                "INVALID_EVENT",
                "UTC-aware event timestamp required",
                422,
                request.scope["request_id"],
            )
        body = await request.body()
        try:
            return await asyncio.wait_for(
                asyncio.to_thread(
                    store.ingest,
                    dict(request.headers),
                    body,
                    payload.model_dump(mode="json"),
                    request.app.state.registry,
                ),
                timeout=3,
            )
        except EventError as exc:
            return error(
                exc.code,
                "Event request rejected",
                exc.status,
                request.scope["request_id"],
            )
        except (InvalidMessage, UnsupportedLanguage):
            return error(
                "NOT_EVALUATED",
                "Message outside supported English input limits",
                400,
                request.scope["request_id"],
            )
        except (SQLAlchemyError, ModelUnavailable, TimeoutError):
            return error(
                "DEPENDENCY_UNAVAILABLE",
                "Event processing unavailable; retry with same idempotency key and fresh nonce",
                503,
                request.scope["request_id"],
            )

    @app.get("/api/v1/demo/events", response_model=None)
    async def demo_events(request: Request) -> dict | JSONResponse:
        denied = check_quota(request)
        if denied is not None:
            return denied
        store = request.app.state.events
        if not store.ready:
            return error(
                "STORAGE_UNAVAILABLE",
                "Run local database migrations",
                503,
                request.scope["request_id"],
            )
        try:
            return {
                "events": await asyncio.to_thread(store.list_demo),
                "source_kind": "synthetic_demo",
                "retention_seconds": 3600,
            }
        except SQLAlchemyError:
            return error(
                "STORAGE_UNAVAILABLE",
                "Event feed unavailable",
                503,
                request.scope["request_id"],
            )

    async def authenticated(
        request: Request, payload: PredictionRequest | None = None
    ) -> dict | JSONResponse:
        denied = check_quota(request)
        if denied is not None:
            return denied
        store = request.app.state.events
        if not store.ready or not request.app.state.registry.ready:
            return error(
                "DEPENDENCY_UNAVAILABLE",
                "Event store or model unavailable",
                503,
                request.scope["request_id"],
            )
        try:
            return await asyncio.wait_for(
                asyncio.to_thread(
                    store.authenticated_operation,
                    dict(request.headers),
                    request.method,
                    request.url.path,
                    await request.body(),
                    payload.model_dump(mode="json") if payload else None,
                    request.app.state.registry,
                ),
                timeout=3,
            )
        except EventError as exc:
            return error(
                exc.code,
                "Authenticated request rejected",
                exc.status,
                request.scope["request_id"],
            )
        except (InvalidMessage, UnsupportedLanguage):
            return error(
                "NOT_EVALUATED",
                "Outside supported English input limits",
                400,
                request.scope["request_id"],
            )
        except (SQLAlchemyError, ModelUnavailable, TimeoutError):
            return error(
                "DEPENDENCY_UNAVAILABLE",
                "Authenticated operation unavailable",
                503,
                request.scope["request_id"],
            )

    @app.post("/api/v1/predictions", response_model=PredictionResponse)
    async def authenticated_prediction(
        request: Request, payload: PredictionRequest
    ) -> dict | JSONResponse:
        return await authenticated(request, payload)

    @app.get("/api/v1/events", response_model=None)
    async def own_events(request: Request) -> dict | JSONResponse:
        return await authenticated(request)

    @app.delete("/api/v1/events", response_model=None)
    async def delete_events(request: Request) -> dict | JSONResponse:
        denied = check_quota(request)
        if denied is not None:
            return denied
        store = request.app.state.events
        if not store.ready:
            return error(
                "STORAGE_UNAVAILABLE",
                "Event store unavailable",
                503,
                request.scope["request_id"],
            )
        try:
            return await asyncio.to_thread(
                store.delete_own, dict(request.headers), await request.body()
            )
        except EventError as exc:
            return error(
                exc.code,
                "Delete request rejected",
                exc.status,
                request.scope["request_id"],
            )
        except SQLAlchemyError:
            return error(
                "STORAGE_UNAVAILABLE",
                "Event store unavailable",
                503,
                request.scope["request_id"],
            )

    @app.get("/", response_class=HTMLResponse)
    async def home() -> str:
        registry = app.state.registry
        report = registry.comparison.get("reports", {}).get(registry.active, {})
        quality = json.loads((root / "data/manifests/data_quality.json").read_text())
        return templates.get_template("index.html").render(
            support=report.get("support", {}),
            confusion=report.get("confusion", {}),
            near_duplicates=quality["near_duplicate_audit"][
                "validation_rows_at_or_above_threshold"
            ],
        )

    @app.get("/assets/{name}")
    async def asset(name: str) -> FileResponse:
        if name not in {"app.js", "style.css"}:
            raise HTTPException(404)
        return FileResponse(root / "src/ai05/web" / name)

    @app.get("/figures/{name}")
    async def figure(name: str) -> FileResponse:
        if name not in {"class_distribution.png", "confusion_matrices.png"}:
            raise HTTPException(404)
        aliases = {
            "class_distribution.png": "01_classes.png",
            "confusion_matrices.png": "09_confusion.png",
        }
        return FileResponse(root / "results/report/figures" / aliases[name])

    @app.get("/research/")
    async def research() -> FileResponse:
        path = root / "results/report/index.html"
        if not path.is_file():
            raise HTTPException(404)
        return FileResponse(path)

    @app.get("/research/style.css")
    async def research_style() -> FileResponse:
        path = root / "results/report/style.css"
        if not path.is_file():
            raise HTTPException(404)
        return FileResponse(path)

    @app.get("/research/figures/{name}")
    async def research_figure(name: str) -> FileResponse:
        allowed = {
            p.name
            for p in (root / "results/report/figures").iterdir()
            if p.suffix in {".png", ".svg"}
        }
        path = root / "results/report/figures" / name
        if name not in allowed or not path.is_file():
            raise HTTPException(404)
        return FileResponse(path)

    return app


app = create_app()
