package com.blackblast.core

import org.junit.Assert.*
import org.junit.Test

class LevelRulesTest {
    private val singles = List(3) { Piece(0, it + 1) }

    @Test
    fun eachLevelHasABoundedObjectiveAndDeterministicRetry() {
        assertEquals(30, Levels.all.size)
        Levels.all.forEach { level ->
            val initial = GameEngine.level(level.number)
            assertEquals(initial, GameEngine.level(level.number))
            assertTrue(GameEngine.isValid(initial))
            assertFalse(initial.isWon)
            assertFalse(GameEngine.isGameOver(initial))
            assertEquals(level.moveLimit, initial.movesRemaining)
            assertEquals(level.startingBlocks, initial.board.count { it != 0 })
            assertTrue(initial.tray.filterNotNull().all { it.shapeId in 1 until level.shapeLimit })
        }
    }

    @Test
    fun completingTheGoalOnTheLastMoveWinsInsteadOfFailing() {
        val definition = Levels.get(1)!!
        val board = MutableList(64) { 0 }.apply { repeat(7) { set(it, 2) } }
        val before = GameEngine.level(1).copy(board = board, tray = singles, lines = definition.target - 1, moves = definition.moveLimit - 1)
        val result = GameEngine.place(before, 0, 0, 7)!!.state
        assertTrue(result.isWon)
        assertEquals(0, result.movesRemaining)
        assertFalse(result.isOutOfMoves)
        assertFalse(GameEngine.isGameOver(result))
        assertNull(GameEngine.place(result, 1, 4, 4))
        assertNull(GameEngine.rotate(result, 1))
        assertNull(GameEngine.findHint(result))
    }

    @Test
    fun exhaustingTheBudgetFailsEvenWithSpaceAndFullPulse() {
        val definition = Levels.get(1)!!
        val before = GameEngine.level(1).copy(tray = singles, moves = definition.moveLimit - 1, charge = 6)
        val after = GameEngine.place(before, 0, 4, 4)!!.state
        assertFalse(after.isWon)
        assertTrue(after.isOutOfMoves)
        assertTrue(GameEngine.isGameOver(after))
        assertFalse(after.canPulse)
        assertNull(GameEngine.place(after, 1, 5, 5))
        assertNull(GameEngine.pulse(after, 4, 4))
        assertNull(GameEngine.rotate(after, 1))
        assertNull(GameEngine.findHint(after))
        assertFalse(GameEngine.canUndo(after, UndoCheckpoint(before, after)))
        assertEquals(GameEngine.level(1), GameEngine.level(after.levelNumber))
    }

    @Test
    fun invalidPlacementsAndUtilityActionsDoNotSpendMoves() {
        val before = GameEngine.level(1).copy(tray = listOf(Piece(5, 1), Piece(0, 2), Piece(0, 3)))
        assertNull(GameEngine.place(before, 0, 7, 7))
        val rotated = GameEngine.rotate(before, 0)!!
        GameEngine.findHint(rotated)
        GameEngine.forecast(rotated, 0, 3, 3)
        assertEquals(before.movesRemaining, rotated.movesRemaining)
        val after = GameEngine.place(rotated, 0, 3, 3)!!.state
        assertEquals(before.movesRemaining!! - 1, after.movesRemaining)
        val restored = GameEngine.undo(after, UndoCheckpoint(rotated, after))!!
        assertEquals(rotated.copy(undosRemaining = 2), restored)
    }

    @Test
    fun completionUnlocksExactlyOneNextLevelAndRewardsAreIdempotent() {
        val won = GameEngine.level(1).copy(lines = 2, score = 420, moves = 10)
        val start = CampaignProgress()
        assertFalse(start.isUnlocked(2))
        assertEquals(start, start.record(won.copy(lines = 0)))
        val completed = start.record(won)
        assertTrue(completed.isUnlocked(2))
        assertFalse(completed.isUnlocked(3))
        assertEquals(1, completed.records.size)
        assertTrue(completed.totalPoints > 0)
        assertEquals(completed, completed.record(won))
        assertEquals(completed, completed.record(won.copy(moves = 12, score = 400)))
        val improved = completed.record(won.copy(moves = 8, score = 500))
        assertEquals(1, improved.records.size)
        assertTrue(improved.totalPoints > completed.totalPoints)
        assertEquals(improved, CampaignCodec.decode(CampaignCodec.encode(improved)))
        val lockedWin = GameEngine.level(10).copy(score = 5000, moves = 10)
        assertEquals(start, start.record(lockedWin))
    }

    @Test
    fun pointObjectivesAndPulseBudgetUseActualGameState() {
        val definition = Levels.get(3)!!
        assertEquals(LevelGoal.POINTS, definition.goal)
        val before = GameEngine.level(3).copy(board = List(64) { if (it == 27) 1 else 0 },
            tray = singles, score = definition.target - 5L, charge = 6, moves = definition.moveLimit - 1)
        val won = GameEngine.pulse(before, 3, 3)!!.state
        assertTrue(won.isWon)
        assertEquals(definition.target.toLong(), won.score)
        assertEquals(0, won.movesRemaining)
        assertNull(GameEngine.undo(won, UndoCheckpoint(before, won)))
    }

    @Test
    fun oldSavesRemainValidAndLevelSnapshotsValidateTheirBounds() {
        val old = """{"tray":[{"shapeId":1,"color":2},null,null],"score":900,"randomState":7654}"""
        val restored = SnapshotCodec.decode(old)!!
        assertEquals(GameMode.FLOW, restored.mode)
        assertEquals(0, restored.levelNumber)
        assertEquals(900L, restored.score)
        val level = GameEngine.level(4).copy(moves = 7)
        assertEquals(level, SnapshotCodec.decode(SnapshotCodec.encode(level)))
        listOf(level.copy(levelNumber = 0), level.copy(levelNumber = 31),
            level.copy(moves = level.level!!.moveLimit + 1), level.copy(challengeId = "wrong")).forEach {
            assertNull(SnapshotCodec.decode(SnapshotCodec.encode(it)))
        }
        assertNull(CampaignCodec.decode("broken"))
        assertNull(CampaignCodec.decode("""{"records":[{"number":2,"bestScore":100,"fewestMoves":1}]}"""))
    }

    @Test
    fun chaptersIncreaseSpacePressureAndLineTargets() {
        val first = Levels.all.filter { it.goal == LevelGoal.LINES }
        assertTrue(first.last().target > first.first().target)
        assertTrue(first.last().moveLimit.toFloat() / first.last().target < first.first().moveLimit.toFloat() / first.first().target)
        assertTrue(Levels.all.last().startingBlocks > Levels.all.first().startingBlocks)
        assertTrue(Levels.all.last().shapeLimit > Levels.all.first().shapeLimit)
    }

    @Test
    fun everyShippedLevelHasADemonstratedWinningRoute() {
        val failures = mutableListOf<String>()
        Levels.all.forEach { definition ->
            var state = GameEngine.level(definition.number)
            while (!state.isWon && !GameEngine.isGameOver(state)) {
                val hint = GameEngine.findHint(state)
                if (state.canPulse && (hint == null || state.board.count { it != 0 } >= 36)) {
                    val origin = state.board.indices.maxBy { index ->
                        GameEngine.pulseArea(index / BOARD_SIZE, index % BOARD_SIZE).count { state.board[it] != 0 }
                    }
                    state = GameEngine.pulse(state, origin / BOARD_SIZE, origin % BOARD_SIZE)!!.state
                } else {
                    if (hint == null) break
                    repeat((hint.rotation - state.tray[hint.slot]!!.rotation + 4) % 4) {
                        state = GameEngine.rotate(state, hint.slot)!!
                    }
                    state = GameEngine.place(state, hint.slot, hint.row, hint.column)!!.state
                }
                assertTrue(GameEngine.isValid(state))
            }
            println("Level ${definition.number}: ${definition.objective}, ${state.moves}/${definition.moveLimit} moves, score=${state.score}, lines=${state.lines}, won=${state.isWon}")
            if (!state.isWon) failures += "Level ${definition.number}: ${definition.current(state)}/${definition.target} at ${state.moves}/${definition.moveLimit} moves"
        }
        assertTrue("No verified route for: ${failures.joinToString()}", failures.isEmpty())
    }
}