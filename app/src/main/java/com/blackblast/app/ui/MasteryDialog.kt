package com.blackblast.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.blackblast.app.PlayerProgress
import com.blackblast.core.Mastery
import com.blackblast.core.TilePalette

@Composable
fun MasteryDialog(progress: PlayerProgress, onDismiss: () -> Unit, onPalette: (TilePalette) -> Boolean) {
    val rank = Mastery.rank(progress.personalBest)
    val next = Mastery.nextRank(progress.personalBest)
    Dialog(onDismissRequest = onDismiss) {
        Surface(Modifier.fillMaxWidth().heightIn(max = 640.dp), shape = RoundedCornerShape(8.dp), color = BlastColors.surface) {
            Column(Modifier.padding(22.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Mastery", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).testTag("mastery_title"))
                    ToolIcon(Icons.Outlined.Close, "Close mastery", onDismiss, Modifier.testTag("close_mastery"))
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.WorkspacePremium, null, Modifier.size(54.dp), tint = BlastColors.lime)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("RANK ${Mastery.ranks.indexOf(rank) + 1}", style = MaterialTheme.typography.labelSmall, color = BlastColors.muted)
                            Text(rank.name, fontFamily = Outfit, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, color = BlastColors.ink)
                            Text("${scoreText(progress.personalBest)} best", style = MaterialTheme.typography.bodyMedium, color = BlastColors.muted)
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(next?.name ?: "All ranks earned", style = MaterialTheme.typography.labelMedium)
                            if (next != null) Text(scoreText(next.requiredScore), style = MaterialTheme.typography.labelMedium, color = BlastColors.muted)
                        }
                        LinearProgressIndicator(progress = { Mastery.progress(progress.personalBest) }, modifier = Modifier.fillMaxWidth().height(5.dp), color = BlastColors.lime, trackColor = BlastColors.cell)
                    }
                    HorizontalDivider(color = BlastColors.muted.copy(alpha = 0.12f))
                    Column {
                        Text("Tile palettes", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        TilePalette.entries.forEach { palette ->
                            PaletteRow(palette, progress.activePalette == palette, palette.isUnlocked(progress.personalBest)) { onPalette(palette) }
                        }
                    }
                    HorizontalDivider(color = BlastColors.muted.copy(alpha = 0.12f))
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Milestones", style = MaterialTheme.typography.titleMedium)
                        Mastery.ranks.forEach { milestone ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                val earned = progress.personalBest >= milestone.requiredScore
                                Icon(if (earned) Icons.Outlined.CheckCircle else Icons.Outlined.Lock, null, Modifier.size(17.dp), tint = if (earned) BlastColors.lime else BlastColors.muted)
                                Spacer(Modifier.width(10.dp))
                                Text(milestone.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Text(scoreText(milestone.requiredScore), style = MaterialTheme.typography.labelMedium, color = BlastColors.muted)
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun PaletteRow(palette: TilePalette, selected: Boolean, unlocked: Boolean, onSelect: () -> Unit) {
    val colors = paletteColors(palette)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp)
            .selectable(selected = selected, enabled = unlocked, role = Role.RadioButton, onClick = onSelect)
            .testTag("palette_${palette.name.lowercase()}")
            .semantics { contentDescription = "${palette.title} palette, ${if (unlocked) "unlocked" else "locked until ${palette.requiredScore} best score"}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(width = 62.dp, height = 42.dp)) {
            val side = size.width / 3
            repeat(6) { index ->
                drawBlock(colors[index + 1], Offset(index % 3 * side, index / 3 * side), side, if (unlocked) 1f else 0.35f)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(palette.title, style = MaterialTheme.typography.titleSmall, color = if (unlocked) BlastColors.ink else BlastColors.muted)
            Text(if (unlocked) "Unlocked" else "${scoreText(palette.requiredScore)} best", style = MaterialTheme.typography.bodySmall, color = BlastColors.muted)
        }
        if (unlocked) RadioButton(selected = selected, onClick = null)
        else Icon(Icons.Outlined.Lock, null, Modifier.padding(12.dp).size(20.dp), tint = BlastColors.muted)
    }
}