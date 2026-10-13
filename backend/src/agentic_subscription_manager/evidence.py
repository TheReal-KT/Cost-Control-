"""Pure decimal calculations over validated, owner-scoped account records."""

from dataclasses import dataclass
from datetime import date, timedelta
from decimal import ROUND_HALF_UP, Decimal, localcontext
from enum import StrEnum


class AnalysisTask(StrEnum):
    SPEND = "explain monthly equivalent spending"
    RENEWALS = "explain upcoming renewals"
    BUDGET = "explain the monthly budget comparison"
    REVIEW = "explain reported low-usage review candidates"


def select_task(question: str) -> AnalysisTask | None:
    # This selects a deterministic facts header; the model still receives the actual question.
    normalized = question.casefold()
    if "renew" in normalized or "reminder" in normalized:
        return AnalysisTask.RENEWALS
    if "budget" in normalized:
        return AnalysisTask.BUDGET
    if "review" in normalized or "usage" in normalized:
        return AnalysisTask.REVIEW
    if "spend" in normalized or "cost" in normalized:
        return AnalysisTask.SPEND
    return None


@dataclass(frozen=True)
class SubscriptionEvidence:
    name: str
    price: Decimal
    currency: str
    annual: bool
    renewal: date
    active: bool = True
    low_usage_reported: bool = False
    status: str | None = None
    usage_level: str | None = None


@dataclass(frozen=True)
class BudgetEvidence:
    limit: Decimal
    currency: str


@dataclass(frozen=True)
class AccountEvidence:
    records: tuple[SubscriptionEvidence, ...]
    budget: BudgetEvidence | None
    today: date
    time_zone: str


def monthly_totals(records: tuple[SubscriptionEvidence, ...]) -> dict[str, str]:
    monthly: dict[str, Decimal] = {}
    annual: dict[str, Decimal] = {}
    for record in records:
        if not record.active:
            continue
        target = annual if record.annual else monthly
        target[record.currency] = target.get(record.currency, Decimal(0)) + record.price
    with localcontext() as context:
        context.prec = 34  # Matches Java MathContext.DECIMAL128 before final HALF_UP rounding.
        return {
            currency: str(
                (
                    monthly.get(currency, Decimal(0)) + annual.get(currency, Decimal(0)) / 12
                ).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
            )
            for currency in sorted(monthly.keys() | annual.keys())
        }


def calculate_evidence(account: AccountEvidence) -> dict:
    records, today = account.records, account.today
    totals = monthly_totals(records)
    budget = account.budget
    renewals = [
        {"name": record.name, "date": record.renewal.isoformat()}
        for record in records
        if record.active and today <= record.renewal <= today + timedelta(7)
    ]
    low_usage = [record.name for record in records if record.active and record.low_usage_reported]
    return {
        "data_mode": "account",
        "as_of": today.isoformat(),
        "time_zone": account.time_zone,
        "period": today.strftime("%Y-%m"),
        "monthly_equivalent": totals,
        "active_count": sum(record.active for record in records),
        "budget": {
            "currency": budget.currency,
            "limit": str(budget.limit),
            "period": today.strftime("%Y-%m"),
            "over_by": str(
                max(Decimal(0), Decimal(totals.get(budget.currency, "0")) - budget.limit)
            ),
        }
        if budget
        else None,
        "subscription_count": len(records),
        "services": [
            {
                "name": record.name,
                "price": str(record.price),
                "currency": record.currency,
                "billing_cycle": "annual" if record.annual else "monthly",
                "active": record.active,
                "status": record.status or ("active" if record.active else "inactive"),
                "renewal_date": record.renewal.isoformat(),
                "reported_low_usage": record.low_usage_reported,
                "reported_usage_level": record.usage_level,
            }
            for record in records[:50]
        ],
        "service_list_truncated": len(records) > 50,
        "renewals_within_7_days": renewals[:50],
        "renewal_count": len(renewals),
        "renewal_list_truncated": len(renewals) > 50,
        "reported_low_usage": low_usage[:50],
        "reported_low_usage_count": len(low_usage),
        "reported_low_usage_list_truncated": len(low_usage) > 50,
    }


def render_facts(task: AnalysisTask, evidence: dict) -> str:
    if task == AnalysisTask.SPEND:
        currencies = list(evidence["monthly_equivalent"].items())
        totals = "; ".join(f"{currency} {value}" for currency, value in currencies[:20])
        amount = totals or "0.00 (no active subscriptions)"
        suffix = f" Showing 20 of {len(currencies)} currencies." if len(currencies) > 20 else ""
        return f"Monthly equivalent: {amount}. Currencies are separate.{suffix}"
    if task == AnalysisTask.BUDGET:
        budget = evidence["budget"]
        if budget is None:
            return f"No budget saved for {evidence['period']}."
        return (
            f"{budget['period']} budget: {budget['currency']} {budget['limit']}; "
            f"over by {budget['currency']} {budget['over_by']}."
        )
    if task == AnalysisTask.RENEWALS:
        renewals = "; ".join(
            f"{item['name']} on {item['date']}" for item in evidence["renewals_within_7_days"][:20]
        )
        suffix = ""
        if evidence["renewal_count"] > 20:
            suffix = f" Showing 20 of {evidence['renewal_count']} renewals."
        return f"Next 7 days: {renewals or 'no renewals'}.{suffix}"
    return (
        "Reported low usage: "
        + ("; ".join(evidence["reported_low_usage"][:20]) or "none recorded")
        + "."
        + (
            f" Showing 20 of {evidence['reported_low_usage_count']} services."
            if evidence["reported_low_usage_count"] > 20
            else ""
        )
    )
