# Native chat history verification — 12 October 2026

Branch: `chat-history-ui`.

This records the initial history implementation. The subsequent actual-question and real-account change is covered in [real-account chat verification](real-account-chat-verification-2026-10-12.md).

## Implemented

- Compact Chat/Insights controls hide on the first accepted Send and return with New Chat.
- Chat-only history side panel with paging, reopening, retry and confirmed deletion.
- Right-aligned user bubbles followed by left-aligned assistant answers; icon Send/Stop remains accessible.
- Account-scoped Supabase conversations and paired turns, atomic save retries and explicit incomplete answers.
- Captured account scopes reject queued work after an account switch. Generation checks ignore stale inference/load callbacks; save revisions keep older partial results from marking newer content saved.
- Bounded recreation state preserves drafts and failed saves across conversations. Older persisted messages remain reloadable after snapshot trimming.

## Database checks

Applied `20261012143650_chat_history` to the Cost Control project (`tulpokqxlemjlcbuwabl`). The local migration filename matches the recorded remote version. Catalog checks confirmed both tables have RLS and seven ownership policies; anonymous table reads/RPC execution are denied while authenticated execution is granted.

Rolled-back fictional-account integration checks passed for idempotent saves, partial-to-complete transitions, delayed partial protection, validation/conflicts, composite ownership foreign keys, isolated UUID reuse, cascading deletion and anonymous denial. Cross-account UPDATE isolation passed as a separate rolled-back transaction.

The combined owner READ/DELETE/INSERT isolation block could not be completed through the connector: calls returned `Invalid or expired requestState`. After reconnection, even migration-list calls returned `Unknown tool`; the plugin was reported installed and enabled. Those remote assertions are not counted as passed. The complete reproducible suite remains in `supabase/tests/chat_history_contract.sql`.

Security advisor reported the existing [disabled leaked-password protection](https://supabase.com/docs/guides/auth/password-security#password-strength-and-leaked-password-protection). Performance advisor reported an existing [users insert-policy initialization warning](https://supabase.com/docs/guides/database/database-linter?lint=0003_auth_rls_initplan) and unused-index information, including the newly created turn index before use. No chat-table RLS security warning was reported. No Auth configuration was changed.

## Automated checks

- Backend: `uv run --locked python -B -m unittest discover -s tests` — 42 passed. Ruff lint and formatting checks passed.
- Android: `:app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug` passed, including 16 JVM tests and release lint checks. The final build includes the delete/save race guard.
- Android native suite on API 36 using isolated `.streamverify` application ID: 50 discovered, 49 passed, one expected assumption skip because a backend endpoint is configured. Executed the compiled test APK directly with `adb shell am instrument -w -r -e verifyLocalAi true com.example.agentcostcontrol.streamverify.test/androidx.test.runner.AndroidJUnitRunner`; runner reported `OK (50 tests)`.
- Coverage includes history load/reopen/retry/delete, message alignment, New Chat state, stale remote answers, failed-save restoration, bounded history snapshots, repository account-switch guards and malformed stored responses. A latch-controlled regression confirms Retry Save cannot queue a save behind a pending deletion, including programmatic clicks; failed deletion restores the retry. The opt-in local HTTPS/auth rejection check passed.

The normal application's signed-in account was preserved. Initial test failures in alignment and activity startup synchronization were corrected. Gradle's connected-test wrapper subsequently hit Windows report-directory access locks; direct instrumentation avoided that filesystem issue without skipping tests.

APK inspection confirmed neither variant contains the configured provider key or TLS private key. Only the debug APK contains the local public certificate; the release APK excludes it.

## Live emulator check

The normal signed-in app called the local HTTPS backend and received a streamed Nemotron answer with HTTP 200. The Chat/Insights controls disappeared after Send, with the question on the right and answer below on the left. New Chat restored the compact controls. The history drawer listed the new conversations; after force-stopping and reopening the application, the saved question and answer reopened successfully from Supabase. No saved credentials were extracted for testing.

The live answer used fictional evidence. It incorrectly described the separate USD and ZAR totals as a currency conversion; the deterministic summary correctly states that the currencies are separate. This existing model-output limitation remains outside the chat persistence change.

## Data boundary

Saving history is an authenticated Android operation, not a model write tool. Saved questions/answers remain in Supabase until deleted. Loading them restores the native display; it does not provide conversational context to the model. Inference continues to use fictional fixtures only, with no Gmail connection or account-action executor.
