# Agent Cost Control AI service

Python/FastAPI hosts a small read-only LangGraph workflow. Supabase remains the sole authentication and application-data authority. Android subscription/budget/reminder/profile operations continue through Supabase directly; they do not pass through this service.

## Run and configure

Install [uv](https://docs.astral.sh/uv/) and Python 3.11 or later. From `backend/`:

```powershell
uv sync --locked
uv run --locked python -B -m unittest discover -s tests -v
uv run --locked ruff check src tests main.py
uv run --locked ruff format --check src tests main.py
```

Set the four environment variables listed in `.env.example`, or copy that file to ignored `.env` and fill it locally. `SUPABASE_URL` is the project's HTTPS origin. Use its public **publishable** key, never a database/service-role/secret key. `OPENROUTER_API_KEY` and a random `AI_CONVERSATION_SECRET` of at least 32 characters belong only on the server. Generate the latter with `python -c "import secrets; print(secrets.token_urlsafe(48))"`. Do not reuse a Supabase JWT signing secret.

```powershell
uv run --locked --env-file .env agentic-subscription-manager
```

This starts a local server on `127.0.0.1:8000`. The compatibility command `uv run --locked --env-file .env python main.py` starts the same AI app with development reload. The installed console command and `main.py` import no legacy routers or SQLAlchemy database configuration.

For hosting, run `uv run --locked uvicorn agentic_subscription_manager.api:create_app --factory --host 0.0.0.0 --port 8000 --workers 1 --no-access-log` behind an HTTPS reverse proxy. Configure server environment variables in the host's secret settings. Set body/header/read limits at the proxy; request bodies, headers and URL query strings must not be logged. The provided entry points disable generic access logs and use the service's safe request metadata logger. Locate the service near Supabase to minimize authentication round-trip time. Set Android's ignored `AI_ENDPOINT_URL` to the complete public `https://YOUR_HOST/ai/chat` route and rebuild. Android deliberately refuses plain HTTP, including localhost HTTP.

No service was deployed by this change. `/health` shows whether values are present, not whether the credentials/provider work. With missing values it remains available, while chat fails closed with 503. No model keys belong in Android `local.properties` or its APK.

## Active API

- `GET /health`: readiness/configuration summary, no secrets or data.
- `POST /ai/chat`: the Android request/response contract below.

```json
{
  "prompt": "Explain my monthly spend",
  "model": "nvidia/nemotron-3.5-lightning:free",
  "data_mode": "synthetic"
}
```

Send the user's Supabase access token as `Authorization: Bearer ...`. A successful response contains `answer` and `conversation_id`. Questions are limited to 4,000 characters; extra fields, unsupported models and non-synthetic data modes are rejected. Request bodies are bounded to 32 KiB before JSON parsing. Validation failures do not echo questions.

The server verifies the caller through Supabase `GET /auth/v1/user`; it does not trust locally decoded token claims. IDs are HMAC-signed, caller-bound correlation identifiers, valid for one hour. They do **not** select stored conversations or carry chat history. Unknown, expired, tampered and cross-user IDs return 404. Android clears an unavailable ID, retains the draft and lets the user resend. There is no checkpoint store or database migration in this implementation.

## Workflow and data policy

`verify Supabase identity → select task locally → load_example → calculate → explain → validate reply`

The three graph nodes live in `src/agentic_subscription_manager/workflow.py`. Calculations in `evidence.py` are ordinary Python functions: active subscriptions only, annual prices summed before division, final HALF_UP rounding per currency, no currency conversion. The response renders authoritative figures separately from the model's explanation.

**The free-model prototype is fictional-data only.** The server uses built-in synthetic records, never reads application tables, and does not forward the raw question, tokens, Auth identity or account records to OpenRouter. A local keyword selector chooses a predefined spending, renewal, budget or reported-usage task. Unsupported questions return a short limitation without invoking the model. This initial mode cannot answer questions about the user's actual account or carry conversational context. It is intentionally more limited than arbitrary model chat.

The configured free endpoint asks callers not to submit confidential/personal data. A suitable real-data endpoint and reviewed data policy are required before adding owner-scoped application reads. At that point, resolve Auth UUID to the existing bigint profile ID and enforce the canonical ownership contract; do not reuse caller-supplied IDs or the prototype SQLAlchemy queries. [Provider data guidance](https://openrouter.ai/blog/insights/nemotron-3-5-lightning/).

The provider request uses the fixed `:free` model, 400 maximum output tokens and disabled provider fallback. It does not request unsupported `response_format`, enable tools, retry failed calls, or substitute a paid model. Empty, wrong-type, overlong, malformed, tool-call and incomplete responses fail; model prose remains generated content, not a guarantee of factual correctness.

## Limits and failures

- At most four in-flight chat operations, five provider calls per user/minute, twenty globally/minute and fifty globally/UTC day.
- Counters are bounded and **process-local**, reset on restart and require one worker/one instance. They are development admission limits, not a durable quota or shared account guarantee. Before scaling, use shared admission state. The provider's own limits still apply, including calls from other applications using the same provider account.
- Eight-second Auth timeout, twenty-five-second provider I/O timeout, thirty-eight-second overall operation deadline; upstream JSON is limited to 64 KiB. Android retains its 45-second read timeout.
- 401: sign in again; 404: unavailable conversation; 413/422: invalid input; 429: busy/limited; 502/503/504: upstream/configuration/timeout failure. Upstream details and credentials are never returned. Retry-After is bounded and respected; the daily local cap waits for the next UTC day.
- Client disconnect cancels local graph and outbound HTTP work. A remote provider may already have accepted the request; cancellation there remains best effort. Reserved attempts are not refunded.
- Logs contain request ID, status and elapsed milliseconds, never prompts, tokens, profiles or provider bodies.

## Legacy prototype

The original top-level `*_Router.py`, `Models.py`, `ASM_database.py` and `create_tables.py` remain for historical review. They are outside the active API and their former bcrypt/SQLAlchemy/psycopg dependencies are no longer installed by the AI package. Do not run those routers or `create_tables.py` against the canonical database: they implement a different, incomplete credential/schema contract. Canonical SQL migrations under `supabase/` remain authoritative.

Read [ADR 0002](../docs/adr/0002-python-langgraph-ai-boundary.md) and the [verification record](../docs/ai-backend-verification-2026-10-07.md) for boundaries and the distinction between mocked-upstream tests and live acceptance. Streaming, durable memory, real-account analysis, source links, recommendation persistence, scheduling and notification delivery remain separate work.
