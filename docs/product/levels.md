# Integrated Flow and Daily Stages: 0.5.0

## Current Rules (Supersede 0.4.0)

The user corrected the product structure: stages belong inside Flow and Daily, never a third selectable mode. Both now show a level objective, move budget and earned stage points. `GameMode.LEVELS` and its old entries remain only for decoding/migration; the production selector exposes Flow and Daily and the ViewModel rejects selecting LEVELS.

Flow has unlimited attempts and permits replaying unlocked stages. Daily has three new stage attempts per local calendar day. Three is the stated initial assumption because the user requested a limit without supplying a number and was unavailable to choose. No paid recovery, ads or notifications were added; exhausted Daily players can continue in Flow.

Daily's stage target is one extra line (line tasks) or 200 extra board-score points (score tasks), with the same move allowance. Its completion reward is three times the equivalent Flow result. Raw board score is an attempt metric, not repeat-farmable completion currency. Flow completion points are `(levelNumber * 40 + stars * 20 + unusedMoves * 5)`; Daily multiplies this by three. Stars retain the two-thirds/five-sixths move thresholds. Best records are idempotent, and equal/worse retries award nothing new.

No legal placement in any rotation means game-over, even if Pulse is charged or undos remain. The goal on the last permitted move is still a win. Failed attempts do not unlock Next. Retry recreates the current stage (same seed within a Daily date), and the completion message/effects occur before the Next action. Completion of Level 30 returns to the map; Flow's completed stages remain replayable indefinitely.

The first successful placement/Pulse starts a Daily attempt; invalid moves, hints, rotation, opening/resuming and mode switches do not consume one. Pausing/resuming an active attempt never charges again. Undo can restore a move during a live stage but never refunds the attempt or undoes a terminal result. Starting a new stage or retry requires remaining attempts; a third active attempt can still finish. On a newer date the allowance resets, but unlocked stages and completion points remain. A completed result can still be viewed before Next; a failed/in-progress attempt restarts the same stage with the new date's deal. Backward local dates do not refill allowance. Server-grade clock-tampering protection is out of scope for this offline app.

## Migration and Safety

New namespaced preference keys store Flow/Daily stage runs, separate stage records, Daily attempts, lifetime Daily points and legacy level credit. The existing Flow/Daily endless JSON entries are not overwritten; their best scores survive, but they do not remain an endless-mode bypass. A valid old Levels run/records migrates to Flow, and the difference from the lower Flow reward scale is retained as legacy credit. Existing palette/settings data remains intact.

Boards, attempt state, rewards and checkpoints are written in one DataStore transaction. Stale lower used-attempt counts and older dates cannot replace the saved Daily ledger. Invalid attempt data fails closed for that day instead of granting a fresh allowance. The existing IOException-blocked Retry flow remains in use. A process exit before an asynchronous save is acknowledged can still lose that unacknowledged action; no stronger guarantee is claimed.

## Current Verification

- [Integrated progress checks](../../artifacts/test-results/android-20260908-174221.txt): seven Android tests passed for fresh Flow default, migration/credit preservation, ten Flow retries, three Daily attempts with reload/mode changes, next-day progression preservation, idempotent rewards and stale-save protection.
- [Stage/UI/legacy-store batch](../../artifacts/test-results/android-20260908-180113.txt): 33 of 34 passed. The final confetti full-image equality failed once; the unchanged-animation diagnostic [rerun](../../artifacts/test-results/android-20260908-180505.txt) passed. The intermittent result is retained, not erased.
- [Input/recovery/lifecycle batch](../../artifacts/test-results/android-20260908-180934.txt): 40 tests passed with zero failures/skips.
- Core IntegratedStagesTest checks 60 Flow/Daily stage combinations for 2026-09-08. All have a demonstrated winning route using bounded legal hint/Pulse strategies after Daily29's authored seed was adjusted by one. Its 14-line/43-move goal was not lowered. [Before evidence](../../artifacts/qa/integrated-stages-route-before.xml) is retained. This does not prove every date/strategy, human difficulty, optimality or enjoyment.
- Version 0.5.0 passed all 66 core tests, debug/release APK builds, and both lint tasks. The full 79-test Android sweep was started, but no final host report was saved; its terminal is no longer available and emulator-5584 is disconnected. That full-suite result remains UNVERIFIED and must be rerun before claiming a complete Android regression pass.
- Confetti/audio pattern coverage is not physical-speaker or device-haptic evidence.

The following sections retain the **historical 0.4.0 design and test evidence**. Its separate-mode/new-player defaults and original reward formula no longer describe current gameplay.

## Historical Requested Experience (0.4.0)

The player receives a bounded task, retries the same stage when it is not completed, sees an explicit completion message on success, and chooses the next unlocked stage. Difficulty and points grow with the campaign; visual/audio feedback marks important results. There are no paid retries, lives, forced waits or notifications.

## Rules

Thirty stages live in [Levels.kt](../../core/src/main/kotlin/com/blackblast/core/Levels.kt). Each defines a number, chapter, objective type and target, move limit, starting block count, shape pool, and seed. Chapters contain five stages. Score objectives alternate with line objectives; later chapters increase targets, reduce the line-target-to-move allowance and add starting occupancy/larger shapes. Difficulty is not claimed to be strictly monotonic for every player's strategy.

- A successful block placement spends one move. Pulse also spends one move. Invalid placements, rotation, hints and time spent thinking spend none.
- The objective is checked before exhaustion: achieving the target on the last move is a win.
- Failure cannot unlock another stage. Retry reconstructs the exact authored board, tray and random sequence for that stage. Both successful and failed attempts remain visible until the player acts.
- Undo is available during a live attempt and restores the spent move using the existing three-use budget. Once the level has failed or succeeded, undo cannot undo the result; the player retries instead.
- `nextLevel()` only accepts a completed active level and never creates Level 31. The map permits only already-unlocked levels. Replacing an unfinished attempt from the map requests confirmation.

## Stars and Rewards

Three stars require completing within two-thirds of the move allowance; two within five-sixths; otherwise a successful completion earns one. Level points equal `level * 100 + stars * 50 + unusedMoves * 20`. Gameplay score is separate and retains the existing placement/combo formula.

The campaign contains one best record per contiguous completed level. Replaying the same result is idempotent. Higher score and fewer moves can improve that record, but points are derived from best moves rather than incremented each completion. Total points and stars are derived from the records, not independently mutable counters. Old mastery palette unlocks still use Flow/Daily bests and are not reset by campaign progress.

## Persistence and State

`GameMode.LEVELS` is appended; `GameState.levelNumber` defaults to zero for old saved games. Snapshot version stays one. Campaign JSON, current level snapshot, and level undo checkpoint use separate DataStore preference keys, written in the same transaction as other progress. Monotonic campaign merging protects acknowledged records from a stale later save.

Missing campaign data gives a fresh Level 1. Malformed campaign/current-level data is recovered independently; Flow and Daily remain intact and a recovery message is shown. This is not a remote anti-cheat system. As before, a process exit before an asynchronous save is acknowledged can lose the latest unacknowledged action.

New storage defaults to Levels. Existing saved active mode is preserved. Completing a stage records the result immediately but does not change the current level until Next is chosen. Retry/Next cancel pending hints and clear transient feedback.

## Visual and Audio Feedback

The native HUD shows level number, exact task, progress and moves left; the last three moves use a caution color. The map includes stage numbers even when locked. Results show `Level N completed`, stars, score, used moves, newly earned/best-retained points and the next/retry action. Level 30 shows campaign completion.

A completion burst draws 36 finite particles over 1400ms. Next remains available during the effect once saving completes. Audio uses Android ToneGenerator with bounded patterns for placement, clear, multi-clear, Pulse, completion and failure. `GameFeedback` consumes each move ID once and stops playback when its effect is cancelled, settings change, Activity pauses/backgrounds or composition is disposed. Pattern tests are not proof of actual speaker quality; physical-device listening and vibration validation remain required.

## Verification Evidence

- All 30 levels have a deterministic successful route using the existing legal-move hint strategy plus charged Pulse under the tested policy. Level 28's first draft seed failed that policy at 14 moves; its seed was curated once while keeping the 3850-point /44-move objective. The final route test passes for every shipped stage. This proves a route exists, not optimal play or human enjoyment.
- Core tests cover deterministic retries, last-move success, move-limit failure despite remaining space/Pulse, disabled terminal actions, undo, score targets, malformed snapshots and sequential/idempotent records.
- [Campaign persistence run](../../artifacts/test-results/android-20260908-165139.txt): eight checks passed, including no skip/duplicate reward, old saves, mode separation, corrupt campaign isolation and retry cancellation.
- [Real level journeys](../../artifacts/test-results/android-20260908-165433.txt): eight checks passed, including Level 1 completion before Next, a replay without extra rewards, budget failure/retry, locked map, Level 30 ending, pause/mode preservation and compact large-text HUD.
- [Existing save regression run](../../artifacts/test-results/android-20260908-165516.txt): seventeen checks passed.
- Added final checks for failed reward-write recovery and actual confetti pixels/settling are pending the final APK run. Full legacy UI/endurance coverage will also be rerun; no earlier failure is erased.

Screenshots from those runs are in `artifacts/qa/level-screenshots`. The earlier v0.3.0 full run [android-20260908-162801.txt](../../artifacts/test-results/android-20260908-162801.txt) had a PixelCopy screenshot timeout after endurance assertions; it is retained as a failed run, not converted into a pass.

## Remaining Research

The user requested interest and replayability, not coercion. The working hypothesis is that visible goals, finite fair attempts, short recovery and earned progression support careful play. No `200x` engagement or retention multiplier is claimed. New-player observations, physical-device audio/performance, broader screen/OS coverage and long sessions are still needed. The historical [runtime audit](../qa/runtime-audit.md) is evidence of its own build, not certification of this new mode.