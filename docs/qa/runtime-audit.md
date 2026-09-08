# Black Blast Runtime, Algorithm and Product Audit

Date: 2026-09-08. Target: v0.2.0 / versionCode 2, current uncommitted worktree after commit `5dc645f`.

## Executive Summary

**Overall status: NOT READY** for a public release. The existing offline puzzle works through the exercised journeys, including a non-debuggable QA-signed release APK. Reproduced recovery defects and extreme-counter save invalidation have targeted fixes and passing regressions. Performance acceptance, physical-device audio/haptics, older Android runtime coverage, production signing, and real-player experience are not established.

This is an audit of implemented behavior, not a claim of exhaustive correctness or enjoyment. The final full debug sweep passed45 tests with zero failures/skips in665.699 seconds; two release-only checks passed in separate processes. No Git commit, push, store publication, personal-phone operation, uninstall, or app-data reset was performed during this audit.

## Repository and Architecture

| Surface | Source of truth and responsibility | Ownership/lifetime |
|---|---|---|
| Build | Root and module Gradle scripts; Gradle 8.13, AGP 8.7.3, Kotlin 2.0.21 | Java/Kotlin bytecode target 17; build verified with Studio JBR21 |
| Rules | [GameEngine.kt](../../core/src/main/kotlin/com/blackblast/core/GameEngine.kt) | Immutable `GameState`, 64 cell colors, 3 tray slots, bounded score/line/move/combo/charge/undo fields, seeded RNG |
| Progression | [Mastery.kt](../../core/src/main/kotlin/com/blackblast/core/Mastery.kt) | Five ranks and three palettes derived from monotonic personal bests; no cumulative grind currency |
| State | [GameViewModel.kt](../../app/src/main/java/com/blackblast/app/GameViewModel.kt) | Main-thread actions; one cancellable background hint job; revision-tagged save completion; ViewModel scope cancelled at destruction |
| Persistence | [GameStore.kt](../../app/src/main/java/com/blackblast/app/GameStore.kt) | Application-scoped DataStore and ordered asynchronous save queue; separate Flow/Daily JSON/checkpoints, bests and preferences |
| Entry/lifecycle | [MainActivity.kt](../../app/src/main/java/com/blackblast/app/MainActivity.kt) | One exported launcher Activity; system insets; stop pauses; observer removed at composition disposal |
| Input/rendering | [GameScreen.kt](../../app/src/main/java/com/blackblast/app/ui/GameScreen.kt), [GameBoard.kt](../../app/src/main/java/com/blackblast/app/ui/GameBoard.kt) | Native Compose Canvas; local drag/selection/Pulse-arm state; per-frame immutable drag snapshot |
| Audio/effects | Activity `ToneGenerator`, board tween, [MoveCelebration.kt](../../app/src/main/java/com/blackblast/app/ui/MoveCelebration.kt) | Short move/clear tones; haptic preference; tone released on disposal; animations finite and cancellable |
| Screens | Gameplay, loading, blocked-load/Retry, pause/settings, restart confirmation, result, mastery | Dialogs overlay gameplay; no marketing/home screen |
| Assets | 3 Outfit TTFs, original vector launcher, procedural tile art | Fonts and SIL license confirmed inside release APK; no bitmap sprites, external downloads at runtime, music, shaders or audio files |
| Tests/tools | Core JUnit; Android Compose/integration tests; [test-android.ps1](../../scripts/test-android.ps1) | Emulator-only runner; failed and passing reports retained; most stores isolated in cache |
| Generated | `app/build`, `core/build`, root `build`, `artifacts`, `.gradle`, `.kotlin` | Not source; old evidence not deleted; clean-output build used a fresh directory |
| IDE/local | `.idea`, ignored local SDK properties | Machine-local, not production config |

There are no enemies, health/damage, projectiles, pathfinding, continuous movement/physics, levels/stages, servers, authentication, ads, purchases, analytics, social systems or notifications. Those checks are **not applicable**, not passing implementations. Collision here means discrete piece-cell occupancy and board bounds.

Critical path: touch/selection -> ViewModel -> immutable engine result -> UI score/tiles/effect -> ordered local save. Hint computation runs on `Dispatchers.Default`, then applies only if the original state still matches. Restart replaces only the active run; retained best scores are intentional. Undo restores the entire prior state, including the random sequence, and spends the current run's budget.

## Feature and Screen Truth Table

Each row describes scoped runtime evidence. Broader production quality is not implied.

| Feature/screen | Expected and exercised behavior | Tests/evidence | Status |
|---|---|---|---|
| Loading / Retry | No playable fresh state after failed read; Retry serializes requests and restores the exact old run | IoErrorIsolationTest, RecoveryScreenTest; E02 | VERIFIED + REGRESSION TESTED (injected IOException) |
| Flow | Endless 8x8 placement, collision rejection, score, hand refill | GameEngineTest, GameScreenTest; repeated sessions E06 | VERIFIED in tested paths |
| Daily | Date-seeded run, 12-line win, replay and return to Flow | Empty-board route: 35 actions, 12 lines, 2775 points; E04 | VERIFIED for 2026-09-08 route; all daily paths not proven |
| Tap / drag | Legal placements reach visible preview; invalid drop preserves hand | GameScreenTest including real touch input | VERIFIED for tested sizes/gestures |
| Rotation | Four turns restore identity; any legal orientation prevents false game-over | Independent grid oracle and rotation UI test | VERIFIED + REGRESSION TESTED |
| Hint | Greedy legal suggestion; does not auto-place; any visible ghost cell confirms | Differential oracle + real ghost touch + release journey | VERIFIED; not a perfect solver |
| Undo | Three per run, one checkpoint; full state restored; persisted and mode-scoped | TacticalRulesTest, GameStoreTest, GameViewModelTest | VERIFIED in tested sequences |
| Pulse | Charge at six lines; clears occupied cells in clipped 3x3 region | Boundary/reference tests and UI rescue | VERIFIED + REGRESSION TESTED |
| Game-over / retry | No moves dialog, undo rescue when available, clean new run | Actual-app RuntimeJourneyTest; E04/E05 screenshots | VERIFIED |
| Pause / resume | Gameplay blocked; effect cleared; new move gets new effect | LifecycleEffectTest, MainActivityTest, release Activity test | VERIFIED state/UI contract; audible replay not directly recorded |
| Restart confirmation | Cancel preserves run; confirm resets active board/budget only | Actual buttons; 20 rapid taps do not score twice | VERIFIED; ambiguity was a test selector defect |
| Sound / haptics settings | Toggle states persist and restore | Actual settings buttons + save equality | VERIFIED settings; output quality UNVERIFIED |
| Mastery / palettes | Derived ranks, unlock boundaries, locked choices disabled, actual tile colors change | Pixel comparison in GameScreenTest and real dialog | VERIFIED; human motivation UNVERIFIED |
| Effects | Line-clear particles and point celebration, next input remains usable | Animation-clock UI test + screenshots | VERIFIED for tested conditions; reduced-motion/long audio fatigue UNVERIFIED |
| Relaunch / update | Acknowledged progress survives new process and same-signature install update | Non-debuggable release, different PIDs, all 10 top-level persisted fields compared; E10 | VERIFIED acknowledged-save recovery; interrupted write not equivalent |

## State and Invariants

- Loading and load-error have `progress == null`; gameplay/settings/mode/restart mutations return without writing. Retry runs only from load-error and hides the retry action while awaiting I/O.
- Active state accepts placements, Pulse, rotation, hints, undo, mode changes. Pause blocks play/hints and clears transient effect state; settings remain available.
- Daily victory stops placement, rotation, hints, Pulse and undo; restart/mode change remain available. Flow game-over appears only when all rotations fail and Pulse cannot help; undo is a separate recovery action.
- Every legal transition from an accepted extreme state remains within the snapshot validator's score/counter limits after the fix. Ordinary scoring is unchanged; saturated score returns only the points actually added.
- Application-level save queue order is deliberate. It is unbounded; under sustained stalled I/O, backlog growth is a remaining risk, not proven by the short failure tests. No claim of leak-free operation is made.

## Mathematics and DSA

See [algorithms.md](algorithms.md) for models, independent oracles and complexity. The fresh-output core run executed 46 tests: BoundaryIntegrity 5, original engine 8, mathematical gameplay 8, mathematical rules 12, tactical rules 13; zero failures/errors/skips.

Scope includes all 17 shapes and four quarter turns, grid bounds/occupancy, independent row/column union, scoring/combo caps, 3x3 Pulse clipping, rotated-only escapes, seeded hands, exact post-placement 299/300 difficulty boundary, deterministic hint comparison, save validation, undo replay, and 1461-day RNG-state uniqueness. It does not exhaust every possible board or prove probability distributions, joint playability of all three drawn pieces, or optimality of the heuristic.

The three earlier math failures were test-oracle defects, not fixed by weakening the game: 270 degrees confused with identity, a linear 0..8 fixture confused with a 3x3 grid, and occupied positions reused during refill. A later threshold oracle was also corrected to include the 30 placement points before deciding the pool.

## Bugs and Changes

Detailed repro, mechanisms, evidence, fixes and remaining issues: [bug-registry.md](bug-registry.md).

- BB-001: Failed read enabled a fresh run and writes. Loader now keeps progress absent and offers Retry; nine focused Android checks passed with whole-progress comparisons and zero forbidden writes.
- BB-002: Pause retained the last move effect. Clearing the transient effect prevents that state from being eligible for recreation replay; state and new-move regressions passed. Actual emitted sound was not recorded.
- BB-003: Accepted maximum counters became invalid after a legal move. Saturating arithmetic preserves save validity and undo at the existing maximums; failing before XML and passing after XML exist.
- BB-004: Hint scans allocated row/column index lists for incomplete lines. Lists are now created only for completed lines. Correctness remains pinned by the same independent reference tests; allocation measurements improved, latency did not reliably improve.

No game rules were redesigned, no paid/retention features were added, and no dependency upgrades were made merely to silence warnings.

## Performance Findings

**Performance acceptance: UNVERIFIED / concerning measured sample.** Android emulator debug run; instrumentation overhead included. It is incorrect to infer physical-device FPS from these numbers.

| Measurement | Recorded value | Interpretation |
|---|---:|---|
| Bounded endurance | 30 sessions, 90 placements, 177359 ms | Passed state/save assertions; less than three minutes, not an hour soak |
| Frame samples | 3449, first draws excluded | Activity Window FrameMetrics, not all GPU presentation latency |
| Frame median / p95 | 37.34 / 160.70 ms | Slow sample; not a smoothness pass |
| Frames over 16.67 ms | 3342 / 3449 | Includes emulator and test interaction overhead |
| Hint interaction median / p95 | 484 / 838 ms | Touch injection + waiting + rendering, not just search cost |
| Process CPU | 123884 ms over 177359 ms wall | Test and app process combined |
| PSS session 1 -> 30 | 171284 -> 182604 KB | +11320 KB; intermediate fall at session21; no proof of leak or absence |
| Threads session 1 -> 30 | 27 -> 31 | Short sample insufficient for unbounded-growth conclusion |
| Normal release cold launch | 2295 ms | Single normal `am start -W` observation |
| Release startup gfxinfo | 4 frames, 3 janky | Far too few frames for a steady-gameplay distribution |

Fixed-board hint benchmark, 15 warmups + 100 scans per fixture, allocation counters process-wide:

| Fixture | Before p50 / p95 ms | After p50 / p95 ms | Before -> after allocated bytes |
|---|---:|---:|---:|
| Empty | 4.609 / 9.263 | 4.514 / 10.286 | 88932352 -> 50659328 |
| Midgame | 0.536 / 2.714 | 0.486 / 3.137 | 9633792 -> 6094848 |
| Blocked | 0.119 / 0.494 | 0.159 / 1.086 | 950272 -> 884736 |

Keep the narrow allocation reduction, but do not sell it as a latency fix. The blocked fixture never reaches full-line construction; its latency variability is a useful control against attributing every timing change to the patch. A repeatable release-device profiler capture is still required before further optimization.

### Final Sweep Measurement

The final same-workload endurance test in the complete suite ran300475ms:30 sessions,90 placements,2113 measured frames. Median frame duration103.63ms, p95482.50ms,2110 frames over16.67ms; hint interaction median723ms/p951370ms. Process CPU150086ms. PSS183112KB initially, peak186716KB, final182291KB; threads31 ->29. The state/save assertions passed, but frame-time acceptance did not. This is roughly five minutes, not an hour soak, and cannot prove human enjoyment or absence of resource leaks. See [final runtime sample](../../artifacts/qa/final-runtime/runtime-endurance.json).

The final hint microbenchmark still measured about51.1MB allocated per100 empty-board scans, consistent with the allocation reduction. Empty p95 was11.85ms, midgame2.00ms and blocked0.52ms; timing variability across these runs prevents a speedup claim. See [final algorithm sample](../../artifacts/qa/final-runtime/algorithm-timing.json).

## UI, Accessibility and Player Experience

Observed screenshots include portrait gameplay, compact 320x568 at 1.3x text, 680x360 landscape, hints, mastery, pause and both result states. Screenshot/pixel tests prove nonblank rendering and selected control bounds at those configurations. They do not prove every text line, extreme score, font scale, TalkBack traversal or hardware touch response.

The first screen is a playable board, not a landing page. Rotate, hint, undo and mastery use familiar icons plus tooltips/semantic labels. Board semantics name row, column and occupancy. Palette selection is not color-only: names, lock state and radio state accompany swatches. Small-grid cell targets necessarily compete with board size; manual accessibility testing remains necessary.

**First-30-seconds assessment: PARTIALLY VERIFIED.** Launch and first move are exercised; no real new player was observed. There is no onboarding or help screen, so the invisible top-left anchor for manual tap placement and long-press discovery of icon explanations remain plausible confusion risks. The hint ghost fix reduces one observed anchor problem without adding a tutorial.

**First five minutes / first session: UNVERIFIED as human experience.** A scripted Daily route reached 12 lines in 35 actions and about31 seconds with hints. This proves reachability for that date/strategy, not human completion time, fairness, boredom, or an engagement funnel. No fabricated failure-rate or retention percentages are reported.

The actual decision loop is choose a tray block/orientation -> preserve space or pursue a clear -> place -> gain cells/line/combo points -> replenish hand -> repeat or recover. Different placement priorities, limited undo and charged Pulse provide agency. Daily's explicit endpoint and replayable seed provide a finite challenge. Mastery ranks/palettes give visible cosmetic milestones. Free hints can lower challenge for a mastery player; no difficulty rebalance is justified without observed players.

Casual/score/experimentation players are the intended audience. There are no stages, narrative secrets or social systems to audit. Reference principles of autonomy, competence and challenge/skill balance are useful hypotheses, not research findings from this session. Online references requested at developer.android.com and Wikipedia were blocked by workspace network policy. Competitor hands-on comparison and external design research are **BLOCKED**. No claims of an exhaustive competitor review are made.

Voluntary engagement remains the design boundary: no artificial waiting, scarcity, coercive streaks, ads, notifications or grind walls were introduced. Genuine reasons to return are score improvement, a new local-date challenge and learning spatial strategy; whether players value these requires real playtesting.

## Audio and Visual Findings

Short ToneGenerator feedback and native haptic requests exist; settings and effect eligibility are tested, ToneGenerator is released at composition disposal. No background music or layered mixer exists. Native screenshots and animation interaction checks cover visual feedback and cleanup at tested events. Actual audibility, mixing, perceived synchronization, device mute/volume, haptic strength, audio fatigue, and interruptions by other audio apps are **UNVERIFIED**. A state test is not an audio recording.

## Release and Trust Boundaries

Fresh-output debug/release builds and both lint tasks passed. Release lint reports 0 errors and 10 advisory warnings: older target/dependencies, backup extraction guidance and launcher resource/themed-icon guidance. The build also warns that two AndroidX native libraries could not be stripped; they were packaged. No warnings were suppressed.

Actual release APK checks: package `com.blackblast.app`, version2 / 0.2.0, `debuggable=false`, no Internet permission. One signature-level self permission is present. The launcher is exported intentionally; startup provider is non-exported; the AndroidX profile receiver is exported but protected by `android.permission.DUMP`. No app endpoint accepts network input.

The release contains all three Outfit fonts and the license. Pre-audit optimized resources retain the TTF bytes despite renamed APK resource paths. APK sizes observed: debug17610208 bytes, unsigned release11533008 bytes; build variants can differ slightly after recompilation. No R8/resource shrinking is configured; dex/Material icon footprint is a size opportunity, not a security bug by itself. Runtime direct dependencies are AndroidX, Compose and kotlinx.serialization; no arbitrary upgrades were performed. An offline dependency graph is not a CVE audit.

The tested release copy is **QA-signed with the standard Android development key**, not a distributable production signature. APK Signature Schemes v2/v3 verified. Production signing configuration and unsigned release output were not changed.

Tested QA release SHA-256: `EAC23F382F339A1E59767A1C92886CBC32A51D9C138B0EED708AFA9C57CCD9E6`.

The release-only test asserts non-debuggable mode, performs actual controls, compares full progress through Activity recreation, and runs a second instrumentation process comparing all10 persisted fields. PIDs5717 and5921 differ. This is acknowledged-save process-restart proof, not power-loss atomicity, low-memory simulation, uninstall/reinstall or production-key upgrade proof.

## Environment and Limits

| Environment | Scope | Result |
|---|---|---|
| Windows + Studio JBR21 / Gradle8.13 | Fresh-output build, core46, lint debug/release | VERIFIED |
| Android16 API36 x86_64 emulator, SunlineRush_API36, 1080x2400 | Read-only/no-snapshot overlay; real native UI and isolated test stores | Final45-test sweep VERIFIED; no failures/skips |
| Constrained Compose content sizes | 393x780; 320x568 at1.3x text; 680x360 | Automated pixel/bounds coverage; not separate physical devices |
| QA-signed release APK | Real Activity controls and separate-PID save recovery | VERIFIED for exercised paths |
| Android26-35 runtime / physical phone / refresh-rate matrix | No authorized/device matrix executed | UNVERIFIED |

Historical startup ANR was not reproduced in the later successful batches and is not root-cause fixed. No files/logs were cleared to hide it. This mission did not obtain explicit permission to erase pre-existing emulator/player data; uninstall/reset and destructive corruption of live saves were not performed. I/O failure tests use a real cache-backed DataStore wrapped by a controlled fault injector, not actual full disk.

## Final Recommendation

**NOT READY** for public release. Continue with a physical-device release profiler and audio/accessibility pass, longer soak, lower-API testing, production signing/target review and small observed new-player sessions. The repaired save safety and validated existing mechanics support continued local QA; missing evidence must not be converted into a release pass.

Raw evidence and exact command/session references: [evidence-ledger.md](evidence-ledger.md). An independent read-only review confirmed the scoped recovery/release evidence and challenged boundary evidence and performance. Boundary and palette objections were disproved by actual XML and pixel assertions; adverse performance measurements remain openly recorded. No independent approval of the entire production goal was obtained. Audit criteria requiring physical devices, human participants, production credentials or external research remain blocked/unverified.