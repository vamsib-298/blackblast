# Black Blast Evidence Ledger

Date: 2026-09-08. Current audit worktree; no new commit. Runtime: read-only Android16/API36 x86_64 emulator-5584. Earlier reports are historical, not silently reused as the final run.

## Executed Sessions

| ID | Action / expected | Actual result | Artifact |
|---|---|---|---|
| E01 | Baseline controlled load/pause failures |5 tests,3 failed; old fresh-progress/write eligibility and retained effect reproduced | [android-20260908-131931.txt](../../artifacts/test-results/android-20260908-131931.txt) |
| E02 | Same failure contracts strengthened; exact stored state, bounded Retry and real error screen |9 passed, no skips; no forbidden write and complete recovery | [android-20260908-142656.txt](../../artifacts/test-results/android-20260908-142656.txt) |
| E03 | Accepted numeric limits followed by legal actions | Before3 failures; after5 boundary checks pass, plus threshold test | [boundary-before.xml](../../artifacts/qa/boundary-before.xml); current/fresh core XML |
| E04 | Empty Daily, game-over undo/retry, restart/settings/rapid taps | Daily35 actions/12lines/2775 passed; loss recovery passed; restart selector ambiguity failed | [android-20260908-143704.txt](../../artifacts/test-results/android-20260908-143704.txt), [route](../../artifacts/qa/runtime/daily-route.json) |
| E05 | Repeat exact restart/settings path with stable confirmation selector |1 passed;20 rapid taps yield1 move/10points | [android-20260908-144016.txt](../../artifacts/test-results/android-20260908-144016.txt) |
| E06 |30 sessions,90 placements, repeated pause/restart, final save equality |1 passed;177359ms measured workload with frame/memory/CPU evidence | [android-20260908-144341.txt](../../artifacts/test-results/android-20260908-144341.txt), [metrics](../../artifacts/qa/runtime/runtime-endurance.json) |
| E07 | Fixed-board hint benchmark before allocation change |1 passed;100 measured calls per fixture | [android-20260908-144716.txt](../../artifacts/test-results/android-20260908-144716.txt), [before](../../artifacts/qa/algorithm-before.json) |
| E08 | Same benchmark after narrow allocation patch |1 passed; allocation reduction, no reliable latency improvement | [android-20260908-145229.txt](../../artifacts/test-results/android-20260908-145229.txt), [after](../../artifacts/qa/algorithm-after.json) |
| E09 | Non-debuggable QA-signed release controls, Activity recreation, pause/resume |1 passed; full in-memory progress equality | [android-20260908-150159.txt](../../artifacts/test-results/android-20260908-150159.txt) |
| E10 | Different process restores all10 fields from acknowledged release save | First oracle failed on Integer/Long type only; corrected exact numeric assertion passed, PIDs5717/5921 | [before](../../artifacts/test-results/android-20260908-150217.txt), [after](../../artifacts/test-results/android-20260908-150343.txt), [PID evidence](../../artifacts/qa/release-process-restart.json) |
| E11 | Fresh-output core tests, debug/release APKs, both lint tasks | BUILD SUCCESSFUL5m11s;101 tasks executed; no deletion of older outputs | [fresh-build.txt](../../artifacts/qa/fresh-build.txt), `artifacts/qa/fresh-build-1788860464282` |
| E12 | Complete final debug sweep on fresh-built APK |45 passed, zero failures/skips,665.699 seconds | [android-20260908-152906.txt](../../artifacts/test-results/android-20260908-152906.txt) |

Core fresh-build XML totals:5+8+8+12+13=46; zero failures/errors/skips. These are JUnit JVM checks, not Android hardware claims. Source/config checks found no editor diagnostics. Debug and release lint ran, not merely compiled.

Final runtime outputs were copied separately to `artifacts/qa/final-runtime`; they do not replace E06-E08 before/after artifacts. The final30-session sample ran300475ms, with frame median103.63ms/p95482.50ms and PSS183112 ->182291KB. UI correctness assertions passed; performance remains unacceptable as a release gate without further isolation. Final fixed-board hint allocation counters were consistent with the allocation reduction; no latency gain claimed.

## Build and Test Commands

PowerShell5.1, workspace root:

```powershell
.\gradlew.bat "-Dorg.gradle.java.home=C:/Program Files/Android/Android Studio/jbr" "-Pkotlin.compiler.execution.strategy=in-process" :core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:lintDebug :app:lintRelease --offline --console=plain --max-workers=2
.\gradlew.bat "-Dorg.gradle.java.home=C:/Program Files/Android/Android Studio/jbr" "-Pkotlin.compiler.execution.strategy=in-process" --init-script .goals/runtime-math-qa/fresh-build.init.gradle :core:test :app:assembleDebug :app:assembleRelease :app:lintDebug :app:lintRelease --offline --console=plain --max-workers=2
.\scripts\test-android.ps1 -Serial emulator-5584
```

Scoped invocations use `-TestClass com.blackblast.app.ClassName` (or `ClassName#method`) and an exact `-ExpectedTests` count. The script retains device-local output then requires positive per-test statuses, no skips/failures and the matching `OK (N tests)` summary. Exit code alone is not the oracle.

Release checks must be separate processes, after installing a non-debuggable QA-signed release with the same test certificate:

```powershell
.\scripts\test-android.ps1 -Serial emulator-5584 -TestClass 'com.blackblast.app.ReleaseJourneyTest#nonDebuggableReleasePlaysSavesAndSurvivesActivityRecreation' -ExpectedTests 1
.\scripts\test-android.ps1 -Serial emulator-5584 -TestClass 'com.blackblast.app.ReleaseJourneyTest#processRestartRestoresThePreviouslyAcknowledgedReleaseSave' -ExpectedTests 1
```

The first method operates on the emulator's preserved QA run; other failure/journey tests create isolated cache DataStores. Do not run the release class as one invocation: its second test intentionally requires a new process and prior checkpoint. Debug default runner and Gradle instrumentation exclude the release-only class.

## Release Artifact Checks

- `apkanalyzer apk summary`: com.blackblast.app /2 /0.2.0.
- `apkanalyzer manifest debuggable`: false.
- `apkanalyzer manifest permissions`: only the app's signature-level dynamic receiver permission; no Internet permission.
- `apksigner verify --verbose --print-certs`: v2/v3 verification true; certificate CN=Android Debug. Local QA only, no production key.
- QA-signed SHA256: `EAC23F382F339A1E59767A1C92886CBC32A51D9C138B0EED708AFA9C57CCD9E6`.
- Unsigned artifact SHA256 at signing time: `D6FB0E28A46DE259D402B8CF77EA7829EE151FBE53EDF30F70B5D893AF76D8DE`.
- Fonts in APK:54912,55492,55308 bytes; license4389 bytes. Resource names optimized but font bytes present.
- Normal release relaunch after instrumentation: COLD, TotalTime2295ms. [Startup gfxinfo](../../artifacts/qa/release-gfxinfo-startup.txt) contains only4 frames; not a steady-state sample.
- [Normal release hierarchy](../../artifacts/qa/release-relaunch-ui.xml) retained. It supplements, not replaces, the complete separate-process state check.

## Visual Evidence

Current actual-app screenshots under `artifacts/qa/runtime`: [Daily victory](../../artifacts/qa/runtime/daily-complete.png), [game-over with undo](../../artifacts/qa/runtime/game-over-with-undo.png), [pause/settings](../../artifacts/qa/runtime/pause-controls.png), [endurance board](../../artifacts/qa/runtime/endurance-final-board.png).

Earlier v0.2.0 screenshot baseline remains under `artifacts/upgrade-screenshots`; final UI-test screenshots are retained separately under `artifacts/qa/final-screenshots`. Screenshots are rendered native app pixels, not mockups.

## Boundaries and Blocked Checks

- Physical audio output, haptic strength, other-audio interaction, low-memory pressure, power loss during writes and pre-API36 devices: UNVERIFIED.
- User consent for destructive existing-data tests was not supplied; preserved AVD and user data were not cleared. Fresh fixture, acknowledged process restart and same-signature update are tested; uninstall/reinstall is not.
- Longest measured repeated-session sample here is300475ms, not an hour. The full45-test suite took665.699seconds, which includes many different tests rather than uninterrupted gameplay. Human first-five-minute/return-session enjoyment and retention have no participant evidence.
- External documentation/research fetches to Android docs and Wikipedia were blocked by network domain policy. No bypass attempted; no live competitor research claimed.
- Production signing/key ownership/store release, rollback under a production certificate, Play Store target compliance: BLOCKED/UNVERIFIED. Local debug-key signing is explicitly not a production artifact.
- Historical startup ANR remains unexplained; successful later runs do not erase it.