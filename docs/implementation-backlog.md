# Deliverable 4 implementation backlog

Assessment date: **2 October 2026**, Africa/Johannesburg. Sources: [R&D project](https://app.notion.com/p/3bc99988e9478001b2b2e6399e5b86e5), [latest implementation plan](https://app.notion.com/p/3df99988e94781e58b25e180eca000fe), the project's linked task board, and this repository. Planner dates below are provisional checkpoints, not new commitments or a verified academic submission deadline.

## Current evidence

**6 October update:** the list below records the 2 October baseline. Catch-up source implementation is now on `codex/mvp-catchup-2026-10-06`; see [the current assessment and verification log](catchup-2026-10-06.md). A task is not marked complete from source changes alone.

- `MainActivity.java` implements navigation among Dashboard, Subscriptions, Reminders, Recommendations and Profile/Settings. Every screen renders an empty state; dashboard totals remain placeholders.
- `AndroidManifest.xml` has no INTERNET permission. App dependencies include Material UI and test libraries; no REST client or authentication integration is implemented.
- No app models, repositories, authentication screens, scheduler, AI endpoint or actual test source files were found in the Android tree.
- Supabase now has ten secured tables, with the applied migration, transactional contract tests, [shared database/API contract](database.md) and [verification record](database-verification.md) saved in this repository.
- The sole legacy profile remains unlinked. API clients cannot access it until an administrator verifies ownership and reconciles it with Supabase Auth.

Database implementation is complete for this change. The planner's full definition of done also requires reviewed PRs, integration and verification from main; those stages have not been claimed complete. Notion statuses were read for context and were not changed.

## Ordered work

| Priority | Work and planner checkpoint | Acceptance criteria | Depends on |
| --- | --- | --- | --- |
| P0 | Supabase Auth integration — 24 Sep, overdue | A new user signs up, signs in, creates/loads their own safe-column profile, refreshes a session and signs out. Protected screens reject missing sessions. Passwords go only to Auth; no service-role secret in APK. | Database contract |
| P0 | Android data access and shared contracts — 27 Sep, overdue | Add INTERNET permission and a deliberate REST integration. Java models use `long`, UUID/string, `BigDecimal`, `LocalDate` and explicit-offset timestamps. Repositories expose loading/success/error results, paginate lists and interpret zero-row mutations honestly. Verify clone/run/auth/navigation for all five developers. | Auth, database contract |
| P0 | Subscription create/list — 29 Sep, overdue | Validated forms persist required fields, reload after app restart and show only the signed-in user's records. Loading, empty, invalid-input and network-error states are observable. | Auth, repositories |
| P0 | Subscription details/edit/delete — 1 Oct, overdue | Detail view loads a stored record; edits persist; deletion requires confirmation. Linked reminder/recommendation deletion is explained and reflected in UI; other users' records stay inaccessible. | Subscription create/list |
| P0 | Reminder create/list — 30 Sep, overdue | User creates a reminder linked to their subscription, using their time zone; upcoming reminders load from persistent storage with pagination. | Subscription records, repositories |
| P0 | Reminder reschedule/delete — 2 Oct | Rescheduling persists the new instant and status; confirmed deletion removes the schedule. Invalid values, unavailable records and failed writes retain useful input/feedback. | Reminder create/list |
| P1 | Dashboard and budgets — 4 Oct | Dashboard shows real active count, upcoming renewals and monthly equivalent spend; annual prices divide by 12. Totals are grouped by currency, and budget comparisons use matching currency. Budget create/update conflicts are handled. | Subscription CRUD, budgets |
| P1 | Finish shell integration — 24 Sep, overdue | Navigation preserves sensible back behavior, shows auth-aware profile/settings, and survives recreation without losing the current destination. Existing placeholders are replaced incrementally by real modules. | Auth, CRUD screens |
| P1 | Deterministic risk rules — 6 Oct | Observable tests protect renewal proximity, budget overrun, low usage/high cost and possible duplicate-category flags. Unknown usage is treated as unknown; rules produce evidence rather than unsupported financial actions. | Dashboard contracts, stored subscriptions |
| P1 | Secure AI endpoint — 8 Oct | Endpoint verifies the token and ownership before loading data; validates bounded action/evidence/reason/confidence/savings output; handles provider failures. Trusted keys and model orchestration stay on the backend. | Auth, risk rules |
| P1 | Recommendations and user decisions — 9 Oct | Screen displays backend recommendations with reason, evidence, confidence and estimated savings. Approved/rejected/ignored decisions persist and produce activity records; approving never automatically cancels a provider account. | AI endpoint, data repositories |
| P1 | Integrated CRUD/RLS/dashboard tests — 10 Oct | Android journey creates, reads, edits and deletes subscriptions/reminders; two users stay isolated; dashboard updates correctly after writes/reloads. Include REST/session behavior beyond the existing SQL tests. | Integrated screens |
| P1 | Renewal notifications and feature freeze — 11 Oct | Deliberate scheduler/delivery design handles permissions, time zones, reschedules, deletes and repeat delivery without duplicate alerts. Backend notification records remain distinct from user-managed schedules. | Reminder CRUD, device integration |
| P1 | UX validation/feedback — 14 Oct | Each CRUD flow has readable field errors, loading states, retry behavior, success/error feedback and confirmation. Verify rotation/background behavior without losing drafts unexpectedly. | Core journeys |
| P1 | Auth/security/persistence regression — 15 Oct | Test expired sessions, sign-out, user switching, forbidden access and restart persistence; inspect the release APK/configuration for accidental trusted secrets. | Auth and full integration |
| P1 | Real-device notifications — 15 Oct | Verify supported Android behavior and notification permissions on representative devices; missed or denied delivery is handled honestly. | Scheduler/delivery |
| P1 | Reproducible build/handover — 17 Oct | Document Android Studio/SDK/JDK prerequisites, configuration and actual build commands. A teammate can clone and build main; run the repository checks and record real results. | Integrated stable app |
| P1 | Final demo/submission evidence — 18 Oct | A fresh user completes sign-up → subscription CRUD → reminder CRUD → recommendation → decision. Capture representative evidence and confirm the real academic deadline with the group. | All required journeys |

The planner already records Android initialization/Git workflow as complete. The current code supports a navigation shell, but UI existence alone is insufficient evidence that authentication, persistence or CRUD is complete.

## Ownership boundaries

Keep rendering and navigation in screens, domain calculations in ordinary testable Java code, and Auth/REST details in repositories/services. The AI endpoint generates recommendations; it is not an additional CRUD backend. Preserve the database's ownership checks even when the client filters by user ID. Review each coherent feature through its own PR and verify from the integrated branch before marking planner tasks complete.

The Gradle daemon configuration requests JDK 25, while application source compatibility is Java 11. Reproducibility work must verify the actual configured toolchain instead of assuming that any Java 11 installation builds the project.

## Deferred scope

Provider OAuth connectors, bank linking, payment processing, automatic cancellation, email scanning, RAG and complex agent orchestration remain outside this month's MVP. The newer native Android decision supersedes the older product summary's web/PWA proposal.
