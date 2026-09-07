package com.blackblast.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.blackblast.app.ui.BlackBlastTheme
import com.blackblast.app.ui.GameScreen
import com.blackblast.core.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GameScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var player by mutableStateOf(PlayerProgress(sound = false, haptics = false))
    private var effect by mutableStateOf<MoveEffect?>(null)
    private var pauseCount = 0
    private val fixtureTray = listOf(Piece(0, 1), Piece(5, 2), Piece(10, 3))

    private fun host(
        state: GameState = GameState(tray = fixtureTray),
        width: Int = 393,
        height: Int = 780,
        scale: Float = 2.5f,
        fontScale: Float = 1f,
    ) {
        player = PlayerProgress(flow = state, sound = false, haptics = false)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(scale, fontScale)) {
                BlackBlastTheme {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Box(Modifier.requiredSize(width.dp, height.dp).testTag("viewport")) {
                            GameScreen(
                                player,
                                effect,
                                false,
                                onPlace = { slot, row, column ->
                                    val result = GameEngine.place(player.current, slot, row, column)
                                    if (result != null) {
                                        player = player.withGame(result.state)
                                        effect = MoveEffect((effect?.id ?: 0) + 1, result)
                                    }
                                    result != null
                                },
                                onPulse = { row, column ->
                                    val result = GameEngine.pulse(player.current, row, column)
                                    if (result != null) {
                                        player = player.withGame(result.state)
                                        effect = MoveEffect((effect?.id ?: 0) + 1, result)
                                    }
                                    result != null
                                },
                                onPause = { pauseCount += 1 },
                                onModeChange = { player = player.copy(activeMode = it) },
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun tapPlacementClearsALineAndUpdatesScore() {
        val board = MutableList(64) { 0 }.apply { repeat(7) { set(it, 2) } }
        host(GameState(board = board, tray = fixtureTray))
        compose.onNodeWithTag("tray_0").performClick()
        compose.onNodeWithTag("cell_0_7", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("score").assertTextEquals("110")
        compose.runOnIdle {
            assertTrue(player.current.board.all { it == 0 })
            assertEquals(1, player.current.lines)
            assertEquals(1, player.current.charge)
        }
    }

    @Test
    fun dragPlacesTheBlockAtTheVisiblePreview() {
        host()
        val tray = compose.onNodeWithTag("tray_1").fetchSemanticsNode().boundsInRoot
        val board = compose.onNodeWithTag("board").fetchSemanticsNode().boundsInRoot
        val pitch = board.width / BOARD_SIZE
        val destination = Offset(board.left + pitch * 3, board.top + pitch * 4 + 64 * 2.5f)
        compose.onRoot().performTouchInput { swipe(tray.center, destination, durationMillis = 700) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, player.current.moves)
            assertEquals(setOf(26, 27, 34, 35), player.current.board.indices.filter { player.current.board[it] != 0 }.toSet())
            assertEquals(40L, player.current.score)
        }
    }

    @Test
    fun droppingOutsideBoardPreservesTheHand() {
        host()
        val initial = player.current
        val tray = compose.onNodeWithTag("tray_1").fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput { swipe(tray.center, tray.center + Offset(0f, 80f), durationMillis = 500) }
        compose.runOnIdle { assertEquals(initial, player.current) }
    }

    @Test
    fun pulseClearsAnAreaAndReopensMoves() {
        val board = List(64) { if ((it / 8 + it % 8) % 2 == 0) 2 else 0 }
        host(GameState(board = board, tray = List(3) { Piece(5, 1) }, charge = PULSE_CAPACITY))
        compose.onNodeWithTag("pulse").performClick()
        compose.onNodeWithTag("cell_4_4", useUnmergedTree = true).performClick()
        compose.runOnIdle {
            assertEquals(0, player.current.charge)
            assertTrue(GameEngine.pulseArea(4, 4).all { player.current.board[it] == 0 })
            assertFalse(GameEngine.isGameOver(player.current))
        }
    }

    @Test
    fun switchingModesPreservesEachBoardAndPauseIsWired() {
        host()
        compose.onNodeWithTag("tray_0").performClick()
        compose.onNodeWithTag("cell_3_3", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("mode_daily").performClick()
        compose.onNodeWithTag("score").assertTextEquals("0")
        compose.onNodeWithTag("mode_flow").performClick()
        compose.onNodeWithTag("score").assertTextEquals("10")
        compose.onNodeWithTag("pause").performClick()
        compose.runOnIdle { assertEquals(1, pauseCount) }
    }

    @Test
    fun portraitGameplayRendersNonblankAndControlsDoNotOverlap() {
        host(showcase())
        assertLayout()
        capture("portrait-393x780")
    }

    @Test
    fun compactPhoneWithLargerTextKeepsBoardAndControlsVisible() {
        host(showcase(), width = 320, height = 568, scale = 2f, fontScale = 1.3f)
        assertLayout()
        capture("compact-320x568-large-text")
    }

    @Test
    fun landscapeUsesTheAvailableSpace() {
        host(showcase(), width = 680, height = 360, scale = 1.5f)
        capture("landscape-680x360")
        assertLayout(landscape = true)
    }

    private fun assertLayout(landscape: Boolean = false) {
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        val board = compose.onNodeWithTag("board").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val tray = compose.onNodeWithTag("tray_1").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val pulse = compose.onNodeWithTag("pulse").fetchSemanticsNode().boundsInRoot
        assertTrue("Pulse bounds $pulse within viewport $viewport", pulse.height > 0 && viewport.contains(pulse.bottomRight - Offset(1f, 1f)))
        compose.onNodeWithTag("pulse").assertIsDisplayed()
        assertTrue(board.width > 250)
        assertEquals(board.width, board.height, 1f)
        assertTrue(viewport.contains(board.topLeft))
        assertTrue(viewport.contains(board.bottomRight - Offset(1f, 1f)))
        assertTrue(viewport.contains(pulse.bottomRight - Offset(1f, 1f)))
        if (landscape) assertTrue(board.right <= tray.left) else assertTrue(board.bottom <= tray.top)
        assertTrue(tray.bottom <= pulse.top + 1f)
    }

    private fun capture(name: String) {
        val image = compose.onNodeWithTag("viewport").captureToImage().asAndroidBitmap()
        val colors = mutableSetOf<Int>()
        for (row in 0 until image.height step 11) {
            for (column in 0 until image.width step 11) colors += image.getPixel(column, row)
        }
        assertTrue("Game capture is blank", colors.size > 40)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { output ->
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    }

    private fun showcase(): GameState {
        val rows = listOf("00011000", "00211000", "00223300", "00200330", "11040000", "01144550", "00640050", "06600050")
        return GameState(
            board = rows.flatMap { row -> row.map { it.digitToInt() } },
            tray = listOf(Piece(10, 1), Piece(8, 3), Piece(5, 2)),
            score = 4820,
            lines = 18,
            combo = 3,
            charge = 4,
        )
    }
}