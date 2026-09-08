package com.blackblast.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.blackblast.app.PlayerProgress
import com.blackblast.core.DAILY_ATTEMPT_LIMIT
import com.blackblast.core.GameEngine
import com.blackblast.core.GameMode
import com.blackblast.core.GameState
import com.blackblast.core.LevelGoal
import com.blackblast.core.Levels
import com.blackblast.core.PlacementForecast
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun LevelStatusHeader(game: GameState, forecast: PlacementForecast?, blocked: Boolean, onLevels: () -> Unit, progress: PlayerProgress? = null) {
    val level = game.level ?: return
    val remaining = game.movesRemaining ?: 0
    Column(Modifier.fillMaxWidth().height(112.dp).padding(vertical = 5.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(
                    Modifier.height(32.dp).clickable(role = Role.Button, onClick = onLevels).testTag("level_map_button")
                        .semantics { contentDescription = "Level ${level.number}. Open level map" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.GridView, null, Modifier.size(16.dp), tint = BlastColors.lime)
                    Spacer(Modifier.width(7.dp))
                    Text("LEVEL ${level.number}", style = MaterialTheme.typography.labelMedium, color = BlastColors.lime)
                    if (progress != null) {
                        Spacer(Modifier.width(10.dp))
                        Text("${scoreText(progress.stagePoints)} PTS", style = MaterialTheme.typography.labelSmall,
                            color = BlastColors.muted, modifier = Modifier.testTag("stage_points"))
                    }
                }
                Text(level.objective, style = MaterialTheme.typography.titleMedium, color = BlastColors.ink,
                    modifier = Modifier.testTag("level_objective"))
            }
            Column(Modifier.width(66.dp).semantics(mergeDescendants = true) {
                contentDescription = "$remaining moves left"
            }, horizontalAlignment = Alignment.End) {
                Text("MOVES", style = MaterialTheme.typography.labelSmall, color = BlastColors.muted)
                Text("$remaining", fontFamily = Outfit, fontSize = 32.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold,
                    color = if (remaining <= 3) BlastColors.coral else BlastColors.ink, modifier = Modifier.testTag("moves_left"))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val reached = level.current(game).coerceAtMost(level.target.toLong())
            Text("$reached / ${level.target} ${if (level.goal == LevelGoal.LINES) "LINES" else "POINTS"}",
                style = MaterialTheme.typography.labelSmall, color = BlastColors.muted, modifier = Modifier.testTag("level_progress"))
            Text(scoreText(game.score), style = MaterialTheme.typography.labelMedium, color = BlastColors.ink, modifier = Modifier.testTag("score"))
        }
        LinearProgressIndicator(progress = { (level.current(game).toFloat() / level.target).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(4.dp), color = BlastColors.lime, trackColor = BlastColors.cell)
        Row(Modifier.fillMaxWidth().height(18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                when {
                    blocked -> "No space"
                    forecast != null -> "+${scoreText(forecast.points)}${if (forecast.lineCount > 0) " / ${forecast.lineCount} lines" else ""}"
                    else -> if (game.mode == GameMode.DAILY) "3X STAGE REWARD" else "UNLIMITED TRIES"
                },
                style = MaterialTheme.typography.labelSmall, color = if (blocked) BlastColors.coral else BlastColors.muted,
                modifier = Modifier.testTag(if (blocked) "blocked_forecast" else "stage_hint"),
            )
            if (game.mode == GameMode.DAILY && progress != null) {
                Text("${progress.dailyAttempts.remaining}/$DAILY_ATTEMPT_LIMIT LEFT", style = MaterialTheme.typography.labelSmall,
                    color = BlastColors.lime, modifier = Modifier.testTag("daily_attempts"))
            }
        }
    }
}

@Composable
fun LevelMapDialog(progress: PlayerProgress, onDismiss: () -> Unit, onChoose: (Int) -> Unit) {
    val stages = progress.stageProgress
    val mode = progress.activeMode
    Dialog(onDismissRequest = onDismiss) {
        Surface(Modifier.fillMaxWidth().heightIn(max = 640.dp), shape = RoundedCornerShape(8.dp), color = BlastColors.surface) {
            Column(Modifier.padding(20.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (mode == GameMode.DAILY) "Daily stages" else "Flow stages", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).testTag("level_map_title"))
                    ToolIcon(Icons.Outlined.Close, "Close level map", onDismiss, Modifier.testTag("close_level_map"))
                }
                Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${stages.records.size} / ${Levels.COUNT} completed", style = MaterialTheme.typography.bodySmall, color = BlastColors.muted)
                    Text("${scoreText(progress.stagePoints)} PTS", style = MaterialTheme.typography.labelMedium, color = BlastColors.lime,
                        modifier = Modifier.testTag("campaign_points"))
                }
                LazyVerticalGrid(columns = GridCells.Adaptive(62.dp), modifier = Modifier.weight(1f, fill = false),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Levels.all.map { Levels.get(it.number, mode)!! }.chunked(5).forEachIndexed { index, chapter ->
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text("${index + 1}. ${chapter.first().chapter}", style = MaterialTheme.typography.labelLarge,
                                color = BlastColors.muted, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                        }
                        items(chapter, key = { it.number }) { level ->
                            val unlocked = stages.isUnlocked(level.number)
                            val record = stages.records.getOrNull(level.number - 1)
                            val current = level.number == progress.current.levelNumber
                            val selectable = unlocked && (mode != GameMode.DAILY ||
                                (current && !progress.current.isWon && !GameEngine.isGameOver(progress.current) && !progress.dailyLocked))
                            Column(
                                Modifier.fillMaxWidth().height(76.dp).clip(RoundedCornerShape(6.dp))
                                    .background(if (current) BlastColors.lime.copy(alpha = 0.12f) else BlastColors.cell)
                                    .border(1.dp, if (current) BlastColors.lime else Color.Transparent, RoundedCornerShape(6.dp))
                                    .clickable(enabled = selectable, role = Role.Button) { onChoose(level.number) }
                                    .testTag("level_${level.number}").semantics(mergeDescendants = true) {
                                        contentDescription = "Level ${level.number}, ${if (unlocked) "unlocked" else "locked"}, ${record?.let { level.stars(it.fewestMoves) } ?: 0} stars"
                                    },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("${level.number}", style = MaterialTheme.typography.titleLarge,
                                        color = if (unlocked) BlastColors.ink else BlastColors.muted)
                                    if (!unlocked) Icon(Icons.Outlined.Lock, null, Modifier.size(12.dp), tint = BlastColors.muted)
                                }
                                Spacer(Modifier.height(5.dp))
                                LevelStars(record?.let { level.stars(it.fewestMoves) } ?: 0, small = true)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LevelResultDialog(
    progress: PlayerProgress,
    reward: Long?,
    celebrate: Boolean,
    saving: Boolean,
    onRetry: () -> Unit,
    onNext: () -> Unit,
    onLevels: () -> Unit,
    onFlow: () -> Unit = {},
) {
    val game = progress.current
    val level = game.level ?: return
    val won = game.isWon
    val dailyExhausted = game.mode == GameMode.DAILY && !progress.canRetry
    Dialog(onDismissRequest = {}) {
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = BlastColors.surface) {
            Box {
                if (won) LevelConfetti(celebrate, Modifier.matchParentSize())
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("${level.chapter.uppercase()} / ${level.number}", style = MaterialTheme.typography.labelSmall, color = BlastColors.muted)
                    if (won) LevelStars(level.stars(game.moves))
                    else Icon(Icons.Outlined.Refresh, null, Modifier.size(42.dp), tint = BlastColors.coral)
                    Text(if (won) "Level ${level.number} completed" else if (game.isOutOfMoves) "Out of moves" else "No space left",
                        style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.testTag("level_result_title"))
                    Text(level.objective, style = MaterialTheme.typography.bodyLarge, color = BlastColors.muted)
                    Text("${level.current(game).coerceAtMost(level.target.toLong())} / ${level.target}", style = MaterialTheme.typography.titleLarge,
                        color = if (won) BlastColors.lime else BlastColors.coral)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("SCORE", style = MaterialTheme.typography.labelSmall, color = BlastColors.muted)
                            Text(scoreText(game.score), style = MaterialTheme.typography.titleLarge)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("MOVES", style = MaterialTheme.typography.labelSmall, color = BlastColors.muted)
                            Text("${game.moves} / ${level.moveLimit}", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                    if (won) {
                        Text(when {
                            reward == null -> "${scoreText(progress.stageProgress.records.getOrNull(level.number - 1)?.let { level.reward(it.fewestMoves) } ?: 0)} level points"
                            reward > 0 -> "+${scoreText(reward)} level points"
                            else -> "Best result kept"
                        }, style = MaterialTheme.typography.titleMedium, color = BlastColors.lime, modifier = Modifier.testTag("level_reward"))
                        Text("${scoreText(progress.stagePoints)} ${if (game.mode == GameMode.DAILY) "DAILY" else "FLOW"} POINTS", style = MaterialTheme.typography.labelSmall, color = BlastColors.muted)
                    }
                    if (game.mode == GameMode.DAILY) {
                        Text(if (dailyExhausted) "Daily limit reached. New attempts tomorrow." else "${progress.dailyAttempts.remaining} daily attempts left",
                            style = MaterialTheme.typography.bodySmall, color = BlastColors.muted, textAlign = TextAlign.Center,
                            modifier = Modifier.testTag("daily_result_allowance"))
                    }
                    Button(
                        onClick = if (dailyExhausted) onFlow else if (won && level.number < Levels.COUNT) onNext else if (won) onLevels else onRetry,
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(if (dailyExhausted) "daily_back_flow" else if (won) "next_level" else "retry_level"),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Icon(if (won) Icons.AutoMirrored.Outlined.ArrowForward else Icons.Outlined.Refresh, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (dailyExhausted) "Back to Flow" else if (won && level.number < Levels.COUNT) "Next level" else if (won) "All levels completed" else "Retry level ${level.number}")
                    }
                    if (won && progress.canRetry) TextButton(onClick = onRetry, enabled = !saving, modifier = Modifier.testTag("replay_level")) { Text("Replay level") }
                    TextButton(onClick = onLevels, modifier = Modifier.testTag("result_level_map")) { Text("Level map") }
                }
            }
        }
    }
}

@Composable
private fun LevelStars(earned: Int, small: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(if (small) 2.dp else 8.dp), modifier = Modifier.semantics {
        contentDescription = "$earned of 3 stars"
    }) {
        repeat(3) { index ->
            Icon(if (index < earned) Icons.Filled.Star else Icons.Outlined.StarBorder, null,
                Modifier.size(if (small) 12.dp else 42.dp), tint = if (index < earned) Color(0xFFF1D472) else BlastColors.muted.copy(alpha = 0.4f))
        }
    }
}

@Composable
private fun LevelConfetti(animate: Boolean, modifier: Modifier = Modifier) {
    val progress = remember { Animatable(1f) }
    val colors = LocalTileColors.current
    LaunchedEffect(animate) {
        if (animate) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(1400))
        }
    }
    Canvas(modifier) {
        val phase = progress.value
        if (phase >= 1f) return@Canvas
        repeat(36) { index ->
            val angle = index * 2.399963f
            val distance = (0.3f + index % 5 * 0.1f) * size.width * phase
            val origin = Offset(size.width / 2 + cos(angle) * distance,
                size.height * 0.24f + sin(angle) * distance + size.height * 0.55f * phase * phase)
            drawRect(colors[index % 6 + 1].copy(alpha = 1f - phase), origin, Size(4.dp.toPx(), 7.dp.toPx()))
        }
    }
}

@Composable
fun DailyLimitDialog(onFlow: () -> Unit) {
    Dialog(onDismissRequest = {}) {
        Surface(shape = RoundedCornerShape(8.dp), color = BlastColors.surface) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Outlined.Lock, null, Modifier.size(36.dp), tint = BlastColors.coral)
                Text("Daily limit reached", style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center, modifier = Modifier.testTag("daily_limit_title"))
                Text("$DAILY_ATTEMPT_LIMIT attempts used today. New attempts tomorrow.",
                    style = MaterialTheme.typography.bodyMedium, color = BlastColors.muted, textAlign = TextAlign.Center)
                Button(onClick = onFlow, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("daily_back_flow")) {
                    Text("Back to Flow")
                }
            }
        }
    }
}