package com.krysta1ll.meowpuut.android.ime

import org.json.JSONObject
import java.time.LocalDate

/**
 * Festival outfits (docs/FESTIVALS.md). The manifest lives at
 * assets/festivals/festivals.json and is normally empty; images sit in
 * assets/festivals/<id>/ on the same canvas as the cat sprites.
 */
data class CatOutfit(val front: String?, val back: String?, val frames: Map<String, String>)

data class Outfit(
    val id: String,
    val titleTraditional: String,
    val titleSimplified: String,
    val start: LocalDate,
    val end: LocalDate,
    val cats: Map<String, CatOutfit>,
    val pill: Int?,
) {
    fun isActive(day: LocalDate) = !day.isBefore(start) && !day.isAfter(end)
    fun asset(file: String) = "festivals/$id/$file"
}

object Festivals {
    const val MANIFEST = "festivals/festivals.json"
    private val CATS = setOf("mimimo", "momo")
    private val FRAMES = setOf("open", "happy", "blink")

    /** Parses the manifest; malformed entries are skipped, never fatal. */
    fun parse(json: String): List<Outfit> {
        val root = try { JSONObject(json) } catch (_: Exception) { return emptyList() }
        if (root.optInt("version", 0) != 1) return emptyList()
        val list = root.optJSONArray("outfits") ?: return emptyList()
        val out = ArrayList<Outfit>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val id = item.optString("id").takeIf { it.isNotEmpty() && !it.contains('/') && !it.contains("..") } ?: continue
            val start = runCatching { LocalDate.parse(item.optString("start")) }.getOrNull() ?: continue
            val end = runCatching { LocalDate.parse(item.optString("end")) }.getOrNull() ?: continue
            if (end.isBefore(start)) continue
            val title = item.optJSONObject("title")
            val cats = HashMap<String, CatOutfit>()
            val catsJson = item.optJSONObject("cats")
            if (catsJson != null) {
                for (cat in CATS) {
                    val entry = catsJson.optJSONObject(cat) ?: continue
                    val frames = HashMap<String, String>()
                    entry.optJSONObject("frames")?.let { f ->
                        for (frame in FRAMES) f.optString(frame).takeIf { it.isNotEmpty() && safe(it) }?.let { frames[frame] = it }
                    }
                    cats[cat] = CatOutfit(
                        front = entry.optString("front").takeIf { it.isNotEmpty() && safe(it) },
                        back = entry.optString("back").takeIf { it.isNotEmpty() && safe(it) },
                        frames = frames,
                    )
                }
            }
            out += Outfit(
                id = id,
                titleTraditional = title?.optString("hant").orEmpty(),
                titleSimplified = title?.optString("hans").orEmpty(),
                start = start,
                end = end,
                cats = cats,
                pill = Look.parseColor(item.optString("pill").ifEmpty { null }),
            )
        }
        return out
    }

    /** The first outfit in manifest order whose dates include [day]. */
    fun active(outfits: List<Outfit>, day: LocalDate): Outfit? = outfits.firstOrNull { it.isActive(day) }

    private fun safe(file: String) = !file.contains('/') && !file.contains("..")
}
