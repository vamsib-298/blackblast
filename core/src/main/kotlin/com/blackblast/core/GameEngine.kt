package com.blackblast.core

import java.time.LocalDate
import java.util.Random
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val BOARD_SIZE = 8
const val PULSE_CAPACITY = 6
const val DAILY_TARGET = 12
const val MAX_UNDOS = 3
const val MAX_SCORE = Long.MAX_VALUE / 2
const val MAX_COUNTER = Int.MAX_VALUE / 2

data class Cell(val row: Int, val column: Int)

data class Shape(val name: String, val cells: List<Cell>) {
    val width = cells.maxOf { it.column } + 1
    val height = cells.maxOf { it.row } + 1
}

object Shapes {
    private fun shape(name: String, vararg rows: String) = Shape(
        name,
        rows.flatMapIndexed { row, pattern ->
            pattern.mapIndexedNotNull { column, value ->
                if (value == '#') Cell(row, column) else null
            }
        },
    )

    val all = listOf(
        shape("Single", "#"),
        shape("Domino", "##"),
        shape("Tall domino", "#", "#"),
        shape("Triple", "###"),
        shape("Tall triple", "#", "#", "#"),
        shape("Square", "##", "##"),
        shape("Corner", "#.", "##"),
        shape("Reverse corner", ".#", "##"),
        shape("L block", "#.", "#.", "##"),
        shape("J block", ".#", ".#", "##"),
        shape("T block", "###", ".#."),
        shape("Zigzag", ".##", "##."),
        shape("Wide block", "###", "###"),
        shape("Tall block", "##", "##", "##"),
        shape("Long line", "####"),
        shape("Tall line", "#", "#", "#", "#"),
        shape("Large square", "###", "###", "###"),
    )

    val orientations = all.map { shape ->
        buildList {
            var current = shape
            repeat(4) {
                add(current)
                val height = current.height
                current = Shape(
                    shape.name,
                    current.cells.map { Cell(it.column, height - 1 - it.row) }
                        .sortedWith(compareBy(Cell::row, Cell::column)),
                )
            }
        }
    }
}

@Serializable
enum class GameMode { FLOW, DAILY, LEVELS }

@Serializable
data class Piece(val shapeId: Int, val color: Int, val rotation: Int = 0) {
    val shape: Shape get() = Shapes.orientations[shapeId][rotation]
}

@Serializable
data class GameState(
    val board: List<Int> = List(BOARD_SIZE * BOARD_SIZE) { 0 },
    val tray: List<Piece?> = List(3) { null },
    val score: Long = 0,
    val combo: Int = 0,
    val charge: Int = 0,
    val lines: Int = 0,
    val moves: Int = 0,
    val randomState: Long = 1,
    val mode: GameMode = GameMode.FLOW,
    val challengeId: String = "",
    val version: Int = 1,
    val undosRemaining: Int = MAX_UNDOS,
    val levelNumber: Int = 0,
) {
    val level: LevelDefinition? get() = if (levelNumber > 0) Levels.get(levelNumber, mode) else null
    val movesRemaining: Int? get() = level?.let { (it.moveLimit - moves).coerceAtLeast(0) }
    val isWon: Boolean get() = level?.let { moves <= it.moveLimit && it.current(this) >= it.target }
        ?: (mode == GameMode.DAILY && lines >= DAILY_TARGET)
    val isOutOfMoves: Boolean get() = level != null && !isWon && movesRemaining == 0
    val canPulse: Boolean get() = !isWon && !isOutOfMoves && charge == PULSE_CAPACITY && board.any { it != 0 } &&
        (level == null || tray.filterNotNull().any { GameEngine.fitsAnyRotation(board, it) })
}

@Serializable
data class UndoCheckpoint(val before: GameState, val after: GameState)

data class PlacementHint(val slot: Int, val row: Int, val column: Int, val rotation: Int, val lines: Int)

data class PlacementForecast(
    val board: List<Int>,
    val placed: Set<Int>,
    val cleared: Set<Int>,
    val points: Long,
    val lineCount: Int,
    val combo: Int,
)

data class MoveResult(
    val state: GameState,
    val cleared: Set<Int>,
    val placed: Set<Int>,
    val points: Long,
    val lineCount: Int,
    val usedPulse: Boolean = false,
)

object GameEngine {
    fun newGame(
        mode: GameMode = GameMode.FLOW,
        seed: Long = System.nanoTime(),
        challengeId: String = "",
    ): GameState = if (mode == GameMode.LEVELS) level(1) else refill(GameState(mode = mode, randomState = seed, challengeId = challengeId))

    fun level(number: Int): GameState {
        val definition = requireNotNull(Levels.get(number)) { "Unknown level: $number" }
        return refill(GameState(board = definition.startingBoard(), mode = GameMode.LEVELS,
            levelNumber = number, randomState = definition.seed))
    }

    fun stage(mode: GameMode, number: Int = 1, date: LocalDate = LocalDate.now()): GameState {
        require(mode != GameMode.LEVELS) { "Stages belong to Flow or Daily" }
        val definition = requireNotNull(Levels.get(number, mode)) { "Unknown stage: $number" }
        val seed = definition.seed + if (mode == GameMode.DAILY) date.toEpochDay() * 104729L else 0L
        return refill(GameState(board = definition.startingBoard(), mode = mode, levelNumber = number,
            randomState = seed, challengeId = if (mode == GameMode.DAILY) date.toString() else ""))
    }

    fun daily(date: LocalDate): GameState = newGame(
        GameMode.DAILY,
        date.toEpochDay() * 104729L + 811L,
        date.toString(),
    )

    fun canPlace(board: List<Int>, piece: Piece, row: Int, column: Int): Boolean =
        piece.shape.cells.all { cell ->
            val targetRow = row + cell.row
            val targetColumn = column + cell.column
            targetRow in 0 until BOARD_SIZE && targetColumn in 0 until BOARD_SIZE &&
                board[targetRow * BOARD_SIZE + targetColumn] == 0
        }

    fun fitsAnywhere(board: List<Int>, piece: Piece): Boolean =
        (0 until BOARD_SIZE).any { row ->
            (0 until BOARD_SIZE).any { column -> canPlace(board, piece, row, column) }
        }

    fun fitsAnyRotation(board: List<Int>, piece: Piece): Boolean =
        (0..3).any { rotation -> fitsAnywhere(board, piece.copy(rotation = rotation)) }

    fun isGameOver(state: GameState): Boolean = !state.isWon && (state.isOutOfMoves ||
        ((state.level != null || !state.canPulse) && state.tray.filterNotNull().none { fitsAnyRotation(state.board, it) }))

    fun rotate(state: GameState, slot: Int): GameState? {
        if (state.isWon || state.isOutOfMoves || (state.level != null && isGameOver(state))) return null
        val piece = state.tray.getOrNull(slot) ?: return null
        return state.copy(tray = state.tray.mapIndexed { index, item ->
            if (index == slot) piece.copy(rotation = (piece.rotation + 1) % 4) else item
        })
    }

    fun canUndo(state: GameState, checkpoint: UndoCheckpoint?): Boolean {
        if (checkpoint == null || state.isWon || state.undosRemaining == 0) return false
        if (state.level != null && isGameOver(state)) return false
        val previous = checkpoint.before
        return checkpoint.after == state && isValid(previous) && !previous.isWon &&
            previous.mode == state.mode && previous.challengeId == state.challengeId && previous.levelNumber == state.levelNumber &&
            (previous.moves + 1).coerceAtMost(MAX_COUNTER) == state.moves && previous.undosRemaining == state.undosRemaining
    }

    fun undo(state: GameState, checkpoint: UndoCheckpoint?): GameState? {
        if (!canUndo(state, checkpoint)) return null
        return checkpoint!!.before.copy(undosRemaining = state.undosRemaining - 1)
    }

    fun findHint(state: GameState): PlacementHint? {
        if (state.isWon || state.isOutOfMoves) return null
        var best: PlacementHint? = null
        var bestValue = Int.MIN_VALUE
        state.tray.forEachIndexed { slot, piece ->
            if (piece == null) return@forEachIndexed
            val orientations = (0..3).map { piece.copy(rotation = (piece.rotation + it) % 4) }
                .distinctBy { it.shape.cells }
            orientations.forEach { oriented ->
                repeat(BOARD_SIZE) { row ->
                    repeat(BOARD_SIZE) { column ->
                        if (canPlace(state.board, oriented, row, column)) {
                            val board = state.board.toMutableList()
                            oriented.shape.cells.forEach { cell ->
                                board[(row + cell.row) * BOARD_SIZE + column + cell.column] = oriented.color
                            }
                            val lines = fullLines(board).size
                            val value = lines * 10000 + oriented.shape.cells.size * 100 +
                                row + column
                            if (value > bestValue) {
                                bestValue = value
                                best = PlacementHint(slot, row, column, oriented.rotation, lines)
                            }
                        }
                    }
                }
            }
        }
        return best
    }

    fun preview(state: GameState, slot: Int, row: Int, column: Int): Set<Int> =
        forecast(state, slot, row, column)?.cleared ?: emptySet()

    fun forecast(state: GameState, slot: Int, row: Int, column: Int): PlacementForecast? {
        if (state.isWon || state.isOutOfMoves) return null
        val piece = state.tray.getOrNull(slot) ?: return null
        if (!canPlace(state.board, piece, row, column)) return null
        val board = state.board.toMutableList()
        val placed = piece.shape.cells.map { (row + it.row) * BOARD_SIZE + column + it.column }.toSet()
        placed.forEach { board[it] = piece.color }
        val fullLines = fullLines(board)
        val cleared = fullLines.flatten().toSet()
        cleared.forEach { board[it] = 0 }
        val combo = if (fullLines.isEmpty()) 0 else (state.combo + 1).coerceAtMost(99)
        val earnedPoints = piece.shape.cells.size * 10L +
            fullLines.size * fullLines.size * 100L * combo.coerceAtMost(8)
        val points = earnedPoints.coerceAtMost(MAX_SCORE - state.score)
        return PlacementForecast(board, placed, cleared, points, fullLines.size, combo)
    }

    fun place(state: GameState, slot: Int, row: Int, column: Int): MoveResult? {
        val forecast = forecast(state, slot, row, column) ?: return null
        val tray = state.tray.toMutableList().apply { set(slot, null) }
        var next = state.copy(
            board = forecast.board,
            tray = tray,
            score = state.score + forecast.points,
            combo = forecast.combo,
            charge = (state.charge + forecast.lineCount).coerceAtMost(PULSE_CAPACITY),
            lines = (state.lines + forecast.lineCount).coerceAtMost(MAX_COUNTER),
            moves = (state.moves + 1).coerceAtMost(MAX_COUNTER),
        )
        if (tray.all { it == null }) next = refill(next)
        return MoveResult(next, forecast.cleared, forecast.placed, forecast.points, forecast.lineCount)
    }

    fun pulseArea(row: Int, column: Int): Set<Int> {
        if (row !in 0 until BOARD_SIZE || column !in 0 until BOARD_SIZE) return emptySet()
        return (row - 1..row + 1).flatMap { targetRow ->
            (column - 1..column + 1).mapNotNull { targetColumn ->
                if (targetRow in 0 until BOARD_SIZE && targetColumn in 0 until BOARD_SIZE) {
                    targetRow * BOARD_SIZE + targetColumn
                } else null
            }
        }.toSet()
    }

    fun pulse(state: GameState, row: Int, column: Int): MoveResult? {
        if (!state.canPulse) return null
        val cleared = pulseArea(row, column).filter { state.board[it] != 0 }.toSet()
        if (cleared.isEmpty()) return null
        val board = state.board.toMutableList().apply { cleared.forEach { set(it, 0) } }
        val points = (cleared.size * 5L).coerceAtMost(MAX_SCORE - state.score)
        return MoveResult(
            state.copy(board = board, score = state.score + points, charge = 0, combo = 0, moves = (state.moves + 1).coerceAtMost(MAX_COUNTER)),
            cleared,
            emptySet(),
            points,
            0,
            usedPulse = true,
        )
    }

    fun isValid(state: GameState): Boolean = state.version == 1 &&
        state.board.size == BOARD_SIZE * BOARD_SIZE && state.board.all { it in 0..6 } &&
        state.tray.size == 3 && state.tray.any { it != null } &&
        state.tray.filterNotNull().all { it.shapeId in Shapes.all.indices && it.color in 1..6 && it.rotation in 0..3 } &&
        state.score in 0..MAX_SCORE && state.moves in 0..MAX_COUNTER &&
        state.lines in 0..MAX_COUNTER && state.combo in 0..99 && state.charge in 0..PULSE_CAPACITY &&
        state.undosRemaining in 0..MAX_UNDOS &&
        fullLines(state.board).isEmpty() &&
        when (state.mode) {
            GameMode.FLOW -> state.challengeId.isEmpty() && (state.levelNumber == 0 || state.level?.let { state.moves <= it.moveLimit } == true)
            GameMode.DAILY -> runCatching { LocalDate.parse(state.challengeId) }.isSuccess &&
                (state.levelNumber == 0 || state.level?.let { state.moves <= it.moveLimit } == true)
            GameMode.LEVELS -> state.level?.let { state.challengeId.isEmpty() && state.moves <= it.moveLimit } == true
        }

    private fun fullLines(board: List<Int>): List<List<Int>> = buildList {
        repeat(BOARD_SIZE) { line ->
            if ((0 until BOARD_SIZE).all { board[line * BOARD_SIZE + it] != 0 }) {
                add(List(BOARD_SIZE) { line * BOARD_SIZE + it })
            }
            if ((0 until BOARD_SIZE).all { board[it * BOARD_SIZE + line] != 0 }) {
                add(List(BOARD_SIZE) { it * BOARD_SIZE + line })
            }
        }
    }

    private fun refill(state: GameState): GameState {
        val random = Random(state.randomState)
        val shapeLimit = state.level?.shapeLimit ?: if (state.score < 300) 12 else Shapes.all.size
        val candidates = (1 until shapeLimit).filter {
            fitsAnywhere(state.board, Piece(it, 1))
        }.ifEmpty { listOf(0) }
        val tray = List(3) {
            Piece(candidates[random.nextInt(candidates.size)], random.nextInt(6) + 1)
        }
        return state.copy(tray = tray, randomState = random.nextLong())
    }
}

object SnapshotCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(state: GameState): String = json.encodeToString(state)

    fun decode(value: String): GameState? = try {
        json.decodeFromString<GameState>(value).takeIf(GameEngine::isValid)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    fun encodeCheckpoint(checkpoint: UndoCheckpoint): String = json.encodeToString(checkpoint)

    fun decodeCheckpoint(value: String, current: GameState): UndoCheckpoint? = try {
        json.decodeFromString<UndoCheckpoint>(value).takeIf { GameEngine.canUndo(current, it) }
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}