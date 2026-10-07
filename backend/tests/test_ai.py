"""Real FastAPI + LangGraph contracts with synthetic, network-free HTTP adapters."""

import asyncio
import json
import time
import unittest
from datetime import date
from decimal import Decimal
from unittest.mock import patch
from uuid import UUID

import httpx
from fastapi.testclient import TestClient

from agentic_subscription_manager.adapters import RemoteFailure, retry_delay
from agentic_subscription_manager.api import (
    BodyLimit,
    RequestBudget,
    create_app,
    run_until_disconnect,
)
from agentic_subscription_manager.contracts import MODEL_ID
from agentic_subscription_manager.evidence import SubscriptionEvidence, monthly_totals
from agentic_subscription_manager.security import ConversationIds, Settings

OWNER_A = UUID("00000000-0000-4000-8000-000000000001")
OWNER_B = UUID("00000000-0000-4000-8000-000000000002")
CONFIG = Settings(
    "https://project.example.invalid",
    "sb_publishable_synthetic",
    "synthetic-provider-secret",
    "synthetic-correlation-secret-at-least-32-characters",
)


class FakeUpstreams:
    def __init__(self):
        self.requests: list[httpx.Request] = []
        self.auth_status = 200
        self.auth_data = None
        self.provider_status = 200
        self.provider_data = {
            "choices": [
                {
                    "finish_reason": "stop",
                    "message": {
                        "content": "Annual plans are spread over the year. Review low usage."
                    },
                }
            ]
        }
        self.provider_error = None
        self.provider_headers = {}

    def handle(self, request: httpx.Request):
        self.requests.append(request)
        if request.url.host == "project.example.invalid":
            assert request.url.path == "/auth/v1/user"
            assert request.headers["apikey"] == CONFIG.publishable_key
            token = request.headers["authorization"]
            if token == "Bearer expired":
                return httpx.Response(401, json={"message": "expired synthetic session"})
            owner = OWNER_B if token == "Bearer user-b" else OWNER_A
            data = self.auth_data if self.auth_data is not None else {"id": str(owner)}
            return httpx.Response(self.auth_status, json=data)
        assert request.url == "https://openrouter.ai/api/v1/chat/completions"
        if self.provider_error:
            raise self.provider_error
        if isinstance(self.provider_data, bytes):
            return httpx.Response(self.provider_status, content=self.provider_data)
        return httpx.Response(
            self.provider_status, json=self.provider_data, headers=self.provider_headers
        )

    @property
    def provider_requests(self):
        return [request for request in self.requests if request.url.host == "openrouter.ai"]


class ApiContracts(unittest.TestCase):
    def setUp(self):
        self.upstreams = FakeUpstreams()
        self.app = create_app(CONFIG, httpx.MockTransport(self.upstreams.handle))
        self.client = TestClient(self.app)
        self.client.__enter__()

    def tearDown(self):
        self.client.__exit__(None, None, None)

    def ask(self, prompt="Explain my monthly spend", token="user-a", **fields):
        payload = {"prompt": prompt, "model": MODEL_ID, "data_mode": "synthetic", **fields}
        return self.client.post(
            "/ai/chat", json=payload, headers={"Authorization": f"Bearer {token}"}
        )

    def test_real_graph_returns_android_contract_and_currency_separated_facts(self):
        response = self.ask()
        self.assertEqual(200, response.status_code)
        reply = response.json()
        self.assertEqual({"answer", "conversation_id"}, set(reply))
        self.assertIn("Fictional example", reply["answer"])
        self.assertIn("ZAR 200.00", reply["answer"])
        self.assertIn("USD 12.00", reply["answer"])
        self.assertLessEqual(len(reply["answer"].encode("utf-16-le")) // 2, 16000)
        self.assertRegex(reply["conversation_id"], r"^[A-Za-z0-9_-]{1,128}$")
        self.assertEqual(1, len(self.upstreams.provider_requests))

    def test_raw_prompt_and_identity_never_reach_provider(self):
        question = "Explain my spending. PRIVATE_PERSON_123 account PRIVATE_BANK_456"
        self.assertEqual(200, self.ask(question).status_code)
        request = self.upstreams.provider_requests[0]
        wire = request.content.decode()
        self.assertNotIn("PRIVATE_", wire)
        self.assertNotIn("user-a", wire)
        self.assertNotIn(str(OWNER_A), wire)
        self.assertNotIn(CONFIG.publishable_key, wire)
        body = json.loads(wire)
        self.assertEqual(MODEL_ID, body["model"])
        self.assertFalse(body["provider"]["allow_fallbacks"])
        self.assertNotIn("models", body)
        self.assertNotIn("response_format", body)
        self.assertEqual(
            "explain monthly equivalent spending",
            json.loads(body["messages"][1]["content"])["task"],
        )

    def test_unknown_question_is_not_forwarded_or_simulated(self):
        response = self.ask("My secret is PRIVATE_123. Tell me about weather")
        self.assertEqual(200, response.status_code)
        self.assertIn("Fictional examples only", response.json()["answer"])
        self.assertEqual([], self.upstreams.provider_requests)

    def test_missing_invalid_and_expired_auth_never_call_provider(self):
        payload = {"prompt": "spend", "model": MODEL_ID, "data_mode": "synthetic"}
        for headers in (
            {},
            {"Authorization": "Basic secret"},
            {"Authorization": "Bearer " + "x" * 8193},
        ):
            with self.subTest(headers_type=next(iter(headers), "missing")):
                response = self.client.post("/ai/chat", json=payload, headers=headers)
                self.assertEqual(401, response.status_code)
                self.assertEqual("Bearer", response.headers["www-authenticate"])
        self.assertEqual(401, self.ask(token="expired").status_code)
        self.assertEqual([], self.upstreams.provider_requests)

    def test_identity_is_fetched_not_decoded_from_unverified_token(self):
        for identifier in ("invalid-uuid", 202, None):
            self.upstreams.auth_data = {"id": identifier}
            self.assertEqual(503, self.ask().status_code)
        self.assertEqual([], self.upstreams.provider_requests)

    def test_auth_provider_redirect_and_outage_fail_closed(self):
        for status in (302, 429, 500):
            with self.subTest(status=status):
                self.upstreams.auth_status = status
                self.assertEqual(503, self.ask().status_code)
        self.assertEqual([], self.upstreams.provider_requests)

    def test_cross_user_and_tampered_conversation_are_denied_before_provider(self):
        identifier = self.ask().json()["conversation_id"]
        self.assertEqual(200, self.ask(conversation_id=identifier).status_code)
        count = len(self.upstreams.provider_requests)
        self.assertEqual(404, self.ask(token="user-b", conversation_id=identifier).status_code)
        changed = ("0" if identifier[0] != "0" else "1") + identifier[1:]
        self.assertEqual(404, self.ask(conversation_id=changed).status_code)
        self.assertEqual(count, len(self.upstreams.provider_requests))

    def test_expired_conversation_is_denied(self):
        expired = ConversationIds(CONFIG.conversation_secret).issue(
            OWNER_A, int(time.time()) - 3600
        )
        self.assertEqual(404, self.ask(conversation_id=expired).status_code)
        self.assertEqual([], self.upstreams.provider_requests)

    def test_client_cannot_select_real_data_paid_model_or_owner(self):
        for fields in (
            {"data_mode": "real"},
            {"model": MODEL_ID.removesuffix(":free")},
            {"user_id": 202},
            {"conversation_id": "bad/id"},
        ):
            with self.subTest(fields=fields):
                self.assertEqual(422, self.ask(**fields).status_code)
        self.assertEqual([], self.upstreams.requests)

    def test_validation_does_not_echo_private_input(self):
        for question in (" ", "PRIVATE_VALUE" * 400):
            response = self.ask(question)
            self.assertEqual(422, response.status_code)
            self.assertNotIn("PRIVATE_VALUE", response.text)
            self.assertEqual({"detail": "Invalid chat request"}, response.json())

    def test_body_is_bounded_before_json_parsing(self):
        response = self.client.post(
            "/ai/chat", content=b"x" * 32769, headers={"Content-Type": "application/json"}
        )
        self.assertEqual(413, response.status_code)
        self.assertEqual([], self.upstreams.requests)

    def test_legacy_auth_crud_and_schema_routes_are_absent(self):
        paths = self.client.get("/openapi.json").json()["paths"]
        self.assertEqual({"/health", "/ai/chat"}, set(paths))
        for path in ("/login", "/add_user", "/user/202", "/subscriptions", "/budgets"):
            self.assertEqual(404, self.client.get(path).status_code)

    def test_missing_config_keeps_health_available_and_chat_closed(self):
        with TestClient(
            create_app(Settings(), httpx.MockTransport(self.upstreams.handle))
        ) as client:
            self.assertFalse(client.get("/health").json()["chat_configured"])
            response = client.post(
                "/ai/chat",
                json={"prompt": "spend", "model": MODEL_ID, "data_mode": "synthetic"},
                headers={"Authorization": "Bearer user-a"},
            )
            self.assertEqual(503, response.status_code)
        self.assertEqual([], self.upstreams.requests)

    def test_provider_rate_limit_and_failure_bodies_are_not_leaked(self):
        self.upstreams.provider_data = {"error": "SYNTHETIC_PROVIDER_SECRET"}
        for upstream, expected in ((429, 429), (401, 502), (302, 502), (500, 502)):
            with self.subTest(upstream=upstream):
                self.upstreams.provider_status = upstream
                response = self.ask()
                self.assertEqual(expected, response.status_code)
                self.assertNotIn("SYNTHETIC_PROVIDER_SECRET", response.text)
                if expected == 429:
                    self.assertEqual("60", response.headers["retry-after"])

    def test_provider_timeout_is_not_retried(self):
        self.upstreams.provider_error = httpx.ReadTimeout("synthetic timeout")
        self.assertEqual(504, self.ask().status_code)
        self.assertEqual(1, len(self.upstreams.provider_requests))

    def test_provider_retry_after_is_honored(self):
        self.upstreams.provider_status = 429
        self.upstreams.provider_headers = {"Retry-After": "120"}
        self.assertEqual("120", self.ask().headers["retry-after"])

    def test_wrong_type_and_excessive_answer_text_fail(self):
        for message in ("invalid", {"content": 123}, {"content": "x" * 4001}, {"content": None}):
            self.upstreams.provider_data = {
                "choices": [{"finish_reason": "stop", "message": message}]
            }
            self.assertEqual(502, self.ask().status_code)

    def test_malformed_partial_tool_and_oversized_answers_fail(self):
        for data in (
            {"choices": []},
            {"choices": [{"message": {"content": "ok"}}]},
            {"choices": [{"finish_reason": "length", "message": {"content": "partial"}}]},
            {
                "choices": [
                    {
                        "finish_reason": "stop",
                        "message": {"content": "ok", "tool_calls": [{"name": "write"}]},
                    }
                ]
            },
            {"choices": [{"finish_reason": "stop", "message": {"content": " "}}]},
        ):
            with self.subTest(data=data):
                self.upstreams.provider_data = data
                self.assertEqual(502, self.ask().status_code)

    def test_provider_response_byte_bound_and_invalid_json(self):
        for data in (b"x" * 65537, b"invalid json", b"[]"):
            with self.subTest(length=len(data)):
                self.upstreams.provider_data = data
                self.assertEqual(502, self.ask().status_code)

    def test_per_user_limit_prevents_extra_model_call(self):
        for _ in range(5):
            self.assertEqual(200, self.ask().status_code)
        self.assertEqual(429, self.ask().status_code)
        self.assertEqual(5, len(self.upstreams.provider_requests))

    def test_logs_have_correlation_and_no_questions_or_tokens(self):
        with self.assertLogs("agentcostcontrol.ai", level="INFO") as logs:
            self.assertEqual(200, self.ask("spend PRIVATE_ABC", token="private-token").status_code)
        text = " ".join(logs.output)
        self.assertIn("request_id=", text)
        self.assertIn("duration_ms=", text)
        self.assertNotIn("PRIVATE_ABC", text)
        self.assertNotIn("private-token", text)

    def test_overall_deadline_returns_recoverable_error(self):
        async def timeout(*args):
            raise TimeoutError

        with patch("agentic_subscription_manager.api.run_until_disconnect", timeout):
            self.assertEqual(504, self.ask().status_code)


class PureContracts(unittest.TestCase):
    def test_retry_after_is_bounded_for_malformed_and_numeric_headers(self):
        for value, expected in (
            ("120", 120),
            ("0", 1),
            ("999999", 86400),
            ("not a date", 60),
            ("²", 60),
            ("1" * 5000, 60),
        ):
            self.assertEqual(expected, retry_delay(value))

    def test_only_active_records_and_final_currency_rounding_match_java(self):
        today = date(2026, 10, 7)
        records = tuple(
            SubscriptionEvidence("Example", Decimal("0.06"), "ZAR", True, today) for _ in range(2)
        ) + (
            SubscriptionEvidence("Example", Decimal("12.00"), "USD", False, today),
            SubscriptionEvidence("Example", Decimal("900.00"), "ZAR", False, today, active=False),
        )
        self.assertEqual({"USD": "12.00", "ZAR": "0.01"}, monthly_totals(records))
        self.assertEqual({}, monthly_totals(()))

    def test_conversation_owner_expiry_future_and_secret_rotation(self):
        ids = ConversationIds(CONFIG.conversation_secret)
        issued = ids.issue(OWNER_A, 1791360000)
        self.assertTrue(ids.verify(issued, OWNER_A, 1791363599))
        self.assertFalse(ids.verify(issued, OWNER_B, 1791360100))
        self.assertFalse(ids.verify(issued, OWNER_A, 1791363600))
        self.assertFalse(ids.verify(issued, OWNER_A, 1791359999))
        self.assertFalse(ConversationIds("rotated").verify(issued, OWNER_A, 1791360100))

    def test_server_configuration_rejects_privileged_key_and_unsafe_url(self):
        for config in (
            {"publishable_key": "sb_secret_unsafe"},
            {"supabase_url": "http://project.invalid"},
            {"supabase_url": "https://secret@project.invalid"},
            {"supabase_url": "https://project.invalid/extra"},
            {"conversation_secret": "short"},
        ):
            with self.subTest(config=config), self.assertRaises(ValueError):
                Settings(**config)
        self.assertNotIn(CONFIG.provider_key, repr(CONFIG))

    def test_global_minute_daily_budget_and_window_expiry(self):
        budget = RequestBudget()
        now = 1791360000.0
        for index in range(20):
            budget.reserve_call(UUID(int=index + 1), now)
        with self.assertRaises(RemoteFailure):
            budget.reserve_call(UUID(int=100), now)
        for index in range(20):
            budget.reserve_call(UUID(int=index + 1), now + 60)
        for index in range(10):
            budget.reserve_call(UUID(int=index + 1), now + 120)
        with self.assertRaises(RemoteFailure):
            budget.reserve_call(UUID(int=100), now + 180)
        budget.reserve_call(OWNER_A, now + 86400)
        self.assertEqual(1, budget.daily_count)


class AsyncContracts(unittest.IsolatedAsyncioTestCase):
    async def test_chunked_body_cannot_bypass_byte_limit(self):
        chunks = iter(
            [
                {"type": "http.request", "body": b"x" * 20000, "more_body": True},
                {"type": "http.request", "body": b"x" * 20000, "more_body": False},
            ]
        )

        async def receive():
            return next(chunks)

        emitted = []

        async def send(message):
            emitted.append(message)

        async def forbidden_app(*args):
            self.fail("Oversized body reached the application")

        await BodyLimit(forbidden_app)({"type": "http"}, receive, send)
        self.assertEqual(413, emitted[0]["status"])

    async def test_disconnect_cancels_local_workflow(self):
        started = asyncio.Event()
        cancelled = asyncio.Event()

        class SlowGraph:
            async def ainvoke(self, *args):
                started.set()
                try:
                    await asyncio.Event().wait()
                finally:
                    cancelled.set()

        class DisconnectingRequest:
            async def receive(self):
                await started.wait()
                return {"type": "http.disconnect"}

        with self.assertRaises(RemoteFailure) as raised:
            await run_until_disconnect(DisconnectingRequest(), SlowGraph(), "spend")
        self.assertEqual(499, raised.exception.status)
        self.assertTrue(cancelled.is_set())

    async def test_concurrent_admission_is_bounded_and_released_on_cancel(self):
        budget = RequestBudget()
        entered = asyncio.Event()

        async def hold():
            async with budget.admit():
                entered.set()
                await asyncio.Event().wait()

        tasks = [asyncio.create_task(hold()) for _ in range(4)]
        await entered.wait()
        await asyncio.sleep(0)
        try:
            with self.assertRaises(RemoteFailure):
                async with budget.admit():
                    self.fail("fifth request admitted")
        finally:
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
        self.assertEqual(0, budget.in_flight)


if __name__ == "__main__":
    unittest.main()
