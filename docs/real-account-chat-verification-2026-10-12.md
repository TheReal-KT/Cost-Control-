# Real-account chat verification — 12 October 2026

## Behavior

The request now carries `data_mode: account`. The backend verifies the caller, reads their Supabase profile, subscriptions, current monthly budget and time-zone preference, calculates account facts, and sends the actual question plus those facts to Nemotron. General questions invoke the model instead of returning the old fixed topic limitation. There is no runtime sample-data fallback and no model write tool.

The composer states that questions and account facts go to OpenRouter. Tokens, identity IDs, emails and passwords are excluded from generated provider context; text deliberately entered by the user is forwarded as their question. Saved chat history remains display-only, and Gmail is not connected. [ADR 0005](adr/0005-real-account-ai-chat.md) records the change in data handling.

## Checks completed

- Backend: 54 tests passed with `uv run --locked python -B -m unittest discover -s tests`. Ruff lint and formatting checks passed.
- Regression coverage: distinct same-topic questions reach the provider unchanged; general prompts call the model; account GETs use the caller's JWT and verified ownership; foreign rows, missing profiles, malformed values and failed reads stop inference; empty accounts have no fixture fallback; zero and absent budgets differ; comparisons keep currencies separate; paging calculates complete totals; excessive collections fail explicitly; month boundaries use the account time zone; large name lists have bounded headers and truthful counts.
- Android: debug and release APK builds, 16 JVM tests and lint passed using `:app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug`.
- Isolated emulator instrumentation: 49 tests passed and one expected skip (the missing-backend case is inapplicable with a configured endpoint). This includes the opt-in real local TLS/invalid-session check. Initial runs exposed dialog timing in the history tests; selecting dialog roots and waiting for confirmation dismissal fixed synchronization without changing product behavior. The complete rerun finished without failures.
- APK scans found no configured provider key or TLS private key in either variant. The local public certificate appears only in debug.

## Live emulator

Installed the normal debug app while preserving the signed-in account and restarted the local HTTPS backend with the new contract. Three live requests returned HTTP 200:

1. “Which model powers this chat? Reply with the model name only.” → “NVIDIA Nemotron 3 Super”.
2. “How many active subscriptions do I have? Use only my account records.” → “0 (as of 2026-10-12, based on your account data)”.
3. After the final backend restart, “Explain my monthly spend using only my saved account records.” produced the deterministic header “Monthly equivalent: 0.00 (no active subscriptions). Currencies are separate.” and a generated explanation of the empty account. The model attached a dollar symbol to zero despite no saved currency; generated prose remains advisory, and the deterministic header is the authoritative figure.

The reconnected Supabase plugin confirmed no subscriptions or budgets were saved at verification time, matching the second answer. No account fixtures or credentials were added or extracted for this check. The populated-account integration tests use network-free fixtures only; they do not change the live account.

No new database migration is required. Supabase's existing authenticated grants and ownership policies protect these reads.
