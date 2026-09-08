# Black Blast

An original, Android-only offline block puzzle built with Kotlin and Jetpack Compose. No web wrapper, account, ads, analytics, or network permission.

## Flow and Daily Stages (0.5.0)

**Levels are built into Flow and Daily. There is no separate Levels tab.** New players start at Flow Level 1.

| Mode | Stage attempts | Difficulty | Stage points |
|---|---|---|---|
| Flow | Unlimited retries; completed stages can be replayed | 30 increasing stages with a goal and move budget | Lower completion rewards |
| Daily | 3 new attempts per local day | One extra required line or 200 extra score, with the same move budget | 3 times the equivalent Flow completion reward |

- Each stage has a line-clear or score objective and a finite move budget. Thinking, pausing, rotation and hints do not spend moves; accepted placements and Pulse do.
- No remaining legal placement, or no remaining moves without meeting the goal, ends the attempt. A blocked board is game-over even with Pulse charge or undo available. Retry recreates the same stage; failure awards no stage points and unlocks nothing.
- Completing the goal on the last allowed move wins. **Level N completed** appears with stars, reward, confetti and a sound cue before the player chooses **Next level**.
- Flow can be retried any number of times. Later chapters raise objectives, crowd the starting board, introduce larger shapes and tighten the relative move allowance.
- A Daily attempt is charged at its first accepted placement/Pulse, not when opening, resuming, viewing a hint or making an invalid move. An active third attempt may still be finished; after it ends, Daily stays locked until the next day. Flow remains available.
- Daily's allowance resets on a newer local date, but its stage progress and earned points stay. A backward date change does not refill the saved allowance. This offline rule is not a server-backed anti-cheat system.
- Stage points are awarded for completing stages, separately from the board's attempt score. Equal or worse replays do not farm rewards; an improved star/move result adds only the improvement. Flow and Daily have separate progress and point totals.
- The map allows Flow replays of unlocked stages. Daily advances sequentially through completion; its map cannot be used to select a different board to avoid an attempt limit.
- After completing all 30 Flow stages, every stage remains replayable without an attempt limit. There is no automatic jump to a nonexistent Level 31.

Existing standalone-level progress migrates to Flow; previously earned level points are retained as credit. Old endless Flow/Daily saves remain archived in their original local entries and their best scores are retained, while play uses the integrated stages. No app data needs clearing. See the [stage rules and verification notes](docs/product/levels.md).

## Play

- Drag a block from the tray onto the 8 by 8 board, or select it and tap a board cell. The tapped cell is the block's top-left anchor.
- Complete rows or columns to clear them. Crossing lines clear together.
- Clear lines on consecutive moves to build a combo. The score multiplier caps at 8.
- Clear six lines to charge Pulse. Arm it, then tap a cell to clear its 3 by 3 neighborhood.
- Clear the active stage objective before its moves run out. Flow retries are unlimited; Daily uses a date-seeded deal and a daily attempt allowance.
- Each mode retains its own board. Scores, settings, remaining blocks, and random state are saved locally.

Pause contains sound, haptics, resume and a confirmed stage retry. Leaving the Activity pauses play. Pulse must be used before the board is blocked; game-over requires a retry, not an undo or Pulse rescue.

## Player Experience Update (0.3.0)

- Fast drops use the latest finger position, even when a render frame has not caught up.
- While dragging or viewing a hint, the score row shows the points and completed lines before committing the move. Forecasts share the placement calculation and do not change the board, tray, random sequence or save.
- Blocked placements show a coral outline and a labeled "No space" indicator; releasing there does not consume the block.
- Arm Pulse and hold a board cell to preview its affected area. Release to activate, or move away to cancel without spending charge.
- Both modes now show progress toward their current stage target.
- Raw finger movement is handled in drawing, with composition driven by destination-cell changes. In the fixed Android experiment, 30 movements within one cell caused 30 composition changes before and zero after. This is not a claim of 200x speed or physical-device FPS.

This update adds no saved fields and changes no score rules or palette unlock thresholds. The [player-experience research notes](docs/product/player-experience-research.md) distinguish measured behavior from untested player-experience hypotheses.

## Tactical Upgrade (0.2.0)

- **Rotate:** select a block, then use the clockwise-arrow icon. Rotation is free and the game checks all four orientations before declaring there are no moves.
- **Undo:** the back-arrow restores the last placement or Pulse, including its score, cleared cells, tray, charge, and random sequence. Each run has three undos, preserved across app restarts. Only one checkpoint is kept; place again before another undo. Completed Daily challenges are final.
- **Hint:** the lightbulb suggests a legal placement, rotates the selected block if necessary, and shows a ghost on the board. You decide whether to place it. Hints prioritize immediate clears; they do not solve the entire game.
- **Mastery:** the medal opens ranks and tile palettes. Your highest personal best across Flow and Daily determines the rank. Arcade unlocks at 1,000 and Aurora at 3,000. Earned ranks and palettes do not disappear after undo.
- Line clears now show a brief points celebration without blocking the next move. Compact screens include Pulse in the icon toolbar.

Existing version-one saves remain compatible. The new fields default to unrotated blocks and three undos. Flow and Daily keep separate undo checkpoints; restarting one does not reset the other. An invalid checkpoint is discarded without replacing a valid board.

## Build

Requirements: JDK 17 or Android Studio's bundled JDK 21, Android SDK platform 35, and Android SDK build tools. Java and Kotlin both target JVM 17. Set `sdk.dir` in the ignored local properties file for your Android SDK. The Gradle wrapper is included.

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-17'
.\gradlew.bat :core:test :app:lintDebug :app:assembleDebug
```

APK: [app/build/outputs/apk/debug/app-debug.apk](app/build/outputs/apk/debug/app-debug.apk)

On this development machine, the JDK is `C:\Users\91961\.jdks\fcp-jdk17\jdk-17.0.19+10`. Gradle also accepts `-Dorg.gradle.java.home` for that path.

Android Studio can keep its bundled JDK at `C:\Program Files\Android\Android Studio\jbr`. Sync the root project, select the `app` module and Default Activity, and run it. Keep "Clear app storage before deployment" disabled. If the Kotlin compiler daemon cannot connect on Windows, add the quoted Gradle argument `"-Pkotlin.compiler.execution.strategy=in-process"`.

Android 8.0 (API 26) or later is required. The current target SDK is 35. The debug build is suitable for local testing, not store distribution.

## Android Tests

Use an emulator explicitly, especially when a personal phone is also connected. These commands do not clear app data or use the test orchestrator.

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
adb -s <emulator-serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <emulator-serial> install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
.\scripts\test-android.ps1 -Serial <emulator-serial>
```

The runner accepts emulator serials only, retains a device-local report, and requires all 79 debug-applicable tests to pass with no failures or skips. Reports are written to the ignored `artifacts/test-results` directory. An adb exit code alone does not prove the tests passed. Release-only tests are excluded from default debug instrumentation and must run separately against a non-debuggable QA-signed release.

For a smaller batch, provide `-TestClass` and `-ExpectedTests`: `GameScreenTest` has 20 tests, `GameStoreTest` has 6, `GameViewModelTest` has 5, `MainActivityTest` has 3, `CampaignProgressTest` has 9, `LevelJourneyTest` has 12, and `IntegratedStageProgressTest` has 7. Prefix each class with `com.blackblast.app.`. Storage and ViewModel tests use isolated cache files, not the player's saved game.

### Verification

The previous v0.2.0 audit passed 46 core tests, 45 Android tests in one final sweep, two separate release-process checks, fresh-output debug/release builds, and both lint tasks. Those historical results do not certify every later build. Captured-pixel and bounds checks cover 393 by 780 portrait, 320 by 568 with 1.3x text, and 680 by 360 landscape content areas. Palette tests verify that selecting an unlocked palette changes actual rendered tile pixels.

**Public release status: NOT READY.** Emulator frame-time samples were poor; physical-device performance, audio/haptics, older Android runtime compatibility, hour-long endurance, production signing and real-player engagement remain unverified. See the [runtime audit](docs/qa/runtime-audit.md), [bug registry](docs/qa/bug-registry.md), and [evidence ledger](docs/qa/evidence-ledger.md).

The audit fixed failed-load overwrite risk (blocked gameplay with Retry), stale feedback state on pause, and extreme numeric transitions that invalidated saves. Hint-search line allocations were reduced with independent algorithm regressions; a reliable latency gain was not established. Existing ordinary gameplay/scoring rules and version-one save compatibility remain unchanged.

One initial instrumentation launch ended in a startup ANR before running any tests. Normal app startup and subsequent unchanged-APK test batches passed; the cause of that initial timeout is still unknown. The failed report is retained, not counted as a passing run.

## Structure

- `core`: immutable puzzle rules, rotation geometry, undo, legal-move hints, mastery thresholds, deterministic hands, JSON validation, and JUnit tests.
- `app`: native Compose rendering, earned palettes, tactical controls, Android lifecycle, and serialized DataStore writes.
- Instrumentation covers real drag/tap actions, rotations, undo, hints, palette locks and rendering, clear effects, mode switching, old saves, and Activity lifecycle.

## Boundaries

This is a playable first version, not a production-readiness claim. Player enjoyment and long-session balance have not been measured. Physical-device audio and haptics, older Android versions, release signing, and current Play Store target requirements still need release validation. Progress stays on the device; uninstalling the app removes it. An abrupt process termination before an asynchronous save completes can lose the latest move.

Tile art and launcher graphics are original. Outfit fonts are bundled offline under the [SIL Open Font License](FONT-LICENSE.txt); the license is also included in APK assets. This project is not affiliated with Block Blast or its publisher.