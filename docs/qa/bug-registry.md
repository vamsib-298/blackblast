# Black Blast QA Bug Registry

Date: 2026-09-08. Severity uses the latest mission scale: P0 critical, P1 major, P2 moderate, P3 minor. Every status is scoped to its stated evidence.

## BB-001: Load Error Exposes Replacement Progress

- Severity/area: P1, persistence and failure recovery.
- Expected: A failed read must not create writable replacement progress over an existing unread save.
- Before: IOException caused `GameViewModel` to install `PlayerProgress()` and enable actions/settings; an ensuing save could replace the durable board. The before test proves fresh progress and forbidden write eligibility, not an observed physical disk outage.
- Reproduction: Seed an isolated DataStore with a nontrivial run; inject read IOException; call settings/game actions after the read fails.
- Evidence: [before Android report](../../artifacts/test-results/android-20260908-131931.txt); [strengthened after report](../../artifacts/test-results/android-20260908-142656.txt).
- Root cause: Recoverable I/O failure was treated as if storage were empty; a valid new in-memory game crossed the persistence boundary before successful loading.
- Fix: Keep progress absent, mark loadError, display Retry. Null-progress guards block all writes. A repeated Retry is ignored while a load is in flight.
- Verification: Full saved progress compared, including both games, tray rotations, RNG, undo, palette, settings and scores; zero writes allowed while blocked; Retry failure/success and 20 repeated requests tested. Actual app Retry UI exercised.
- Regression: IoErrorIsolationTest4, RecoveryScreenTest1, RetryScreenTest3, neighboring GameStore/ViewModel tests.
- Performance impact: No extra work on normal gameplay; no arbitrary wait added.
- Status: VERIFIED + REGRESSION TESTED for controlled IOException. Actual disk exhaustion remains UNVERIFIED.

## BB-002: Paused State Retains Prior Move Effect

- Severity/area: P2, lifecycle and feedback eligibility.
- Expected: Stop/pause should retire prior move feedback before recreation.
- Before: `pause()` set only paused=true; the retained effect ID could trigger `LaunchedEffect` again when composition was recreated.
- Reproduction: Place, observe effect, pause, inspect effect; resume and place another block.
- Evidence: Same [before report](../../artifacts/test-results/android-20260908-131931.txt), [after9 report](../../artifacts/test-results/android-20260908-142656.txt).
- Root cause: Transient event state had the same lifetime as durable ViewModel state, with no pause cleanup.
- Fix: Clear effect when pausing; retain board and score.
- Verification: Effect absent after pause/resume, subsequent move receives a larger event ID; Activity/release lifecycle paths tested separately.
- Regression: LifecycleEffectTest2, MainActivityTest3, ReleaseJourneyTest.
- Performance impact: One field reset; no new background work.
- Status: VERIFIED + REGRESSION TESTED for state contract. Audible replay and hardware haptics are UNVERIFIED, not inferred.

## BB-003: Legal Extreme Moves Become Unloadable

- Severity/area: P2, numeric boundaries and persistence. Not normally reachable in practical play.
- Expected: An accepted save followed by a legal action must still pass snapshot validation.
- Before: At score `Long.MAX_VALUE/2` or counters `Int.MAX_VALUE/2`, placement/Pulse incremented past the validator ceiling; decoding the generated state returned null.
- Reproduction: Accepted max-score state + Single placement; accepted max-moves/lines + line clear; max-score/moves + charged Pulse.
- Evidence: [before3 failures](../../artifacts/qa/boundary-before.xml); [after5 passing XML](../../core/build/test-results/test/TEST-com.blackblast.core.BoundaryIntegrityTest.xml); independent fresh-build XML under `artifacts/qa/fresh-build-1788860464282/core/test-results/test`.
- Root cause: Validation limits and transition arithmetic were inconsistent, even though arithmetic had headroom from machine overflow.
- Fix: Named existing ceilings, saturation at those ceilings, actual score delta returned as points, saturated move comparison in undo eligibility.
- Verification: Same failing cases round-trip; undo restores max-counter state; extreme coordinates reject; ordinary scores/combos independently regression-tested.
- Regression: BoundaryIntegrityTest5 and all46 core tests.
- Performance impact: Constant bounded arithmetic per accepted move.
- Status: VERIFIED + REGRESSION TESTED at core/runtime JVM level; pathological max-score Android text layout remains UNVERIFIED.

## BB-004: Incomplete Lines Allocate Index Lists During Hint Search

- Severity/area: P2, allocation pressure; not presented as a complete frame-time fix.
- Before workload: 15 warmups then100 scans of identical empty/midgame/blocked boards on the same emulator.
- Evidence: [before](../../artifacts/qa/algorithm-before.json), [after](../../artifacts/qa/algorithm-after.json), [benchmark pass](../../artifacts/test-results/android-20260908-145229.txt).
- Root cause: `fullLines()` created16 row/column lists for every candidate board, including incomplete lines that were discarded.
- Fix: Check row/column occupancy first; allocate a list only for a completed line. Order and line semantics unchanged.
- Verification: Same46 core reference/property regressions pass. Empty sample allocations88932352 ->50659328 bytes; midgame9633792 ->6094848. Process-wide counters include background activity.
- Performance impact: Allocation reduction VERIFIED in measured workload. Empty p95 latency9.26 ->10.29ms; not a reliable speed improvement. No smoothness claim.
- Status: VERIFIED allocation improvement; overall latency/jank remains unresolved.

## BB-005: Poor Measured Frame-Time Sample

- Severity/area: P1 risk, performance. Hardware and harness attribution unresolved.
- Expected: Release gameplay responsive at the supported refresh rate on intended devices.
- Actual: Debug emulator30 sessions/90 placements: median37.34ms, p95160.70ms,3342/3449 frames over16.67ms. Normal release startup's4-frame sample also janky but insufficient for steady gameplay.
- Evidence: [endurance](../../artifacts/qa/runtime/runtime-endurance.json), [release startup](../../artifacts/qa/release-gfxinfo-startup.txt).
- Root cause: UNKNOWN beyond measured hint allocation cost. Instrumentation/virtual GPU/host scheduling may contribute; this was not isolated into a renderer defect.
- Fix: No speculative rendering rewrite. Only BB-004 allocation reduction retained.
- Required check: Release-device frame trace, main/render-thread work, GPU timings and sustained gestures with a known refresh rate; compare identical workload.
- Status: PARTIALLY VERIFIED adverse measurements; production performance acceptance UNVERIFIED.

## BB-006: Historical Instrumentation Startup ANR

- Severity/area: P1 reliability risk.
- Reported: Android killed PID4110 for failing to complete instrumentation startup; delayed class loading, zero executed tests.
- Evidence: [historical failed report](../../artifacts/test-results/android-20260908-093105.txt) and repository decision log; normal app launch and subsequent batches succeeded unchanged.
- Attempts: Bounded normal-launch control and smaller class runs; later complete journeys/release launches succeeded. No arbitrary startup delays added.
- Root cause/fix: UNKNOWN / no fabricated fix.
- Status: UNVERIFIED, not reproduced in later passing runs. Keep a cold instrumentation/launch investigation in the release risk list.

## Test-Oracle Defects, Not Product Fixes

| ID | Defect | Proven correction | Evidence |
|---|---|---|---|
| QA-001 | Rotation index3 (270 degrees) treated as identity; all shapes expected4 orientations | Compare four successive turns; expected1/2/4 symmetries | `artifacts/qa/math-before`, TestOracle, core XML |
| QA-002 | Linear indices0..8 described as a3x3 square | Correct row/column fixture indices and9-cell Pulse expectation | Same preserved before/after math reports |
| QA-003 | Refill fixture reused occupied positions in a later hand | Distinct legal anchors and one controlled refill | MathVerificationGameplayTest |
| QA-004 | Pool test started at299 but earned30 before refill | Start269/270, assert resulting299/300, check100 seeds | Exact boundary test passed |
| QA-005 | Two dialogs both expose New run text | Tag actual confirmation `confirm_restart` | [before](../../artifacts/test-results/android-20260908-143704.txt), [after](../../artifacts/test-results/android-20260908-144016.txt) |
| QA-006 | JSON Integer830 compared with in-memory Long830 | Exact long comparison for score fields, all other fields unchanged | [before](../../artifacts/test-results/android-20260908-150217.txt), [after](../../artifacts/test-results/android-20260908-150343.txt) |

No assertions were removed to pass a known product defect. Test-only compile corrections (member import and unsupported click argument) were repaired before runtime use.

## Review Findings Rejected or Bounded

- Claim that boundary after-tests were absent: rejected. Both current and fresh-build BoundaryIntegrity XML contain5 passes, including all original failures.
- Claim palettes were only checked logically: rejected. GameScreenTest captures a board image before/after Arcade selection and asserts an occupied pixel changed while state stayed equal.
- Claim that release defaults to debuggable: rejected by actual APK manifest query (`false`) and release-only runtime assertion.
- Proposed hint-result race after snapshot check: not reproduced; the check and state mutation are on the main thread without a suspension between them. Mode-change cancellation is tested.
- Claim that every latency change proves the allocation patch caused slowdown: not established by one noisy run. All p95 values are disclosed, including a blocked control which does not enter the changed code.