package com.blackblast.core

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class TacticalRulesTest {
    private val singles = List(3) { Piece(0, it + 1) }

    @Test
    fun allRotationsStayNormalizedAndFourTurnsRestoreEveryPiece() {
        Shapes.all.indices.forEach { shapeId ->
            val initial = GameState(tray = listOf(Piece(shapeId, 1), null, null))
            var state = initial
            repeat(4) {
                state = GameEngine.rotate(state, 0)!!
                val shape = state.tray[0]!!.shape
                assertEquals(0, shape.cells.minOf { it.row })
                assertEquals(0, shape.cells.minOf { it.column })
                assertEquals(Shapes.all[shapeId].cells.size, shape.cells.toSet().size)
                assertTrue(shape.cells.all { it.row < shape.height && it.column < shape.width })
            }
            assertEquals(initial, state)
        }
        assertEquals(Shapes.orientations[5][0].cells, Shapes.orientations[5][1].cells)
    }

    @Test
    fun aPieceThatFitsOnlyAfterRotationDoesNotEndTheGame() {
        val board = MutableList(64) { index -> if (index % 8 == index / 8) 0 else 1 }
        board[8] = 0
        board[16] = 0
        val state = GameState(board = board, tray = List(3) { Piece(3, 2) })
        assertFalse(GameEngine.fitsAnywhere(board, state.tray[0]!!))
        assertTrue(GameEngine.fitsAnyRotation(board, state.tray[0]!!))
        assertFalse(GameEngine.isGameOver(state))
        val rotated = GameEngine.rotate(state, 0)!!
        assertTrue(GameEngine.canPlace(board, rotated.tray[0]!!, 0, 0))
        val result = GameEngine.place(rotated, 0, 0, 0)!!
        assertEquals(setOf(0, 8, 16), result.placed)
        assertTrue(result.lineCount > 0)
    }

    @Test
    fun rotationDoesNotChangeScoreRandomnessOrTheOtherPieces() {
        val state = GameEngine.newGame(seed = 892)
        val rotated = GameEngine.rotate(state, 1)!!
        assertEquals(state.copy(tray = rotated.tray), rotated)
        assertEquals(state.tray[0], rotated.tray[0])
        assertEquals(state.tray[2], rotated.tray[2])
        assertNull(GameEngine.rotate(state, -1))
        assertNull(GameEngine.rotate(state.copy(tray = listOf(null, null, Piece(0, 1))), 0))
    }

    @Test
    fun undoRestoresTheWholeMoveIncludingARefilledHandAndRandomState() {
        var before = GameState(tray = singles, randomState = 532)
        before = GameEngine.place(before, 0, 1, 1)!!.state
        before = GameEngine.place(before, 1, 2, 2)!!.state
        val after = GameEngine.place(before, 2, 3, 3)!!.state
        val checkpoint = UndoCheckpoint(before, after)
        val restoredCheckpoint = SnapshotCodec.decodeCheckpoint(SnapshotCodec.encodeCheckpoint(checkpoint), after)!!
        val undone = GameEngine.undo(after, restoredCheckpoint)!!
        assertEquals(before.copy(undosRemaining = 2), undone)
        assertEquals(after.copy(undosRemaining = 2), GameEngine.place(undone, 2, 3, 3)!!.state)
        assertNull(GameEngine.undo(undone, checkpoint))
    }

    @Test
    fun undoBudgetCannotBeRefilledByRepeatedUndoOrSnapshotReload() {
        var state = GameState(tray = singles)
        repeat(MAX_UNDOS) {
            val before = state
            val after = GameEngine.place(before, 0, 4, 4)!!.state
            state = GameEngine.undo(after, UndoCheckpoint(before, after))!!
            state = SnapshotCodec.decode(SnapshotCodec.encode(state))!!
        }
        assertEquals(0, state.undosRemaining)
        val after = GameEngine.place(state, 0, 4, 4)!!.state
        assertNull(GameEngine.undo(after, UndoCheckpoint(state, after)))
    }

    @Test
    fun undoRestoresChargeAndComboAfterPulseWithoutKeepingItsScore() {
        val before = GameState(board = List(64) { if (it % 9 == 0) 2 else 0 }, tray = singles, charge = 6, combo = 2)
        val after = GameEngine.pulse(before, 3, 3)!!.state
        assertEquals(before.copy(undosRemaining = 2), GameEngine.undo(after, UndoCheckpoint(before, after)))
    }

    @Test
    fun staleWrongModeAndCorruptCheckpointsAreRejectedWithoutChangingCurrentGame() {
        val before = GameState(tray = singles)
        val after = GameEngine.place(before, 0, 4, 4)!!.state
        val checkpoint = UndoCheckpoint(before, after)
        assertNull(GameEngine.undo(after.copy(score = 200), checkpoint))
        assertNull(GameEngine.undo(after, checkpoint.copy(before = before.copy(mode = GameMode.DAILY, challengeId = "2026-09-08"))))
        assertNull(GameEngine.undo(after, checkpoint.copy(before = before.copy(board = listOf(1)))))
        assertNull(SnapshotCodec.decodeCheckpoint("not json", after))
        assertEquals(10L, after.score)
    }

    @Test
    fun hintPrefersAClearIsDeterministicAndNeverMutatesTheRun() {
        val board = MutableList(64) { 0 }.apply { repeat(7) { set(it, 1) } }
        val state = GameState(board = board, tray = listOf(Piece(0, 2), Piece(5, 3), Piece(3, 4)))
        val encoded = SnapshotCodec.encode(state)
        val hint = GameEngine.findHint(state)!!
        val piece = state.tray[hint.slot]!!.copy(rotation = hint.rotation)
        assertTrue(GameEngine.canPlace(state.board, piece, hint.row, hint.column))
        assertTrue(hint.lines >= 1)
        assertEquals(hint, GameEngine.findHint(state))
        assertEquals(encoded, SnapshotCodec.encode(state))
    }

    @Test
    fun completedDailyRunsRejectHintsRotationsAndUndo() {
        val before = GameEngine.daily(LocalDate.of(2026, 9, 8))
        val won = before.copy(lines = DAILY_TARGET, moves = 1)
        assertNull(GameEngine.findHint(won))
        assertNull(GameEngine.rotate(won, 0))
        assertNull(GameEngine.undo(won, UndoCheckpoint(before, won)))
    }

    @Test
    fun oldVersionOneSavesLoadWithoutLosingTheBoardOrRandomState() {
        val legacy = """{"tray":[{"shapeId":0,"color":1},{"shapeId":5,"color":2},null],"score":210,"charge":2,"lines":2,"moves":8,"randomState":9196161,"mode":"DAILY","challengeId":"2026-09-08"}"""
        val state = SnapshotCodec.decode(legacy)!!
        assertEquals(210L, state.score)
        assertEquals(9196161L, state.randomState)
        assertEquals(8, state.moves)
        assertEquals("2026-09-08", state.challengeId)
        assertEquals(MAX_UNDOS, state.undosRemaining)
        assertTrue(state.tray.filterNotNull().all { it.rotation == 0 })
        assertEquals(state, SnapshotCodec.decode(SnapshotCodec.encode(state)))
    }

    @Test
    fun newSaveFieldsAreValidated() {
        val state = GameState(tray = singles)
        assertNull(SnapshotCodec.decode(SnapshotCodec.encode(state.copy(undosRemaining = 4))))
        assertNull(SnapshotCodec.decode(SnapshotCodec.encode(state.copy(undosRemaining = -1))))
        val invalidRotation = state.copy(tray = List(3) { Piece(0, 1, 4) })
        assertNull(SnapshotCodec.decode(SnapshotCodec.encode(invalidRotation)))
        assertEquals(state.copy(undosRemaining = 0), SnapshotCodec.decode(SnapshotCodec.encode(state.copy(undosRemaining = 0))))
    }

    @Test
    fun masteryAndPalettesUseRealBestScoreThresholds() {
        assertEquals("Rookie", Mastery.rank(0).name)
        assertEquals("Builder", Mastery.rank(1000).name)
        assertEquals("Legend", Mastery.rank(15000).name)
        assertEquals(0.5f, Mastery.progress(500), 0.001f)
        assertEquals(1f, Mastery.progress(20000), 0.001f)
        assertNull(Mastery.nextRank(20000))
        assertFalse(TilePalette.ARCADE.isUnlocked(999))
        assertTrue(TilePalette.ARCADE.isUnlocked(1000))
        assertFalse(TilePalette.AURORA.isUnlocked(2999))
        assertTrue(TilePalette.AURORA.isUnlocked(3000))
    }
    @Test
    fun rotatingAfterAMoveKeepsTheUndoCheckpointUsable() {
        val before = GameState(tray = singles, randomState = 900)
        val after = GameEngine.place(before, 0, 0, 0)!!.state
        val checkpoint = UndoCheckpoint(before, after)
        // The player rotates another tray piece; the placement checkpoint must survive.
        val rotated = GameEngine.rotate(after, 1)!!
        val preserved = UndoCheckpoint(checkpoint.before, rotated)
        assertTrue(GameEngine.canUndo(rotated, preserved))
        val undone = GameEngine.undo(rotated, preserved)!!
        assertEquals(before.copy(undosRemaining = 2), undone)
        // The checkpoint must also survive persistence round-trips after a rotation.
        assertEquals(rotated, SnapshotCodec.decode(SnapshotCodec.encode(rotated))!!)
        assertEquals(preserved, SnapshotCodec.decodeCheckpoint(SnapshotCodec.encodeCheckpoint(preserved), rotated)!!)
    }
}