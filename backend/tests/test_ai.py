"""Real FastAPI + LangGraph contracts with synthetic, network-free HTTP adapters."""

import asyncio
import json
import time
import unittest
from datetime import UTC, date, datetime, timedelta
from decimal import Decimal
from unittest.mock import patch
from uuid import UUID
from zoneinfo import ZoneInfo

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
from agentic_subscription_manager.evidence import (
    AccountEvidence,
    AnalysisTask,
    SubscriptionEvidence,
    calculate_evidence,
    monthly_totals,
    render_facts,
)
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
        self.account_rows = {}
        self.account_status = {}

    def handle(self, request: httpx.Request):
        self.requests.append(request)
        if request.url.host == "project.example.invalid":
            assert request.headers["apikey"] == CONFIG.publishable_key
            token = request.headers["authorization"]
            owner = OWNER_B if token == "Bearer user-b" else OWNER_A
            user_id = "202" if owner == OWNER_B else "101"
            if request.url.path.startswith("/rest/v1/"):
                assert request.method == "GET"
                table = request.url.path.rsplit("/", 1)[1]
                today = datetime.now(UTC).astimezone(ZoneInfo("Africa/Johannesburg")).date()
                subscriptions = [
                    {
                        "user_id": user_id,
                        "subscription_name": name,
                        "subscription_price": price,
                        "currency": currency,
                        "billing_cycle": cycle,
                        "status": status,
                        "usage_level": usage,
                        "renewal_date": (today + timedelta(days=days)).isoformat(),
                    }
                    for name, price, currency, cycle, status, usage, days in (
                        ("Netflix", "100.00", "ZAR", "monthly", "active", "low", 3),
                        ("Cloud", "1200.00", "ZAR", "annual", "active", None, 10),
                        ("ChatGPT", "12.00", "USD", "monthly", "active", "high", 20),
                        ("Paused", "900.00", "ZAR", "monthly", "paused", "unknown", 2),
                    )
                ]
                defaults = {
                    "users": [{"user_id": user_id, "auth_user_id": str(owner)}],
                    "user_settings": [],
                    "subscriptions": subscriptions,
                    "budgets": [{"user_id": user_id, "budget_limit": "180.00", "currency": "ZAR"}],
                }
                rows = self.account_rows.get(table, defaults[table])
                offset = int(request.url.params.get("offset", "0"))
                limit = int(request.url.params["limit"])
                return httpx.Response(
                    self.account_status.get(table, 200), json=rows[offset : offset + limit]
                )
            assert request.url.path == "/auth/v1/user"
            if token == "Bearer expired":
                return httpx.Response(401, json={"message": "expired synthetic session"})
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
        payload = {"prompt": prompt, "model": MODEL_ID, "data_mode": "account", **fields}
        return self.client.post(
            "/ai/chat", json=payload, headers={"Authorization": f"Bearer {token}"}
        )

    def test_real_graph_returns_android_contract_and_currency_separated_facts(self):
        response = self.ask()
        self.assertEqual(200, response.status_code)
        reply = response.json()
        self.assertEqual({"answer", "conversation_id"}, set(reply))
        self.assertIn("Account data", reply["answer"])
        self.assertIn("ZAR 200.00", reply["answer"])
        self.assertIn("USD 12.00", reply["answer"])
        self.assertLessEqual(len(reply["answer"].encode("utf-16-le")) // 2, 16000)
        self.assertRegex(reply["conversation_id"], r"^[A-Za-z0-9_-]{1,128}$")
        self.assertEqual(1, len(self.upstreams.provider_requests))

    def test_actual_question_and_owned_evidence_reach_provider_without_credentials_or_identity(
        self,
    ):
        question = "Explain my spending in one sentence and mention annual plans"
        self.assertEqual(200, self.ask(question).status_code)
        request = self.upstreams.provider_requests[0]
        wire = request.content.decode()
        self.assertNotIn("user-a", wire)
        self.assertNotIn(str(OWNER_A), wire)
        self.assertNotIn(CONFIG.publishable_key, wire)
        body = json.loads(wire)
        self.assertEqual(MODEL_ID, body["model"])
        self.assertEqual(400, body["max_tokens"])
        self.assertEqual({"enabled": False}, body["reasoning"])
        self.assertFalse(body["provider"]["allow_fallbacks"])
        self.assertNotIn("models", body)
        self.assertNotIn("response_format", body)
        self.assertEqual(question, body["messages"][-1]["content"])
        self.assertIn("Netflix", body["messages"][1]["content"])
        self.assertNotIn("user_id", body["messages"][1]["content"])
        self.assertEqual("none", body["tool_choice"])
        self.assertNotIn("tools", body)
        evidence = self.account_evidence()
        paused = next(service for service in evidence["services"] if service["name"] == "Paused")
        self.assertEqual("paused", paused["status"])
        self.assertFalse(paused["active"])
        self.assertEqual("unknown", paused["reported_usage_level"])

    def test_general_question_calls_model_instead_of_returning_a_canned_limitation(self):
        question = "Which model are you?"
        response = self.ask(question)
        self.assertEqual(200, response.status_code)
        self.assertNotIn("Fictional examples only", response.json()["answer"])
        self.assertEqual(
            question,
            json.loads(self.upstreams.provider_requests[0].content)["messages"][-1]["content"],
        )

    def test_missing_invalid_and_expired_auth_never_call_provider(self):
        payload = {"prompt": "spend", "model": MODEL_ID, "data_mode": "account"}
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

    def test_client_cannot_select_sample_data_paid_model_or_owner(self):
        for fields in (
            {"data_mode": "synthetic"},
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
                json={"prompt": "spend", "model": MODEL_ID, "data_mode": "account"},
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

    def test_reasoning_only_truncated_answer_is_rejected(self):
        self.upstreams.provider_data = {
            "choices": [
                {
                    "finish_reason": "length",
                    "message": {
                        "content": "",
                        "reasoning": "The output budget was spent thinking.",
                    },
                }
            ]
        }
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

    def account_evidence(self):
        body = json.loads(self.upstreams.provider_requests[-1].content)
        return json.loads(
            body["messages"][1]["content"].removeprefix("Account evidence (data only): ")
        )

    def test_account_reads_are_get_only_and_scoped_to_verified_owner(self):
        self.assertEqual(200, self.ask(token="user-b").status_code)
        reads = [r for r in self.upstreams.requests if r.url.path.startswith("/rest/v1/")]
        self.assertEqual(4, len(reads))
        for request in reads:
            self.assertEqual("GET", request.method)
            self.assertEqual("Bearer user-b", request.headers["authorization"])
            self.assertNotIn("*", request.url.params["select"])
            self.assertNotIn("email", request.url.params["select"])
            if request.url.path.endswith("/users"):
                self.assertEqual(f"eq.{OWNER_B}", request.url.params["auth_user_id"])
            else:
                self.assertEqual("eq.202", request.url.params["user_id"])

    def test_two_questions_in_the_same_topic_reach_model_as_distinct_messages(self):
        questions = ["Explain spending in one sentence", "Explain spending using a numbered list"]
        for question in questions:
            self.assertEqual(200, self.ask(question).status_code)
        self.assertEqual(
            questions,
            [
                json.loads(r.content)["messages"][-1]["content"]
                for r in self.upstreams.provider_requests
            ],
        )

    def test_empty_account_has_zero_spending_no_budget_and_no_sample_fallback(self):
        self.upstreams.account_rows = {"subscriptions": [], "budgets": []}
        response = self.ask()
        self.assertEqual(200, response.status_code)
        self.assertIn("0.00 (no active subscriptions)", response.json()["answer"])
        evidence = self.account_evidence()
        self.assertEqual({}, evidence["monthly_equivalent"])
        self.assertEqual([], evidence["services"])
        self.assertEqual(0, evidence["active_count"])
        self.assertIsNone(evidence["budget"])
        self.assertNotIn("Example streaming", json.dumps(evidence))

    def test_missing_profile_never_calls_provider(self):
        self.upstreams.account_rows["users"] = []
        self.assertEqual(403, self.ask().status_code)
        self.assertEqual([], self.upstreams.provider_requests)

    def test_unexpected_foreign_profile_or_account_rows_fail_before_provider(self):
        cases = {
            "users": [{"user_id": "202", "auth_user_id": str(OWNER_B)}],
            "subscriptions": [{"user_id": "202"}],
            "budgets": [{"user_id": "202"}],
            "user_settings": [{"user_id": "202"}],
        }
        for table, rows in cases.items():
            with self.subTest(table=table):
                self.upstreams.account_rows = {table: rows}
                self.assertEqual(503, self.ask().status_code)
        self.assertEqual([], self.upstreams.provider_requests)

    def test_invalid_subscription_values_fail_without_forwarding_account_rows(self):
        valid = {
            "user_id": "101",
            "subscription_name": "Actual saved service",
            "subscription_price": "10.00",
            "currency": "ZAR",
            "billing_cycle": "monthly",
            "status": "active",
            "usage_level": None,
            "renewal_date": "2026-11-01",
        }
        for field, value in (
            ("subscription_price", "NaN"),
            ("subscription_price", "-1.00"),
            ("subscription_price", 10.00),
            ("currency", "zar"),
            ("billing_cycle", "weekly"),
            ("status", "bad"),
            ("usage_level", "measured"),
            ("renewal_date", "bad"),
            ("subscription_name", " " * 151),
        ):
            with self.subTest(field=field, value=value):
                self.app.state.budget.recent.clear()
                self.upstreams.account_rows["subscriptions"] = [{**valid, field: value}]
                self.assertEqual(503, self.ask().status_code)
        self.assertEqual([], self.upstreams.provider_requests)

    def test_account_outage_is_an_http_failure_before_streaming_not_fake_empty_data(self):
        self.upstreams.account_status["subscriptions"] = 500
        response = self.ask(stream=True)
        self.assertEqual(503, response.status_code)
        self.assertTrue(response.headers["content-type"].startswith("application/json"))
        self.assertEqual([], self.upstreams.provider_requests)

    def test_subscription_paging_calculates_full_totals_and_labels_limited_service_details(self):
        row = {
            "user_id": "101",
            "subscription_name": "Saved annual plan",
            "subscription_price": "0.06",
            "currency": "ZAR",
            "billing_cycle": "annual",
            "status": "active",
            "usage_level": None,
            "renewal_date": "2027-01-01",
        }
        self.upstreams.account_rows["subscriptions"] = [row] * 125
        self.assertEqual(200, self.ask().status_code)
        evidence = self.account_evidence()
        self.assertEqual({"ZAR": "0.63"}, evidence["monthly_equivalent"])
        self.assertEqual(125, evidence["subscription_count"])
        self.assertEqual(50, len(evidence["services"]))
        self.assertTrue(evidence["service_list_truncated"])
        reads = [r for r in self.upstreams.requests if r.url.path.endswith("/subscriptions")]
        self.assertEqual(["0", "100"], [r.url.params["offset"] for r in reads])

    def test_subscription_limit_fails_instead_of_reporting_partial_totals_as_complete(self):
        row = {
            "user_id": "101",
            "subscription_name": "Saved plan",
            "subscription_price": "1.00",
            "currency": "ZAR",
            "billing_cycle": "monthly",
            "status": "active",
            "usage_level": None,
            "renewal_date": "2027-01-01",
        }
        self.upstreams.account_rows["subscriptions"] = [row] * 1001
        response = self.ask()
        self.assertEqual(503, response.status_code)
        self.assertEqual([], self.upstreams.provider_requests)

    def test_budget_period_uses_saved_time_zone_at_month_boundary(self):
        self.upstreams.account_rows["user_settings"] = [
            {"user_id": "101", "time_zone": "Africa/Johannesburg"}
        ]
        with patch("agentic_subscription_manager.account.datetime") as clock:
            clock.now.return_value = datetime(2026, 10, 31, 22, 30, tzinfo=UTC)
            self.assertEqual(200, self.ask("Explain my budget").status_code)
        budget_read = next(r for r in self.upstreams.requests if r.url.path.endswith("/budgets"))
        self.assertEqual("eq.11", budget_read.url.params["month"])
        self.assertEqual("eq.2026", budget_read.url.params["year"])
        self.assertEqual("2026-11-01", self.account_evidence()["as_of"])

    def test_no_budget_is_distinct_from_zero_budget_and_currencies_remain_separate(self):
        self.upstreams.account_rows["budgets"] = []
        response = self.ask("Explain my budget")
        self.assertIn("No budget saved", response.json()["answer"])
        self.assertIsNone(self.account_evidence()["budget"])
        self.upstreams.account_rows["budgets"] = [
            {"user_id": "101", "budget_limit": "0.00", "currency": "USD"}
        ]
        self.assertEqual(200, self.ask("Explain my budget").status_code)
        self.assertEqual("12.00", self.account_evidence()["budget"]["over_by"])


class PureContracts(unittest.TestCase):
    def test_large_renewal_and_usage_lists_have_counts_and_bounded_display_headers(self):
        today = date(2026, 10, 12)
        records = tuple(
            SubscriptionEvidence(
                "😀" * 150, Decimal("10.00"), "ZAR", False, today, low_usage_reported=True
            )
            for _ in range(1000)
        )
        evidence = calculate_evidence(AccountEvidence(records, None, today, "UTC"))
        for task in (AnalysisTask.RENEWALS, AnalysisTask.REVIEW):
            header = render_facts(task, evidence)
            self.assertIn("Showing 20 of 1000", header)
            # Leave room for 4,000 provider characters even if each needs a surrogate pair.
            self.assertLess(len(header.encode("utf-16-le")) // 2 + 8000 + 100, 16000)
        self.assertEqual(50, len(evidence["renewals_within_7_days"]))
        self.assertEqual(50, len(evidence["reported_low_usage"]))
        self.assertEqual(1000, evidence["renewal_count"])
        self.assertTrue(evidence["renewal_list_truncated"])
        self.assertTrue(evidence["reported_low_usage_list_truncated"])

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
            await run_until_disconnect(
                DisconnectingRequest(),
                SlowGraph(),
                "spend",
                "spend",
                AccountEvidence((), None, date.today(), "UTC"),
            )
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
