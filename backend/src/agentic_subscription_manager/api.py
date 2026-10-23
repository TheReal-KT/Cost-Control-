"""Small single-worker API for authenticated, read-only account AI analysis."""

import asyncio
import json
import logging
import time
from collections import deque
from collections.abc import AsyncIterator
from contextlib import aclosing, asynccontextmanager
from uuid import UUID, uuid4

import anyio
import httpx
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, StreamingResponse
from starlette.types import ASGIApp, Receive, Scope, Send

from .account import SupabaseAccountReads
from .adapters import OpenRouterExplanation, RemoteFailure, SupabaseIdentity
from .composio_gateway import ComposioGmail
from .contracts import ChatReply, ChatRequest
from .evidence import AccountEvidence, AnalysisTask, select_task
from .security import ConversationIds, Settings
from .subscription_discovery import discover_subscriptions
from .subscription_imports import SupabaseReceiptImports
from .workflow import build_graph

logger = logging.getLogger("agentcostcontrol.ai")
if not logger.handlers:
    handler = logging.StreamHandler()
    handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
    logger.addHandler(handler)
logger.setLevel(logging.INFO)
logger.propagate = False

INTEGRATION_STATUS_DEADLINE = 20
SUBSCRIPTION_SYNC_DEADLINE = 75
RECEIPT_IMPORT_TIMEOUT = 8


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


class IntegrationHeadersAndLogs:
    """Add private response headers and safe metadata logs without wrapping chat receive."""

    _OPERATIONS = {
        ("GET", "/integrations/gmail"): "gmail_status",
        ("POST", "/integrations/gmail/connect"): "gmail_connect",
        ("POST", "/integrations/subscriptions/sync"): "subscription_sync",
    }

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        operation = self._OPERATIONS.get((scope["method"], scope["path"]))
        if operation is None:
            await self.app(scope, receive, send)
            return

        request_id = uuid4().hex
        started = time.monotonic()
        status = 500
        response_started = False

        async def send_with_metadata(message: dict) -> None:
            nonlocal status, response_started
            if message["type"] == "http.response.start":
                response_started = True
                status = message["status"]
                headers = [
                    (name, value)
                    for name, value in message.get("headers", [])
                    if name.lower() not in {b"cache-control", b"x-request-id"}
                ]
                headers.extend(
                    (
                        (b"cache-control", b"private, no-store, max-age=0"),
                        (b"x-request-id", request_id.encode("ascii")),
                    )
                )
                message["headers"] = headers
            await send(message)

        try:
            await self.app(scope, receive, send_with_metadata)
        except asyncio.CancelledError:
            status = 499
            raise
        except Exception:
            if response_started:
                raise
            response = JSONResponse({"detail": "Gmail integration unavailable"}, status_code=500)
            await response(scope, receive, send_with_metadata)
        finally:
            logger.info(
                "integration request_id=%s operation=%s status=%s duration_ms=%d",
                request_id,
                operation,
                status,
                int((time.monotonic() - started) * 1000),
            )


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


class IntegrationBudget:
    """Bound simultaneous Gmail operations without consuming chat quota."""

    def __init__(self) -> None:
        self.in_flight = 0
        self.by_owner: dict[UUID, int] = {}

    @asynccontextmanager
    async def admit(self, owner: UUID):
        if self.in_flight >= 4 or self.by_owner.get(owner, 0) >= 2:
            raise RemoteFailure(429, "Gmail is busy; try again shortly")
        self.in_flight += 1
        self.by_owner[owner] = self.by_owner.get(owner, 0) + 1
        try:
            yield
        finally:
            self.in_flight -= 1
            remaining = self.by_owner[owner] - 1
            if remaining:
                self.by_owner[owner] = remaining
            else:
                del self.by_owner[owner]


# Run the AI graph until the client disconnects.
async def run_until_disconnect(
    request: Request, graph, task, prompt: str, account: AccountEvidence
):
    """Stop local graph/HTTP work on disconnect; remote provider cancellation is best effort."""

    async def disconnected() -> None:
        # The request body is already parsed. Await the ASGI disconnect event without polling.
        while True:
            if (await request.receive())["type"] == "http.disconnect":
                return

    run = asyncio.create_task(
        graph.ainvoke({"task": task, "prompt": prompt, "account": account}, {"recursion_limit": 5})
    )
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


async def graph_deltas(
    graph, task: AnalysisTask | None, prompt: str, account: AccountEvidence
) -> AsyncIterator[dict[str, str]]:
    """Keep graph cancellation/cleanup outside Starlette's repeatedly cancelled scope."""
    queue: asyncio.Queue[dict[str, str] | Exception | None] = asyncio.Queue(maxsize=1)

    async def produce():
        try:
            async with aclosing(
                graph.astream(
                    {"task": task, "prompt": prompt, "account": account, "streaming": True},
                    {"recursion_limit": 5},
                    stream_mode="custom",
                )
            ) as parts:
                async for part in parts:
                    await queue.put(part)
        except Exception as error:
            await queue.put(error)
        else:
            await queue.put(None)

    producer = asyncio.create_task(produce())
    try:
        while True:
            part = await queue.get()
            if part is None:
                return
            if isinstance(part, Exception):
                raise part
            yield part
    finally:
        producer.cancel()
        # Join the graph so its outbound response is closed before releasing admission.
        with anyio.CancelScope(shield=True):
            await asyncio.gather(producer, return_exceptions=True)


def create_app(
    settings: Settings | None = None,
    transport: httpx.AsyncBaseTransport | None = None,
    composio_client_factory=None,
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
            app.state.account_reads = SupabaseAccountReads(
                client, config.supabase_url, config.publishable_key
            )
            app.state.receipt_imports = SupabaseReceiptImports(
                client, config.supabase_url, config.publishable_key
            )
            app.state.gmail = ComposioGmail(
                composio_client_factory,
                config.composio_api_key,
                config.composio_gmail_auth_config_id,
            )
            app.state.conversations = ConversationIds(config.conversation_secret)
            app.state.graph = build_graph(OpenRouterExplanation(client, config.provider_key))
            app.state.budget = RequestBudget()
            app.state.integration_budget = IntegrationBudget()
            try:
                yield
            finally:
                app.state.gmail.close()

    app = FastAPI(title="Agent Cost Control AI", lifespan=lifespan, debug=False)
    app.add_middleware(BodyLimit)
    app.add_middleware(IntegrationHeadersAndLogs)

    @app.exception_handler(RequestValidationError)
    async def invalid_request(request: Request, error: RequestValidationError):
        # Default validation errors echo input, which may contain sensitive questions.
        return JSONResponse({"detail": "Invalid chat request"}, 422)

    @app.get("/health")
    async def health():
        return {
            "status": "ok",
            "chat_configured": app.state.settings.configured,
            "data_mode": "account",
        }

    def bearer_token(request: Request) -> str:
        scheme, _, token = request.headers.get("authorization", "").partition(" ")
        if scheme.casefold() != "bearer" or not token or len(token) > 8192:
            raise RemoteFailure(401, "Sign in again")
        return token

    def require_token(request: Request) -> str:
        token = bearer_token(request)
        if not app.state.settings.configured:
            raise RemoteFailure(503, "Chat is not configured")
        return token

    async def verify_account_request(request: Request) -> tuple[str, UUID]:
        token = bearer_token(request)
        if not app.state.settings.identity_configured:
            raise RemoteFailure(503, "Account sign-in is not configured")
        return token, await app.state.identity.verify(token)

    def integration_error(error: RemoteFailure) -> JSONResponse:
        headers = {}
        if error.status == 401:
            headers["WWW-Authenticate"] = "Bearer"
        if error.status == 429:
            headers["Retry-After"] = str(error.retry_after)
        return JSONResponse({"detail": error.code}, error.status, headers=headers)

    @app.get("/integrations/gmail")
    async def gmail_status(request: Request):
        try:
            async with asyncio.timeout(INTEGRATION_STATUS_DEADLINE):
                _, owner = await verify_account_request(request)
                configured = app.state.settings.composio_configured
                if configured:
                    async with app.state.integration_budget.admit(owner):
                        connected = await app.state.gmail.is_connected(owner)
                else:
                    connected = False
                return {"configured": configured, "connected": connected}
        except TimeoutError:
            return integration_error(RemoteFailure(504, "Gmail request timed out"))
        except RemoteFailure as error:
            return integration_error(error)

    @app.post("/integrations/gmail/connect")
    async def gmail_connect(request: Request):
        try:
            async with asyncio.timeout(INTEGRATION_STATUS_DEADLINE):
                _, owner = await verify_account_request(request)
                if not app.state.settings.composio_configured:
                    raise RemoteFailure(503, "Gmail integration is not configured")
                async with app.state.integration_budget.admit(owner):
                    return {"redirect_url": await app.state.gmail.connect(owner)}
        except TimeoutError:
            return integration_error(RemoteFailure(504, "Gmail request timed out"))
        except RemoteFailure as error:
            return integration_error(error)

    @app.post("/integrations/subscriptions/sync")
    async def sync_subscriptions(request: Request):
        deadline = time.monotonic() + SUBSCRIPTION_SYNC_DEADLINE
        try:
            async with asyncio.timeout_at(deadline):
                token, owner = await verify_account_request(request)
                if not app.state.settings.composio_configured:
                    raise RemoteFailure(503, "Gmail integration is not configured")
                async with app.state.integration_budget.admit(owner):
                    messages, has_more = await app.state.gmail.fetch_recent_messages(owner)
                scanned = len(messages)
                try:
                    receipts = discover_subscriptions(messages)
                except ValueError as error:
                    raise RemoteFailure(502, "Gmail email data is invalid") from error
                if receipts:
                    # The RPC has an 8-second client timeout. Do not start it when
                    # the overall request budget cannot leave time to observe its
                    # atomic result before returning to the Android client.
                    if time.monotonic() + RECEIPT_IMPORT_TIMEOUT + 0.25 >= deadline:
                        raise RemoteFailure(504, "Gmail request timed out")
                    imported = await app.state.receipt_imports.import_receipts(token, receipts)
                    imported_count = imported.imported
                    skipped_count = scanned - imported_count
                    message = (
                        f"Imported {imported_count} subscription(s); "
                        f"{skipped_count} scanned email(s) were skipped as existing/duplicate "
                        "receipts or because they lacked clear subscription evidence."
                    )
                else:
                    imported_count = 0
                    skipped_count = scanned
                    message = (
                        f"No eligible subscription was found; {scanned} scanned email(s) "
                        "lacked clear recurring-charge and future-renewal evidence."
                    )
                if has_more:
                    message += " More matching emails remain beyond this scan."
                return {
                    "imported": imported_count,
                    "skipped": skipped_count,
                    "scanned": scanned,
                    "has_more": has_more,
                    "message": message,
                }
        except TimeoutError:
            return integration_error(RemoteFailure(504, "Gmail request timed out"))
        except RemoteFailure as error:
            return integration_error(error)

    async def prepare_chat(
        payload: ChatRequest, token: str
    ) -> tuple[AnalysisTask | None, str, AccountEvidence]:
        owner = await app.state.identity.verify(token)
        conversations = app.state.conversations
        if payload.conversation_id is not None:
            if not conversations.verify(payload.conversation_id, owner):
                raise RemoteFailure(404, "Conversation unavailable")
            conversation = payload.conversation_id
        else:
            conversation = conversations.issue(owner)
        task = select_task(payload.prompt)
        app.state.budget.reserve_call(owner)
        account = await app.state.account_reads.load(owner, token)
        return task, conversation, account

    @app.post("/ai/chat", response_model=ChatReply)
    async def chat(payload: ChatRequest, request: Request):
        if payload.stream:
            return await stream_chat(payload, request)
        started = time.monotonic()
        request_id = uuid4().hex
        status = 200
        try:
            token = require_token(request)
            async with app.state.budget.admit(), asyncio.timeout(38):
                task, conversation, account = await prepare_chat(payload, token)
                result = await run_until_disconnect(
                    request, app.state.graph, task, payload.prompt, account
                )
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

    def event(name: str, data: dict) -> str:
        return f"event: {name}\ndata: {json.dumps(data)}\n\n"

    async def stream_chat(payload: ChatRequest, request: Request):
        started = time.monotonic()
        request_id = uuid4().hex
        status = 200
        try:
            token = require_token(request)
            async with app.state.budget.admit(), asyncio.timeout(38):
                task, conversation, account = await prepare_chat(payload, token)
        except (RemoteFailure, TimeoutError) as error:
            status = error.status if isinstance(error, RemoteFailure) else 504
            detail = error.code if isinstance(error, RemoteFailure) else "AI request timed out"
            headers = {"X-Request-ID": request_id}
            if status == 401:
                headers["WWW-Authenticate"] = "Bearer"
            if status == 429:
                headers["Retry-After"] = str(error.retry_after)
            logger.info(
                "chat request_id=%s status=%s duration_ms=%d",
                request_id,
                status,
                int((time.monotonic() - started) * 1000),
            )
            return JSONResponse({"detail": detail}, status, headers=headers)

        async def body():
            nonlocal status
            try:
                async with (
                    app.state.budget.admit(),
                    asyncio.timeout(max(0, 38 - (time.monotonic() - started))),
                ):
                    yield event("start", {"conversation_id": conversation})
                    async with aclosing(
                        graph_deltas(app.state.graph, task, payload.prompt, account)
                    ) as parts:
                        async for part in parts:
                            yield event("delta", {"text": part["text"]})
                    yield event("done", {"conversation_id": conversation})
            except TimeoutError:
                status = 504
                yield event("error", {"status": status, "detail": "AI request timed out"})
            except RemoteFailure as error:
                status = error.status
                yield event(
                    "error",
                    {"status": status, "detail": error.code, "retry_after": error.retry_after},
                )
            except asyncio.CancelledError:
                status = 499
                raise
            except Exception as error:
                status = 500
                logger.error(
                    "chat unexpected_failure request_id=%s type=%s",
                    request_id,
                    type(error).__name__,
                )
                yield event("error", {"status": status, "detail": "AI stream failed"})
            finally:
                logger.info(
                    "chat request_id=%s status=%s duration_ms=%d",
                    request_id,
                    status,
                    int((time.monotonic() - started) * 1000),
                )

        return StreamingResponse(
            body(),
            media_type="text/event-stream",
            headers={
                "Cache-Control": "no-store",
                "X-Accel-Buffering": "no",
                "X-Request-ID": request_id,
            },
        )

    return app
