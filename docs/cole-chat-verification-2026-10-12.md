# Cole chat and Android run verification — 12 October 2026

Branch: `codex/cole-chat-run-fix`, based on `chat-history-ui` (PR #7).

## Changes

- Only the real application has a launcher entry. The debug preview remains internal for UI tests.
- Supplied navigation SVG artwork is represented by Android vectors, including Edit Big for New chat.
- Chat introduces Cole, without renaming the AI navigation destination or model selector. The backend system prompt uses the same identity.
- Repeated Chat/AI headings, header logo, provider notice and visible message speaker tags are removed. Screen readers retain speaker context.
- Keyboard insets keep the composer above the keyboard; bottom navigation hides during chat input. Insights remains accessible from the compact toolbar.
- Streaming text is buffered for 50 ms UI updates, with pending text flushed on completion, failure or Stop. Stale request callbacks are rejected.
- `scripts/Start-LocalAi.ps1` restores local TLS configuration and ADB forwarding before starting the server.
- `scripts/Run-Android.ps1` builds, installs and launches the current real application without the IDE artifact loader.

## Run failure

Android Studio reported an error loading `apk_ide_redirect_file/debug/createDebugApkListingFileRedirect/redirect.txt` after the local build output directory had changed. The redirect, metadata and APK existed, and a fresh Java process using Android Studio's own artifact loader could load them. Gradle sync alone did not resolve the running IDE's failure.

Rebuilt all IDE-style artifacts using:

```powershell
.\gradlew.bat :app:assembleDebug '-Pandroid.injected.invoked.from.ide=true' '-Pandroid.injected.enableStableIds=true' '-Pandroid.injected.build.api=36' '-Pandroid.injected.build.abi=x86_64,arm64-v8a' --rerun-tasks --no-configuration-cache --max-workers=2 --console=plain
```

All 35 tasks executed successfully. The redirect's metadata and referenced APK were checked again after regeneration. The terminal run script subsequently installed the debug APK and received `Status: ok` for `com.example.agentcostcontrol/.MainActivity`; ADB confirmed the real activity was resumed. The developer then retried Android Studio Run and confirmed **“Run works now.”**

This is a local generated-output/IDE-state recovery, not a source compilation defect. No generated redirect, machine-specific path or IDE cache is committed. Recovery and terminal launch instructions are in the README.

## Checks

- Backend: `uv run --locked python -B -m unittest discover -s tests -v` — **54 passed**, including the Cole system-prompt assertion. Ruff lint and formatting checks passed.
- Android JVM: `:app:testDebugUnitTest` — **16 passed**.
- Android lint: `:app:lintDebug` — **passed with warnings, no errors**. Final JVM/lint checks used an ignored init script assigning a fresh external build directory, `--no-configuration-cache --no-daemon --max-workers=2`; all 33 tasks executed successfully.
- Native suite on API 36: **52 passed, zero failures or skips**, using a separate `.uiverify` installation with an empty AI endpoint. Includes Cole greeting, message accessibility, preview launcher isolation, history, forms, repository contracts and stream parsing. No production account session was cleared.
- Local HTTPS/auth: **1 passed**, with the configured endpoint in the isolated app. Direct `adb shell am instrument -w -r -e class com.example.agentcostcontrol.chat.LocalAiConnectionTest -e verifyLocalAi true com.example.agentcostcontrol.uiverify.test/androidx.test.runner.AndroidJUnitRunner` reported `OK (1 test)`. This confirms TLS and rejection of an invalid session before inference, without a model request or account write.
- PowerShell parser accepted both new scripts. `Run-Android.ps1` was also executed successfully against the emulator.
- `git diff --check` passed.

An initial native run exposed a missing saved-state field in the new regression test fixture; it was corrected before the passing run. The endpoint-dependent tests are run separately to avoid treating an assumption skip as a failed connected-test report. An initial AAPT2 process crashed during concurrent builds; the successful native rebuild used two workers. The separate HTTPS run succeeded through Gradle but left a stale XML report, so its result was captured again directly from Android's test runner.

Repeated JVM/lint checks encountered Windows refusal to delete existing generated files. The final checks passed with a fresh external output directory and dedicated Gradle process. No source files were deleted or checks disabled. The isolated test packages were removed afterwards and the real application was resumed successfully.

## Limits

Automated backend tests use mocked upstreams. The native account/repository tests use fictional fixtures. These results do not establish live provider availability or the accuracy of generated financial prose. The real app's saved account data was preserved. The supplied home artwork retains its original long path, which lint flags as a performance warning.

## PR review follow-up — 13 October 2026

Addressed the certificate renewal comment on `Start-LocalAi.ps1`. Startup now initializes TLS, then runs the existing Android installer with `-InstallOnly`, and starts Uvicorn only after successful installation. The incremental install runs on every startup, covering renewal performed by an earlier initialization and retries after a failed installation. The default Android run command still launches the app; install-only preserves its working directory/JAVA_HOME cleanup without launching.

`pwsh -NoProfile -File scripts/tests/LocalAiStartupTest.ps1` passed nine regression cases: unchanged certificate, renewed certificate, missing certificate, installation failure after renewal, retry, initialization failure, install-only, default launch and build failure. PowerShell syntax checks passed for both changed scripts; `git diff --check` passed. Tests use isolated initialization/Gradle/ADB/server stubs and verify initialization → installation → server ordering, matching certificate state, failure propagation and runner environment restoration. No real certificate was rotated or live backend restarted for these checks; the earlier Android/backend test results above were not rerun for this script-only follow-up.
