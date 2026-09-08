package com.blackblast.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.blackblast.app.ui.BlackBlastTheme
import com.blackblast.app.ui.GameScreen
import com.blackblast.app.ui.MasteryDialog
import com.blackblast.core.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.json.JSONObject

class GameScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private var player by mutableStateOf(PlayerProgress(sound = false, haptics = false))
    private var effect by mutableStateOf<MoveEffect?>(null)
    private var hint by mutableStateOf<PlacementHint?>(null)
    private var showMastery by mutableStateOf(false)
    private var pauseCount = 0
    private val fixtureTray = listOf(Piece(0, 1), Piece(5, 2), Piece(10, 3))

    private fun host(
        state: GameState = GameState(tray = fixtureTray),
        width: Int = 393,
        height: Int = 780,
        scale: Float = 2.5f,
        fontScale: Float = 1f,
    ) {
        player = PlayerProgress(flow = state, bestFlow = state.score, sound = false, haptics = false)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(scale, fontScale)) {
            BlackBlastTheme(palette = player.activePalette) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Box(Modifier.requiredSize(width.dp, height.dp).testTag("viewport")) {
                            GameScreen(
                                player,
                                effect,
                                false,
                                onPlace = { slot, row, column ->
                                    val result = GameEngine.place(player.current, slot, row, column)
                                    if (result != null) {
                                        player = player.withGame(result.state, undoFrom = player.current)
                                        hint = null
                                        effect = MoveEffect((effect?.id ?: 0) + 1, result)
                                    }
                                    result != null
                                },
                                onPulse = { row, column ->
                                    val result = GameEngine.pulse(player.current, row, column)
                                    if (result != null) {
                                        player = player.withGame(result.state, undoFrom = player.current)
                                        hint = null
                                        effect = MoveEffect((effect?.id ?: 0) + 1, result)
                                    }
                                    result != null
                                },
                                onPause = { pauseCount += 1 },
                                onModeChange = { player = player.copy(activeMode = it); hint = null },
                                onRotate = { slot ->
                                    val rotated = GameEngine.rotate(player.current, slot)
                                    if (rotated != null) {
                                        player = player.withGame(rotated, undoFrom = player.undoCheckpoint?.before)
                                        hint = null
                                    }
                                    rotated != null
                                },
                                onUndo = {
                                    val restored = GameEngine.undo(player.current, player.undoCheckpoint)
                                    if (restored != null) {
                                        player = player.withGame(restored)
                                        hint = null
                                        effect = null
                                    }
                                    restored != null
                                },
                                onHint = {
                                    val suggested = GameEngine.findHint(player.current)
                                    if (suggested != null) {
                                        var oriented = player.current
                                        repeat((suggested.rotation - oriented.tray[suggested.slot]!!.rotation + 4) % 4) {
                                            oriented = GameEngine.rotate(oriented, suggested.slot)!!
                                        }
                                        player = player.withGame(oriented, undoFrom = player.undoCheckpoint?.before)
                                    }
                                    hint = suggested
                                },
                                onDismissHint = { hint = null },
                                onMastery = { showMastery = true },
                                hint = hint,
                            )
                            if (showMastery) {
                                MasteryDialog(player, { showMastery = false }) { palette ->
                                    if (palette.isUnlocked(player.personalBest)) {
                                        player = player.copy(palette = palette)
                                        true
                                    } else false
                                }
                            }
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
    fun releaseUsesTheLatestPointerEvenBeforeAnotherFrame() {
        host()
        val tray = compose.onNodeWithTag("tray_1").fetchSemanticsNode().boundsInRoot
        val board = compose.onNodeWithTag("board").fetchSemanticsNode().boundsInRoot
        val pitch = board.width / BOARD_SIZE
        val firstTarget = Offset(board.left + pitch, board.top + pitch * 2 + 64 * 2.5f)
        val finalTarget = Offset(board.left + pitch * 6, board.top + pitch * 6 + 64 * 2.5f)
        compose.onRoot().performTouchInput {
            down(tray.center)
            moveTo(firstTarget, delayMillis = 200)
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            compose.onRoot().performTouchInput {
                moveTo(finalTarget, delayMillis = 1)
                up()
            }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                assertEquals(1, player.current.moves)
                assertEquals(setOf(45, 46, 53, 54),
                    player.current.board.indices.filter { player.current.board[it] != 0 }.toSet())
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun withinCellDragDoesNotRecomposeTheWholeScreenForEveryPixel() {
        host()
        val tray = compose.onNodeWithTag("tray_1").fetchSemanticsNode().boundsInRoot
        val board = compose.onNodeWithTag("board").fetchSemanticsNode().boundsInRoot
        val pitch = board.width / BOARD_SIZE
        val destination = Offset(board.left + pitch * 4, board.top + pitch * 4 + 64 * 2.5f)
        compose.onRoot().performTouchInput {
            down(tray.center)
            moveTo(destination, delayMillis = 200)
        }
        compose.waitForIdle()
        val beforeChanges = compose.runOnIdle { Recomposer.runningRecomposers.value.sumOf { it.changeCount } }
        repeat(30) { sample ->
            compose.onRoot().performTouchInput {
                moveTo(destination + Offset(if (sample % 2 == 0) 6f else -6f, 0f), delayMillis = 16)
            }
            compose.waitForIdle()
        }
        val afterChanges = compose.runOnIdle { Recomposer.runningRecomposers.value.sumOf { it.changeCount } }
        compose.onRoot().performTouchInput { up() }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "qa").apply { mkdirs() }
        File(directory, "drag-recomposition.json").writeText(JSONObject()
            .put("pointerMovesWithinSameCell", 30).put("compositionChanges", afterChanges - beforeChanges)
            .put("scope", "Compose recomposer changes, not a physical-device FPS measurement").toString(2))
        compose.runOnIdle { assertEquals(1, player.current.moves) }
        assertTrue("Within-cell pointer motion should not compose the screen on each event; actual changes=${afterChanges - beforeChanges}",
            afterChanges - beforeChanges <= 5)
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
    fun rotationControlChangesThePlacedGeometry() {
        host(GameState(tray = listOf(Piece(8, 3), Piece(0, 1), Piece(5, 2))))
        compose.onNodeWithTag("rotate").assertIsNotEnabled()
        compose.onNodeWithTag("tray_0").performClick()
        compose.onNodeWithTag("rotate").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, player.current.tray[0]!!.rotation)
            assertEquals(0, player.current.moves)
        }
        compose.onNodeWithTag("cell_2_3", useUnmergedTree = true).performClick()
        compose.runOnIdle {
            assertEquals(setOf(19, 20, 21, 27), player.current.board.indices.filter { player.current.board[it] != 0 }.toSet())
            assertEquals(40L, player.current.score)
        }
    }

    @Test
    fun undoReturnsTheWholeBoardAndCannotBeUsedTwiceWithoutAnotherMove() {
        host()
        val original = player.current
        compose.onNodeWithTag("undo").assertIsNotEnabled()
        compose.onNodeWithTag("tray_0").performClick()
        compose.onNodeWithTag("cell_3_3", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("undo").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(original.copy(undosRemaining = 2), player.current)
            assertEquals(10L, player.personalBest)
        }
        compose.onNodeWithTag("undo").assertIsNotEnabled()
        compose.onNodeWithTag("score").assertTextEquals("0")
    }

    @Test
    fun hintShowsALegalGhostAndWaitsForThePlayerToPlaceIt() {
        val board = MutableList(64) { 0 }.apply { repeat(7) { set(it, 2) } }
        host(GameState(board = board, tray = fixtureTray))
        compose.onNodeWithTag("hint").performClick()
        val suggestion = hint!!
        compose.onNodeWithTag("cell_${suggestion.row}_${suggestion.column}", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Placement preview origin"))
        compose.runOnIdle {
            assertEquals(board, player.current.board)
            assertEquals(0, player.current.moves)
        }
        capture("hint-preview-393x780")
        val ghostCell = player.current.tray[suggestion.slot]!!.shape.cells.first { it != Cell(0, 0) }
        compose.onNodeWithTag("cell_${suggestion.row + ghostCell.row}_${suggestion.column + ghostCell.column}", useUnmergedTree = true).performClick()
        compose.runOnIdle {
            assertEquals(1, player.current.moves)
            assertTrue(player.current.lines > 0)
            assertNull(hint)
        }
    }

    @Test
    fun unlockedPaletteChangesTheRenderedBoardWithoutChangingTheRun() {
        host(showcase())
        val original = player.current
        val originalImage = compose.onNodeWithTag("board").captureToImage().asAndroidBitmap()
        val sampleColumn = (originalImage.width * 3.5f / BOARD_SIZE).toInt()
        val sampleRow = (originalImage.height * 0.5f / BOARD_SIZE).toInt()
        val originalPixel = originalImage.getPixel(sampleColumn, sampleRow)
        compose.onNodeWithTag("mastery").performClick()
        compose.onNodeWithTag("mastery_title").assertIsDisplayed()
        compose.onNodeWithTag("palette_arcade").assertIsEnabled().performClick()
        capture("mastery-unlocks", compose.onNode(isDialog()))
        compose.onNodeWithTag("close_mastery").performClick()
        compose.runOnIdle {
            assertEquals(original, player.current)
            assertEquals(TilePalette.ARCADE, player.activePalette)
        }
        val changedImage = compose.onNodeWithTag("board").captureToImage().asAndroidBitmap()
        assertNotEquals(originalPixel, changedImage.getPixel(sampleColumn, sampleRow))
        capture("arcade-393x780")
    }

    @Test
    fun palettesRemainLockedUntilTheirBestScoreThreshold() {
        host()
        compose.onNodeWithTag("mastery").performClick()
        compose.onNodeWithTag("palette_prism").assertIsEnabled()
        compose.onNodeWithTag("palette_arcade").assertIsNotEnabled()
        compose.onNodeWithTag("palette_aurora").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(TilePalette.PRISM, player.activePalette) }
        compose.onNodeWithTag("close_mastery").performClick()
    }

    @Test
    fun clearCelebrationIsVisibleAndDoesNotBlockTheNextMove() {
        val board = MutableList(64) { 0 }.apply { repeat(7) { set(it, 2) } }
        host(GameState(board = board, tray = fixtureTray))
        compose.onNodeWithTag("tray_0").performClick()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("cell_0_7", useUnmergedTree = true).performClick()
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithTag("move_celebration").assertIsDisplayed()
        capture("line-clear-feedback")
        compose.onNodeWithTag("tray_1").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("cell_3_3", useUnmergedTree = true).performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals(2, player.current.moves) }
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun portraitGameplayRendersNonblankAndControlsDoNotOverlap() {
        host(showcase())
        assertLayout()
        capture("portrait-393x780")
    }

    @Test
    fun hintForecastShowsExactPointsBeforeThePlayerCommits() {
        val board = MutableList(64) { 0 }.apply { repeat(7) { set(it, 2) } }
        host(GameState(board = board, tray = listOf(Piece(0, 1), null, null)))
        compose.onNodeWithTag("hint").performClick()
        compose.onNodeWithTag("move_forecast").assertContentDescriptionEquals("1 line, 110 points")
        compose.onNodeWithTag("score").assertTextEquals("0")
        capture("move-forecast-before-clear")
        compose.onNodeWithTag("cell_0_7", useUnmergedTree = true).performTouchInput { click() }
        compose.onNodeWithTag("score").assertTextEquals("110")
        compose.onNodeWithTag("move_forecast").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, player.current.lines) }
    }

    @Test
    fun blockedDragShowsTheCollisionAndNeverChangesTheRun() {
        host(GameState(board = List(64) { if (it == 27) 3 else 0 }, tray = fixtureTray))
        val before = player.current
        val tray = compose.onNodeWithTag("tray_1").fetchSemanticsNode().boundsInRoot
        val board = compose.onNodeWithTag("board").fetchSemanticsNode().boundsInRoot
        val pitch = board.width / BOARD_SIZE
        val destination = Offset(board.left + pitch * 4, board.top + pitch * 4 + 64 * 2.5f)
        compose.onRoot().performTouchInput {
            down(tray.center)
            moveTo(destination, delayMillis = 200)
        }
        compose.onNodeWithTag("blocked_forecast").assertTextEquals("No space")
        compose.onNodeWithTag("move_forecast").assertDoesNotExist()
        capture("blocked-drag-feedback")
        compose.onRoot().performTouchInput { up() }
        compose.runOnIdle { assertEquals(before, player.current) }
    }

    @Test
    fun heldPulsePreviewsItsAreaWithoutSpendingChargeUntilRelease() {
        host(GameState(board = List(64) { if (it in setOf(0, 9, 18)) 2 else 0 }, tray = fixtureTray, charge = 6))
        val before = player.current
        compose.onNodeWithTag("pulse").performClick()
        compose.onNodeWithTag("board").performTouchInput {
            down(Offset(width * 1.5f / BOARD_SIZE, height * 1.5f / BOARD_SIZE))
        }
        compose.onNodeWithTag("board").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Pulse preview: 3 blocks"))
        compose.runOnIdle { assertEquals(before, player.current) }
        capture("pulse-area-preview")
        compose.onNodeWithTag("board").performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(0, player.current.charge)
            assertEquals(15L, player.current.score)
            assertTrue(player.current.board.all { it == 0 })
        }
    }

    @Test
    fun cancelingAPulsePressPreservesTheBoardAndCharge() {
        host(GameState(board = List(64) { if (it % 9 == 0) 2 else 0 }, tray = fixtureTray, charge = 6))
        val before = player.current
        compose.onNodeWithTag("pulse").performClick()
        compose.onNodeWithTag("board").performTouchInput {
            down(center)
            moveTo(Offset(-20f, center.y), delayMillis = 100)
            up()
        }
        compose.runOnIdle { assertEquals(before, player.current) }
        compose.onNodeWithTag("pulse").performClick()
        compose.runOnIdle { assertEquals(before, player.current) }
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
        listOf("undo", "rotate", "hint", "mastery").forEach { tag ->
            val bounds = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("$tag is outside the viewport", viewport.contains(bounds.bottomRight - Offset(1f, 1f)))
            assertTrue("$tag overlaps the tray", tray.bottom <= bounds.top + 1f)
        }
    }

    private fun capture(name: String, node: SemanticsNodeInteraction = compose.onNodeWithTag("viewport")) {
        val image = node.captureToImage().asAndroidBitmap()
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