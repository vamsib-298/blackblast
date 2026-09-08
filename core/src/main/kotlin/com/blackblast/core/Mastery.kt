package com.blackblast.core

data class MasteryRank(val name: String, val requiredScore: Long)

enum class TilePalette(val title: String, val requiredScore: Long) {
    PRISM("Prism", 0),
    ARCADE("Arcade", 1000),
    AURORA("Aurora", 3000);

    fun isUnlocked(best: Long): Boolean = best >= requiredScore
}

object Mastery {
    val ranks = listOf(
        MasteryRank("Rookie", 0),
        MasteryRank("Builder", 1000),
        MasteryRank("Strategist", 3000),
        MasteryRank("Master", 7500),
        MasteryRank("Legend", 15000),
    )

    fun rank(best: Long): MasteryRank = ranks.lastOrNull { best >= it.requiredScore } ?: ranks.first()

    fun nextRank(best: Long): MasteryRank? = ranks.firstOrNull { best < it.requiredScore }

    fun progress(best: Long): Float {
        val current = rank(best)
        val next = nextRank(best) ?: return 1f
        return ((best - current.requiredScore).toFloat() / (next.requiredScore - current.requiredScore))
            .coerceIn(0f, 1f)
    }
}