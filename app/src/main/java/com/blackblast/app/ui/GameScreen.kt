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
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EmojiEvents
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
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

data class DraggedPiece(val slot: Int, val position: Offset)

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
) {
    val game = progress.current
    var selectedSlot by rememberSaveable(game.mode, game.challengeId) { mutableStateOf<Int?>(null) }
    var pulseArmed by rememberSaveable(game.mode) { mutableStateOf(false) }
    var drag by remember { mutableStateOf<DraggedPiece?>(null) }
    var boardBounds by remember { mutableStateOf(Rect.Zero) }
    var screenOrigin by remember { mutableStateOf(Offset.Zero) }
    val lift = with(LocalDensity.current) { 64.dp.toPx() }
    val dragSnapshot = drag
    val piece = dragSnapshot?.let { game.tray.getOrNull(it.slot) }
    val pitch = boardBounds.width / BOARD_SIZE
    val target = if (dragSnapshot != null && piece != null && pitch > 0) {
        Cell(
            ((dragSnapshot.position.y - lift - boardBounds.top) / pitch - piece.shape.height / 2f).roundToInt(),
            ((dragSnapshot.position.x - boardBounds.left) / pitch - piece.shape.width / 2f).roundToInt(),
        )
    } else null
    LaunchedEffect(game.mode, game.moves) {
        selectedSlot = null
        pulseArmed = false
        drag = null
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
                    game, selectedSlot, target, pulseArmed, effect,
                    onCell = { row, column ->
                        if (pulseArmed) {
                            if (onPulse(row, column)) pulseArmed = false
                        } else {
                            selectedSlot?.let { if (onPlace(it, row, column)) selectedSlot = null }
                        }
                    },
                    modifier = Modifier.size(side).onGloballyPositioned { boardBounds = it.boundsInRoot() },
                )
            }
        }
        val trayContent: @Composable () -> Unit = {
            PieceTray(
                game, selectedSlot, drag?.slot,
                onSelect = { slot -> selectedSlot = if (selectedSlot == slot) null else slot; pulseArmed = false },
                onDragStart = { slot, position ->
                    selectedSlot = slot
                    pulseArmed = false
                    drag = DraggedPiece(slot, position)
                },
                onDragMove = { amount -> drag = drag?.let { it.copy(position = it.position + amount) } },
                onDragEnd = {
                    val dragging = drag
                    val destination = target
                    if (dragging != null && destination != null) onPlace(dragging.slot, destination.row, destination.column)
                    drag = null
                },
                onDragCancel = { drag = null },
                modifier = Modifier.fillMaxWidth().height(if (compact) 80.dp else 108.dp),
            )
        }
        val pulseContent: @Composable () -> Unit = {
            PulseControl(game, pulseArmed) {
                pulseArmed = !pulseArmed
                selectedSlot = null
                drag = null
            }
        }

        if (landscape) {
            Row(Modifier.fillMaxSize().padding(gutter), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Column(Modifier.weight(1f)) {
                    GameHeader(onPause)
                    boardContent(Modifier.weight(1f).fillMaxWidth())
                }
                Column(Modifier.widthIn(max = 300.dp).weight(0.85f), verticalArrangement = Arrangement.SpaceEvenly) {
                    ModeSelector(game.mode, onModeChange)
                    ScoreHeader(progress, effect, compact = true)
                    trayContent()
                    pulseContent()
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().widthIn(max = 520.dp).align(Alignment.TopCenter).padding(horizontal = gutter, vertical = if (compact) 6.dp else 12.dp),
            ) {
                GameHeader(onPause)
                Spacer(Modifier.height(if (compact) 4.dp else 14.dp))
                ModeSelector(game.mode, onModeChange)
                ScoreHeader(progress, effect, compact)
                boardContent(Modifier.fillMaxWidth().weight(1f))
                trayContent()
                pulseContent()
                if (!compact) {
                    Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (game.mode == GameMode.FLOW) "NO LIMIT. JUST FLOW." else dailyLabel(game.challengeId), color = BlastColors.muted, style = MaterialTheme.typography.labelSmall)
                        Text(if (saving) "SAVING" else "ON DEVICE", color = BlastColors.muted.copy(alpha = 0.65f), style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("save_status"))
                    }
                }
            }
        }

        if (dragSnapshot != null && piece != null && pitch > 0) {
            Canvas(Modifier.fillMaxSize()) {
                val origin = dragSnapshot.position - screenOrigin - Offset(piece.shape.width * pitch / 2, lift + piece.shape.height * pitch / 2)
                drawPiece(piece, origin, pitch, 0.9f)
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
        Text("BLACK BLAST", fontFamily = Outfit, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = BlastColors.ink)
        Spacer(Modifier.weight(1f))
        ToolIcon(Icons.Outlined.Pause, "Pause game", onPause, Modifier.testTag("pause"))
    }
}

@Composable
private fun ModeSelector(mode: GameMode, onModeChange: (GameMode) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(38.dp).clip(RoundedCornerShape(6.dp)).background(BlastColors.surface).selectableGroup(),
    ) {
        GameMode.entries.forEach { item ->
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
                Icon(if (item == GameMode.FLOW) Icons.Outlined.AllInclusive else Icons.Outlined.CalendarToday, null, Modifier.size(17.dp), tint = color)
                Spacer(Modifier.width(8.dp))
                Text(if (item == GameMode.FLOW) "Flow" else "Daily", color = color, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun ScoreHeader(progress: PlayerProgress, effect: MoveEffect?, compact: Boolean) {
    val game = progress.current
    Column(Modifier.fillMaxWidth().height(if (compact) 96.dp else 134.dp), verticalArrangement = Arrangement.Center) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("SCORE", style = MaterialTheme.typography.labelSmall, color = BlastColors.muted)
                Text(scoreText(game.score), fontSize = if (compact) 44.sp else 60.sp, lineHeight = if (compact) 48.sp else 66.sp, fontFamily = Outfit, fontWeight = FontWeight.ExtraBold, color = BlastColors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("score"))
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
            AnimatedContent(targetState = game.combo, label = "combo") { combo ->
                Text(if (combo > 1) "${combo}x COMBO" else if (effect?.result?.lineCount ?: 0 > 0) "CLEAN CLEAR" else "", style = MaterialTheme.typography.labelMedium, color = BlastColors.lime)
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
    val currentStart by rememberUpdatedState(onDragStart)
    val currentMove by rememberUpdatedState(onDragMove)
    val currentEnd by rememberUpdatedState(onDragEnd)
    val currentCancel by rememberUpdatedState(onDragCancel)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        game.tray.forEachIndexed { slot, piece ->
            var bounds by remember { mutableStateOf(Rect.Zero) }
            val available = piece != null && GameEngine.fitsAnywhere(game.board, piece)
            Box(
                Modifier.weight(1f).fillMaxHeight().padding(vertical = 10.dp)
                    .onGloballyPositioned { bounds = it.boundsInRoot() }
                    .testTag("tray_$slot")
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selectedSlot == slot) Color.White.copy(alpha = 0.035f) else Color.Transparent)
                    .border(1.dp, if (selectedSlot == slot) BlastColors.lime.copy(alpha = 0.45f) else Color.Transparent, RoundedCornerShape(8.dp))
                    .semantics {
                        contentDescription = if (piece == null) "Used block slot ${slot + 1}" else "${piece.shape.name}, ${piece.shape.cells.size} squares${if (!available) ", no space" else ""}"
                        selected = selectedSlot == slot
                    }
                    .clickable(enabled = available, role = Role.Button) { onSelect(slot) }
                    .pointerInput(piece, available) {
                        if (available) detectDragGestures(
                            onDragStart = { currentStart(slot, bounds.topLeft + it) },
                            onDrag = { change, amount -> change.consume(); currentMove(amount) },
                            onDragEnd = { currentEnd() },
                            onDragCancel = { currentCancel() },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    if (piece != null && draggedSlot != slot) {
                        val side = min(min(size.width / 4.5f, size.height / 3.7f), 24.dp.toPx())
                        drawPiece(piece, Offset((size.width - side * piece.shape.width) / 2, (size.height - side * piece.shape.height) / 2), side, if (available) 1f else 0.24f)
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
fun ToolIcon(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, modifier = modifier.size(48.dp)) {
            Icon(icon, label, Modifier.size(23.dp), tint = BlastColors.ink)
        }
    }
}