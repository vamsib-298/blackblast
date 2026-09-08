package com.blackblast.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.blackblast.app.PlayerProgress

@Composable
fun TacticalToolbar(
    progress: PlayerProgress,
    selectedSlot: Int?,
    findingHint: Boolean,
    compact: Boolean,
    pulseArmed: Boolean,
    onRotate: () -> Unit,
    onUndo: () -> Unit,
    onHint: () -> Unit,
    onPulse: () -> Unit,
    onMastery: () -> Unit,
) {
    val game = progress.current
    Row(
        Modifier.fillMaxWidth().height(48.dp).testTag("tactical_tools"),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolIcon(Icons.AutoMirrored.Outlined.Undo, "Undo last move. ${game.undosRemaining} remaining", onUndo,
            Modifier.testTag("undo"), enabled = progress.canUndo, badge = "${game.undosRemaining}")
        ToolIcon(Icons.Outlined.RotateRight, if (selectedSlot == null) "Select a block to rotate" else "Rotate clockwise", onRotate,
            Modifier.testTag("rotate"), enabled = selectedSlot != null && game.tray.getOrNull(selectedSlot) != null && !game.isWon && !game.isOutOfMoves)
        ToolIcon(Icons.Outlined.Lightbulb, if (findingHint) "Finding a move" else "Suggest a legal move", onHint,
            Modifier.testTag("hint"), enabled = !findingHint && !game.isWon && !game.isOutOfMoves, active = findingHint)
        if (compact) {
            ToolIcon(if (pulseArmed) Icons.Outlined.Close else Icons.Outlined.Bolt,
                if (pulseArmed) "Cancel Pulse" else "Pulse: ${game.charge} of 6 charge. Clears a 3 by 3 area", onPulse,
                Modifier.testTag("pulse"), enabled = game.canPulse, badge = "${game.charge}/6", active = game.canPulse)
        }
        ToolIcon(Icons.Outlined.WorkspacePremium, "Mastery and palettes", onMastery,
            Modifier.testTag("mastery"))
    }
}