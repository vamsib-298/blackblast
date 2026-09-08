package com.blackblast.app

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.blackblast.core.GameEngine
import com.blackblast.core.GameMode
import com.blackblast.core.MoveResult
import kotlinx.coroutines.delay

enum class SoundCue { PLACE, CLEAR, MULTI_CLEAR, PULSE, LEVEL_COMPLETE, LEVEL_FAILED }

data class FeedbackTone(val type: Int, val durationMs: Int)

object FeedbackSounds {
    fun cue(result: MoveResult): SoundCue = when {
        result.state.level != null && result.state.isWon -> SoundCue.LEVEL_COMPLETE
        result.state.level != null && GameEngine.isGameOver(result.state) -> SoundCue.LEVEL_FAILED
        result.usedPulse -> SoundCue.PULSE
        result.lineCount > 1 -> SoundCue.MULTI_CLEAR
        result.lineCount == 1 -> SoundCue.CLEAR
        else -> SoundCue.PLACE
    }

    fun pattern(cue: SoundCue): List<FeedbackTone> = when (cue) {
        SoundCue.PLACE -> listOf(FeedbackTone(ToneGenerator.TONE_PROP_BEEP, 45))
        SoundCue.CLEAR -> listOf(FeedbackTone(ToneGenerator.TONE_PROP_ACK, 95))
        SoundCue.MULTI_CLEAR -> listOf(FeedbackTone(ToneGenerator.TONE_DTMF_3, 60), FeedbackTone(ToneGenerator.TONE_PROP_ACK, 130))
        SoundCue.PULSE -> listOf(FeedbackTone(ToneGenerator.TONE_DTMF_0, 65), FeedbackTone(ToneGenerator.TONE_PROP_ACK, 120))
        SoundCue.LEVEL_COMPLETE -> listOf(FeedbackTone(ToneGenerator.TONE_DTMF_1, 75), FeedbackTone(ToneGenerator.TONE_DTMF_3, 75),
            FeedbackTone(ToneGenerator.TONE_DTMF_6, 75), FeedbackTone(ToneGenerator.TONE_PROP_ACK, 180))
        SoundCue.LEVEL_FAILED -> listOf(FeedbackTone(ToneGenerator.TONE_PROP_NACK, 150))
    }
}

@Composable
fun GameFeedback(effect: MoveEffect?, sound: Boolean, haptics: Boolean, paused: Boolean) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val haptic = LocalHapticFeedback.current
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 30) }.getOrNull() }
    var consumedId by remember { mutableLongStateOf(Long.MIN_VALUE) }
    DisposableEffect(tone) {
        onDispose { tone?.stopTone(); tone?.release() }
    }
    LaunchedEffect(effect?.id, sound, haptics, paused, lifecycle) {
        try {
            if (paused || !lifecycle.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
            val current = effect ?: return@LaunchedEffect
            if (current.id == consumedId) return@LaunchedEffect
            consumedId = current.id
            if (haptics) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            if (!sound) return@LaunchedEffect
            FeedbackSounds.pattern(FeedbackSounds.cue(current.result)).forEach { note ->
                tone?.startTone(note.type, note.durationMs)
                delay(note.durationMs + 30L)
            }
        } finally {
            tone?.stopTone()
        }
    }
}