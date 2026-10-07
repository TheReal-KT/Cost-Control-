# Python AI boundary verification — 7 October 2026

## Change

The active backend now exposes only `/health` and `/ai/chat` (plus generated API documentation). Legacy custom password/CRUD routers are not imported or mounted. Supabase remains Auth/data authority; Android ordinary CRUD is unchanged. Original prototype files are preserved for review, with their unused database/password dependencies removed from the active package.

The Python package adds three real LangGraph nodes: load fictional records, calculate decimal/currency-separated evidence and request an explanation. The server verifies Supabase identity, binds expiring correlation IDs to the verified caller, limits work/body/output and blocks real data/model overrides. Raw questions and identity never enter graph state or provider requests. Unsupported questions return an explicit limitation without model execution. Android resets unavailable conversation IDs while retaining drafts.

## Checks executed

From `backend/`, using the project environment installed by `uv sync --locked` with CPython 3.12.8:

```powershell
.venv\Scripts\python.exe -B -m unittest discover -s tests -v
.venv\Scripts\ruff.exe check src tests main.py
.venv\Scripts\ruff.exe format --check src tests main.py
uv lock --check --python C:\Users\khulu\AppData\Local\Programs\Python\Python312\python.exe --no-python-downloads
```

The backend suite passed 30 tests with no failures/errors/skips. It uses the actual FastAPI routes, Pydantic validation and installed LangGraph graph, with synthetic `httpx.MockTransport` Auth/provider responses; it does not contact live accounts or generate real inference. Covered:

- Successful response matching Android's JSON/length/ID contract and currency-separated authoritative figures.
- Missing/expired tokens, invalid upstream identity and Auth redirect/outage denial.
- Cross-user, expired and tampered conversation rejection before provider execution.
- Server-only fictional evidence; raw sensitive-marker questions, identity and tokens absent from provider payloads.
- Rejection of real-data mode, paid model selection and caller-supplied owner fields.
- Body/answer byte limits, chunked input, generic validation responses, malformed/partial/tool-call/wrong-type output.
- Provider failures/timeouts without retries, bounded Retry-After, per-user/global/daily admission limits and cancellation cleanup.
- Graph cancellation on client disconnect and logs containing safe correlation/status/timing metadata.
- Active-only annual-to-monthly calculation, rounding once per currency consistent with Java and no currency conversion.
- Legacy routes absent, missing configuration fail-closed, unsafe config/privileged key rejection.

Ruff lint and formatting checks passed; the lockfile check passed. `uv.lock` pins the resolved 46-package environment, including LangGraph 1.2.14, FastAPI 0.141.1, HTTPX 0.28.1 and Pydantic 2.13.5. Initial lint identified formatting/line-length issues and disconnect polling; these were corrected, with disconnect monitoring now awaiting ASGI events directly.

An additional read-only live negative probe ran the actual `/ai/chat` API against the project's Supabase `/auth/v1/user`, using its existing public publishable key and an intentionally invalid token. It returned 401 `Sign in again`; exactly one Auth GET occurred and zero provider calls. No account creation, table query/write, schema change or inference occurred. The temporary probe is outside the repository and contains no secrets.

Android command:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
```

Passed: debug APK, 16 JVM tests and lint with zero errors and 76 warnings. No device was connected, so instrumentation was not rerun in this change; the earlier 25-test device result is historical evidence, not verification of the new expiration feedback.

## Limits of this evidence

No Python service was deployed and Android's `AI_ENDPOINT_URL` remains unset. No live successful account login, real provider generation, mobile-to-host chat journey or production latency benchmark was performed. Missing provider credentials/hosting configuration remain deployment work. The API is implemented and contract-tested; it is not an operating hosted chat feature yet.

The prototype uses predefined topics and fictional data, without durable conversational context, source links, record reads/writes or stored recommendations. It does not resolve the Auth UUID to the bigint profile because it reads no application data. That ownership mapping must be implemented before real-account tools are introduced with an appropriate model/data policy.

Admission counters are single-process, restartable limits, not durable/shared quotas. Run one worker/instance until shared admission is implemented. Client cancellation stops local work best-effort; it cannot guarantee cancellation of a provider request already accepted remotely. Model output validation checks structure/bounds/completion, not complete semantic correctness.

## Architecture records

- [ADR 0002](adr/0002-python-langgraph-ai-boundary.md)
- [Service configuration](../backend/README.md)
- [Project related architecture note](https://app.notion.com/p/3f299988e94781dfacf0ddca2c3741c7)
- Review branch: `feat/mvp-catchup`; [PR #4](https://github.com/TheReal-KT/Cost-Control-/pull/4).
