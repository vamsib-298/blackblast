package com.blackblast.core

import java.time.LocalDate
import java.util.Random
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class LevelGoal { LINES, POINTS }

data class LevelDefinition(
    val number: Int,
    val chapter: String,
    val goal: LevelGoal,
    val target: Int,
    val moveLimit: Int,
    val startingBlocks: Int,
    val shapeLimit: Int,
    val seed: Long,
    val rewardScale: Int = 0,
) {
    val objective: String get() = when (goal) {
        LevelGoal.LINES -> "Clear $target lines"
        LevelGoal.POINTS -> "Score $target points"
    }

    fun current(game: GameState): Long = when (goal) {
        LevelGoal.LINES -> game.lines.toLong()
        LevelGoal.POINTS -> game.score
    }

    fun stars(moves: Int): Int = when {
        moves <= moveLimit * 2 / 3 -> 3
        moves <= moveLimit * 5 / 6 -> 2
        else -> 1
    }

    fun reward(moves: Int): Long = if (rewardScale == 0) {
        number * 100L + stars(moves) * 50L + (moveLimit - moves).coerceAtLeast(0) * 20L
    } else {
        (number * 40L + stars(moves) * 20L + (moveLimit - moves).coerceAtLeast(0) * 5L) * rewardScale
    }

    fun startingBoard(): List<Int> {
        val random = Random(seed xor 0x4C4556454CL)
        val cells = (0 until BOARD_SIZE * BOARD_SIZE).toMutableList()
        java.util.Collections.shuffle(cells, random)
        val board = MutableList(BOARD_SIZE * BOARD_SIZE) { 0 }
        cells.take(startingBlocks).forEach { board[it] = random.nextInt(6) + 1 }
        return board
    }
}

object Levels {
    const val COUNT = 30
    private val chapters = listOf("First Steps", "Momentum", "Precision", "Pressure", "Expert", "Mastery")

    val all: List<LevelDefinition> = (1..COUNT).map { number ->
        val tier = (number - 1) / 5
        val step = (number - 1) % 5
        val goal = if (step == 2 || step == 4) LevelGoal.POINTS else LevelGoal.LINES
        val target = if (goal == LevelGoal.LINES) 2 + tier * 2 + step / 2 else 400 + tier * 650 + step * 100
        LevelDefinition(
            number = number,
            chapter = chapters[tier],
            goal = goal,
            target = target,
            moveLimit = if (goal == LevelGoal.LINES) target * 3 + 8 - minOf(tier, 4) else 20 + tier * 4 + step * 2,
            startingBlocks = tier * 3,
            shapeLimit = when (tier) { 0 -> 10; 1 -> 12; 2 -> 14; else -> Shapes.all.size },
            seed = number * 104729L + 6113L + if (number == 28) 1L else 0L,
        )
    }

    fun get(number: Int, mode: GameMode = GameMode.LEVELS): LevelDefinition? {
        val base = all.getOrNull(number - 1) ?: return null
        return when (mode) {
            GameMode.LEVELS -> base
            GameMode.FLOW -> base.copy(rewardScale = 1)
            GameMode.DAILY -> base.copy(
                target = base.target + if (base.goal == LevelGoal.LINES) 1 else 200,
                rewardScale = 3,
                seed = base.seed + if (number == 29) 1L else 0L,
            )
        }
    }
}

@Serializable
data class LevelRecord(val number: Int, val bestScore: Long, val fewestMoves: Int) {
    val stars: Int get() = Levels.get(number)!!.stars(fewestMoves)
    val points: Long get() = Levels.get(number)!!.reward(fewestMoves)
}

@Serializable
data class CampaignProgress(
    val records: List<LevelRecord> = emptyList(),
    val mode: GameMode = GameMode.LEVELS,
    val challengeId: String = "",
) {
    val unlockedLevel: Int get() = (records.size + 1).coerceAtMost(Levels.COUNT)
    val totalPoints: Long get() = records.sumOf { Levels.get(it.number, mode)!!.reward(it.fewestMoves) }
    val totalStars: Int get() = records.sumOf { Levels.get(it.number, mode)!!.stars(it.fewestMoves) }
    val isComplete: Boolean get() = records.size == Levels.COUNT

    fun isUnlocked(number: Int): Boolean = number in 1..unlockedLevel

    fun merge(other: CampaignProgress): CampaignProgress {
        if (mode != other.mode || challengeId != other.challengeId) return this
        return copy(records = List(maxOf(records.size, other.records.size)) { index ->
            val first = records.getOrNull(index)
            val second = other.records.getOrNull(index)
            when {
                first == null -> second!!
                second == null -> first
                else -> LevelRecord(index + 1, maxOf(first.bestScore, second.bestScore), minOf(first.fewestMoves, second.fewestMoves))
            }
        })
    }

    fun record(game: GameState): CampaignProgress {
        if (game.mode != mode || (challengeId.isNotEmpty() && game.challengeId != challengeId) || game.level == null || !game.isWon ||
            !GameEngine.isValid(game) || !isUnlocked(game.levelNumber)) return this
        val old = records.getOrNull(game.levelNumber - 1)
        val updated = LevelRecord(game.levelNumber, maxOf(old?.bestScore ?: 0, game.score), minOf(old?.fewestMoves ?: Int.MAX_VALUE, game.moves))
        val next = records.toMutableList()
        if (old == null) next.add(updated) else next[game.levelNumber - 1] = updated
        return copy(records = next)
    }

    fun isValid(): Boolean = (if (mode == GameMode.DAILY) challengeId.isEmpty() || runCatching { LocalDate.parse(challengeId) }.isSuccess else challengeId.isEmpty()) &&
        records.size <= Levels.COUNT && records.withIndex().all { (index, record) ->
        val level = Levels.get(record.number, mode)
        level != null && record.number == index + 1 && record.fewestMoves in 1..level.moveLimit &&
            record.bestScore in 1..MAX_SCORE && (level.goal != LevelGoal.POINTS || record.bestScore >= level.target)
    }
}

object CampaignCodec {
    private val json = Json { ignoreUnknownKeys = true }
    fun encode(progress: CampaignProgress): String = json.encodeToString(progress)
    fun decode(value: String): CampaignProgress? = try {
        json.decodeFromString<CampaignProgress>(value).takeIf(CampaignProgress::isValid)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

const val DAILY_ATTEMPT_LIMIT = 3

@Serializable
data class DailyAttempts(val date: String, val used: Int = 0, val active: Boolean = false) {
    val remaining: Int get() = (DAILY_ATTEMPT_LIMIT - used).coerceAtLeast(0)
    val canPlay: Boolean get() = active || remaining > 0

    fun start(): DailyAttempts? = if (active) this else if (remaining > 0) copy(used = used + 1, active = true) else null
    fun finish(): DailyAttempts = copy(active = false)

    fun forDate(today: LocalDate): DailyAttempts = if (today.isAfter(LocalDate.parse(date))) DailyAttempts(today.toString()) else this

    fun isValid(): Boolean = runCatching { LocalDate.parse(date) }.isSuccess && used in 0..DAILY_ATTEMPT_LIMIT && (!active || used > 0)
}

object DailyAttemptsCodec {
    private val json = Json { ignoreUnknownKeys = true }
    fun encode(attempts: DailyAttempts): String = json.encodeToString(attempts)
    fun decode(value: String): DailyAttempts? = try {
        json.decodeFromString<DailyAttempts>(value).takeIf(DailyAttempts::isValid)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}