package com.blackblast.core

import java.time.LocalDate
import java.util.Random
import org.junit.Assert.*
import org.junit.Test

class PlacementForecastTest {
    @Test
    fun crossingClearForecastHasTheExactUnionAndComboPoints() {
        val board = MutableList(64) { 0 }
        repeat(7) { index ->
            board[56 + index] = 2
            board[index * 8 + 7] = 3
        }
        val state = GameState(board = board, tray = listOf(Piece(0, 1), null, Piece(5, 2)), combo = 2)
        val preview = GameEngine.forecast(state, 0, 7, 7)!!
        assertEquals(2, preview.lineCount)
        assertEquals(3, preview.combo)
        assertEquals(1210L, preview.points)
        assertEquals(15, preview.cleared.size)
        assertEquals(setOf(63), preview.placed)
        assertTrue(preview.board.all { it == 0 })
        assertEquals(14, state.board.count { it != 0 })
    }

    @Test
    fun forecastsMatchIndependentGeometryAndScoreAcrossGeneratedInputs() {
        val random = Random(73014)
        repeat(200) {
            val board = List(64) { if (random.nextInt(5) == 0) random.nextInt(6) + 1 else 0 }
            val piece = Piece(random.nextInt(Shapes.all.size), random.nextInt(6) + 1, random.nextInt(4))
            val state = GameState(board = board, tray = listOf(piece, null, Piece(0, 1)), combo = random.nextInt(15))
            val row = random.nextInt(10) - 1
            val column = random.nextInt(10) - 1
            val cells = refCells(piece.shapeId, piece.rotation)
            val actual = GameEngine.forecast(state, 0, row, column)
            if (!refCanPlace(board, cells, row, column)) {
                assertNull(actual)
            } else {
                val projectedBoard = board.toMutableList()
                cells.forEach { cell -> projectedBoard[(row + cell.row) * 8 + column + cell.column] = piece.color }
                val lines = refFullLines(projectedBoard)
                val union = lines.flatten().toSet()
                union.forEach { index -> projectedBoard[index] = 0 }
                val combo = if (lines.isEmpty()) 0 else state.combo + 1
                val points = cells.size * 10L + lines.size * lines.size * 100L * minOf(combo, 8)
                assertNotNull(actual)
                assertEquals(projectedBoard, actual!!.board)
                assertEquals(union, actual.cleared)
                assertEquals(points, actual.points)
                assertEquals(lines.size, actual.lineCount)
            }
        }
    }

    @Test
    fun repeatedForecastDoesNotConsumeTheHandOrChangeTheNextRandomHand() {
        val before = GameState(tray = listOf(null, Piece(5, 2, 1), null), score = 800, randomState = 93213)
        val encoded = SnapshotCodec.encode(before)
        val prediction = GameEngine.forecast(before, 1, 2, 3)!!
        repeat(50) { assertEquals(prediction, GameEngine.forecast(before, 1, 2, 3)) }
        assertEquals(encoded, SnapshotCodec.encode(before))
        val actual = GameEngine.place(before, 1, 2, 3)!!
        assertEquals(prediction.board, actual.state.board)
        assertEquals(prediction.points, actual.points)
        assertEquals(GameEngine.place(SnapshotCodec.decode(encoded)!!, 1, 2, 3), actual)
    }

    @Test
    fun forecastRejectsInvalidUsedAndCompletedMoves() {
        val state = GameState(tray = listOf(Piece(5, 2), null, Piece(0, 1)))
        assertNull(GameEngine.forecast(state, 0, 7, 7))
        assertNull(GameEngine.forecast(state, 1, 0, 0))
        assertNull(GameEngine.forecast(state, -1, 0, 0))
        assertNull(GameEngine.forecast(state, 0, Int.MAX_VALUE, 0))
        val won = GameEngine.daily(LocalDate.of(2026, 9, 8)).copy(lines = DAILY_TARGET)
        assertNull(GameEngine.forecast(won, 0, 0, 0))
    }

    @Test
    fun cappedScoreForecastReportsOnlyPointsThatCanBeAwarded() {
        val state = GameState(tray = List(3) { Piece(0, 1) }, score = MAX_SCORE - 4)
        assertEquals(4L, GameEngine.forecast(state, 0, 0, 0)!!.points)
        assertEquals(MAX_SCORE, GameEngine.place(state, 0, 0, 0)!!.state.score)
    }
}