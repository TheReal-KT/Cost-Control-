# AI tab and LangGraph integration proposal

Date: 6 October 2026; implementation update 12 October. The native composer streams Nemotron 3 Super free answers through the Python `/ai/chat` endpoint and three LangGraph nodes. Supabase identity, fictional fixtures, caller-bound correlation IDs, strict output bounds and cancellation remain enforced. Android connects locally over loopback HTTPS and ADB forwarding; no public deployment is required. Native history now saves directly to account-scoped Supabase tables; it is not model memory. Real-account reads, Gmail connections and source links remain proposed. See [ADR 0003](../adr/0003-streaming-local-android-ai.md), [history ADR](../adr/0004-native-chat-history.md), [backend instructions](../../backend/README.md) and [streaming verification](../ai-streaming-verification-2026-10-12.md). The sections below describe the broader intended integration; the current prototype is narrower.

## Confirmed product decisions

The five root tabs become **Home, Services, Reminders, AI, Profile**. AI replaces the current Insights root and contains **Chat** (default) and **Insights**. Switching these subviews preserves their separate scroll/filter/conversation state. Existing recommendations remain accessible through Insights; they do not become conversational messages or lose their stored evidence.

The configured model is **Nemotron 3 Super (free)**. The compact NVIDIA model selector lives inside the composer and offers this single supported option. Future model choices should appear only after their endpoints and data handling have been integrated and verified. Keep headers free of back arrows; Android Back and gestures handle navigation.

The initial Chat design uses fictional subscription data. Suggested prompts: “What renews next?”, “Explain my monthly spend”, and “Which services should I review?” Show concise answers, the currency and calculation period, and tappable source records. A sample answer must be labelled as a demo; it is not evidence that the model or data integration already runs.

## Endpoint verified through Exa

OpenRouter lists `nvidia/nemotron-3-super-120b-a12b:free`. The backend fixes this model, disables fallback, sets `tool_choice: none` and exposes no model tools or action executor. Validate any generated structured values before later use or persistence. A paid model must not be substituted silently. [Model catalog](https://openrouter.ai/nvidia/nemotron-3-super-120b-a12b:free)

Changing models does not establish private-data suitability. OpenRouter distinguishes provider policies and free-model data settings, and this endpoint's catalog response did not establish a private-data guarantee. The prototype therefore retains synthetic evidence only. Connecting real financial or email records requires a suitable endpoint and review of its data terms first; removing names alone is insufficient. [OpenRouter data guidance](https://openrouter.ai/docs/guides/privacy/logging)

The published free-variant limits are 20 requests/minute and 50 requests/day when lifetime purchased credits are below 10; the published higher tier is 1,000/day after at least 10 purchased credits. Treat these as account/provider limits, not a per-user allowance, and check actual account capacity before deployment. Free availability can change. Handle HTTP 429 with bounded retry, honour `Retry-After`, and show a recoverable limit message. Never enable paid fallback without an explicit product decision. [Official rate-limit contract](https://openrouter.ai/docs/api_reference/limits), [free variant](https://openrouter.ai/docs/guides/routing/model-variants/free)

## Smallest backend boundary

```mermaid
flowchart LR
  A[Android AI / Chat] --> B[Authenticated AI endpoint]
  B --> C[LangGraph workflow]
  C --> D[User-scoped read tools]
  D --> E[Existing application records]
  C --> F[Configured model endpoint]
  C --> G[Validated answer and source references]
  G --> A
```

LangGraph coordinates the steps of an AI workflow; Nemotron generates language within that workflow. Neither owns the Android navigation. Keep LangGraph in the trusted backend already planned for optional AI. A small Python endpoint using OSS LangGraph is a practical proposal; the final hosting/runtime choice should be made with the actual backend implementation. Do not create an additional general CRUD service or move current Android CRUD behind LangGraph just to introduce Chat.

The endpoint validates the signed-in access token, resolves the application profile, and scopes every record lookup to that profile. The Auth UUID and application bigint user ID are distinct. Model-supplied IDs, conversation IDs, source IDs and confirmation requests are untrusted inputs. A conversation identifier selects history; it does not grant access. Enforce ownership server-side even when a privileged database client bypasses row policies. Provider credentials stay on the server. These boundaries preserve the existing [database contract](../database.md) and [ADR 0001](../adr/0001-supabase-auth-and-mvp-data.md).

Provide small read tools for subscription summaries, upcoming reminders and budget comparisons. Tools use ordinary deterministic domain calculations, including decimal money and separate currency totals. The model explains their results; it must not invent conversions, historical trends, usage measurements or savings certainty. Return source references from the actual tool results and validate them before displaying record links.

MVP scope is **read-only Q&A**. When an answer suggests changing a record, link to its existing edit/review screen. This is quicker and preserves the current mutation contracts. Conversation-driven writes are a later feature, requiring the confirmation flow below.

## Proposed conversation contract

Keep implementation-specific LangGraph events behind an application-owned response contract. Each message/run has a stable identifier; every response belongs to an authorized conversation. Expose answer deltas, a short user-facing status, validated source references, completion and recoverable failure. Deduplicate replayed events after reconnection. Do not expose raw internal reasoning or tool traces as chat text. LangGraph supports streaming graph updates and model output, but the Android client should not depend on every internal event shape. [Streaming documentation](https://docs.langchain.com/oss/python/langgraph/streaming)

Show sending, streaming, completed, cancelled and failed states. While a run is active, offer Stop and avoid duplicate submits. Preserve an unsent composer draft when switching tabs or rotating. A failed/limited run retains the question and offers a deliberate retry. If a partial answer is retained after failure, label it incomplete. Keyboard insets and large text must leave the composer and last answer reachable.

Native display history uses `chat_conversations`, paired `chat_turns` and an atomic authenticated save RPC. The Chat-only drawer reads bounded pages and supports deleting a conversation. New Chat restores the compact Chat/Insights controls; sending the first message hides them to focus on the conversation. User bubbles align right, followed by the assistant answer on the left. Save failures are separate from inference failures. Saved text and durable UUIDs are not forwarded to the model. See [ADR 0004](../adr/0004-native-chat-history.md).

LangGraph checkpointing remains a separate future decision for resumable workflows or conversational model memory. It stores workflow state by thread and requires a durable checkpointer for persistent use. Do not store access tokens or provider keys in graph state. [Memory and persistence](https://docs.langchain.com/oss/python/langgraph/add-memory)

Own-server authentication must be implemented at its API boundary. LangSmith deployment examples using custom `Auth` handlers are deployment-specific; they do not automatically secure an arbitrary OSS LangGraph server. [LangSmith custom authentication](https://docs.langchain.com/langsmith/custom-auth)

## Optional confirmed writes after the read-only MVP

1. Generate a validated proposal identifying the exact record, expected version and before/after values. Re-read the record before presenting the change.
2. Pause and present a confirmation sheet. The user can confirm or dismiss; an unrelated later message is not consent.
3. On confirmation, re-check identity, ownership, current values and proposal expiry. Apply an idempotent domain mutation; duplicate confirmations must not duplicate reminders or decisions.
4. Return the stored result, or explain a conflict/failure. Keep the original draft when a write fails.

LangGraph `interrupt()` can pause for confirmation and resume with `Command`; it requires checkpointing and a thread identifier. Nodes may restart on resume, so side effects must not run before consent or be repeated unsafely. An interrupt is workflow control, not authorization. [Interrupt and resume contract](https://docs.langchain.com/oss/python/langgraph/interrupts)

Approving an insight records an approved/rejected/ignored decision under the existing schema. It does not cancel a provider subscription, move money or perform a bank operation. Preserve that wording in both Chat and Insights.

## Verification required when implemented

Backend tests now cover streaming, cross-user correlation IDs, strict request/output contracts, provider failures and disconnect cleanup. Native tests cover the SSE parser, draft/auth boundaries and an opt-in live TLS/Auth rejection check. The [current verification record](../ai-streaming-verification-2026-10-12.md) distinguishes those checks from the live emulator flow. Future private-data tools need source ownership tests, and future writes need replay/conflict/authorization checks. Large text and TalkBack still require dedicated acceptance checks.
