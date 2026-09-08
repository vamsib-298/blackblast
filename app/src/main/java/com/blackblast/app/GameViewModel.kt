package com.blackblast.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blackblast.core.GameEngine
import com.blackblast.core.GameMode
import com.blackblast.core.Levels
import com.blackblast.core.MoveResult
import com.blackblast.core.PlacementHint
import com.blackblast.core.TilePalette
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MoveEffect(val id: Long, val result: MoveResult)

data class GameUiState(
    val progress: PlayerProgress? = null,
    val paused: Boolean = false,
    val effect: MoveEffect? = null,
    val saving: Boolean = false,
    val message: String? = null,
    val hint: PlacementHint? = null,
    val findingHint: Boolean = false,
    val loadError: Boolean = false,
    val levelReward: Long? = null,
)

class GameViewModel(
    application: Application,
    private val store: GameStore,
    private val today: () -> LocalDate = LocalDate::now,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, (application as BlackBlastApplication).gameStore)

    private val mutableUi = MutableStateFlow(GameUiState())
    val ui: StateFlow<GameUiState> = mutableUi.asStateFlow()
    private var effectId = 0L
    private var saveRevision = 0L
    private var hintJob: Job? = null

    init {
        viewModelScope.launch {
            try {
                val loaded = store.load(today())
                mutableUi.value = mutableUi.value.copy(
                    progress = loaded.progress,
                    message = if (loaded.recovered) "Some saved progress could not be restored." else null,
                )
            } catch (_: IOException) {
                mutableUi.value = mutableUi.value.copy(
                    loadError = true,
                    message = "Local storage is unavailable.",
                )
            }
        }
    }

    fun place(slot: Int, row: Int, column: Int): Boolean {
        val progress = playableProgress() ?: return false
        val result = GameEngine.place(progress.current, slot, row, column) ?: return false
        acceptMove(progress, result)
        return true
    }

    fun pulse(row: Int, column: Int): Boolean {
        val progress = playableProgress() ?: return false
        val result = GameEngine.pulse(progress.current, row, column) ?: return false
        acceptMove(progress, result)
        return true
    }

    private fun acceptMove(progress: PlayerProgress, result: MoveResult) {
        dismissHint()
        effectId += 1
        val started = if (progress.activeMode == GameMode.DAILY) {
            progress.copy(dailyAttempts = progress.dailyAttempts.start() ?: return)
        } else progress
        val updated = started.withGame(result.state, undoFrom = progress.current)
        mutableUi.value = mutableUi.value.copy(progress = updated, effect = MoveEffect(effectId, result),
            levelReward = if (result.state.level != null && result.state.isWon) {
                updated.stagePoints - progress.stagePoints
            } else null)
        persist(updated)
    }

    fun rotate(slot: Int): Boolean {
        val progress = playableProgress() ?: return false
        val rotated = GameEngine.rotate(progress.current, slot) ?: return false
        dismissHint()
        val updated = progress.withGame(rotated, undoFrom = progress.undoCheckpoint?.before)
        mutableUi.value = mutableUi.value.copy(progress = updated, effect = null)
        persist(updated)
        return true
    }

    fun undo(): Boolean {
        val progress = playableProgress() ?: return false
        val restored = GameEngine.undo(progress.current, progress.undoCheckpoint) ?: return false
        dismissHint()
        val updated = progress.withGame(restored)
        mutableUi.value = mutableUi.value.copy(progress = updated, effect = null)
        persist(updated)
        return true
    }

    fun requestHint() {
        val progress = playableProgress() ?: return
        if (mutableUi.value.paused || mutableUi.value.findingHint || progress.current.isWon || progress.current.isOutOfMoves) return
        dismissHint()
        val snapshot = progress.current
        mutableUi.value = mutableUi.value.copy(findingHint = true)
        hintJob = viewModelScope.launch {
            val hint = withContext(Dispatchers.Default) { GameEngine.findHint(snapshot) }
            if (mutableUi.value.progress?.current != snapshot || mutableUi.value.paused) return@launch
            if (hint == null) {
                mutableUi.value = mutableUi.value.copy(findingHint = false, message = "No block fits. Try Pulse or Undo.")
                return@launch
            }
            var oriented = snapshot
            val quarterTurns = (hint.rotation - snapshot.tray[hint.slot]!!.rotation + 4) % 4
            repeat(quarterTurns) { oriented = GameEngine.rotate(oriented, hint.slot)!! }
            val latest = mutableUi.value.progress ?: return@launch
            val updated = latest.withGame(oriented, undoFrom = latest.undoCheckpoint?.before)
            mutableUi.value = mutableUi.value.copy(progress = updated, hint = hint, findingHint = false, effect = null)
            if (oriented != snapshot) persist(updated)
        }
    }

    fun dismissHint() {
        hintJob?.cancel()
        hintJob = null
        mutableUi.value = mutableUi.value.copy(hint = null, findingHint = false)
    }

    fun setPalette(palette: TilePalette): Boolean {
        val progress = mutableUi.value.progress ?: return false
        if (!palette.isUnlocked(progress.personalBest)) return false
        val updated = progress.copy(palette = palette)
        mutableUi.value = mutableUi.value.copy(progress = updated)
        persist(updated)
        return true
    }

    fun changeMode(mode: GameMode) {
        val progress = mutableUi.value.progress ?: return
        if (mode == GameMode.LEVELS) return
        dismissHint()
        val updated = progress.forDate(today()).copy(activeMode = mode)
        mutableUi.value = mutableUi.value.copy(progress = updated, effect = null, paused = false, levelReward = null)
        persist(updated)
    }

    fun restart() {
        val progress = refreshDate() ?: return
        if (!progress.canRetry) return
        dismissHint()
        val game = when (progress.activeMode) {
            GameMode.FLOW -> GameEngine.stage(GameMode.FLOW, progress.flow.levelNumber.coerceAtLeast(1))
            GameMode.DAILY -> GameEngine.stage(GameMode.DAILY, progress.daily.levelNumber.coerceAtLeast(1), LocalDate.parse(progress.dailyAttempts.date))
            GameMode.LEVELS -> return
        }
        val updated = (if (progress.activeMode == GameMode.DAILY) progress.copy(dailyAttempts = progress.dailyAttempts.finish()) else progress).withGame(game)
        mutableUi.value = mutableUi.value.copy(progress = updated, effect = null, paused = false, levelReward = null)
        persist(updated)
    }

    fun startLevel(number: Int): Boolean {
        val progress = refreshDate() ?: return false
        if (progress.activeMode != GameMode.FLOW || !progress.flowStages.isUnlocked(number)) return false
        dismissHint()
        val updated = progress.withGame(GameEngine.stage(GameMode.FLOW, number))
        mutableUi.value = mutableUi.value.copy(progress = updated, effect = null, paused = false, levelReward = null)
        persist(updated)
        return true
    }

    fun nextLevel(): Boolean {
        val progress = refreshDate() ?: return false
        val game = progress.current
        if (mutableUi.value.saving || game.level == null || !game.isWon || game.levelNumber >= Levels.COUNT ||
            !progress.stageProgress.isUnlocked(game.levelNumber + 1) || !progress.canRetry) return false
        dismissHint()
        val next = GameEngine.stage(game.mode, game.levelNumber + 1, LocalDate.parse(progress.dailyAttempts.date))
        val updated = progress.withGame(next)
        mutableUi.value = mutableUi.value.copy(progress = updated, effect = null, paused = false, levelReward = null)
        persist(updated)
        return true
    }

    private fun refreshDate(): PlayerProgress? {
        val progress = mutableUi.value.progress ?: return null
        val refreshed = progress.forDate(today())
        if (refreshed != progress) {
            dismissHint()
            mutableUi.value = mutableUi.value.copy(progress = refreshed, effect = null, levelReward = null)
            persist(refreshed)
        }
        return refreshed
    }

    private fun playableProgress(): PlayerProgress? {
        val progress = refreshDate() ?: return null
        if (mutableUi.value.paused || progress.dailyLocked || progress.activeMode == GameMode.LEVELS) return null
        return progress
    }

    fun pause() {
        dismissHint()
        mutableUi.value = mutableUi.value.copy(paused = true, effect = null)
    }

    fun retryLoad() {
        if (!mutableUi.value.loadError) return
        mutableUi.value = mutableUi.value.copy(loadError = false, message = null)
        viewModelScope.launch {
            try {
                val loaded = store.load(today())
                mutableUi.value = mutableUi.value.copy(
                    progress = loaded.progress,
                    message = if (loaded.recovered) "Some saved progress could not be restored." else null,
                )
            } catch (_: IOException) {
                mutableUi.value = mutableUi.value.copy(
                    loadError = true,
                    message = "Local storage is unavailable.",
                )
            }
        }
    }
    fun resume() {
        refreshDate()
        mutableUi.value = mutableUi.value.copy(paused = false)
    }
    fun dismissMessage() { mutableUi.value = mutableUi.value.copy(message = null) }

    fun setSound(enabled: Boolean) {
        val updated = mutableUi.value.progress?.copy(sound = enabled) ?: return
        mutableUi.value = mutableUi.value.copy(progress = updated)
        persist(updated)
    }

    fun setHaptics(enabled: Boolean) {
        val updated = mutableUi.value.progress?.copy(haptics = enabled) ?: return
        mutableUi.value = mutableUi.value.copy(progress = updated)
        persist(updated)
    }

    private fun persist(progress: PlayerProgress) {
        saveRevision += 1
        val revision = saveRevision
        val completion = store.save(progress)
        mutableUi.value = mutableUi.value.copy(saving = true)
        viewModelScope.launch {
            val successful = completion.await()
            if (revision == saveRevision) {
                mutableUi.value = mutableUi.value.copy(
                    saving = false,
                    message = if (successful) mutableUi.value.message else "Could not save this run. Storage may be full.",
                )
            }
        }
    }
}