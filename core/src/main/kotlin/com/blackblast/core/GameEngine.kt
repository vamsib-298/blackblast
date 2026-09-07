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
}

@Serializable
enum class GameMode { FLOW, DAILY }

@Serializable
data class Piece(val shapeId: Int, val color: Int) {
    val shape: Shape get() = Shapes.all[shapeId]
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
) {
    val isWon: Boolean get() = mode == GameMode.DAILY && lines >= DAILY_TARGET
    val canPulse: Boolean get() = !isWon && charge == PULSE_CAPACITY && board.any { it != 0 }
}

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
    ): GameState = refill(GameState(mode = mode, randomState = seed, challengeId = challengeId))

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

    fun isGameOver(state: GameState): Boolean = !state.isWon && !state.canPulse &&
        state.tray.filterNotNull().none { fitsAnywhere(state.board, it) }

    fun preview(state: GameState, slot: Int, row: Int, column: Int): Set<Int> {
        val piece = state.tray.getOrNull(slot) ?: return emptySet()
        if (!canPlace(state.board, piece, row, column)) return emptySet()
        val board = state.board.toMutableList()
        piece.shape.cells.forEach { board[(row + it.row) * BOARD_SIZE + column + it.column] = piece.color }
        return fullLines(board).flatten().toSet()
    }

    fun place(state: GameState, slot: Int, row: Int, column: Int): MoveResult? {
        if (state.isWon) return null
        val piece = state.tray.getOrNull(slot) ?: return null
        if (!canPlace(state.board, piece, row, column)) return null
        val board = state.board.toMutableList()
        val placed = piece.shape.cells.map { (row + it.row) * BOARD_SIZE + column + it.column }.toSet()
        placed.forEach { board[it] = piece.color }
        val fullLines = fullLines(board)
        val cleared = fullLines.flatten().toSet()
        cleared.forEach { board[it] = 0 }
        val combo = if (fullLines.isEmpty()) 0 else (state.combo + 1).coerceAtMost(99)
        val points = piece.shape.cells.size * 10L +
            fullLines.size * fullLines.size * 100L * combo.coerceAtMost(8)
        val tray = state.tray.toMutableList().apply { set(slot, null) }
        var next = state.copy(
            board = board,
            tray = tray,
            score = state.score + points,
            combo = combo,
            charge = (state.charge + fullLines.size).coerceAtMost(PULSE_CAPACITY),
            lines = state.lines + fullLines.size,
            moves = state.moves + 1,
        )
        if (tray.all { it == null }) next = refill(next)
        return MoveResult(next, cleared, placed, points, fullLines.size)
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
        val points = cleared.size * 5L
        return MoveResult(
            state.copy(board = board, score = state.score + points, charge = 0, combo = 0, moves = state.moves + 1),
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
        state.tray.filterNotNull().all { it.shapeId in Shapes.all.indices && it.color in 1..6 } &&
        state.score in 0..Long.MAX_VALUE / 2 && state.moves in 0..Int.MAX_VALUE / 2 &&
        state.lines in 0..Int.MAX_VALUE / 2 && state.combo in 0..99 && state.charge in 0..PULSE_CAPACITY &&
        fullLines(state.board).isEmpty() &&
        when (state.mode) {
            GameMode.FLOW -> state.challengeId.isEmpty()
            GameMode.DAILY -> runCatching { LocalDate.parse(state.challengeId) }.isSuccess
        }

    private fun fullLines(board: List<Int>): List<List<Int>> = buildList {
        repeat(BOARD_SIZE) { line ->
            val row = List(BOARD_SIZE) { line * BOARD_SIZE + it }
            val column = List(BOARD_SIZE) { it * BOARD_SIZE + line }
            if (row.all { board[it] != 0 }) add(row)
            if (column.all { board[it] != 0 }) add(column)
        }
    }

    private fun refill(state: GameState): GameState {
        val random = Random(state.randomState)
        val shapeLimit = if (state.score < 300) 12 else Shapes.all.size
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
}