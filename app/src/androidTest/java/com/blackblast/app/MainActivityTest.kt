package com.blackblast.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MainActivityTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun waitForGame() {
        compose.waitUntil(10000) {
            runCatching { compose.onNodeWithTag("score").fetchSemanticsNode() }.isSuccess
        }
    }

    @Test
    fun realActivityLoadsPausesAndResumes() {
        waitForGame()
        compose.onNodeWithTag("pause").performClick()
        compose.onNodeWithTag("paused_title").assertIsDisplayed()
        compose.onNodeWithTag("sound_toggle").assertIsDisplayed()
        compose.onNodeWithTag("haptics_toggle").assertIsDisplayed()
        compose.onNodeWithTag("resume").performClick()
        compose.onNodeWithTag("board").assertIsDisplayed()
    }

    @Test
    fun activityRecreationKeepsTheCurrentBoard() {
        waitForGame()
        val score = compose.onNodeWithTag("score").fetchSemanticsNode().config[SemanticsProperties.Text].first().text
        compose.activityRule.scenario.recreate()
        waitForGame()
        val resumedScore = compose.onNodeWithTag("score").fetchSemanticsNode().config[SemanticsProperties.Text].first().text
        assertEquals(score, resumedScore)
    }

    @Test
    fun leavingTheActivityPausesPlay() {
        waitForGame()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("paused_title").assertIsDisplayed()
        compose.onNodeWithTag("resume").performClick()
    }
}