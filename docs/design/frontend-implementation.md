# Quickest route to a premium Agent Cost Control frontend

Decision date: 6 October 2026. This records the original implementation plan. Native Java/XML screens, compact Chat and local risk checks were implemented during the 6–7 October catch-up; see [the verification record](../catchup-2026-10-06.md) for actual checks and outstanding acceptance work. Motion values and streaming/source-link components below remain proposals unless that record says otherwise.

## Recommended stack

Keep **Java + XML layouts + existing Material 3 Components** for this delivery. The app already uses `Theme.Material3.Light.NoActionBar`; adopting the Paper visual design does not require replacing its UI framework. Gradle's Kotlin build scripts do not make the application Kotlin.

Use Material transitions for navigation and expanding details. Introduce MotionLayout only for a specific coordinated layout/gesture effect that simpler transitions cannot cover. Add Lottie only if a reviewed illustration asset warrants it; defer Rive and interactive chart libraries until there is a concrete requirement. These choices reduce migration and dependency work while delivering the requested appearance. See the [source-backed comparison](../../exa-results/animated-android-2026-10-06/research.md).

Kotlin + Compose + Material 3 remains a strong choice for a deliberate subsequent UI migration. It should be an explicit project decision with a verified build and one representative screen before changing the remaining UI. A platform migration is separate from matching a visual design.

## Visual implementation sequence

Paper currently contains reviewed Home and Services screens, an unfinished AI/Chat shell, and scaffolded remaining roots. Its weekly MCP quota blocked further drawing and export. The written navigation and detail specifications are the full intended scope; the canvas is not a complete flow.

1. Review the five Paper root screens and core detail/form routes. Confirm the content hierarchy using realistic long names, currency values and dates.
2. Translate the Paper design values into Android resources: colours, dimensions, text appearances, shape appearances and drawable icons. Reuse existing theme/colour/string resources before creating duplicates. Paper's HTML/CSS export is a specification to translate, not Java/XML that can be pasted directly into the app.
3. Build **Home → Services → Service detail** first. These establish the spending summary, service row, headings, primary/secondary buttons, and shared navigation chrome.
4. Reuse those visual rules for **Reminders → Reminder detail/form**, **AI → Insights → Evidence review**, and **Profile → Budget/settings**. Implement subscription and reminder add/edit forms with reusable field appearances and clear validation messages. The confirmed fourth root is AI, containing Chat (default) and Insights.
5. Connect each finished screen to real domain/data results. Loading, empty and error layouts must be as deliberate as populated layouts. Keep illustrative sample values in design/demo artifacts; do not present them as live account data.
6. Add restrained motion after navigation and data behavior work. Check the result on an Android device, with large text and TalkBack, before calling it complete.

Add **AI → Chat** as a separate, read-only MVP journey through the trusted AI backend. Use a single Nemotron 3.5 Lightning (free) model badge, streaming answer states and links to source records. The free endpoint's data terms mean initial integration uses synthetic data. LangGraph and provider credentials belong on the backend; they do not require replacing the Java/XML frontend. See the [AI architecture and verified endpoint constraints](ai-chat-architecture.md).

## Components to build once

| Shared visual element | Native implementation direction | Used by |
| --- | --- | --- |
| Screen title and supporting text | Existing TextViews plus named text appearances | All screens |
| Primary and secondary pill actions | MaterialButton with shared shape/colour/text styles | Add, save, review and reschedule |
| Service row | Shared row XML; RecyclerView for lists as they become real/paginated | Services and Home previews |
| Renewal row | Shared icon/text/date/amount lanes | Home and Reminders |
| Form field | TextInputLayout/TextInputEditText with inline field errors | Subscription, reminder, budget and auth forms |
| Status/filter | Material chips or a restrained toggle group | Services, Reminders and Insights |
| Bottom navigation | Existing BottomNavigationView with consistent icon/label styling | Five root screens |
| AI subview switch | Two accessible tabs/toggle destinations preserving independent state | Chat and Insights |
| Chat transcript and composer | RecyclerView message rows, source-link actions and an inset-aware input/action row | AI Chat |
| Confirmation/feedback | Material dialog and Snackbar where appropriate | Delete, unsaved draft and save feedback |

Keep icon and trailing amount/action columns at consistent widths. Use whitespace and dividers for ordinary information; reserve cards for content that benefits from a distinct container. Maintain at least 48dp touch targets and enough contrast for small supporting text.

## Behavior that the design must preserve

- **Navigation:** roots are peers: Home, Services, Reminders, AI, Profile. Tab changes do not pile up in the detail back stack. Preserve each tab's filters/search/scroll state and AI's conversation/subview; Back from a detail restores its origin. Hide the bottom bar on task/detail/auth routes.
- **Drafts:** restore form values after rotation and process recreation. Ask before deliberately discarding changed input. A failed save retains the draft and reports the actual failure.
- **Money:** use the existing decimal contract, normalise annual cost to monthly equivalent, and group totals/budgets by currency. Do not add ZAR and USD into one total. Round at the specified calculation boundary.
- **Insights:** show the stored reason/evidence and estimated savings. Approve/dismiss records a choice; it does not cancel the provider subscription.
- **Authentication:** protected screens require a valid session. Clear user-scoped UI state at sign-out or user switching; hiding controls is not authorization.
- **Data states:** unknown or unavailable data stays unknown. A decorative chart must not imply that historical spending records exist when they do not.

## Motion budget

Start with a brief root fade (about 160ms), directional detail transition (about 220ms), and sheet/form feedback (about 240ms). These are initial design values to validate on a device, not measured performance guarantees. Respect the system animation setting; a disabled transition must preserve the same behavior and information.

For the existing View stack, use MaterialSharedAxis for related routes and MaterialContainerTransform when continuity between a row/card and its detail meaningfully helps orientation. Do not replay chart reveals on every tab switch or animate a guessed money total while a request is loading.

## Ownership and verification

Keep `MainActivity` responsible for hosting/navigation; move screen-specific rendering and event wiring into cohesive screen code as functionality grows. Keep spending calculations in ordinary testable Java code and Auth/REST behavior behind the documented data boundary. Avoid a large rewrite or a new generic UI framework for the five screens.

For the actual implementation, run the repository's available build and lint commands, then verify the important native journeys: sign in → add service → view/edit → add/reschedule reminder → review insight → sign out. Check Back, rotation/process restoration, two currencies, failed requests, enlarged text and reduced motion. Test domain money/state behavior with meaningful assertions. This planning change does not run or establish those implementation checks.

The [navigation specification](mobile-navigation-2026-10-06.md) defines destinations, Back rules, tokens, states and Paper handoff. The [core detail/form specification](core-detail-frames.md) records the supporting frames that could not be drawn before Paper's quota was reached. The subsequent catch-up retains Java/XML and adds no production dependencies. Paper revisions remain pending because the user chose Android work while Desktop was closed.
