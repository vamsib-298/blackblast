package com.blackblast.app

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.blackblast.core.GameEngine
import com.blackblast.core.GameMode
import com.blackblast.core.GameState
import com.blackblast.core.Piece
import com.blackblast.core.TilePalette
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class GameViewModelTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private suspend fun withModel(
        initial: PlayerProgress,
        action: suspend (GameViewModel, GameStore) -> Unit,
    ) {
        val application = instrumentation.targetContext.applicationContext as Application
        val dataStore = PreferenceDataStoreFactory.create {
            File(application.cacheDir, "viewmodel-${UUID.randomUUID()}.preferences_pb")
        }
        val storage = GameStore(application, dataStore)
        assertTrue(storage.save(initial).await())
        lateinit var model: GameViewModel
        val owner = ViewModelStore()
        onMain {
            model = GameViewModel(application, storage)
            owner.put("game", model)
        }
        try {
            withTimeout(10000) { model.ui.first { it.progress != null } }
            action(model, storage)
        } finally {
            onMain { owner.clear() }
        }
    }

    private suspend fun awaitSaved(model: GameViewModel) {
        withTimeout(10000) { model.ui.first { it.progress != null && !it.saving } }
    }

    @Test
    fun actualModelRotatesFindsAHintAndPersistsUndo() = runBlocking {
        val initial = PlayerProgress(flow = GameState(tray = listOf(Piece(8, 3), Piece(0, 1), Piece(5, 2))))
        withModel(initial) { model, storage ->
            onMain { assertTrue(model.rotate(0)) }
            assertEquals(1, model.ui.value.progress!!.current.tray[0]!!.rotation)
            onMain { model.requestHint() }
            val hinted = withTimeout(10000) { model.ui.first { it.hint != null && !it.findingHint } }
            val suggestion = hinted.hint!!
            val before = hinted.progress!!.current
            assertEquals(initial.flow.board, before.board)
            assertEquals(initial.flow.randomState, before.randomState)
            assertEquals(0, before.moves)
            onMain { assertTrue(model.place(suggestion.slot, suggestion.row, suggestion.column)) }
            assertTrue(model.ui.value.progress!!.canUndo)
            assertNull(model.ui.value.hint)
            awaitSaved(model)
            assertTrue(storage.load().progress.canUndo)
            onMain { assertTrue(model.undo()) }
            awaitSaved(model)
            val restored = storage.load().progress
            assertEquals(before.copy(undosRemaining = 2), restored.current)
            assertFalse(restored.canUndo)
            assertTrue(restored.best > 0)
        }
    }

    @Test
    fun switchingModeCancelsAnInFlightHintWithoutOverwritingTheNewBoard() = runBlocking {
        val initial = PlayerProgress(flow = GameEngine.newGame(seed = 84523))
        withModel(initial) { model, storage ->
            onMain {
                model.requestHint()
                model.changeMode(GameMode.DAILY)
            }
            awaitSaved(model)
            assertNull(model.ui.value.hint)
            assertFalse(model.ui.value.findingHint)
            val loaded = storage.load().progress
            assertEquals(GameMode.DAILY, loaded.activeMode)
            assertEquals(initial.daily, loaded.current)
            assertEquals(initial.flow, loaded.flow)
        }
    }

    @Test
    fun pauseRejectsGameActionsAndLockedPalettesCannotBeSaved() = runBlocking {
        val initial = PlayerProgress(flow = GameState(tray = List(3) { Piece(0, 1) }))
        withModel(initial) { model, storage ->
            onMain {
                model.pause()
                assertFalse(model.place(0, 1, 1))
                assertFalse(model.rotate(0))
                assertFalse(model.undo())
                model.requestHint()
                assertFalse(model.setPalette(TilePalette.AURORA))
            }
            assertEquals(initial.flow, model.ui.value.progress!!.current)
            assertNull(model.ui.value.hint)
            assertFalse(model.ui.value.findingHint)
            assertEquals(TilePalette.PRISM, storage.load().progress.activePalette)
            onMain {
                model.resume()
                assertTrue(model.place(0, 1, 1))
            }
            awaitSaved(model)
            assertEquals(1, storage.load().progress.current.moves)
        }
    }
    @Test
    fun rotatingAnotherPieceAfterAPlaceDoesNotDropTheUndoCheckpoint() = runBlocking {
        val initial = PlayerProgress(flow = GameState(tray = List(3) { Piece(0, 1) }))
        withModel(initial) { model, storage ->
            onMain { assertTrue(model.place(0, 1, 1)) }
            assertTrue(model.ui.value.progress!!.canUndo)
            onMain { assertTrue(model.rotate(1)) }
            assertTrue(model.ui.value.progress!!.canUndo)
            onMain { assertTrue(model.undo()) }
            awaitSaved(model)
            val restored = storage.load().progress
            assertEquals(initial.flow.copy(undosRemaining = 2), restored.current)
        }
    }

    @Test
    fun eachModeKeepsItsUndoAndRestartResetsOnlyTheCurrentRun() = runBlocking {
        val flow = GameState(tray = List(3) { Piece(0, 1) })
        val daily = GameEngine.daily(LocalDate.now()).copy(tray = List(3) { Piece(0, 2) })
        val dailyAfter = GameEngine.place(daily, 0, 3, 3)!!.state
        val initial = PlayerProgress(flow = flow, daily = daily).withGame(dailyAfter, undoFrom = daily)
        withModel(initial) { model, storage ->
            onMain {
                assertTrue(model.place(0, 4, 4))
                model.changeMode(GameMode.DAILY)
                assertTrue(model.undo())
                model.changeMode(GameMode.FLOW)
            }
            assertTrue(model.ui.value.progress!!.canUndo)
            val unchangedDaily = model.ui.value.progress!!.daily
            onMain { model.restart() }
            awaitSaved(model)
            val loaded = storage.load().progress
            assertEquals(0, loaded.current.moves)
            assertEquals(3, loaded.current.undosRemaining)
            assertFalse(loaded.canUndo)
            assertEquals(unchangedDaily, loaded.daily)
            assertEquals(daily.copy(undosRemaining = 2), loaded.daily)
            assertTrue(loaded.bestFlow >= 10)
        }
    }
}