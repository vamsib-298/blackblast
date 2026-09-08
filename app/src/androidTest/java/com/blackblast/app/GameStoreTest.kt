package com.blackblast.app

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.platform.app.InstrumentationRegistry
import com.blackblast.core.GameEngine
import com.blackblast.core.GameMode
import com.blackblast.core.GameState
import com.blackblast.core.Piece
import com.blackblast.core.SnapshotCodec
import com.blackblast.core.TilePalette
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class GameStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun isolatedDataStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create {
        File(context.cacheDir, "store-${UUID.randomUUID()}.preferences_pb")
    }

    @Test
    fun queuedSavesKeepTheNewestBoardAndSurviveReload() = runBlocking {
        val store = GameStore(context, isolatedDataStore())
        val baseline = store.load().progress
        try {
            val initial = PlayerProgress(flow = GameState(tray = List(3) { Piece(0, 1) }), sound = false)
            val first = initial.withGame(GameEngine.place(initial.flow, 0, 2, 3)!!.state)
            val second = first.withGame(GameEngine.place(first.flow, 1, 4, 5)!!.state).copy(haptics = false, activeMode = GameMode.DAILY)
            val firstWrite = store.save(first)
            val secondWrite = store.save(second)
            assertTrue(firstWrite.await())
            assertTrue(secondWrite.await())
            val loaded = store.load().progress
            assertEquals(second.flow, loaded.flow)
            assertEquals(GameMode.DAILY, loaded.activeMode)
            assertFalse(loaded.sound)
            assertFalse(loaded.haptics)
            assertTrue(loaded.bestFlow >= second.flow.score)
        } finally {
            assertTrue(store.save(baseline).await())
        }
    }

    @Test
    fun newDateReplacesOnlyTheDailyBoard() = runBlocking {
        val store = GameStore(context, isolatedDataStore())
        val baseline = store.load().progress
        try {
            val date = LocalDate.of(2026, 9, 8)
            val progress = baseline.copy(daily = GameEngine.daily(date))
            assertTrue(store.save(progress).await())
            val loaded = store.load(date.plusDays(1)).progress
            assertEquals(date.plusDays(1).toString(), loaded.daily.challengeId)
            assertEquals(progress.flow, loaded.flow)
            assertEquals(0L, loaded.daily.score)
        } finally {
            assertTrue(store.save(baseline).await())
        }
    }

    @Test
    fun undoRotationAndUnlockedPaletteSurviveDurableReload() = runBlocking {
        val store = GameStore(context, isolatedDataStore())
        val before = GameState(tray = listOf(Piece(8, 3, 1), Piece(0, 1), Piece(5, 2)), score = 1000, undosRemaining = 2)
        val after = GameEngine.place(before, 1, 4, 4)!!.state
        val progress = PlayerProgress(flow = before, bestFlow = 1000, palette = TilePalette.ARCADE)
            .withGame(after, undoFrom = before)
        assertTrue(store.save(progress).await())
        val loaded = store.load().progress
        assertEquals(after, loaded.flow)
        assertEquals(TilePalette.ARCADE, loaded.activePalette)
        assertTrue(loaded.canUndo)
        val restored = GameEngine.undo(loaded.current, loaded.undoCheckpoint)!!
        assertTrue(store.save(loaded.withGame(restored)).await())
        val reloaded = store.load().progress
        assertEquals(before.copy(undosRemaining = 1), reloaded.current)
        assertFalse(reloaded.canUndo)
        assertEquals(1010L, reloaded.personalBest)
    }

    @Test
    fun lockedPalettesFallBackWithoutChangingTheBoard() = runBlocking {
        val store = GameStore(context, isolatedDataStore())
        val progress = PlayerProgress(palette = TilePalette.AURORA)
        assertTrue(store.save(progress).await())
        val loaded = store.load().progress
        assertEquals(TilePalette.PRISM, loaded.activePalette)
        assertEquals(progress.flow, loaded.flow)
    }

    @Test
    fun legacyPreferencesKeepTheirBoardBestsAndSettings() = runBlocking {
        val dataStore = isolatedDataStore()
        val legacy = """{"tray":[{"shapeId":8,"color":3},null,{"shapeId":5,"color":2}],"score":440,"moves":8,"randomState":99881}"""
        dataStore.edit {
            it[stringPreferencesKey("flow")] = legacy
            it[longPreferencesKey("best_flow")] = 2400
        }
        val store = GameStore(context, dataStore)
        val loaded = store.load()
        assertFalse(loaded.recovered)
        assertEquals(1, loaded.progress.flow.levelNumber)
        assertEquals(legacy, dataStore.data.first()[stringPreferencesKey("flow")])
        assertEquals(2400L, loaded.progress.bestFlow)
        assertEquals(3, loaded.progress.flow.undosRemaining)
        assertNull(loaded.progress.flowUndo)
        assertEquals(TilePalette.PRISM, loaded.progress.activePalette)
        assertTrue(store.save(loaded.progress).await())
        assertEquals(loaded.progress.flow, store.load().progress.flow)
        assertEquals(legacy, dataStore.data.first()[stringPreferencesKey("flow")])
    }

    @Test
    fun aCorruptUndoIsDroppedWithoutResettingTheRun() = runBlocking {
        val dataStore = isolatedDataStore()
        val game = GameEngine.newGame(seed = 9233)
        dataStore.edit {
            it[stringPreferencesKey("flow_stage_run")] = SnapshotCodec.encode(game)
            it[stringPreferencesKey("flow_stage_undo")] = "broken"
        }
        val loaded = GameStore(context, dataStore).load()
        assertTrue(loaded.recovered)
        assertEquals(game, loaded.progress.flow)
        assertNull(loaded.progress.flowUndo)
    }
}