import json
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.api import groups, settlements, trips
from app.config import get_settings
from app.db.session import init_db
from app.errors import ApiError
from app.services.cost_engine import MESSAGES_KO, CostInputError

log = logging.getLogger("moata")
PREFIX = "/api/v1"


class BodyLimit:
    """ASGI request size cap. Counts streamed bytes, so chunked bodies without Content-Length are capped too."""

    def __init__(self, app, max_bytes: int):
        self.app, self.max = app, max_bytes
        self.reply = json.dumps({"code": "PAYLOAD_TOO_LARGE", "message_ko": "요청이 너무 큽니다.", "detail": None}, ensure_ascii=False).encode()

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        declared = next((v for k, v in scope["headers"] if k == b"content-length"), b"")
        if declared.isdigit() and int(declared) > self.max:
            return await self._reject(send)
        seen, over = 0, False

        async def counted():
            nonlocal seen, over
            msg = await receive()
            if msg["type"] == "http.request":
                seen += len(msg.get("body", b""))
                if seen > self.max:
                    over = True
                    return {"type": "http.request", "body": b"", "more_body": False}
            return msg

        async def guarded(msg):
            if over:  # handler got a truncated body: answer 413 instead
                if msg["type"] == "http.response.start":
                    await self._reject(send)
                return
            await send(msg)

        await self.app(scope, counted, guarded)

    async def _reject(self, send):
        body = self.reply
        await send({"type": "http.response.start", "status": 413, "headers": [(b"content-type", b"application/json"), (b"content-length", str(len(body)).encode())]})
        await send({"type": "http.response.body", "body": body})


@asynccontextmanager
async def lifespan(_: FastAPI):
    init_db()  # dev/test convenience; production uses `alembic upgrade head`
    yield


def create_app() -> FastAPI:
    settings = get_settings()
    app = FastAPI(title="MOATA API", version="1.0.0", lifespan=lifespan,
                  openapi_url=f"{PREFIX}/openapi.json" if settings.enable_docs else None,
                  docs_url=f"{PREFIX}/docs" if settings.enable_docs else None, redoc_url=None,
                  description="MOATA 그룹 이동 기록·정산 API. 정산은 참고용 추정치이며 결제·송금 기능은 없습니다.")
    app.add_middleware(CORSMiddleware, allow_origins=settings.cors_origin_list, allow_methods=["GET", "POST", "PUT", "PATCH", "DELETE"],
                       allow_headers=["Authorization", "Content-Type", "If-None-Match"], expose_headers=["ETag"], max_age=600)

    @app.middleware("http")
    async def security_headers(request: Request, call_next):
        resp = await call_next(request)
        resp.headers.setdefault("X-Content-Type-Options", "nosniff")
        resp.headers.setdefault("Cache-Control", "no-store")  # token-bearing and per-user responses are never cached
        return resp

    @app.exception_handler(StarletteHTTPException)
    async def http_error(_: Request, exc: StarletteHTTPException):
        body = exc.detail if isinstance(exc, ApiError) else {"code": "HTTP_ERROR", "message_ko": "요청을 처리할 수 없습니다.", "detail": str(exc.detail)}
        return JSONResponse(body, status_code=exc.status_code, headers=getattr(exc, "headers", None))

    @app.exception_handler(RequestValidationError)
    async def validation_error(_: Request, exc: RequestValidationError):
        first = exc.errors()[0] if exc.errors() else {}
        where = ".".join(str(p) for p in first.get("loc", []) if p != "body")
        return JSONResponse({"code": "VALIDATION_ERROR", "message_ko": "입력값이 올바르지 않습니다.", "detail": f"{where}: {first.get('msg', '')}"}, status_code=422)

    @app.exception_handler(CostInputError)
    async def cost_error(_: Request, exc: CostInputError):
        return JSONResponse({"code": exc.code, "message_ko": MESSAGES_KO.get(exc.code, "입력값이 올바르지 않습니다."), "detail": exc.detail or None}, status_code=422)

    @app.exception_handler(Exception)
    async def unexpected(_: Request, exc: Exception):
        log.exception("unhandled error")  # details go to the server log only
        return JSONResponse({"code": "INTERNAL_ERROR", "message_ko": "서버에 문제가 생겼어요. 잠시 후 다시 시도해 주세요.", "detail": None}, status_code=500)

    @app.get(f"{PREFIX}/health", tags=["health"])
    def health():
        return {"status": "ok", "service": "moata-api", "version": app.version}

    for r in (groups.router, trips.router, settlements.router):
        app.include_router(r, prefix=PREFIX)

    app.add_middleware(BodyLimit, max_bytes=settings.max_body_bytes)
    return app


app = create_app()
