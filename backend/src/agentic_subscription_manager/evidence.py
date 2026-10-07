"""Pure decimal calculations over server-owned fictional records only."""

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
    # Raw text never crosses the provider boundary, including text around matched keywords.
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


def synthetic_records(today: date) -> tuple[SubscriptionEvidence, ...]:
    return (
        SubscriptionEvidence(
            "Example streaming",
            Decimal("100.00"),
            "ZAR",
            False,
            today + timedelta(days=3),
            low_usage_reported=True,
        ),
        SubscriptionEvidence(
            "Example cloud", Decimal("1200.00"), "ZAR", True, today + timedelta(days=10)
        ),
        SubscriptionEvidence(
            "Example editor", Decimal("12.00"), "USD", False, today + timedelta(days=20)
        ),
        SubscriptionEvidence(
            "Example paused",
            Decimal("900.00"),
            "ZAR",
            False,
            today + timedelta(days=2),
            active=False,
        ),
    )


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


def calculate_evidence(records: tuple[SubscriptionEvidence, ...], today: date) -> dict:
    totals = monthly_totals(records)
    return {
        "data_mode": "fictional example",
        "as_of": today.isoformat(),
        "period": today.strftime("%Y-%m"),
        "monthly_equivalent": totals,
        "active_count": sum(record.active for record in records),
        "budget": {
            "currency": "ZAR",
            "limit": "180.00",
            "period": today.strftime("%Y-%m"),
            "over_by": str(max(Decimal(0), Decimal(totals.get("ZAR", "0")) - Decimal(180))),
        },
        "renewals_within_7_days": [
            {"name": record.name, "date": record.renewal.isoformat()}
            for record in records
            if record.active and today <= record.renewal <= today + timedelta(7)
        ],
        "reported_low_usage": [
            record.name for record in records if record.active and record.low_usage_reported
        ],
    }


def render_facts(task: AnalysisTask, evidence: dict) -> str:
    if task == AnalysisTask.SPEND:
        totals = "; ".join(
            f"{currency} {value}" for currency, value in evidence["monthly_equivalent"].items()
        )
        return f"Monthly equivalent: {totals}. Currencies are separate."
    if task == AnalysisTask.BUDGET:
        budget = evidence["budget"]
        return (
            f"{budget['period']} budget: {budget['currency']} {budget['limit']}; "
            f"over by {budget['currency']} {budget['over_by']}."
        )
    if task == AnalysisTask.RENEWALS:
        renewals = "; ".join(
            f"{item['name']} on {item['date']}" for item in evidence["renewals_within_7_days"]
        )
        return f"Next 7 days: {renewals or 'no renewals'}."
    return "Reported low usage: " + "; ".join(evidence["reported_low_usage"]) + "."
