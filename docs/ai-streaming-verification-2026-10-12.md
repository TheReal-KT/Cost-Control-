# Android/backend streaming verification — 12 October 2026

Branch: `nemotron-super-streaming`.

## Implemented

- Fixed backend/client model: `nvidia/nemotron-3-super-120b-a12b:free`.
- Application SSE through LangGraph, native incremental text, icon Send/Stop, explicit completion and incomplete partial answers.
- Shared Supabase identity/conversation checks for JSON and streaming requests. No model tools or account-action executor; tool calls are rejected.
- Local emulator HTTPS using ADB reverse forwarding, generated debug-only loopback certificate and a server-only private key.
- Repeatable PowerShell initialization and documented startup. No public deployment, Gmail connection or database migration.

## Checks executed

- Backend: `uv run --locked python -B -m unittest discover -s tests` — 42 tests passed. Includes real graph/mock-provider streaming, malformed/truncated/oversized responses, tools rejection, provider 429, cross-user IDs, terminal usage events and HTTP disconnect closing the provider response/releasing admission.
- Backend: Ruff lint and full formatting check passed; locked dependency resolution preserved existing versions. AnyIO, already installed through FastAPI, is now explicit for cancellation cleanup.
- Android: debug and release assembly, debug JVM tests and debug lint passed. JVM suite: 16 tests.
- Android instrumentation on API 36 emulator using a temporary isolated `.streamverify` application ID: 35 tests discovered, 34 passed, one assumption skip. The skip is the existing absent-backend test because this build has an endpoint. The runner's XML serializes that assumption as a failure element; Gradle reports a successful run. Six new parser tests passed, including fragmented Unicode, explicit completion, partial failure, invalid event order and size limits.
- Opt-in native connection test passed against the actual local HTTPS server: an invalid Supabase session returned 401 before any answer/inference. This verifies device forwarding, certificate trust, HTTP transport and server authentication rejection.
- APK content checks: debug contains the public development certificate; release excludes it. Neither checked APK contains the configured OpenRouter credential or PEM private key.
- Local initialization executed twice successfully without replacing the still-valid certificate.

## Live acceptance

The real three-node graph completed a Nemotron 3 Super free stream in 2.08 seconds, with 30 application deltas and 327 answer characters. This exposed OpenRouter's extra terminal usage frame; the adapter now accepts its empty repeated stop choice and has regression tests that reject extra answer text after stop. This timing is one connection check, not a performance/reliability guarantee.

The normal signed-in Android app was preserved while instrumentation used the separate app ID. A question, “Explain my monthly spend”, then completed through:

`Android → loopback HTTPS/ADB → FastAPI → real Supabase identity verification → LangGraph → OpenRouter → streamed native answer`

The server logged HTTP 200 and 2,375 ms. The composer cleared after completion and returned to the Send icon. A later unsupported model-identity question returned the existing local fictional-topic limitation, without a model call. This is a restricted subscription example assistant, not general chat.

Only the built-in fictional records were sent to the model. Provider keys, bearer tokens, identities and raw user questions were not logged or included in graph evidence. The generated prose repeated amounts and used “or” between separate currencies despite its instructions. Deterministic facts were correctly separated; protocol success does not establish prose accuracy or financial suitability.

## Remaining boundaries

The local connectivity portion of demonstration task 3 is verified. A private-data policy decision remains necessary before real subscription/email analysis; switching to Super free does not settle that requirement. Gmail/Composio connection was explicitly excluded from this change. Durable history, source-record links and account actions remain unimplemented.

The backend must keep running and ADB forwarding must be restored after device/ADB restart. Rebuild after certificate renewal or endpoint changes. Release distribution needs a normally trusted HTTPS endpoint. Dedicated TalkBack/large-text acceptance and evaluation of generated explanations remain follow-up work.
