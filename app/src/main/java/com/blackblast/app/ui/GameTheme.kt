package com.blackblast.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blackblast.app.R

object BlastColors {
    val background = Color(0xFF101413)
    val surface = Color(0xFF1B211E)
    val cell = Color(0xFF222B26)
    val ink = Color(0xFFF1F5EF)
    val muted = Color(0xFF9BA79F)
    val lime = Color(0xFFBCF67B)
    val coral = Color(0xFFFF997D)
    val tiles = listOf(Color.Transparent, lime, Color(0xFF7FCBEE), coral, Color(0xFFC1A2F5), Color(0xFFF1D472), Color(0xFF73D9BD))
}

val Outfit = FontFamily(
    Font(R.font.outfit_400regular, FontWeight.Normal),
    Font(R.font.outfit_600semibold, FontWeight.SemiBold),
    Font(R.font.outfit_800extrabold, FontWeight.ExtraBold),
)

private fun textStyle(size: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = Outfit,
    fontSize = size.sp,
    fontWeight = weight,
    letterSpacing = 0.sp,
)

@Composable
fun BlackBlastTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = BlastColors.lime,
            onPrimary = BlastColors.background,
            secondary = BlastColors.coral,
            background = BlastColors.background,
            surface = BlastColors.surface,
            onBackground = BlastColors.ink,
            onSurface = BlastColors.ink,
            onSurfaceVariant = BlastColors.muted,
            outline = Color(0xFF435047),
        ),
        typography = Typography(
            displayLarge = textStyle(64, FontWeight.ExtraBold),
            displayMedium = textStyle(48, FontWeight.ExtraBold),
            displaySmall = textStyle(36, FontWeight.ExtraBold),
            headlineLarge = textStyle(30, FontWeight.ExtraBold),
            headlineMedium = textStyle(26, FontWeight.SemiBold),
            headlineSmall = textStyle(24, FontWeight.SemiBold),
            titleLarge = textStyle(22, FontWeight.SemiBold),
            titleMedium = textStyle(18, FontWeight.SemiBold),
            titleSmall = textStyle(16, FontWeight.SemiBold),
            bodyLarge = textStyle(16),
            bodyMedium = textStyle(14),
            bodySmall = textStyle(12),
            labelLarge = textStyle(14, FontWeight.SemiBold),
            labelMedium = textStyle(12, FontWeight.SemiBold),
            labelSmall = textStyle(10, FontWeight.SemiBold),
        ),
        content = content,
    )
}

@Composable
fun BlastMark(modifier: Modifier = Modifier) {
    Canvas(modifier.size(30.dp)) {
        val unit = size.width / 2.25f
        val tile = Size(unit, unit)
        val gap = size.width - unit
        drawRoundRect(BlastColors.lime, Offset.Zero, tile, CornerRadius(2.dp.toPx()))
        drawRoundRect(BlastColors.lime, Offset(0f, gap), tile, CornerRadius(2.dp.toPx()))
        drawRoundRect(BlastColors.lime, Offset(gap, gap), tile, CornerRadius(2.dp.toPx()))
        drawRoundRect(BlastColors.coral, Offset(gap, 0f), tile, CornerRadius(2.dp.toPx()))
    }
}