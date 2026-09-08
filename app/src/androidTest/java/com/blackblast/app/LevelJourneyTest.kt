package com.blackblast.app

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.blackblast.app.ui.BlackBlastTheme
import com.blackblast.app.ui.GameScreen
import com.blackblast.app.ui.LevelResultDialog
import com.blackblast.core.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LevelJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val application get() = instrumentation.targetContext.applicationContext as Application
    private val owner = ViewModelStore()
    private lateinit var model: GameViewModel
    private lateinit var storage: GameStore

    private fun host(initial: PlayerProgress = PlayerProgress(sound = false, haptics = false)) {
        val dataStore = PreferenceDataStoreFactory.create {
            File(application.cacheDir, "level-ui-${UUID.randomUUID()}.preferences_pb")
        }
        storage = GameStore(application, dataStore)
        runBlocking { assertTrue(storage.save(initial).await()) }
        instrumentation.runOnMainSync {
            model = GameViewModel(application, storage)
            owner.put("game", model)
        }
        compose.setContent { BlackBlastApp(model) }
        compose.waitUntil(10000) { model.ui.value.progress != null }
        compose.waitForIdle()
    }

    @After
    fun cleanup() {
        instrumentation.runOnMainSync { owner.clear() }
    }

    private fun tap(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).performTouchInput { click() }
    private fun saved() = compose.waitUntil(10000) { !model.ui.value.saving }

    private fun winCurrentLevel() {
        val limit = model.ui.value.progress!!.current.level!!.moveLimit
        var actions = 0
        while (!model.ui.value.progress!!.current.isWon && actions < limit) {
            val game = model.ui.value.progress!!.current
            assertFalse("Unexpected failure in level ${game.levelNumber}", GameEngine.isGameOver(game))
            tap("hint")
            compose.waitUntil(10000) { model.ui.value.hint != null && !model.ui.value.findingHint }
            val hint = model.ui.value.hint!!
            val shapeCell = model.ui.value.progress!!.current.tray[hint.slot]!!.shape.cells.first()
            tap("cell_${hint.row + shapeCell.row}_${hint.column + shapeCell.column}")
            actions += 1
        }
        assertTrue("Level goal was not achieved", model.ui.value.progress!!.current.isWon)
        saved()
    }

    private fun capture(name: String, dialog: Boolean = false) {
        val node = if (dialog) compose.onNode(isDialog()) else compose.onNodeWithTag("game_screen")
        val bitmap = node.captureToImage().asAndroidBitmap()
        val output = File(application.getExternalFilesDir(null), "levels").apply { mkdirs() }
        File(output, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    @Test
    fun firstLevelShowsObjectiveBudgetAndLockedMap() {
        host()
        compose.onNodeWithTag("level_objective").assertTextEquals("Clear 2 lines")
        compose.onNodeWithTag("moves_left", useUnmergedTree = true).assertTextEquals("14")
        capture("level-one-start")
        tap("level_map_button")
        compose.onNodeWithTag("level_map_title").assertIsDisplayed()
        compose.onNodeWithTag("level_1").assertIsEnabled()
        compose.onNodeWithTag("level_2").assertIsNotEnabled().assertTextContains("2")
        val before = model.ui.value.progress
        compose.onNodeWithTag("level_2").performTouchInput { click() }
        assertEquals(before, model.ui.value.progress)
        capture("locked-level-map", dialog = true)
        tap("close_level_map")
        compose.onNodeWithTag("board").assertIsDisplayed()
    }

    @Test
    fun completionMessageAppearsBeforeNextAndRewardsCannotBeFarmed() {
        host()
        winCurrentLevel()
        compose.onNodeWithTag("level_result_title").assertTextEquals("Level 1 completed")
        assertEquals(1, model.ui.value.progress!!.flow.levelNumber)
        val rewarded = model.ui.value.progress!!.flowStages
        assertTrue(rewarded.totalPoints > 0)
        assertEquals(2, rewarded.unlockedLevel)
        capture("level-one-completed", dialog = true)
        tap("next_level")
        compose.onNodeWithTag("level_result_title").assertDoesNotExist()
        compose.onNodeWithTag("moves_left", useUnmergedTree = true).assertTextEquals("14")
        assertEquals(2, model.ui.value.progress!!.current.levelNumber)
        tap("level_map_button")
        compose.onNodeWithTag("level_2").assertIsEnabled()
        compose.onNodeWithTag("level_3").assertIsNotEnabled()
        tap("level_1")
        winCurrentLevel()
        assertEquals(rewarded, model.ui.value.progress!!.flowStages)
        compose.onNodeWithTag("level_reward").assertTextEquals("Best result kept")
        assertEquals(rewarded, runBlocking { storage.load().progress.flowStages })
    }

    @Test
    fun exhaustedBudgetOffersOnlySameLevelRetryAndPreservesOtherModes() {
        val initial = PlayerProgress(sound = false, haptics = false,
            flow = GameEngine.stage(GameMode.FLOW).copy(moves = 13, tray = List(3) { Piece(0, 1) }))
        host(initial)
        compose.onNodeWithTag("moves_left", useUnmergedTree = true).assertTextEquals("1")
        tap("tray_0")
        tap("cell_4_4")
        saved()
        compose.onNodeWithTag("level_result_title").assertTextEquals("Out of moves")
        compose.onNodeWithTag("next_level").assertDoesNotExist()
        compose.onNodeWithTag("rescue_undo").assertDoesNotExist()
        assertEquals(0L, model.ui.value.progress!!.flowStages.totalPoints)
        capture("level-one-failed", dialog = true)
        tap("retry_level")
        assertEquals(GameEngine.stage(GameMode.FLOW), model.ui.value.progress!!.current)
        assertEquals(initial.levels, model.ui.value.progress!!.levels)
        assertEquals(initial.daily, model.ui.value.progress!!.daily)
        assertEquals(1, model.ui.value.progress!!.flowStages.unlockedLevel)
        compose.onNodeWithTag("moves_left", useUnmergedTree = true).assertTextEquals("14")
    }

    @Test
    fun lastAllowedMoveWinsAndNextNeverSkips() {
        val game = GameEngine.stage(GameMode.FLOW).copy(board = List(64) { if (it < 7) 2 else 0 },
            moves = 13, lines = 1, tray = List(3) { Piece(0, 1) })
        host(PlayerProgress(flow = game, sound = false, haptics = false))
        tap("tray_0")
        tap("cell_0_7")
        saved()
        compose.onNodeWithTag("level_result_title").assertTextEquals("Level 1 completed")
        assertEquals(0, model.ui.value.progress!!.current.movesRemaining)
        tap("next_level")
        instrumentation.runOnMainSync { assertFalse(model.nextLevel()) }
        assertEquals(2, model.ui.value.progress!!.current.levelNumber)
    }

    @Test
    fun pauseAndModeChangesKeepTheAttemptBudget() {
        host()
        tap("hint")
        compose.waitUntil(10000) { model.ui.value.hint != null }
        val hint = model.ui.value.hint!!
        val cell = model.ui.value.progress!!.current.tray[hint.slot]!!.shape.cells.first()
        tap("cell_${hint.row + cell.row}_${hint.column + cell.column}")
        val before = model.ui.value.progress!!.flow
        tap("pause")
        tap("resume")
        tap("mode_flow")
        tap("mode_daily")
        tap("mode_flow")
        assertEquals(before, model.ui.value.progress!!.current)
        saved()
        assertEquals(before, runBlocking { storage.load().progress.flow })
    }

    @Test
    fun finalLevelShowsCampaignCompletionWithoutCreatingLevelThirtyOne() {
        val records = Levels.all.dropLast(1).map { LevelRecord(it.number, maxOf(1000L, it.target.toLong()), 10) }
        val definition = Levels.get(30, GameMode.FLOW)!!
        val game = GameEngine.stage(GameMode.FLOW, 30).copy(board = List(64) { 0 }, tray = List(3) { Piece(0, 1) },
            score = definition.target - 10L, moves = definition.moveLimit - 1)
        host(PlayerProgress(flow = game, flowStages = CampaignProgress(records, GameMode.FLOW), sound = false, haptics = false))
        tap("tray_0")
        tap("cell_3_3")
        saved()
        compose.onNodeWithTag("level_result_title").assertTextEquals("Level 30 completed")
        compose.onNodeWithText("All levels completed").assertIsDisplayed()
        assertTrue(model.ui.value.progress!!.flowStages.isComplete)
        instrumentation.runOnMainSync { assertFalse(model.nextLevel()) }
        capture("campaign-completed", dialog = true)
    }

    @Test
    fun compactLevelHudKeepsBudgetObjectiveAndBoardVisible() {
        val game = GameEngine.stage(GameMode.FLOW, 26)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, 1.3f)) {
                BlackBlastTheme {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Box(Modifier.requiredSize(320.dp, 568.dp).testTag("viewport")) {
                            GameScreen(PlayerProgress(flow = game), null, false,
                                { _, _, _ -> false }, { _, _ -> false }, {}, {})
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("level_objective").assertIsDisplayed()
        compose.onNodeWithTag("moves_left", useUnmergedTree = true).assertIsDisplayed()
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        val board = compose.onNodeWithTag("board").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val tools = compose.onNodeWithTag("tactical_tools").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(board.width >= 250f)
        assertEquals(board.width, board.height, 1f)
        assertTrue(viewport.contains(board.topLeft))
        assertTrue(viewport.contains(tools.bottomRight - Offset(1f, 1f)))
        assertTrue(board.bottom <= tools.top)
        capture("compact-level-hud")
    }

    @Test
    fun successFailurePulseAndClearSoundPatternsAreDistinctAndFinite() {
        val level = GameEngine.stage(GameMode.FLOW)
        val success = MoveResult(level.copy(lines = 2, moves = 8), emptySet(), emptySet(), 100, 1)
        val failure = MoveResult(level.copy(moves = level.level!!.moveLimit), emptySet(), emptySet(), 10, 0)
        assertEquals(SoundCue.LEVEL_COMPLETE, FeedbackSounds.cue(success))
        assertEquals(SoundCue.LEVEL_FAILED, FeedbackSounds.cue(failure))
        assertNotEquals(FeedbackSounds.pattern(SoundCue.LEVEL_COMPLETE), FeedbackSounds.pattern(SoundCue.LEVEL_FAILED))
        SoundCue.entries.forEach { cue ->
            val pattern = FeedbackSounds.pattern(cue)
            assertTrue(pattern.isNotEmpty())
            assertTrue(pattern.all { it.durationMs in 30..250 })
            assertTrue(pattern.sumOf { it.durationMs + 30 } <= 600)
        }
    }

    @Test
    fun completionParticlesAnimateThenSettleWithoutBlockingNext() {
        val won = GameEngine.stage(GameMode.FLOW).copy(lines = 2, score = 480, moves = 9)
        val progress = PlayerProgress().withGame(won)
        var nextCount = 0
        compose.mainClock.autoAdvance = false
        try {
            compose.setContent {
                BlackBlastTheme {
                    LevelResultDialog(progress, 350, true, false, {}, { nextCount += 1 }, {})
                }
            }
            compose.mainClock.advanceTimeBy(240)
            val animated = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
            val output = File(application.getExternalFilesDir(null), "levels").apply { mkdirs() }
            File(output, "completion-confetti.png").outputStream().use {
                assertTrue(animated.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            compose.onNodeWithTag("next_level").assertIsEnabled().performTouchInput { click() }
            compose.runOnIdle { assertEquals(1, nextCount) }
            compose.mainClock.advanceTimeBy(1600)
            val settled = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
            var changed = 0
            for (row in 0 until animated.height step 3) {
                for (column in 0 until animated.width step 3) {
                    if (animated.getPixel(column, row) != settled.getPixel(column, row)) changed += 1
                }
            }
            assertTrue("The completion burst must render actual moving pixels", changed > 20)
            compose.mainClock.advanceTimeBy(400)
            val later = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
            File(output, "completion-settled.png").outputStream().use { settled.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(output, "completion-later.png").outputStream().use { later.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val dialogBounds = compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
            val nextBounds = compose.onNodeWithTag("next_level").fetchSemanticsNode().boundsInRoot
            val buttonTop = (nextBounds.top - dialogBounds.top).toInt()
            val changedRows = (0 until settled.height).filter { row ->
                (0 until settled.width).any { column -> settled.getPixel(column, row) != later.getPixel(column, row) }
            }
            println("Confetti settling probe: changed rows=${changedRows.minOrNull()}..${changedRows.maxOrNull()}, Next top=$buttonTop")
            assertTrue("Confetti must stop instead of looping indefinitely", settled.sameAs(later))
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun onlyFlowAndDailyExistAndEachShowsItsOwnStageGoal() {
        host()
        compose.onNodeWithTag("mode_levels").assertDoesNotExist()
        compose.onNodeWithTag("mode_flow").assertIsDisplayed()
        compose.onNodeWithTag("mode_daily").assertIsDisplayed()
        compose.onNodeWithTag("level_objective").assertTextEquals("Clear 2 lines")
        tap("mode_daily")
        compose.onNodeWithTag("level_objective").assertTextEquals("Clear 3 lines")
        compose.onNodeWithTag("daily_attempts").assertTextEquals("3/3 LEFT")
        compose.onNodeWithText("3X STAGE REWARD").assertIsDisplayed()
        capture("daily-integrated-stage")
        tap("mode_flow")
        compose.onNodeWithTag("level_objective").assertTextEquals("Clear 2 lines")
    }

    @Test
    fun thirdDailyFailureRequiresFlowOrTomorrowAndCannotUndoTheLoss() {
        val daily = GameEngine.stage(GameMode.DAILY).let { it.copy(moves = it.level!!.moveLimit - 1, tray = List(3) { Piece(0, 1) }) }
        host(PlayerProgress(daily = daily, activeMode = GameMode.DAILY,
            dailyAttempts = DailyAttempts(daily.challengeId, 3, true), sound = false, haptics = false))
        tap("tray_0")
        tap("cell_4_4")
        saved()
        compose.onNodeWithTag("level_result_title").assertTextEquals("Out of moves")
        compose.onNodeWithTag("daily_result_allowance").assertTextEquals("Daily limit reached. New attempts tomorrow.")
        compose.onNodeWithTag("retry_level").assertDoesNotExist()
        compose.onNodeWithTag("rescue_undo").assertDoesNotExist()
        compose.onNodeWithTag("next_level").assertDoesNotExist()
        capture("daily-attempts-exhausted", dialog = true)
        tap("daily_back_flow")
        assertEquals(GameMode.FLOW, model.ui.value.progress!!.activeMode)
        compose.onNodeWithTag("level_objective").assertTextEquals("Clear 2 lines")
        tap("mode_daily")
        compose.onNodeWithTag("daily_result_allowance").assertIsDisplayed()
        assertEquals(3, model.ui.value.progress!!.dailyAttempts.used)
    }

    @Test
    fun exhaustedDailyAllowanceBlocksAReadyBoardAndLeavesFlowPlayable() {
        val initial = PlayerProgress(activeMode = GameMode.DAILY)
        host(initial.copy(dailyAttempts = DailyAttempts(initial.daily.challengeId, 3)))
        compose.onNodeWithTag("daily_limit_title").assertTextEquals("Daily limit reached")
        val before = model.ui.value.progress!!.daily
        instrumentation.runOnMainSync {
            assertFalse(model.place(0, 0, 0))
            model.restart()
        }
        assertEquals(before, model.ui.value.progress!!.daily)
        tap("daily_back_flow")
        compose.onNodeWithTag("hint").assertIsEnabled()
        assertEquals(GameMode.FLOW, model.ui.value.progress!!.activeMode)
    }
}