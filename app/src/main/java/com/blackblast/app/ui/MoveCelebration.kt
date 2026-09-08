package com.blackblast.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blackblast.app.MoveEffect
import kotlinx.coroutines.delay

@Composable
fun MoveCelebration(effect: MoveEffect?, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(effect?.id) {
        visible = effect != null && (effect.result.lineCount > 0 || effect.result.usedPulse)
        if (visible) {
            delay(650)
            visible = false
        }
    }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn() + scaleIn(initialScale = 0.8f),
        exit = fadeOut() + slideOutVertically { -it / 2 },
    ) {
        val result = effect?.result
        Column(Modifier.padding(12.dp).testTag("move_celebration"), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when {
                    result?.usedPulse == true -> "PULSE"
                    (result?.lineCount ?: 0) > 1 -> "${result!!.lineCount} LINE CLEAR"
                    else -> "CLEAN CLEAR"
                },
                color = BlastColors.lime,
                style = MaterialTheme.typography.labelLarge.copy(shadow = Shadow(BlastColors.background, Offset.Zero, 18f)),
            )
            Text("+${scoreText(result?.points ?: 0)}", color = BlastColors.ink,
                style = TextStyle(fontFamily = Outfit, fontWeight = FontWeight.ExtraBold, fontSize = 42.sp, letterSpacing = 0.sp,
                    shadow = Shadow(BlastColors.background, Offset(0f, 4f), 20f)))
        }
    }
}