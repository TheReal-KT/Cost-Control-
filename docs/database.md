# Cost Control database and Android data contract

This contract follows the [Deliverable 4 build plan](https://app.notion.com/p/3df99988e94781e58b25e180eca000fe) and [database task](https://app.notion.com/p/3e299988e947811a86c3fec4a069ef40). The newer plan requires native Android with Java/XML. Android calls Supabase Auth and the Data API directly for CRUD; AI generation belongs in a trusted backend endpoint.

Project: **Cost Control**, ref `tulpokqxlemjlcbuwabl`. [Database dashboard](https://supabase.com/dashboard/project/tulpokqxlemjlcbuwabl/editor).

## Tables

| Table | Responsibility | Android access |
| --- | --- | --- |
| `users` | App profile, bigint `user_id`, UUID `auth_user_id` linking Supabase Auth | Read safe columns; create own profile; edit names |
| `providers` | Optional user-owned service/provider details | Own-row CRUD |
| `subscriptions` | Manual subscription capture, cost, cycle, usage, importance and renewal date | Own-row CRUD |
| `budgets` | One monthly budget per user/month/year | Own-row CRUD |
| `reminders` | User-managed reminder schedules | Own-row CRUD |
| `user_settings` | Display currency, IANA time zone and notification preferences | Own-row CRUD |
| `recommendations` | Backend-produced action, explanation, evidence, confidence and savings | Own-row read only |
| `user_decision` | One current approved/rejected/ignored response per recommendation | Own-row CRUD |
| `notifications` | Backend-produced notification records; distinct from editable reminders | Own-row read; update `read_at` only |
| `activity_logs` | Database-generated create/update/delete history for subscriptions, reminders, budgets and decisions | Own-row read only |

Existing table names and bigint IDs are preserved. `reminders`, `user_settings` and `activity_logs` complete the required MVP. Connected accounts, provider credentials, usage connectors, bank links and payments are deferred.

## Authentication and profile onboarding

1. Register or sign in through **Supabase Auth**. Account passwords belong only in Auth calls.
2. After obtaining an authenticated session, query `users` by the session UUID in `auth_user_id`.
3. Always select explicit safe columns: `user_id,auth_user_id,first_name,last_name,email,user_type,created_at,updated_at`. `select=*` is intentionally denied because the legacy `password` column is excluded from API grants.
4. If no profile exists, insert `first_name` and `last_name`. The database derives `auth_user_id` and `email` from the verified JWT. The client cannot write email, password or user type, or later change the identity link.
5. Reuse the returned bigint `user_id` for app records. Auth UUID and application bigint ID are different identifiers; do not cast one into the other.
6. Fetch `user_settings`; create the default settings row if absent. Defaults are ZAR, Africa/Johannesburg, seven days before renewal, notifications enabled.

The sole pre-existing app profile has no Supabase Auth identity. It is retained but invisible to API clients until an administrator verifies and reconciles its ownership. Email matching does **not** automatically link or claim existing records. A duplicate profile email produces a conflict for explicit reconciliation. The legacy password column is preserved for compatibility but API roles cannot read or write it; it is never used for new authentication. `user_type` is descriptive, not an authorization role.

REST base URL: `https://tulpokqxlemjlcbuwabl.supabase.co/rest/v1`. Requests include the project's publishable API key and `Authorization: Bearer <signed-in access token>`. A service-role/secret key must never be bundled in the APK. Use explicit profile columns in both GET queries and insert responses.

Example profile request after email sign-in:

```http
POST /rest/v1/users?select=user_id,auth_user_id,first_name,last_name,email
Prefer: return=representation
Content-Type: application/json

{"first_name":"Example","last_name":"User"}
```

## Shared field rules

- IDs: Postgres bigint; Java `long` for app IDs, UUID/string for Auth IDs. Never use floating-point IDs.
- Money: `numeric(12,2)`; use Java `BigDecimal` and plain decimal JSON values. Prices, budgets and savings are non-negative and finite. Do not calculate financial totals with `double`.
- Currency: uppercase three-letter code, default `ZAR` on subscriptions/settings. This format check does not verify an ISO currency registry. Budgets require an explicit currency.
- Billing cycle: `monthly` or `annual`. Price is the amount for that cycle. Monthly equivalent is monthly price, or annual price divided by 12. Sum before rounding to two decimals. Estimated annual recurring spend is monthly equivalent multiplied by 12.
- Compare a budget with active subscriptions **in the budget's currency**. Group dashboard totals by currency; no exchange-rate conversion is implemented.
- Subscription status: `active`, `paused`, `cancelled`. Usage: `unknown`, `low`, `medium`, `high`, or null for unreported usage. Importance: `low`, `medium`, `high`.
- Subscription category is captured directly on the subscription; provider is optional. `plan_name` is optional. Renewal date must be on/after start date.
- Renewal/start dates: `YYYY-MM-DD`, mapped to Java `LocalDate`. Reminder times and record timestamps: ISO 8601 with an explicit UTC offset, mapped to `Instant`/`OffsetDateTime`. Existing naive timestamps are interpreted as UTC during migration.
- Reminder status: `pending`, `completed`, `dismissed`. Rescheduling updates `remind_at` and usually resets status to `pending`. Time zone preferences must exist in PostgreSQL's time zone catalogue.
- Recommendation action: `keep`, `review`, `cancel`, `downgrade`, `pause`. `confidence_score` is between 0 and 1. `evidence` is a JSON object or null for compatibility. Savings are monthly and annual estimates in the subscription currency.
- Decision: `approved`, `rejected`, `ignored`; one row per recommendation. Recording approval never cancels a provider service or processes a payment.

Example subscription insert (replace the sample user ID with the signed-in profile ID):

```json
{
  "user_id": 123,
  "subscription_name": "Example service",
  "subscription_price": 120.00,
  "currency": "ZAR",
  "billing_cycle": "monthly",
  "category": "productivity",
  "start_date": "2026-10-02",
  "renewal_date": "2026-11-02",
  "usage_level": "low",
  "importance": "medium"
}
```

Only documented editable columns should be sent. IDs, ownership on updates, and generated timestamps are not client-editable. Filter reads by `user_id`, use stable ordering and limit/range pagination. RLS still enforces ownership independently of these query filters.

## Integrity, deletion and backend boundaries

Composite foreign keys require a subscription's provider, a reminder/notification/recommendation's subscription, and a decision's recommendation to belong to the same user. These rules apply even to trusted writers that bypass RLS.

Deleting a subscription cascades to its reminders, notifications and recommendations; deleting a recommendation cascades to its decision. Activity history retains entity IDs after deletion. Deleting a referenced provider is rejected until the subscription is reassigned or its optional provider reference is cleared. Deleting an Auth identity unlinks its profile, immediately removing client access while retaining application records for deliberate reconciliation or cleanup.

Activity records are created by database triggers. Clients cannot insert, alter or delete activity, or fabricate AI recommendations/notification deliveries. The privileged trigger writer is confined to a non-exposed `private` schema with a fixed search path and no API execution grant. Public tables all use row-level security, and anonymous access and broad privileges such as TRUNCATE are revoked.

The future AI endpoint must authenticate the caller, resolve their profile and load only their subscriptions. Validate bounded outputs before a trusted write to `recommendations`. Service-role access bypasses RLS, so ownership must be enforced in the endpoint too. The Android app owns display and user decisions. API keys, model calls and orchestration stay on the backend.

The schema stores reminder schedules and notification records. It does not run a scheduler or send Android push notifications. That remains a separate planner task.

## Error contract

Repositories should translate expected failures into domain results; screens display actionable messages and keep submitted input on failure. Preserve diagnostic Postgres/PostgREST error codes without logging tokens or passwords.

| Condition | Handling |
| --- | --- |
| Missing/expired session | Refresh the session or return to sign-in |
| Permission denied (`42501`) | Do not retry with elevated credentials; show protected-access error |
| Missing or inaccessible row / update returns zero rows | Show record unavailable; do not claim a successful update |
| Unique conflict (`23505`) | Explain duplicate budget/decision/profile; reload or reconcile |
| Invalid reference (`23503`) | Explain that the linked record was removed or is unavailable |
| Check failure (`23514`) | Show field validation feedback |
| Network timeout / transient failure | Preserve input; allow a deliberate retry; check prior outcome before repeating creates |

## Migration and verification

`supabase/migrations/` contains a snapshot of the seven pre-existing tables followed by the MVP additions/security changes. It contains no exported application records. Apply migrations once through the migration runner; do not rerun the SQL as an ad hoc idempotent script. Existing populated core tables should be checked against the new field constraints before applying this migration elsewhere.

`supabase/tests/mvp_contract.sql` is a transactional integration suite with temporary Auth identities. Run it using a privileged SQL connection/tool; all fixture data rolls back. Identity/serial sequence counters can advance despite rollback. It tests authenticated ownership, mutation permissions, integrity constraints, reminder updates and deletion/audit behavior.

Remote Supabase migration versions must match local filenames before using `db push`. This project uses the connected MCP migration tool for remote application; the saved local filename is synchronized with its recorded version. Docker/local Supabase is optional for this connected project, but a fresh local replay requires the normal Supabase Auth schemas supplied by the local stack.
