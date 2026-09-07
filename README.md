# Black Blast

An original, Android-only offline block puzzle built with Kotlin and Jetpack Compose. No web wrapper, account, ads, analytics, or network permission.

## Play

- Drag a block from the tray onto the 8 by 8 board, or select it and tap a board cell. The tapped cell is the block's top-left anchor.
- Complete rows or columns to clear them. Crossing lines clear together.
- Clear lines on consecutive moves to build a combo. The score multiplier caps at 8.
- Clear six lines to charge Pulse. Arm it, then tap a cell to clear its 3 by 3 neighborhood.
- Flow is endless. Daily uses the same seeded sequence for a local calendar date and ends after 12 lines.
- Each mode retains its own board. Scores, settings, remaining blocks, and random state are saved locally.

Pause contains sound, haptics, resume, and a confirmed new-run action. Leaving the Activity pauses play. A full Pulse remains usable when none of the remaining blocks fit.

## Build

Requirements: JDK 17, Android SDK platform 35, and Android SDK build tools. Set `sdk.dir` in the ignored local properties file for your Android SDK. The Gradle wrapper is included.

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-17'
.\gradlew.bat :core:test :app:lintDebug :app:assembleDebug
```

APK: [app/build/outputs/apk/debug/app-debug.apk](app/build/outputs/apk/debug/app-debug.apk)

On this development machine, the JDK is `C:\Users\91961\.jdks\fcp-jdk17\jdk-17.0.19+10`. Gradle also accepts `-Dorg.gradle.java.home` for that path.

Android 8.0 (API 26) or later is required. The current target SDK is 35. The debug build is suitable for local testing, not store distribution.

## Android Tests

Use an emulator explicitly, especially when a personal phone is also connected. These commands do not clear app data or use the test orchestrator.

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
adb -s <emulator-serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <emulator-serial> install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <emulator-serial> shell am instrument -w -r com.blackblast.app.test/androidx.test.runner.AndroidJUnitRunner
```

Require explicit per-test passing status and `OK (N tests)`. An adb exit code alone does not prove the tests passed.

## Structure

- `core`: immutable puzzle rules, deterministic hands, scoring, Pulse, daily challenges, JSON validation, and JUnit tests.
- `app`: native Compose rendering, touch controls, Android lifecycle, and serialized DataStore writes.
- Instrumentation covers real drag/tap actions, line clears, Pulse, mode switching, rendering at multiple sizes, saves, and Activity lifecycle.

## Boundaries

This is a playable first version, not a production-readiness claim. Player enjoyment and long-session balance have not been measured. Physical-device audio and haptics, older Android versions, release signing, and current Play Store target requirements still need release validation. Progress stays on the device; uninstalling the app removes it. An abrupt process termination before an asynchronous save completes can lose the latest move.

Tile art and launcher graphics are original. Outfit fonts are bundled offline under the [SIL Open Font License](FONT-LICENSE.txt); the license is also included in APK assets. This project is not affiliated with Block Blast or its publisher.