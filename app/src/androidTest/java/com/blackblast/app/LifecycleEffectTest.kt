package com.blackblast.app

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.blackblast.core.GameState
import com.blackblast.core.Piece
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/**
 * Hypothesis B: GameViewModel.pause() does not clear ui.effect; the effect's id is
 * still non-null after pause, so a fresh Compose composition (recreation, back-from-background)
 * re-fires LaunchedEffect(ui.effect?.id), replaying haptics/audio already delivered.
 *
 * Note: physical audio/haptic audibility is UNVERIFIED; these tests verify the VM state contract.
 * Failing before fix; passing after fix (pause() clears effect).
 */
class LifecycleEffectTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val application get() = instrumentation.targetContext.applicationContext as Application

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private suspend fun withModel(
        initial: PlayerProgress,
        action: suspend (GameViewModel) -> Unit,
    ) {
        val dataStore = PreferenceDataStoreFactory.create {
            File(application.cacheDir, "lifecycle-${UUID.randomUUID()}.preferences_pb")
        }
        val store = GameStore(application, dataStore)
        assertTrue(store.save(initial).await())
        lateinit var model: GameViewModel
        val owner = ViewModelStore()
        onMain { model = GameViewModel(application, store); owner.put("g", model) }
        try {
            withTimeout(10000) { model.ui.first { it.progress != null } }
            action(model)
        } finally {
            onMain { owner.clear() }
        }
    }

    // ── Test 1: pause must clear the transient effect ─────────────────────────

    @Test
    fun pauseClearsEffectPreventingReplayOnResume() = runBlocking {
        val initial = PlayerProgress(
            flow = GameState(tray = listOf(Piece(0, 1), Piece(0, 2), Piece(0, 3))),
        )
        withModel(initial) { model ->
            onMain { assertTrue("place must succeed on empty board", model.place(0, 1, 1)) }
            assertNotNull("effect must be set immediately after a valid placement", model.ui.value.effect)

            onMain { model.pause() }

            // CORRECT: pause must clear the transient effect so LaunchedEffect(effect?.id)
            // does not fire again on activity recreation / resume from background.
            // BUG (before fix): pause() set paused=true but left effect unchanged.
            assertNull(
                "effect must be null after pause() — retained effect replays haptics/audio on Compose recreation",
                model.ui.value.effect,
            )
        }
    }

    // ── Test 2 (post-fix): resume after pause does not restore the old effect ─

    @Test
    fun resumeAfterPauseKeepsEffectClearedAndAllowsNewMoves() = runBlocking {
        val initial = PlayerProgress(
            flow = GameState(tray = listOf(Piece(0, 1), Piece(0, 2), Piece(0, 3))),
        )
        withModel(initial) { model ->
            onMain { assertTrue(model.place(0, 1, 1)) }
            val effectIdBeforePause = model.ui.value.effect!!.id

            // Simulate lifecycle stop → resume.
            onMain { model.pause() }
            assertNull("effect must be cleared on pause", model.ui.value.effect)

            onMain { model.resume() }
            assertNull("effect must still be null after resume — no ghost replay", model.ui.value.effect)

            // New move after resume creates a fresh effect (not the old one).
            onMain { assertTrue(model.place(1, 2, 2)) }  // slot 0 is exhausted; use slot 1
            val effectIdAfterResume = model.ui.value.effect!!.id
            assertTrue("new move after resume must produce a new effect id", effectIdAfterResume > effectIdBeforePause)
        }
    }
}
