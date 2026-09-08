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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/**
 * Hypothesis A: GameViewModel sets progress=PlayerProgress() on IOException,
 * allowing a subsequent settings/action write to overwrite a durable stored run.
 *
 * Failing before fix; passing after fix (progress stays null on load error).
 * Labelled [injection-failure] — uses FaultableDataStore, not real storage exhaustion.
 */
class IoErrorIsolationTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val application get() = instrumentation.targetContext.applicationContext as Application

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)

    /** Creates an isolated DataStore backed by a temp file. */
    private fun isolatedDataStore() = PreferenceDataStoreFactory.create {
        File(application.cacheDir, "io-err-${UUID.randomUUID()}.preferences_pb")
    }

    /**
     * Waits for VM init to settle — either a successful load (progress != null) or an IO error
     * (message != null is set by the IOException handler in both before- and after-fix code).
     */
    private suspend fun awaitVmInit(model: GameViewModel) {
        withTimeout(8000) { model.ui.first { it.progress != null || it.message != null } }
    }

    private fun storedProgress(): PlayerProgress {
        val before = GameState(
            board = List(64) { when (it) { 0 -> 1; 8 -> 3; 22 -> 5; else -> 0 } },
            tray = listOf(Piece(8, 3, 1), Piece(0, 2), Piece(5, 4)),
            score = 5070,
            lines = 4,
            moves = 12,
            charge = 4,
            combo = 2,
            randomState = 99881,
            undosRemaining = 2,
        )
        val after = GameEngine.place(before, 1, 5, 5)!!.state
        return PlayerProgress(
            flow = before,
            daily = GameEngine.daily(LocalDate.now()),
            bestFlow = 8000,
            bestDaily = 1400,
            palette = TilePalette.AURORA,
            sound = false,
            haptics = false,
        ).withGame(after, undoFrom = before)
    }

    // ── Test 1: load-error must not install playable progress ─────────────────

    @Test
    fun loadIoFailureKeepsProgressNullNotFreshState() = runBlocking {
        // [injection-failure] FaultableDataStore makes data.first() throw IOException.
        val faultable = FaultableDataStore(isolatedDataStore(), failNextRead = true)
        val store = GameStore(application, faultable)
        lateinit var model: GameViewModel
        val owner = ViewModelStore()
        onMain { model = GameViewModel(application, store); owner.put("g", model) }
        try {
            awaitVmInit(model)
            // CORRECT: load error must not install a playable PlayerProgress().
            // BUG (before fix): progress = PlayerProgress() is installed, enabling silent data loss.
            assertNull(
                "load-error must not install a fresh PlayerProgress() — game actions would overwrite real stored data",
                model.ui.value.progress,
            )
        } finally {
            onMain { owner.clear() }
        }
    }

    // ── Test 2: no write must reach the store while in load-error state ───────

    @Test
    fun loadIoFailureWithRecoveredIoDoesNotOverwriteStoredRun() = runBlocking {
        // [injection-failure] Preload a nontrivial run, then make the first read fail.
        val realDataStore = isolatedDataStore()
        val nontrivial = storedProgress()
        val seed = GameStore(application, realDataStore)
        assertTrue("pre-condition: nontrivial run must be stored", seed.save(nontrivial).await())

        val faultable = FaultableDataStore(realDataStore, failNextRead = true)
        val store = GameStore(application, faultable)
        lateinit var model: GameViewModel
        val owner = ViewModelStore()
        onMain { model = GameViewModel(application, store); owner.put("g", model) }
        try {
            awaitVmInit(model)

            // IO recovers after the failed load.
            faultable.failNextRead = false

            // Trigger a settings write — will call persist() if progress is non-null (buggy).
            var saveCalledInErrorState = false
            onMain {
                model.setSound(true)
                model.setHaptics(true)
                assertFalse(model.place(0, 4, 4))
                assertFalse(model.rotate(0))
                assertFalse(model.pulse(1, 1))
                assertFalse(model.undo())
                assertFalse(model.setPalette(TilePalette.PRISM))
                model.restart()
                model.changeMode(GameMode.DAILY)
                model.requestHint()
                saveCalledInErrorState = model.ui.value.saving
            }

            // CORRECT: no write must happen — progress was null, setSound returns early.
            // BUG (before fix): progress = PlayerProgress(), persist() was called, saving=true.
            assertFalse(
                "persist must not be called while in load-error state — would silently overwrite the stored run",
                saveCalledInErrorState,
            )

            // Verify the store data is undamaged.
            assertEquals(0, faultable.writeCount.get())
            val loaded = store.load().progress
            assertEquals(
                "Every stored board, tray, RNG, undo, score, palette and setting must survive a failed read",
                nontrivial, loaded,
            )
        } finally {
            onMain { owner.clear() }
        }
    }

    // ── Test 3 (post-fix): retryLoad restores the exact stored run ────────────

    @Test
    fun retryLoadAfterIoFailureRestoresExactStoredRun() = runBlocking {
        // [injection-failure] Preload a distinct run, fail the first load, then retry.
        val realDataStore = isolatedDataStore()
        val stored = storedProgress()
        assertTrue(GameStore(application, realDataStore).save(stored).await())

        val faultable = FaultableDataStore(realDataStore, failNextRead = true)
        val store = GameStore(application, faultable)
        lateinit var model: GameViewModel
        val owner = ViewModelStore()
        onMain { model = GameViewModel(application, store); owner.put("g", model) }
        try {
            awaitVmInit(model)
            assertTrue("loadError must be true after IOException", model.ui.value.loadError)
            assertNull("progress must be null in load-error state", model.ui.value.progress)

            // Recover IO and retry.
            faultable.failNextRead = false
            onMain { model.retryLoad() }
            withTimeout(8000) { model.ui.first { it.progress != null } }

            val loaded = model.ui.value.progress!!
            assertEquals("Retry must restore the entire durable run", stored, loaded)
            assertEquals(stored, store.load().progress)
            assertEquals(0, faultable.writeCount.get())
            assertFalse("loadError must be false after successful retryLoad", model.ui.value.loadError)
        } finally {
            onMain { owner.clear() }
        }
    }

    @Test
    fun rapidRetryHasOnlyOneInFlightReadAndFailureRemainsRecoverable() = runBlocking {
        val dataStore = isolatedDataStore()
        val stored = storedProgress()
        assertTrue(GameStore(application, dataStore).save(stored).await())
        val faultable = FaultableDataStore(dataStore, failNextRead = true)
        val storage = GameStore(application, faultable)
        lateinit var model: GameViewModel
        val owner = ViewModelStore()
        onMain { model = GameViewModel(application, storage); owner.put("game", model) }
        try {
            awaitVmInit(model)
            val barrier = CompletableDeferred<Unit>()
            faultable.readBarrier = barrier
            onMain { repeat(20) { model.retryLoad() } }
            assertEquals(2, faultable.readCount.get())
            assertNull(model.ui.value.progress)
            assertFalse(model.ui.value.loadError)
            barrier.complete(Unit)
            withTimeout(8000) { model.ui.first { it.loadError } }
            assertNull(model.ui.value.progress)
            faultable.failNextRead = false
            faultable.readBarrier = null
            onMain { model.retryLoad() }
            val recovered = withTimeout(8000) { model.ui.first { it.progress != null } }
            assertEquals(stored, recovered.progress)
            assertEquals(3, faultable.readCount.get())
            assertEquals(0, faultable.writeCount.get())
        } finally {
            onMain { owner.clear() }
        }
    }
}
