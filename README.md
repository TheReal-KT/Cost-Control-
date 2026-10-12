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

If OneDrive locks generated build files, add an absolute `BUILD_OUTPUT_DIR` path outside the sync folder to ignored `local.properties`, for example `BUILD_OUTPUT_DIR=C:/Users/YOUR_NAME/AppData/Local/AgentCostControl/build`. Sync the project again in Android Studio. Gradle places each project's generated output beneath that folder, including the APK at `app/outputs/apk/debug/app-debug.apk`. This setting applies to both Android Studio and normal command-line builds; without it, the usual project-local build folders are used. Do not delete source or disable verification to resolve a generated-output lock.

### Connect the emulator to local AI

Configure ignored `backend/.env` as described in [backend setup](backend/README.md), start one emulator, and run from the repository root using PowerShell 7:

```powershell
pwsh -File scripts/Initialize-LocalDemo.ps1
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:installDebug
```

Initialization generates a 30-day localhost TLS certificate and private key in ignored `.local-demo/`, sets Android's ignored `AI_ENDPOINT_URL` to `https://127.0.0.1:8443/ai/chat`, and forwards device port 8443 to this computer using ADB. Only the public certificate enters debug resources, trusted only for loopback; release builds retain system certificate trust and exclude it. Rerun initialization after an emulator/ADB restart; rebuild after certificate renewal. This requires no public tunnel or machine-wide certificate installation. Start the backend in another terminal:

```powershell
cd backend
uv run --locked --env-file .env uvicorn agentic_subscription_manager.api:create_app --factory --host 127.0.0.1 --port 8443 --ssl-keyfile ../.local-demo/server.key.pem --ssl-certfile ../.local-demo/server.cert.pem --no-access-log
```

Open **Cost Control**, sign in, then **AI → Chat**. Ask “Explain my monthly spend” to stream a fictional example. The separate **Cost Control Preview** launcher contains sample subscription screens and cannot authenticate chat. Stop keeps partial text labelled incomplete and retains the question for retry. For an opt-in emulator-to-backend TLS/Auth check, with the server running:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.example.agentcostcontrol.chat.LocalAiConnectionTest' '-Pandroid.testInstrumentationRunnerArguments.verifyLocalAi=true'
```

For a physical Android device, connect it by USB with debugging enabled and use the same ADB forwarding; initialize with one device/emulator attached. A release build requires a publicly trusted HTTPS endpoint configured separately.

## Application boundaries

Debug builds also install a **Cost Control Preview** launcher. It shows 13 fictional subscriptions,
including branded services, paused/cancelled records and an unknown-provider fallback. Home totals,
Services search/filtering and detail screens reuse the real UI; sample prices are illustrative,
not current market prices. Preview data lives only in memory and cannot be saved. Its activity and
fixtures are under `app/src/debug`, so release builds exclude them. Your normal account data and
saved session are unaffected. See [brand architecture comparison](docs/design/brand-logo-architecture.md).

- `model`: typed records and validated form drafts; money is `BigDecimal`, row IDs are `long`, dates and instants use `java.time`.
- `data`: HTTPS transport, Supabase Auth/profile/CRUD and encrypted session persistence. Call synchronous repository operations from a worker thread.
- `domain`: currency-separated financial summaries and deterministic, bounded risk checks. No model calls or database writes.
- `ui` and `MainActivity`: native presentation, form state, navigation and background-result coordination. Root tabs are peers; task routes return to their origin.
- `chat`: client for an authenticated application AI endpoint. It does not contact OpenRouter directly or include a provider credential.

The AI root contains Chat and Insights. Chat keeps its model control inside the prompt field. The supported model is `nvidia/nemotron-3-super-120b-a12b:free`. The prototype remains synthetic-only until endpoint data handling is reviewed for private records. The backend enforces this constraint. With no AI endpoint configured, Send keeps the draft and reports that Chat is not connected. No generated answer is simulated.

The Python/FastAPI service implements `/ai/chat` with three Python LangGraph nodes. It accepts `prompt`, `model`, optional `conversation_id`, `data_mode: "synthetic"`, and optional `stream: true`. It verifies the Supabase session, then streams application events to Android; JSON replies remain supported. It selects supported topics locally and sends only fictional fixture evidence to the model; raw questions and real account records are never forwarded. There are no model tools, write executors or Gmail connections. Legacy Python login/CRUD routes are inactive. Android saves account-scoped conversations directly to Supabase and offers a Chat-only history drawer; saved history is not sent to the model. See [backend setup](backend/README.md), [ADR 0003](docs/adr/0003-streaming-local-android-ai.md), [chat history decision](docs/adr/0004-native-chat-history.md) and [AI architecture](docs/design/ai-chat-architecture.md). Source links and private-account analysis remain separate work.

Approval of an insight records a decision. It never cancels a provider service or moves money. Reminder CRUD saves schedules; local notification delivery is a separate planner task.

Insights also runs local renewal, budget, usage/cost and possible-category-overlap checks. High-cost thresholds are explicitly set per currency through the compact Thresholds control; no financial limit is assumed. These settings stay on the device and are scoped by the signed-in account UUID. Local checks remain distinct from stored backend recommendations. Current-month budgets are queried directly, while subscription summaries are bounded to 1,000 records and show a partial-data label if that bound is reached.

Headers show titles without back arrows. Android Back and gestures navigate task routes, and edited forms ask before discarding a draft. A write whose outcome is unknown requires checking the list and explicitly resolving it before another create is allowed.

## Review workflow

Work on a feature branch, open a reviewable PR, obtain another member's review and verify integration on main. Planner tasks stay open until their full acceptance criteria and review/device checks pass. See [catch-up assessment](docs/catchup-2026-10-06.md) for the current implementation and remaining verification.
