# ADR 0004: Save native chat history through Supabase

Date: 12 October 2026. Status: implemented on the feature branch.

## Context

The Android chat needs a history drawer and New Chat, with messages surviving app restarts. The AI endpoint's signed correlation IDs expire and hold no messages. The model remains restricted to fictional evidence and exposes no write tools.

## Decision

Android owns conversation presentation and calls a narrow `ChatHistoryRepository` implemented by the existing authenticated Supabase repository. A conversation has a client-generated UUID, title and timestamps; each turn pairs the user question with a complete or incomplete assistant answer. Paging bounds history reads. New Chat creates a separate conversation and restores the compact Chat/Insights controls.

Use account-owned `chat_conversations` and `chat_turns`, keyed with the canonical bigint profile owner. Row-level security checks the verified Supabase Auth identity. A security-invoker RPC atomically saves the parent and turn using stable UUIDs, making deliberate save retries idempotent. It cannot downgrade a completed answer to an incomplete partial response. Deleting a conversation cascades to its turns.

Durable conversation IDs stay separate from the backend's temporary correlation IDs. Loading a conversation restores its display; it does not send previous messages to the model or create model memory. Provider credentials and access tokens are never stored in chat records.

## Alternatives considered

- Local-only history: simpler storage, but it loses cross-device persistence and requires a separate account isolation and deletion mechanism.
- LangGraph checkpoints: useful when workflows need resumable state; unnecessary for this stateless, fictional-data explanation workflow.
- A new history HTTP service: duplicates the existing Supabase authentication and data boundary without a current need.

## Consequences

History requires a signed-in account and an applied additive migration. Failed saves remain visible and can be retried; a successful model answer does not imply a successful database save. History text is retained in Supabase until its conversation is deleted. Private-data inference and conversational model memory require separate product and data-handling decisions.
