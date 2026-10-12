# Live AI connection verification — 12 October 2026

The developer saved the OpenRouter key in the Git-ignored `backend/.env`. No provider key was
added to Android or committed. The local file also contains the existing public Supabase
configuration and a generated server-only conversation signing secret.

## Observed results

- The actual FastAPI app's `/health` route returned 200, `chat_configured: true`, synthetic mode.
- An unauthenticated `/ai/chat` request returned 401 before provider execution.
- The original real Nemotron request returned HTTP 200 but `finish_reason: length`, empty content,
  and 400 reasoning tokens out of a 400-token completion budget. The application rejected it.
- The request now explicitly disables reasoning while retaining the fixed free model,
  400-token cap, no provider fallback, no retries and strict completed-answer validation.
- The actual three-node LangGraph workflow then returned a completed answer in 1.7 seconds.
  OpenRouter reported cost 0 for that call. Only the existing fictional subscription fixture
  was sent. This is a connection and protocol check, not a throughput or reliability benchmark.
- The backend regression suite passes 31 tests, including the request settings and rejection
  of an empty, truncated reasoning-only answer. Ruff lint and formatting of the changed
  adapter/test files pass. Final merge verification also corrected the spacing before the existing
  `api.py` comment so the complete backend formatting check passes.

## Remaining acceptance

The generated paragraph repeated figures and misattributed a currency amount despite the
instruction to leave figures to the deterministic facts. The facts preceding that paragraph
were calculated correctly. Generated prose still needs accuracy controls/evaluation before
it should be trusted for financial guidance; this test does not establish semantic correctness.

Android-to-server chat was not exercised with a live signed-in session. `AI_ENDPOINT_URL` still
needs a reachable HTTPS URL ending in `/ai/chat` and an Android rebuild. The live model test
invoked the real backend graph directly; it did not bypass or weaken the API's authentication.

The backend remains synthetic-only. Real account AI analysis and account actions remain outside
the implemented prototype.

OpenRouter documents the shared reasoning/output budget in its
[reasoning guide](https://openrouter.ai/docs/guides/best-practices/reasoning-tokens).
