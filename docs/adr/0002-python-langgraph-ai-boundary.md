# ADR 0002: Use Python LangGraph behind a focused AI API

Date: 7 October 2026. Status: accepted by the project owner.

## Context

Android already uses Supabase Auth and protected REST tables under ADR 0001. The merged backend prototype adds custom login and general CRUD through FastAPI/SQLAlchemy, duplicating these responsibilities without matching current identity/schema contracts. The AI tab needs a trusted model endpoint, and the owner selected Python for LangGraph.

The fixed initial model is `nvidia/nemotron-3.5-lightning:free`. Its published hosting guidance excludes confidential/personal data. A client `data_mode` flag alone cannot guarantee that arbitrary free text or financial records are safe to send.

## Decision

Keep Java/XML presentation and direct authenticated Supabase CRUD. Repurpose the active Python entry point for `/health` and authenticated `/ai/chat`; leave the legacy files inactive for review. Use Python LangGraph nodes for loading fictional evidence, deterministic decimal calculations and model explanation. External I/O lives in explicit Auth/provider adapters. No new credential store, general CRUD API, SQLAlchemy connection or schema setup runs in the AI service.

Verify each caller against Supabase Auth using the existing public publishable key. The synthetic implementation needs only the verified Auth UUID, not a database profile lookup. Bind signed, expiring correlation IDs to that UUID; IDs do not confer access or implement conversation memory. Before real-account reads are added, resolve the verified UUID to the canonical bigint app profile and enforce record ownership.

Enforce synthetic fixtures server-side. Map raw questions locally to a small set of predefined analysis tasks; pass only task and fixture-derived evidence to the model. Unsupported questions return a limitation without provider execution. Use fixed model selection, no paid/provider fallback, bounded async requests/output, no automatic retries, process-local admission limits and cancellation. Keep credentials and caller text out of graph state, model prompts and logs.

## Alternatives considered

- Route all Android operations through the Python prototype: duplicates working Auth/CRUD and adds another server dependency to ordinary screens.
- TypeScript LangGraph: valid alternative, but Python reuses the existing server foundation and matches the owner's choice.
- LangGraph directly on Android: violates the trusted credential boundary and couples the device to server orchestration.
- Forward arbitrary questions with anonymized account data to the free model: redaction alone cannot establish compliance with its data restrictions.
- Add checkpoints, streaming or an autonomous tool loop immediately: exceeds the first read-only JSON chat requirement.

## Consequences / trade-offs

Normal screens retain their existing data path. Chat adds Auth/provider network round trips and a server hosting responsibility; deploy near Supabase and measure latency. Python and Java share behavioral contracts rather than source code, especially decimal money and currency separation.

The first AI mode is limited to fictional examples and predefined topics, with no stored context or account writes. Single-instance counters are restartable admission limits, not durable quotas. A real-data endpoint, owned read adapters, retention/storage and shared admission are separate decisions when those requirements arise. The checked-in API implementation does not establish deployment, live provider acceptance or end-to-end Android chat.

## References

- [ADR 0001](0001-supabase-auth-and-mvp-data.md)
- [Python service instructions](../../backend/README.md)
- [LangGraph Graph API](https://docs.langchain.com/oss/python/langgraph/graph-api)
- [Supabase token verification](https://supabase.com/docs/guides/auth/jwts)
- [Free-model hosting guidance](https://openrouter.ai/blog/insights/nemotron-3-5-lightning/)
- [Project architecture note](https://app.notion.com/p/3f299988e94781dfacf0ddca2c3741c7)
