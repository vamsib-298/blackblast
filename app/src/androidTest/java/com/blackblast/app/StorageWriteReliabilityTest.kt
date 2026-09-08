package com.blackblast.app

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.platform.app.InstrumentationRegistry
import com.blackblast.core.GameState
import com.blackblast.core.Piece
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/**
 * Hypothesis C: repeated write failures must not deadlock the save channel or corrupt
 * the last acknowledged board; recovery must persist the latest in-memory state.
 *
 * These tests verify correct existing GameStore behavior; expected to pass without any fix.
 * [injection-failure] — write IOException injected via FaultableDataStore, not real storage.
 */
class StorageWriteReliabilityTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val application get() = instrumentation.targetContext.applicationContext as Application

    private fun faultableStore(): Pair<FaultableDataStore<Preferences>, GameStore> {
        val realDS = PreferenceDataStoreFactory.create {
            File(application.cacheDir, "write-rel-${UUID.randomUUID()}.preferences_pb")
        }
        val faultable = FaultableDataStore<Preferences>(realDS)
        return faultable to GameStore(application, faultable)
    }

    // ── Test 1: write failures do not deadlock; save returns false ─────────────

    @Test
    fun repeatedWriteFailuresReturnFalseWithoutDeadlock() = runBlocking {
        // [injection-failure] Disable all writes before queuing saves.
        val (faultable, store) = faultableStore()
        faultable.failWrites = true

        val progress = PlayerProgress(
            flow = GameState(tray = listOf(Piece(0, 1), Piece(0, 2), Piece(0, 3))),
            sound = false,
        )
        // Three consecutive write failures must return false but must not hang (no deadlock).
        withTimeout(8000) {
            assertFalse("[injection-failure] write 1 should fail", store.save(progress).await())
            assertFalse("[injection-failure] write 2 should fail", store.save(progress).await())
            assertFalse("[injection-failure] write 3 should fail", store.save(progress).await())
        }
    }

    // ── Test 2: recovery after failure persists the latest in-memory state ────

    @Test
    fun latestStateAfterWriteRecoveryMatchesLastSave() = runBlocking {
        // [injection-failure] Seed data, then fail writes, then recover.
        val (faultable, store) = faultableStore()

        val baseProgress = PlayerProgress(bestFlow = 100L, sound = false)
        assertTrue("initial save must succeed", store.save(baseProgress).await())

        // Two writes fail — their data must not reach storage.
        faultable.failWrites = true
        assertFalse("[injection-failure] write fails", store.save(PlayerProgress(bestFlow = 200L)).await())
        assertFalse("[injection-failure] write fails", store.save(PlayerProgress(bestFlow = 300L)).await())
        assertEquals("Rejected writes cannot replace the last acknowledged run", baseProgress, store.load().progress)

        // Recover: last write must be durable.
        faultable.failWrites = false
        val recoveredProgress = PlayerProgress(bestFlow = 400L, sound = false, haptics = false)
        assertTrue("recovered write must succeed", store.save(recoveredProgress).await())

        val loaded = store.load().progress
        assertEquals("The entire last successful write must be durable", recoveredProgress, loaded)
    }
}
