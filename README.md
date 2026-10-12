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

### Android Studio cannot load build artifacts

After changing `BUILD_OUTPUT_DIR`, Android Studio can retain the previous APK location even when Gradle builds successfully. If Run reports an error loading `apk_ide_redirect_file/.../redirect.txt`:

1. Save `local.properties`, then use **File → Sync Project with Gradle Files** (`Ctrl+Shift+O`). Wait for sync to finish.
2. Select the **app** run configuration and the running emulator, then click **Run**. The default launcher is the real Cost Control application.
3. If the error persists, regenerate the IDE artifacts, sync again, and retry Run:

   ```powershell
   $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
   .\gradlew.bat :app:assembleDebug '-Pandroid.injected.invoked.from.ide=true' --rerun-tasks --no-configuration-cache --max-workers=2
   ```

The redirect file points to APK metadata under `app/intermediates/apk/debug`. Android Studio uses these IDE artifacts; a normal command-line build can instead place its APK under `app/outputs/apk/debug`. Do not manually rewrite `redirect.txt` or point the run configuration at an old APK. Each developer configures their own ignored output path and syncs locally.

To run directly from a terminal without Android Studio's artifact loader, start one emulator and run:

```powershell
pwsh -File scripts/Run-Android.ps1
```

This builds and installs the current debug application through Gradle, then launches `MainActivity` through ADB. It preserves existing application data and respects `BUILD_OUTPUT_DIR`. It uses ADB from PATH, `ANDROID_HOME`/`ANDROID_SDK_ROOT`, or the default Windows SDK location. If `JAVA_HOME` is unset, it uses Android Studio's bundled JDK at its default Windows installation path when available. Start local AI separately using the instructions below.

### Connect the emulator to local AI

Configure ignored `backend/.env` as described in [backend setup](backend/README.md), start one emulator, and run from the repository root using PowerShell 7:

```powershell
pwsh -File scripts/Initialize-LocalDemo.ps1
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:installDebug
```

Initialization generates a 30-day localhost TLS certificate and private key in ignored `.local-demo/`, sets Android's ignored `AI_ENDPOINT_URL` to `https://127.0.0.1:8443/ai/chat`, and forwards device port 8443 to this computer using ADB. Only the public certificate enters debug resources, trusted only for loopback; release builds retain system certificate trust and exclude it. Rebuild after certificate renewal. This requires no public tunnel or machine-wide certificate installation. Start the backend from the repository root in another terminal:

```powershell
pwsh -File scripts/Start-LocalAi.ps1
```

Keep the server terminal open while using Chat. After restarting the emulator or ADB, rerun `scripts/Initialize-LocalDemo.ps1` to restore forwarding if the server is still running, or `scripts/Start-LocalAi.ps1` if it has stopped.

Open **Cost Control**, sign in, then **AI → Chat**. Ask “Explain my monthly spend” to stream an answer using your saved subscriptions and current budget. An empty account shows zero spending; Chat never substitutes sample records. Your question and account facts are sent to OpenRouter. Stop keeps partial text labelled incomplete and retains the question for retry. For an opt-in emulator-to-backend TLS/Auth check, with the server running:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.example.agentcostcontrol.chat.LocalAiConnectionTest' '-Pandroid.testInstrumentationRunnerArguments.verifyLocalAi=true'
```

For a physical Android device, connect it by USB with debugging enabled and use the same ADB forwarding; initialize with one device/emulator attached. A release build requires a publicly trusted HTTPS endpoint configured separately.

## Application boundaries

Debug builds retain an internal preview activity for UI verification, with 13 fictional subscriptions.
It has no launcher entry and is not exported to other apps. Its activity and fixtures are under
`app/src/debug`, so release builds exclude them. Preview data lives only in memory and cannot be
saved. See [brand architecture comparison](docs/design/brand-logo-architecture.md).

- `model`: typed records and validated form drafts; money is `BigDecimal`, row IDs are `long`, dates and instants use `java.time`.
- `data`: HTTPS transport, Supabase Auth/profile/CRUD and encrypted session persistence. Call synchronous repository operations from a worker thread.
- `domain`: currency-separated financial summaries and deterministic, bounded risk checks. No model calls or database writes.
- `ui` and `MainActivity`: native presentation, form state, navigation and background-result coordination. Root tabs are peers; task routes return to their origin.
- `chat`: client for an authenticated application AI endpoint. It does not contact OpenRouter directly or include a provider credential.

The AI root contains Chat and Insights. Chat uses one compact action row for Insights, history and New chat, without repeated AI/Chat headings. Bottom navigation hides while the chat keyboard is open so the composer stays directly above it, and returns when the keyboard closes. Chat keeps its model control inside the prompt field. The supported model is `nvidia/nemotron-3-super-120b-a12b:free`. Chat uses authenticated account records and sends the actual question to Nemotron. With no AI endpoint configured, Send keeps the draft and reports that Chat is not connected. No generated answer is simulated.

The Python/FastAPI service implements `/ai/chat` with two Python LangGraph nodes. It accepts `prompt`, `model`, optional `conversation_id`, `data_mode: "account"`, and optional `stream: true`. It verifies the Supabase session, resolves the owned profile, reads subscriptions and the current budget with the caller's token, calculates per-currency facts and sends those facts plus the actual question to Nemotron. Tokens and identity fields are excluded from provider messages. It streams application events to Android; JSON replies remain supported. There are no model tools, write executors or Gmail connections. Legacy Python login/CRUD routes are inactive. Android saves account-scoped conversations directly to Supabase and offers a Chat-only history drawer; saved history is not sent to the model. See [backend setup](backend/README.md), [real-account decision](docs/adr/0005-real-account-ai-chat.md), [chat history decision](docs/adr/0004-native-chat-history.md) and [AI architecture](docs/design/ai-chat-architecture.md). Source links and model memory remain separate work.

Approval of an insight records a decision. It never cancels a provider service or moves money. Reminder CRUD saves schedules; local notification delivery is a separate planner task.

Insights also runs local renewal, budget, usage/cost and possible-category-overlap checks. High-cost thresholds are explicitly set per currency through the compact Thresholds control; no financial limit is assumed. These settings stay on the device and are scoped by the signed-in account UUID. Local checks remain distinct from stored backend recommendations. Current-month budgets are queried directly, while subscription summaries are bounded to 1,000 records and show a partial-data label if that bound is reached.

Headers show titles without back arrows. Android Back and gestures navigate task routes, and edited forms ask before discarding a draft. A write whose outcome is unknown requires checking the list and explicitly resolving it before another create is allowed.

## Review workflow

Work on a feature branch, open a reviewable PR, obtain another member's review and verify integration on main. Planner tasks stay open until their full acceptance criteria and review/device checks pass. See [catch-up assessment](docs/catchup-2026-10-06.md) for the current implementation and remaining verification.
