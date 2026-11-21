package com.krysta1ll.meowpuut.android.app.stats

import android.content.Context
import com.krysta1ll.meowpuut.android.ime.LocalCatAffinity
import com.krysta1ll.meowpuut.android.ime.Prefs
import java.util.concurrent.Executors

/**
 * The app's side of the statistics: reads the one record the keyboard already keeps (filesDir/PetBook.bin) and the journey's distance
 * (the keyboard's settings), folds them into the ledger (filesDir/PetStatsLedger.json, the app's own file) and answers [JourneyStats]
 * (see [StatsFiles]). The keyboard writes nothing for it and none of this is on the key path: it runs when the home or 行旅 page opens.
 */
internal class StatsStore(private val context: Context, festivals: FestivalSource = FestivalSource { emptyList() }) {
    private val files = StatsFiles(
        context.filesDir, festivals, { Prefs(context).glyphDistance }, { forgetElsewhere() }, LocalCatAffinity.FILE,
    )

    private fun forgetElsewhere() {
        LocalCatAffinity.forgetLegacyCounters(context)
        Prefs(context).glyphDistance = 0L
    }

    /** Reads, absorbs and answers. Blocks on the files: call it off the main thread (see [refreshAsync]). */
    fun refresh(): JourneyStats = files.refresh()

    /** The cats' records are cleared, and the statistics and the journey with them. */
    fun forget() = files.forget()

    private val io = lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "meowpuut-stats").apply { isDaemon = true } } }

    /** [refresh] on a thread of its own; [done] runs on the main thread. */
    fun refreshAsync(done: (JourneyStats) -> Unit) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        io.value.execute {
            val stats = try { refresh() } catch (_: RuntimeException) { null }
            if (stats != null) handler.post { done(stats) }
        }
    }

    fun shutdown() { if (io.isInitialized()) io.value.shutdown() }
}
