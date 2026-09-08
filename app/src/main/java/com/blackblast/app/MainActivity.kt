package com.blackblast.app

import android.media.AudioManager
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.blackblast.app.ui.*
import com.blackblast.core.GameEngine
import com.blackblast.core.GameMode
import com.blackblast.core.TilePalette

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        volumeControlStream = AudioManager.STREAM_MUSIC
        setContent { BlackBlastApp() }
    }
}

@Composable
internal fun BlackBlastApp(viewModel: GameViewModel = viewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val progress = ui.progress
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val snackbar = remember { SnackbarHostState() }
    var confirmRestart by rememberSaveable { mutableStateOf(false) }
    var showMastery by rememberSaveable { mutableStateOf(false) }
    var showLevels by rememberSaveable { mutableStateOf(false) }
    var pendingLevel by rememberSaveable { mutableStateOf<Int?>(null) }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.pause()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    GameFeedback(ui.effect, progress?.sound == true, progress?.haptics == true, ui.paused)
    LaunchedEffect(ui.message) {
        ui.message?.let { message -> snackbar.showSnackbar(message); viewModel.dismissMessage() }
    }
    BackHandler(enabled = progress != null) {
        if (ui.paused) viewModel.resume() else viewModel.pause()
    }

    BlackBlastTheme(palette = progress?.activePalette ?: TilePalette.PRISM) {
        Box(Modifier.fillMaxSize().background(BlastColors.background).safeDrawingPadding()) {
            if (progress == null) {
                if (ui.loadError) {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        BlastMark(Modifier.size(48.dp))
                        Text("Storage unavailable", color = BlastColors.muted, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = viewModel::retryLoad, modifier = Modifier.testTag("retry_load")) { Text("Retry") }
                    }
                } else {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        BlastMark(Modifier.size(48.dp))
                        CircularProgressIndicator(Modifier.size(22.dp), color = BlastColors.lime, strokeWidth = 2.dp)
                    }
                }
            } else {
                GameScreen(
                    progress, ui.effect, ui.saving, viewModel::place, viewModel::pulse, viewModel::pause, viewModel::changeMode,
                    onRotate = viewModel::rotate,
                    onUndo = viewModel::undo,
                    onHint = viewModel::requestHint,
                    onDismissHint = viewModel::dismissHint,
                    onMastery = { viewModel.dismissHint(); showMastery = true },
                    hint = ui.hint,
                    findingHint = ui.findingHint,
                    onLevels = { viewModel.dismissHint(); showLevels = true },
                )
                if (showLevels) {
                    LevelMapDialog(progress, { showLevels = false }) { number ->
                        val current = progress.current
                        if (number == current.levelNumber && !current.isWon && !GameEngine.isGameOver(current)) {
                            viewModel.resume()
                            showLevels = false
                        } else if (current.moves > 0 && !current.isWon && !GameEngine.isGameOver(current)) {
                            pendingLevel = number
                        } else if (viewModel.startLevel(number)) {
                            showLevels = false
                        }
                    }
                } else if (showMastery) {
                    MasteryDialog(progress, { showMastery = false }, viewModel::setPalette)
                } else if (ui.paused) {
                    PauseDialog(progress, viewModel::resume, { confirmRestart = true }, viewModel::setSound, viewModel::setHaptics)
                } else if (progress.current.isWon || GameEngine.isGameOver(progress.current)) {
                    if (progress.current.level != null) {
                        LevelResultDialog(progress, ui.levelReward, ui.effect != null, ui.saving,
                            viewModel::restart, { viewModel.nextLevel() }, { showLevels = true }, { viewModel.changeMode(GameMode.FLOW) })
                    } else ResultDialog(progress, viewModel::restart, viewModel::changeMode, viewModel::undo)
                } else if (progress.dailyLocked) {
                    DailyLimitDialog { viewModel.changeMode(GameMode.FLOW) }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
            if (confirmRestart) {
                AlertDialog(
                    onDismissRequest = { confirmRestart = false },
                    title = { Text("Retry level ${progress?.current?.levelNumber?.coerceAtLeast(1)}?") },
                    text = { Text(if (progress?.activeMode == GameMode.DAILY) "Your current attempt will end. Starting the retry uses another daily attempt. Earned points stay." else "Your current board will be replaced. Completed levels and earned points stay.") },
                    confirmButton = { TextButton(onClick = { confirmRestart = false; viewModel.restart() }, modifier = Modifier.testTag("confirm_restart")) { Text("Retry level") } },
                    dismissButton = { TextButton(onClick = { confirmRestart = false }) { Text("Keep playing") } },
                )
            }
            pendingLevel?.let { number ->
                AlertDialog(
                    onDismissRequest = { pendingLevel = null },
                    title = { Text("Start level $number?") },
                    text = { Text("Your current attempt will be replaced. Completed levels and points stay.") },
                    confirmButton = { TextButton(onClick = {
                        if (viewModel.startLevel(number)) showLevels = false
                        pendingLevel = null
                    }, modifier = Modifier.testTag("confirm_level_selection")) { Text("Start level") } },
                    dismissButton = { TextButton(onClick = { pendingLevel = null }) { Text("Keep playing") } },
                )
            }
        }
    }
}