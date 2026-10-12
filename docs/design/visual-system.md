# Cost Control visual system

The Coinbase references inform the hierarchy and rhythm: a dark ground, large financial totals,
compact lists, circular actions and quiet navigation. Cost Control uses its own spending-bar mark,
copy and service imagery. It does not add wallet, biometric or payment flows.

- Ground `#0B0D10`, raised surfaces `#191D24`, outlines `#2A303A`.
- Primary text `#F5F7FA`, supporting text `#A1A9B7`, action blue `#578BFF`.
- Native sans-serif typography, tabular figures, large totals and restrained section headings.
- Service rows use bundled provider marks, not network-loaded images. Known names and explicit
  aliases match after trimming and case/space normalization. Unknown names use a Unicode initial.
- Home previews at most five saved services, ordered by renewal date, with navigation to the full list.
  The preview never inserts example subscriptions into a real account.
- Sign-in, account creation and profile setup share the same visual language. The original spending
  illustration and form groups reveal once on entrance. Animations stop when detached and respect
  Android's disabled-animation setting. Forms remain scrollable for small screens and the keyboard.
- Inputs, auth validation, password persistence protection and server boundaries remain owned by
  their existing components. NVIDIA branding identifies the configured Nemotron model; it does not
  imply that AI can access real account data.

## Brand asset provenance

The `ic_brand_*` VectorDrawables preserve the SVG path data from
[Simple Icons 13.21.0](https://github.com/simple-icons/simple-icons/tree/13.21.0/icons):
Netflix, OpenAI, NVIDIA, Spotify, YouTube, Apple, Amazon Prime, Adobe, Notion, Dropbox, Discord,
GitHub and Canva. The OpenAI blossom identifies
ChatGPT subscriptions. Colours use the provider palette, with white monochrome marks on the dark
ground where appropriate. Dropbox and Discord use lighter palette variants for the dark ground.
Icons are bundled locally with no additional runtime dependency.

The upstream CC0 text is in [brand-icon-license.txt](brand-icon-license.txt). Brand names and marks
belong to their respective owners; see the upstream
[disclaimer](https://github.com/simple-icons/simple-icons/blob/13.21.0/DISCLAIMER.md).

## Local AI setup

Create `backend/.env` from `backend/.env.example`. Place the provider credential only after
`OPENROUTER_API_KEY=` in that ignored server file. Set the public Supabase configuration and a
random `AI_CONVERSATION_SECRET` of at least 32 characters. Do not add a provider key to Android's
`local.properties`, resources or BuildConfig.

From `backend`, run `uv run --locked --env-file .env agentic-subscription-manager`.
Android needs a reachable HTTPS endpoint ending in `/ai/chat` in `AI_ENDPOINT_URL`, then a rebuild.
`/health` confirms configuration presence only; a signed-in synthetic-data chat request is needed
to verify credentials and inference. See [backend setup](../../backend/README.md) for the complete
contract and current prototype limits.
