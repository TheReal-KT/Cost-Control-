# Gmail integration setup and verification

Date: 22 October 2026, Africa/Johannesburg.

## Result

Three Luna subagents at max effort assisted with runtime inspection, Android integration, backend integration and independent review. The active application now has an explicit Gmail connection and subscription discovery flow. Composio is installed, the dependency lockfile is updated, and the owner-scoped import migration is applied to the existing Cost Control Supabase project. The existing private backend configuration was preserved.

Run from the repository root:

```powershell
pwsh -File scripts/Initialize-Backend.ps1
```

The setup command installs locked dependencies, creates `backend/.env` only if absent, appends missing Composio placeholders, and prints configuration presence without printing credentials. It refuses to proceed if Git would track the environment file. Repeating setup preserves existing values.

At the 22 October setup checkpoint, Account API and AI chat were configured while Gmail discovery was waiting for the Composio project key. A local HTTPS server was started on port 8443 and `/health` returned HTTP 200 with the existing certificate verified. Configuration presence does not establish model-provider access. See the 23 October follow-up below for the key handoff.

## Owner handoff

1. The project owner supplies a Composio **Platform project** API key through the team's private secret channel.
2. Add `COMPOSIO_API_KEY` to the existing ignored `backend/.env`; preserve the existing Supabase and model settings. Never add it to Android `local.properties` or source control.
3. Optionally configure a Composio Gmail auth configuration with Google's `gmail.readonly` scope and set `COMPOSIO_GMAIL_AUTH_CONFIG_ID`. The application's fetch-only tool allowlist is separate from OAuth consent permissions.
4. Restart the backend so it reads the new configuration. For local Android use, start one emulator and run `pwsh -File scripts/Start-LocalAi.ps1` from the repository root. That script handles the matching TLS certificate, ADB forwarding and application install.
5. Sign in to Cost Control, open **Profile → Connections → Connect Gmail**, finish browser consent, return to the app and tap **Sync subscriptions**. Home and Services reload the saved records and existing currency-specific spending calculations.

Live completion requires a real read-only Gmail result and a Composio log ID. Neither a configured key nor a returned connection link alone proves Gmail access. The 22 October checkpoint had no live Composio result because the key was pending.

## Changes and findings

- Kept Supabase Auth as the identity authority. The backend verifies the caller and uses the Auth UUID for Composio; the import function derives the canonical profile internally.
- Added explicit connect/sync controls, bounded network requests and retained sync state across Activity recreation. Account switches invalidate old outcomes. A request is bound to its initiating account before attaching its token.
- Damaged or lost sync responses trigger an honest uncertain-outcome message and reload saved services, since the database may have committed already.
- Conservative receipt parsing rejects ambiguous or truncated prices, marketing offers, failed payments, stale renewals and cancellation evidence. Separate currencies and billing cycles are retained.
- Imported records and receipt IDs save atomically. Repeated receipts do not create duplicate services; existing prices, manual edits and cancellation states are preserved. A previously imported receipt does not recreate a deleted service.
- Fixed a streaming cancellation regression found during implementation by limiting the integration metadata middleware to its own routes and preserving the chat request lifecycle.
- Pinned Composio 0.30.0 and its required client prerelease together. Configured uv to copy packages because OneDrive rejects incompatible cache hardlinks. The existing Windows Store Python interpreter works with appropriate local file access; it did not need replacement.
- The existing Supabase advisor warns that leaked-password protection is disabled. Review [Supabase's password protection configuration](https://supabase.com/docs/guides/auth/password-security#password-strength-and-leaked-password-protection). The existing `users_insert_own` policy also has a per-row Auth evaluation performance warning; new import policies produced no additional advisor warnings. Unused-index notices were not treated as a reason to remove indexes without workload evidence.

## Verification

| Check | Result |
| --- | --- |
| `uv sync --locked` with the final copy setting | Passed; Composio installed |
| `uv run --locked python -B -m unittest discover -s tests -v` | 83 tests passed |
| `uv run --locked ruff check src tests main.py` | Passed |
| `uv run --locked ruff format --check src tests main.py` | Passed |
| Android `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest` | Passed |
| `scripts/tests/LocalAiStartupTest.ps1` | 10 checks passed |
| `scripts/tests/BackendSetupTest.ps1` | Five checks passed |
| `supabase/tests/subscription_import_contract.sql` on the connected database | Passed: retries, edits, currencies, invalid-batch rollback, deletion, owner isolation and anonymous denial; fixtures rolled back |
| Existing `supabase/tests/chat_history_contract.sql` after the migration | Passed; fixtures rolled back |
| Existing `supabase/tests/mvp_contract.sql` through the connected SQL tool | No result: tool returned `Invalid or expired requestState`; this suite is not claimed passed |
| Local HTTPS `/health` with certificate verification | HTTP 200 |
| `git diff --check` | Passed |

The initial Android attempts encountered cache permissions and stale generated-file locks. Verification used the installed Gradle/JDK/SDK with suitable file access and completed successfully. Existing untracked `.idea` files were left untouched. No source changes were made to the inactive legacy Python credential/CRUD routers.

## Limits

This is a deliberate scan of at most 50 matching messages from the last 90 days. It does not guarantee finding every subscription or recognize every receipt format. More matching email can remain beyond this page; this version does not provide mailbox-wide pagination. Existing saved service prices are not automatically overwritten. Users should review imported services and add or correct missing details manually. No scheduled sync, billing cancellation or notification-delivery system is introduced.

## 23 October 2026: key handoff and live verification

The owner-provided key is now saved in the existing ignored environment file. Setup reports all three configuration groups ready, and the local HTTPS backend was restarted with it. Real requests to Composio's Gmail toolkit and `GMAIL_FETCH_EMAILS` catalog endpoints succeeded. The live tool version is `20261018_00`. These were metadata reads; no arbitrary Composio user ID or mailbox was selected.

The live SDK/schema check found two integration defects missed by the original fakes: successful session executions use `result_type="completed"`, and current messages expose `sender` and `messageTimestamp`. The gateway and parser now recognize those contracts, reject underlying unsuccessful tool results, and include regression fixtures using the catalog's actual message fields.

After those compatibility corrections, all 84 backend unit tests, Ruff lint, and Ruff formatting checks passed. The backend was restarted again with the verified code and the owner-provided key.

The running backend also passed real HTTPS checks for `/health` and missing/invalid-session rejection on Gmail status and sync routes. Unauthorized integration responses returned 401 with `no-store` caching headers. The setup command's five regression checks passed again after improving its final message for already-configured installations.

Android's ignored `BUILD_OUTPUT_DIR` was changed to `C:/Users/khulu/AppData/Local/AgentCostControl/build-gmail-20261023` after the former generated-output directory was locked. Android Studio should sync Gradle before its next Run so it learns the new artifact path.

The opt-in `LiveGmailConnectionTest` compiled successfully in the fresh output directory. It restores the app's encrypted session, requires `verifyGmail=true`, and requires a separate `syncGmail=true` opt-in for a real scan/import. Missing sign-in or Gmail consent is reported as an incomplete/skipped live verification rather than a provider success.

Device execution is blocked by the existing Pixel_4a emulator: APK installation returned `Error: device is still booting`, including after one non-wiping cold boot. Its package service was intermittently unavailable. No Android test cases ran. The initial connected-test task printed `BUILD SUCCESSFUL` despite its runner/install failure, so that message is not evidence of device-test success. Its uninstall attempts failed; no uninstall succeeded. Further live checks use direct `adb install -r` and instrumentation to preserve app sign-in. The SDK has an installed Android system image but no `avdmanager.bat` at its standard locations, so a fresh temporary device could not be created with the available CLI. Only the emulator process started for verification was stopped.

The current APKs are under `C:/Users/khulu/AppData/Local/AgentCostControl/build-gmail-20261023/app/outputs/apk/`: `debug/app-debug.apk` and `androidTest/debug/app-debug-androidTest.apk`. Resume on a fully booted emulator or connected phone, sign in to Cost Control, and complete Gmail authorization. There has been no real Gmail fetch, Composio execution log ID, subscription import, or authenticated Home update verification yet. Successful metadata calls validate project access, not access to a user's mailbox.

The catalog declares `messageTimestamp` only as an optional string, without an example or timezone guarantee. The parser accepts explicit timezone-aware timestamps and existing date-header fallbacks; messages without enough date evidence are conservatively skipped. The schema-shaped ISO timestamp fixture establishes parser support, not a guarantee of actual mailbox payload formatting.
