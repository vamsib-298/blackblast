package com.blackblast.core

import java.time.LocalDate
import java.util.Random
import org.junit.Assert.*
import org.junit.Test

/**
 * Reference-comparison and boundary verification for hints, refill, validation,
 * daily seeding, and mastery progression.
 */
class MathVerificationGameplayTest {
    private val singles = List(3) { Piece(0, it + 1) }

    private fun referenceHint(state: GameState): PlacementHint? {
        if (state.isWon) return null
        var best: PlacementHint? = null
        var bestValue = Int.MIN_VALUE
        state.tray.forEachIndexed { slot, piece ->
            if (piece == null) return@forEachIndexed
            // Compute unique orientations using refCells — no production Shapes.orientations or canPlace
            val seen = mutableSetOf<List<Cell>>()
            for (r in 0..3) {
                val rotation = (piece.rotation + r) % 4
                val cells = refCells(piece.shapeId, rotation)
                if (!seen.add(cells)) continue
                repeat(BOARD_SIZE) { row ->
                    repeat(BOARD_SIZE) { col ->
                        if (refCanPlace(state.board, cells, row, col)) {
                            val next = state.board.toMutableList()
                            cells.forEach { c -> next[(row + c.row) * BOARD_SIZE + col + c.column] = piece.color }
                            val lines = refFullLines(next).size
                            val value = lines * 10000 + cells.size * 100 + row + col
                            if (value > bestValue) {
                                bestValue = value
                                best = PlacementHint(slot, row, col, rotation, lines)
                            }
                        }
                    }
                }
            }
        }
        return best
    }

    @Test
    fun hintMatchesTheReferenceAndItsPlacementIsAlwaysLegal() {
        val random = Random(555L)
        repeat(250) {
            val board = MutableList(64) { 0 }
            repeat(random.nextInt(40)) {
                val i = random.nextInt(64)
                if (board[i] == 0) board[i] = random.nextInt(6) + 1
            }
            val state = GameState(board = board, tray = List(3) { Piece(random.nextInt(Shapes.all.size), random.nextInt(6) + 1) })
            val expected = referenceHint(state)
            val actual = GameEngine.findHint(state)
            assertEquals(expected, actual)
            if (actual != null) {
                val oriented = state.tray[actual.slot]!!.copy(rotation = actual.rotation)
                assertTrue(GameEngine.canPlace(state.board, oriented, actual.row, actual.column))
            }
        }
    }

    @Test
    fun seededRunsAlwaysProduceValidLegalHandsRegardlessOfPath() {
        repeat(40) { seed ->
            var state = GameEngine.newGame(seed = seed.toLong())
            for (step in 0..119) {
                assertTrue("run $seed invalid: ${SnapshotCodec.encode(state)}", GameEngine.isValid(state))
                if (GameEngine.isGameOver(state)) break
                val hint = GameEngine.findHint(state)
                if (hint != null) {
                    val oriented = state.tray[hint.slot]!!.copy(rotation = hint.rotation)
                    if (GameEngine.canPlace(state.board, oriented, hint.row, hint.column)) {
                        var rotated = state
                        val turns = (hint.rotation - state.tray[hint.slot]!!.rotation + 4) % 4
                        repeat(turns) { rotated = GameEngine.rotate(rotated, hint.slot)!! }
                        state = GameEngine.place(rotated, hint.slot, hint.row, hint.column)!!.state
                        continue
                    }
                }
                if (state.canPulse) {
                    val target = state.board.indices.firstOrNull { state.board[it] != 0 } ?: 0
                    state = GameEngine.pulse(state, target / BOARD_SIZE, target % BOARD_SIZE)!!.state
                    continue
                }
                break
            }
        }
    }

    @Test
    fun refilledHandsBelowTheScoreThresholdUseTheSmallShapePool() {
        var current = GameState(tray = singles, randomState = 12345L)
        // Consume 3 Single pieces at non-overlapping positions to trigger exactly one refill
        current = GameEngine.place(current, 0, 0, 0)!!.state
        current = GameEngine.place(current, 1, 0, 1)!!.state
        current = GameEngine.place(current, 2, 0, 2)!!.state
        // Score is 0 < 300; refill must draw shapeId from 1 until 12 only
        assertTrue(current.tray.all { it != null })
        assertTrue(current.tray.filterNotNull().all { it.shapeId in 1 until 12 })
    }

    @Test
    fun validationRejectsEveryInvalidNumericBoundary() {
        val base = GameState(tray = singles)
        val mustReject = listOf(
            base.copy(charge = -1),
            base.copy(charge = PULSE_CAPACITY + 1),
            base.copy(undosRemaining = -1),
            base.copy(undosRemaining = MAX_UNDOS + 1),
            base.copy(combo = -1),
            base.copy(combo = 100),
            base.copy(moves = -1),
            base.copy(lines = -1),
            base.copy(score = -1),
            base.copy(version = 0),
            base.copy(version = 2),
            base.copy(board = List(63) { 1 }),
            base.copy(tray = listOf(null, null, null)),
        )
        mustReject.forEach {
            assertEquals("must reject ${SnapshotCodec.encode(it)}", null, SnapshotCodec.decode(SnapshotCodec.encode(it)))
        }
    }

    @Test
    fun dailyChallengeIsDeterministicAndUniqueAcrossThirtyDays() {
        val date = LocalDate.of(2026, 9, 8)
        val a = SnapshotCodec.encode(GameEngine.daily(date))
        assertEquals(a, SnapshotCodec.encode(GameEngine.daily(date)))
        for (days in 1..30) {
            val other = GameEngine.daily(date.plusDays(days.toLong()))
            assertEquals(date.plusDays(days.toLong()).toString(), other.challengeId)
            assertNotEquals(a, SnapshotCodec.encode(other))
        }
    }

    @Test
    fun chargeAddsExactlyTheClearedLineCountAndCapsAtSix() {
        val board = MutableList(64) { 0 }.apply { repeat(7) { column -> set(column, 1) } }
        val state = GameState(board = board, tray = singles, charge = 5)
        val result = GameEngine.place(state, 0, 0, 7)!!
        assertEquals(PULSE_CAPACITY, result.state.charge)
        val capped = GameState(board = board, tray = singles, charge = 6)
        assertTrue(capped.canPulse)
        assertFalse(capped.copy(board = List(64) { 0 }).canPulse)
    }

    @Test
    fun refillThresholdUsesLargerPoolAtOrAboveScore300() {
        var sawLargePool = false
        for (seed in 1L..100L) {
            var below = GameState(tray = singles, randomState = seed, score = 269)
            below = GameEngine.place(below, 0, 0, 0)!!.state
            below = GameEngine.place(below, 1, 0, 1)!!.state
            below = GameEngine.place(below, 2, 0, 2)!!.state
            assertEquals(299L, below.score)
            assertTrue("refill at score 299 must use the small pool", below.tray.filterNotNull().all { it.shapeId in 1 until 12 })

            var above = GameState(tray = singles, randomState = seed, score = 270)
            above = GameEngine.place(above, 0, 0, 0)!!.state
            above = GameEngine.place(above, 1, 0, 1)!!.state
            above = GameEngine.place(above, 2, 0, 2)!!.state
            assertEquals(300L, above.score)
            assertTrue(above.tray.filterNotNull().all { it.shapeId in 1 until Shapes.all.size })
            if (above.tray.filterNotNull().any { it.shapeId >= 12 }) sawLargePool = true
        }
        assertTrue("at score=300 at least one seed must produce shapeId >= 12", sawLargePool)
    }

    @Test
    fun undoBudgetDecreasesAndIsBlockedAfterThreeExhausted() {
        var state = GameEngine.newGame(seed = 42L)
        assertEquals(MAX_UNDOS, state.undosRemaining)
        repeat(MAX_UNDOS) { i ->
            val hint = GameEngine.findHint(state)
            assertNotNull("hint must exist at step $i", hint)
            val checkpoint = state
            // Perform a hint-guided placement
            val oriented = state.tray[hint!!.slot]!!.copy(rotation = hint.rotation)
            var rotated = state
            val turns = (hint.rotation - state.tray[hint.slot]!!.rotation + 4) % 4
            repeat(turns) { rotated = GameEngine.rotate(rotated, hint.slot)!! }
            val after = GameEngine.place(rotated, hint.slot, hint.row, hint.column)!!.state
            val cp = UndoCheckpoint(before = state, after = after)
            assertTrue("canUndo must be true at step $i", GameEngine.canUndo(after, cp))
            state = GameEngine.undo(after, cp)!!
            assertEquals("undosRemaining after undo $i", MAX_UNDOS - (i + 1), state.undosRemaining)
        }
        assertEquals(0, state.undosRemaining)
        // Any further undo attempt must be blocked even with a syntactically valid checkpoint
        val hint = GameEngine.findHint(state)!!
        var rotated = state
        val turns = (hint.rotation - state.tray[hint.slot]!!.rotation + 4) % 4
        repeat(turns) { rotated = GameEngine.rotate(rotated, hint.slot)!! }
        val after = GameEngine.place(rotated, hint.slot, hint.row, hint.column)!!.state
        val cp = UndoCheckpoint(before = state, after = after)
        assertFalse("canUndo must be false when budget=0", GameEngine.canUndo(after, cp))
        assertNull("undo must return null when budget=0", GameEngine.undo(after, cp))
    }
}