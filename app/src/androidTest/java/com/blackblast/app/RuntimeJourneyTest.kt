package com.blackblast.app

import android.app.Application
import android.graphics.Bitmap
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.blackblast.core.*
import java.io.File
import java.time.LocalDate
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RuntimeJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val application get() = instrumentation.targetContext.applicationContext as Application
    private val owner = ViewModelStore()
    private lateinit var model: GameViewModel
    private lateinit var storage: GameStore
    private val outputDirectory get() = File(application.getExternalFilesDir(null), "qa").apply { mkdirs() }

    private fun host(initial: PlayerProgress) {
        val dataStore = PreferenceDataStoreFactory.create {
            File(application.cacheDir, "journey-${UUID.randomUUID()}.preferences_pb")
        }
        storage = GameStore(application, dataStore)
        runBlocking { assertTrue(storage.save(initial).await()) }
        instrumentation.runOnMainSync {
            model = GameViewModel(application, storage)
            owner.put("game", model)
        }
        compose.setContent { BlackBlastApp(model) }
        compose.waitUntil(10000) { model.ui.value.progress != null }
        compose.waitForIdle()
    }

    @After
    fun closeModel() {
        instrumentation.runOnMainSync { owner.clear() }
    }

    private fun awaitSave() {
        compose.waitUntil(10000) { !model.ui.value.saving }
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).performTouchInput { click() }
    }

    private fun screenshot(name: String, dialog: Boolean = false) {
        val node = if (dialog) compose.onNode(isDialog()) else compose.onNodeWithTag("game_screen")
        val bitmap = node.captureToImage().asAndroidBitmap()
        File(outputDirectory, "$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    @Test
    fun blockedFlowStageRequiresRetryWithoutUndoRescue() {
        val blocked = List(64) { if ((it / 8 + it % 8) % 2 == 0) 2 else 0 }
        val initial = PlayerProgress(
            flow = GameEngine.stage(GameMode.FLOW).copy(board = blocked, tray = listOf(Piece(0, 1), Piece(5, 3), Piece(5, 4))),
            sound = false, haptics = false,
        )
        host(initial)
        tap("tray_0")
        tap("cell_3_4")
        compose.onNodeWithTag("level_result_title").assertTextEquals("No space left")
        assertTrue(GameEngine.isGameOver(model.ui.value.progress!!.current))
        screenshot("stage-game-over-retry", dialog = true)
        compose.onNodeWithTag("rescue_undo").assertDoesNotExist()
        compose.runOnUiThread { assertFalse(model.undo()) }
        awaitSave()
        tap("retry_level")
        val reset = model.ui.value.progress!!
        assertEquals(0, reset.current.moves)
        assertEquals(0L, reset.current.score)
        assertEquals(3, reset.current.undosRemaining)
        assertTrue(reset.current.board.all { it == 0 })
        assertTrue(reset.current.tray.all { it != null })
        assertFalse(reset.canUndo)
        assertEquals(initial.daily, reset.daily)
        assertEquals(10L, reset.bestFlow)
        awaitSave()
        assertEquals(reset, runBlocking { storage.load().progress })
    }

    @Test
    fun completeDailyFromEmptyBoardThenReplayAndReturnToFlow() {
        val daily = GameEngine.stage(GameMode.DAILY, date = LocalDate.of(2026, 9, 8))
        host(PlayerProgress(daily = daily, activeMode = GameMode.DAILY, sound = false, haptics = false))
        val start = SystemClock.elapsedRealtime()
        val actions = JSONArray()
        repeat(200) {
            val game = model.ui.value.progress!!.current
            if (game.isWon) return@repeat
            assertFalse("Scripted route ended at ${game.moves} moves and ${game.lines} lines", GameEngine.isGameOver(game))
            if (game.canPulse && game.board.count { it != 0 } >= 30) {
                val index = game.board.indices.maxBy { origin ->
                    GameEngine.pulseArea(origin / 8, origin % 8).count { game.board[it] != 0 }
                }
                tap("pulse")
                tap("cell_${index / 8}_${index % 8}")
                actions.put(JSONObject().put("action", "pulse").put("index", index))
            } else {
                tap("hint")
                compose.waitUntil(10000) { !model.ui.value.findingHint }
                val hint = model.ui.value.hint
                assertNotNull("A legal route or charged Pulse is required", hint)
                val chosen = hint!!
                val visible = model.ui.value.progress!!.current.tray[chosen.slot]!!.shape.cells.first()
                tap("cell_${chosen.row + visible.row}_${chosen.column + visible.column}")
                actions.put(JSONObject().put("slot", chosen.slot).put("rotation", chosen.rotation)
                    .put("row", chosen.row).put("column", chosen.column))
            }
            assertTrue(GameEngine.isValid(model.ui.value.progress!!.current))
        }
        val won = model.ui.value.progress!!.current
        File(outputDirectory, "daily-route.json").writeText(JSONObject().put("date", daily.challengeId)
            .put("elapsedMs", SystemClock.elapsedRealtime() - start).put("moves", won.moves)
            .put("lines", won.lines).put("score", won.score).put("actions", actions).toString(2))
        assertTrue("Route did not reach the Daily goal within 200 actions", won.isWon)
        compose.onNodeWithTag("level_result_title").assertTextEquals("Level 1 completed")
        compose.onNodeWithTag("rescue_undo").assertDoesNotExist()
        screenshot("daily-complete", dialog = true)
        awaitSave()
        assertEquals(won, runBlocking { storage.load(LocalDate.of(2026, 9, 8)).progress.daily })
        tap("replay_level")
        assertEquals(0, model.ui.value.progress!!.current.lines)
        assertEquals(0L, model.ui.value.progress!!.current.score)
        tap("mode_flow")
        assertEquals(GameMode.FLOW, model.ui.value.progress!!.activeMode)
    }

    @Test
    fun restartConfirmationSettingsAndRapidInputsAreConsistent() {
        val initial = PlayerProgress(flow = GameState(tray = List(3) { Piece(0, it + 1) }), sound = false, haptics = false)
        host(initial)
        tap("tray_0")
        compose.onNodeWithTag("board").performTouchInput { repeat(20) { click(center); advanceEventTime(1) } }
        assertEquals(1, model.ui.value.progress!!.current.moves)
        assertEquals(10L, model.ui.value.progress!!.current.score)
        val saved = model.ui.value.progress!!.current
        tap("pause")
        screenshot("pause-controls", dialog = true)
        tap("sound_toggle")
        tap("haptics_toggle")
        compose.onNodeWithTag("sound_toggle").assertIsOn()
        compose.onNodeWithTag("haptics_toggle").assertIsOn()
        tap("restart_request")
        compose.onNodeWithText("Keep playing").performTouchInput { click() }
        assertEquals(saved, model.ui.value.progress!!.current)
        tap("restart_request")
        tap("confirm_restart")
        assertEquals(0L, model.ui.value.progress!!.current.score)
        assertEquals(3, model.ui.value.progress!!.current.undosRemaining)
        assertFalse(model.ui.value.paused)
        tap("mastery")
        screenshot("mastery-first-session", dialog = true)
        tap("close_mastery")
        tap("pause")
        tap("sound_toggle")
        tap("haptics_toggle")
        tap("resume")
        awaitSave()
        assertEquals(model.ui.value.progress, runBlocking { storage.load().progress })
    }

    @Test
    fun repeatedSessionsReportFrameCpuMemoryAndHintLatency() {
        host(PlayerProgress(flow = GameEngine.newGame(seed = 16231), sound = false, haptics = false))
        val frames = Collections.synchronizedList(mutableListOf<Long>())
        val frameThread = HandlerThread("blackblast-qa-frames").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 0L) frames.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION))
        }
        compose.runOnIdle { compose.activity.window.addOnFrameMetricsAvailableListener(listener, Handler(frameThread.looper)) }
        val start = SystemClock.elapsedRealtime()
        val cpuStart = Process.getElapsedCpuTime()
        val samples = JSONArray()
        val hintTimes = mutableListOf<Long>()
        try {
            repeat(30) { session ->
                if (session > 0) {
                    tap("pause")
                    tap("restart_request")
                    tap("confirm_restart")
                }
                repeat(3) {
                    val hintStart = SystemClock.elapsedRealtime()
                    tap("hint")
                    compose.waitUntil(10000) { model.ui.value.hint != null && !model.ui.value.findingHint }
                    hintTimes += SystemClock.elapsedRealtime() - hintStart
                    val hint = model.ui.value.hint!!
                    val cell = model.ui.value.progress!!.current.tray[hint.slot]!!.shape.cells.first()
                    tap("cell_${hint.row + cell.row}_${hint.column + cell.column}")
                }
                tap("pause")
                tap("resume")
                assertEquals(3, model.ui.value.progress!!.current.moves)
                assertTrue(GameEngine.isValid(model.ui.value.progress!!.current))
                if (session % 5 == 0 || session == 29) {
                    val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
                    samples.put(JSONObject().put("session", session + 1).put("elapsedMs", SystemClock.elapsedRealtime() - start)
                        .put("pssKb", memory.totalPss).put("javaUsedBytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                        .put("threads", Thread.getAllStackTraces().size))
                }
            }
            awaitSave()
            assertEquals(model.ui.value.progress, runBlocking { storage.load().progress })
        } finally {
            compose.runOnIdle { compose.activity.window.removeOnFrameMetricsAvailableListener(listener) }
            frameThread.quitSafely()
            frameThread.join(5000)
        }
        val sortedFrames = synchronized(frames) { frames.toList().sorted() }
        assertTrue("No rendered frame metrics were collected", sortedFrames.isNotEmpty())
        fun percentile(values: List<Long>, percent: Double): Long = values[((values.size - 1) * percent).toInt()]
        val report = JSONObject().put("scope", "debug emulator, instrumentation overhead included, not physical-device FPS")
            .put("sessions", 30).put("placements", 90).put("elapsedMs", SystemClock.elapsedRealtime() - start)
            .put("processCpuMs", Process.getElapsedCpuTime() - cpuStart).put("frames", sortedFrames.size)
            .put("frameP50Ns", percentile(sortedFrames, 0.5)).put("frameP95Ns", percentile(sortedFrames, 0.95))
            .put("framesOver16_67ms", sortedFrames.count { it > 16666667 })
            .put("hintInteractionP50Ms", percentile(hintTimes.sorted(), 0.5))
            .put("hintInteractionP95Ms", percentile(hintTimes.sorted(), 0.95)).put("memorySamples", samples)
        File(outputDirectory, "runtime-endurance.json").writeText(report.toString(2))
        screenshot("endurance-final-board")
    }
}