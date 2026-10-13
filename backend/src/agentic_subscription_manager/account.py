"""Bounded, read-only Supabase account access using the caller's verified session."""

import json
import re
from datetime import UTC, date, datetime
from decimal import Decimal, InvalidOperation
from uuid import UUID
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

import httpx

from .adapters import RemoteFailure
from .evidence import AccountEvidence, BudgetEvidence, SubscriptionEvidence

PAGE_SIZE = 100
MAX_SUBSCRIPTIONS = 1000


def money(value: object) -> Decimal:
    if not isinstance(value, str) or not re.fullmatch(r"[0-9]{1,10}(\.[0-9]{1,2})?", value):
        raise ValueError("Invalid account amount")
    amount = Decimal(value)
    if amount > Decimal("9999999999.99"):
        raise ValueError("Invalid account amount")
    return amount


def identifier(value: object) -> int:
    if not isinstance(value, str) or not re.fullmatch(r"[1-9][0-9]{0,18}", value):
        raise ValueError("Invalid account identifier")
    result = int(value)
    if result > 9223372036854775807:
        raise ValueError("Invalid account identifier")
    return result


def currency(value: object) -> str:
    if not isinstance(value, str) or not re.fullmatch(r"[A-Z]{3}", value):
        raise ValueError("Invalid account currency")
    return value


class SupabaseAccountReads:
    def __init__(self, client: httpx.AsyncClient, origin: str, key: str) -> None:
        self._client, self._origin, self._key = client, origin, key

    async def _rows(self, table: str, params: dict, token: str, limit: int) -> list[dict]:
        try:
            async with self._client.stream(
                "GET",
                f"{self._origin}/rest/v1/{table}",
                params={**params, "limit": limit},
                headers={"apikey": self._key, "Authorization": f"Bearer {token}"},
                timeout=8,
            ) as response:
                if response.status_code == 401:
                    raise RemoteFailure(401, "Sign in again")
                if response.status_code != 200:
                    raise RemoteFailure(503, "Account data unavailable")
                body = bytearray()
                async for chunk in response.aiter_bytes():
                    if len(body) + len(chunk) > 262144:
                        raise RemoteFailure(503, "Account data response too large")
                    body.extend(chunk)
            rows = json.loads(body)
            if (
                not isinstance(rows, list)
                or len(rows) > limit
                or any(not isinstance(row, dict) for row in rows)
            ):
                raise ValueError("Invalid account rows")
            return rows
        except httpx.HTTPError as error:
            raise RemoteFailure(503, "Account data unavailable") from error
        except (ValueError, UnicodeError) as error:
            raise RemoteFailure(503, "Invalid account data") from error

    async def load(self, owner: UUID, token: str) -> AccountEvidence:
        try:
            profiles = await self._rows(
                "users",
                {"select": "user_id::text,auth_user_id", "auth_user_id": f"eq.{owner}"},
                token,
                2,
            )
            if not profiles:
                raise RemoteFailure(403, "Complete your account profile before using Chat")
            if len(profiles) != 1 or profiles[0]["auth_user_id"] != str(owner):
                raise ValueError("Invalid account profile")
            user_id = identifier(profiles[0]["user_id"])
            owned = {"user_id": f"eq.{user_id}"}
            settings = await self._rows(
                "user_settings",
                {**owned, "select": "user_id::text,time_zone"},
                token,
                2,
            )
            if len(settings) > 1:
                raise ValueError("Invalid account settings")
            time_zone = "Africa/Johannesburg"
            if settings:
                self._check_owner(settings[0], user_id)
                time_zone = settings[0]["time_zone"]
                if not isinstance(time_zone, str) or len(time_zone) > 100:
                    raise ValueError("Invalid account time zone")
            today = datetime.now(UTC).astimezone(ZoneInfo(time_zone)).date()

            records: list[SubscriptionEvidence] = []
            for offset in range(0, MAX_SUBSCRIPTIONS + 1, PAGE_SIZE):
                rows = await self._rows(
                    "subscriptions",
                    {
                        **owned,
                        "select": "user_id::text,subscription_name,subscription_price::text,"
                        "currency,billing_cycle,status,usage_level,renewal_date",
                        "order": "subscription_id.asc",
                        "offset": offset,
                    },
                    token,
                    PAGE_SIZE if offset < MAX_SUBSCRIPTIONS else 1,
                )
                if offset == MAX_SUBSCRIPTIONS and rows:
                    raise RemoteFailure(503, "Too many subscriptions for this chat analysis")
                for row in rows:
                    self._check_owner(row, user_id)
                    name = row["subscription_name"]
                    if not isinstance(name, str) or not name.strip() or len(name) > 150:
                        raise ValueError("Invalid subscription name")
                    if row["billing_cycle"] not in ("monthly", "annual"):
                        raise ValueError("Invalid billing cycle")
                    if row["status"] not in ("active", "paused", "cancelled"):
                        raise ValueError("Invalid subscription status")
                    if row["usage_level"] not in (None, "unknown", "low", "medium", "high"):
                        raise ValueError("Invalid subscription usage")
                    records.append(
                        SubscriptionEvidence(
                            name,
                            money(row["subscription_price"]),
                            currency(row["currency"]),
                            row["billing_cycle"] == "annual",
                            date.fromisoformat(row["renewal_date"]),
                            row["status"] == "active",
                            row["usage_level"] == "low",
                            status=row["status"],
                            usage_level=row["usage_level"],
                        )
                    )
                if len(rows) < PAGE_SIZE:
                    break

            budgets = await self._rows(
                "budgets",
                {
                    **owned,
                    "select": "user_id::text,budget_limit::text,currency",
                    "month": f"eq.{today.month}",
                    "year": f"eq.{today.year}",
                },
                token,
                2,
            )
            if len(budgets) > 1:
                raise ValueError("Invalid monthly budget")
            budget = None
            if budgets:
                self._check_owner(budgets[0], user_id)
                budget = BudgetEvidence(
                    money(budgets[0]["budget_limit"]), currency(budgets[0]["currency"])
                )
            return AccountEvidence(tuple(records), budget, today, time_zone)
        except (KeyError, TypeError, ValueError, InvalidOperation, ZoneInfoNotFoundError) as error:
            raise RemoteFailure(503, "Invalid account data") from error

    @staticmethod
    def _check_owner(row: dict, user_id: int) -> None:
        if identifier(row["user_id"]) != user_id:
            raise ValueError("Unexpected account owner")
