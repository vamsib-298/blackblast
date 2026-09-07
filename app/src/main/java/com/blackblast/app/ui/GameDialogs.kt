package com.blackblast.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.blackblast.app.PlayerProgress
import com.blackblast.core.GameMode

@Composable
fun PauseDialog(
    progress: PlayerProgress,
    onResume: () -> Unit,
    onRestart: () -> Unit,
    onSound: (Boolean) -> Unit,
    onHaptics: (Boolean) -> Unit,
) {
    Dialog(onDismissRequest = onResume) {
        Surface(shape = RoundedCornerShape(8.dp), color = BlastColors.surface) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Paused", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f).testTag("paused_title"))
                    ToolIcon(Icons.Outlined.Close, "Resume game", onResume)
                }
                HorizontalDivider(color = BlastColors.muted.copy(alpha = 0.15f))
                SettingToggle(Icons.Outlined.VolumeUp, "Sound", progress.sound, onSound, "sound_toggle")
                SettingToggle(Icons.Outlined.Vibration, "Haptics", progress.haptics, onHaptics, "haptics_toggle")
                Button(onClick = onResume, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth().height(52.dp).testTag("resume")) {
                    Icon(Icons.Outlined.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Resume")
                }
                TextButton(onClick = onRestart, modifier = Modifier.fillMaxWidth().testTag("restart_request")) {
                    Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("New run")
                }
            }
        }
    }
}

@Composable
private fun SettingToggle(icon: ImageVector, name: String, enabled: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(21.dp), tint = BlastColors.muted)
        Spacer(Modifier.width(12.dp))
        Text(name, modifier = Modifier.weight(1f))
        Switch(checked = enabled, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}

@Composable
fun ResultDialog(progress: PlayerProgress, onRestart: () -> Unit, onModeChange: (GameMode) -> Unit) {
    val game = progress.current
    Dialog(onDismissRequest = {}) {
        Surface(shape = RoundedCornerShape(8.dp), color = BlastColors.surface) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(Icons.Outlined.EmojiEvents, null, Modifier.size(38.dp), tint = BlastColors.lime)
                Text(if (game.isWon) "Daily complete" else "No moves left", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("result_title"))
                Text(scoreText(game.score), fontFamily = Outfit, fontWeight = FontWeight.ExtraBold, fontSize = 44.sp)
                if (game.score >= progress.best && game.score > 0) Text("PERSONAL BEST", style = MaterialTheme.typography.labelMedium, color = BlastColors.lime)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("LINES", color = BlastColors.muted, style = MaterialTheme.typography.labelSmall)
                        Text("${game.lines}", style = MaterialTheme.typography.titleLarge)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("BEST", color = BlastColors.muted, style = MaterialTheme.typography.labelSmall)
                        Text(scoreText(progress.best), style = MaterialTheme.typography.titleLarge)
                    }
                }
                Button(onClick = onRestart, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth().height(52.dp).testTag("play_again")) {
                    Icon(Icons.Outlined.Refresh, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Play again")
                }
                TextButton(onClick = { onModeChange(if (game.mode == GameMode.FLOW) GameMode.DAILY else GameMode.FLOW) }) {
                    Text(if (game.mode == GameMode.FLOW) "Daily challenge" else "Back to Flow")
                }
            }
        }
    }
}