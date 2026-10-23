"""Composio/Gmail integration contracts using synthetic upstream responses."""

import asyncio
import json
import sys
import threading
import types
import unittest
from contextlib import AsyncExitStack
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from unittest.mock import patch
from uuid import UUID

import httpx
from fastapi.testclient import TestClient

from agentic_subscription_manager.adapters import RemoteFailure
from agentic_subscription_manager.api import IntegrationBudget, create_app
from agentic_subscription_manager.composio_gateway import ComposioGmail
from agentic_subscription_manager.security import Settings

OWNER_A = UUID("00000000-0000-4000-8000-000000000001")
OWNER_B = UUID("00000000-0000-4000-8000-000000000002")
BASE_SETTINGS = Settings(
    supabase_url="https://project.example.invalid",
    publishable_key="sb_publishable_test",
    conversation_secret="synthetic-correlation-secret-at-least-32-characters",
    composio_api_key="synthetic-composio-key",
)


def receipt_message(message_id="gmail-message-1"):
    now = datetime.now(UTC)
    renewal = (now.date() + timedelta(days=20)).isoformat()
    return {
        "messageId": message_id,
        "sender": "Netflix <billing@mailer.netflix.com>",
        "subject": f"Your Netflix monthly subscription renews on {renewal}",
        "messageTimestamp": now.isoformat().replace("+00:00", "Z"),
        "messageText": (
            "We charged ZAR 99.00 for your monthly subscription, billed every month. "
            f"Your next billing date is {renewal}."
        ),
    }


class IntegrationUpstreams:
    def __init__(self):
        self.rpc_requests = []
        self.seen_by_token = {}
        self.rpc_response = None
        self.rpc_status = 200

    def handle(self, request):
        self.assert_safe_request(request)
        token = request.headers.get("authorization", "")
        if request.url.path == "/auth/v1/user":
            if token == "Bearer expired":
                return httpx.Response(401, json={"message": "synthetic expired session"})
            owner = OWNER_B if token == "Bearer user-b" else OWNER_A
            return httpx.Response(200, json={"id": str(owner)})
        if request.url.path == "/rest/v1/rpc/import_subscription_receipts":
            self.rpc_requests.append(request)
            if self.rpc_response is not None:
                return httpx.Response(self.rpc_status, content=self.rpc_response)
            if self.rpc_status != 200:
                return httpx.Response(self.rpc_status, content=b"private database detail")
            payload = json.loads(request.content)
            seen = self.seen_by_token.setdefault(token, set())
            imported = skipped = 0
            for item in payload["receipts"]:
                if item["source_id"] in seen:
                    skipped += 1
                else:
                    imported += 1
                    seen.add(item["source_id"])
            return httpx.Response(200, json={"imported": imported, "skipped": skipped})
        raise AssertionError(f"Unexpected Supabase request: {request.method} {request.url.path}")

    @staticmethod
    def assert_safe_request(request):
        assert request.url.host == "project.example.invalid"
        assert request.headers.get("apikey") == BASE_SETTINGS.publishable_key


class FakeComposioSession:
    def __init__(self, client, owner, options):
        self.client = client
        self.owner = owner
        self.options = options

    def toolkits(self, **options):
        self.client.status_queries.append((self.owner, options))
        items = [{"slug": "gmail"}] if self.client.connected else []
        return {"items": items}

    def authorize(self, toolkit):
        self.client.authorize_calls.append((self.owner, toolkit))
        return {"redirect_url": self.client.redirect_url}

    def execute(self, tool_slug, *, arguments):
        self.client.executions.append((self.owner, tool_slug, arguments))
        return SimpleNamespace(
            result_type=self.client.result_type,
            data=self.client.result_data,
            log_id=self.client.log_id,
            error=self.client.error,
        )

    def delete(self):
        self.client.deleted_sessions.append(self)


class FakeComposio:
    def __init__(self):
        self.connected = True
        self.redirect_url = "https://connect.composio.dev/link/synthetic"
        self.messages = [receipt_message()]
        self.has_more = False
        self.result_type = "completed"
        self.result_data = None
        self.error = "PRIVATE_PROVIDER_ERROR"
        self.log_id = "log_synthetic"
        self.sessions = []
        self.deleted_sessions = []
        self.status_queries = []
        self.authorize_calls = []
        self.executions = []

    def create_session(self, **options):
        session = FakeComposioSession(self, options["user_id"], options)
        self.sessions.append(session)
        return session

    @property
    def session_collection(self):
        return SimpleNamespace(create=self.create_session)

    @property
    def result_data(self):
        return self._result_data or {
            "data": {
                "messages": self.messages,
                "nextPageToken": "more-page" if self.has_more else "",
                "resultSizeEstimate": len(self.messages),
            },
            "successful": True,
        }

    @result_data.setter
    def result_data(self, value):
        self._result_data = value

    @property
    def client(self):
        return lambda: SimpleNamespace(sessions=self.session_collection)


class IntegrationContracts(unittest.TestCase):
    def setUp(self):
        self.upstreams = IntegrationUpstreams()
        self.composio = FakeComposio()
        self.app = create_app(
            BASE_SETTINGS,
            httpx.MockTransport(self.upstreams.handle),
            self.composio.client,
        )
        self.client = TestClient(self.app)
        self.client.__enter__()

    def tearDown(self):
        self.client.__exit__(None, None, None)

    def headers(self, user="a"):
        return {"Authorization": f"Bearer user-{user}"}

    def assert_no_store(self, response):
        self.assertEqual("private, no-store, max-age=0", response.headers["cache-control"])
        self.assertRegex(response.headers["x-request-id"], r"^[0-9a-f]{32}$")

    def test_connection_status_uses_verified_supabase_uuid_without_model_key(self):
        settings = Settings(
            supabase_url=BASE_SETTINGS.supabase_url,
            publishable_key=BASE_SETTINGS.publishable_key,
            conversation_secret=BASE_SETTINGS.conversation_secret,
            composio_api_key=BASE_SETTINGS.composio_api_key,
        )
        fake = FakeComposio()
        app = create_app(settings, httpx.MockTransport(IntegrationUpstreams().handle), fake.client)
        with TestClient(app) as client:
            response_a = client.get("/integrations/gmail", headers=self.headers("a"))
            response_b = client.get("/integrations/gmail", headers=self.headers("b"))
        self.assertEqual({"configured": True, "connected": True}, response_a.json())
        self.assertEqual({"configured": True, "connected": True}, response_b.json())
        self.assert_no_store(response_a)
        self.assert_no_store(response_b)
        self.assertEqual([str(OWNER_A), str(OWNER_B)], [session.owner for session in fake.sessions])
        self.assertFalse(settings.configured)
        self.assertNotIn(settings.composio_api_key, repr(settings))
        self.assertEqual(fake.sessions, fake.deleted_sessions)

    def test_unconfigured_status_and_connect_are_independent_of_chat_settings(self):
        settings = Settings(
            supabase_url=BASE_SETTINGS.supabase_url,
            publishable_key=BASE_SETTINGS.publishable_key,
        )
        app = create_app(settings, httpx.MockTransport(self.upstreams.handle), self.composio.client)
        with TestClient(app) as client:
            status = client.get("/integrations/gmail", headers=self.headers())
            connect = client.post("/integrations/gmail/connect", headers=self.headers())
        self.assertEqual({"configured": False, "connected": False}, status.json())
        self.assertEqual(503, connect.status_code)
        self.assert_no_store(status)
        self.assert_no_store(connect)
        self.assertEqual([], self.composio.sessions)

    def test_connect_link_is_scoped_to_verified_user_and_gmail_read_tool(self):
        self.composio.connected = False
        response = self.client.post("/integrations/gmail/connect", headers=self.headers("b"))
        self.assertEqual(200, response.status_code)
        self.assert_no_store(response)
        self.assertEqual(
            {"redirect_url": self.composio.redirect_url},
            response.json(),
        )
        session = self.composio.sessions[-1]
        self.assertEqual(str(OWNER_B), session.owner)
        self.assertEqual({"enable": ["gmail"]}, session.options["toolkits"])
        self.assertEqual({"gmail": {"enable": ["GMAIL_FETCH_EMAILS"]}}, session.options["tools"])
        self.assertEqual({"enable": False}, session.options["sandbox"])
        self.assertNotIn("auth_configs", session.options)
        self.assertNotIn(session, self.composio.deleted_sessions)
        self.assertEqual([(str(OWNER_B), "gmail")], self.composio.authorize_calls)

    def test_connect_for_existing_account_cleans_up_unused_session(self):
        response = self.client.post("/integrations/gmail/connect", headers=self.headers())
        self.assertEqual(409, response.status_code)
        self.assertEqual(self.composio.sessions, self.composio.deleted_sessions)

    def test_configured_gmail_auth_config_is_applied_to_composio_session(self):
        settings = Settings(
            supabase_url=BASE_SETTINGS.supabase_url,
            publishable_key=BASE_SETTINGS.publishable_key,
            conversation_secret=BASE_SETTINGS.conversation_secret,
            composio_api_key=BASE_SETTINGS.composio_api_key,
            composio_gmail_auth_config_id="gmail-readonly-config",
        )
        fake = FakeComposio()
        app = create_app(settings, httpx.MockTransport(self.upstreams.handle), fake.client)
        with TestClient(app) as client:
            response = client.get("/integrations/gmail", headers=self.headers())
        self.assertEqual(200, response.status_code)
        self.assertEqual(
            {"gmail": "gmail-readonly-config"}, fake.sessions[0].options["auth_configs"]
        )
        self.assertEqual(fake.sessions, fake.deleted_sessions)

    def test_sync_imports_only_valid_receipts_and_repeated_scan_is_idempotent(self):
        first = self.client.post("/integrations/subscriptions/sync", headers=self.headers("a"))
        second = self.client.post("/integrations/subscriptions/sync", headers=self.headers("a"))
        self.assertEqual(
            {"imported": 1, "skipped": 0, "scanned": 1, "has_more": False},
            {key: first.json()[key] for key in ("imported", "skipped", "scanned", "has_more")},
        )
        self.assertEqual(
            {"imported": 0, "skipped": 1, "scanned": 1, "has_more": False},
            {key: second.json()[key] for key in ("imported", "skipped", "scanned", "has_more")},
        )
        self.assertEqual(2, len(self.upstreams.rpc_requests))
        self.assertEqual(self.composio.sessions, self.composio.deleted_sessions)
        self.assert_no_store(first)
        self.assert_no_store(second)
        first_request, second_request = self.upstreams.rpc_requests
        first_payload = json.loads(first_request.content)
        self.assertEqual(first_payload, json.loads(second_request.content))
        self.assertEqual("Bearer user-a", first_request.headers["authorization"])
        self.assertEqual(BASE_SETTINGS.publishable_key, first_request.headers["apikey"])
        self.assertEqual(
            {
                "source_id",
                "service_name",
                "price",
                "currency",
                "billing_cycle",
                "renewal_date",
                "source_url",
            },
            set(first_payload["receipts"][0]),
        )
        self.assertEqual("MONTHLY", first_payload["receipts"][0]["billing_cycle"])
        self.assertNotIn("subscription payment is", first_request.content.decode())
        owner, tool_slug, arguments = self.composio.executions[0]
        self.assertEqual(str(OWNER_A), owner)
        self.assertEqual("GMAIL_FETCH_EMAILS", tool_slug)
        self.assertEqual(50, arguments["max_results"])
        self.assertTrue(arguments["include_payload"])

    def test_second_account_uses_its_own_composio_and_supabase_identity(self):
        response_a = self.client.post("/integrations/subscriptions/sync", headers=self.headers("a"))
        response_b = self.client.post("/integrations/subscriptions/sync", headers=self.headers("b"))
        self.assertEqual(1, response_a.json()["imported"])
        self.assertEqual(1, response_b.json()["imported"])
        self.assertEqual(
            [str(OWNER_A), str(OWNER_B)], [session.owner for session in self.composio.sessions]
        )
        tokens = [request.headers["authorization"] for request in self.upstreams.rpc_requests]
        self.assertEqual(["Bearer user-a", "Bearer user-b"], tokens)

    def test_more_emails_and_empty_results_are_reported_without_false_imports(self):
        self.composio.messages = [{"id": "not-a-receipt", "textBody": "Hello"}]
        self.composio.has_more = True
        response = self.client.post("/integrations/subscriptions/sync", headers=self.headers())
        self.assertEqual(0, response.json()["imported"])
        self.assertEqual(1, response.json()["skipped"])
        self.assertEqual(1, response.json()["scanned"])
        self.assert_no_store(response)
        self.assertTrue(response.json()["has_more"])
        self.assertIn("More matching emails remain", response.json()["message"])
        self.assertEqual([], self.upstreams.rpc_requests)

    def test_malformed_or_failed_composio_response_is_sanitized(self):
        self.composio.result_data = {"messages": "not-a-list"}
        malformed = self.client.post("/integrations/subscriptions/sync", headers=self.headers())
        self.assertEqual(503, malformed.status_code)
        self.assertNotIn("not-a-list", malformed.text)
        self.assertEqual([], self.upstreams.rpc_requests)

        self.composio.result_data = None
        self.composio.result_data = {
            "data": {"messages": [], "nextPageToken": "", "resultSizeEstimate": 0},
            "successful": False,
            "error": "PRIVATE_PROVIDER_ERROR",
        }
        with self.assertLogs("agentcostcontrol.integrations", level="WARNING") as provider_logs:
            unsuccessful = self.client.post(
                "/integrations/subscriptions/sync", headers=self.headers()
            )
        self.assertEqual(502, unsuccessful.status_code)
        self.assertNotIn("PRIVATE_PROVIDER_ERROR", unsuccessful.text)
        self.assertTrue(
            any("gmail tool_failure log_id=log_synthetic" in line for line in provider_logs.output)
        )
        self.assertEqual([], self.upstreams.rpc_requests)

        self.composio.result_data = None
        self.composio.result_type = "failed"
        failed = self.client.post("/integrations/subscriptions/sync", headers=self.headers())
        self.assertEqual(502, failed.status_code)
        self.assertNotIn("PRIVATE_PROVIDER_ERROR", failed.text)
        self.assertEqual([], self.upstreams.rpc_requests)

    def test_invalid_connect_url_and_rpc_failure_do_not_report_success(self):
        self.composio.connected = False
        self.composio.redirect_url = "https://attacker.example/link/token"
        bad_connect = self.client.post("/integrations/gmail/connect", headers=self.headers())
        self.assertEqual(503, bad_connect.status_code)
        self.assert_no_store(bad_connect)
        self.assertNotIn("attacker.example", bad_connect.text)

        self.composio.redirect_url = "https://connect.composio.dev:8443/link/synthetic"
        bad_port = self.client.post("/integrations/gmail/connect", headers=self.headers())
        self.assertEqual(503, bad_port.status_code)
        self.assert_no_store(bad_port)

        self.composio.connected = True
        self.upstreams.rpc_status = 500
        failed_import = self.client.post("/integrations/subscriptions/sync", headers=self.headers())
        self.assertEqual(503, failed_import.status_code)
        self.assert_no_store(failed_import)
        self.assertNotIn("private database detail", failed_import.text)

    def test_authentication_is_required_before_provider_access(self):
        response = self.client.post(
            "/integrations/subscriptions/sync", headers={"Authorization": "Bearer expired"}
        )
        self.assertEqual(401, response.status_code)
        self.assert_no_store(response)
        self.assertEqual([], self.composio.sessions)
        self.assertEqual([], self.upstreams.rpc_requests)

    def test_unexpected_provider_error_is_sanitized_and_uncacheable(self):
        class BrokenGmail:
            async def is_connected(self, owner):
                raise RuntimeError("private provider response")

            def close(self):
                pass

        self.app.state.gmail = BrokenGmail()
        response = self.client.get("/integrations/gmail", headers=self.headers())
        self.assertEqual(500, response.status_code)
        self.assert_no_store(response)
        self.assertNotIn("private provider response", response.text)


class CancelledIntegration(unittest.IsolatedAsyncioTestCase):
    async def test_cancelled_sync_does_not_write_after_gmail_read_finishes(self):
        entered = asyncio.Event()
        release = asyncio.Event()
        upstreams = IntegrationUpstreams()
        app = create_app(
            BASE_SETTINGS, httpx.MockTransport(upstreams.handle), FakeComposio().client
        )

        class WaitingGmail:
            async def fetch_recent_messages(self, owner):
                entered.set()
                await release.wait()
                return [receipt_message()], False

            def close(self):
                pass

        async with app.router.lifespan_context(app):
            app.state.gmail = WaitingGmail()
            async with httpx.AsyncClient(
                transport=httpx.ASGITransport(app=app), base_url="https://local.invalid"
            ) as client:
                request = asyncio.create_task(
                    client.post(
                        "/integrations/subscriptions/sync",
                        headers={"Authorization": "Bearer user-a"},
                    )
                )
                await asyncio.wait_for(entered.wait(), 2)
                request.cancel()
                with self.assertRaises(asyncio.CancelledError):
                    await request
                release.set()
                self.assertEqual([], upstreams.rpc_requests)

    async def test_cancelled_sdk_read_is_joined_before_handler_cancellation_returns(self):
        entered = threading.Event()
        release = threading.Event()
        completed = threading.Event()
        deleted = threading.Event()

        class BlockingSession:
            def toolkits(self, **options):
                return {"items": [{"slug": "gmail"}]}

            def execute(self, tool_slug, *, arguments):
                entered.set()
                release.wait(3)
                completed.set()
                return SimpleNamespace(
                    result_type="completed",
                    data={"messages": [], "nextPageToken": ""},
                    log_id="safe_log_id",
                )

            def delete(self):
                deleted.set()

        client = SimpleNamespace(
            sessions=SimpleNamespace(create=lambda **options: BlockingSession())
        )
        gateway = ComposioGmail(lambda: client)
        request = asyncio.create_task(gateway.fetch_recent_messages(OWNER_A))
        self.assertTrue(await asyncio.wait_for(asyncio.to_thread(entered.wait), 2))
        request.cancel()
        await asyncio.sleep(0)
        self.assertFalse(request.done())
        release.set()
        with self.assertRaises(asyncio.CancelledError):
            await request
        self.assertTrue(completed.is_set())
        self.assertTrue(deleted.is_set())


class IntegrationAdmission(unittest.IsolatedAsyncioTestCase):
    async def test_gmail_provider_concurrency_is_bounded_separately_from_chat(self):
        budget = IntegrationBudget()
        async with AsyncExitStack() as stack:
            await stack.enter_async_context(budget.admit(OWNER_A))
            await stack.enter_async_context(budget.admit(OWNER_A))
            with self.assertRaises(RemoteFailure) as per_user:
                async with budget.admit(OWNER_A):
                    pass
            self.assertEqual(429, per_user.exception.status)

            await stack.enter_async_context(budget.admit(OWNER_B))
            await stack.enter_async_context(
                budget.admit(UUID("00000000-0000-4000-8000-000000000003"))
            )
            with self.assertRaises(RemoteFailure) as global_limit:
                async with budget.admit(UUID("00000000-0000-4000-8000-000000000004")):
                    pass
            self.assertEqual(429, global_limit.exception.status)
        self.assertEqual(0, budget.in_flight)
        self.assertEqual({}, budget.by_owner)


class ComposioClientConfiguration(unittest.TestCase):
    def test_sdk_is_created_with_bounded_http_timeout_and_no_retries(self):
        calls = []
        composio_module = types.ModuleType("composio")

        def fake_composio(**options):
            calls.append(options)
            return object()

        composio_module.Composio = fake_composio
        with patch.dict(sys.modules, {"composio": composio_module}):
            ComposioGmail._default_client(18, "synthetic-composio-key")
        self.assertEqual(
            [{"api_key": "synthetic-composio-key", "timeout": 18, "max_retries": 0}],
            calls,
        )

    def test_shutdown_closes_the_sdk_owned_http_client(self):
        closed = []
        client = SimpleNamespace(_client=SimpleNamespace(close=lambda: closed.append(True)))
        gateway = ComposioGmail(lambda: client)
        gateway._get_client(18)
        gateway.close()
        self.assertEqual([True], closed)


if __name__ == "__main__":
    unittest.main()
