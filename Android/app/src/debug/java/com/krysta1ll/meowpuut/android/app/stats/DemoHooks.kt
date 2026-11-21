package com.krysta1ll.meowpuut.android.app.stats

import android.content.Context
import android.content.Intent
import com.krysta1ll.meowpuut.android.ime.CustomSkinLoader
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * Debug builds only (src/debug): shows the statistics page and the home page a set of made-up records and a pretend date, so their
 * festival parts can be looked at without writing into the app's own files. The release build has an empty stand-in (src/release).
 *
 *   adb shell am start -n com.krysta1ll.meowpuut.android/.app.SettingsActivity --es meowpuut.demo road
 *   adb shell am start -n com.krysta1ll.meowpuut.android/.app.SettingsActivity --es meowpuut.demo festival-day
 *   adb shell am start -n com.krysta1ll.meowpuut.android/.app.SettingsActivity --es meowpuut.demo few      (sixty characters: most places not reached yet)
 *
 * "road": a year of typing with a dozen festivals met, on an ordinary day. "festival-day": the same records, on a day that is one of the
 * festivals with a picture of its own. Nothing is stored: the choice lasts until the process ends, and the files are never read or written.
 */
internal object DemoHooks {
    @Volatile private var name: String? = null
    @Volatile private var app: Context? = null
    private val ordinary = LocalDate.of(2026, 11, 21).toEpochDay().toInt()

    fun enable(context: Context, intent: Intent?) {
        if (!CustomSkinLoader.debuggable(context)) return
        val asked = intent?.getStringExtra("meowpuut.demo") ?: return
        name = asked
        app = context.applicationContext
    }

    /** Today, as a local day number (the pretend one while a demo runs). */
    fun today(): Int {
        val which = name ?: return LocalDate.now().toEpochDay().toInt()
        if (which != "festival-day") return ordinary
        val list = BundledFestivals.of(app ?: return ordinary).occurrences()
        return list.filter { it.ownStamp && it.main > ordinary }.minByOrNull { it.main }?.main ?: ordinary
    }

    fun characters(context: Context): Long? = if (name == null) null else if (name == "few") 60L else 248_316L

    fun stats(context: Context, festivals: FestivalSource): JourneyStats? {
        if (name == null) return null
        val list = festivals.occurrences()
        val today = today()
        val ledger = StatsLedger.parse(ledgerJson(list, today)).ledger ?: return null
        return JourneyStats.of(ledger, list)
    }

    /** About fourteen months of days (most of the days of a typist, some missing) and the first day of every festival that has come. */
    private fun ledgerJson(festivals: List<FestivalOccurrence>, today: Int): String {
        val days = LinkedHashMap<Int, Long>()
        fun put(day: Int, k: Int) {
            val size = (k * 7 + day) % 5
            val who = 1 + (day + k) % 3
            val part = 1 shl ((day * 3 + k) % 5) or (1 shl ((day + 2) % 5))
            days[day] = (day.toLong() shl 24) or (size.toLong() shl 16) or (who.toLong() shl 8) or part.toLong()
        }
        val met = festivals.filter { it.last < today - 1 && it.first > today - 400 }.sortedBy { it.main }
        val chosen = met.filterIndexed { k, _ -> k % 16 == 0 }                         // about fifteen festivals met
        // Ordinary days keep out of every festival's days (a day typed on one meets it), except those of the festivals chosen.
        val inFestival = HashSet<Int>()
        for (f in festivals) if (f !in chosen) for (d in f.from..f.to) inFestival += d
        for (i in 0 until 430) { val d = today - 1 - i; if ((i * 7) % 11 != 0 && (i * 5) % 13 != 2 && d !in inFestival) put(d, i) }
        for ((k, f) in chosen.withIndex()) put(f.main, k + 3)
        val root = JSONObject().put("version", StatsLedger.VERSION).put("epoch", 1).put("graphemes", if (name == "few") 60 else 248_316).put("cleared", false)
        root.put("mimimo", JSONObject().put("level", 86).put("pets", 141).put("falls", 12).put("plays", 58))
        root.put("momo", JSONObject().put("level", 100).put("pets", 203).put("falls", 4).put("plays", 77))
        root.put("days", JSONArray(days.keys.sorted().map { days.getValue(it) }))
        return root.toString()
    }
}
