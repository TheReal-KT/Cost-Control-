# Core detail and form frames

**Status:** planned fallback specification; the frames have not been built in Paper. Paper MCP returned a weekly quota limit before any artboard was created.

These three 390 × 844 mobile frames extend the root-screen design system in `mobile-navigation-2026-10-06.md`. Use the existing white canvas, navy ink (`#142033`), blue action (`#155EEF`), semantic status colors, Roboto, and shared spacing/type tokens. Include the standard status bar and hide bottom navigation on each task route.

## 06 Service detail

- Use a title without a header back arrow, `Design Studio`, category `Design tools`, and an `Active` status label. Android system Back or its gesture returns to Services with its query, filter, and list position retained.
- Make the money area the visual focus: `R449.00` billed monthly, `R449.00 / month` monthly equivalent, and `ZAR` stated explicitly. Show `Next renewal · 14 Oct 2026` as the next most prominent fact.
- Keep optional provider, plan, usage, and importance metadata in a compact list with quiet dividers. Do not invent values when they are unknown.
- Place `Edit service` and `Add reminder` as the primary and secondary actions above the gesture area. Edit opens the prefilled form; Add reminder returns to this detail on cancel. Put `Delete service` in a lower separated section with its own confirmation.

## 07 Add service form

Use the same form pattern for editing, with the saved values populated and the title changed to `Edit service`. Keep fields scrollable and pin `Save service` and `Cancel` in a bottom action lane. Cancel returns to Services for add or Service detail for edit; a dirty draft asks whether to keep editing or discard it.

Show one realistic validation state: the required service-name field is empty and displays `Enter a service name`; the other entered values remain visible. Include required category (`Design tools`), amount (`449.00`), currency (`ZAR`, three-letter code), billing cycle (`Monthly` / `Annual`), and renewal date (`14 Oct 2026`). Add the derived line `R449.00 / month` beneath the amount. Annual amounts divide by twelve; never combine or convert currencies. Renewal date must not precede the start date. Start date defaults to today under the existing database contract; show that value explicitly and allow editing. Validation belongs to its field and preserves the rest of the draft.

Keep provider and plan name optional. Usage offers `Unknown` / `Low` / `Medium` / `High`; importance offers `Low` / `Medium` / `High` and defaults to Medium. Do not add a notes field without extending the data contract. An unfilled required name alongside the sample values is an illustrative post-save validation state, not a persisted service record.

## 12 Insight review

- Review `Cloud Notes` with the stored reason `You marked your usage as low`. Identify the evidence as self-reported and show its data date/source only when available; keep confidence secondary to evidence.
- Show projected savings only in the subscription currency: `R89 / month` and `up to R1,068 / year`, marked as estimates. The annual figure is the twelve-month projection of the displayed monthly estimate.
- Explain beside the actions: `Your decision is recorded here. Changes to your service happen with the provider.` `Approve idea` records an approved decision and leaves the subscription active; `Dismiss` records an ignored decision. Neither action cancels the service or moves money.
- Android system Back or its gesture returns to the originating Insights list position. Keep the header free of a back arrow. After approval, show the recorded decision and `Your service is unchanged.`

Use named layers in the eventual Paper frames: `System / status`, `App bar`, `Content / scroll`, `Component / ...`, and `Component / actions`. Keep touch actions at least 48 px tall and use text as well as color to communicate validation and status.
