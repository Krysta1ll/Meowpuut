package com.krysta1ll.meowpuut.android.app.stats

import com.krysta1ll.meowpuut.android.ime.glyph.GlyphJourney
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphScenes
import java.time.LocalDate

/** One festival in one year: its own days (first to last, as local day numbers) and its main day. */
internal class FestivalOccurrence(val id: String, val hant: String, val hans: String, val first: Int, val main: Int, val last: Int) {
    fun name(simplified: Boolean) = if (simplified) hans else hant
}

/**
 * Where the festival days come from. The statistics ask for nothing else, so the list can be swapped for the larger one of world
 * festivals (the days table and the stamps) without touching them.
 */
internal fun interface FestivalSource {
    fun occurrences(): List<FestivalOccurrence>
}

/** The picture a festival leaves by the road, as the rows of a stamp ("X" ink, "R" vermilion, "o" a light); null for one without its own. */
internal fun interface FestivalStamps {
    fun stamp(id: String): List<String>?
}

/**
 * What the records add up to, for the 行旅 page and the home page. A projection of [StatsLedger]: every field is a running total that
 * only grows while the records are kept (the rule of the cats' records, tested): no streaks, no averages, no bests, no "days since".
 * A day with nothing typed is not a thing here at all.
 */
internal class JourneyStats(
    val graphemes: Long,
    /** 0…100: the part of the cats' affection both share (one point per 200 characters), the ribbon of iOS. */
    val sharedAffection: Int,
    val mimimo: CatTotals,
    val momo: CatTotals,
    /** Days with typing, all the days the ledger has seen. */
    val daysMet: Int,
    val bothCatsDays: Int,
    /** Days with typing in each part of the day: dawn, day, evening, night, late night. */
    val dayParts: IntArray,
    /** `daysReaching[k]`: days whose size reached band k (so index 0 is every day). Counted as "at least": a day's band can still rise today. */
    val daysReaching: IntArray,
    /** Every day with typing, oldest first. */
    val days: List<DayEntry>,
    val months: List<Month>,
    /** How far the cats have walked: eight units for each committed character (the same journey as on the iPhone). */
    val distance: Long,
    /** The number of places the cats have come to, the one they are in included (the "stop" number). */
    val stop: Int,
    /** The scene the cats are in now. */
    val here: Int,
    /** How many times each scene has been reached (index: scene). */
    val visits: IntArray,
    val festivals: List<FestivalMet>,
    /** The records were cleared and nothing has been recorded since. */
    val cleared: Boolean,
) {
    class CatTotals(val level: Int, val pets: Long, val falls: Long, val plays: Long, val days: Int)

    class DayEntry(val day: Int, val size: Int, val who: Int, val part: Int, val festival: FestivalOccurrence?) {
        val date: LocalDate get() = LocalDate.ofEpochDay(day.toLong())
    }

    class Month(val year: Int, val month: Int, val days: Int, val largestBand: Int)

    /** A festival the person typed on: how many of its years, and the first day. */
    class FestivalMet(val id: String, val hant: String, val hans: String, val years: Int, val firstDay: Int) {
        fun name(simplified: Boolean) = if (simplified) hans else hant
    }

    /** Nothing recorded at all: no day, no character, no touch, no step. The page then says so in a sentence, with no chart. */
    val isEmpty: Boolean get() = graphemes == 0L && daysMet == 0 && mimimo.pets == 0L && momo.pets == 0L && mimimo.falls == 0L && momo.falls == 0L

    val firstDay: Int? get() = days.firstOrNull()?.day

    val sinceDate: LocalDate? get() = firstDay?.let { LocalDate.ofEpochDay(it.toLong()) }

    companion object {
        fun of(ledger: StatsLedger, festivals: List<FestivalOccurrence> = emptyList()): JourneyStats {
            val dayParts = IntArray(5)
            val reaching = IntArray(5)
            var both = 0
            var days1 = 0
            var days2 = 0
            val met = HashMap<Int, MutableList<FestivalOccurrence>>()
            if (festivals.isNotEmpty()) {
                // The first day typed within a festival's own days is the day it was met; two festivals on one day are both met.
                for (f in festivals) {
                    val hit = ledger.dayMap.subMap(f.first, true, f.last, true).keys.firstOrNull() ?: continue
                    met.getOrPut(hit) { ArrayList(1) } += f
                }
            }
            val entries = ArrayList<DayEntry>(ledger.dayMap.size)
            val monthDays = LinkedHashMap<Int, IntArray>()
            for ((day, d) in ledger.dayMap) {
                if (d.who and 3 == 3) both++
                if (d.who and 1 != 0) days1++
                if (d.who and 2 != 0) days2++
                for (bit in 0 until 5) if (d.part and (1 shl bit) != 0) dayParts[bit]++
                for (band in 0..d.size) reaching[band]++
                entries += DayEntry(day, d.size, d.who, d.part, met[day]?.firstOrNull())
                val date = LocalDate.ofEpochDay(day.toLong())
                val key = date.year * 12 + date.monthValue - 1
                val m = monthDays.getOrPut(key) { intArrayOf(date.year, date.monthValue, 0, 0) }
                m[2]++
                m[3] = maxOf(m[3], d.size)
            }
            val months = monthDays.values.map { Month(it[0], it[1], it[2], it[3]) }
            val meetings = LinkedHashMap<String, FestivalMet>()
            for (list in met.entries.sortedBy { it.key }) for (f in list.value) {
                val old = meetings[f.id]
                meetings[f.id] = if (old == null) FestivalMet(f.id, f.hant, f.hans, 1, list.key) else FestivalMet(f.id, f.hant, f.hans, old.years + 1, old.firstDay)
            }
            val distance = GlyphJourney.distanceOf(ledger.graphemes)
            val places = places(distance)
            return JourneyStats(
                graphemes = ledger.graphemes,
                sharedAffection = (ledger.graphemes / 200).coerceAtMost(100).toInt(),
                mimimo = CatTotals(ledger.mimimo.level, ledger.mimimo.pets, ledger.mimimo.falls, ledger.mimimo.plays, days1),
                momo = CatTotals(ledger.momo.level, ledger.momo.pets, ledger.momo.falls, ledger.momo.plays, days2),
                daysMet = ledger.dayMap.size, bothCatsDays = both, dayParts = dayParts, daysReaching = reaching,
                days = entries, months = months,
                distance = distance, stop = places.stop, here = places.here, visits = places.visits,
                festivals = meetings.values.toList(), cleared = ledger.cleared,
            )
        }

        class Places(val stop: Int, val here: Int, val visits: IntArray)

        /**
         * Where a distance has got to on the journey (the same one the keyboard walks, [GlyphJourney]): the place it is in, how many it
         * has come to, and how often each scene has been reached. Zero distance is the first place.
         */
        fun places(distance: Long, journey: GlyphJourney = GlyphJourney()): Places {
            val visits = IntArray(GlyphScenes.ALL.size)
            var sum = 0L
            var n = 0
            while (true) {
                val p = journey.placeAt(n)
                visits[p.scene]++
                sum += p.len
                if (sum > distance) return Places(n + 1, p.scene, visits)
                n++
            }
        }
    }
}
