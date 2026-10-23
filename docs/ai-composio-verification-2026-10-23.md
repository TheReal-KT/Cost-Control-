# AI and Composio verification — 23 October 2026

## Passed

- Backend: `uv run --locked python -B -m unittest discover -s tests -v` — 84 tests.
- Backend: `uv run --locked ruff check src tests main.py` and `uv run --locked ruff format --check src tests main.py` — passed.
- Android: `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug` — passed; 16 JVM unit tests, no failures or errors.
- Fresh output directory: `:app:assembleDebug :app:assembleDebugAndroidTest --max-workers=2 --no-configuration-cache` — passed after the previous generated output directory hit Windows file locks.
- Final Android test APK build and lint — passed.
- `scripts/tests/LocalAiStartupTest.ps1` — all startup, install and failure-path checks passed.
- Android instrumentation: six streaming parser checks, eleven Gmail client contract checks, and the real local HTTPS/authentication check passed. The latter rejected an invalid session with HTTP 401 before inference.
- The Gmail-return profile regression passed.
- One live account-free probe through `OpenRouterExplanation.stream_explain` returned `READY` in two streamed chunks using `nvidia/nemotron-3.5-lightning:free`. It used the configured server credential and no account records. This verifies the provider adapter, not the signed-in Android account path.

## Incomplete or failing

- The live Android AI and Gmail checks were skipped because the emulator had no saved session (`NEEDS_SIGN_IN`). No successful live Composio mailbox scan or tool log ID was obtained.
- The combined instrumentation run completed 23 tests with five navigation failures: Espresso reported `RootViewWithoutFocusException` while waiting for the authentication view. A fresh APK and foreground launch did not resolve this; an isolated short-screen navigation check also failed. Changing the Gmail regression's lifecycle transition did not resolve it and was reverted. A subsequent navigation-only run was interrupted during diagnosis and reported a crashed process; it is not a passing run.
- Signed-in AI/Gmail verification remains pending. On 24 October, the user explicitly requested publishing the changes to `main` with that limitation documented.

## Local setup

The configured HTTPS backend was started on port 8443, with emulator port forwarding. The ignored Android `BUILD_OUTPUT_DIR` now points to `.local-demo/verification-build` to avoid locked output files. No credentials, connection links, email contents or local environment files belong in the commit.

After signing in, run the direct instrumentation commands in `backend/README.md`. Gmail sync has a separate `syncGmail` opt-in because it can import real subscriptions. Skipped tests do not establish integration success.

## Emulator recovery — 24 October

The black screen also affected Android Home. Cost Control's activity dump contained the complete sign-in view hierarchy, but its window lacked input focus; SurfaceFlinger retained unfinished transition layers. A guest reboot then encountered a system-server watchdog kill. A fresh emulator process launched with `-avd Pixel_4a -no-snapshot` completed a cold boot without wiping data. Android System UI subsequently displayed an unresponsive-process dialog; closing that process allowed it to restart.

After recovery, the sign-in form was visually confirmed and both Android's input dispatcher and the activity reported application window focus. The local backend remained available on port 8443, and ADB port forwarding was restored. These observations establish emulator recovery, not successful authenticated AI or Gmail calls. Host CPU saturation and low available memory were observed during startup; no unrelated applications were terminated.

Rerunning the installed navigation test APK after recovery completed all six sidebar tests with zero failures and zero skipped tests, including the five checks that failed before the emulator recovery. The committed Gmail-return test uses the same pause/resume transition as this verified APK. The app was relaunched for manual sign-in after the suite.
