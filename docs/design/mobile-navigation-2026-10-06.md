# Agent Cost Control — mobile navigation design

**Review date:** 7 October 2026

**Status:** framework-neutral design specification updated for the AI tab; Paper canvas is partial because the Paper MCP weekly limit stopped further edits.

## Product and current application

The repository contains a native Android **Java/XML** application. The implementation now uses Home, Services, Reminders, AI, and Profile, with Chat and Insights inside AI. The app has no Kotlin source files or Jetpack Compose dependency. This specification describes the visual direction; [the catch-up record](../catchup-2026-10-06.md) distinguishes implemented behavior from planned backend and design work.

For the quickest October MVP route, keep the current Java/XML shell and its Material 3 theme/dependency. This visual direction can be implemented with the components already in the project; it does not require a Compose migration or a new chart dependency.

The product helps people understand and manage recurring service costs. Use recognizable financial-app hierarchy—one clear total, useful trends, concise detail, and well-signposted actions—while keeping every screen about subscriptions and reminders. Do not add coin prices, wallets, token balances, trading, exchange, or other crypto behavior. The Coinbase design story is a reference for consolidated overviews and clearer transaction/detail navigation; Kraken's app guide is a reference for readable charts and balance privacy controls. These are pattern references, not requests to reproduce their screens, names, or marks: [Coinbase design story](https://www.coinbase.com/en-es/blog/building-economic-freedom-one-pixel-at-a-time), [Kraken app navigation](https://support.kraken.com/articles/360059154531-navigating-the-kraken-app).

The financial data contract remains authoritative: amounts use two-decimal precision; monthly and annual plans convert to monthly equivalents; totals, charts, and budgets stay grouped by currency with no exchange-rate conversion. Approval of an insight records the user's choice only; it never cancels a service or moves money. See [the database and Android contract](../database.md).

## Visual direction

Follow the [UI copy rules](ui-copy.md): one title, concise labels and explanations only where they help the user act.

The direction is **clear ledger**: an airy white canvas; a prominent money figure near the top; a thin electric-blue trend line with a very subtle fill; compact service rows separated by quiet dividers; and generous whitespace. Use strong ink for money and dates, electric blue for the next action, and restrained teal/amber/red signals for status. Place one saturated blue pill primary action beside a pale-blue secondary action above the bottom navigation. Avoid a stack of decorative cards and promotional clutter. Keep service rows text-led with neutral category marks or initials. Real provider logos are unnecessary. Use the same semantic action names in every screen, such as “Add service,” “Save service,” “Reschedule reminder,” and “Approve idea.”

### Design tokens

Values are logical pixels/dp. The five root artboards are 390 × 844 logical px with system-status and gesture-safe areas reserved. The Paper status-bar illustration is a mock; production Android layouts must use platform insets. Content scrolls beneath fixed app navigation.

| Token | Light | Dark alternative | Use |
| --- | --- | --- | --- |
| `canvas` | `#FFFFFF` | `#10151E` | App background |
| `surface` | `#FFFFFF` | `#171F2B` | Rows, cards, sheets |
| `surface-raised` | `#F5F8FC` | `#202A38` | Muted panels, selected navigation |
| `ink` | `#142033` | `#F4F7FC` | Main labels, amounts, headings |
| `ink-muted` | `#59677A` | `#B1BDCC` | Supporting copy and metadata |
| `outline` | `#DCE3ED` | `#334154` | Dividers and control borders |
| `action` | `#155EEF` | `#7EA7FF` | Primary actions, links, chart line |
| `action-pressed` | `#0E49C7` | `#A9C4FF` | Pressed/selected action |
| `action-tint` | `#EAF1FF` | `#202F49` | Selected chips and soft action areas |
| `positive` | `#16794A` | `#64D59A` | Completed/within budget |
| `caution` | `#8A5700` | `#FFD27A` | Due soon/near budget |
| `danger` | `#B42318` | `#FF9289` | Validation/deletion/error |
| `on-action` | `#FFFFFF` | `#10151E` | Text on primary action |

Use Roboto or the platform's sans-serif. Suggested text roles: display 30/36 semibold; screen heading 26/32 semibold; section title 17/24 semibold; body 15/22 regular; secondary 13/18; label 12/16 medium; tab labels 11/14 medium. Financial values use tabular numerals where supported. Do not use tiny all-caps copy to convey important status.

Spacing uses a 4 px base: 4, 8, 12, 16, 20, 24, 32, 40. Root horizontal inset is 20 px; list row vertical padding is 14 px; card padding is 18 px; cards use 18 px corners; action sheets use 24 px top corners. Interactive targets are at least 48 × 48 px. Keep clear space around chart labels and do not rely on color alone for status.

## Navigation model

| Root | Tab label | Purpose |
| --- | --- | --- |
| Home | Home | Currency-grouped recurring-cost overview, budgets, next renewals, one short insight preview |
| Services | Services | Search, filter and manage active/paused/cancelled subscriptions |
| Reminders | Reminders | Upcoming/completed renewal reminders and their schedules |
| AI | AI | Chat (default) and Insights subviews; the Insights subview retains recommendations and recorded decisions |
| Profile | Profile | Budgets, account, preferences, activity and sign-out |

Each tab owns a root and retains its scroll position, selected filter and query when the user switches tabs. The AI root defaults to Chat and retains both its conversation and selected Chat/Insights subview. The bottom bar remains visible on roots and is hidden for detail, form, confirmation and authentication routes. Switching tabs from a detail route returns to the selected tab's retained root. Root tabs are peers: changing tabs does not add a root destination to the detail back stack. This replaces the current shell's single history stack, which currently pushes tab changes into Android back history.

Headers contain their title without a back arrow, following the user's 7 October decision. Android system Back and gestures return from a detail or form to its originating route. Back from a non-Home root returns to Home while retaining that tab's state. Back from Home delegates to Android task behavior. If a create/edit form has changed, Back or Cancel opens “Discard your changes?” with “Keep editing” and “Discard draft.” An untouched form closes immediately. A successfully saved form returns to the parent detail/list and announces success. Production forms restore in-progress draft values after rotation and process recreation; intentional discard happens only after the user chooses “Discard draft.” Search, filters, tab roots and drafts are also restorable after ordinary activity recreation.

### Screen map

```text
Sign in / Create account
  └─ profile setup → Home
Home
  ├─ All services → Services → Service detail → Edit / Add reminder
  ├─ Upcoming renewals → Reminders → Reminder detail → Reschedule
  └─ Insight preview → AI / Insights → Insight review → Approve / Dismiss
Services
  ├─ Add service → Subscription form → Service detail
  └─ Service row → Service detail → Edit / Delete / Add reminder
Reminders
  ├─ Add reminder → Reminder form → Reminder detail
  └─ Reminder row → Reminder detail → Complete / Reschedule / Delete
AI / Chat
  ├─ Suggested question or message → Answer with service/reminder evidence links
  └─ Any proposed data change → explicit confirmation → authorized save
AI / Insights
  └─ Recommendation → Evidence and decision → Approve / Dismiss
Profile
  ├─ Budgets → Budget form
  ├─ Preferences / Activity / Account details
  └─ Sign out → Sign in
```

## Primary root artboards — 390 × 844

### 01 — Home

The heading reads “Good morning” with an accessible notification action that opens Reminders. Lead with **Monthly recurring** and a large `R 1,348.00` value; underneath, show a separate `USD 10.99` line and the caption “Currencies shown separately.” Do not make a combined total. Place a thin blue line chart directly below the total, with a very subtle fill, 1M/3M/6M period controls and six-month ZAR labels; provide a factual text summary such as “ZAR monthly-equivalent spend · May–Oct,” without inferring a trend from illustrative data.

Below the chart, show the ZAR budget as a concise line, `R 1,348.00 of R 1,500.00 · 90% used`, plus its status and an accessible progress value. Follow with “Next renewal” and a compact row naming the service, date and billed amount/currency; “See all” opens Reminders. A single evidence-backed insight preview may appear as a quiet inset row. Keep the main list white and use dividers in place of separate cards. Anchor a blue pill “Add service” primary action beside a pale-blue “Add reminder” secondary action above the fixed bottom tabs. The home state must make clear that R and USD totals were not converted. Clearly label illustrative values as sample data.

### 02 — Services

Show heading “Services,” a labeled search field, and filter controls for All, Active, Paused and Cancelled. “Add service” is the primary action. Separate sections by currency; each group shows a sum such as `ZAR · R 1,348.00 / month` or `USD · $10.99 / month`. Service rows are full-width buttons with a small neutral monogram/category shape, service and category names, price/cycle, and next renewal date. Use thin dividers and right-aligned amounts. Never use an unlabeled logo tile to identify a provider. Selecting a row opens Service detail; search and filter state remain on return.

### 03 — Reminders

Show “Reminders,” an upcoming/completed segmented control, and “Add reminder.” Group upcoming items by “Tomorrow,” “This week,” and month/date as useful; show the reminder time with its IANA time zone in the detail view. Each row names both the service and what will happen, for example “Review Design Studio renewal” and “Wed, 7 Oct · 9:00 AM.” Completed reminders remain reachable through the Completed filter. A row opens Reminder detail. Empty state: “No upcoming reminders” with “Add reminder.”

### 04 — AI / Chat

The AI tab opens Chat by default and offers a two-option Chat/Insights selector within the same retained root. Show the active model as `Nemotron 3.5 Lightning (free)`; the current provider identifier is [`nvidia/nemotron-3.5-lightning:free`](https://openrouter.ai/nvidia/nemotron-3.5-lightning:free). Do not show an Auto choice or invent alternative configured models. Suggested prompts include “What renews next?” and “Where can I save?” Answers cite the specific service or reminder records they use and keep totals grouped by currency, for example `R 1,348.00 (ZAR)` and `$10.99 (USD)` with no conversion. Source links open the related service, reminder, or currency total. The composer has a clear send action and accessible busy/error status.

The free provider may log prompts for model improvement and says not to submit confidential or personal information. The Paper preview must use clearly labeled sample data and must not imply live service records are being sent. Before a production chat can use personal subscription data, confirm an acceptable data-handling path. Keep model credentials on the server, validate account ownership server-side, and send only data the signed-in user is authorized to access. Chat cannot cancel a provider or move money. Any future action that changes a record needs a plain-language summary and an explicit confirmation before the server writes it; a model response alone never authorizes a write.

### 04 — AI / Insights

The Insights subview sits within the AI root and retains the existing evidence-backed recommendations. Show active and reviewed groupings. A recommendation card includes a verb-led title, short reason, evidence label, affected service, possible savings in its original currency, qualitative confidence, and a “Review” action. Keep confidence secondary to evidence. Do not invent usage or pricing data. Example: “Review Cloud Notes” because the user has marked usage low; show `R 89.00/month possible savings if you decide to cancel` and “Usage is self-reported.” Opening the card shows the full evidence and actions. Decision state changes to “Approved” or “Dismissed,” remains in history, and never triggers an external provider action.

### 05 — Profile

Show a profile/account summary, then sections for Budgets, Preferences, Activity, Security and Account. Budget rows identify month/year and currency; “Edit ZAR budget” opens the budget form. Preferences include display currency, time zone, renewal lead time, notifications and theme. Currency preference affects display formatting only; it does not convert or merge stored totals. Theme offers System, Light and Dark. Activity presents database-backed records when available. “Sign out” returns to Sign in after a clear local confirmation; authentication screens never expose one user's records to another.

## Detail and task routes

### Service detail

Frame `Service / detail` uses a Back control, service name, category and status. Its price area states the billed amount, cadence, normalized monthly equivalent and currency separately. Metadata includes next renewal, provider (optional), plan (optional), user-entered usage, and importance. Primary actions are “Edit service” and “Add reminder.” A destructive “Delete service” sits in a lower separated section and requires confirmation that linked reminders and recommendations will also be deleted. On success, return to Services and show a concise confirmation. Back returns to the same list position/filter.

### Add/edit subscription form

Fields: service name (required); provider (optional); category (required); amount (required, non-negative decimal); currency (required three-letter code, initially ZAR); billing cycle (monthly/annual); renewal date (required and not before start date); start date (optional with a clear default); usage (unknown/low/medium/high); importance (low/medium/high); notes (optional). Show monthly equivalent under amount as the user edits it. Annual amount is divided by twelve; do not silently convert currencies. Inline errors point to the specific field, retain all other input and are announced. Save uses “Save service”; Cancel returns to the source. Editing populates the saved record. Delete is a separate confirmed action, never folded into Save.

### Reminder detail and form

Detail names the linked service, renewal date/amount/currency, scheduled reminder date/time, time zone, and status. Actions: “Mark complete” changes pending to completed and stays on detail with updated status; “Reschedule” opens the prefilled reminder form; “Delete reminder” asks for confirmation and returns to Reminders. A completed reminder offers “Reopen reminder.”

The form requires a linked active or paused service, reminder date, local time and time zone; an optional lead-time preset can initialize the date from the service renewal. Saving or editing never changes the service's own renewal date. Validate that the selected reminder instant is not malformed or in an unsupported past state; explain the date/time context and keep values after failures. Back returns to Reminder detail when editing, otherwise to the Reminders list.

### Budget form

Edit one monthly budget for a calendar month/year and an explicit currency. Amount must be non-negative. Budget progress compares only active subscription monthly equivalents in the same currency. If no matching services exist, say “No active services in ZAR yet”; never show a cross-currency amount. The database permits one budget per user/month/year; a duplicate conflict should keep the draft and offer “Reload budget.”

### Insight review

Use a detail route with “Why you’re seeing this,” the exact stored evidence, data date/source where available, confidence, and possible monthly/annual savings in the subscription currency. State clearly that savings are estimates and service changes happen with the provider. “Approve idea” records approved; “Dismiss” records ignored. A follow-up confirmation says “Your decision is recorded. Your service is unchanged.” Both actions can be changed later through the decision control if product policy permits. No button says Cancel unless it opens the provider or explains the manual next step.

## Authentication and onboarding artboards

Create `Welcome`, `Sign in`, `Create account`, `Verify email`, and `Profile setup` frames at 390 × 844. Authentication forms use email/password and accessible password visibility controls; passwords go only to the Auth provider, never a profile table. Profile setup asks first and last name, display currency and time zone, prefilled from device/project defaults where appropriate. A success state confirms the account is ready before entering Home. Provide field-level validation, loading, retry/network error, expired-session and sign-out states. Authentication routes hide bottom navigation. Back from profile setup returns to account creation only while that local flow remains valid; back from sign in returns to Welcome. An expired session returns to Sign in with a clear “Sign in again to continue” message and does not discard stored records.

Authentication screens are specified here as design requirements; this document does not submit credentials or connect to an account.

## State, motion and accessibility

Every data-owning root and form needs loading, empty and error states. Loading uses two or three short skeleton rows and an accessible busy label. Empty states name what is absent and offer one safe next step. Errors say what failed, keep submitted form values and provide Retry; Retry never claims success without a returned record.

| Motion | Duration / easing | Behavior |
| --- | --- | --- |
| Root tab switch | 160 ms, standard decelerate | Brief content fade; preserve scroll/query/filter |
| Detail push/pop | 220 ms, standard ease | Horizontal slide; reverse on Back |
| Bottom sheet/modal | 240 ms, emphasized decelerate | Fade backdrop and lift sheet from bottom |
| Chart draw on first load | 260 ms, ease-out | Draw line once; do not replay on every recomposition |
| Button press | 90 ms | Small tonal shift; no bounce or scale that shifts layout |

When system Reduce Motion is enabled, remove translation and chart-draw animations, leaving an immediate state change or brief fade. Support screen readers with role, name, current tab, validation text, busy/error status, accessible chart summary and descriptive buttons. All tab items and icon actions are at least 48 dp square, focus order follows visual reading order, and keyboard/Back navigation matches touch behavior. Check large text, 200% scaling, contrast, external keyboard focus, and long service names. Color must always have a text/icon shape cue.

## CTA and Back behavior map

| Origin | Control | Destination / result | Back or cancel |
| --- | --- | --- | --- |
| Any root | Home / Services / Reminders / AI / Profile tab | That tab's retained root | Switching tabs never changes detail history |
| Home | Notification bell | Reminders, Upcoming | Back returns to Home |
| Home | All services | Services, current filter | Back returns to Home |
| Home | See all renewals | Reminders, Upcoming | Back returns to Home |
| Home | Insight preview / Review | AI / Insights or specific insight detail | Back returns to origin |
| Services | Search / status filter | Filters the same currency-grouped list | Query/filter are retained |
| Services | Add service | Blank subscription form | Cancel returns to Services; dirty draft asks first |
| Services | Service row | Service detail | Back returns to list position/filter |
| Service detail | Edit service | Prefilled subscription form | Cancel returns to detail; dirty draft asks first |
| Service detail | Add reminder | Reminder form preselected to service | Cancel returns to detail |
| Service detail | Delete service | Confirmation, then delete and return to Services | Cancel confirmation keeps record |
| Subscription form | Save service | Validate, save, return to parent/detail | On error retain draft; Back asks if dirty |
| Reminders | Upcoming / Completed | Filter the list | Filter is retained |
| Reminders | Add reminder | Reminder form | Cancel returns to Reminders |
| Reminders | Reminder row | Reminder detail | Back returns to list position/filter |
| Reminder detail | Mark complete / Reopen reminder | Update status in place | Back returns to Reminders |
| Reminder detail | Reschedule | Prefilled reminder form | Cancel returns to Reminder detail |
| Reminder detail | Delete reminder | Confirm, delete, return to Reminders | Cancel keeps record |
| Reminder form | Save reminder | Validate, save, return to source | On error retain draft; Back asks if dirty |
| AI | Chat / Insights selector | Retained Chat or Insights subview | Switching subviews preserves conversation and insight decisions |
| AI / Chat | Suggested prompt / Send | Sample or grounded answer with service/reminder evidence links | Back returns to the originating root; conversation state is retained |
| AI / Chat | Proposed account-data change | Confirmation summary, then authorized save only after explicit confirmation | Cancel leaves data unchanged; no write occurs on model output alone |
| AI / Insights | Recommendation card / Review | Insight review detail | Back returns to AI / Insights position |
| Insight review | Approve idea | Record approved decision; service unchanged | Stay on detail, update status |
| Insight review | Dismiss | Record ignored decision; service unchanged | Stay on detail, update status |
| Profile | Edit budget | Budget form for selected month/currency | Cancel returns to Profile |
| Profile | Theme / time zone / notifications | Update named local preference after selection | Screen reader receives changed value |
| Profile | Activity | Activity list | Back returns to Profile |
| Profile | Sign out | Confirm, clear local session, show Sign in | Cancel remains in Profile |
| Welcome | Sign in / Create account | Selected authentication route | Back returns to Welcome |
| Auth | Continue / Create account | Validate, show loading, then success/error | Preserve fields on error |
| Profile setup | Finish setup | Complete onboarding, show Home | Back returns to previous onboarding step |

## Paper handoff plan

Create one Paper document named **Agent Cost Control — Mobile navigation** with primary 390 × 844 roots `01 Home`, `02 Services`, `03 Reminders`, `04 AI / Chat`, and `05 Profile`. Show `04 AI / Insights` as the second retained subview of the AI root. Add supporting frames for Service detail, Add/edit service, Reminder detail/form, Insight review, Budget edit, authentication, loading, empty, error, delete confirmation, and confirmation before any AI-proposed write.

Use named, ordered layers in every frame: `System / status`; `App bar`; `Content / scroll`; `Component / ...`; `Navigation / bottom tabs` on roots only; and `Overlay / ...` for sheets/dialogs. Keep repeated components at the same x position and reuse the same layer names for shared controls. Mark design tokens as color/text styles, and annotate each interactive layer with the CTA destination and Back result from the map above. Add arrow connectors from each primary CTA to its target frame. Keep Android system bars as an annotated safe-area overlay rather than app-owned controls.

**Current Paper state:** `01 Home` and `02 Services` have polished, reviewed content. Artboards `03 Reminders`, `04 AI / Insights`, and `05 Profile` exist but are empty. `04 AI / Chat` has the status bar, Chat/Insights selector, Nemotron label/privacy notice, suggested questions, and sample user question; the sample answer and sources remain unfinished. The AI tab label has been changed across the root navigation; its visual icon replacement and full AI/Insights, Reminders, and Profile content remain unfinished. The Paper MCP refused further writes after reporting its weekly limit; no export was available. Resume canvas work after the limit resets, complete the missing screens, review screenshots, and call `finish_working_on_nodes`.

The Paper design should preserve this information architecture and visual system while allowing layout adjustments for legibility. This specification is a reviewable handoff; it is not an implemented application or a clickable HTML prototype.

## Research and constraints

- Repository evidence: `MainActivity.java`, `main_navigation.xml`, dashboard/placeholder XML, app theme and strings; current app is Java/XML with Material dependency and five root destinations.
- Product/data constraints: [implementation backlog](../implementation-backlog.md) and [database contract](../database.md).
- Visual pattern references: [Coinbase design story](https://www.coinbase.com/en-es/blog/building-economic-freedom-one-pixel-at-a-time) and [Kraken app navigation guide](https://support.kraken.com/articles/360059154531-navigating-the-kraken-app).
- This is a design artifact only. No application code, dependencies, Supabase records or authentication state are changed.
