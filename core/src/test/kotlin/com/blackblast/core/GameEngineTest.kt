package com.blackblast.core

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class GameEngineTest {
    private val singles = List(3) { Piece(0, it + 1) }

    @Test
    fun invalidPlacementDoesNotMutateTheBoardOrTray() {
        val state = GameState(tray = listOf(Piece(5, 2), Piece(1, 3), Piece(0, 1)))
        val snapshot = SnapshotCodec.encode(state)
        assertNull(GameEngine.place(state, 0, 7, 7))
        assertNull(GameEngine.place(state, 0, -1, 0))
        assertNull(GameEngine.place(state, 9, 0, 0))
        assertEquals(snapshot, SnapshotCodec.encode(state))
        val placed = GameEngine.place(state, 0, 0, 0)!!.state
        assertNull(GameEngine.place(placed, 1, 0, 0))
        assertNull(GameEngine.place(placed, 0, 3, 3))
    }

    @Test
    fun crossingRowAndColumnClearTogetherWithoutDoubleCounting() {
        val board = MutableList(64) { 0 }
        repeat(7) { index ->
            board[7 * 8 + index] = 1
            board[index * 8 + 7] = 2
        }
        val state = GameState(board = board, tray = singles)
        assertEquals(15, GameEngine.preview(state, 0, 7, 7).size)
        val result = GameEngine.place(state, 0, 7, 7)!!
        assertEquals(2, result.lineCount)
        assertEquals(15, result.cleared.size)
        assertEquals(410L, result.points)
        assertEquals(2, result.state.charge)
        assertTrue(result.state.board.all { it == 0 })
        assertEquals(14, state.board.count { it != 0 })
    }

    @Test
    fun comboRewardsConsecutiveClearsAndResetsAfterAnOrdinaryMove() {
        val board = MutableList(64) { 0 }
        repeat(7) { column -> board[column] = 1; board[8 + column] = 2 }
        val first = GameEngine.place(GameState(board = board, tray = singles), 0, 0, 7)!!
        val second = GameEngine.place(first.state, 1, 1, 7)!!
        val third = GameEngine.place(second.state, 2, 3, 3)!!
        assertEquals(110L, first.points)
        assertEquals(210L, second.points)
        assertEquals(2, second.state.combo)
        assertEquals(0, third.state.combo)
        assertEquals(330L, third.state.score)
    }

    @Test
    fun trayRefillsOnlyAfterAllThreePiecesAreUsed() {
        val initial = GameState(tray = singles, randomState = 100)
        val first = GameEngine.place(initial, 0, 0, 0)!!.state
        val second = GameEngine.place(first, 1, 1, 1)!!.state
        assertNull(first.tray[0])
        assertNull(second.tray[1])
        val third = GameEngine.place(second, 2, 2, 2)!!.state
        assertTrue(third.tray.all { it != null })
        assertNotEquals(initial.randomState, third.randomState)
        assertEquals(3, third.board.count { it != 0 })
    }

    @Test
    fun chargedPulseCanRescueAnOtherwiseBlockedBoard() {
        val board = List(64) { if ((it / 8 + it % 8) % 2 == 0) 1 else 0 }
        val blocked = GameState(board = board, tray = List(3) { Piece(5, 2) })
        assertTrue(GameEngine.isGameOver(blocked))
        assertNull(GameEngine.pulse(blocked, 4, 4))
        val charged = blocked.copy(charge = PULSE_CAPACITY)
        assertFalse(GameEngine.isGameOver(charged))
        assertNull(GameEngine.pulse(charged, -1, 0))
        val result = GameEngine.pulse(charged, 4, 4)!!
        assertTrue(result.cleared.all { it in GameEngine.pulseArea(4, 4) })
        assertEquals(0, result.state.charge)
        assertFalse(GameEngine.isGameOver(result.state))
        assertEquals(4, GameEngine.pulseArea(0, 0).size)
    }

    @Test
    fun dailyChallengeIsDeterministicAndStopsAtTheGoal() {
        val date = LocalDate.of(2026, 9, 8)
        assertEquals(GameEngine.daily(date), GameEngine.daily(date))
        assertNotEquals(GameEngine.daily(date).randomState, GameEngine.daily(date.plusDays(1)).randomState)
        val board = MutableList(64) { 0 }.apply { repeat(7) { set(it, 1) } }
        val state = GameEngine.daily(date).copy(board = board, tray = singles, lines = DAILY_TARGET - 1)
        val result = GameEngine.place(state, 0, 0, 7)!!.state
        assertTrue(result.isWon)
        assertFalse(GameEngine.isGameOver(result))
        assertNull(GameEngine.place(result, 1, 4, 4))
        assertNull(GameEngine.pulse(result.copy(charge = PULSE_CAPACITY), 0, 0))
    }

    @Test
    fun snapshotsRoundTripAndRejectCorruption() {
        val state = GameEngine.newGame(seed = 93)
        assertEquals(state, SnapshotCodec.decode(SnapshotCodec.encode(state)))
        assertNull(SnapshotCodec.decode("not json"))
        assertNull(SnapshotCodec.decode("{}"))
        assertNull(SnapshotCodec.decode(SnapshotCodec.encode(state.copy(board = listOf(1)))))
        assertNull(SnapshotCodec.decode(SnapshotCodec.encode(state.copy(tray = listOf(Piece(900, 1))))))
        assertNull(SnapshotCodec.decode(SnapshotCodec.encode(state.copy(charge = 100))))
        assertNull(SnapshotCodec.decode(SnapshotCodec.encode(state.copy(version = 2))))
    }

    @Test
    fun restoredRandomStateProducesTheSameNextHand() {
        var state = GameState(tray = singles, randomState = 500)
        state = GameEngine.place(state, 0, 0, 0)!!.state
        state = GameEngine.place(state, 1, 1, 1)!!.state
        val restored = SnapshotCodec.decode(SnapshotCodec.encode(state))!!
        assertEquals(
            GameEngine.place(state, 2, 2, 2)!!.state,
            GameEngine.place(restored, 2, 2, 2)!!.state,
        )
    }
}