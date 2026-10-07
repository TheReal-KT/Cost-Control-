"""Configuration and owner-bound correlation IDs; no password or profile storage."""

import base64
import hashlib
import hmac
import os
import re
import secrets
import time
from dataclasses import dataclass, field
from urllib.parse import urlsplit
from uuid import UUID


@dataclass(frozen=True)
class Settings:
    supabase_url: str = ""
    publishable_key: str = field(default="", repr=False)
    provider_key: str = field(default="", repr=False)
    conversation_secret: str = field(default="", repr=False)

    def __post_init__(self) -> None:
        if self.supabase_url:
            url = urlsplit(self.supabase_url)
            if (
                url.scheme != "https"
                or not url.hostname
                or url.username
                or url.password
                or url.query
                or url.fragment
                or url.path not in ("", "/")
            ):
                raise ValueError("SUPABASE_URL must be an HTTPS project origin")
        if self.publishable_key and not self.publishable_key.startswith("sb_publishable_"):
            raise ValueError("Use a Supabase publishable key, never a privileged key")
        if self.conversation_secret and len(self.conversation_secret) < 32:
            raise ValueError("AI_CONVERSATION_SECRET must contain at least 32 characters")

    @classmethod
    def from_env(cls) -> "Settings":
        return cls(
            os.getenv("SUPABASE_URL", "").rstrip("/"),
            os.getenv("SUPABASE_PUBLISHABLE_KEY", ""),
            os.getenv("OPENROUTER_API_KEY", ""),
            os.getenv("AI_CONVERSATION_SECRET", ""),
        )

    @property
    def configured(self) -> bool:
        return bool(
            self.supabase_url
            and self.publishable_key
            and self.provider_key
            and self.conversation_secret
        )


class ConversationIds:
    """Stateless, expiring correlation only: IDs do not select stored history."""

    def __init__(self, secret: str) -> None:
        self._secret = secret.encode()

    def _signature(self, owner: UUID, prefix: str) -> str:
        digest = hmac.new(self._secret, f"{owner}:{prefix}".encode(), hashlib.sha256).digest()
        return base64.urlsafe_b64encode(digest).decode().rstrip("=")

    def issue(self, owner: UUID, now: int | None = None) -> str:
        prefix = f"{int(time.time()) if now is None else now}_{secrets.token_hex(16)}"
        return f"{prefix}_{self._signature(owner, prefix)}"

    def verify(self, value: str, owner: UUID, now: int | None = None) -> bool:
        match = re.fullmatch(r"([0-9]{10})_([a-f0-9]{32})_([A-Za-z0-9_-]{43})", value)
        if match is None:
            return False
        issued, nonce, signature = match.groups()
        age = (int(time.time()) if now is None else now) - int(issued)
        return 0 <= age < 3600 and hmac.compare_digest(
            signature, self._signature(owner, f"{issued}_{nonce}")
        )
