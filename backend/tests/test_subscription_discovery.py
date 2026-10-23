"""Conservative parsing rules for email-based subscription receipts."""

import unittest
from datetime import UTC, datetime, timedelta

from agentic_subscription_manager.subscription_discovery import discover_subscriptions

NOW = datetime(2026, 10, 22, 10, 0, tzinfo=UTC)


def message(message_id="fresh-receipt", *, received_at=NOW, text=None, subject=None):
    renewal = (NOW.date() + timedelta(days=20)).isoformat()
    return {
        "id": message_id,
        "from": "Netflix <billing@mailer.netflix.com>",
        "subject": subject or f"Your Netflix subscription renews on {renewal}",
        "internalDate": str(int(received_at.timestamp() * 1000)),
        "textBody": text
        or f"We charged ZAR 99.00 for your monthly subscription, billed every month. "
        f"Your next billing date is {renewal}.",
    }


class SubscriptionDiscoveryContracts(unittest.TestCase):
    def test_accepts_current_composio_fetch_emails_schema(self):
        renewal = (NOW.date() + timedelta(days=20)).isoformat()
        live_schema_message = {
            "messageId": "schema-message-1",
            "sender": "Acme Billing <billing@example.invalid>",
            "subject": f"Your Acme monthly subscription renews on {renewal}",
            "messageTimestamp": NOW.isoformat().replace("+00:00", "Z"),
            "messageText": (
                "We charged ZAR 99.00 for your monthly subscription, billed every month. "
                f"Your next billing date is {renewal}."
            ),
        }

        receipts = discover_subscriptions([live_schema_message], NOW)

        self.assertEqual(1, len(receipts))
        self.assertEqual(
            {
                "source_id": "schema-message-1",
                "service_name": "Acme",
                "price": "99.00",
                "currency": "ZAR",
                "billing_cycle": "MONTHLY",
                "renewal_date": renewal,
                "source_url": "https://mail.google.com/mail/u/0/#all/schema-message-1",
            },
            receipts[0],
        )

    def test_extracts_only_explicit_current_recurring_charge_with_future_renewal(self):
        receipts = discover_subscriptions([message()], NOW)
        self.assertEqual(
            [
                {
                    "source_id": "fresh-receipt",
                    "service_name": "Netflix",
                    "price": "99.00",
                    "currency": "ZAR",
                    "billing_cycle": "MONTHLY",
                    "renewal_date": "2026-11-11",
                    "source_url": "https://mail.google.com/mail/u/0/#all/fresh-receipt",
                }
            ],
            receipts,
        )

    def test_skips_one_off_purchase_without_explicit_next_renewal(self):
        one_off = message(
            text=(
                "Our one-time offer is ZAR 99.00. A monthly plan is available, "
                "and it renews on 2026-11-11."
            )
        )
        self.assertEqual([], discover_subscriptions([one_off], NOW))

    def test_skips_unclear_currency_cycle_or_renewal_evidence(self):
        ambiguous_currency = message(text=message()["textBody"].replace("ZAR 99.00", "$99.00"))
        ambiguous_cycle = message(
            text=message()["textBody"] + " The annual plan is also available."
        )
        missing_renewal = message(
            subject="Your Netflix subscription charge",
            text="We charged ZAR 99.00 for your monthly subscription, billed every month.",
        )
        past_renewal = message(
            text="We charged ZAR 99.00 for your monthly subscription, billed every month. "
            "Your next billing date is 2026-10-21."
        )
        self.assertEqual(
            [],
            discover_subscriptions(
                [ambiguous_currency, ambiguous_cycle, missing_renewal, past_renewal], NOW
            ),
        )

    def test_uses_the_freshest_valid_receipt_per_service(self):
        older = message("older", received_at=NOW - timedelta(days=10))
        newer_renewal = (NOW.date() + timedelta(days=25)).isoformat()
        newer = message(
            "newer",
            subject="Netflix subscription receipt",
            text=(
                "We charged ZAR 109.00 for your monthly subscription, billed every month. "
                f"Your subscription renews on {newer_renewal}."
            ),
        )
        receipts = discover_subscriptions([older, newer], NOW)
        self.assertEqual(1, len(receipts))
        self.assertEqual("newer", receipts[0]["source_id"])
        self.assertEqual("109.00", receipts[0]["price"])

    def test_keeps_distinct_currency_and_billing_cycles_for_the_same_service(self):
        monthly = message("monthly")
        yearly_date = (NOW.date() + timedelta(days=300)).isoformat()
        yearly = message(
            "yearly",
            received_at=NOW - timedelta(days=1),
            subject="Netflix annual subscription receipt",
            text=f"We charged USD 120.00 for your annual subscription, billed every year. "
            f"Your next renewal date is {yearly_date}.",
        )
        receipts = discover_subscriptions([monthly, yearly], NOW)
        self.assertEqual(
            {("ZAR", "MONTHLY"), ("USD", "YEARLY")},
            {(receipt["currency"], receipt["billing_cycle"]) for receipt in receipts},
        )

    def test_skips_truncated_amounts_and_normalizes_rand_symbol_case(self):
        malformed = message(text=message()["textBody"].replace("ZAR 99.00", "USD 99.999"))
        rand_symbol = message(text=message()["textBody"].replace("ZAR 99.00", "r99.00"))
        self.assertEqual([], discover_subscriptions([malformed], NOW))
        self.assertEqual("ZAR", discover_subscriptions([rand_symbol], NOW)[0]["currency"])

    def test_skips_marketing_price_without_completed_charge_or_with_offer_language(self):
        marketing = message(
            subject="Monthly plans renew automatically",
            text="Our monthly plan costs USD 19.99 per month and renews on 2026-11-11.",
        )
        promotional_receipt = message(
            text="We charged ZAR 99.00 for your monthly subscription, billed every month. "
            "Your next billing date is 2026-11-11. This is a special offer.",
        )
        self.assertEqual([], discover_subscriptions([marketing, promotional_receipt], NOW))

    def test_skips_processor_as_service_but_keeps_explicit_merchant_name(self):
        processor_only = message()
        processor_only["from"] = "PayPal Billing <noreply@paypal.com>"
        explicit_merchant = message()
        explicit_merchant["from"] = "Netflix via PayPal <noreply@paypal.com>"
        receipts = discover_subscriptions([processor_only, explicit_merchant], NOW)
        self.assertEqual(1, len(receipts))
        self.assertEqual("Netflix", receipts[0]["service_name"])

    def test_skips_missing_country_domain_service_and_non_active_receipts(self):
        short_domain = message()
        short_domain["from"] = "noreply@co.za"
        trial = message(text=message()["textBody"] + " This free trial ends soon.")
        failed = message(text=message()["textBody"] + " Your payment was declined.")
        cancelled = message(
            text="Your subscription was cancelled. Monthly ZAR 99.00 per month. "
            "Your next renewal date is 2026-11-11."
        )
        self.assertEqual([], discover_subscriptions([short_domain, trial, failed, cancelled], NOW))

    def test_newer_cancellation_suppresses_older_active_receipt(self):
        active = message("active", received_at=NOW - timedelta(days=1))
        cancellation = message(
            "cancelled",
            text="Your Netflix subscription was cancelled and will not renew.",
        )
        self.assertEqual([], discover_subscriptions([active, cancellation], NOW))

    def test_accepts_explicit_iso_currency_code_and_yearly_cycle(self):
        renewal = (NOW.date() + timedelta(days=300)).isoformat()
        yearly = message(
            subject="Netflix yearly membership receipt",
            text=f"We charged USD 120.00 for your yearly membership, billed every year. "
            f"Your next renewal date is {renewal}.",
        )
        receipt = discover_subscriptions([yearly], NOW)[0]
        self.assertEqual("YEARLY", receipt["billing_cycle"])
        self.assertEqual("USD", receipt["currency"])
        self.assertEqual(renewal, receipt["renewal_date"])

    def test_rejects_stale_malformed_and_overlong_message_pages(self):
        stale = message("stale", received_at=NOW - timedelta(days=91))
        malformed = {"id": "valid-but-no-date", "textBody": "not enough evidence"}
        self.assertEqual([], discover_subscriptions([stale, malformed], NOW))
        with self.assertRaises(ValueError):
            discover_subscriptions([{}] * 101, NOW)


if __name__ == "__main__":
    unittest.main()
