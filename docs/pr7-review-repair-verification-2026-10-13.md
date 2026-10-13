# PR 7 review repair — 13 October 2026

Verified origin `TheReal-KT/Cost-Control-`, PR head `chat-history-ui` at `396e227`, and base `main`. A concurrent task switched the shared checkout, so this repair uses an attached isolated worktree at that same PR revision.

## Save response reconciliation

`save_chat_turn` preserves completed server answers against incomplete retries. Android now uses the canonical `ChatTurn` returned by the repository, applying its answer and completion status only after the existing content-revision and save-attempt checks pass. Changed visible content is rerendered immediately; persisted state and retry controls reflect the canonical result. The repository contract documents this return behavior. No RPC or schema change is needed.

## Pending-state projection

The reviewed revision already reserved 16,000 answer characters for a persisted retry through the default new-turn projection. The remaining mismatch was that it derived a new title from the question instead of counting the existing turn's title. The projection now explicitly includes the saved turn's identifiers, actual title, question and full 16,000-character answer allowance. Unsaved retries continue to count only the additional answer growth, avoiding double counting.

## Regression evidence

Both new emulator tests failed on the original code:

- A save retry returned the server's completed answer, but the visible transcript kept the older partial answer.
- With 33,900 pending characters and an 80-character saved title, a persisted retry began inference although its full footprint exceeded 50,000 characters.

The first test checks the visible canonical answer, completion/persistence flags and removal of pending retry state. The second checks admission rejection and preservation of all failed saves through a save/restore round trip. Tests use an isolated application ID and a non-listening loopback AI endpoint; no live account or model request is needed.

## Completed checks

- `:app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:testDebugUnitTest :app:lintDebug` passed in the isolated checkout; all 16 JVM unit tests passed and lint reported zero errors.
- Full API 36 emulator instrumentation finished `OK (52 tests)`: 50 passed, including both new regressions, and two expected configuration skips (missing endpoint case and opt-in live backend test). The test app uses `.reviewverify`; the normal signed-in app and its data are preserved.
- `git diff --check` passed. The Python backend, database schema, RPC implementation and dependencies were not changed by this repair.
