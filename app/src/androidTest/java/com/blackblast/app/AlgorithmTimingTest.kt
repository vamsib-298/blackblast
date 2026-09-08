package com.blackblast.app

import android.os.Debug
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.blackblast.core.GameEngine
import com.blackblast.core.GameState
import com.blackblast.core.Piece
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AlgorithmTimingTest {
    @Test
    fun measureHintLatencyAndAllocationsOnFixedBoards() {
        val tray = listOf(Piece(8, 2), Piece(10, 3), Piece(11, 4))
        val fixtures = linkedMapOf(
            "empty" to GameState(tray = tray),
            "midgame" to GameState(board = "0001100000211000002233000020033011040000011445500064005006600050".map { it.digitToInt() }, tray = tray),
            "blocked" to GameState(board = List(64) { if ((it / 8 + it % 8) % 2 == 0) 1 else 0 }, tray = List(3) { Piece(5, 1) }),
        )
        val results = JSONArray()
        fixtures.forEach { (name, state) ->
            repeat(15) { GameEngine.findHint(state) }
            val allocationsBefore = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()
            val times = ArrayList<Long>(100)
            repeat(100) {
                val started = SystemClock.elapsedRealtimeNanos()
                val hint = GameEngine.findHint(state)
                times += SystemClock.elapsedRealtimeNanos() - started
                if (name == "blocked") assertNull(hint)
                else {
                    assertNotNull(hint)
                    val chosen = hint!!
                    assertTrue(GameEngine.canPlace(state.board, state.tray[chosen.slot]!!.copy(rotation = chosen.rotation), chosen.row, chosen.column))
                }
            }
            val allocated = Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()
            times.sort()
            results.put(JSONObject().put("fixture", name).put("samples", times.size)
                .put("p50Ns", times[49]).put("p95Ns", times[94]).put("maxNs", times.last())
                .put("processAllocatedBytes", if (allocated != null && allocationsBefore != null) allocated - allocationsBefore else JSONObject.NULL))
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "qa").apply { mkdirs() }
        File(directory, "algorithm-timing.json").writeText(JSONObject()
            .put("scope", "Android emulator; 15 warmups per board; allocation counters are process-wide")
            .put("hint", results).toString(2))
    }
}