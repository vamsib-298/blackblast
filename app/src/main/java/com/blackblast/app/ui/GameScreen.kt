package com.blackblast.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AllInclusive
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blackblast.app.MoveEffect
import com.blackblast.app.PlayerProgress
import com.blackblast.core.*
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

internal fun dragTarget(piece: Piece, position: Offset, board: Rect, lift: Float): Cell? {
    if (board.width <= 0f || board.height <= 0f || !position.x.isFinite() || !position.y.isFinite()) return null
    val pitch = board.width / BOARD_SIZE
    return Cell(
        ((position.y - lift - board.top) / pitch - piece.shape.height / 2f).roundToInt(),
        ((position.x - board.left) / pitch - piece.shape.width / 2f).roundToInt(),
    )
}

fun scoreText(score: Long): String = NumberFormat.getIntegerInstance(Locale.getDefault()).format(score)

@Composable
fun GameScreen(
    progress: PlayerProgress,
    effect: MoveEffect?,
    saving: Boolean,
    onPlace: (Int, Int, Int) -> Boolean,
    onPulse: (Int, Int) -> Boolean,
    onPause: () -> Unit,
    onModeChange: (GameMode) -> Unit,
    onRotate: (Int) -> Boolean = { false },
    onUndo: () -> Boolean = { false },
    onHint: () -> Unit = {},
    onDismissHint: () -> Unit = {},
    onMastery: () -> Unit = {},
    hint: PlacementHint? = null,
    findingHint: Boolean = false,
    onLevels: () -> Unit = {},
) {
    val game = progress.current
    val tileColors = LocalTileColors.current
    val haptic = LocalHapticFeedback.current
    var selectedSlot by rememberSaveable(game.mode, game.challengeId, game.levelNumber) { mutableStateOf<Int?>(null) }
    var pulseArmed by rememberSaveable(game.mode, game.levelNumber) { mutableStateOf(false) }
    var draggedSlot by remember { mutableStateOf<Int?>(null) }
    val dragPosition = remember { mutableStateOf<Offset?>(null) }
    var boardBounds by remember { mutableStateOf(Rect.Zero) }
    var screenOrigin by remember { mutableStateOf(Offset.Zero) }
    val lift = with(LocalDensity.current) { 64.dp.toPx() }
    val piece = draggedSlot?.let { game.tray.getOrNull(it) }
    val pitch = boardBounds.width / BOARD_SIZE
    val draggedTarget by remember(piece, boardBounds, lift) {
        derivedStateOf {
            dragPosition.value?.let { position -> piece?.let { dragTarget(it, position, boardBounds, lift) } }
        }
    }
    val target = draggedTarget ?: hint?.let { Cell(it.row, it.column) }
    val activeSlot = hint?.slot ?: selectedSlot
    val forecast = remember(game, activeSlot, target) {
        if (activeSlot != null && target != null) GameEngine.forecast(game, activeSlot, target.row, target.column) else null
    }
    val blockedTarget = target != null && activeSlot != null && forecast == null
    LaunchedEffect(game.mode, game.moves, game.levelNumber) {
        selectedSlot = null
        pulseArmed = false
        draggedSlot = null
        dragPosition.value = null
    }
    LaunchedEffect(hint) {
        if (hint != null) {
            selectedSlot = hint.slot
            pulseArmed = false
        }
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(BlastColors.background)
            .onGloballyPositioned { screenOrigin = it.boundsInRoot().topLeft }
            .testTag("game_screen"),
    ) {
        val compact = maxHeight < 700.dp
        val landscape = maxWidth > maxHeight
        val gutter = if (compact) 16.dp else 22.dp

        Canvas(Modifier.fillMaxSize()) {
            val spacing = 28.dp.toPx()
            var position = spacing / 2
            while (position < size.width) {
                drawLine(Color.White.copy(alpha = 0.012f), Offset(position, 0f), Offset(position, size.height), 1f)
                position += spacing
            }
        }

        val boardContent: @Composable (Modifier) -> Unit = { modifier ->
            BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
                val side = minOf(maxWidth, maxHeight, 480.dp)
                GameBoard(
                    game, activeSlot, target, pulseArmed, effect,
                    onCell = { row, column ->
                        if (pulseArmed) {
                            if (onPulse(row, column)) pulseArmed = false
                        } else {
                            val confirmedHint = hint?.takeIf { suggested ->
                                game.tray.getOrNull(suggested.slot)?.shape?.cells?.any { cell ->
                                    row == suggested.row + cell.row && column == suggested.column + cell.column
                                } == true
                            }
                            activeSlot?.let { slot ->
                                if (onPlace(slot, confirmedHint?.row ?: row, confirmedHint?.column ?: column)) selectedSlot = null
                            }
                        }
                    },
                    modifier = Modifier.size(side).onGloballyPositioned { boardBounds = it.boundsInRoot() },
                    forecast = forecast,
                )
                MoveCelebration(effect, Modifier.align(Alignment.Center))
            }
        }
        val trayContent: @Composable () -> Unit = {
            PieceTray(
                game, activeSlot, draggedSlot,
                onSelect = { slot ->
                    onDismissHint()
                    selectedSlot = if (selectedSlot == slot) null else slot
                    pulseArmed = false
                },
                onDragStart = { slot, position ->
                    onDismissHint()
                    selectedSlot = slot
                    pulseArmed = false
                    draggedSlot = slot
                    dragPosition.value = position
                },
                onDragMove = { position -> if (draggedSlot != null) dragPosition.value = position },
                onDragEnd = {
                    val slot = draggedSlot
                    val position = dragPosition.value
                    val destination = if (slot != null && position != null) {
                        game.tray.getOrNull(slot)?.let { dragTarget(it, position, boardBounds, lift) }
                    } else null
                    draggedSlot = null
                    dragPosition.value = null
                    if (slot != null && destination != null) onPlace(slot, destination.row, destination.column)
                },
                onDragCancel = {
                    draggedSlot = null
                    dragPosition.value = null
                },
                modifier = Modifier.fillMaxWidth().height(if (compact) 72.dp else 86.dp),
            )
        }
        val togglePulse = {
            onDismissHint()
            pulseArmed = !pulseArmed
            selectedSlot = null
            draggedSlot = null
            dragPosition.value = null
        }
        val pulseContent: @Composable () -> Unit = {
            PulseControl(game, pulseArmed, togglePulse)
        }
        val toolsContent: @Composable () -> Unit = {
            TacticalToolbar(
                progress = progress,
                selectedSlot = activeSlot,
                findingHint = findingHint,
                compact = compact,
                pulseArmed = pulseArmed,
                onRotate = {
                    activeSlot?.let { slot ->
                        if (onRotate(slot) && progress.haptics) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                },
                onUndo = {
                    if (onUndo()) {
                        selectedSlot = null
                        if (progress.haptics) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                },
                onHint = onHint,
                onPulse = togglePulse,
                onMastery = onMastery,
            )
        }

        if (landscape) {
            Row(Modifier.fillMaxSize().padding(gutter), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Column(Modifier.weight(1f)) {
                    GameHeader(onPause)
                    boardContent(Modifier.weight(1f).fillMaxWidth())
                }
                Column(Modifier.widthIn(max = 300.dp).weight(0.85f), verticalArrangement = Arrangement.SpaceEvenly) {
                    ModeSelector(game.mode, onModeChange)
                    if (game.level != null) LevelStatusHeader(game, forecast, blockedTarget, onLevels, progress)
                    else ScoreHeader(progress, effect, compact = true, hintActive = hint != null, forecast, blockedTarget)
                    trayContent()
                    toolsContent()
                    if (!compact) pulseContent()
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().widthIn(max = 520.dp).align(Alignment.TopCenter).padding(horizontal = gutter, vertical = if (compact) 6.dp else 12.dp),
            ) {
                GameHeader(onPause)
                Spacer(Modifier.height(if (compact) 4.dp else 14.dp))
                ModeSelector(game.mode, onModeChange)
                if (game.level != null) LevelStatusHeader(game, forecast, blockedTarget, onLevels, progress)
                else ScoreHeader(progress, effect, compact, hintActive = hint != null, forecast, blockedTarget)
                if (game.mode == GameMode.DAILY && game.level == null) {
                    LinearProgressIndicator(
                        progress = { game.lines.coerceAtMost(DAILY_TARGET).toFloat() / DAILY_TARGET },
                        modifier = Modifier.fillMaxWidth().height(3.dp).testTag("daily_progress"),
                        color = BlastColors.lime,
                        trackColor = BlastColors.cell,
                    )
                }
                boardContent(Modifier.fillMaxWidth().weight(1f))
                trayContent()
                toolsContent()
                if (!compact) pulseContent()
                if (!compact) {
                    Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${scoreText(progress.stagePoints)} ${if (game.mode == GameMode.DAILY) "DAILY" else "FLOW"} POINTS",
                            color = BlastColors.muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("mode_point_total"))
                        Text(if (saving) "SAVING" else "ON DEVICE", color = BlastColors.muted.copy(alpha = 0.65f), style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("save_status"))
                    }
                }
            }
        }

        if (piece != null && pitch > 0) {
            Canvas(Modifier.fillMaxSize()) {
            val position = dragPosition.value ?: return@Canvas
            val origin = position - screenOrigin - Offset(piece.shape.width * pitch / 2, lift + piece.shape.height * pitch / 2)
                drawPiece(piece, origin, pitch, 0.9f, tileColors)
            }
        }
    }
}

private fun dailyLabel(date: String): String = LocalDate.parse(date).format(DateTimeFormatter.ofPattern("dd MMM", Locale.ENGLISH)).uppercase(Locale.ENGLISH)

@Composable
private fun GameHeader(onPause: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        BlastMark()
        Spacer(Modifier.width(10.dp))
        Text("BLACK BLAST", modifier = Modifier.weight(1f), fontFamily = Outfit, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = BlastColors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        ToolIcon(Icons.Outlined.Pause, "Pause game", onPause, Modifier.testTag("pause"))
    }
}

@Composable
private fun ModeSelector(mode: GameMode, onModeChange: (GameMode) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(6.dp)).background(BlastColors.surface).selectableGroup(),
    ) {
        listOf(GameMode.FLOW, GameMode.DAILY).forEach { item ->
            val active = mode == item
            Row(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(6.dp))
                    .background(if (active) BlastColors.lime else Color.Transparent)
                    .selectable(active, role = Role.RadioButton, onClick = { onModeChange(item) })
                    .testTag("mode_${item.name.lowercase()}"),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val color = if (active) BlastColors.background else BlastColors.muted
                Icon(when (item) {
                    GameMode.FLOW -> Icons.Outlined.AllInclusive
                    GameMode.DAILY -> Icons.Outlined.CalendarToday
                    GameMode.LEVELS -> Icons.Outlined.GridView
                }, null, Modifier.size(17.dp), tint = color)
                Spacer(Modifier.width(8.dp))
                Text(when (item) {
                    GameMode.FLOW -> "Flow"
                    GameMode.DAILY -> "Daily"
                    GameMode.LEVELS -> "Levels"
                }, color = color, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun ScoreHeader(
    progress: PlayerProgress,
    effect: MoveEffect?,
    compact: Boolean,
    hintActive: Boolean,
    forecast: PlacementForecast?,
    blockedTarget: Boolean,
) {
    val game = progress.current
    Column(Modifier.fillMaxWidth().height(if (compact) 86.dp else 108.dp), verticalArrangement = Arrangement.Center) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("SCORE", style = MaterialTheme.typography.labelSmall, color = BlastColors.muted)
                Text(scoreText(game.score), fontSize = if (compact) 38.sp else 52.sp, lineHeight = if (compact) 40.sp else 58.sp, fontFamily = Outfit, fontWeight = FontWeight.ExtraBold, color = BlastColors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("score"))
            }
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.EmojiEvents, null, Modifier.size(16.dp), tint = BlastColors.lime)
                    Spacer(Modifier.width(5.dp))
                    Text("BEST", color = BlastColors.muted, style = MaterialTheme.typography.labelSmall)
                }
                Text(scoreText(progress.best), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, fontFamily = Outfit, color = BlastColors.ink)
            }
        }
        Row(Modifier.fillMaxWidth().height(22.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            if (forecast != null) {
                val lineLabel = "${forecast.lineCount} ${if (forecast.lineCount == 1) "line" else "lines"}"
                Row(
                    Modifier.weight(1f).testTag("move_forecast").semantics(mergeDescendants = true) {
                        contentDescription = "$lineLabel, ${scoreText(forecast.points)} points"
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("+${scoreText(forecast.points)}", style = MaterialTheme.typography.labelMedium, color = BlastColors.lime)
                    if (forecast.lineCount > 0) {
                        Spacer(Modifier.width(8.dp))
                        Text(lineLabel.uppercase(Locale.ENGLISH), style = MaterialTheme.typography.labelSmall, color = BlastColors.ink)
                    }
                }
            } else if (blockedTarget) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Block, null, Modifier.size(14.dp), tint = BlastColors.coral)
                    Spacer(Modifier.width(6.dp))
                    Text("No space", style = MaterialTheme.typography.labelMedium, color = BlastColors.coral, modifier = Modifier.testTag("blocked_forecast"))
                }
            } else {
                AnimatedContent(targetState = game.combo, modifier = Modifier.weight(1f), label = "combo") { combo ->
                    Text(if (hintActive) "SUGGESTED MOVE" else if (combo > 1) "${combo}x COMBO" else if (effect?.result?.lineCount ?: 0 > 0) "CLEAN CLEAR" else Mastery.rank(progress.personalBest).name.uppercase(Locale.ENGLISH), style = MaterialTheme.typography.labelMedium, color = BlastColors.lime)
                }
            }
            Text(if (game.mode == GameMode.DAILY) "${game.lines.coerceAtMost(DAILY_TARGET)} / $DAILY_TARGET LINES" else "${game.lines} LINES", color = BlastColors.muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("lines"))
        }
    }
}

@Composable
private fun PieceTray(
    game: GameState,
    selectedSlot: Int?,
    draggedSlot: Int?,
    onSelect: (Int) -> Unit,
    onDragStart: (Int, Offset) -> Unit,
    onDragMove: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tileColors = LocalTileColors.current
    val currentStart by rememberUpdatedState(onDragStart)
    val currentMove by rememberUpdatedState(onDragMove)
    val currentEnd by rememberUpdatedState(onDragEnd)
    val currentCancel by rememberUpdatedState(onDragCancel)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        game.tray.forEachIndexed { slot, piece ->
            var bounds by remember { mutableStateOf(Rect.Zero) }
            val available = piece != null && GameEngine.fitsAnyRotation(game.board, piece)
            Box(
                Modifier.weight(1f).fillMaxHeight().padding(vertical = 10.dp)
                    .onGloballyPositioned { bounds = it.boundsInRoot() }
                    .testTag("tray_$slot")
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selectedSlot == slot) Color.White.copy(alpha = 0.035f) else Color.Transparent)
                    .border(1.dp, if (selectedSlot == slot) BlastColors.lime.copy(alpha = 0.45f) else Color.Transparent, RoundedCornerShape(8.dp))
                    .semantics {
                        contentDescription = if (piece == null) "Used block slot ${slot + 1}" else "${piece.shape.name}, ${piece.shape.cells.size} squares, ${piece.rotation * 90} degrees${if (!available) ", no space" else ""}"
                        selected = selectedSlot == slot
                    }
                    .clickable(enabled = available, role = Role.Button) { onSelect(slot) }
                    .pointerInput(piece, available) {
                        if (available) detectDragGestures(
                            onDragStart = { currentStart(slot, bounds.topLeft + it) },
                            onDrag = { change, _ -> change.consume(); currentMove(bounds.topLeft + change.position) },
                            onDragEnd = { currentEnd() },
                            onDragCancel = { currentCancel() },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    if (piece != null && draggedSlot != slot) {
                        val side = min(min(size.width / 4.5f, size.height / (piece.shape.height + 0.5f)), 24.dp.toPx())
                        drawPiece(piece, Offset((size.width - side * piece.shape.width) / 2, (size.height - side * piece.shape.height) / 2), side, if (available) 1f else 0.24f, tileColors)
                    } else if (piece == null) {
                        drawLine(BlastColors.muted.copy(alpha = 0.2f), center - Offset(8.dp.toPx(), 0f), center + Offset(8.dp.toPx(), 0f), 2.dp.toPx())
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PulseControl(game: GameState, armed: Boolean, onToggle: () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text("Clear six lines to charge Pulse. A full Pulse clears a 3 by 3 area.") } },
        state = rememberTooltipState(),
    ) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(8.dp))
                .background(if (armed) BlastColors.lime else BlastColors.surface)
                .border(1.dp, if (game.canPulse) BlastColors.lime.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
                .clickable(enabled = game.canPulse, onClick = onToggle)
                .testTag("pulse")
                .semantics { contentDescription = if (armed) "Cancel Pulse" else "Pulse charge ${game.charge} of $PULSE_CAPACITY" }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val color = if (armed) BlastColors.background else BlastColors.lime
            Icon(if (armed) Icons.Outlined.Close else Icons.Outlined.Bolt, null, Modifier.size(24.dp), tint = color)
            Spacer(Modifier.width(8.dp))
            Text(if (armed) "PULSE ARMED" else "PULSE", color = if (armed) BlastColors.background else BlastColors.ink, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            repeat(PULSE_CAPACITY) { segment ->
                Box(Modifier.padding(start = 4.dp).size(width = 13.dp, height = 20.dp).clip(RoundedCornerShape(2.dp)).background(if (segment < game.charge) color else if (armed) BlastColors.background.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.09f)))
            }
            Spacer(Modifier.width(10.dp))
            Text(if (game.canPulse) "READY" else "${game.charge}/$PULSE_CAPACITY", color = if (armed) BlastColors.background else BlastColors.muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolIcon(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    badge: String? = null,
    active: Boolean = false,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(48.dp)) {
            BadgedBox(badge = {
                if (badge != null) Badge(
                    containerColor = if (active) BlastColors.lime else BlastColors.cell,
                    contentColor = if (active) BlastColors.background else BlastColors.muted,
                    modifier = Modifier.clearAndSetSemantics {},
                ) { Text(badge, style = MaterialTheme.typography.labelSmall) }
            }) {
                Icon(icon, label, Modifier.size(23.dp), tint = if (!enabled) BlastColors.muted.copy(alpha = 0.4f) else if (active) BlastColors.lime else BlastColors.ink)
            }
        }
    }
}