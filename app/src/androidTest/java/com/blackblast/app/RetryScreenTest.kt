package com.blackblast.app

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.blackblast.core.GameState
import com.blackblast.core.Piece
import com.blackblast.core.TilePalette
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Production composable Retry-screen contract:
 *  - No gameplay visible while load-error is set (progress == null).
 *  - "Retry" button (testTag="retry_load") is labelled and tappable.
 *  - After a successful retry the exact stored score/board is shown and loadError is cleared.
 *  - After a failed retry the screen stays recoverable (retry_load still tappable).
 *
 * [injection-failure] — FaultableDataStore injects the IOException; physical storage not exhausted.
 * State contract is verified; haptics/audio are not audible in tests.
 */
class RetryScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val application get() = instrumentation.targetContext.applicationContext as Application

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)

    // Non-trivial run: distinct board cells, tray rotation mix, score, lines, moves.
    private val nontrivialProgress = PlayerProgress(
        flow = GameState(
            board = List(64) { idx -> if (idx == 9 || idx == 18 || idx == 27) 1 else 0 },
            tray = listOf(Piece(0, 1), Piece(5, 2), Piece(10, 3)),
            score = 5000,
            lines = 3,
            moves = 10,
            randomState = 99999L,
        ),
        bestFlow = 5000L,
        sound = false,
        haptics = false,
        palette = TilePalette.PRISM,
    )

    private fun newFaultableDataStore(failNextRead: Boolean = false): Pair<FaultableDataStore<Preferences>, GameStore> {
        val realDS = PreferenceDataStoreFactory.create {
            File(application.cacheDir, "retry-ui-${UUID.randomUUID()}.preferences_pb")
        }
        val faultable = FaultableDataStore<Preferences>(realDS, failNextRead = failNextRead)
        return faultable to GameStore(application, faultable)
    }

    // ── Test 1: load-error shows error text and retry button; no gameplay visible ─

    @Test
    fun loadErrorShowsNoGameplayAndRetryButton() {
        val (_, store) = newFaultableDataStore(failNextRead = true)
        val owner = ViewModelStore()
        lateinit var model: GameViewModel
        onMain { model = GameViewModel(application, store); owner.put("g", model) }
        try {
            runBlocking { withTimeout(8000) { model.ui.first { it.loadError } } }
            compose.setContent { BlackBlastApp(model) }
            compose.waitForIdle()

            compose.onNodeWithTag("game_screen").assertDoesNotExist()
            compose.onNodeWithText("Storage unavailable").assertIsDisplayed()
            compose.onNodeWithTag("retry_load").assertIsDisplayed()
        } finally {
            onMain { owner.clear() }
        }
    }

    // ── Test 2: retry success loads exact saved run and clears the error screen ─

    @Test
    fun retrySuccessDisplaysExactSavedProgressAndClearsError() {
        // Seed the real DataStore before faulting reads.
        val realDS = PreferenceDataStoreFactory.create {
            File(application.cacheDir, "retry-ui-${UUID.randomUUID()}.preferences_pb")
        }
        val seedStore = GameStore(application, realDS)
        runBlocking { assertTrue("[injection-failure] seed save must succeed", seedStore.save(nontrivialProgress).await()) }

        val faultable = FaultableDataStore<Preferences>(realDS, failNextRead = true)
        val store = GameStore(application, faultable)
        val owner = ViewModelStore()
        lateinit var model: GameViewModel
        onMain { model = GameViewModel(application, store); owner.put("g", model) }
        try {
            runBlocking { withTimeout(8000) { model.ui.first { it.loadError } } }
            compose.setContent { BlackBlastApp(model) }
            compose.waitForIdle()

            compose.onNodeWithTag("game_screen").assertDoesNotExist()
            compose.onNodeWithTag("retry_load").assertIsDisplayed()

            // Recover IO, then tap Retry.
            faultable.failNextRead = false
            compose.onNodeWithTag("retry_load").performClick()

            runBlocking { withTimeout(10000) { model.ui.first { it.progress != null } } }
            compose.waitForIdle()

            val loaded = model.ui.value.progress!!.flow
            assertEquals("score must match stored run after retry", 5000L, loaded.score)
            assertEquals("board must match stored run after retry",
                nontrivialProgress.flow.board, loaded.board)
            assertEquals("bestFlow must be restored after retry",
                5000L, model.ui.value.progress!!.bestFlow)
            assertFalse("loadError must be false after successful retry",
                model.ui.value.loadError)
            compose.onNodeWithTag("game_screen").assertIsDisplayed()
        } finally {
            onMain { owner.clear() }
        }
    }

    // ── Test 3: retry failure keeps the retry button visible (screen stays recoverable) ─

    @Test
    fun retryFailureKeepsRetryButtonVisible() {
        val (faultable, store) = newFaultableDataStore(failNextRead = true)
        val owner = ViewModelStore()
        lateinit var model: GameViewModel
        onMain { model = GameViewModel(application, store); owner.put("g", model) }
        try {
            runBlocking { withTimeout(8000) { model.ui.first { it.loadError } } }
            compose.setContent { BlackBlastApp(model) }
            compose.waitForIdle()

            compose.onNodeWithTag("retry_load").assertIsDisplayed()

            // Retry while IO still faulted — second read fails too.
            compose.onNodeWithTag("retry_load").performClick()
            runBlocking { withTimeout(10000) { model.ui.first { it.loadError } } }
            compose.waitForIdle()

            compose.onNodeWithTag("retry_load").assertIsDisplayed()
            assertNull("progress must remain null after a failed retry", model.ui.value.progress)
        } finally {
            onMain { owner.clear() }
        }
    }
}
