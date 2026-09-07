package com.blackblast.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blackblast.core.GameEngine
import com.blackblast.core.GameMode
import com.blackblast.core.MoveResult
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MoveEffect(val id: Long, val result: MoveResult)

data class GameUiState(
    val progress: PlayerProgress? = null,
    val paused: Boolean = false,
    val effect: MoveEffect? = null,
    val saving: Boolean = false,
    val message: String? = null,
)

class GameViewModel(application: Application) : AndroidViewModel(application) {
    private val store = (application as BlackBlastApplication).gameStore
    private val mutableUi = MutableStateFlow(GameUiState())
    val ui: StateFlow<GameUiState> = mutableUi.asStateFlow()
    private var effectId = 0L
    private var saveRevision = 0L

    init {
        viewModelScope.launch {
            try {
                val loaded = store.load()
                mutableUi.value = mutableUi.value.copy(
                    progress = loaded.progress,
                    message = if (loaded.recovered) "An unreadable save was replaced with a fresh board." else null,
                )
            } catch (_: IOException) {
                mutableUi.value = mutableUi.value.copy(
                    progress = PlayerProgress(),
                    message = "Local storage is unavailable. Progress may not be saved.",
                )
            }
        }
    }

    fun place(slot: Int, row: Int, column: Int): Boolean {
        val progress = mutableUi.value.progress ?: return false
        if (mutableUi.value.paused) return false
        val result = GameEngine.place(progress.current, slot, row, column) ?: return false
        acceptMove(progress, result)
        return true
    }

    fun pulse(row: Int, column: Int): Boolean {
        val progress = mutableUi.value.progress ?: return false
        if (mutableUi.value.paused) return false
        val result = GameEngine.pulse(progress.current, row, column) ?: return false
        acceptMove(progress, result)
        return true
    }

    private fun acceptMove(progress: PlayerProgress, result: MoveResult) {
        effectId += 1
        val updated = progress.withGame(result.state)
        mutableUi.value = mutableUi.value.copy(progress = updated, effect = MoveEffect(effectId, result))
        persist(updated)
    }

    fun changeMode(mode: GameMode) {
        val progress = mutableUi.value.progress ?: return
        if (mode == progress.activeMode) return
        var updated = progress.copy(activeMode = mode)
        if (mode == GameMode.DAILY && updated.daily.challengeId != LocalDate.now().toString()) {
            updated = updated.copy(daily = GameEngine.daily(LocalDate.now()))
        }
        mutableUi.value = mutableUi.value.copy(progress = updated, effect = null, paused = false)
        persist(updated)
    }

    fun restart() {
        val progress = mutableUi.value.progress ?: return
        val game = if (progress.activeMode == GameMode.FLOW) GameEngine.newGame() else GameEngine.daily(LocalDate.now())
        val updated = progress.withGame(game)
        mutableUi.value = mutableUi.value.copy(progress = updated, effect = null, paused = false)
        persist(updated)
    }

    fun pause() { mutableUi.value = mutableUi.value.copy(paused = true) }
    fun resume() { mutableUi.value = mutableUi.value.copy(paused = false) }
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