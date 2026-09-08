package com.blackblast.app

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.blackblast.core.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class CampaignProgressTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val application get() = instrumentation.targetContext.applicationContext as Application
    private fun isolatedDataStore() = PreferenceDataStoreFactory.create {
        File(application.cacheDir, "campaign-${UUID.randomUUID()}.preferences_pb")
    }
    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private suspend fun withModel(initial: PlayerProgress, test: suspend (GameViewModel, GameStore) -> Unit) {
        val store = GameStore(application, isolatedDataStore())
        assertTrue(store.save(initial).await())
        val owner = ViewModelStore()
        lateinit var model: GameViewModel
        onMain { model = GameViewModel(application, store); owner.put("game", model) }
        try {
            withTimeout(10000) { model.ui.first { it.progress != null } }
            test(model, store)
        } finally {
            onMain { owner.clear() }
        }
    }

    private suspend fun awaitSave(model: GameViewModel) {
        withTimeout(10000) { model.ui.first { !it.saving } }
    }

    @Test
    fun winIsSavedBeforeNextAndDuplicateNextCannotSkipALevel() = runBlocking {
        val level = GameEngine.stage(GameMode.FLOW).copy(board = List(64) { if (it < 7) 2 else 0 },
            tray = List(3) { Piece(0, 1) }, lines = 1, moves = 13)
        val initial = PlayerProgress(flow = level)
        withModel(initial) { model, store ->
            onMain {
                assertFalse(model.nextLevel())
                assertFalse(model.startLevel(2))
                assertTrue(model.place(0, 0, 7))
                assertFalse(model.place(1, 1, 1))
            }
            awaitSave(model)
            val won = store.load().progress
            assertTrue(won.flow.isWon)
            assertEquals(1, won.flow.levelNumber)
            assertEquals(2, won.flowStages.unlockedLevel)
            assertTrue(won.flowStages.totalPoints > 0)
            onMain {
                assertTrue(model.nextLevel())
                assertFalse(model.nextLevel())
                assertFalse(model.startLevel(3))
            }
            awaitSave(model)
            val next = store.load().progress
            assertEquals(GameEngine.stage(GameMode.FLOW, 2), next.flow)
            assertEquals(won.flowStages, next.flowStages)
            assertEquals(initial.levels, next.levels)
            assertEquals(initial.daily, next.daily)
        }
    }

    @Test
    fun failureRetriesExactlyTheSameStageWithoutRewards() = runBlocking {
        val initial = PlayerProgress(flow = GameEngine.stage(GameMode.FLOW)
            .copy(tray = List(3) { Piece(0, 1) }, moves = Levels.get(1)!!.moveLimit - 1))
        withModel(initial) { model, store ->
            onMain {
                assertTrue(model.place(0, 4, 4))
                assertFalse(model.nextLevel())
                assertFalse(model.undo())
                assertFalse(model.rotate(1))
                model.requestHint()
            }
            awaitSave(model)
            val failed = store.load().progress
            assertTrue(failed.flow.isOutOfMoves)
            assertEquals(0L, failed.flowStages.totalPoints)
            assertEquals(1, failed.flowStages.unlockedLevel)
            onMain { model.restart() }
            awaitSave(model)
            assertEquals(GameEngine.stage(GameMode.FLOW), store.load().progress.flow)
            assertNull(model.ui.value.hint)
        }
    }

    @Test
    fun remainingMovesAndUndoAreSeparatedFromOtherModesOnReload() = runBlocking {
        val level = GameEngine.stage(GameMode.FLOW)
        val piece = level.tray.first()!!
        val origin = (0 until 64).first { GameEngine.canPlace(level.board, piece, it / 8, it % 8) }
        val placed = GameEngine.place(level, 0, origin / 8, origin % 8)!!.state
        val progress = PlayerProgress().withGame(placed, undoFrom = level)
        val store = GameStore(application, isolatedDataStore())
        assertTrue(store.save(progress).await())
        val loaded = store.load().progress
        assertEquals(progress, loaded)
        assertTrue(loaded.canUndo)
        assertNotNull(loaded.flowUndo)
        assertNull(loaded.levelsUndo)
        assertNull(loaded.dailyUndo)
        assertEquals(level.movesRemaining!! - 1, loaded.current.movesRemaining)
    }

    @Test
    fun oldModeAndBoardArchiveSurviveStageIntegration() = runBlocking {
        val dataStore = isolatedDataStore()
        val flow = GameEngine.newGame(seed = 127)
        dataStore.edit {
            it[stringPreferencesKey("flow")] = SnapshotCodec.encode(flow)
            it[stringPreferencesKey("active_mode")] = "FLOW"
        }
        val loaded = GameStore(application, dataStore).load()
        assertFalse(loaded.recovered)
        assertEquals(GameEngine.stage(GameMode.FLOW), loaded.progress.current)
        assertEquals(SnapshotCodec.encode(flow), dataStore.data.first()[stringPreferencesKey("flow")])
        assertEquals(GameEngine.level(1), loaded.progress.levels)
        assertEquals(CampaignProgress(), loaded.progress.campaign)
    }

    @Test
    fun corruptCampaignDoesNotResetFlowOrDaily() = runBlocking {
        val dataStore = isolatedDataStore()
        val initial = PlayerProgress(bestFlow = 7000, palette = TilePalette.AURORA)
        val store = GameStore(application, dataStore)
        assertTrue(store.save(initial).await())
        dataStore.edit {
            it[stringPreferencesKey("campaign")] = "broken"
            it[stringPreferencesKey("levels")] = SnapshotCodec.encode(GameEngine.level(20))
        }
        val recovered = store.load()
        assertTrue(recovered.recovered)
        assertEquals(initial.flow, recovered.progress.flow)
        assertEquals(initial.daily, recovered.progress.daily)
        assertEquals(initial.palette, recovered.progress.activePalette)
        assertEquals(GameEngine.level(1), recovered.progress.levels)
        assertEquals(1, recovered.progress.campaign.unlockedLevel)
    }

    @Test
    fun acknowledgedRewardsNeverDuplicateOrRegressAcrossSaves() = runBlocking {
        val store = GameStore(application, isolatedDataStore())
        val original = PlayerProgress()
        val won = GameEngine.stage(GameMode.FLOW).copy(lines = 2, score = 900, moves = 8)
        val earned = original.withGame(won)
        assertTrue(store.save(earned).await())
        assertTrue(store.save(earned.withGame(won)).await())
        assertEquals(earned.flowStages, store.load().progress.flowStages)
        assertTrue(store.save(original).await())
        assertEquals(earned.flowStages, store.load().progress.flowStages)
    }

    @Test
    fun retryCancelsAnOldHintEvenWithIdenticalStartingState() = runBlocking {
        val initial = PlayerProgress()
        withModel(initial) { model, store ->
            onMain {
                model.requestHint()
                model.restart()
            }
            awaitSave(model)
            assertNull(model.ui.value.hint)
            assertFalse(model.ui.value.findingHint)
            assertEquals(GameEngine.stage(GameMode.FLOW), store.load().progress.current)
        }
    }

    @Test
    fun freshPlayerStartsAtLevelOneWithoutErasingLegacyDefaults() = runBlocking {
        val fresh = GameStore(application, isolatedDataStore()).load().progress
        assertEquals(GameMode.FLOW, fresh.activeMode)
        assertEquals(1, fresh.current.levelNumber)
        assertEquals(1, fresh.flowStages.unlockedLevel)
        assertEquals(GameMode.FLOW, PlayerProgress().activeMode)
    }

    @Test
    fun failedCompletionWriteThenRetryKeepsOneRewardRecord() = runBlocking {
        val faultable = FaultableDataStore(isolatedDataStore())
        val store = GameStore(application, faultable)
        val initial = PlayerProgress()
        assertTrue(store.save(initial).await())
        val won = GameEngine.stage(GameMode.FLOW).copy(lines = 2, score = 720, moves = 10)
        val completed = initial.withGame(won)
        faultable.failWrites = true
        assertFalse(store.save(completed).await())
        assertEquals(initial.flowStages, store.load().progress.flowStages)
        faultable.failWrites = false
        assertTrue(store.save(completed).await())
        assertTrue(store.save(completed.withGame(won)).await())
        val recovered = store.load().progress
        assertEquals(completed.flowStages, recovered.flowStages)
        assertEquals(1, recovered.flowStages.records.size)
        assertEquals(completed.flowStages.totalPoints, recovered.flowStages.totalPoints)
        assertEquals(won, recovered.flow)
    }
}