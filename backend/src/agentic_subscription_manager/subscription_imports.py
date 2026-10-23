"""Caller-scoped persistence of validated subscription receipts through Supabase RPC."""

import json
import re
from collections.abc import Mapping
from dataclasses import dataclass
from datetime import date
from decimal import Decimal, InvalidOperation
from urllib.parse import urlsplit

import httpx

from .adapters import RemoteFailure

MAX_RECEIPTS = 100
MAX_RESPONSE_BYTES = 16384
_PRICE = re.compile(r"^[0-9]{1,10}(?:\.[0-9]{1,2})?$")
_CURRENCY = re.compile(r"^[A-Z]{3}$")
_SOURCE_ID = re.compile(r"^[A-Za-z0-9_-]{1,256}$")
_SERVICE_NAME = re.compile(r"^[^\x00-\x1f\x7f]{1,150}$")


@dataclass(frozen=True)
class ImportCounts:
    imported: int
    skipped: int


def _validate_receipts(receipts: list[dict]) -> list[dict]:
    if not isinstance(receipts, list) or not 1 <= len(receipts) <= MAX_RECEIPTS:
        raise ValueError("Invalid subscription receipt batch")
    validated = []
    required = {
        "source_id",
        "service_name",
        "price",
        "currency",
        "billing_cycle",
        "renewal_date",
    }
    allowed = required | {"source_url"}
    for receipt in receipts:
        if not isinstance(receipt, Mapping) or not required.issubset(receipt):
            raise ValueError("Invalid subscription receipt")
        if set(receipt) - allowed:
            raise ValueError("Invalid subscription receipt")
        source_id = receipt["source_id"]
        service_name = receipt["service_name"]
        price = receipt["price"]
        currency = receipt["currency"]
        cycle = receipt["billing_cycle"]
        renewal = receipt["renewal_date"]
        if not isinstance(source_id, str) or not _SOURCE_ID.fullmatch(source_id):
            raise ValueError("Invalid subscription receipt")
        if (
            not isinstance(service_name, str)
            or not service_name.strip()
            or not _SERVICE_NAME.fullmatch(service_name)
        ):
            raise ValueError("Invalid subscription receipt")
        if not isinstance(price, str) or not _PRICE.fullmatch(price):
            raise ValueError("Invalid subscription receipt")
        try:
            amount = Decimal(price)
        except InvalidOperation as error:
            raise ValueError("Invalid subscription receipt") from error
        if amount <= 0 or amount > Decimal("9999999999.99"):
            raise ValueError("Invalid subscription receipt")
        if not isinstance(currency, str) or not _CURRENCY.fullmatch(currency):
            raise ValueError("Invalid subscription receipt")
        if cycle not in ("MONTHLY", "YEARLY"):
            raise ValueError("Invalid subscription receipt")
        if not isinstance(renewal, str):
            raise ValueError("Invalid subscription receipt")
        try:
            parsed_renewal = date.fromisoformat(renewal)
        except ValueError as error:
            raise ValueError("Invalid subscription receipt") from error
        if parsed_renewal.isoformat() != renewal:
            raise ValueError("Invalid subscription receipt")

        item = {
            "source_id": source_id,
            "service_name": service_name.strip(),
            "price": price,
            "currency": currency,
            "billing_cycle": cycle,
            "renewal_date": renewal,
        }
        source_url = receipt.get("source_url")
        if source_url is not None:
            if not isinstance(source_url, str) or len(source_url) > 2048:
                raise ValueError("Invalid subscription receipt")
            parsed_url = urlsplit(source_url)
            if (
                parsed_url.scheme != "https"
                or parsed_url.hostname != "mail.google.com"
                or parsed_url.username
                or parsed_url.password
                or not parsed_url.path.startswith("/mail/")
            ):
                raise ValueError("Invalid subscription receipt")
            item["source_url"] = source_url
        validated.append(item)
    return validated


class SupabaseReceiptImports:
    def __init__(self, client: httpx.AsyncClient, origin: str, key: str) -> None:
        self._client, self._origin, self._key = client, origin, key

    async def import_receipts(self, token: str, receipts: list[dict]) -> ImportCounts:
        payload = {"receipts": _validate_receipts(receipts)}
        try:
            async with self._client.stream(
                "POST",
                f"{self._origin}/rest/v1/rpc/import_subscription_receipts",
                headers={
                    "apikey": self._key,
                    "Authorization": f"Bearer {token}",
                    "Content-Type": "application/json",
                },
                json=payload,
                timeout=8,
            ) as response:
                if response.status_code == 401:
                    raise RemoteFailure(401, "Sign in again")
                if response.status_code == 403:
                    raise RemoteFailure(403, "Cannot save Gmail discoveries for this account")
                if response.status_code != 200:
                    raise RemoteFailure(503, "Subscription import unavailable")
                body = bytearray()
                async for chunk in response.aiter_bytes():
                    if len(body) + len(chunk) > MAX_RESPONSE_BYTES:
                        raise RemoteFailure(503, "Invalid subscription import response")
                    body.extend(chunk)
            data = json.loads(body)
            if not isinstance(data, dict):
                raise ValueError("Invalid subscription import response")
            imported, skipped = data.get("imported"), data.get("skipped")
            if (
                type(imported) is not int
                or type(skipped) is not int
                or imported < 0
                or skipped < 0
                or imported + skipped != len(payload["receipts"])
            ):
                raise ValueError("Invalid subscription import counts")
            return ImportCounts(imported, skipped)
        except RemoteFailure:
            raise
        except httpx.HTTPError as error:
            raise RemoteFailure(503, "Subscription import unavailable") from error
        except (ValueError, UnicodeError, TypeError) as error:
            raise RemoteFailure(503, "Invalid subscription import response") from error
