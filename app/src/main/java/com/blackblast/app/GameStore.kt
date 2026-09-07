package com.blackblast.app

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.blackblast.core.GameEngine
import com.blackblast.core.GameMode
import com.blackblast.core.GameState
import com.blackblast.core.SnapshotCodec
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Context.gameDataStore by preferencesDataStore(name = "black_blast")

data class PlayerProgress(
    val flow: GameState = GameEngine.newGame(),
    val daily: GameState = GameEngine.daily(LocalDate.now()),
    val activeMode: GameMode = GameMode.FLOW,
    val bestFlow: Long = 0,
    val bestDaily: Long = 0,
    val sound: Boolean = true,
    val haptics: Boolean = true,
) {
    val current: GameState get() = if (activeMode == GameMode.FLOW) flow else daily
    val best: Long get() = if (activeMode == GameMode.FLOW) bestFlow else bestDaily

    fun withGame(game: GameState): PlayerProgress = when (game.mode) {
        GameMode.FLOW -> copy(flow = game, bestFlow = maxOf(bestFlow, game.score))
        GameMode.DAILY -> copy(daily = game, bestDaily = maxOf(bestDaily, game.score))
    }
}

data class LoadedProgress(val progress: PlayerProgress, val recovered: Boolean)

class GameStore(context: Context) {
    private val dataStore = context.applicationContext.gameDataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class Save(val progress: PlayerProgress, val completion: CompletableDeferred<Boolean>)
    private val pending = Channel<Save>(Channel.UNLIMITED)
    private val flowKey = stringPreferencesKey("flow")
    private val dailyKey = stringPreferencesKey("daily")
    private val modeKey = stringPreferencesKey("active_mode")
    private val bestFlowKey = longPreferencesKey("best_flow")
    private val bestDailyKey = longPreferencesKey("best_daily")
    private val soundKey = booleanPreferencesKey("sound")
    private val hapticsKey = booleanPreferencesKey("haptics")

    init {
        scope.launch {
            for (save in pending) {
                val successful = try {
                    dataStore.edit { preferences ->
                        val progress = save.progress
                        preferences[flowKey] = SnapshotCodec.encode(progress.flow)
                        preferences[dailyKey] = SnapshotCodec.encode(progress.daily)
                        preferences[modeKey] = progress.activeMode.name
                        preferences[bestFlowKey] = maxOf(preferences[bestFlowKey] ?: 0, progress.bestFlow)
                        preferences[bestDailyKey] = maxOf(preferences[bestDailyKey] ?: 0, progress.bestDaily)
                        preferences[soundKey] = progress.sound
                        preferences[hapticsKey] = progress.haptics
                    }
                    true
                } catch (_: IOException) {
                    false
                }
                save.completion.complete(successful)
            }
        }
    }

    suspend fun load(today: LocalDate = LocalDate.now()): LoadedProgress {
        val preferences = dataStore.data.first()
        val rawFlow = preferences[flowKey]
        val rawDaily = preferences[dailyKey]
        val flow = rawFlow?.let(SnapshotCodec::decode)?.takeIf { it.mode == GameMode.FLOW }
        val daily = rawDaily?.let(SnapshotCodec::decode)?.takeIf { it.mode == GameMode.DAILY }
        val currentDaily = daily?.takeIf { it.challengeId == today.toString() }
        return LoadedProgress(
            PlayerProgress(
                flow = flow ?: GameEngine.newGame(),
                daily = currentDaily ?: GameEngine.daily(today),
                activeMode = if (preferences[modeKey] == GameMode.DAILY.name) GameMode.DAILY else GameMode.FLOW,
                bestFlow = maxOf(0, preferences[bestFlowKey] ?: 0, flow?.score ?: 0),
                bestDaily = maxOf(0, preferences[bestDailyKey] ?: 0, daily?.score ?: 0),
                sound = preferences[soundKey] ?: true,
                haptics = preferences[hapticsKey] ?: true,
            ),
            recovered = (rawFlow != null && flow == null) || (rawDaily != null && daily == null),
        )
    }

    fun save(progress: PlayerProgress): Deferred<Boolean> {
        val completion = CompletableDeferred<Boolean>()
        if (pending.trySend(Save(progress, completion)).isFailure) completion.complete(false)
        return completion
    }
}

class BlackBlastApplication : Application() {
    val gameStore: GameStore by lazy { GameStore(this) }
}