"""Only this boundary communicates with Supabase Auth and the model provider."""

import json
import re
from collections.abc import AsyncIterator
from datetime import UTC, datetime
from email.utils import parsedate_to_datetime
from uuid import UUID

import httpx

from .contracts import MODEL_ID
from .evidence import AnalysisTask
from .streaming import event_data


class RemoteFailure(Exception):
    def __init__(self, status: int, code: str, retry_after: int = 60) -> None:
        super().__init__(code)
        self.status = status
        self.code = code
        self.retry_after = retry_after


def retry_delay(value: str) -> int:
    if re.fullmatch(r"[0-9]{1,6}", value):
        return min(86400, max(1, int(value)))
    try:
        until = parsedate_to_datetime(value)
        if until.tzinfo is None:
            return 60
        return min(86400, max(1, int((until - datetime.now(UTC)).total_seconds())))
    except (TypeError, ValueError, OverflowError):
        return 60


async def bounded_json(response: httpx.Response, limit: int = 65536) -> dict:
    body = bytearray()
    async for chunk in response.aiter_bytes():
        if len(body) + len(chunk) > limit:
            raise RemoteFailure(502, "Upstream response too large")
        body.extend(chunk)
    try:
        value = json.loads(body)
    except (ValueError, UnicodeDecodeError) as error:
        raise RemoteFailure(502, "Invalid upstream response") from error
    if not isinstance(value, dict):
        raise RemoteFailure(502, "Invalid upstream response")
    return value


class SupabaseIdentity:
    def __init__(self, client: httpx.AsyncClient, origin: str, key: str) -> None:
        self._client, self._origin, self._key = client, origin, key

    async def verify(self, token: str) -> UUID:
        try:
            async with self._client.stream(
                "GET",
                f"{self._origin}/auth/v1/user",
                headers={"apikey": self._key, "Authorization": f"Bearer {token}"},
                timeout=8,
            ) as response:
                if response.status_code in (400, 401, 403):
                    raise RemoteFailure(401, "Sign in again")
                if response.status_code != 200:
                    raise RemoteFailure(503, "Authentication unavailable")
                data = await bounded_json(response)
            identifier = data.get("id")
            if not isinstance(identifier, str):
                raise RemoteFailure(503, "Authentication unavailable")
            return UUID(identifier)
        except (KeyError, TypeError, ValueError) as error:
            raise RemoteFailure(503, "Authentication unavailable") from error
        except httpx.HTTPError as error:
            raise RemoteFailure(503, "Authentication unavailable") from error


class OpenRouterExplanation:
    def __init__(self, client: httpx.AsyncClient, key: str) -> None:
        self._client, self._key = client, key

    def _request(self, task: AnalysisTask, evidence: dict) -> dict:
        return {
            "model": MODEL_ID,
            "messages": [
                {
                    "role": "system",
                    "content": (
                        "Explain this fictional subscription example in three short sentences. "
                        "Use only supplied evidence. Do not give figures; the app renders "
                        "them separately. Do not claim measured usage, currency conversions, "
                        "historical trends or guaranteed savings. "
                        "Do not execute or claim any account changes."
                    ),
                },
                {"role": "user", "content": json.dumps({"task": task.value, "evidence": evidence})},
            ],
            "max_tokens": 400,
            # Reasoning shares this budget; leave room for the short, visible explanation.
            "reasoning": {"enabled": False},
            "provider": {"allow_fallbacks": False},
            # There is no account-action executor. Do not let a model choose any tools.
            "tool_choice": "none",
        }

    async def stream_explain(self, task: AnalysisTask, evidence: dict) -> AsyncIterator[str]:
        request = {**self._request(task, evidence), "stream": True}
        try:
            async with self._client.stream(
                "POST",
                "https://openrouter.ai/api/v1/chat/completions",
                headers={"Authorization": f"Bearer {self._key}"},
                json=request,
                timeout=25,
            ) as response:
                self._check_status(response)
                if not response.headers.get("content-type", "").startswith("text/event-stream"):
                    raise RemoteFailure(502, "Invalid AI stream")
                text = ""
                finished = False
                async for raw in event_data(response):
                    if raw == "[DONE]":
                        if not finished or not text.strip():
                            raise RemoteFailure(502, "Incomplete AI answer")
                        return
                    data = json.loads(raw)
                    if not isinstance(data, dict) or "error" in data:
                        raise RemoteFailure(502, "AI stream failed")
                    choices = data.get("choices")
                    if choices == [] and isinstance(data.get("usage"), dict):
                        continue
                    if not isinstance(choices, list) or len(choices) != 1:
                        raise RemoteFailure(502, "Invalid AI stream")
                    choice = choices[0]
                    if not isinstance(choice, dict) or choice.get("index", 0) != 0:
                        raise RemoteFailure(502, "Invalid AI stream")
                    delta = choice.get("delta")
                    if (
                        not isinstance(delta, dict)
                        or delta.get("tool_calls")
                        or delta.get("function_call")
                    ):
                        raise RemoteFailure(502, "AI tools are unavailable")
                    content = delta.get("content")
                    if finished:
                        # OpenRouter may repeat the terminal choice in its usage frame.
                        if (
                            isinstance(data.get("usage"), dict)
                            and content in (None, "")
                            and choice.get("finish_reason") == "stop"
                        ):
                            continue
                        raise RemoteFailure(502, "Invalid AI stream")
                    if content is not None:
                        if not isinstance(content, str) or len(text) + len(content) > 4000:
                            raise RemoteFailure(502, "Invalid AI answer")
                        text += content
                        if content:
                            yield content
                    reason = choice.get("finish_reason")
                    if reason is not None:
                        if reason != "stop":
                            raise RemoteFailure(502, "Incomplete AI answer")
                        finished = True
                raise RemoteFailure(502, "Incomplete AI answer")
        except httpx.TimeoutException as error:
            raise RemoteFailure(504, "AI provider timed out") from error
        except httpx.HTTPError as error:
            raise RemoteFailure(502, "AI provider unavailable") from error
        except (ValueError, UnicodeError, TypeError) as error:
            raise RemoteFailure(502, "Invalid AI stream") from error

    @staticmethod
    def _check_status(response: httpx.Response) -> None:
        if response.status_code == 429:
            seconds = retry_delay(response.headers.get("retry-after", "60"))
            raise RemoteFailure(429, "AI request limit reached", seconds)
        if response.status_code != 200:
            raise RemoteFailure(502, "AI provider unavailable")

    async def explain(self, task: AnalysisTask, evidence: dict) -> str:
        request = self._request(task, evidence)
        try:
            async with self._client.stream(
                "POST",
                "https://openrouter.ai/api/v1/chat/completions",
                headers={"Authorization": f"Bearer {self._key}"},
                json=request,
                timeout=25,
            ) as response:
                self._check_status(response)
                data = await bounded_json(response)
            # Never accept partial generations, tool calls, or reasoning in place of a final answer.
            choices = data.get("choices")
            if not isinstance(choices, list) or not choices or not isinstance(choices[0], dict):
                raise RemoteFailure(502, "Invalid AI answer")
            choice = choices[0]
            message = choice.get("message")
            if (
                not isinstance(message, dict)
                or choice.get("finish_reason") != "stop"
                or message.get("tool_calls")
                or message.get("function_call")
            ):
                raise RemoteFailure(502, "Invalid AI answer")
            content = message.get("content")
            if not isinstance(content, str) or not 1 <= len(content.strip()) <= 4000:
                raise RemoteFailure(502, "Invalid AI answer")
            return content.strip()
        except httpx.TimeoutException as error:
            raise RemoteFailure(504, "AI provider timed out") from error
        except httpx.HTTPError as error:
            raise RemoteFailure(502, "AI provider unavailable") from error
        except (KeyError, IndexError, TypeError) as error:
            raise RemoteFailure(502, "Invalid AI answer") from error
