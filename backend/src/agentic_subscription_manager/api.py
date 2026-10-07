"""Small single-worker API for authenticated, synthetic-only AI analysis."""

import asyncio
import logging
import time
from collections import deque
from contextlib import asynccontextmanager
from uuid import UUID, uuid4

import httpx
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.types import ASGIApp, Receive, Scope, Send

from .adapters import OpenRouterExplanation, RemoteFailure, SupabaseIdentity
from .contracts import ChatReply, ChatRequest
from .evidence import select_task
from .security import ConversationIds, Settings
from .workflow import build_graph

logger = logging.getLogger("agentcostcontrol.ai")
if not logger.handlers:
    handler = logging.StreamHandler()
    handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
    logger.addHandler(handler)
logger.setLevel(logging.INFO)
logger.propagate = False


class BodyLimit:
    """Bound streamed bodies before FastAPI parses JSON, including chunked requests."""

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        body = bytearray()
        while True:
            message = await receive()
            if message["type"] == "http.disconnect":
                return
            chunk = message.get("body", b"")
            if len(body) + len(chunk) > 32768:
                await JSONResponse({"detail": "Request too large"}, 413)(scope, receive, send)
                return
            body.extend(chunk)
            if not message.get("more_body", False):
                break
        delivered = False

        async def replay() -> dict:
            nonlocal delivered
            if not delivered:
                delivered = True
                return {"type": "http.request", "body": bytes(body), "more_body": False}
            return await receive()

        await self.app(scope, replay, send)


class RequestBudget:
    """Bounded process-local admission; call only from the API's event loop."""

    def __init__(self) -> None:
        self.in_flight = 0
        self.recent: deque[tuple[float, UUID]] = deque()
        self.day = int(time.time() // 86400)
        self.daily_count = 0

    @asynccontextmanager
    async def admit(self):
        if self.in_flight >= 4:
            raise RemoteFailure(429, "AI is busy; try again shortly")
        self.in_flight += 1
        try:
            yield
        finally:
            self.in_flight -= 1

    def reserve_call(self, owner: UUID, now: float | None = None) -> None:
        current = time.time() if now is None else now
        while self.recent and self.recent[0][0] <= current - 60:
            self.recent.popleft()
        day = int(current // 86400)
        if day != self.day:
            self.day, self.daily_count = day, 0
        if self.daily_count >= 50:
            delay = max(1, int((day + 1) * 86400 - current))
            raise RemoteFailure(429, "AI request limit reached", delay)
        if len(self.recent) >= 20 or sum(subject == owner for _, subject in self.recent) >= 5:
            raise RemoteFailure(429, "AI request limit reached")
        self.recent.append((current, owner))
        self.daily_count += 1  # Failed/cancelled provider attempts also consume capacity.


async def run_until_disconnect(request: Request, graph, task):
    """Stop local graph/HTTP work on disconnect; remote provider cancellation is best effort."""

    async def disconnected() -> None:
        # The request body is already parsed. Await the ASGI disconnect event without polling.
        while True:
            if (await request.receive())["type"] == "http.disconnect":
                return

    run = asyncio.create_task(graph.ainvoke({"task": task}, {"recursion_limit": 5}))
    monitor = asyncio.create_task(disconnected())
    try:
        done, _ = await asyncio.wait({run, monitor}, return_when=asyncio.FIRST_COMPLETED)
        if run in done:
            return await run
        raise RemoteFailure(499, "Request cancelled")
    finally:
        run.cancel()
        monitor.cancel()
        await asyncio.gather(run, monitor, return_exceptions=True)


def create_app(
    settings: Settings | None = None, transport: httpx.AsyncBaseTransport | None = None
) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app: FastAPI):
        config = settings if settings is not None else Settings.from_env()
        async with httpx.AsyncClient(
            transport=transport,
            follow_redirects=False,
            trust_env=False,
            limits=httpx.Limits(max_connections=8, max_keepalive_connections=8),
        ) as client:
            app.state.settings = config
            app.state.identity = SupabaseIdentity(
                client, config.supabase_url, config.publishable_key
            )
            app.state.conversations = ConversationIds(config.conversation_secret)
            app.state.graph = build_graph(OpenRouterExplanation(client, config.provider_key))
            app.state.budget = RequestBudget()
            yield

    app = FastAPI(title="Agent Cost Control AI", lifespan=lifespan, debug=False)
    app.add_middleware(BodyLimit)

    @app.exception_handler(RequestValidationError)
    async def invalid_request(request: Request, error: RequestValidationError):
        # Default validation errors echo input, which may contain sensitive questions.
        return JSONResponse({"detail": "Invalid chat request"}, 422)

    @app.get("/health")
    async def health():
        return {
            "status": "ok",
            "chat_configured": app.state.settings.configured,
            "data_mode": "synthetic",
        }

    @app.post("/ai/chat", response_model=ChatReply)
    async def chat(payload: ChatRequest, request: Request):
        started = time.monotonic()
        request_id = uuid4().hex
        status = 200
        try:
            authorization = request.headers.get("authorization", "")
            scheme, _, token = authorization.partition(" ")
            if scheme.casefold() != "bearer" or not token or len(token) > 8192:
                raise RemoteFailure(401, "Sign in again")
            if not app.state.settings.configured:
                raise RemoteFailure(503, "Chat is not configured")
            async with app.state.budget.admit(), asyncio.timeout(38):
                owner = await app.state.identity.verify(token)
                conversations = app.state.conversations
                if payload.conversation_id is not None:
                    if not conversations.verify(payload.conversation_id, owner):
                        raise RemoteFailure(404, "Conversation unavailable")
                    conversation = payload.conversation_id
                else:
                    conversation = conversations.issue(owner)
                task = select_task(payload.prompt)
                if task is None:
                    return ChatReply(
                        answer=(
                            "Fictional examples only. "
                            "Ask about spending, renewals, budgets or usage."
                        ),
                        conversation_id=conversation,
                    )
                app.state.budget.reserve_call(owner)
                result = await run_until_disconnect(request, app.state.graph, task)
                return ChatReply(answer=result["answer"], conversation_id=conversation)
        except TimeoutError:
            status = 504
            return JSONResponse(
                {"detail": "AI request timed out"}, status, headers={"X-Request-ID": request_id}
            )
        except RemoteFailure as error:
            status = error.status
            headers = {"X-Request-ID": request_id}
            if status == 401:
                headers["WWW-Authenticate"] = "Bearer"
            if status == 429:
                headers["Retry-After"] = str(error.retry_after)
            return JSONResponse({"detail": error.code}, status, headers=headers)
        except asyncio.CancelledError:
            status = 499
            raise
        except Exception as error:
            # Preserve programmer failures while logging only safe diagnostic metadata.
            status = 500
            logger.error(
                "chat unexpected_failure request_id=%s type=%s", request_id, type(error).__name__
            )
            raise
        finally:
            logger.info(
                "chat request_id=%s status=%s duration_ms=%d",
                request_id,
                status,
                int((time.monotonic() - started) * 1000),
            )

    return app
