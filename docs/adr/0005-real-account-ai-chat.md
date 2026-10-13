# ADR 0005: Answer actual questions using owned account data

Date: 12 October 2026. Status: implemented on the feature branch.

## Context

Chat previously discarded the question after a keyword lookup and sent predefined tasks over fictional fixtures. General questions returned a canned limitation. The user explicitly requested real subscriptions and budgets, actual model responses and read-only access for the local demonstration.

## Decision

Supersede the fictional-only and question-redaction portions of ADRs 0002 and 0003. Verify each bearer token through Supabase Auth, resolve its UUID to the canonical application profile, then issue GET-only Data API requests with that same bearer token and the existing publishable key. Apply explicit ownership filters and validate returned ownership as well as content. Supabase RLS continues to enforce row access. Missing profiles, malformed data and failed reads stop inference; an empty account never falls back to fixtures. No schema change or privileged key is needed.

Calculate per-currency totals, annual equivalents, reported low usage, renewals and the current budget deterministically. Use the saved IANA time zone for the date and budget period; `tzdata` provides these rules on Windows. Page reads to 1,000 subscriptions and fail explicitly above that bound. Limit provider lists to 50 items with total counts and truncation flags; aggregate totals cover every loaded record. Limit deterministic answer headers to 20 names.

Send the actual question and relevant account facts to the configured Nemotron 3 Super free endpoint. Keep tokens, Auth/profile IDs, emails and passwords out of provider messages. Account text belongs in user-role data messages, never system instructions. The composer states that questions and account facts go to OpenRouter. This user-authorized local data flow does not establish provider zero-retention or training exclusion; distribution requires a separate review of provider terms and account controls.

Keep the fixed model, no paid/provider fallback, no model tools or action executor, existing streaming/cancellation, safe metadata logs and bounded output. A local topic selector supplies only an optional facts header; it never replaces the question or returns a canned answer. The API now requires `data_mode: account`; old synthetic clients fail validation and must be rebuilt.

## Alternatives considered

- Keep synthetic topic explanations: violates the requested behavior and masks the absence of account integration.
- Trust client-supplied records or owner IDs: would let callers fabricate account evidence or select another account.
- Use a service-role key or legacy SQLAlchemy routes: unnecessarily bypasses the existing authenticated Data API boundary.
- Model-selected database tools: adds orchestration and authorization complexity without a current need; deterministic bounded reads are sufficient for these questions.

## Consequences

Answers can reflect real saved records, and general prompts reach Nemotron unchanged. Questions and account facts leave the application for OpenRouter. Model prose can still be wrong; the separate deterministic figures remain authoritative. Saved chat history restores the display but is not model context. Gmail/Composio, source links, model memory and account actions remain outside this change.
