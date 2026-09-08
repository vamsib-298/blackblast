package com.blackblast.app

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.blackblast.core.CampaignCodec
import com.blackblast.core.CampaignProgress
import com.blackblast.core.DAILY_ATTEMPT_LIMIT
import com.blackblast.core.DailyAttempts
import com.blackblast.core.DailyAttemptsCodec
import com.blackblast.core.GameEngine
import com.blackblast.core.GameMode
import com.blackblast.core.GameState
import com.blackblast.core.MAX_SCORE
import com.blackblast.core.SnapshotCodec
import com.blackblast.core.TilePalette
import com.blackblast.core.UndoCheckpoint
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
    val flow: GameState = GameEngine.stage(GameMode.FLOW),
    val daily: GameState = GameEngine.stage(GameMode.DAILY),
    val activeMode: GameMode = GameMode.FLOW,
    val bestFlow: Long = 0,
    val bestDaily: Long = 0,
    val sound: Boolean = true,
    val haptics: Boolean = true,
    val flowUndo: UndoCheckpoint? = null,
    val dailyUndo: UndoCheckpoint? = null,
    val palette: TilePalette = TilePalette.PRISM,
    val levels: GameState = GameEngine.level(1),
    val campaign: CampaignProgress = CampaignProgress(),
    val levelsUndo: UndoCheckpoint? = null,
    val flowStages: CampaignProgress = CampaignProgress(mode = GameMode.FLOW),
    val dailyStages: CampaignProgress = CampaignProgress(mode = GameMode.DAILY),
    val dailyAttempts: DailyAttempts = DailyAttempts(daily.challengeId),
    val dailyPoints: Long = 0,
    val legacyLevelCredit: Long = 0,
) {
    val current: GameState get() = when (activeMode) {
        GameMode.FLOW -> flow
        GameMode.DAILY -> daily
        GameMode.LEVELS -> levels
    }
    val best: Long get() = when (activeMode) {
        GameMode.FLOW -> bestFlow
        GameMode.DAILY -> bestDaily
        GameMode.LEVELS -> campaign.records.getOrNull(levels.levelNumber - 1)?.bestScore ?: 0
    }
    val personalBest: Long get() = maxOf(bestFlow, bestDaily)
    val activePalette: TilePalette get() = palette.takeIf { it.isUnlocked(personalBest) } ?: TilePalette.PRISM
    val undoCheckpoint: UndoCheckpoint? get() = when (activeMode) {
        GameMode.FLOW -> flowUndo
        GameMode.DAILY -> dailyUndo
        GameMode.LEVELS -> levelsUndo
    }
    val canUndo: Boolean get() = GameEngine.canUndo(current, undoCheckpoint)
    val stageProgress: CampaignProgress get() = when (activeMode) {
        GameMode.FLOW -> flowStages
        GameMode.DAILY -> dailyStages
        GameMode.LEVELS -> campaign
    }
    val stagePoints: Long get() = when (activeMode) {
        GameMode.FLOW -> (flowStages.totalPoints + legacyLevelCredit).coerceAtMost(MAX_SCORE)
        GameMode.DAILY -> dailyPoints
        GameMode.LEVELS -> campaign.totalPoints
    }
    val dailyLocked: Boolean get() = activeMode == GameMode.DAILY && !dailyAttempts.canPlay
    val canRetry: Boolean get() = activeMode != GameMode.DAILY || dailyAttempts.remaining > 0

    fun forDate(today: LocalDate): PlayerProgress {
        val nextAttempts = dailyAttempts.forDate(today)
        if (nextAttempts == dailyAttempts) return this
        val nextGame = if (daily.isWon) daily else GameEngine.stage(GameMode.DAILY, daily.levelNumber.coerceAtLeast(1), today)
        return copy(daily = nextGame, dailyUndo = null, dailyAttempts = nextAttempts)
    }

    fun withGame(game: GameState, undoFrom: GameState? = null): PlayerProgress {
        val checkpoint = undoFrom?.let { UndoCheckpoint(it, game) }
            ?.takeIf { GameEngine.canUndo(game, it) }
        return when (game.mode) {
            GameMode.FLOW -> copy(flow = game, bestFlow = maxOf(bestFlow, game.score), flowUndo = checkpoint,
                flowStages = flowStages.record(game))
            GameMode.DAILY -> {
                val updated = dailyStages.record(game)
                val reward = (updated.totalPoints - dailyStages.totalPoints).coerceAtLeast(0)
                copy(daily = game, bestDaily = maxOf(bestDaily, game.score), dailyUndo = checkpoint,
                    dailyStages = updated, dailyPoints = dailyPoints + reward.coerceAtMost(MAX_SCORE - dailyPoints),
                    dailyAttempts = if (game.isWon || GameEngine.isGameOver(game)) dailyAttempts.finish() else dailyAttempts)
            }
            GameMode.LEVELS -> copy(levels = game, campaign = campaign.record(game), levelsUndo = checkpoint)
        }
    }
}

data class LoadedProgress(val progress: PlayerProgress, val recovered: Boolean)

class GameStore(
    context: Context,
    private val dataStore: DataStore<Preferences> = context.applicationContext.gameDataStore,
) {
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
    private val flowUndoKey = stringPreferencesKey("flow_undo")
    private val dailyUndoKey = stringPreferencesKey("daily_undo")
    private val paletteKey = stringPreferencesKey("palette")
    private val levelsKey = stringPreferencesKey("levels")
    private val campaignKey = stringPreferencesKey("campaign")
    private val levelsUndoKey = stringPreferencesKey("levels_undo")
    private val flowStageRunKey = stringPreferencesKey("flow_stage_run")
    private val dailyStageRunKey = stringPreferencesKey("daily_stage_run")
    private val flowStagesKey = stringPreferencesKey("flow_stage_progress")
    private val dailyStagesKey = stringPreferencesKey("daily_stage_progress")
    private val dailyAttemptsKey = stringPreferencesKey("daily_attempts")
    private val dailyPointsKey = longPreferencesKey("daily_stage_points")
    private val legacyCreditKey = longPreferencesKey("legacy_level_credit")
    private val flowStageUndoKey = stringPreferencesKey("flow_stage_undo")
    private val dailyStageUndoKey = stringPreferencesKey("daily_stage_undo")

    init {
        scope.launch {
            for (save in pending) {
                val successful = try {
                    dataStore.edit { preferences ->
                        val progress = save.progress
                        preferences[flowStageRunKey] = SnapshotCodec.encode(progress.flow)
                        val previousFlow = preferences[flowStagesKey]?.let(CampaignCodec::decode)
                            ?.takeIf { it.mode == GameMode.FLOW } ?: CampaignProgress(mode = GameMode.FLOW)
                        preferences[flowStagesKey] = CampaignCodec.encode(previousFlow.merge(progress.flowStages))
                        preferences[legacyCreditKey] = maxOf(preferences[legacyCreditKey] ?: 0, progress.legacyLevelCredit)
                        preferences[dailyPointsKey] = maxOf(preferences[dailyPointsKey] ?: 0, progress.dailyPoints)
                        val previousAttempts = preferences[dailyAttemptsKey]?.let(DailyAttemptsCodec::decode)
                        val currentDate = LocalDate.parse(progress.dailyAttempts.date)
                        val keepDaily = previousAttempts == null || currentDate.isAfter(LocalDate.parse(previousAttempts.date)) ||
                            (progress.dailyAttempts.date == previousAttempts.date && progress.dailyAttempts.used >= previousAttempts.used)
                        if (keepDaily) {
                            preferences[dailyStageRunKey] = SnapshotCodec.encode(progress.daily)
                            val previousDaily = preferences[dailyStagesKey]?.let(CampaignCodec::decode)
                                ?.takeIf { it.mode == GameMode.DAILY && it.challengeId == progress.dailyStages.challengeId }
                                ?: CampaignProgress(mode = GameMode.DAILY, challengeId = progress.dailyStages.challengeId)
                            preferences[dailyStagesKey] = CampaignCodec.encode(previousDaily.merge(progress.dailyStages))
                            preferences[dailyAttemptsKey] = DailyAttemptsCodec.encode(progress.dailyAttempts)
                            val dailyUndo = progress.dailyUndo?.takeIf { GameEngine.canUndo(progress.daily, it) }
                            if (dailyUndo == null) preferences.remove(dailyStageUndoKey)
                            else preferences[dailyStageUndoKey] = SnapshotCodec.encodeCheckpoint(dailyUndo)
                        }
                        preferences[levelsKey] = SnapshotCodec.encode(progress.levels)
                        val previousCampaign = preferences[campaignKey]?.let(CampaignCodec::decode) ?: CampaignProgress()
                        preferences[campaignKey] = CampaignCodec.encode(previousCampaign.merge(progress.campaign))
                        preferences[modeKey] = progress.activeMode.name
                        preferences[bestFlowKey] = maxOf(preferences[bestFlowKey] ?: 0, progress.bestFlow)
                        preferences[bestDailyKey] = maxOf(preferences[bestDailyKey] ?: 0, progress.bestDaily)
                        preferences[soundKey] = progress.sound
                        preferences[hapticsKey] = progress.haptics
                        preferences[paletteKey] = progress.activePalette.name
                        val flowUndo = progress.flowUndo?.takeIf { GameEngine.canUndo(progress.flow, it) }
                        val levelsUndo = progress.levelsUndo?.takeIf { GameEngine.canUndo(progress.levels, it) }
                        if (flowUndo == null) preferences.remove(flowStageUndoKey)
                        else preferences[flowStageUndoKey] = SnapshotCodec.encodeCheckpoint(flowUndo)
                        if (levelsUndo == null) preferences.remove(levelsUndoKey)
                        else preferences[levelsUndoKey] = SnapshotCodec.encodeCheckpoint(levelsUndo)
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
        val legacyFlow = rawFlow?.let(SnapshotCodec::decode)?.takeIf { it.mode == GameMode.FLOW }
        val legacyDaily = rawDaily?.let(SnapshotCodec::decode)?.takeIf { it.mode == GameMode.DAILY }
        val rawCampaign = preferences[campaignKey]
        val decodedCampaign = rawCampaign?.let(CampaignCodec::decode)
        val savedCampaign = decodedCampaign ?: CampaignProgress()
        val rawLevels = preferences[levelsKey]
        val levels = rawLevels?.let(SnapshotCodec::decode)
            ?.takeIf { it.mode == GameMode.LEVELS && savedCampaign.isUnlocked(it.levelNumber) }
        val campaign = levels?.let(savedCampaign::record) ?: savedCampaign
        val rawFlowStages = preferences[flowStagesKey]
        val decodedFlowStages = rawFlowStages?.let(CampaignCodec::decode)?.takeIf { it.mode == GameMode.FLOW }
        val migratedFlowStages = CampaignProgress(records = campaign.records, mode = GameMode.FLOW)
        val flowStages = decodedFlowStages ?: if (rawFlowStages == null) migratedFlowStages else CampaignProgress(mode = GameMode.FLOW)
        val rawStageFlow = preferences[flowStageRunKey]
        val stageFlow = rawStageFlow?.let(SnapshotCodec::decode)?.takeIf {
            it.mode == GameMode.FLOW && (it.levelNumber == 0 || flowStages.isUnlocked(it.levelNumber))
        }
        val migratedLevel = levels?.copy(mode = GameMode.FLOW)
            ?.takeIf { GameEngine.isValid(it) && flowStages.isUnlocked(it.levelNumber) }
        val flow = stageFlow ?: if (rawStageFlow == null && migratedLevel != null) migratedLevel
            else GameEngine.stage(GameMode.FLOW, flowStages.unlockedLevel)
        val rawAttempts = preferences[dailyAttemptsKey]
        val savedAttempts = rawAttempts?.let(DailyAttemptsCodec::decode)
        val rawStageDaily = preferences[dailyStageRunKey]
        val savedStageDaily = rawStageDaily?.let(SnapshotCodec::decode)?.takeIf { it.mode == GameMode.DAILY }
        val recoveredAttempts = savedAttempts ?: if (rawAttempts != null || rawStageDaily != null) {
            DailyAttempts(savedStageDaily?.challengeId ?: today.toString(), DAILY_ATTEMPT_LIMIT,
                active = savedStageDaily != null && savedStageDaily.moves > 0 && !savedStageDaily.isWon && !GameEngine.isGameOver(savedStageDaily))
        } else DailyAttempts(today.toString())
        val attempts = recoveredAttempts.forDate(today)
        val rawDailyStages = preferences[dailyStagesKey]
        val decodedDailyStages = rawDailyStages?.let(CampaignCodec::decode)?.takeIf { it.mode == GameMode.DAILY }
        val dailyStages = decodedDailyStages?.copy(challengeId = "") ?: CampaignProgress(mode = GameMode.DAILY)
        val currentDaily = savedStageDaily?.takeIf { (it.challengeId == attempts.date || it.isWon) &&
            (it.levelNumber == 0 || dailyStages.isUnlocked(it.levelNumber)) }
            ?: GameEngine.stage(GameMode.DAILY,
                savedStageDaily?.levelNumber?.takeIf { dailyStages.isUnlocked(it) } ?: dailyStages.unlockedLevel,
                LocalDate.parse(attempts.date))
        val bestFlow = maxOf(0, preferences[bestFlowKey] ?: 0, legacyFlow?.score ?: 0, flow.score)
        val bestDaily = maxOf(0, preferences[bestDailyKey] ?: 0, legacyDaily?.score ?: 0, currentDaily.score)
        val flowUndo = preferences[flowStageUndoKey]?.let { SnapshotCodec.decodeCheckpoint(it, flow) }
        val dailyUndo = preferences[dailyStageUndoKey]?.let { SnapshotCodec.decodeCheckpoint(it, currentDaily) }
        val levelsUndo = levels?.let { game -> preferences[levelsUndoKey]?.let { SnapshotCodec.decodeCheckpoint(it, game) } }
        val palette = TilePalette.entries.firstOrNull { it.name == preferences[paletteKey] }
            ?.takeIf { it.isUnlocked(maxOf(bestFlow, bestDaily)) } ?: TilePalette.PRISM
        return LoadedProgress(
            PlayerProgress(
                flow = flow,
                daily = currentDaily,
                activeMode = if (preferences[modeKey] == GameMode.DAILY.name) GameMode.DAILY else GameMode.FLOW,
                bestFlow = bestFlow,
                bestDaily = bestDaily,
                sound = preferences[soundKey] ?: true,
                haptics = preferences[hapticsKey] ?: true,
                flowUndo = flowUndo,
                dailyUndo = dailyUndo,
                palette = palette,
                levels = levels ?: GameEngine.level(campaign.unlockedLevel),
                campaign = campaign,
                levelsUndo = levelsUndo,
                flowStages = flowStages.record(flow),
                dailyStages = dailyStages.record(currentDaily),
                dailyAttempts = attempts,
                dailyPoints = maxOf(0, preferences[dailyPointsKey] ?: 0, dailyStages.totalPoints).coerceAtMost(MAX_SCORE),
                legacyLevelCredit = maxOf(0, preferences[legacyCreditKey] ?: if (rawFlowStages == null) {
                    campaign.totalPoints - migratedFlowStages.totalPoints
                } else 0),
            ),
            recovered = (rawFlow != null && legacyFlow == null) || (rawDaily != null && legacyDaily == null) ||
                (rawStageFlow != null && stageFlow == null) || (rawStageDaily != null && savedStageDaily == null) ||
                (rawFlowStages != null && decodedFlowStages == null) || (rawAttempts != null && savedAttempts == null) ||
                (preferences[flowStageUndoKey] != null && flowUndo == null) ||
                (savedStageDaily?.challengeId == attempts.date && preferences[dailyStageUndoKey] != null && dailyUndo == null) ||
                (rawCampaign != null && decodedCampaign == null) || (rawLevels != null && levels == null) ||
                (levels != null && preferences[levelsUndoKey] != null && levelsUndo == null),
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