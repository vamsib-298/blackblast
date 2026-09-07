package com.blackblast.app

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.blackblast.app.ui.*
import com.blackblast.core.GameEngine

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        volumeControlStream = AudioManager.STREAM_MUSIC
        setContent { BlackBlastTheme { BlackBlastApp() } }
    }
}

@Composable
private fun BlackBlastApp(viewModel: GameViewModel = viewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val progress = ui.progress
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val haptic = LocalHapticFeedback.current
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 35) }.getOrNull() }
    val snackbar = remember { SnackbarHostState() }
    var confirmRestart by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.pause()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(tone) { onDispose { tone?.release() } }
    LaunchedEffect(ui.effect?.id) {
        val effect = ui.effect ?: return@LaunchedEffect
        if (progress?.haptics == true) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        if (progress?.sound == true) {
            tone?.startTone(if (effect.result.lineCount > 0 || effect.result.usedPulse) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_BEEP, 65)
        }
    }
    LaunchedEffect(ui.message) {
        ui.message?.let { message -> snackbar.showSnackbar(message); viewModel.dismissMessage() }
    }
    BackHandler(enabled = progress != null) {
        if (ui.paused) viewModel.resume() else viewModel.pause()
    }

    Box(Modifier.fillMaxSize().background(BlastColors.background).safeDrawingPadding()) {
        if (progress == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
                BlastMark(Modifier.size(48.dp))
                CircularProgressIndicator(Modifier.size(22.dp), color = BlastColors.lime, strokeWidth = 2.dp)
            }
        } else {
            GameScreen(progress, ui.effect, ui.saving, viewModel::place, viewModel::pulse, viewModel::pause, viewModel::changeMode)
            if (ui.paused) {
                PauseDialog(progress, viewModel::resume, { confirmRestart = true }, viewModel::setSound, viewModel::setHaptics)
            } else if (progress.current.isWon || GameEngine.isGameOver(progress.current)) {
                ResultDialog(progress, viewModel::restart, viewModel::changeMode)
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        if (confirmRestart) {
            AlertDialog(
                onDismissRequest = { confirmRestart = false },
                title = { Text("Start a new run?") },
                text = { Text("Your current board will be replaced. Your best score stays.") },
                confirmButton = { TextButton(onClick = { confirmRestart = false; viewModel.restart() }) { Text("New run") } },
                dismissButton = { TextButton(onClick = { confirmRestart = false }) { Text("Keep playing") } },
            )
        }
    }
}