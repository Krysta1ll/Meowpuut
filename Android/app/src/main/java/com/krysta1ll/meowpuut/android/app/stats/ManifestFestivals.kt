package com.krysta1ll.meowpuut.android.app.stats

import android.content.Context
import com.krysta1ll.meowpuut.android.ime.Festivals

/**
 * The festivals the app already knows (assets/festivals/festivals.json: the ones with a costume, one list of days for each year), as the
 * statistics' [FestivalSource]. The larger list of world festivals replaces this by implementing the same interface.
 */
internal class ManifestFestivals(private val context: Context) : FestivalSource {
    private val list: List<FestivalOccurrence> by lazy {
        val json = try { context.assets.open(Festivals.MANIFEST).use { it.readBytes().decodeToString() } } catch (_: Exception) { return@lazy emptyList() }
        from(Festivals.parse(json))
    }

    override fun occurrences(): List<FestivalOccurrence> = list

    companion object {
        fun from(loaded: Festivals.Loaded): List<FestivalOccurrence> = loaded.themes.flatMap { t ->
            t.days.map { d -> FestivalOccurrence(t.id, t.hant, t.hans, d.first.toEpochDay().toInt(), d.main.toEpochDay().toInt(), d.last.toEpochDay().toInt()) }
        }
    }
}
