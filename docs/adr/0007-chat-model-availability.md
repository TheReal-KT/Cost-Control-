# ADR 0007: Use the available Nemotron endpoint for chat

Date: 23 October 2026. Status: implemented.

## Context

Chat used `nvidia/nemotron-3-super-120b-a12b:free`. The Android app was correctly sending the configured model through the authenticated backend, but a live request returned HTTP 404 from NVIDIA. OpenRouter's current model page reports 0% availability for that free endpoint. A minimal account-free request using the application's request options succeeded with `nvidia/nemotron-3.5-lightning:free`; its current OpenRouter page reports 99.61% availability over three days.

## Decision

Update the single supported model ID in the Android client and backend contract to `nvidia/nemotron-3.5-lightning:free`. Keep the model fixed on the backend, preserve provider fallback being disabled, and continue to prohibit tool calls. Update the visible model label and current setup documentation. The model probe used no account records.

## Alternatives considered

- Keep the previous endpoint: it returned provider 404 and currently has no reported availability.
- Permit provider fallbacks: this could send account facts to additional providers, so it is not enabled by this model change.
- Select a paid model: this could charge the user's OpenRouter account and needs an explicit cost decision.

## Consequences

Chat now uses a free Nemotron endpoint that responded to the same request shape as the app. Free endpoints can still become rate limited or unavailable. The account-data flow and provider privacy risks described in ADR 0005 remain unchanged; this change does not promise zero retention or training exclusion.
