package com.blackblast.core

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class BoundaryIntegrityTest {
    private val singles = List(3) { Piece(0, it + 1) }

    @Test
    fun legalPlacementAtAcceptedScoreLimitStillRoundTrips() {
        val before = GameState(tray = singles, score = Long.MAX_VALUE / 2)
        assertTrue(GameEngine.isValid(before))
        val result = GameEngine.place(before, 0, 3, 3)!!
        assertEquals(result.state, SnapshotCodec.decode(SnapshotCodec.encode(result.state)))
        assertEquals(result.state.score - before.score, result.points)
    }

    @Test
    fun legalClearAtAcceptedCounterLimitsStillRoundTrips() {
        val board = MutableList(64) { 0 }.apply { repeat(7) { set(it, 1) } }
        val before = GameState(board = board, tray = singles, lines = Int.MAX_VALUE / 2, moves = Int.MAX_VALUE / 2)
        assertTrue(GameEngine.isValid(before))
        val result = GameEngine.place(before, 0, 0, 7)!!
        assertEquals(result.state, SnapshotCodec.decode(SnapshotCodec.encode(result.state)))
        assertTrue(result.state.moves >= before.moves)
        assertTrue(result.state.lines >= before.lines)
        val restored = GameEngine.undo(result.state, UndoCheckpoint(before, result.state))
        assertEquals(before.copy(undosRemaining = 2), restored)
    }

    @Test
    fun pulseAtAcceptedScoreAndMoveLimitsStillRoundTrips() {
        val before = GameState(board = List(64) { if (it == 27) 1 else 0 }, tray = singles,
            score = Long.MAX_VALUE / 2, moves = Int.MAX_VALUE / 2, charge = 6)
        assertTrue(GameEngine.isValid(before))
        val result = GameEngine.pulse(before, 3, 3)!!
        assertEquals(result.state, SnapshotCodec.decode(SnapshotCodec.encode(result.state)))
        assertEquals(result.state.score - before.score, result.points)
    }

    @Test
    fun extremeCoordinatesNeverWrapIntoTheBoard() {
        val state = GameState(tray = listOf(Piece(16, 2), Piece(8, 3), Piece(0, 1)))
        listOf(Int.MIN_VALUE, Int.MIN_VALUE + 4, -8, 8, Int.MAX_VALUE - 4, Int.MAX_VALUE).forEach { coordinate ->
            state.tray.indices.forEach { slot ->
                assertNull(GameEngine.place(state, slot, coordinate, 0))
                assertNull(GameEngine.place(state, slot, 0, coordinate))
                assertTrue(GameEngine.preview(state, slot, coordinate, coordinate).isEmpty())
            }
            assertTrue(GameEngine.pulseArea(coordinate, coordinate).isEmpty())
        }
    }

    @Test
    fun dailyRandomStatesNotJustDateLabelsDifferAcrossFourYears() {
        val start = LocalDate.of(2024, 1, 1)
        val states = (0 until 1461).map { GameEngine.daily(start.plusDays(it.toLong())) }
        assertEquals(states.size, states.map { it.randomState }.toSet().size)
        states.forEachIndexed { index, game ->
            assertEquals(game, GameEngine.daily(start.plusDays(index.toLong())))
            assertTrue(GameEngine.isValid(game))
        }
    }
}