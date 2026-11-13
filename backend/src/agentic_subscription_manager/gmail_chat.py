"""Recognize explicit, single-service Gmail import requests from the user."""

import re

_SERVICE = r"(?P<service>[A-Za-z0-9][A-Za-z0-9 &.+-]{0,49}?)"
_GREETING = r"(?:(?:hi|hello|hey)[,!]?\s+)?(?:(?:can|could|would)\s+you\s+)?(?:please\s+)?"
_IMPORT = (
    r"(?:and|then)\s+(?:please\s+)?(?:add|save|import)\s+(?:it|that|this)\s+"
    r"(?:as|to)\s+(?:(?:a|my|the)\s+)?(?:service|services|subscriptions)"
)
_REQUESTS = (
    re.compile(
        _GREETING
        + r"(?:look\s+(?:in|into|through)|search|check)\s+(?:my\s+)?gmail\s+for\s+"
        + r"(?:my\s+)?"
        + _SERVICE
        + r"\s+subscription\s+"
        + _IMPORT
        + r"[.!?]?",
        re.I,
    ),
    re.compile(
        _GREETING
        + r"(?:find|pull|look for|search for)\s+(?:my\s+)?"
        + _SERVICE
        + r"\s+subscription\s+(?:in|from)\s+(?:my\s+)?gmail\s+"
        + _IMPORT
        + r"[.!?]?",
        re.I,
    ),
)


def requested_service(prompt: str) -> str | None:
    """Only a direct instruction naming one service can authorize an import."""
    for pattern in _REQUESTS:
        match = pattern.fullmatch(prompt.strip())
        if match:
            return " ".join(match.group("service").split())
    return None
