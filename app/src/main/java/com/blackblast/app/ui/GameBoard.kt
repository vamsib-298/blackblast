package com.blackblast.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.blackblast.app.MoveEffect
import com.blackblast.core.BOARD_SIZE
import com.blackblast.core.Cell
import com.blackblast.core.GameEngine
import com.blackblast.core.GameState
import com.blackblast.core.Piece
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

fun DrawScope.drawBlock(color: Color, origin: Offset, side: Float, alpha: Float = 1f) {
    val inset = (side * 0.045f).coerceAtLeast(1f)
    val edge = side - inset * 2
    val corner = CornerRadius(side * 0.12f)
    val topLeft = origin + Offset(inset, inset)
    drawRoundRect(color.copy(alpha = alpha * 0.6f), topLeft, Size(edge, edge), corner)
    drawRoundRect(color.copy(alpha = alpha), topLeft, Size(edge, edge - side * 0.085f), corner)
    drawLine(
        Color.White.copy(alpha = alpha * 0.3f),
        topLeft + Offset(side * 0.14f, side * 0.085f),
        topLeft + Offset(edge - side * 0.14f, side * 0.085f),
        strokeWidth = (side * 0.027f).coerceAtLeast(1f),
    )
}

fun DrawScope.drawPiece(piece: Piece, origin: Offset, side: Float, alpha: Float = 1f) {
    piece.shape.cells.forEach { cell ->
        drawBlock(BlastColors.tiles[piece.color], origin + Offset(cell.column * side, cell.row * side), side, alpha)
    }
}

@Composable
fun GameBoard(
    state: GameState,
    selectedSlot: Int?,
    target: Cell?,
    pulseArmed: Boolean,
    effect: MoveEffect?,
    onCell: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val animation = remember { Animatable(1f) }
    LaunchedEffect(effect?.id) {
        if (effect != null) {
            animation.snapTo(0f)
            animation.animateTo(1f, tween(520))
        }
    }
    val currentOnCell by rememberUpdatedState(onCell)
    val selectedPiece = selectedSlot?.let { state.tray.getOrNull(it) }
    val validTarget = target != null && selectedPiece != null &&
        GameEngine.canPlace(state.board, selectedPiece, target.row, target.column)
    val preview = if (validTarget) GameEngine.preview(state, selectedSlot!!, target!!.row, target.column) else emptySet()

    Box(
        modifier.testTag("board").pointerInput(Unit) {
            detectTapGestures { position ->
                val row = floor(position.y / size.height * BOARD_SIZE).toInt()
                val column = floor(position.x / size.width * BOARD_SIZE).toInt()
                if (row in 0 until BOARD_SIZE && column in 0 until BOARD_SIZE) currentOnCell(row, column)
            }
        },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val pitch = size.width / BOARD_SIZE
            val progress = animation.value
            repeat(BOARD_SIZE * BOARD_SIZE) { index ->
                val origin = Offset(index % BOARD_SIZE * pitch, index / BOARD_SIZE * pitch)
                val inset = pitch * 0.045f
                drawRoundRect(
                    BlastColors.cell,
                    origin + Offset(inset, inset),
                    Size(pitch - inset * 2, pitch - inset * 2),
                    CornerRadius(pitch * 0.12f),
                )
                drawRoundRect(
                    Color.White.copy(alpha = 0.035f),
                    origin + Offset(inset, inset),
                    Size(pitch - inset * 2, pitch - inset * 2),
                    CornerRadius(pitch * 0.12f),
                    style = Stroke(1f),
                )
                if (state.board[index] != 0) drawBlock(BlastColors.tiles[state.board[index]], origin, pitch)
                if (index in preview) {
                    drawRoundRect(BlastColors.ink.copy(alpha = 0.26f), origin, Size(pitch, pitch), CornerRadius(4.dp.toPx()))
                }
                if (target == null && selectedPiece != null && state.board[index] == 0 &&
                    GameEngine.canPlace(state.board, selectedPiece, index / BOARD_SIZE, index % BOARD_SIZE)
                ) {
                    drawCircle(BlastColors.lime.copy(alpha = 0.36f), pitch * 0.045f, origin + Offset(pitch / 2, pitch / 2))
                }
                if (effect != null && index in effect.result.cleared && progress < 1f) {
                    drawRoundRect(
                        BlastColors.lime.copy(alpha = (1f - progress) * 0.8f),
                        origin + Offset(pitch * progress / 3, pitch * progress / 3),
                        Size(pitch * (1f - progress * 0.66f), pitch * (1f - progress * 0.66f)),
                        CornerRadius(4.dp.toPx()),
                    )
                    repeat(3) { particle ->
                        val angle = index * 1.73f + particle * 2.1f
                        val travel = pitch * progress * 1.35f
                        drawCircle(
                            BlastColors.tiles[index % 6 + 1].copy(alpha = 1f - progress),
                            pitch * 0.06f * (1f - progress),
                            origin + Offset(pitch / 2 + cos(angle) * travel, pitch / 2 + sin(angle) * travel),
                        )
                    }
                }
            }
            if (validTarget) {
                selectedPiece!!.shape.cells.forEach { cell ->
                    val origin = Offset((target!!.column + cell.column) * pitch, (target.row + cell.row) * pitch)
                    drawRoundRect(
                        BlastColors.tiles[selectedPiece.color].copy(alpha = 0.32f),
                        origin + Offset(2f, 2f),
                        Size(pitch - 4f, pitch - 4f),
                        CornerRadius(pitch * 0.1f),
                        style = Stroke(2.dp.toPx()),
                    )
                }
            }
            if (pulseArmed) {
                drawRoundRect(BlastColors.lime.copy(alpha = 0.7f), Offset.Zero, size, CornerRadius(8.dp.toPx()), style = Stroke(2.dp.toPx()))
            }
        }
        Column(Modifier.fillMaxSize()) {
            repeat(BOARD_SIZE) { row ->
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    repeat(BOARD_SIZE) { column ->
                        val filled = state.board[row * BOARD_SIZE + column] != 0
                        Box(
                            Modifier.weight(1f).fillMaxSize().testTag("cell_${row}_$column").semantics {
                                contentDescription = "Row ${row + 1}, column ${column + 1}, ${if (filled) "filled" else "empty"}"
                                role = Role.Button
                                onClick(if (pulseArmed) "Use Pulse here" else "Place selected block here") {
                                    currentOnCell(row, column)
                                    true
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}