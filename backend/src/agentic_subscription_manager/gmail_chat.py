"""Validate the assistant's small set of Gmail actions against the user's request."""

import re
from dataclasses import dataclass
from typing import Literal

_MAIL_REFERENCE = re.compile(
    r"\b(?:gmail|composio|e-?mail|emails|inbox|mailbox|google\s+account|connected\s+apps?)\b",
    re.I,
)
_IMPORT_VERB = re.compile(r"\b(?:add|save|import|record|create)\b", re.I)
_SEARCH_VERB = re.compile(
    r"\b(?:find|search|look|check|scan|pull|get|retrieve|show|add|save|import)\b", re.I
)
_SUBSCRIPTION = re.compile(r"\b(?:subscriptions?|services?)\b", re.I)
_INDIRECT = re.compile(
    r"\b(?:if|imagine|suppose|pretend|hypothetically|example|whether|describe|said|says|"
    r"saying|told|wrote|quoted)\b|\b(?:ask|asked)\s+(?:it|him|her|them)\b|"
    r"\b(?:how\s+(?:do|can|would|to)|should\s+i)\b|"
    r"\b(?:from|to|subject|label|in|is):",
    re.I,
)
_DENY_READ = re.compile(
    r"\b(?:do not|don't|dont|never|stop)\s+(?:please\s+)?"
    r"(?:look|search|check|read|access|use|scan)\b",
    re.I,
)
_DENY_IMPORT = re.compile(r"\b(?:not|never|don't|dont|without|avoid|unless)\b", re.I)
_SERVICE_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9 &.+-]{0,49}")
_GENERIC_NAMES = {"gmail", "email", "mail", "composio", "subscription", "service", "my"}


@dataclass(frozen=True)
class GmailAction:
    name: Literal["gmail_connection_status", "find_gmail_subscription"]
    service: str | None = None
    save: bool = False


def may_need_gmail(prompt: str) -> bool:
    """Only offer Gmail tools for requests that could use this integration."""
    return bool(_MAIL_REFERENCE.search(prompt)) or bool(
        _SUBSCRIPTION.search(prompt) and _IMPORT_VERB.search(prompt)
    )


def valid_requested_service(prompt: str, service: object) -> bool:
    if not isinstance(service, str) or not _SERVICE_NAME.fullmatch(service):
        return False
    if service.casefold() in _GENERIC_NAMES:
        return False
    return bool(re.search(rf"(?<!\w){re.escape(service)}(?!\w)", prompt, re.I))


def allows_import(prompt: str, service: str) -> bool:
    """A model proposal is not authorization: require a direct user instruction."""
    target = rf"(?:it|that|this|{re.escape(service)}(?:\s+subscription)?)"
    instruction = re.compile(
        r"\b(?:add|save|import|record|create)\s+(?:(?:my|the|a)\s+)?"
        + target
        + r"(?:\s+from\s+(?:my\s+)?gmail)?"
        + r"(?:\s+(?:as|to|into)\s+(?:(?:my|the|a)\s+)?"
        + r"(?:services?|subscriptions?)(?:\s+list)?)?"
        + r"(?:\s+please|,\s*(?:please|thanks|thank you))*[.!?]?\s*$",
        re.I,
    )
    return (
        allows_search(prompt, service)
        and bool(instruction.search(prompt))
        and bool(_SUBSCRIPTION.search(prompt))
        and not _DENY_IMPORT.search(prompt)
    )


def denies_gmail_access(prompt: str) -> bool:
    return bool(_DENY_READ.search(prompt))


def allows_search(prompt: str, service: str) -> bool:
    return (
        valid_requested_service(prompt, service)
        and bool(_SEARCH_VERB.search(prompt))
        and not _INDIRECT.search(prompt)
        and not denies_gmail_access(prompt)
    )
