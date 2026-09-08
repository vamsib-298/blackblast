# Black Blast — Core and UI Mathematical Algorithm Inventory
**Date:** 2026-09-08 | **Slice:** runtime-math-qa iteration 1

## Audit Update

The inventory below records the initial mathematical slice. Later evidence supersedes its historical gap/status statements: the fresh-output suite has46 passing core tests, the final Android sweep45 passing tests, and2 separately executed release checks. See [runtime-audit.md](runtime-audit.md), [evidence-ledger.md](evidence-ledger.md), and [bug-registry.md](bug-registry.md) for current scope and limitations.

BoundaryIntegrityTest reproduces3 original numeric save failures and verifies the saturation fix, including undo. Accepted score/counter limits are now preserved by moves. A1461-day test compares actual RNG states, not merely date labels. That proves uniqueness in the tested window, not every representable date or a probability distribution. Final runtime includes real hint/drag/rotation/Pulse/palette interactions; broader device coverage is still unverified.

The hint implementation enumerates candidates rather than using a best-first priority queue. Generalized complexity for board side N and piece size K: rotation precompute O(K log K) including sorting (bounded17x4 shapes); placement O(K); line detection O(N squared); hint O(tray * rotations * N squared * (K + N squared)), with bounded board copies. Full-line detection now allocates index lists only for complete lines. Measured allocations fell; frame-time/latency acceptance is not established.

The exact difficulty transition uses the score AFTER the final placement. The corrected test starts269/270, adds30, asserts299/300, and checks100 seeds. The older description below meant the refill-time score; starting299 before those placements would not test the small pool.

---

## 1. Rotation Geometry

**Input:** `Shape` (list of `Cell(row, col)` pairs, normalized to origin), `rotation ∈ 0..3`  
**Output:** Normalized `Shape` for that orientation  
**Model:** Clockwise 90° transform `(r, c) → (c, height − 1 − r)`, normalized to (0,0)  
**Complexity:** O(cells) per rotation; 4 orientations precomputed per shape at startup  
**Bounds:** All cell coordinates remain in `[0, BOARD_SIZE)` after normalization (BOARD_SIZE = 8)  
**Implementation:** `Shapes.orientations[shapeId][rotation]`  
**Test evidence (VERIFIED):**  
- `everyOrientationMatchesTheIndependentGridReferenceRotation` — all 17 shapes × 4 rotations match `referenceRotate` oracle  
- `fourQuarterTurnsAreTheIdentityAndOrientationsAreDistinct` — `referenceRotate(orientations[shapeId][3])` == base; distinct count matches independent chain  
- `rotationsStayInsideTheBoardAndRenderingBounds` — all cells within bounds  
- `rotatedOnlyFitDetectedByRefCanPlace` — board with all even cols filled blocks horizontal domino; vertical rotation still fits  
**Status:** VERIFIED; FIXED iteration 1 (oracle incorrectly expected 4 distinct orientations for all shapes; many shapes have 1 or 2)

**Note on shape symmetry:**  
- 1 distinct orientation: Single (0), Square (5), Large square (16)  
- 2 distinct orientations: Domino (1), Tall domino (2), Triple (3), Tall triple (4), Zigzag (11), Wide block (12), Tall block (13), Long line (14), Tall line (15)  
- 4 distinct orientations: Corner (6), Reverse corner (7), L block (8), J block (9), T block (10)

---

## 2. Placement & Collision

**Input:** `board: List<Int>` (64 values 0–6), `piece: Piece`, `row: Int`, `col: Int`  
**Output:** `Boolean` — all piece cells in-bounds and over empty squares  
**Model:** `piece.shape.cells.all { (row+dr) ∈ [0,8) ∧ (col+dc) ∈ [0,8) ∧ board[(row+dr)×8+(col+dc)] == 0 }`  
**Complexity:** O(cells in piece) ≤ O(9) per call  
**Implementation:** `GameEngine.canPlace(board, piece, row, col)`  
**Independent oracle:** `refCanPlace(board, cells, row, col)` in TestOracle.kt — pure array check, no production methods  
**Test evidence (VERIFIED):**  
- `invalidPlacementDoesNotMutateTheBoardOrTray` (GameEngineTest) — invalid placements rejected, immutable  
- `placementAcceptsRejectsAndOutOfBoundsCountsMatchIndependentReference` — every (shapeId, rotation, row, col) triple: `refCanPlace` == `GameEngine.canPlace`; full board always rejects all 64 positions  
**Status:** VERIFIED; `refCanPlace` oracle added in iteration 1

---

## 3. Line Clearing & Scoring

**Input:** Board after placement  
**Output:** Set of cleared indices, point delta, new combo  
**Model:**  
- Full line: all 8 cells in a row OR column non-zero  
- Simultaneous detection: scan all 8 rows + 8 columns, union of full lines  
- Points: `cells_placed × 10 + lines² × 100 × clamp(combo, 0, 8)` where `combo = clamp(prev + 1, 0, 99)` if lines > 0 else 0  
**Complexity:** O(64) scan per placement  
**Key properties:** Union prevents double-counting crossing row+column; combo caps multiplier at 8  
**Implementation:** `GameEngine.place`, `GameEngine.fullLines` (private)  
**Independent oracle:** `refFullLines(board)` in TestOracle.kt  
**Test evidence (VERIFIED):**  
- `crossingRowAndColumnClearsTheUnionWithoutDoubleCounting` (MathVerificationTest) — 7+7 cells, 1 crossing → 15 cleared, 2 lines  
- `placementScoringMatchesTheClosedFormAcrossFuzzedBoards` — 300 fuzzed states vs closed-form reference  
- `comboMultiplierCapsAtEightRegardlessOfStackedClears` — 12 consecutive clears prove multiplier ≤ 8  
- `chargeAddsExactlyTheClearedLineCountAndCapsAtSix` — charge += lineCount capped at PULSE_CAPACITY=6  
**Status:** VERIFIED; no production defects found

---

## 4. Pulse (3×3 Rescue)

**Input:** `state: GameState` with `charge == 6`, `row: Int`, `col: Int`  
**Output:** `MoveResult` clearing all non-zero cells in 3×3 area around target  
**Model:** Area = `{(r,c) | r ∈ [row−1, row+1] ∩ [0,8), c ∈ [col−1, col+1] ∩ [0,8)}`, score += cleared × 5, combo = 0, charge = 0  
**Conditions:** `canPulse = !isWon ∧ charge == 6 ∧ board.any { it != 0 }`; pulse on empty area returns null  
**Complexity:** O(9) per pulse  
**Implementation:** `GameEngine.pulse`, `GameEngine.pulseArea`  
**Independent oracle:** `referencePulseArea(row, col)` in MathVerificationTest  
**Test evidence (VERIFIED):**  
- `pulseAreaMatchesReferenceOverEveryCellAndOutOfBounds` — all 64 cells + 4 OOB probes  
- `pulseRequiresFullChargeAndScoresFivePerClearedCell` — **FIXED iteration 1**: board was linear indices 0..8 (row 0 + one cell of row 1), not a 3×3 grid; corrected to true 3×3 at rows 0–2, cols 0–2 → 9 cleared, 45 pts  
**Status:** VERIFIED; oracle flaw fixed

---

## 5. Game-Over Detection

**Input:** `GameState`  
**Output:** `Boolean`  
**Model:** `!isWon ∧ !canPulse ∧ all non-null tray pieces cannot be placed in any rotation at any position`  
**Complexity:** O(pieces × 4 × 64 × cells) ≤ O(3 × 4 × 64 × 9) ≈ 6912 calls; early-exit per valid placement  
**Implementation:** `GameEngine.isGameOver` → `fitsAnyRotation` → `fitsAnywhere` → `canPlace`  
**Independent oracle:** `referenceIsGameOver(state)` — **STRENGTHENED iteration 1**: now uses `refCells + refCanPlace` instead of production `GameEngine.canPlace + Shapes.orientations`  
**Test evidence (VERIFIED):**  
- `gameOverDetectionMatchesTheReferenceAcrossFuzzedStates` — 400 fuzzed boards, production vs independent reference match  
- `rotatingAnyTrayPieceNeverChangesTheGameOverOutcome` — 200 boards, rotation invariant  
- `aPieceThatFitsOnlyAfterRotationDoesNotEndTheGame` (TacticalRulesTest)  
**Status:** VERIFIED; oracle independence strengthened

---

## 6. Hand Generation / RNG / Difficulty Threshold

**Input:** `GameState` with `randomState: Long`, `board: List<Int>`, `score: Long`  
**Output:** New `GameState` with refilled `tray` and updated `randomState`  
**Model:**  
```
shapeLimit = if (score < 300) 12 else 17
candidates = (1 until shapeLimit).filter { fitsAnywhere(board, Piece(it, 1)) } ?: [0]
tray = List(3) { Piece(candidates[random.nextInt(size)], random.nextInt(6) + 1) }
randomState = random.nextLong()
```  
**Key properties:** Shape 0 (Single) fallback guarantees non-empty tray; deterministic from seed; score threshold unlocks larger shape pool  
**Complexity:** O(shapeLimit × 64 × cells) per refill to check fitsAnywhere  
**Implementation:** `GameEngine.refill` (private), triggered by `place` when all tray slots null  
**Test evidence (VERIFIED):**  
- `refilledHandsBelowTheScoreThresholdUseTheSmallShapePool` — **FIXED iteration 1**: second-iteration positions collided with first (NPE on null!!); fixed to use non-overlapping positions and single refill cycle  
- `refillThresholdUsesLargerPoolAtOrAboveScore300` — score=299 → all shapeIds in 1..11; score=300 → at least one seed among 1..100 produces shapeId ≥ 12  
- `seededRunsAlwaysProduceValidLegalHandsRegardlessOfPath` — 40 seeds × 120 steps, hands always valid  
- `restoredRandomStateProducesTheSameNextHand` (GameEngineTest) — persistence round-trip  
**Status:** VERIFIED

---

## 7. Hint (Greedy Placement Suggestion)

**Input:** `GameState`  
**Output:** `PlacementHint(slot, row, col, rotation, lines)?`  
**Model:** Greedy best-first over all (piece, rotation, row, col) combos; value = `lines × 10000 + cells × 100 + row + col`; deduplicates identical-cell rotations  
**Complexity:** O(pieces × 4 × 64 × 64) ≤ O(3 × 4 × 64 × 9) scans  
**Implementation:** `GameEngine.findHint`  
**Independent oracle:** `referenceHint(state)` — **STRENGTHENED iteration 1**: previously called production `GameEngine.canPlace` and `Shapes.orientations`; now uses `refCells + refCanPlace + refFullLines`  
**Test evidence (VERIFIED):**  
- `hintMatchesTheReferenceAndItsPlacementIsAlwaysLegal` — 250 fuzzed boards, production vs independent reference match; every hint placement is legal  
**Status:** VERIFIED; oracle independence strengthened

---

## 8. Undo & Checkpoint

**Input:** `GameState`, `UndoCheckpoint(before, after)`  
**Output:** Restored `GameState` with `undosRemaining − 1`  
**Model:** `canUndo` requires: checkpoint != null, budget > 0, !isWon, after == state (byte-identical), before.mode == state.mode, before.challengeId == state.challengeId, before.moves + 1 == state.moves, before.undosRemaining == state.undosRemaining  
**Complexity:** O(state equality check) = O(64 board + 3 tray + constants)  
**Budget:** MAX_UNDOS = 3 per run; depletes on each use; no refill  
**Implementation:** `GameEngine.canUndo`, `GameEngine.undo`  
**Test evidence (VERIFIED):**  
- `undoRestoresTheWholeMoveIncludingARefilledHandAndRandomState` (TacticalRulesTest)  
- `undoBudgetCannotBeRefilledByRepeatedUndoOrSnapshotReload` (TacticalRulesTest)  
- `undoBudgetDecreasesAndIsBlockedAfterThreeExhausted` — **NEW iteration 1**: 3 hint-guided placements + undo cycles; budget depletes from 3→2→1→0; 4th attempt blocked by canUndo=false, undo=null  
**Status:** VERIFIED

---

## 9. Mastery & Palette Unlocks

**Input:** `best: Long = max(bestFlow, bestDaily)`  
**Output:** `Rank` (Rookie/Builder/Strategist/Master/Legend) and `Palette` unlock status  
**Model:**  
- Rank: `ranks.lastOrNull { best >= it.requiredScore } ?: ranks[0]` with thresholds 0, 1000, 3000, 7500, 15000  
- Palette: unlock at scores 0 (PRISM), 1000 (ARCADE), 3000 (AURORA)  
- Progress: `(best − rank.threshold) / (next.threshold − rank.threshold)` clamped to [0, 1]  
**Complexity:** O(rank count) = O(5)  
**Implementation:** `Mastery.kt`  
**Test evidence (VERIFIED):**  
- `masteryAndPalettesUseRealBestScoreThresholds` (TacticalRulesTest)  
**Status:** VERIFIED; no new tests added this slice

---

## 10. Serialization & Validation

**Input:** `String` (JSON), `GameState`  
**Output:** `GameState?` (null if invalid)  
**Model:** kotlinx.serialization JSON; `isValid` checks: version==1, board size 64 ∈ 0..6, tray size 3 with ≥1 non-null, pieces valid, scores/charges/combos within bounds, no full lines, mode-specific challengeId  
**Complexity:** O(board size) per validation  
**Implementation:** `SnapshotCodec`, `GameEngine.isValid`  
**Test evidence (VERIFIED):**  
- `snapshotsRoundTripAndRejectCorruption` (GameEngineTest)  
- `validationRejectsEveryInvalidNumericBoundary` (MathVerificationGameplayTest) — 13 invalid states all rejected  
- `dailyChallengeIsDeterministicAndUniqueAcrossThirtyDays` — same date → same seed → same game; 30 distinct dates → 30 distinct games  
**Status:** VERIFIED

---

## 11. Daily Seeding

**Input:** `LocalDate`  
**Output:** Deterministic `GameState`  
**Model:** `seed = date.toEpochDay() × 104729L + 811L`  
**Properties:** Same date → same seed → same game; multiplicative formula, linear in epoch day; no collision test across multi-year window (UNVERIFIED)  
**Implementation:** `GameEngine.daily(date)`  
**Test evidence (VERIFIED):**  
- `dailyChallengeIsDeterministicAndUniqueAcrossThirtyDays` — 30-day uniqueness confirmed  
**Status:** VERIFIED for 30-day window; seed collision across 4-year window UNVERIFIED (known gap, low risk)

---

## UI Layer Algorithms (UNVERIFIED — no touch/animation instrumentation run this slice)

| Algorithm | Location | Description | Status |
|-----------|----------|-------------|--------|
| Ghost rendering | GameScreen.kt | Hint/drag preview cells drawn via Canvas; anchor outside (0,0) for rotated pieces | UNVERIFIED (fix applied v0.2.0) |
| Touch-to-board coordinate mapping | GameScreen.kt | Pixel offset → row/col index for drag placement | UNVERIFIED |
| Clear animation timing | GameBoard.kt | 520ms tween for cleared cells; non-blocking | UNVERIFIED |
| Hint ghost tap area | GameScreen.kt | ANY ghost tile tap triggers placement (anchor-outside fix) | UNVERIFIED runtime |
| Rotation button cycle | TacticalToolbar.kt | Tray slot rotate cycles 0→1→2→3→0 | UNVERIFIED |
| Palette color mapping | GameBoard.kt | Logical color 1–6 → theme-specific Color | UNVERIFIED post-v0.2.0 |
| Frame timing / jank | GameBoard.kt | Board invalidation per placement | UNVERIFIED (no profiling) |
| Haptic/audio triggers | MainActivity.kt | Per-clear sound/vibration based on settings | UNVERIFIED |

---

## Defects Found This Slice

| # | Test | Type | Description | Status |
|---|------|------|-------------|--------|
| 1 | `fourQuarterTurnsAreTheIdentityAndOrientationsAreDistinct` | Test oracle flaw | Oracle assumed all non-square shapes have 4 distinct orientations; correct counts are 1, 2, or 4 depending on symmetry | FIXED |
| 2 | `pulseRequiresFullChargeAndScoresFivePerClearedCell` | Test oracle flaw | Board `0..8` is not a 3×3 grid: only 4 cells overlap with pulse area at (1,1), not 9 | FIXED |
| 3 | `refilledHandsBelowTheScoreThresholdUseTheSmallShapePool` | Test oracle flaw | Second-iteration placements reused occupied board positions (NPE on null!!) | FIXED |

**No production rule defects were found in the initial slice.** Those3 issues were test oracle errors. Later BB-003 numeric-boundary save invalidation was reproduced and fixed; do not interpret this historical statement as the final audit verdict.

---

## Evidence Paths

| Artifact | Path |
|----------|------|
| Baseline XML (before fixes) | `artifacts/qa/math-before/TEST-*.xml` |
| Baseline run log | `artifacts/qa/math-before/baseline-run.txt` |
| Scoped post-fix log | `artifacts/qa/math-after-scoped3.txt` |
| Full core run log | `artifacts/qa/core-test-full-after.txt` |
| Final XML reports | `artifacts/qa/TEST-*.xml` |
| TestOracle.kt | `core/src/test/kotlin/com/blackblast/core/TestOracle.kt` |

---

## Remaining Gaps (Not Addressed This Slice)

1. **Seed collision check** — No test covers the 4-year Daily seed window for collisions
2. **Hint performance** — No latency benchmark for findHint on 32–40 cell boards
3. **Game-over exhaustive solver** — Property-based exhaustive solver absent (400-board fuzzing is strong but not total)
4. **UI touch/animation** — All 8 UI algorithms above labelled UNVERIFIED
5. **Release APK QA** — No release variant built or tested this slice
6. **Startup ANR** — Root cause still UNKNOWN (historical evidence retained)
