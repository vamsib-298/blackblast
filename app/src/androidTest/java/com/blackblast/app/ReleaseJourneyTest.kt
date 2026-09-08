package com.blackblast.app

import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.os.Process
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.blackblast.core.CampaignCodec
import com.blackblast.core.DailyAttemptsCodec
import com.blackblast.core.SnapshotCodec
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReleaseJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun progressRecord(progress: PlayerProgress): JSONObject = JSONObject()
        .put("flow", SnapshotCodec.encode(progress.flow))
        .put("daily", SnapshotCodec.encode(progress.daily))
        .put("levels", SnapshotCodec.encode(progress.levels))
        .put("campaign", CampaignCodec.encode(progress.campaign))
        .put("flowStages", CampaignCodec.encode(progress.flowStages))
        .put("dailyStages", CampaignCodec.encode(progress.dailyStages))
        .put("dailyAttempts", DailyAttemptsCodec.encode(progress.dailyAttempts))
        .put("dailyPoints", progress.dailyPoints).put("legacyLevelCredit", progress.legacyLevelCredit)
        .put("activeMode", progress.activeMode.name)
        .put("bestFlow", progress.bestFlow).put("bestDaily", progress.bestDaily)
        .put("sound", progress.sound).put("haptics", progress.haptics).put("palette", progress.palette.name)
        .put("flowUndo", progress.flowUndo?.let(SnapshotCodec::encodeCheckpoint) ?: JSONObject.NULL)
        .put("dailyUndo", progress.dailyUndo?.let(SnapshotCodec::encodeCheckpoint) ?: JSONObject.NULL)
        .put("levelsUndo", progress.levelsUndo?.let(SnapshotCodec::encodeCheckpoint) ?: JSONObject.NULL)

    @Test
    fun nonDebuggableReleasePlaysSavesAndSurvivesActivityRecreation() {
        val started = SystemClock.elapsedRealtime()
        assertEquals("This check must run against a release-mode target, not debug", 0,
            compose.activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        lateinit var model: GameViewModel
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[GameViewModel::class.java] }
        compose.waitUntil(10000) { model.ui.value.progress != null }
        if (model.ui.value.paused) compose.onNodeWithTag("resume").performTouchInput { click() }
        val initial = model.ui.value.progress!!
        assertFalse("The preserved QA run must have a playable board", initial.current.isWon)
        var beforeLastMove = initial.current
        repeat(3) {
            compose.onNodeWithTag("hint").performTouchInput { click() }
            compose.waitUntil(10000) { model.ui.value.hint != null && !model.ui.value.findingHint }
            val hint = model.ui.value.hint!!
            beforeLastMove = model.ui.value.progress!!.current
            val cell = beforeLastMove.tray[hint.slot]!!.shape.cells.first()
            compose.onNodeWithTag("cell_${hint.row + cell.row}_${hint.column + cell.column}", useUnmergedTree = true)
                .performTouchInput { click() }
        }
        assertEquals(initial.current.moves + 3, model.ui.value.progress!!.current.moves)
        assertTrue(model.ui.value.progress!!.current.score > initial.current.score)
        if (beforeLastMove.undosRemaining > 0) {
            compose.onNodeWithTag("undo").assertIsEnabled().performTouchInput { click() }
            assertEquals(beforeLastMove.copy(undosRemaining = beforeLastMove.undosRemaining - 1), model.ui.value.progress!!.current)
        }
        compose.onNodeWithTag("mastery").performTouchInput { click() }
        compose.onNodeWithTag("mastery_title").assertIsDisplayed()
        compose.onNodeWithTag("close_mastery").performTouchInput { click() }
        compose.onNodeWithTag("pause").performTouchInput { click() }
        compose.onNodeWithTag("paused_title").assertIsDisplayed()
        compose.onNodeWithTag("resume").performTouchInput { click() }
        compose.waitUntil(10000) { !model.ui.value.saving }
        val played = model.ui.value.progress!!
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("score").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[GameViewModel::class.java] }
        assertEquals(played, model.ui.value.progress)
        compose.onNodeWithTag("resume").performTouchInput { click() }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("paused_title").assertIsDisplayed()
        compose.onNodeWithTag("resume").performTouchInput { click() }
        assertEquals(played, model.ui.value.progress)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "qa").apply { mkdirs() }
        File(directory, "release-checkpoint.json").writeText(JSONObject().put("pid", Process.myPid())
            .put("progress", progressRecord(played)).toString(2))
        File(directory, "release-journey.json").writeText(JSONObject().put("debuggable", false)
            .put("elapsedMs", SystemClock.elapsedRealtime() - started).put("placements", 3)
            .put("score", played.current.score).put("moves", played.current.moves)
            .put("recreationPreservedEntireProgress", true).put("backgroundResumePreservedEntireProgress", true)
            .put("scope", "QA-signed release on read-only emulator; not production signing or process-death proof").toString(2))
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        assertNotNull(screenshot)
        File(directory, "release-gameplay.png").outputStream().use { screenshot!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot?.recycle()
    }

    @Test
    fun processRestartRestoresThePreviouslyAcknowledgedReleaseSave() {
        assertEquals(0, compose.activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "qa")
        val checkpoint = File(directory, "release-checkpoint.json")
        assertTrue("Run the release gameplay method in a separate invocation first", checkpoint.isFile)
        val expected = JSONObject(checkpoint.readText())
        assertNotEquals("Separate instrumentation invocations are required to prove process restart", expected.getInt("pid"), Process.myPid())
        lateinit var model: GameViewModel
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[GameViewModel::class.java] }
        compose.waitUntil(10000) { model.ui.value.progress != null }
        val actual = progressRecord(model.ui.value.progress!!)
        val expectedProgress = expected.getJSONObject("progress")
        expectedProgress.keys().forEach { key ->
            if (key in setOf("bestFlow", "bestDaily", "dailyPoints", "legacyLevelCredit")) {
                assertEquals("Persisted field $key changed on process restart", expectedProgress.getLong(key), actual.getLong(key))
            } else {
                assertEquals("Persisted field $key changed on process restart", expectedProgress.get(key), actual.get(key))
            }
        }
        compose.onNodeWithTag("board").assertIsDisplayed()
        File(directory, "release-process-restart.json").writeText(JSONObject()
            .put("previousPid", expected.getInt("pid")).put("currentPid", Process.myPid())
            .put("matchedFields", expectedProgress.length()).put("entireProgressRestored", true)
            .put("debuggable", false).toString(2))
    }
}