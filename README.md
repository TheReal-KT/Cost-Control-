# Agent Cost Control

Native Android application for tracking recurring services, renewal reminders and budgets. The client uses **Java 11 source + XML/Material 3**, with direct authenticated Supabase REST access. Financial calculations and risk checks are ordinary Java domain code. AI orchestration belongs to a separate trusted backend.

## Run locally

Open the project in Android Studio. Install the Android 37 SDK/build tools selected by Gradle. The checked-in Gradle daemon configuration requests **JDK 25**; Java source compatibility 11 does not mean a Java 11 runtime builds this project.

Configure these environment variables, or add the same names to the ignored `local.properties` beside `sdk.dir`:

```properties
SUPABASE_URL=https://YOUR_PROJECT.supabase.co
SUPABASE_PUBLISHABLE_KEY=sb_publishable_YOUR_PUBLIC_CLIENT_KEY
AI_ENDPOINT_URL=
```

Only a public, modern publishable client key belongs in the APK. Never configure a service-role/secret key or a model-provider key here. Supabase must enforce the ownership and column-access contract documented in [docs/database.md](docs/database.md). The existing project is `tulpokqxlemjlcbuwabl`; developers obtain its publishable key from its dashboard.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

The connected test command needs a running emulator or device. Auth-guard tests expect a fresh installation without a saved session. Each team member must still verify clone, build, sign-in and navigation on their own environment; this repository cannot verify five machines automatically.

If OneDrive locks generated build files, use a temporary Gradle init script to set `project.layout.buildDirectory` to a local directory outside the sync folder, then pass it with `-I`. Do not delete source or disable verification to resolve a generated-output lock.

## Application boundaries

- `model`: typed records and validated form drafts; money is `BigDecimal`, row IDs are `long`, dates and instants use `java.time`.
- `data`: HTTPS transport, Supabase Auth/profile/CRUD and encrypted session persistence. Call synchronous repository operations from a worker thread.
- `domain`: currency-separated financial summaries and deterministic, bounded risk checks. No model calls or database writes.
- `ui` and `MainActivity`: native presentation, form state, navigation and background-result coordination. Root tabs are peers; task routes return to their origin.
- `chat`: client for an authenticated application AI endpoint. It does not contact OpenRouter directly or include a provider credential.

The AI root contains Chat and Insights. Chat keeps its model control inside the prompt field. The only initially supported model is `nvidia/nemotron-3.5-lightning:free`; its provider's data terms require a synthetic-data prototype. Backend configuration must enforce that constraint, not trust the client's `data_mode` field. With no AI endpoint configured, Send keeps the draft and reports that Chat is not connected. No generated answer is simulated.

The proposed endpoint accepts `prompt`, `model`, optional `conversation_id`, and `data_mode: "synthetic"`, with a verified access token in the Authorization header. Its initial client response contract is JSON containing nonempty `answer` (at most 16,000 characters) and an opaque `conversation_id` (1–128 letters, digits, underscores or hyphens). The current client uses request/response JSON; streaming, grounded source links and durable server history require the subsequent backend implementation. See [AI architecture](docs/design/ai-chat-architecture.md).

Approval of an insight records a decision. It never cancels a provider service or moves money. Reminder CRUD saves schedules; local notification delivery is a separate planner task.

Insights also runs local renewal, budget, usage/cost and possible-category-overlap checks. High-cost thresholds are explicitly set per currency through the compact Thresholds control; no financial limit is assumed. These settings stay on the device and are scoped by the signed-in account UUID. Local checks remain distinct from stored backend recommendations. Current-month budgets are queried directly, while subscription summaries are bounded to 1,000 records and show a partial-data label if that bound is reached.

Headers show titles without back arrows. Android Back and gestures navigate task routes, and edited forms ask before discarding a draft. A write whose outcome is unknown requires checking the list and explicitly resolving it before another create is allowed.

## Review workflow

Work on a feature branch, open a reviewable PR, obtain another member's review and verify integration on main. Planner tasks stay open until their full acceptance criteria and review/device checks pass. See [catch-up assessment](docs/catchup-2026-10-06.md) for the current implementation and remaining verification.
