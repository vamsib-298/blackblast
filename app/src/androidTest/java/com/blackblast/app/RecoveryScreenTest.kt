package com.blackblast.app

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.blackblast.core.GameState
import com.blackblast.core.Piece
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RecoveryScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun actualAppRetryScreenBlocksPlayAndRestoresTheExactRun() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as Application
        val dataStore = PreferenceDataStoreFactory.create {
            File(application.cacheDir, "retry-ui-${UUID.randomUUID()}.preferences_pb")
        }
        val before = PlayerProgress(
            flow = GameState(board = List(64) { if (it == 27) 4 else 0 },
                tray = listOf(Piece(8, 2, 1), null, Piece(5, 3)), score = 1234, moves = 19, randomState = 43218),
            bestFlow = 1234,
            sound = false,
            haptics = false,
        )
        assertTrue(GameStore(application, dataStore).save(before).await())
        val faultable = FaultableDataStore(dataStore, failNextRead = true)
        val store = GameStore(application, faultable)
        val owner = ViewModelStore()
        lateinit var model: GameViewModel
        instrumentation.runOnMainSync {
            model = GameViewModel(application, store)
            owner.put("game", model)
        }
        try {
            compose.setContent { BlackBlastApp(model) }
            compose.waitUntil(10000) { compose.onAllNodesWithTag("retry_load").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("retry_load").assertIsDisplayed()
            compose.onNodeWithTag("board").assertDoesNotExist()
            val barrier = CompletableDeferred<Unit>()
            faultable.readBarrier = barrier
            compose.onNodeWithTag("retry_load").performClick()
            compose.onNodeWithTag("retry_load").assertDoesNotExist()
            compose.onNodeWithTag("board").assertDoesNotExist()
            barrier.complete(Unit)
            compose.waitUntil(10000) { compose.onAllNodesWithTag("retry_load").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(2, faultable.readCount.get())
            faultable.failNextRead = false
            faultable.readBarrier = null
            compose.onNodeWithTag("retry_load").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("board").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("retry_load").assertDoesNotExist()
            compose.onNodeWithTag("board").assertIsDisplayed()
            compose.onNodeWithTag("score").assertTextEquals("1,234")
            assertEquals(before, model.ui.value.progress)
            assertEquals(before, store.load().progress)
            assertEquals(0, faultable.writeCount.get())
        } finally {
            instrumentation.runOnMainSync { owner.clear() }
        }
    }
}