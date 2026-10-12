"""Bounded SSE framing; provider events never pass through to the application client."""

from collections.abc import AsyncIterator

import httpx


async def event_data(response: httpx.Response) -> AsyncIterator[str]:
    # Bound bytes before decoding or splitting, including comment-only/unterminated streams.
    pending = bytearray()
    total = 0
    data: list[str] = []
    async for chunk in response.aiter_bytes():
        total += len(chunk)
        if total > 65536:
            raise ValueError("Stream too large")
        pending.extend(chunk)
        while b"\n" in pending:
            raw, _, rest = pending.partition(b"\n")
            pending = bytearray(rest)
            line = raw.rstrip(b"\r").decode("utf-8")
            if not line:
                if data:
                    yield "\n".join(data)
                    data.clear()
            elif line.startswith("data:"):
                value = line[5:]
                data.append(value[1:] if value.startswith(" ") else value)
    if pending or data:
        raise ValueError("Unterminated stream event")
