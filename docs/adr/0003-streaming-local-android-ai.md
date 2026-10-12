# ADR 0003: Stream read-only AI to Android over local HTTPS

Date: 12 October 2026. Status: implemented on the feature branch.

## Context

The local demonstration needs a working Android-to-backend connection, Nemotron 3 Super free, incremental answers and Stop. The existing Supabase identity boundary and fictional-only workflow must remain. Gmail/Composio connections are explicitly outside this change.

## Decision

Use `nvidia/nemotron-3-super-120b-a12b:free` through the existing backend adapter, with no provider fallback, no tools list and `tool_choice: none`. Keep deterministic fixture calculations and reject tool calls. This model switch does not approve private financial/email data for inference.

Add opt-in SSE to the existing `/ai/chat` route; keep JSON compatibility. Supabase authentication and conversation ownership precede streaming. LangGraph custom deltas become application-owned `start`, `delta`, `done` and `error` events. The native client never consumes raw model reasoning or LangGraph internals. Completion requires an explicit successful provider finish and done marker. Partial/cancelled output remains visibly incomplete.

A bounded producer queue separates LangGraph cleanup from Starlette's cancellation scope. On disconnect, cancel and join the producer under a shield so the outbound response closes before the in-flight slot is released. AnyIO was already installed through FastAPI; it is now an explicit dependency for that cleanup boundary.

For local Android development, use ADB reverse forwarding with loopback HTTPS. Generate a short-lived self-signed localhost certificate in ignored `.local-demo`. Include only its public certificate in debug resources, scoped to loopback. Release trust remains the system trust store. Model keys and the TLS private key remain off-device and uncommitted.

## Alternatives considered

- Plain HTTP: would weaken the existing HTTPS contract and expose bearer-token traffic if accidentally reused beyond loopback.
- Public tunnel/host: unnecessary for a local presentation. Cloudflare Quick Tunnels also do not support SSE; a supported hosted option remains possible later.
- Composio and private-account reads now: outside the agreed model/streaming scope and requires separate consent, ownership, tools and provider-data decisions.

## Consequences

The computer/backend must stay running, and ADB forwarding must be restored after device/ADB restarts. Rebuild Android after endpoint or certificate changes. Certificates renew near expiry through initialization. A release or distributed demo needs a normally trusted HTTPS host. Current chat explains fictional evidence; it does not inspect actual subscriptions or Gmail.

References: [Android TLS configuration](https://developer.android.com/privacy-and-security/security-config), [LangGraph streaming](https://docs.langchain.com/oss/python/langgraph/streaming), [OpenRouter streaming](https://openrouter.ai/docs/api/reference/streaming), [provider data handling](https://openrouter.ai/docs/guides/privacy/logging), [Quick Tunnel limitations](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/do-more-with-tunnels/trycloudflare/).
