# 0006: User-scoped Gmail subscription discovery

Date: 22 October 2026

## Context

Users can already manage subscriptions in the native Android app. Home calculates monthly equivalents from those owned Supabase rows, grouping totals by currency. The user requested Composio connections and discovery of subscription spending from their accounts. Gmail is the selected first source.

Email is incomplete and untrusted evidence. A receipt can describe a one-off purchase, a cancelled plan, a past price or another person's service. Connecting Gmail cannot establish that every subscription has been found.

## Decision

Keep Android rendering and account data in their existing layers. Add explicit Gmail connect and sync controls. Send the current Supabase session token to the Python backend. The backend verifies identity with Supabase and uses that Auth UUID for the Composio session. Composio credentials stay on the server; Composio manages the browser authorization link.

Use a bounded, read-only Gmail scan and conservative extraction of explicit recurring billing evidence. Sync is a deliberate user action. The chat model does not receive a general Gmail tool executor or permission to change provider accounts. Discovered records enter the existing subscription table, so the current Home and Services calculations remain authoritative.

Persist through the security-invoker `import_subscription_receipts(receipts jsonb)` database function. Security invoker means it runs with the caller's permissions, so normal row ownership checks still apply. The function derives the profile from `auth.uid()`; callers cannot supply another owner's ID. Validate at most 100 receipts and save the batch in one transaction. A per-owner transaction lock serializes concurrent imports.

Retain an owner-scoped receipt ID ledger without email bodies or credentials. Repeating a receipt skips it even if the service was deleted. Match existing services by trimmed, case-insensitive name, currency and billing cycle, and skip them without overwriting prices, cancellation or manual edits. The database accepts current renewal dates within 400 days; extraction applies additional evidence checks. Imported annual amounts use the existing annual-to-monthly calculation, without currency conversion.

## Alternatives considered

- Let the chat model execute arbitrary tools and write subscriptions: unnecessary permissions and unpredictable financial records for this workflow.
- Import every receipt mentioning a price: overcounts purchases and stale plans.
- Replace all saved subscriptions on each scan: risks losing user edits and records absent from the current email page.
- Build Gmail OAuth inside the app: duplicates Composio's connection management and increases credential handling.

## Consequences / trade-offs

The first implementation prioritizes avoiding false financial records. Supported receipt formats are deliberately narrow; users can add or correct services manually. The scan is bounded and does not guarantee mailbox completeness. Existing service price changes are not applied automatically. Service names that differ materially can still require manual reconciliation.

The connection and scan require configured server credentials and individual Gmail consent. Mocked API tests establish application behaviour but cannot establish live Composio/provider access. A live read-only Gmail result and Composio log ID are required before claiming that integration verified.

Receipt IDs remain after individual service deletion to prevent reimport of the same evidence. This ledger is ordinary account data under RLS, not a trusted billing audit log: authenticated users have insert access to their own IDs, just as they can create their own subscriptions. No billing actions, cancellations, scheduled scans or notification delivery are introduced.
