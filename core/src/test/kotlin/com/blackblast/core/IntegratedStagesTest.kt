package com.blackblast.core

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class IntegratedStagesTest {
    private val date = LocalDate.of(2026, 9, 8)
    private val singles = List(3) { Piece(0, it + 1) }

    @Test
    fun stagesAreInsideBothModesAndDailyHasHarderGoalsAndHigherRewards() {
        (1..Levels.COUNT).forEach { number ->
            val flow = GameEngine.stage(GameMode.FLOW, number, date)
            val daily = GameEngine.stage(GameMode.DAILY, number, date)
            assertEquals(GameMode.FLOW, flow.mode)
            assertEquals(GameMode.DAILY, daily.mode)
            assertTrue(GameEngine.isValid(flow))
            assertTrue(GameEngine.isValid(daily))
            assertTrue(daily.level!!.target > flow.level!!.target)
            assertEquals(flow.level!!.moveLimit, daily.level!!.moveLimit)
            assertEquals(flow.level!!.reward(10) * 3, daily.level!!.reward(10))
            assertEquals(flow, GameEngine.stage(GameMode.FLOW, number, date))
            assertEquals(daily, GameEngine.stage(GameMode.DAILY, number, date))
        }
    }

    @Test
    fun blockedStagesEndEvenWithPulseAndUndoAvailable() {
        val board = List(64) { if ((it / 8 + it % 8) % 2 == 0) 1 else 0 }
        listOf(GameMode.FLOW, GameMode.DAILY).forEach { mode ->
            val before = GameEngine.stage(mode, 1, date).copy(board = board,
                tray = listOf(Piece(0, 1), Piece(5, 2), Piece(5, 3)), charge = 6)
            val after = GameEngine.place(before, 0, 3, 4)!!.state
            assertTrue(GameEngine.isGameOver(after))
            assertFalse(after.canPulse)
            assertNull(GameEngine.pulse(after, 2, 2))
            assertNull(GameEngine.rotate(after, 1))
            assertNull(GameEngine.undo(after, UndoCheckpoint(before, after)))
            assertNull(GameEngine.place(after, 1, 2, 2))
        }
    }

    @Test
    fun finalMoveSuccessWinsInEachModeButUnmetGoalRequiresRetry() {
        listOf(GameMode.FLOW, GameMode.DAILY).forEach { mode ->
            val initial = GameEngine.stage(mode, 1, date)
            val definition = initial.level!!
            val board = MutableList(64) { 0 }.apply { repeat(7) { set(it, 1) } }
            val before = initial.copy(board = board, tray = singles, lines = definition.target - 1, moves = definition.moveLimit - 1)
            val won = GameEngine.place(before, 0, 0, 7)!!.state
            assertTrue(won.isWon)
            assertFalse(GameEngine.isGameOver(won))
            val failed = GameEngine.place(initial.copy(tray = singles, moves = definition.moveLimit - 1), 0, 4, 4)!!.state
            assertTrue(failed.isOutOfMoves)
            assertTrue(GameEngine.isGameOver(failed))
        }
    }

    @Test
    fun dailyAttemptCapSurvivesSerializationAndOnlyResetsOnANewerDay() {
        var attempts = DailyAttempts(date.toString())
        repeat(DAILY_ATTEMPT_LIMIT) { index ->
            val active = attempts.start()!!
            assertEquals(index + 1, active.used)
            assertEquals(active, active.start())
            assertTrue(active.canPlay)
            attempts = DailyAttemptsCodec.decode(DailyAttemptsCodec.encode(active.finish()))!!
        }
        assertEquals(0, attempts.remaining)
        assertFalse(attempts.canPlay)
        assertNull(attempts.start())
        assertEquals(attempts, attempts.forDate(date))
        assertEquals(attempts, attempts.forDate(date.minusDays(1)))
        assertEquals(DailyAttempts(date.plusDays(1).toString()), attempts.forDate(date.plusDays(1)))
        assertNull(DailyAttemptsCodec.decode("""{"date":"2026-09-08","used":4}"""))
    }

    @Test
    fun stageRewardsStayModeScopedAndCannotBeFarmedByReplaying() {
        listOf(GameMode.FLOW, GameMode.DAILY).forEach { mode ->
            val won = GameEngine.stage(mode, 1, date).let { it.copy(lines = it.level!!.target, score = 500, moves = 10) }
            val empty = CampaignProgress(mode = mode, challengeId = won.challengeId)
            val complete = empty.record(won)
            assertEquals(1, complete.records.size)
            assertEquals(complete, complete.record(won))
            assertEquals(complete, complete.record(won.copy(moves = 12)))
            assertTrue(complete.isUnlocked(2))
            assertFalse(complete.isUnlocked(3))
            assertEquals(complete, CampaignCodec.decode(CampaignCodec.encode(complete)))
            assertEquals(empty, empty.record(won.copy(mode = if (mode == GameMode.FLOW) GameMode.DAILY else GameMode.FLOW)))
        }
    }

    @Test
    fun allFlowAndDailyStagesHaveADemonstratedRouteForTheTestDate() {
        val failed = mutableListOf<String>()
        listOf(GameMode.FLOW, GameMode.DAILY).forEach { mode ->
            (1..Levels.COUNT).forEach { number ->
                val route = listOf(36, 28, 24, 32).asSequence().map { threshold -> playRoute(mode, number, threshold) }.firstOrNull { it.isWon }
                if (route == null) failed += "$mode $number: no successful route across four Pulse strategies"
            }
        }
        assertTrue("No demonstrated route: ${failed.joinToString()}", failed.isEmpty())
    }

    private fun playRoute(mode: GameMode, number: Int, pulseThreshold: Int): GameState {
        var game = GameEngine.stage(mode, number, date)
        while (!game.isWon && !GameEngine.isGameOver(game)) {
            val hint = GameEngine.findHint(game)
            if (game.canPulse && game.board.count { it != 0 } >= pulseThreshold) {
                val origin = game.board.indices.maxBy { index ->
                    GameEngine.pulseArea(index / 8, index % 8).count { game.board[it] != 0 }
                }
                game = GameEngine.pulse(game, origin / 8, origin % 8)!!.state
            } else {
                if (hint == null) break
                repeat((hint.rotation - game.tray[hint.slot]!!.rotation + 4) % 4) {
                    game = GameEngine.rotate(game, hint.slot)!!
                }
                game = GameEngine.place(game, hint.slot, hint.row, hint.column)!!.state
            }
            assertTrue(GameEngine.isValid(game))
        }
        return game
    }
}