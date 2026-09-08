# Goal: Black Blast Runtime and Mathematical QA

## User Request

Deeply understand, run, adversarially test, improve, and production-harden the Android-only Black Blast game. Runtime behavior outranks source and documentation. Verify mathematical and algorithmic foundations with independent references, boundaries, generated inputs, measurements, and real gameplay. Reproduce each important defect before fixing; retain before/after and regression evidence. Produce the requested A-P report and exactly one readiness verdict: NOT READY, CONDITIONALLY READY, or READY FOR RELEASE. The user is unavailable and requested autonomous execution.

## Refined Goal

Evaluate the actual current v0.2.0 worktree, not an idealized game or prior success summaries. Inventory every implemented behavior, screen, asset class, state owner, asynchronous operation, and algorithm. Run debug and release-mode checks where feasible, real Android journeys, reference-based math tests, failure recovery, bounded repeated sessions, and performance observations. Fix reproduced defects in small verified iterations; do not redesign the game. Record unverified or blocked areas and withhold a release-ready claim when required evidence or signing authority is missing.

## Acceptance Criteria

- [ ] Establish baseline source/config/test inventory and executed build/runtime evidence before production edits; preserve any original failing reports.
- [ ] Validate all actual algorithms: normalized quarter turns, grid collision/indexing, simultaneous line union, scoring/combo bounds, Pulse geometry, hand generation/RNG/difficulty, hint search, mastery thresholds, touch transforms, animation timing, validated snapshots and undo. Independent oracles must not call the production method under test.
- [ ] Exercise complete native UI paths: loading, Flow/Daily, place/tap/drag/rotate/hint/undo/Pulse, game-over/victory, resume/restart confirmation, mastery/palettes, sound/haptics toggles, background/recreation, and input behind overlays. Retain actual executed test counts and screen evidence.
- [ ] Verify persistence under old JSON, invalid/corrupt data, failed/recovered storage, queued saves, restart/mode changes, and process recreation. Destructive live-data tests require explicit authority or must use a new disposable fixture and be labelled accurately.
- [ ] Measure startup, frame time or Android rendering counters, process memory across repeated sessions, and core/hint latency. State emulator limitations and test duration/count; do not infer physical-device FPS, audio quality, or leak absence.
- [ ] Build release variant; inspect resulting manifest/assets/dependencies. Exercise an actual release-mode APK locally when safely signable with test credentials; distinguish QA-signed release from store production signing. Never publish or invent production keys.
- [ ] Every material production fix has a failing reproduction or observed before evidence, the same passing reproduction after, neighboring regression coverage, and an impact statement.
- [ ] Maintain concise QA knowledge under docs/qa and generated raw artifacts under artifacts/qa. Final A-P report includes features/screens/algorithms/environment matrix, bug registry, journal/evidence, measurements, missing evidence, and exact readiness verdict.
- [ ] Independent Inspector examines final code and actual evidence, runs targeted checks, and returns PASS for this audit goal or FAIL with precise gaps. PASS on the audit does not mean READY FOR RELEASE.

## Scope Boundaries

**In scope:** Existing Android puzzle only; :core and :app; tests/scripts and focused documentation. Reproduce/fix observed rule, gesture, lifecycle, save, UI or release defects. Review all major resource and dependency classes; mark absent systems not applicable (no health, enemies, projectiles, continuous physics, or backend unless newly discovered). Local QA signing can use existing standard development tooling only, without exposing key material or modifying production signing.

**Out of scope:** New game features, architecture rewrites, web app, backend, analytics, ads, purchases, arbitrary dependency upgrades, store publication, Git push, commits, branch changes, personal-phone operations, elevated privileges, secret acquisition, destructive changes to existing AVD/user data. No automatic git commits or squash despite generic Goal agent defaults: no same-turn authorization was given. Preserve all baseline uncommitted edits. User unavailable is not approval to clear data.

## Applicable Project Conventions

**Quality gates:** Windows PowerShell 5.1, workspace C:/Users/91961/black blast. Use `.\gradlew.bat "-Dorg.gradle.java.home=C:/Program Files/Android/Android Studio/jbr" "-Pkotlin.compiler.execution.strategy=in-process" ... --offline --console=plain --max-workers=2`. :core:test, :app:assembleDebug, :app:assembleDebugAndroidTest, :app:lintDebug, :app:assembleRelease, :app:lintRelease. Always quote dotted -P/-D flags. Avoid the previously failing Kotlin daemon. Sync one-shot commands; do not poll/sleep. One owner runs Gradle and device tests at a time.

**Android:** SDK C:/Users/91961/AppData/Local/Android/Sdk; target com.blackblast.app; runner com.blackblast.app.test/androidx.test.runner.AndroidJUnitRunner. Existing `scripts/test-android.ps1` accepts an emulator serial plus class and exact expected count; device-local output and explicit passing statuses are required. Baseline default count was 27; source currently contains additional math tests and possibly Android additions, so recount after reading.

**Style:** Kotlin 2.0.21, AGP8.7.3, Gradle8.13, Java/Kotlin target17, minSdk26/target35. Preserve Compose visual language and version1 JSON compatibility. Use apply_patch for manual edits, no speculative abstractions, no inline comments unless necessary under explicit task request. Do not weaken assertions or discard failing tests. New test fixture names must describe actual geometry; avoid one-letter identifiers.

**Existing evidence:** v0.2.0 previously passed 20 core and 27 Android checks, not this mission's baseline. Historical initial instrumentation startup ANR is unresolved. Current baseline :core:test on 2026-09-08 ran 37 tests and failed 3: MathVerificationTest.fourQuarterTurnsAreTheIdentityAndOrientationsAreDistinct; MathVerificationTest.pulseRequiresFullChargeAndScoresFivePerClearedCell; MathVerificationGameplayTest.refilledHandsBelowTheScoreThresholdUseTheSmallShapePool. Inspect existing XML reports before rerunning. Test source is existing work; correct only with independently proven oracle error. First expected identity compares orientation index3 (270 degrees) to0, assumes every non-square has4 distinct orientations; Pulse fixture fills indices0..8 instead of a3x3 grid; refill fixture reuses occupied anchors after a full hand. Production code must still be checked independently.

**Independent review:** A first consultant request failed with network reset (not evidence). Exploration returned unsupported broad passing claims; do not reuse them as verified results. A different-model critic must inspect high-risk findings and final evidence. The goal file is immutable; status and QA evidence evolve separately.