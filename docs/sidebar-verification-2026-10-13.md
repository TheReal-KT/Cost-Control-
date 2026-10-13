# Sidebar redesign verification

13 October 2026. Branch: `fix/layout-redesign`, based on the latest `chat-history-ui` branch (`eafbc71`). This retains the merged Cole and canonical chat-save fixes before applying the layout changes.

## Executed checks

- Debug application and Android test APK builds passed: `:app:assembleDebug` and `:app:assembleDebugAndroidTest`.
- `:app:testDebugUnitTest`: 16 tests passed.
- `:app:lintDebug`: passed, with 0 errors and 74 warnings.
- Full `AndroidJUnitRunner` run through ADB: 59 cases, 57 passed and 2 conditional assumptions skipped. The skipped cases cover an unconfigured AI endpoint and the opt-in local-AI connection check; the endpoint is configured locally and the live-check opt-in was not enabled.
- All five path geometries in `ic_action_delete.xml` match the supplied `IconTrashCan.svg`. The original rounded 1.5-unit strokes are retained; controls apply the existing supporting-text or danger tint.
- `git diff --check` passed.
- The normal `com.example.agentcostcontrol` APK was installed with `adb install -r`, preserving saved account data. `MainActivity` launched successfully; its authenticated sidebar showed AI and Insights as separate destinations. Local port 8443 forwarding was restored, and the two isolated verification apps were removed after the checks.

The full instrumentation run covers authenticated navigation, signed-out guards, chat drafts and activity recreation, independent navigation/history scrolling, 25-row history pagination, opening a saved conversation from Insights, New chat, Back behavior, delete confirmation and failure recovery, canonical save reconciliation, bounded retries, repository contracts, forms, service branding and local risk checks.

## Environment and evidence

Verification used the Pixel 4a emulator on Android API 36 (x86_64), Android compile/target SDK 37, and Android Studio's JDK 25. A local Gradle init script redirected generated output to the Windows temporary directory to avoid OneDrive locks and used the isolated application ID `com.example.agentcostcontrol.sidebarverification` for instrumentation. The real app's saved account data was preserved.

The final native test command was:

```text
adb -s emulator-5554 shell am instrument -w -r -e additionalTestOutputDir /sdcard/Android/media/com.example.agentcostcontrol.sidebarverification/additional_test_output com.example.agentcostcontrol.sidebarverification.test/androidx.test.runner.AndroidJUnitRunner
```

Screenshots were rendered from the in-memory test account. The [committed sidebar preview](design/previews/sidebar-navigation.png) shows the final AI label and supplied trash icon. Native-run logs and additional chat/compact-window previews remain in the ignored local verification directory.

Physical display rotation was not verified: this emulator stalled or disconnected during rotation. The short-screen test instead resizes the app window to 390 dp high and verifies that the navigation menu and chat history remain separately scrollable. Live provider inference and large-text/TalkBack acceptance are outside these checks.

## PR 9 review repairs

Verified the repository origin and checked-out `fix/layout-redesign` branch against PR 9's head `c4ee595` and base `chat-history-ui` before editing.

- A successful append no longer clears history's dirty flag. If saving a chat is followed by a failed first-page refresh, reopening the sidebar still refreshes page zero, even after older chats were successfully appended.
- Sidebar system-bar/cutout padding now follows its physical start edge: left in LTR, right in RTL. Main-content padding and vertical sidebar insets are preserved.

Both new emulator regressions failed on the original PR code: the saved conversation stayed absent on reopen, and RTL incorrectly retained left-side padding. After the fixes, the chat/auth and sidebar classes completed `OK (23 tests)`: 22 passed and the missing-backend test was skipped because an endpoint is configured. The drawer test supplies unequal physical bar/cutout insets and checks both RTL and LTR; it does not depend on rotating the emulator.

Debug app/test APK builds, all 16 unit tests and lint passed again (0 errors, 74 warnings). The normal debug app was also rebuilt, installed with existing data preserved, and cold-launched successfully. Review logs remain in ignored `.local-demo/pr9-review-baseline.txt` and `.local-demo/pr9-review-fixed.txt`.
