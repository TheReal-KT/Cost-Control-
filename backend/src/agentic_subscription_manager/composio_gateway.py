"""Small, user-scoped Composio sessions boundary for read-only Gmail discovery."""

import asyncio
import json
import logging
import re
import threading
import time
from collections.abc import Callable, Mapping
from typing import Any
from urllib.parse import urlsplit
from uuid import UUID

from .adapters import RemoteFailure

logger = logging.getLogger("agentcostcontrol.integrations")

MAX_EMAILS_PER_SYNC = 50
MAX_PAGES_PER_SYNC = 2
MAX_TOOL_RESPONSE_BYTES = 1_000_000
TOOLKIT = "gmail"
FETCH_TOOL = "GMAIL_FETCH_EMAILS"
STATUS_SDK_TIMEOUT = 4
SYNC_SDK_TIMEOUT = 18
STATUS_TIMEOUT = 14
CONNECT_TIMEOUT = 13
SYNC_TIMEOUT = 55


def _value(obj: object, key: str, default: object = None) -> object:
    if isinstance(obj, Mapping):
        return obj.get(key, default)
    return getattr(obj, key, default)


def _plain(value: object, depth: int = 0, budget: list[int] | None = None) -> object:
    if budget is None:
        budget = [MAX_TOOL_RESPONSE_BYTES]
    if depth > 12:
        raise ValueError("Composio response is too deeply nested")
    if isinstance(value, Mapping):
        if len(value) > 1000:
            raise ValueError("Composio response has too many fields")
        result = {}
        for key, item in value.items():
            name = str(key)
            budget[0] -= len(name.encode("utf-8"))
            if budget[0] < 0:
                raise ValueError("Composio response exceeded its size limit")
            result[name] = _plain(item, depth + 1, budget)
        return result
    if isinstance(value, list):
        if len(value) > 1000:
            raise ValueError("Composio response has too many items")
        return [_plain(item, depth + 1, budget) for item in value]
    if isinstance(value, str):
        budget[0] -= len(value.encode("utf-8"))
        if budget[0] < 0:
            raise ValueError("Composio response exceeded its size limit")
        return value
    model_dump = getattr(value, "model_dump", None)
    if callable(model_dump):
        return _plain(model_dump(mode="json"), depth + 1, budget)
    return value


def _response_mapping(value: object) -> dict:
    plain = _plain(value)
    if not isinstance(plain, dict):
        raise ValueError("Composio returned an invalid response")
    encoded = json.dumps(plain, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if len(encoded) > MAX_TOOL_RESPONSE_BYTES:
        raise ValueError("Composio response exceeded its size limit")
    return plain


def _email_page(value: object) -> tuple[list[dict], str | None]:
    response = _response_mapping(value)
    for _ in range(5):
        if "messages" in response:
            messages = response.get("messages")
            token = response.get("nextPageToken", response.get("next_page_token"))
            if not isinstance(messages, list) or len(messages) > MAX_EMAILS_PER_SYNC:
                raise ValueError("Composio returned an invalid email page")
            if any(not isinstance(message, dict) for message in messages):
                raise ValueError("Composio returned an invalid email")
            if token is not None and not isinstance(token, str):
                raise ValueError("Composio returned an invalid page token")
            return messages, token or None
        nested = response.get("data")
        if isinstance(nested, dict):
            response = nested
            continue
        results = response.get("results")
        if isinstance(results, list) and results and isinstance(results[0], dict):
            first = results[0]
            nested = first.get("response")
            response = nested if isinstance(nested, dict) else first
            continue
        preview = response.get("data_preview")
        if isinstance(preview, dict):
            response = preview
            continue
        break
    raise ValueError("Composio returned an invalid email page")


class ComposioGmail:
    """Runs bounded, blocking Composio SDK calls away from FastAPI's event loop."""

    def __init__(
        self,
        client_factory: Callable[..., Any] | None = None,
        api_key: str = "",
        gmail_auth_config_id: str = "",
    ) -> None:
        self._client_factory = client_factory or self._default_client
        self._custom_client_factory = client_factory is not None
        self._api_key = api_key
        self._gmail_auth_config_id = gmail_auth_config_id
        self._clients: dict[int, Any] = {}
        self._client_lock = threading.Lock()

    @staticmethod
    def _default_client(timeout: int, api_key: str = "") -> Any:
        from composio import Composio

        # Explicit SDK timeouts keep the worker thread bounded if the HTTP task is
        # cancelled. Disable retries because this service already reports failures
        # to the caller and should not silently stretch a scan past its deadline.
        return Composio(api_key=api_key, timeout=timeout, max_retries=0)

    def _get_client(self, timeout: int) -> Any:
        with self._client_lock:
            key = 0 if self._custom_client_factory else timeout
            if key not in self._clients:
                self._clients[key] = (
                    self._client_factory()
                    if self._custom_client_factory
                    else self._client_factory(timeout, self._api_key)
                )
            return self._clients[key]

    @staticmethod
    def _check_active(cancelled: threading.Event, deadline: float) -> None:
        if cancelled.is_set():
            raise _WorkerStopped
        if time.monotonic() >= deadline:
            raise TimeoutError("Composio operation deadline reached")

    def _session(
        self,
        owner: UUID,
        cancelled: threading.Event,
        deadline: float,
        sdk_timeout: int,
    ) -> Any:
        self._check_active(cancelled, deadline)
        options = {
            "user_id": str(owner),
            "toolkits": {"enable": [TOOLKIT]},
            "tools": {TOOLKIT: {"enable": [FETCH_TOOL]}},
            "sandbox": {"enable": False},
        }
        if self._gmail_auth_config_id:
            options["auth_configs"] = {TOOLKIT: self._gmail_auth_config_id}
        return self._get_client(sdk_timeout).sessions.create(**options)

    @staticmethod
    def _connected(session: Any, cancelled: threading.Event, deadline: float) -> bool:
        ComposioGmail._check_active(cancelled, deadline)
        state = session.toolkits(toolkits=[TOOLKIT], limit=1, is_connected=True)
        items = _value(state, "items")
        if not isinstance(items, (list, tuple)):
            raise ValueError("Composio returned an invalid connection state")
        if len(items) > 1:
            raise ValueError("Composio returned too many connection states")
        if not items:
            return False
        slug = _value(items[0], "slug")
        if not isinstance(slug, str) or slug.casefold() != TOOLKIT:
            raise ValueError("Composio returned an unexpected toolkit")
        return True

    def _is_connected(
        self, owner: UUID, cancelled: threading.Event, deadline: float, sdk_timeout: int
    ) -> bool:
        session = self._session(owner, cancelled, deadline, sdk_timeout)
        try:
            return self._connected(session, cancelled, deadline)
        finally:
            self._delete_session(session, "connection_status")

    def _connect(
        self, owner: UUID, cancelled: threading.Event, deadline: float, sdk_timeout: int
    ) -> str:
        session = self._session(owner, cancelled, deadline, sdk_timeout)
        keep_for_authorization = False
        try:
            if self._connected(session, cancelled, deadline):
                raise RemoteFailure(409, "Gmail is already connected")
            self._check_active(cancelled, deadline)
            request = session.authorize(TOOLKIT)
            redirect_url = _value(request, "redirect_url")
            if not isinstance(redirect_url, str) or len(redirect_url) > 2048:
                raise ValueError("Composio returned an invalid connection link")
            parsed = urlsplit(redirect_url)
            if (
                parsed.scheme != "https"
                or parsed.hostname != "connect.composio.dev"
                or parsed.port not in (None, 443)
                or parsed.username
                or parsed.password
                or not parsed.path.startswith("/link/")
            ):
                raise ValueError("Composio returned an invalid connection link")
            self._check_active(cancelled, deadline)
            keep_for_authorization = True
            return redirect_url
        finally:
            if not keep_for_authorization:
                self._delete_session(session, "connect")

    def _fetch(
        self, owner: UUID, cancelled: threading.Event, deadline: float, sdk_timeout: int
    ) -> tuple[list[dict], bool]:
        session = self._session(owner, cancelled, deadline, sdk_timeout)
        try:
            if not self._connected(session, cancelled, deadline):
                raise RemoteFailure(409, "Connect Gmail before syncing subscriptions")
            self._check_active(cancelled, deadline)
            messages: list[dict] = []
            next_token: str | None = None
            for _ in range(MAX_PAGES_PER_SYNC):
                self._check_active(cancelled, deadline)
                arguments: dict[str, object] = {
                    "query": (
                        "newer_than:90d {subscription renewal recurring membership receipt invoice "
                        "billing payment}"
                    ),
                    "max_results": MAX_EMAILS_PER_SYNC,
                    "include_payload": True,
                    "include_spam_trash": False,
                }
                if next_token:
                    arguments["page_token"] = next_token
                result = session.execute(FETCH_TOOL, arguments=arguments)
                result_type = _value(result, "result_type")
                log_id = _value(result, "log_id")
                safe_log_id = (
                    log_id
                    if isinstance(log_id, str) and re.fullmatch(r"[A-Za-z0-9_-]{1,100}", log_id)
                    else "unknown"
                )
                data = _value(result, "data")
                tool_success = _value(data, "successful")
                if (
                    result_type != "completed"
                    or (tool_success is not None and not isinstance(tool_success, bool))
                    or tool_success is False
                ):
                    logger.warning("gmail tool_failure log_id=%s", safe_log_id)
                    raise RemoteFailure(502, "Gmail email search failed")
                logger.info("gmail tool_success log_id=%s", safe_log_id)
                self._check_active(cancelled, deadline)
                page, next_token = _email_page(data)
                messages.extend(page)
                if not next_token or not page:
                    break
            return messages, bool(next_token)
        finally:
            self._delete_session(session, "message_search")

    @staticmethod
    def _delete_session(session: Any, operation: str) -> None:
        delete = getattr(session, "delete", None)
        if not callable(delete):
            return
        try:
            delete()
        except Exception as error:
            logger.warning(
                "gmail session_cleanup_failed operation=%s type=%s",
                operation,
                type(error).__name__,
            )

    async def _run(
        self,
        operation: str,
        function: Callable[..., Any],
        owner: UUID,
        *,
        sdk_timeout: int,
        operation_timeout: int,
    ) -> Any:
        cancelled = threading.Event()
        deadline = time.monotonic() + operation_timeout
        worker = asyncio.create_task(
            asyncio.to_thread(function, owner, cancelled, deadline, sdk_timeout)
        )
        try:
            async with asyncio.timeout(operation_timeout):
                return await asyncio.shield(worker)
        except TimeoutError as error:
            cancelled.set()
            await self._join_worker(worker)
            raise RemoteFailure(504, "Gmail request timed out") from error
        except asyncio.CancelledError:
            cancelled.set()
            await self._join_worker(worker)
            raise
        except RemoteFailure:
            raise
        except _WorkerStopped as error:
            raise RemoteFailure(504, "Gmail request cancelled") from error
        except Exception as error:
            if "timeout" in type(error).__name__.casefold():
                logger.warning("gmail operation_timed_out operation=%s", operation)
                raise RemoteFailure(504, "Gmail request timed out") from error
            logger.warning(
                "gmail operation_failed operation=%s type=%s", operation, type(error).__name__
            )
            raise RemoteFailure(503, "Gmail integration unavailable") from error

    @staticmethod
    async def _join_worker(worker: asyncio.Task[Any]) -> None:
        """Wait for a bounded SDK request to stop before releasing this operation."""
        try:
            await asyncio.shield(worker)
        except (Exception, asyncio.CancelledError):
            return

    async def is_connected(self, owner: UUID) -> bool:
        return await self._run(
            "connection_status",
            self._is_connected,
            owner,
            sdk_timeout=STATUS_SDK_TIMEOUT,
            operation_timeout=STATUS_TIMEOUT,
        )

    async def connect(self, owner: UUID) -> str:
        return await self._run(
            "connect",
            self._connect,
            owner,
            sdk_timeout=STATUS_SDK_TIMEOUT,
            operation_timeout=CONNECT_TIMEOUT,
        )

    async def fetch_recent_messages(self, owner: UUID) -> tuple[list[dict], bool]:
        return await self._run(
            "message_search",
            self._fetch,
            owner,
            sdk_timeout=SYNC_SDK_TIMEOUT,
            operation_timeout=SYNC_TIMEOUT,
        )

    def close(self) -> None:
        """Release the SDK's underlying HTTP clients during application shutdown."""
        with self._client_lock:
            clients, self._clients = tuple(self._clients.values()), {}
        for client in clients:
            close = getattr(client, "close", None)
            if not callable(close):
                # Composio 0.30 exposes the owned HTTP pool through its HttpClient
                # but does not expose a top-level close method.
                close = getattr(getattr(client, "_client", None), "close", None)
            if callable(close):
                close()


class _WorkerStopped(Exception):
    """Internal signal used to stop queued SDK work after request cancellation."""
