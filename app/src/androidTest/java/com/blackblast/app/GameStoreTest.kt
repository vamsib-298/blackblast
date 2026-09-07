package com.blackblast.app

import androidx.test.platform.app.InstrumentationRegistry
import com.blackblast.core.GameEngine
import com.blackblast.core.GameMode
import com.blackblast.core.GameState
import com.blackblast.core.Piece
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GameStoreTest {
    @Test
    fun queuedSavesKeepTheNewestBoardAndSurviveReload() = runBlocking {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as BlackBlastApplication
        val store = application.gameStore
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
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as BlackBlastApplication
        val store = application.gameStore
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
}