# Database verification — 2 October 2026

Target: connected Supabase project **Cost Control** (`tulpokqxlemjlcbuwabl`).

## Applied change

- Migration `20261002162433_cost_control_schema_baseline` was successfully applied through the connected Supabase migration tool.
- The local migration filename matches the remote history version.
- Seven existing tables were retained; `reminders`, `user_settings` and `activity_logs` were added.
- All ten public tables have RLS enabled, with 31 operation-specific policies.
- All foreign keys are validated.
- API roles cannot read/write the legacy password column, truncate app tables or execute the private audit function.
- Profile identity and email defaults resolve from the authenticated JWT.

## Checks executed

Live transactional smoke tests passed for authenticated profile onboarding, subscription creation, reminder creation/rescheduling, subscription deletion, dependent reminder deletion and retained activity history. Test Auth identities and application rows rolled back.

Supabase security advisors returned **zero findings**. Performance advisors returned only [unused-index informational notices](https://supabase.com/docs/guides/database/database-linter?lint=0005_unused_index), expected for this database before application traffic; no index removal was performed on that basis.

Post-test counts remained one retained, unlinked application profile and zero Supabase Auth identities. No existing credentials or profile details were exported into repository files.

The saved `supabase/tests/mvp_contract.sql` suite passed against the connected database. It verifies two-user read isolation across every user-owned table, blocked cross-user mutations and spoofed ownership, composite reference integrity even for privileged writes, protected credentials/identity/backend output, domain checks and duplicate budgets, reminder rescheduling, decisions, notification read state, cascading deletion with retained activity, and anonymous access denial.

## Limits

These checks exercise PostgreSQL privileges, RLS, constraints and triggers directly. Android sign-in, REST integration, scheduling, AI endpoint behavior and device notifications still require implementation and end-to-end tests. No Android build was run for this database/documentation change. A fresh Docker-based local migration replay was not run because the local Supabase/Docker stack is not available.

The existing legacy profile needs explicit administrator reconciliation with a verified Auth identity. Its data remains preserved and inaccessible through the client API while unlinked.
