# Agent Cost Control AI service

Python/FastAPI hosts a small read-only LangGraph workflow. Supabase remains the sole authentication and application-data authority. Android subscription/budget/reminder/profile operations continue through Supabase directly; they do not pass through this service.

## Run and configure

From the repository root, prepare the backend while waiting for the Composio project owner to supply the key:

```powershell
pwsh -File scripts/Initialize-Backend.ps1
```

This installs locked dependencies, creates an ignored `.env` template only if absent, appends missing Composio placeholders, and prints configuration status without displaying values. It preserves existing keys and works before Gmail is configured. Add `COMPOSIO_API_KEY` to the local file when the owner supplies it, restart the server, then use **Profile → Connections → Connect Gmail → Sync subscriptions**. Each user authorizes their own Gmail account in the browser; the owner’s project API key stays on the server. Setup does not create a Composio project or authorize a Gmail account.

Install [uv](https://docs.astral.sh/uv/) and Python 3.11 or later. From `backend/`:

```powershell
uv sync --locked
uv run --locked python -B -m unittest discover -s tests -v
uv run --locked ruff check src tests main.py
uv run --locked ruff format --check src tests main.py
```

Composio is pinned to 0.30.0 together with its required `composio-client==2.0.0rc19` prerelease so uv can resolve and reproduce the SDK dependency graph with its default prerelease policy.

The project uses package copies rather than hardlinks because OneDrive can reject hardlinked environment files with Windows error 396. Composio and its required prerelease client are explicitly pinned together; the lockfile records the complete dependency set. If a coding sandbox cannot access a Windows Store Python interpreter, run these commands in a normal local terminal or use a permitted installed Python interpreter rather than rewriting the environment's interpreter configuration.

Set the five required environment variables listed in `.env.example`, or copy that file to ignored `.env` and fill it locally. `SUPABASE_URL` is the project's HTTPS origin. Use its public **publishable** key, never a database/service-role/secret key. `OPENROUTER_API_KEY`, `COMPOSIO_API_KEY`, and a random `AI_CONVERSATION_SECRET` of at least 32 characters belong only on the server. Generate the latter with `python -c "import secrets; print(secrets.token_urlsafe(48))"`. Do not reuse a Supabase JWT signing secret. Composio is independent of chat configuration, so Gmail account linking and subscription sync work when the model key is absent.

```powershell
uv run --locked --env-file .env agentic-subscription-manager
```

This starts a local server on `127.0.0.1:8000`. The compatibility command `uv run --locked --env-file .env python main.py` starts the same AI app with development reload. The installed console command and `main.py` import no legacy routers or SQLAlchemy database configuration.

For hosting, run `uv run --locked uvicorn agentic_subscription_manager.api:create_app --factory --host 0.0.0.0 --port 8000 --workers 1 --no-access-log` behind an HTTPS reverse proxy. Configure server environment variables in the host's secret settings. Set body/header/read limits at the proxy; request bodies, headers and URL query strings must not be logged. The provided entry points disable generic access logs and use the service's safe request metadata logger. Locate the service near Supabase to minimize authentication round-trip time. Set Android's ignored `AI_ENDPOINT_URL` to the complete public `https://YOUR_HOST/ai/chat` route and rebuild. Android deliberately refuses plain HTTP, including localhost HTTP.

For local Android testing, use the TLS certificate and ADB forwarding setup in the [root README](../README.md#connect-the-emulator-to-local-ai). No public service is deployed. `/health` shows whether values are present, not whether the credentials/provider work. With missing values it remains available, while chat fails closed with 503. No model keys belong in Android `local.properties` or its APK.

## Active API

- `GET /health`: readiness/configuration summary, no secrets or data.
- `POST /ai/chat`: the Android request/response contract below.
- `GET /integrations/gmail`: reports whether Composio is configured and whether the authenticated user has an active Gmail connection.
- `POST /integrations/gmail/connect`: returns a Composio Connect Link for the authenticated user.
- `POST /integrations/subscriptions/sync`: scans recent Gmail evidence and imports validated subscriptions using the caller's Supabase access token.

All three integration endpoints require `Authorization: Bearer <Supabase access token>`. The service verifies that token with Supabase Auth before using the verified Auth UUID as the Composio user ID. The OAuth link comes from Composio; the backend does not handle provider credentials. Each session enables only the Gmail toolkit and its `GMAIL_FETCH_EMAILS` tool, with the sandbox disabled, and the application only runs bounded email fetches. Status and sync sessions are deleted after use; a connect session stays available for the authorization flow to finish. This tool allowlist limits what the application executes; Google's OAuth scopes are controlled separately by the Composio auth configuration. Composio's managed Gmail consent can request broader scopes. To restrict consent to read access, configure a Gmail auth config in Composio with Google's `gmail.readonly` scope and set its ID in the optional server-only `COMPOSIO_GMAIL_AUTH_CONFIG_ID` variable.

Subscription discovery scans at most 50 messages from the last 90 days per request. It imports only the freshest qualifying receipt for each service, currency, and billing cycle when the email shows a completed charge, an explicit recurring billing cycle, and a future next-renewal date. One-off purchases, marketing prices, ambiguous amounts, old emails, and unclear renewal dates are skipped. The `skipped` count includes all scanned emails not imported, including duplicates, existing service records, and messages without enough evidence. Gmail message IDs are the receipt IDs; Supabase's `import_subscription_receipts` RPC uses them and existing service records to make repeated syncs idempotent while preserving manual records. Email bodies are parsed in the backend and are never sent to the model provider or stored in the subscription table. A `has_more` response indicates that Gmail returned additional matching messages beyond the bounded page.

Integration responses use `Cache-Control: private, no-store`; safe request logs include only a request ID, operation, status and duration. Gmail operations are admitted at four in flight globally and two per account. Status/connect requests have a 20-second deadline; subscription sync has a 75-second overall deadline, uses bounded Composio SDK requests with retries disabled, and does not start the receipt RPC unless its 8-second timeout fits within the remaining request budget. SDK work runs outside the event loop and is joined after cancellation before the request releases its admission slot.

An opt-in Android test exercises the real signed-in connection without exporting credentials. With the local HTTPS backend and one fully booted emulator running, build the APKs, install them with `adb install -r`, and invoke instrumentation directly. This preserves the app's saved sign-in; Gradle's connected-test runner can uninstall target packages during cleanup. From the repository root:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
# Use the APK paths printed by Gradle; BUILD_OUTPUT_DIR can relocate them.
adb install -r '<build-output>/app/outputs/apk/debug/app-debug.apk'
adb install -r '<build-output>/app/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
adb reverse tcp:8443 tcp:8443
adb shell am instrument -w -r -e class com.example.agentcostcontrol.data.LiveGmailConnectionTest -e verifyGmail true com.example.agentcostcontrol.test/androidx.test.runner.AndroidJUnitRunner
```

It reports `NEEDS_SIGN_IN` or `CONNECT_REQUIRED` and marks incomplete authorization as skipped. A connection link authorizes the actual signed-in app user. After completing Gmail consent, adding `-e syncGmail true` before the instrumentation component explicitly opts into one real discovery/import and owner-scoped reload. Results contain counts only; tokens, email bodies and keys are never exported. A passing connection check alone is not a verified mailbox scan.

With the same APKs installed and the app signed in, verify the real model response and streaming path:

```powershell
adb shell am instrument -w -r -e class com.example.agentcostcontrol.chat.LiveAiModelTest -e verifyAi true com.example.agentcostcontrol.test/androidx.test.runner.AndroidJUnitRunner
```

This sends one question through the authenticated backend and checks that the model answers it and the completed text matches the streamed text. It reports only the model ID and response lengths. A missing sign-in skips the check; a skipped check does not establish live provider success.

```json
{
  "prompt": "Explain my monthly spend",
  "model": "nvidia/nemotron-3.5-lightning:free",
  "data_mode": "account",
  "stream": true
}
```

Send the user's Supabase access token as `Authorization: Bearer ...`. Omit `stream`, or set it to false, for JSON containing `answer` and `conversation_id`. Questions are limited to 4,000 characters; extra fields, unsupported models and data modes other than `account` are rejected. Request bodies are bounded to 32 KiB before JSON parsing. Validation failures do not echo questions. Rebuild Android when upgrading from the earlier `synthetic` contract; old clients fail validation instead of silently receiving sample data.

With `stream: true`, the response is `text/event-stream`, with `Cache-Control: no-store` and `X-Accel-Buffering: no`. Authorization, conversation ownership and initial quota errors are ordinary HTTP errors before streaming starts. The application's SSE events are:

```text
event: start
data: {"conversation_id":"caller_bound_id"}

event: delta
data: {"text":"Account data ..."}

event: done
data: {"conversation_id":"caller_bound_id"}

```

There can be many deltas. Failure after headers produces `event: error` with sanitized `status`, `detail` and optional `retry_after`, then closes without `done`. Only `done` means success; EOF or partial text does not. Android labels retained partial answers incomplete. The server requires the provider's successful stop and `[DONE]` marker, supports its terminal usage frame, and excludes reasoning/tool traces. Configure any hosting proxy to flush SSE without buffering. Stop/disconnect cancels graph work and closes the outbound response; remote inference cancellation remains best effort.

The server verifies the caller through Supabase `GET /auth/v1/user`; it does not trust locally decoded token claims. IDs are HMAC-signed, caller-bound correlation identifiers, valid for one hour. They do **not** select stored conversations or carry chat history. Unknown, expired, tampered and cross-user IDs return 404. Android clears an unavailable ID, retains the draft and lets the user resend. There is no checkpoint store or database migration in this implementation.

## Workflow and data policy

`verify Supabase identity → read owned Supabase records → calculate → answer the actual question → validate reply`

The two graph nodes live in `src/agentic_subscription_manager/workflow.py`. The account adapter resolves the verified Auth UUID to the canonical bigint profile and uses the caller's JWT plus the publishable key for GET-only reads of subscriptions, the current budget and time-zone preference. Explicit owner filters and Supabase RLS both apply. Tokens and identities never enter graph state. Calculations in `evidence.py` use active subscriptions only, sum annual prices before division, round HALF_UP per currency and never convert currencies. Topic matching selects only a deterministic facts header; Nemotron always receives the actual question, including general questions.

**Chat uses real account records, as explicitly requested for the local demonstration.** The question and relevant account facts are sent to OpenRouter. No tokens, Auth/profile IDs, email addresses or passwords are added to provider messages. Records are untrusted data in user-role messages, not system instructions. An empty account produces zero spending and no saved budget; there is no sample fallback. Account-read failures stop the request before inference instead of masquerading as empty data. Gmail subscription discovery imports eligible records into Supabase so the existing Android account screens and chat account reads can use them. Saved chat history is display-only, so follow-up questions must include their context.

Using [Nemotron 3.5 Lightning free](https://openrouter.ai/nvidia/nemotron-3.5-lightning:free) does not establish a private-data guarantee. The local demo sends the requested account facts under the configured OpenRouter/provider policies. Review those policies and account data controls before distributing the application; this change does not claim zero retention or training exclusion. [Provider data guidance](https://openrouter.ai/docs/guides/privacy/logging). See [ADR 0005](../docs/adr/0005-real-account-ai-chat.md) and [ADR 0007](../docs/adr/0007-chat-model-availability.md) for the account-data flow and model selection.

The provider request uses the fixed `:free` model, 400 maximum output tokens, disabled reasoning, `tool_choice: none` and disabled provider fallback. Reasoning shares the output budget and otherwise can consume all 400 tokens without a visible answer. There is no tools list or action executor. It does not retry failed calls or substitute a paid model. Empty, wrong-type, overlong, malformed, tool-call and incomplete responses fail; model prose remains generated content, not a guarantee of factual correctness.

## Limits and failures

- At most four in-flight chat operations, five provider calls per user/minute, twenty globally/minute and fifty globally/UTC day.
- Counters are bounded and **process-local**, reset on restart and require one worker/one instance. They are development admission limits, not a durable quota or shared account guarantee. Before scaling, use shared admission state. The provider's own limits still apply, including calls from other applications using the same provider account.
- Eight-second Auth timeout, twenty-five-second provider I/O timeout, thirty-eight-second overall operation deadline; upstream JSON/SSE is limited to 64 KiB and generated prose to 4,000 characters. Android bounds response bytes to 128 KiB and rendered text to 16,000 characters, with a 45-second read timeout.
- Gmail status/connect deadlines are 20 seconds; bounded subscription sync allows 75 seconds and Android uses a 90-second read timeout. Composio calls have SDK-level timeouts and no automatic retries; Gmail operation admission is limited to four concurrent calls globally and two per account. Integration responses are private and uncacheable.
- Account reads have an eight-second timeout and a 256 KiB response limit per page. Subscription pages contain at most 100 rows, with an overall limit of 1,000; exceeding that limit fails explicitly. Totals include every loaded row. Provider service, renewal and low-usage lists contain at most 50 items with counts/truncation flags; the deterministic answer header shows at most 20 names. Budget periods use the saved IANA time zone, defaulting to Africa/Johannesburg. The small `tzdata` dependency supplies IANA rules on Windows.
- 401: sign in again; 404: unavailable conversation; 413/422: invalid input; 429: busy/limited; 502/503/504: upstream/configuration/timeout failure. Upstream details and credentials are never returned. Retry-After is bounded and respected; the daily local cap waits for the next UTC day.
- Client disconnect cancels local graph and outbound HTTP work. A remote provider may already have accepted the request; cancellation there remains best effort. Reserved attempts are not refunded.
- Logs contain request ID, operation, status and elapsed milliseconds, never prompts, tokens, profiles, provider bodies or email contents.

## Legacy prototype

The original top-level `*_Router.py`, `Models.py`, `ASM_database.py` and `create_tables.py` remain for historical review. They are outside the active API and their former bcrypt/SQLAlchemy/psycopg dependencies are no longer installed by the AI package. Do not run those routers or `create_tables.py` against the canonical database: they implement a different, incomplete credential/schema contract. Canonical SQL migrations under `supabase/` remain authoritative.

Read [ADR 0002](../docs/adr/0002-python-langgraph-ai-boundary.md), [ADR 0003](../docs/adr/0003-streaming-local-android-ai.md), [ADR 0005](../docs/adr/0005-real-account-ai-chat.md) and the [streaming verification record](../docs/ai-streaming-verification-2026-10-12.md). Durable model memory, recommendation persistence, scheduling and notification delivery remain separate work.
