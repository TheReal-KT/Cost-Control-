# Auth and data verification — 7 October 2026

Target: connected Supabase **Cost Control** (`tulpokqxlemjlcbuwabl`), discovered through the Supabase plugin. This report distinguishes live service checks from tests with simulated responses.

## Live checks

- Project status: `ACTIVE_HEALTHY`. Its API URL matches the Android configuration, which uses a modern publishable key. No database password or connection string was needed.
- All ten public application tables exist and have row-level security (RLS) enabled; 31 public policies exist. RLS means the database restricts which rows each signed-in user can access.
- Supabase security advisors returned zero findings.
- Auth settings returned HTTP 200: email authentication and signup are enabled; email confirmation is required.
- A deliberately nonexistent account's password login returned HTTP 400 / `invalid_credentials`, with no access token.
- Signed-out REST reads returned HTTP 401 for each of `users`, `providers`, `subscriptions`, `budgets`, `reminders`, `recommendations`, `notifications`, `user_decision`, `user_settings`, and `activity_logs`. No row contents were printed.
- The checked-in `supabase/tests/mvp_contract.sql` suite passed again against the live database. It tests two-user isolation, blocked ownership spoofing/cross-user writes, constraints, permitted mutations, dependent deletion and anonymous denial. All fixture data rolled back; sequence values can advance despite rollback.
- Counts before and after that suite were unchanged: zero Auth identities, three application profiles, one provider, zero subscriptions/budgets/reminders, and eight activity records. These are snapshots before the user's manual signup, not an ongoing claim about current counts.
- No Supabase Edge Functions are deployed.

## Automated client checks

The debug app and instrumentation APK build passed. `:app:testDebugUnitTest` executed again and all 16 JVM tests passed with zero failures, errors or skips. These cover money precision, owned query construction, financial summaries, risk rules and session validation.

All 25 instrumentation tests were then rerun on a hidden Pixel_7 emulator (Android 16 / API 36), using `adb -s emulator-5556 shell am instrument -w -r com.example.agentcostcontrol.test/androidx.test.runner.AndroidJUnitRunner`. The full successful run reported `OK (25 tests)` in 82.838 seconds, with zero failures or skips. The initial attempt reported `Process crashed` while Pixel Launcher/System UI were unresponsive; the observed emulator dialogs were cleared and the unchanged full suite retried. No legitimate test was disabled or modified. The test emulator was closed afterward.

Verification builds used the previously documented temporary Gradle init script to redirect generated output outside OneDrive. Raw device output is saved in the local visualization directory as `auth-device-tests-retry-2026-10-07.log`; generated output and credentials are not included in repository source.

The existing Android repository contract tests use a fake HTTP transport with explicit synthetic responses. This lets them deterministically test token refresh, failed writes, malformed responses and exact amounts. The UI tests run actual native Android views. Passing these tests is meaningful evidence of client behavior; it does not prove successful live email signup, login or REST persistence.

## Manual acceptance still required

The user chose to test successful authentication in the application rather than provide a disposable account's credentials for automation. No real login credentials were requested in chat, exported or used by this verification.

1. Create an account with a new email, confirm the email, then sign in. If prompted, finish profile setup.
2. Create, edit and delete a synthetic service, reminder and current-month budget. Restart the app between creation and deletion to check persistence.
3. Sign out, check that protected tabs disappear, and sign in again.
4. Repeat with a second account and verify that the first account's records are absent. The database suite already tests the underlying policies, but this adds the full Android/Auth/REST path.

AI chat remains unconnected until its trusted endpoint is implemented/configured. Reminder records can be saved; notification delivery is a separate task. These are not covered by the passing database checks.

## Where code belongs

- `app/src/main/java/com/example/agentcostcontrol/data`: Android-side HTTPS requests, Supabase Auth/profile/CRUD adapters, response parsing, encrypted device session storage and account-scoped local thresholds. This code ships inside the APK and runs on the user's device.
- `supabase/migrations`: database tables, constraints, policies and database functions/triggers, executed in Supabase PostgreSQL.
- `backend`: the existing Python/FastAPI prototype. The current Android CRUD flow calls Supabase directly and does not use these routes. Trusted AI orchestration belongs on a server; this prototype has no LangGraph chat endpoint yet.
- `supabase/functions`: the conventional location for Edge Function source if that deployment option is chosen later; no such functions are implemented/deployed here.

Android needs the project API URL and public publishable key plus the signed-in user's token. Direct PostgreSQL connection strings and privileged service/model keys belong only in trusted server configuration. Auth manages login identities in its own schema; the application's public tables are still required for profiles and app data. Removing them would break the app's persistent flows.

## Android Studio resource-build correction

After the user reported a debug resource failure, the normal `:app:processDebugResources` build reproduced `AccessDeniedException` in both `mergeDebugResources` and `packageDebugResources`, under the OneDrive checkout's generated `app/build` folders. Earlier verification used an external output directory through a temporary init script, so it did not prove that Android Studio's default output location was writable. Resource-generation failure can also leave the editor unable to resolve generated `R` references in `MainActivity`.

The root Gradle script now supports an optional `BUILD_OUTPUT_DIR` in ignored `local.properties`. This machine uses `C:/Users/khulu/AppData/Local/AgentCostControl/build`. Both IDE and ordinary command-line builds use the setting after sync; other machines retain the default unless they configure it. A guard rejects the source project directory and its parents as output roots to protect source from a later Gradle clean.

Verification ran the normal command, with **no temporary init script**: `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug`. All 48 scheduled tasks executed successfully, all 16 unit tests passed with zero failures/errors/skips, lint reported zero errors and 76 warnings, and the APK exists at `app/outputs/apk/debug/app-debug.apk` beneath the configured output folder. Java compilation passed; the existing system-bar API deprecation warning remains. Android Studio still needs a project sync to reload its model and resource references; that UI action was not automated.
