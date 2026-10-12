"""Streaming contracts include partial failure, ownership, framing and cancellation."""

import asyncio
import json
import unittest
from contextlib import aclosing

import httpx
from fastapi.testclient import TestClient
from test_ai import CONFIG, FakeUpstreams

from agentic_subscription_manager.adapters import OpenRouterExplanation
from agentic_subscription_manager.api import create_app
from agentic_subscription_manager.contracts import MODEL_ID
from agentic_subscription_manager.evidence import AnalysisTask


def chunk(text=None, finish=None, **delta):
    if text is not None:
        delta["content"] = text
    return {"choices": [{"index": 0, "delta": delta, "finish_reason": finish}]}


def wire(*events):
    return b": keepalive\r\n\r\n" + b"".join(
        ("data: " + (value if isinstance(value, str) else json.dumps(value)) + "\r\n\r\n").encode()
        for value in events
    )


class Fragments(httpx.AsyncByteStream):
    def __init__(self, body):
        self.body = body

    async def __aiter__(self):
        for offset in range(0, len(self.body), 7):
            yield self.body[offset : offset + 7]


class StreamingUpstreams(FakeUpstreams):
    def __init__(self):
        super().__init__()
        self.body = wire(
            chunk("Review "), chunk("fictional plans."), chunk(finish="stop"), "[DONE]"
        )
        self.content_type = "text/event-stream"

    def handle(self, request):
        if request.url.host != "openrouter.ai":
            return super().handle(request)
        self.requests.append(request)
        return httpx.Response(
            self.provider_status,
            stream=Fragments(self.body),
            headers={
                "content-type": self.content_type,
                **self.provider_headers,
            },
        )


def events(response):
    parsed = []
    for block in response.text.split("\n\n"):
        if block:
            name, data = block.split("\n", 1)
            parsed.append((name.removeprefix("event: "), json.loads(data.removeprefix("data: "))))
    return parsed


class StreamingContracts(unittest.TestCase):
    def setUp(self):
        self.upstreams = StreamingUpstreams()
        self.app = create_app(CONFIG, httpx.MockTransport(self.upstreams.handle))
        self.client = TestClient(self.app)
        self.client.__enter__()

    def tearDown(self):
        self.client.__exit__(None, None, None)

    def ask(self, token="user-a", **fields):
        return self.client.post(
            "/ai/chat",
            json={
                "prompt": "monthly spending PRIVATE_PERSON",
                "model": MODEL_ID,
                "data_mode": "synthetic",
                "stream": True,
                **fields,
            },
            headers={"Authorization": f"Bearer {token}"},
        )

    def test_real_graph_streams_facts_and_provider_deltas_before_done(self):
        response = self.ask()
        self.assertEqual(200, response.status_code)
        self.assertTrue(response.headers["content-type"].startswith("text/event-stream"))
        self.assertEqual("no-store", response.headers["cache-control"])
        self.assertEqual("no", response.headers["x-accel-buffering"])
        received = events(response)
        self.assertEqual(["start", "delta", "delta", "delta", "done"], [v[0] for v in received])
        self.assertEqual(received[0][1], received[-1][1])
        self.assertIn("ZAR 200.00", received[1][1]["text"])
        self.assertEqual("Review ", received[2][1]["text"])
        request = self.upstreams.provider_requests[0]
        body = json.loads(request.content)
        self.assertTrue(body["stream"])
        self.assertEqual(MODEL_ID, body["model"])
        self.assertEqual("none", body["tool_choice"])
        self.assertNotIn("tools", body)
        self.assertNotIn("PRIVATE", request.content.decode())
        self.assertEqual(0, self.app.state.budget.in_flight)

    def test_auth_and_conversation_checks_happen_before_streaming(self):
        self.assertEqual(401, self.ask(token="expired").status_code)
        self.assertEqual([], self.upstreams.provider_requests)
        identifier = events(self.ask())[0][1]["conversation_id"]
        count = len(self.upstreams.provider_requests)
        self.assertEqual(404, self.ask(token="user-b", conversation_id=identifier).status_code)
        self.assertEqual(count, len(self.upstreams.provider_requests))

    def test_unsupported_question_is_a_completed_local_stream_without_provider(self):
        received = events(self.ask(prompt="weather"))
        self.assertEqual(["start", "delta", "done"], [v[0] for v in received])
        self.assertEqual([], self.upstreams.provider_requests)

    def test_invalid_stream_requests_are_rejected(self):
        for fields in (
            {"stream": "true"},
            {"tools": []},
            {"data_mode": "real"},
            {"model": "nvidia/nemotron-3.5-lightning:free"},
        ):
            self.assertEqual(422, self.ask(**fields).status_code)
        self.assertEqual([], self.upstreams.provider_requests)

    def test_partial_failures_never_emit_done_or_provider_errors(self):
        invalid = [
            wire(chunk("Partial")),
            wire(chunk("Partial"), chunk(finish="length"), "[DONE]"),
            wire(chunk("Partial"), {"error": {"message": "PRIVATE_UPSTREAM"}}),
            wire(chunk("Partial"), chunk(tool_calls=[{"function": {"name": "delete_email"}}])),
            wire(chunk("Partial"), chunk(function_call={"name": "send_email"})),
            wire(chunk("Partial"), "not json"),
            wire(chunk("Partial"), chunk(123)),
            wire(chunk("Partial"), chunk("x" * 4001)),
            wire(chunk(finish="stop"), "[DONE]"),
            wire(chunk("Partial"), "[DONE]"),
            wire(chunk("Partial"), chunk(finish="stop")),
            b":" + b"x" * 65537,
        ]
        for body in invalid:
            with self.subTest(body_length=len(body)):
                self.app.state.budget.recent.clear()
                self.upstreams.body = body
                response = self.ask()
                received = events(response)
                self.assertEqual("error", received[-1][0])
                self.assertNotIn("done", [value[0] for value in received])
                self.assertNotIn("PRIVATE_UPSTREAM", response.text)
                self.assertEqual(0, self.app.state.budget.in_flight)

    def test_provider_rate_limit_is_a_safe_stream_error(self):
        self.upstreams.provider_status = 429
        self.upstreams.provider_headers = {"retry-after": "7"}
        received = events(self.ask())
        self.assertEqual("error", received[-1][0])
        self.assertEqual(429, received[-1][1]["status"])
        self.assertEqual(7, received[-1][1]["retry_after"])

    def test_non_stream_response_from_provider_is_rejected(self):
        self.upstreams.content_type = "application/json"
        self.assertEqual("error", events(self.ask())[-1][0])

    def test_terminal_usage_frame_can_repeat_stop_without_repeating_text(self):
        self.upstreams.body = wire(
            chunk("Review sample plans."),
            chunk(finish="stop"),
            {**chunk("", finish="stop"), "usage": {"completion_tokens": 20}},
            "[DONE]",
        )
        received = events(self.ask())
        self.assertEqual("done", received[-1][0])
        self.assertEqual(1, sum(part.get("text") == "Review sample plans." for _, part in received))

    def test_usage_frame_cannot_add_content_after_stop(self):
        self.upstreams.body = wire(
            chunk("First"),
            chunk(finish="stop"),
            {**chunk("unexpected", finish="stop"), "usage": {}},
            "[DONE]",
        )
        received = events(self.ask())
        self.assertEqual("error", received[-1][0])
        self.assertNotIn("done", [name for name, _ in received])


class StreamingCancellation(unittest.IsolatedAsyncioTestCase):
    async def test_api_disconnect_cancels_graph_and_releases_request_slot(self):
        waiting = asyncio.Event()
        closed = asyncio.Event()
        incoming = asyncio.Queue()
        sent = []

        class WaitingStream(httpx.AsyncByteStream):
            async def __aiter__(self):
                yield wire(chunk("First"))
                waiting.set()
                await asyncio.Event().wait()

            async def aclose(self):
                closed.set()

        upstreams = FakeUpstreams()

        async def handle(request):
            if request.url.host != "openrouter.ai":
                return upstreams.handle(request)
            return httpx.Response(
                200, headers={"content-type": "text/event-stream"}, stream=WaitingStream()
            )

        app = create_app(CONFIG, httpx.MockTransport(handle))
        await incoming.put(
            {
                "type": "http.request",
                "body": json.dumps(
                    {
                        "prompt": "monthly spending",
                        "model": MODEL_ID,
                        "data_mode": "synthetic",
                        "stream": True,
                    }
                ).encode(),
                "more_body": False,
            }
        )

        async def send(message):
            sent.append(message)

        scope = {
            "type": "http",
            "asgi": {"version": "3.0", "spec_version": "2.3"},
            "method": "POST",
            "path": "/ai/chat",
            "raw_path": b"/ai/chat",
            "query_string": b"",
            "scheme": "https",
            "http_version": "1.1",
            "headers": [
                (b"authorization", b"Bearer user-a"),
                (b"content-type", b"application/json"),
            ],
            "client": ("127.0.0.1", 10000),
            "server": ("127.0.0.1", 8443),
        }
        async with app.router.lifespan_context(app):
            async with asyncio.timeout(3):
                run = asyncio.create_task(app(scope, incoming.get, send))
                try:
                    await waiting.wait()
                    await incoming.put({"type": "http.disconnect"})
                    await run
                finally:
                    run.cancel()
                    await asyncio.gather(run, return_exceptions=True)
            async with asyncio.timeout(1):
                await closed.wait()
            self.assertTrue(closed.is_set())
            self.assertEqual(0, app.state.budget.in_flight)
            self.assertNotIn(b"event: done", b"".join(message.get("body", b"") for message in sent))

    async def test_provider_yields_before_completion_and_closes_on_cancel(self):
        resumed = asyncio.Event()
        closed = asyncio.Event()

        class SlowStream(httpx.AsyncByteStream):
            async def __aiter__(self):
                yield wire(chunk("First"))
                resumed.set()
                await asyncio.Event().wait()

            async def aclose(self):
                closed.set()

        async def handle(request):
            return httpx.Response(
                200, headers={"content-type": "text/event-stream"}, stream=SlowStream()
            )

        async with httpx.AsyncClient(transport=httpx.MockTransport(handle)) as client:
            provider = OpenRouterExplanation(client, "synthetic")
            async with aclosing(provider.stream_explain(AnalysisTask.SPEND, {})) as stream:
                self.assertEqual("First", await anext(stream))
                self.assertFalse(resumed.is_set())
            self.assertTrue(closed.is_set())


if __name__ == "__main__":
    unittest.main()
