# Animated mobile frontend research

Research date: 6 October 2026, Africa/Johannesburg. Requested provider: Exa. Eight frontend searches returned 40 results across three workstreams: Android UI/migration/navigation; animation and chart libraries; crypto-app product design. All 40 returned search URLs were unique. A Paper integration search returned five results. Five further searches covering LangGraph, the requested Nemotron endpoint and free-tier limits returned 20 result slots, including repeated URLs. Selected primary pages were fetched. These counts describe search results screened, not independently verified claims. Recommendations below are engineering judgments for this repository; no dependencies or production source code were changed.

## What this project actually uses

The Android application currently uses **Java + XML layouts + Material Components**. Its entry point is `app/src/main/java/com/example/agentcostcontrol/MainActivity.java`. Screens are inflated from `app/src/main/res/layout`; `main_navigation.xml` declares Home, Services, Reminders, Insights and Profile. `app/build.gradle.kts` declares Java 11 source compatibility and a Material dependency, without Compose configuration. No Kotlin application source was found.

The `.gradle.kts` extension means the **build script** uses Kotlin syntax. It does not mean the application is written in Kotlin. Build configuration and application source are separate things.

`MainActivity` currently replaces views in one container and maintains a history of tab changes. It renders a dashboard and four empty placeholders. Existing repository documents describe subscription/reminder CRUD, budgets and recommendations as the domain; this is not a crypto trading application. The design can adopt crypto-app visual patterns while preserving that domain.

## Recommendation

**Latest user priority: clean, premium, and quickest delivery.** For that priority, the best fit is to keep the existing **Java/XML + Material 3** implementation, define a small visual system in Paper, and implement the designed screens using the existing dependencies. The app already uses `Theme.Material3.Light.NoActionBar`. Kotlin/Compose is the preferred option for a deliberate UI migration, but is not required to achieve the visual quality and adds setup/learning work to this delivery.

If the team chooses a deliberate UI migration, my recommended stack is **Kotlin + Jetpack Compose + Material 3 + Compose's built-in animation APIs**. Compose is a Kotlin UI toolkit: choosing it requires adding Kotlin/Compose build support and writing new UI code in Kotlin. Its composable functions render UI from current state; animations describe how that UI changes when the state changes. This migration is optional and has not been authorized as implementation work. [Compose setup](https://developer.android.com/develop/ui/compose/setup), [Material 3](https://developer.android.com/develop/ui/compose/designsystems/material3)

For the lowest-change delivery path, keep **Java/XML + existing Material Components**, add Material transitions, and use MotionLayout only for a complex gesture or layout transition. This can produce a polished interface without a language migration. The repo backlog records an October submission schedule, so migration effort should be weighed against remaining functional integration; that schedule has not been independently reconfirmed. [Material shared axis](https://developer.android.com/reference/com/google/android/material/transition/MaterialSharedAxis), [MotionLayout](https://developer.android.com/develop/ui/views/animations/motionlayout)

Neither framework determines the quality of the visual design. Consistent typography, spacing, colours, content hierarchy, useful states and restrained motion need an explicit design specification.

If sharing an Android/iOS UI becomes a real requirement, evaluate Compose Multiplatform, Flutter, or React Native separately. Compose Multiplatform extends Compose to additional platforms; ordinary Jetpack Compose remains Android-specific. Flutter offers implicit, explicit and physics-based animation APIs. React Native Reanimated runs animations on the UI thread, but its current 4.x documentation requires React Native's New Architecture. These are credible alternatives, but replacing this Android shell with a different platform stack is additional scope, rather than a prerequisite for a beautiful UI. [Compose platforms](https://kotlinlang.org/docs/multiplatform/compose-multiplatform-and-jetpack-compose.html), [Flutter animations](https://docs.flutter.dev/ui/animations), [Reanimated setup](https://docs.swmansion.com/react-native-reanimated/docs/fundamentals/getting-started/)

## Framework and library comparison

| Choice | Purpose | Fit for this project | Main trade-off |
| --- | --- | --- | --- |
| Jetpack Compose + Material 3 | Complete native Android UI, theme, buttons, sheets and navigation bar | Preferred if choosing a Kotlin UI migration | Requires a UI/build migration; not already installed |
| Compose Animation | Visibility, size, colour, content and coordinated motion | Default animation layer for Compose | Requires deliberate state/lifecycle handling, rather than animating every change |
| Material transitions | Container transforms and directional slide/fade transitions | Best initial option with existing Java/XML | Existing manual screen replacement must be integrated with transitions correctly |
| MotionLayout | XML-defined, gesture-driven layout animation | Optional for collapsing headers or interactive layout movement | Applies to direct children; not a whole-app navigation system |
| Lottie Android / Lottie Compose | Designer-exported vector animation assets | Optional onboarding, empty states and success feedback | Asset complexity and supported export features must be checked |
| Rive Android | Interactive graphics driven by state machines | Optional only if interaction-rich illustration is a requirement | New Compose API is labelled Beta; native runtime and worker lifecycle add integration effort |
| Vico | Interactive line/column charts | Optional Compose spending-history chart | Adds a dependency; current direction is Compose-first, with limited Views maintenance |
| Navigation 3 | Compose destination display and owned back stacks | Candidate for a Compose-only redesign | You own stack policy and must explicitly save/restore state |

### Built-in animations before additional dependencies

Use `AnimatedVisibility` for content appearing/disappearing, `animateContentSize` for expanding detail cards, `animate*AsState` for a single changing property, and `updateTransition` when properties must change together. Springs are useful for interruptible interactions; duration-based transitions suit predictable page changes. These APIs cover most requested motion without Lottie or Rive. [Choose an animation API](https://developer.android.com/develop/ui/compose/animation/choose-api), [customise animations](https://developer.android.com/develop/ui/compose/animation/customize)

For Java/XML, `MaterialContainerTransform` connects a service card to its detail view, and `MaterialSharedAxis` supplies directional movement between related screens. MotionLayout combines layout transitions, touch input and keyframes, but its documented direct-child limitation means it should be applied locally. [Container transform](https://developer.android.com/reference/com/google/android/material/transition/MaterialContainerTransform), [shared axis](https://developer.android.com/reference/com/google/android/material/transition/MaterialSharedAxis), [MotionLayout](https://developer.android.com/develop/ui/views/animations/motionlayout)

Lottie supports a View entry point (`LottieAnimationView`) and a Compose entry point (`LottieAnimation`). Use bundled assets and provide static fallback artwork if loading fails. Prefer vectors and inspect render performance rather than using an intricate illustration as a continuously looping background. The older GitBook performance page discusses historical Android limitations, so its implementation defaults should not be treated as current configuration advice. [Android integration](https://github.com/airbnb/lottie/blob/master/android.md), [Compose integration](https://github.com/airbnb/lottie/blob/master/android-compose.md)

Rive's official Android page distinguishes the legacy XML/View API from its newer Compose API. It calls the latter Beta while recommending it for new Compose projects. That is a vendor recommendation, not an independent stability guarantee; treat it as optional and validate the specific runtime/assets before release. [Rive Android](https://rive.app/docs/runtimes/android/android)

Vico's getting-started documentation lists Compose and Material 3 theming modules and a minimum Android SDK of 23, below this app's minimum of 26. A June 2026 prerelease notice says its Views module will receive critical fixes and have separate versioning. Prefer its Compose module for new chart work; do not adopt that prerelease merely for the described features. A small static sparkline may be simpler with native drawing; interactive chart requirements justify considering a library. [Vico setup](https://guide.vico.patrykandpatrick.com/getting-started), [maintenance direction](https://github.com/patrykandpatrick/vico/releases/tag/v3.3.0-next.1)

Navigation 3's fetched release table lists **1.2.0 stable**, released 23 September 2026, alongside **1.3.0-alpha01**. The example dependency block uses the alpha: choose a compatible stable release deliberately instead of copying that example blindly. `rememberNavBackStack` can restore navigation through configuration change and process death; keys need `NavKey` and serialisation support. It does not automatically decide the app's five-tab navigation rules. [Release notes](https://developer.android.com/jetpack/androidx/releases/navigation3), [state restoration](https://developer.android.com/guide/navigation/navigation-3/save-state)

## Visual references and how to adapt them

The user supplied this [specific Coinbase Mobbin collection](https://mobbin.com/apps/coinbase-ios-1d0a6f78-e687-4d7b-bbff-e9abe5cd09ff/88a889ac-e10b-4e45-b610-f5add141a7c6/screens). Exa could not fetch it. After the user signed in themselves, the in-app browser successfully displayed the collection and its accessible highlighted screenshots. Additional screens remained Pro-gated; those were not accessed.

Direct visual observations from the highlighted Home screen: white background, a prominent balance, thin blue line chart with subtle fill, small time-range controls, compact Crypto/Cash rows with right-aligned amounts, and a blue primary pill paired with a pale secondary action above five navigation icons. Small promotional/onboarding panels are present, but the layout does not depend on filling every section with a large card. Adapt that hierarchy to recurring spend, compact service rows, add-service/reminder actions and the existing five roots. Exact pixel measurements or typography specifications were not extracted from screenshots.

Coinbase's January 2025 design account describes a concise Home overview, consolidated assets, simpler asset details and filterable transactions. It is a historical first-party rationale, not proof of the exact October 2026 interface. Adapt those concepts to a spending overview, service collection, subscription details and activity history. [Coinbase design account](https://www.coinbase.com/en-es/blog/building-economic-freedom-one-pixel-at-a-time)

Kraken's navigation guide, last updated August 2026 in fetched content, describes chart inspection by dragging and an option to hide balances. Adapt these as spending-history inspection and a balance-privacy control. Its guide has some inconsistent tab wording, so it is used for documented interactions rather than an exact bottom-bar replica. [Kraken navigation](https://support.kraken.com/articles/360059154531-navigating-the-kraken-app)

Suggested design direction: a spacious light theme, deep navy text, electric-blue primary actions, a prominent monthly-spend figure, clear chart labels, compact provider rows and stable bottom navigation. Include a dark theme and readable text at enlarged font sizes. These are our design decisions inspired by the references, not claims that every source uses those exact tokens.

For fastest implementation, begin with Home and Services plus service detail; make their text roles, row spacing, buttons and dividers reusable. Then apply those components to Reminders, AI and Profile. Restrict initial animations to a short root fade, directional detail transition, expanding content and success feedback. Additional illustration libraries and a rich chart library can follow only when their specific assets/interactions are needed. This sequence develops one approved visual language once instead of styling each screen independently.

| Existing destination | Proposed content and interactions |
| --- | --- |
| Home | Monthly-equivalent spending by currency, budget progress, upcoming renewals, add-service action |
| Services | Search/filter, service rows, details, add/edit/delete subscription |
| Reminders | Upcoming timeline, reminder details, create/reschedule/delete |
| AI (replaces Insights) | Chat by default; Insights subview retains evidence-backed suggestions and approve/dismiss decisions |
| Profile | Account, preferences, currency, privacy, notifications, activity, sign-out |

Approval of an insight records a decision; it must not imply automatic provider cancellation. Different currencies must not be added into one total without a documented conversion mechanism. Unknown values must not be animated into invented financial figures.

## Implementation boundary and sequence

1. Review the navigation/design specification and representative screens first.
2. Choose the Java/XML delivery path or the Kotlin/Compose redesign explicitly; do not install both animation asset engines by default.
3. Define named colour, typography, spacing and motion values; implement a few domain components such as spend summary, service row and renewal row.
4. If migrating, build one new Compose screen and verify interoperability before replacing further screens. Google recommends incremental migration. The current app has no Fragment navigation setup; a Fragment recommendation in general docs is not a description of this repository. Choose one coherent destination-hosting approach. [Migration guidance](https://developer.android.com/develop/ui/compose/migrate/strategy)
5. Keep money/date calculations in domain code, data fetching behind repositories, and screen rendering/navigation in the UI layer. Restore tab/detail state and drafts; verify back gestures, loading/error/empty states and authenticated navigation.
6. Verify the chosen implementation on a real Android device, including large text, TalkBack, reduced motion, rotation and a low-end rendering profile. A browser prototype validates design interactions, not native Android integration.

## Confirmed AI scope

The user confirmed **AI → Chat / Insights**, replacing the Insights root, and one initial model: Nemotron 3.5 Lightning (free). OpenRouter's exact identifier is `nvidia/nemotron-3.5-lightning:free`. It supports tool calling but not enforced JSON through `response_format`. Its free endpoint is rate limited, and the hosting guidance asks users not to submit confidential information or personal data. Start the prototype with synthetic data; do not silently substitute the paid model identifier. [Verified model](https://openrouter.ai/nvidia/nemotron-3.5-lightning:free), [data guidance](https://openrouter.ai/blog/insights/nemotron-3-5-lightning/), [rate limits](https://openrouter.ai/docs/api_reference/limits)

The simplest integration keeps the native frontend and adds Chat to the existing trusted AI backend boundary: authenticate → user-scoped read tools → LangGraph workflow → configured model → validated answer with source links. Keep financial arithmetic deterministic and credentials server-side. Begin with read-only Q&A and existing edit/review screens. Durable conversations and any later confirmed writes require explicit persistence, authorization and replay handling. LangGraph supplies workflow streaming and interrupt/resume capabilities; it does not supply authorization for an arbitrary own-server API. [Streaming](https://docs.langchain.com/oss/python/langgraph/streaming), [memory](https://docs.langchain.com/oss/python/langgraph/add-memory), [interrupts](https://docs.langchain.com/oss/python/langgraph/interrupts)

The complete scoped proposal is [AI chat architecture](../../docs/design/ai-chat-architecture.md).

## Deliverables and Paper limit

The requested Luna Max designer created [Agent Cost Control — Mobile navigation](https://app.paper.design/file/01M48HD3JSXAB6VT4CSQ9BWKWG/p-1-0). The user added Paper MCP and the connection worked. Home and Services were completed and visually reviewed. Reminders, AI/Insights and Profile are scaffolded; AI/Chat has its selector, model/demo treatment, prompts and sample question, but its answer and remaining content were blocked. A navigation/architecture map was started and is incomplete. Core detail/form artboard creation was blocked before any such frame was created.

Paper then returned: “Weekly MCP limit reached. It resets in 3 days.” This is an account/service limit, not a missing connection or an approval rejection. Further canvas creation and screenshot/export verification were unavailable. A final release of the parent's working indicator succeeded. No complete Paper flow, clickable HTML prototype or native implementation is claimed. [Paper MCP documentation](https://paper.design/docs/mcp)

The complete written handoff is [navigation specification](../../docs/design/mobile-navigation-2026-10-06.md), [planned core detail frames](../../docs/design/core-detail-frames.md), and [Java/XML implementation sequence](../../docs/design/frontend-implementation.md). The remaining Paper drawing work can resume when service quota is available; no upgrade or purchase was performed.

Existing October 1 research remains in `exa-results/mobile-design-2026-10-01/research.md`; this report narrows the recommendation to the actual Java/XML Android project and requested animated redesign.
