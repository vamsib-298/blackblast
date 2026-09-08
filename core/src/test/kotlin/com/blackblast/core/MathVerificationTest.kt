package com.blackblast.core

import java.util.Random
import org.junit.Assert.*
import org.junit.Test

/**
 * Differential and property verification of the game's computational core.
 * Reference implementations here are intentionally naive and independent of the
 * production code paths they cross-check.
 */
class MathVerificationTest {
    private val singles = List(3) { Piece(0, it + 1) }

    @Test
    fun everyOrientationMatchesTheIndependentGridReferenceRotation() {
        Shapes.all.indices.forEach { shapeId ->
            repeat(4) { rotation ->
                val measured = Shapes.orientations[shapeId][rotation].cells.toSet()
                if (rotation == 0) {
                    assertEquals(Shapes.all[shapeId].cells.toSet(), measured)
                } else {
                    assertEquals(
                        "shape $shapeId rotation $rotation",
                        referenceRotate(Shapes.orientations[shapeId][rotation - 1]),
                        measured,
                    )
                }
            }
        }
    }

    @Test
    fun fourQuarterTurnsAreTheIdentityAndOrientationsAreDistinct() {
        Shapes.all.indices.forEach { shapeId ->
            val base = Shapes.all[shapeId]
            // orientations[3] is 270°; one more referenceRotate must return to the base (360°=identity)
            val afterFour = referenceRotate(Shapes.orientations[shapeId][3])
            assertEquals("shape $shapeId cell count after 4 turns", base.cells.size.toLong(), afterFour.size.toLong())
            assertEquals("shape $shapeId: 4 quarter-turns must be identity", base.cells.toSet(), afterFour)
            // Expected distinct count computed via referenceRotate chain only, not Shapes.orientations
            val s1 = referenceRotate(base)
            val s2 = referenceRotate(Shape(base.name, s1.sortedWith(compareBy(Cell::row, Cell::column))))
            val s3 = referenceRotate(Shape(base.name, s2.sortedWith(compareBy(Cell::row, Cell::column))))
            val expectedDistinct = setOf(base.cells.toSet(), s1, s2, s3).size
            val measured = Shapes.orientations[shapeId].map { it.cells.toSet() }.toSet().size
            assertEquals("shape $shapeId distinct orientations", expectedDistinct, measured)
        }
    }

    @Test
    fun rotationsStayInsideTheBoardAndRenderingBounds() {
        Shapes.all.indices.forEach { shapeId ->
            repeat(4) { rotation ->
                val shape = Shapes.orientations[shapeId][rotation]
                assertTrue(shape.cells.all { it.row in 0 until shape.height && it.column in 0 until shape.width })
                assertTrue(shape.cells.all { it.row in 0 until BOARD_SIZE && it.column in 0 until BOARD_SIZE })
            }
        }
    }

    // ---------------------------------------------------------------- scoring

    private fun referencePoints(cells: Int, lines: Int, previousCombo: Int): Long {
        val combo = if (lines > 0) (previousCombo + 1).coerceAtMost(99) else 0
        return cells * 10L + lines.toLong() * lines * 100L * combo.coerceAtMost(8)
    }

    @Test
    fun placementScoringMatchesTheClosedFormAcrossFuzzedBoards() {
        val random = Random(20260908L)
        repeat(300) {
            val board = MutableList(64) { 0 }
            repeat(24) {
                val index = random.nextInt(64)
                if (board[index] == 0) board[index] = random.nextInt(6) + 1
            }
            val tray = List(3) { Piece(random.nextInt(Shapes.all.size), random.nextInt(6) + 1) }
            val state = GameState(board = board, tray = tray, combo = random.nextInt(99), score = random.nextInt(10_000).toLong())
            val slot = random.nextInt(3)
            for (attempt in 0..39) {
                val row = random.nextInt(BOARD_SIZE)
                val column = random.nextInt(BOARD_SIZE)
                val result = GameEngine.place(state, slot, row, column) ?: continue
                assertEquals(referencePoints(result.placed.size, result.lineCount, state.combo), result.points)
                assertEquals(state.score + result.points, result.state.score)
                assertEquals(state.moves + 1, result.state.moves)
                break
            }
        }
    }

    @Test
    fun comboMultiplierCapsAtEightRegardlessOfStackedClears() {
        var state = GameState(tray = singles)
        repeat(12) {
            val board = MutableList(64) { 0 }.apply { repeat(7) { column -> set(column, 1) } }
            state = state.copy(board = board, tray = singles)
            val result = GameEngine.place(state, 0, 0, 7)!!
            val multiplier = (result.points - result.placed.size * 10L) / (result.lineCount.toLong() * result.lineCount * 100L)
            assertTrue("multiplier $multiplier must be <= 8", multiplier <= 8)
            state = result.state
        }
        assertTrue(state.combo > 8)
    }

    @Test
    fun crossingRowAndColumnClearsTheUnionWithoutDoubleCounting() {
        val board = MutableList(64) { 0 }
        repeat(7) { index ->
            board[7 * 8 + index] = 1
            board[index * 8 + 7] = 2
        }
        val result = GameEngine.place(GameState(board = board, tray = singles), 0, 7, 7)!!
        assertEquals(2, result.lineCount)
        assertEquals(15, result.cleared.size)
        assertEquals(0, result.state.board.count { it != 0 })
    }

    // ---------------------------------------------------------------- pulse

    private fun referencePulseArea(row: Int, column: Int): Set<Int> {
        if (row !in 0 until BOARD_SIZE || column !in 0 until BOARD_SIZE) return emptySet()
        val out = HashSet<Int>()
        for (r in (row - 1)..(row + 1)) for (c in (column - 1)..(column + 1)) {
            if (r in 0 until BOARD_SIZE && c in 0 until BOARD_SIZE) out.add(r * BOARD_SIZE + c)
        }
        return out
    }

    @Test
    fun pulseAreaMatchesReferenceOverEveryCellAndOutOfBounds() {
        repeat(BOARD_SIZE) { row ->
            repeat(BOARD_SIZE) { column -> assertEquals(referencePulseArea(row, column), GameEngine.pulseArea(row, column)) }
        }
        for (bad in listOf(-5, -1, BOARD_SIZE, BOARD_SIZE + 4)) {
            assertTrue(GameEngine.pulseArea(bad, 3).isEmpty())
            assertTrue(GameEngine.pulseArea(3, bad).isEmpty())
        }
        assertEquals(9, GameEngine.pulseArea(4, 4).size)
        assertEquals(4, GameEngine.pulseArea(0, 0).size)
    }

    @Test
    fun pulseRequiresFullChargeAndScoresFivePerClearedCell() {
        // Fill a true 3×3 grid (rows 0-2, cols 0-2) so pulse at (1,1) covers all 9 cells
        val board = MutableList(64) { 0 }.apply {
            for (r in 0..2) for (c in 0..2) set(r * BOARD_SIZE + c, (r * 3 + c) % 6 + 1)
        }
        val charged = GameState(board = board, tray = singles, charge = PULSE_CAPACITY, score = 300)
        val result = GameEngine.pulse(charged, 1, 1)!!
        assertEquals(9, result.cleared.size)
        assertEquals(45L, result.points)
        assertEquals(0, result.state.charge)
        assertEquals(1, result.state.moves)
        assertEquals(345L, result.state.score)
        assertNull(GameEngine.pulse(charged.copy(charge = PULSE_CAPACITY - 1), 1, 1))
        assertNull(GameEngine.pulse(charged.copy(board = List(64) { 0 }), 1, 1))
    }

    // ---------------------------------------------------------------- game over

    private fun referenceIsGameOver(state: GameState): Boolean {
        if (state.isWon) return false
        if (state.charge == PULSE_CAPACITY && state.board.any { it != 0 }) return false
        return state.tray.filterNotNull().all { piece ->
            (0..3).all { rotation ->
                val cells = refCells(piece.shapeId, rotation)
                !(0 until BOARD_SIZE).any { row ->
                    (0 until BOARD_SIZE).any { col -> refCanPlace(state.board, cells, row, col) }
                }
            }
        }
    }

    @Test
    fun gameOverDetectionMatchesTheReferenceAcrossFuzzedStates() {
        val random = Random(77L)
        repeat(400) {
            val board = MutableList(64) { 0 }
            repeat(random.nextInt(56)) {
                val index = random.nextInt(64)
                if (board[index] == 0) board[index] = random.nextInt(6) + 1
            }
            val tray = List(3) { if (random.nextBoolean()) null else Piece(random.nextInt(Shapes.all.size), random.nextInt(6) + 1, random.nextInt(4)) }
            val state = GameState(
                board = board, tray = tray, charge = random.nextInt(PULSE_CAPACITY + 1),
                mode = GameMode.DAILY, challengeId = "2026-09-08", lines = random.nextInt(DAILY_TARGET),
            )
            assertEquals(referenceIsGameOver(state), GameEngine.isGameOver(state))
        }
    }

    @Test
    fun rotatingAnyTrayPieceNeverChangesTheGameOverOutcome() {
        val random = Random(2024L)
        repeat(200) {
            val board = MutableList(64) { 0 }
            repeat(random.nextInt(45)) {
                val i = random.nextInt(64)
                if (board[i] == 0) board[i] = random.nextInt(6) + 1
            }
            val state = GameState(board = board, tray = List(3) { Piece(random.nextInt(Shapes.all.size), random.nextInt(6) + 1) })
            for (slot in state.tray.indices) {
                val rotated = GameEngine.rotate(state, slot) ?: continue
                assertEquals(GameEngine.isGameOver(state), GameEngine.isGameOver(rotated))
            }
        }
    }

    @Test
    fun placementAcceptsRejectsAndOutOfBoundsCountsMatchIndependentReference() {
        // For each shape and rotation, count accepted/rejected/OOB placements using refCanPlace
        // then verify GameEngine.canPlace agrees on every single cell
        val fullBoard = List(64) { 1 }
        val emptyBoard = List(64) { 0 }
        Shapes.all.indices.forEach { shapeId ->
            repeat(4) { rotation ->
                val cells = refCells(shapeId, rotation)
                var refAcceptEmpty = 0; var refRejectFull = 0; var refOobCount = 0
                var prodAcceptEmpty = 0; var prodRejectFull = 0
                repeat(BOARD_SIZE) { row ->
                    repeat(BOARD_SIZE) { col ->
                        val fits = refCanPlace(emptyBoard, cells, row, col)
                        val oob = cells.any { c ->
                            (row + c.row) !in 0 until BOARD_SIZE || (col + c.column) !in 0 until BOARD_SIZE
                        }
                        if (fits) refAcceptEmpty++ else if (oob) refOobCount++ else refRejectFull++
                        if (GameEngine.canPlace(emptyBoard, Piece(shapeId, 1, rotation), row, col)) prodAcceptEmpty++
                        if (!GameEngine.canPlace(fullBoard, Piece(shapeId, 1, rotation), row, col)) prodRejectFull++
                    }
                }
                assertEquals("shape $shapeId rot $rotation accept count", refAcceptEmpty, prodAcceptEmpty)
                assertEquals("shape $shapeId rot $rotation reject full count", 64, prodRejectFull)
                assertTrue("shape $shapeId rot $rotation fits at least 1 cell on empty board", refAcceptEmpty > 0)
                assertEquals("shape $shapeId rot $rotation oob+reject+accept=64", 64, refAcceptEmpty + refOobCount + refRejectFull)
            }
        }
    }

    @Test
    fun rotatedOnlyFitDetectedByRefCanPlace() {
        // Fill all even columns: for any (row,col), the horizontal domino needs (row,col) and (row,col+1)
        // to both be empty. With even cols filled: col even → col occupied; col odd → col+1 even → occupied.
        // No horizontal placement is possible, but vertical domino at any odd column still fits.
        val board = MutableList(64) { 0 }.apply {
            for (r in 0 until BOARD_SIZE) for (c in 0 until BOARD_SIZE step 2) set(r * BOARD_SIZE + c, 1)
        }
        val horizontalCells = refCells(1, 0) // "##"
        val verticalCells = refCells(1, 1)   // "#\n#"
        // Horizontal domino must not fit anywhere (all even-col cells and their +1 neighbours block it)
        repeat(BOARD_SIZE) { row ->
            repeat(BOARD_SIZE) { col ->
                assertFalse("horizontal domino must not fit at ($row,$col)", refCanPlace(board, horizontalCells, row, col))
            }
        }
        // Vertical domino must fit on at least one odd column position
        assertTrue("vertical domino must fit somewhere in cols 1..7", (0 until BOARD_SIZE).any { row ->
            (1 until BOARD_SIZE step 2).any { col -> refCanPlace(board, verticalCells, row, col) }
        })
        // isGameOver must be false because the rotated orientation fits
        val state = GameState(board = board, tray = List(3) { Piece(1, 1, 0) })
        assertFalse("rotated domino fits so game is not over", referenceIsGameOver(state))
        assertFalse("production isGameOver must agree", GameEngine.isGameOver(state))
    }
}
