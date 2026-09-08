package com.blackblast.app

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.blackblast.core.*
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class IntegratedStageProgressTest {
    private val date = LocalDate.of(2026, 9, 8)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val application get() = instrumentation.targetContext.applicationContext as Application
    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun dataStore() = PreferenceDataStoreFactory.create {
        File(application.cacheDir, "integrated-stage-${UUID.randomUUID()}.preferences_pb")
    }
    private fun initial(mode: GameMode = GameMode.FLOW) = PlayerProgress(
        flow = GameEngine.stage(GameMode.FLOW, date = date), daily = GameEngine.stage(GameMode.DAILY, date = date),
        activeMode = mode, sound = false, haptics = false,
    )
    private suspend fun saved(model: GameViewModel) {
        withTimeout(10000) { model.ui.first { !it.saving && it.progress != null } }
    }
    private suspend fun withModel(progress: PlayerProgress, action: suspend (GameViewModel, GameStore, (LocalDate) -> Unit) -> Unit) {
        val storage = GameStore(application, dataStore())
        assertTrue(storage.save(progress).await())
        var clock = date
        val owner = ViewModelStore()
        lateinit var model: GameViewModel
        onMain { model = GameViewModel(application, storage) { clock }; owner.put("game", model) }
        try {
            withTimeout(10000) { model.ui.first { it.progress != null } }
            action(model, storage) { clock = it }
        } finally {
            onMain { owner.clear() }
        }
    }

    private suspend fun playHint(model: GameViewModel) {
        onMain { model.requestHint() }
        val state = withTimeout(10000) { model.ui.first { !it.findingHint && it.hint != null } }
        val hint = state.hint!!
        onMain { assertTrue(model.place(hint.slot, hint.row, hint.column)) }
        saved(model)
    }

    @Test
    fun freshStorageStartsFlowAndBothModesHaveStages() = runBlocking {
        val progress = GameStore(application, dataStore()).load(date).progress
        assertEquals(GameMode.FLOW, progress.activeMode)
        assertEquals(1, progress.flow.levelNumber)
        assertEquals(1, progress.daily.levelNumber)
        assertNotNull(progress.flow.level)
        assertNotNull(progress.daily.level)
        assertEquals(DAILY_ATTEMPT_LIMIT, progress.dailyAttempts.remaining)
    }

    @Test
    fun legacyCampaignMigratesIntoFlowAndKeepsItsEarnedPointsAndRawBoard() = runBlocking {
        val preferences = dataStore()
        val win = GameEngine.level(1).copy(lines = 2, score = 420, moves = 10)
        val campaign = CampaignProgress().record(win)
        val level = GameEngine.level(2).copy(moves = 3)
        val oldFlow = SnapshotCodec.encode(GameEngine.newGame(seed = 123).copy(score = 900))
        preferences.edit {
            it[stringPreferencesKey("flow")] = oldFlow
            it[stringPreferencesKey("levels")] = SnapshotCodec.encode(level)
            it[stringPreferencesKey("campaign")] = CampaignCodec.encode(campaign)
            it[stringPreferencesKey("active_mode")] = "LEVELS"
            it[longPreferencesKey("best_flow")] = 2400
        }
        val storage = GameStore(application, preferences)
        val migrated = storage.load(date).progress
        assertEquals(GameMode.FLOW, migrated.activeMode)
        assertEquals(level.copy(mode = GameMode.FLOW), migrated.flow)
        assertEquals(2, migrated.flowStages.unlockedLevel)
        assertEquals(campaign.totalPoints, migrated.stagePoints)
        assertEquals(2400L, migrated.bestFlow)
        assertTrue(storage.save(migrated).await())
        assertEquals(oldFlow, preferences.data.first()[stringPreferencesKey("flow")])
        assertEquals(migrated, storage.load(date).progress)
    }

    @Test
    fun flowAllowsMoreRetriesThanTheDailyCapWithoutSpendingDailyAttempts() = runBlocking {
        withModel(initial()) { model, storage, _ ->
            repeat(10) {
                playHint(model)
                onMain { model.restart() }
                saved(model)
                assertEquals(1, model.ui.value.progress!!.current.levelNumber)
                assertEquals(0, model.ui.value.progress!!.current.moves)
            }
            val result = storage.load(date).progress
            assertEquals(0, result.dailyAttempts.used)
            assertEquals(0L, result.flowStages.totalPoints)
            assertEquals(1, result.flowStages.unlockedLevel)
        }
    }

    @Test
    fun dailyQuotaPersistsAcrossModeSwitchAndReloadWithoutChargingResumeOrInvalidInput() = runBlocking {
        withModel(initial(GameMode.DAILY)) { model, storage, _ ->
            onMain {
                assertFalse(model.place(0, -10, -10))
                model.changeMode(GameMode.FLOW)
                model.changeMode(GameMode.DAILY)
                model.pause()
                model.resume()
            }
            saved(model)
            assertEquals(0, storage.load(date).progress.dailyAttempts.used)
            repeat(DAILY_ATTEMPT_LIMIT) { attempt ->
                playHint(model)
                assertEquals(attempt + 1, model.ui.value.progress!!.dailyAttempts.used)
                assertEquals(attempt + 1, storage.load(date).progress.dailyAttempts.used)
                if (attempt < DAILY_ATTEMPT_LIMIT - 1) {
                    onMain { model.restart() }
                    saved(model)
                }
            }
            val activeLast = model.ui.value.progress!!
            onMain { model.restart() }
            assertEquals(activeLast, model.ui.value.progress)
            while (!model.ui.value.progress!!.current.isWon && !GameEngine.isGameOver(model.ui.value.progress!!.current)) {
                playHint(model)
            }
            val finished = model.ui.value.progress!!
            assertTrue(finished.dailyLocked)
            onMain {
                assertFalse(model.nextLevel())
                model.restart()
                assertFalse(model.rotate(0))
                assertFalse(model.undo())
                assertFalse(model.place(0, 0, 0))
                model.changeMode(GameMode.FLOW)
                model.changeMode(GameMode.DAILY)
            }
            saved(model)
            assertEquals(3, storage.load(date).progress.dailyAttempts.used)
            assertEquals(finished.daily, storage.load(date).progress.daily)
        }
    }

    @Test
    fun dailyNewDayRestoresAllowanceButKeepsUnlockedLevelsAndPoints() = runBlocking {
        val before = GameEngine.stage(GameMode.DAILY, 1, date)
        val won = before.copy(lines = before.level!!.target, score = 600, moves = 10)
        val progress = initial(GameMode.DAILY).copy(dailyAttempts = DailyAttempts(date.toString(), 3, true)).withGame(won)
        withModel(progress) { model, storage, setDate ->
            assertTrue(model.ui.value.progress!!.dailyLocked)
            setDate(date.plusDays(1))
            onMain { model.resume() }
            saved(model)
            assertEquals(3, model.ui.value.progress!!.dailyAttempts.remaining)
            assertEquals(2, model.ui.value.progress!!.dailyStages.unlockedLevel)
            assertEquals(progress.dailyPoints, model.ui.value.progress!!.dailyPoints)
            onMain { assertTrue(model.nextLevel()) }
            saved(model)
            val next = storage.load(date.plusDays(1)).progress
            assertEquals(2, next.daily.levelNumber)
            assertEquals(date.plusDays(1).toString(), next.daily.challengeId)
            assertEquals(0, next.dailyAttempts.used)
            assertEquals(progress.flow, next.flow)
            setDate(date)
            onMain { model.resume() }
            assertEquals(next.dailyAttempts, model.ui.value.progress!!.dailyAttempts)
        }
    }

    @Test
    fun completionRewardIsSeparatePerModeAndCannotBeRepeatedOrBypassed() = runBlocking {
        val game = GameEngine.stage(GameMode.FLOW, 1, date)
        val board = MutableList(64) { 0 }.apply { repeat(7) { set(it, 1) } }
        val before = game.copy(board = board, tray = List(3) { Piece(0, 1) }, lines = game.level!!.target - 1)
        withModel(initial().copy(flow = before)) { model, storage, _ ->
            onMain {
                assertFalse(model.startLevel(2))
                assertFalse(model.nextLevel())
                model.changeMode(GameMode.LEVELS)
                assertTrue(model.place(0, 0, 7))
            }
            saved(model)
            val completed = model.ui.value.progress!!
            assertEquals(GameMode.FLOW, completed.activeMode)
            assertTrue(completed.current.isWon)
            assertTrue(completed.stagePoints > 0)
            assertEquals(0L, completed.dailyPoints)
            assertTrue(storage.save(completed).await())
            assertTrue(storage.save(completed).await())
            assertEquals(completed.stagePoints, storage.load(date).progress.stagePoints)
            onMain { assertTrue(model.nextLevel()) }
            saved(model)
            assertEquals(2, model.ui.value.progress!!.current.levelNumber)
            onMain {
                model.changeMode(GameMode.DAILY)
                assertFalse(model.startLevel(2))
                assertFalse(model.startLevel(1))
            }
            assertEquals(1, model.ui.value.progress!!.current.levelNumber)
        }
    }

    @Test
    fun staleSavesCannotReduceTheDailyAllowanceOrRewards() = runBlocking {
        val storage = GameStore(application, dataStore())
        val earlier = initial(GameMode.DAILY)
        val used = earlier.copy(dailyAttempts = DailyAttempts(date.toString(), 3), dailyPoints = 900)
        assertTrue(storage.save(used).await())
        assertTrue(storage.save(earlier).await())
        val loaded = storage.load(date).progress
        assertEquals(3, loaded.dailyAttempts.used)
        assertEquals(900L, loaded.dailyPoints)
        assertTrue(loaded.dailyLocked)
    }
}