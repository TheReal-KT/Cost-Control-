# Automatic service logos — architecture comparison

Research and implementation check: 12 October 2026. Exa searches returned 20 candidate results
across Android image loading, logo identity APIs and provider operational constraints. The
recommendation below uses primary documentation, not vendor coverage/performance claims.

## What the application does now

`ServiceBrand.iconFor(name)` trims, normalizes case/whitespace and matches explicit aliases to
bundled Android VectorDrawables. Home, Services and service details call the same resolver. Saving
"Netflix" or "ChatGPT Plus" therefore automatically displays the right bundled mark on the next
render, without a network lookup or a logo upload. This is automatic recognition, not an external
account connection or internet-wide brand discovery.

Coverage: Netflix, OpenAI/ChatGPT, Spotify, YouTube, Apple, Amazon Prime, Adobe, Notion, Dropbox,
Discord, GitHub and Canva, plus NVIDIA for Nemotron. Unknown names use a Unicode initial. This is
a curated catalogue; it does not cover every popular or regional provider. Explicit aliases avoid
misidentifying names such as "Netflix account consulting". Sub-brands sometimes use their parent
mark (ChatGPT uses OpenAI, Apple Music uses Apple), not a separate product logo.

## Two architectures

| Criterion | Bundled vectors + local aliases (implemented) | Remote logo API + cache |
| --- | --- | --- |
| Initial display | Local resource, no image request | First uncached logo needs network/decoding |
| Offline | Available immediately | Only previously cached logos; otherwise fallback |
| Coverage | Curated catalogue | Broader catalogue, still not guaranteed |
| Identity | Exact known names/aliases | Canonical brand ID/domain; free text needs disambiguation |
| Freshness | App release updates assets/aliases | Provider refresh plus cache expiry/versioning |
| Costs | Small APK asset footprint, no logo API requests | Requests, cache/storage, vendor quotas/terms |
| Privacy | No service names/domains sent to logo provider | Direct requests reveal requested brands and client network metadata |
| Maintenance | Curate common marks and aliases | Operate identity lookup, caching, timeouts and failures |

Android documents vectors' scalability and reduced density-specific asset maintenance in its
[VectorDrawable overview](https://developer.android.com/develop/ui/views/graphics/vector-drawable-resources).
For remote images, Android recommends an established image loader such as Glide rather than
manual bitmap/network handling in a Java application; see
[efficient bitmap loading](https://developer.android.com/topic/performance/graphics/load-bitmap).
The table is an engineering comparison, not a measured speed benchmark of this application.

Logo.dev's [brand search API](https://www.logo.dev/docs/api-reference/search/search-brands) returns
candidate names/domains, illustrating why a typed service name is not itself a stable identity.
Its [key documentation](https://www.logo.dev/docs/platform/api-keys) distinguishes public CDN
tokens from server-only API secrets. Its [caching documentation](https://www.logo.dev/docs/platform/caching)
describes browser caching; that does not automatically implement Android disk caching.

Provider terms affect architecture. Brandfetch's
[Logo API guidelines](https://docs.brandfetch.com/logo-api/overview) require direct embedding and
referer headers, restrict programmatic access and direct users needing caching to a custom setup.
Do not assume its free web integration permits native image-loader caching or server mirroring.
Logo.dev documents an explicit server API/etag flow for
[self-hosted copies](https://www.logo.dev/docs/platform/self-hosting); it forbids building those
copies by downloading from its CDN. These policies must be checked before choosing a provider.

## Recommendation

Keep the local catalogue for this prototype: it is simpler and has the lowest cold-load network
cost for the supported services. No remote-image dependency, schema migration or logo-provider
credentials are needed now. This recommendation is an inference from the app's size and the
documented constraints, not a claim that every engineer chooses the same design.

If users need many unrecognized providers, evolve to a hybrid: known local mark → approved remote
mark → initial. Store a stable service/provider ID and verified domain independently of the user's
display name. Resolve ambiguous brands when adding the service, not on every list render. Keep
private search credentials on the backend, send only provider identity (never account or payment
data), and restrict remote image URLs to the approved delivery host. Use bounded image sizes,
Glide memory/disk caching under permitted terms, timeouts, deduplicated requests and a fallback
that always renders. Honour updates with cache expiry/versioning. Avoid arbitrary website scraping.

## Reviewing mock data

Debug builds provide a separate **Cost Control Preview** launcher with 13 fictional subscriptions:
12 recognized brands and one unknown provider. It reuses the actual Home/Services/detail views,
financial calculator and search/filtering. All prices are illustrative. Paused/cancelled services
are excluded from active monthly totals by the existing calculator. No repository, session,
database or AI API is used. Activity and fixtures live under `app/src/debug` and are excluded from
release builds. Editing controls explain that the preview is read-only.
