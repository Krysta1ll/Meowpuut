package com.krysta1ll.meowpuut.android.app.stats

import android.content.Context
import com.krysta1ll.meowpuut.android.ime.Festivals
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDate

/**
 * The world's festivals, 2026 to 2030 (assets/festivals-world/, from design-preview/festivals/README.md): one stamp each, shown only on the
 * statistics page and in the home page's scenery, never on the keyboard. Two files:
 *  - festivals-world.days.json: every festival with its names, its stamp, how far its date may drift, and its days: a list of
 *    `[first, main, last]` (yyyy-MM-dd), several to a year for some and none for years it has no data for, so each entry stands alone (its
 *    year is its main day's). `existingStamps` names the stamp of each of the 24 festivals that have a costume.
 *  - festivals-world.stamps.json: the pictures, by name.
 * A festival or a picture that does not read is left out, never the rest.
 */
internal object WorldFestivals {
    class Loaded(val occurrences: List<FestivalOccurrence>, val stamps: Map<String, List<String>>)

    /** What a row of a stamp may hold: nothing, ink, vermilion, a light, or one of the second inks. */
    private const val STAMP_CHARS = ".XRoGYBPOCM"
    private const val MAX_STAMP = 16
    private const val MAX_DRIFT = 7

    /** The stamps by name; a picture with an odd row, an unknown mark or a size past [MAX_STAMP] is left out. */
    fun parseStamps(json: String): Map<String, List<String>> {
        val root = try { JSONObject(json) } catch (_: JSONException) { return emptyMap() }
        val out = LinkedHashMap<String, List<String>>()
        for (name in root.keys()) {
            if (!Festivals.isName(name)) continue
            val rows = try { root.getJSONArray(name).let { a -> (0 until a.length()).map { a.getString(it) } } } catch (_: JSONException) { continue }
            if (rows.isEmpty() || rows.size > MAX_STAMP) continue
            val width = rows[0].length
            if (width == 0 || width > MAX_STAMP || rows.any { it.length != width || it.any { c -> c !in STAMP_CHARS } }) continue
            out[name] = rows
        }
        return out
    }

    /**
     * The days file as occurrences, one for each entry of each festival's `days`, and the stamp of each festival that has a costume.
     * [known] is the stamps that exist: a festival whose stamp is not among them has none (the page draws its fallback).
     */
    fun parseDays(json: String, known: Set<String>): Pair<List<FestivalOccurrence>, Map<String, String>> {
        val root = try { JSONObject(json) } catch (_: JSONException) { return emptyList<FestivalOccurrence>() to emptyMap() }
        val existing = HashMap<String, String>()
        root.optJSONObject("existingStamps")?.let { o -> for (id in o.keys()) o.optString(id).takeIf { it.isNotEmpty() && it in known }?.let { existing[id] = it } }
        val list = root.optJSONArray("festivals") ?: return emptyList<FestivalOccurrence>() to existing
        val out = ArrayList<FestivalOccurrence>(list.length() * 5)
        for (i in 0 until list.length()) {
            val f = list.optJSONObject(i) ?: continue
            val id = f.optString("id")
            if (!Festivals.isName(id)) continue
            val names = f.optJSONObject("names") ?: continue
            val hant = names.optString("hant").trim()
            if (hant.isEmpty() || hant.length > 24) continue
            val hans = names.optString("hans").trim().takeIf { it.isNotEmpty() && it.length <= 24 } ?: hant
            val stamp = f.optString("stamp").takeIf { it in known }
            val drift = f.optInt("drift", 0).coerceIn(0, MAX_DRIFT)
            val days = f.optJSONArray("days") ?: continue
            for (k in 0 until days.length()) {
                val range = days.optJSONArray(k) ?: continue
                val d = try { if (range.length() == 3) (0 until 3).map { LocalDate.parse(range.getString(it)).toEpochDay().toInt() } else null } catch (_: Exception) { null } ?: continue
                if (d[0] > d[1] || d[1] > d[2]) continue
                out += FestivalOccurrence(id, hant, hans, d[0], d[1], d[2], drift, stamp)
            }
        }
        return out to existing
    }

    /** Both files, and the 24 festivals that already have a costume (their days are the keyboard's own); all as one list. */
    fun load(daysJson: String, stampsJson: String, wardrobe: Festivals.Loaded): Loaded {
        val stamps = parseStamps(stampsJson)
        val (world, existingStamps) = parseDays(daysJson, stamps.keys)
        val costumed = ManifestFestivals.from(wardrobe).map { o -> FestivalOccurrence(o.id, o.hant, o.hans, o.first, o.main, o.last, 0, existingStamps[o.id]) }
        val ids = costumed.map { it.id }.toSet()
        return Loaded(costumed + world.filter { it.id !in ids }, stamps)
    }
}

/**
 * The festival days and stamps the app ships: the keyboard's 24 (assets/festivals/festivals.json) and the world's (assets/festivals-world/).
 * Read once, on first use (off the main thread: the statistics read it while they refresh), and kept.
 */
internal class BundledFestivals(private val context: Context) : FestivalSource, FestivalStamps {
    private val loaded: WorldFestivals.Loaded by lazy {
        fun asset(path: String): String = try { context.assets.open(path).use { it.readBytes().decodeToString() } } catch (_: Exception) { "" }
        WorldFestivals.load(asset("festivals-world/festivals-world.days.json"), asset("festivals-world/festivals-world.stamps.json"), Festivals.parse(asset(Festivals.MANIFEST)))
    }

    override fun occurrences(): List<FestivalOccurrence> = loaded.occurrences
    override fun stamp(name: String): List<String>? = loaded.stamps[name]

    companion object {
        @Volatile private var shared: BundledFestivals? = null

        /** One for the app: the files are read once, whichever page asks first. */
        fun of(context: Context): BundledFestivals = shared ?: synchronized(BundledFestivals::class.java) {
            shared ?: BundledFestivals(context.applicationContext).also { shared = it }
        }
    }
}
