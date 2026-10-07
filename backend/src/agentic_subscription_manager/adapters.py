"""Only this boundary communicates with Supabase Auth and the model provider."""

import json
import re
from datetime import UTC, datetime
from email.utils import parsedate_to_datetime
from uuid import UUID

import httpx

from .contracts import MODEL_ID
from .evidence import AnalysisTask


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

    async def explain(self, task: AnalysisTask, evidence: dict) -> str:
        request = {
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
            "provider": {"allow_fallbacks": False},
        }
        try:
            async with self._client.stream(
                "POST",
                "https://openrouter.ai/api/v1/chat/completions",
                headers={"Authorization": f"Bearer {self._key}"},
                json=request,
                timeout=25,
            ) as response:
                if response.status_code == 429:
                    seconds = retry_delay(response.headers.get("retry-after", "60"))
                    raise RemoteFailure(429, "AI request limit reached", seconds)
                if response.status_code != 200:
                    raise RemoteFailure(502, "AI provider unavailable")
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
