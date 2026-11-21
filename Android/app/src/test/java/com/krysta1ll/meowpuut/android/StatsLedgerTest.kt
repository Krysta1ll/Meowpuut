package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.app.stats.FestivalOccurrence
import com.krysta1ll.meowpuut.android.app.stats.JourneyStats
import com.krysta1ll.meowpuut.android.app.stats.PetBookFile
import com.krysta1ll.meowpuut.android.app.stats.StatsLedger
import com.krysta1ll.meowpuut.android.ime.JourneyOdometer
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphJourney
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphScenes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Random

/** The statistics' data layer: the paw-book file, the grow-only ledger, what it adds up to, and the journey's odometer. */
class StatsLedgerTest {
    private fun resource(name: String): ByteArray = javaClass.classLoader!!.getResourceAsStream(name)!!.use { it.readBytes() }

    /** A state file as the pets core writes it (layout of codec.rs), for tests that need histories the core would take years to write. */
    private fun state(
        epoch: Long = 0, graphemes: Long = 0, mimimo: LongArray = LongArray(3), momo: LongArray = LongArray(3),
        marks: List<IntArray> = emptyList(),
    ): ByteArray {
        val b = ByteArray(PetBookFile.STATE_BYTES)
        b[0] = 0x9C.toByte(); b[1] = 0xA7.toByte(); b[2] = 1
        fun put(at: Int, v: Long, n: Int) { for (i in 0 until n) b[at + i] = (v shr (8 * i)).toByte() }
        put(4, epoch, 4); put(8, graphemes, 8)
        for (i in 0 until 3) { put(16 + 8 * i, mimimo[i], 8); put(40 + 8 * i, momo[i], 8) }
        for (m in marks) {   // day, size, who, part, and optionally the slot to put it in (the core puts a day at day % 400)
            val at = 80 + (if (m.size > 4) m[4] else m[0] % PetBookFile.BOOK_DAYS) * 7
            put(at, m[0].toLong(), 4); b[at + 4] = m[1].toByte(); b[at + 5] = m[2].toByte(); b[at + 6] = m[3].toByte()
        }
        var hash = 0x811C9DC5L
        for (i in 0 until PetBookFile.STATE_BYTES - 4) { hash = hash xor (b[i].toLong() and 0xFF); hash = (hash * 0x01000193L) and 0xFFFFFFFFL }
        put(PetBookFile.STATE_BYTES - 4, hash, 4)
        return b
    }

    private fun book(bytes: ByteArray) = PetBookFile.parse(bytes)!!

    // ---- the file ---------------------------------------------------------------------------------------------

    @Test
    fun theFileTheRustCoreWroteReadsBack() {
        val read = book(resource("petbook-golden.bin"))
        assertEquals(3L, read.epoch)
        assertEquals(248_316L, read.graphemes)
        assertEquals(141L, read.mimimo.pets); assertEquals(7L, read.mimimo.falls); assertEquals(58L, read.mimimo.plays)
        assertEquals(203L, read.momo.pets); assertEquals(11L, read.momo.falls); assertEquals(77L, read.momo.plays)
        // 450 days were recorded; the ring holds the last 400.
        assertEquals(400, read.marks.size)
        assertEquals(20_100, read.marks.minOf { it.day })
        assertEquals(20_499, read.marks.maxOf { it.day })
        val last = read.marks.first { it.day == 20_499 }
        assertEquals(4, last.size); assertEquals(1, last.who); assertEquals(16, last.part)
        assertEquals(2, read.marks.first { it.day == 20_101 }.who)
        assertEquals(3, read.marks.first { it.day == 20_102 }.who)
    }

    @Test
    fun clearingLeavesWhatTheCoreLeaves() {
        // The core's `clear()` of the golden state (epoch 3) is an empty state at epoch 4; the app writes the same bytes.
        assertArrayEquals(resource("petbook-cleared.bin"), PetBookFile.cleared(4))
        val read = book(PetBookFile.cleared(4))
        assertEquals(4L, read.epoch); assertEquals(0L, read.graphemes); assertTrue(read.marks.isEmpty())
        assertEquals(4L, PetBookFile.epochOf(PetBookFile.cleared(4)))
    }

    @Test
    fun aFileNobodyCanVouchForReadsAsNothing() {
        val good = resource("petbook-golden.bin")
        assertNull(PetBookFile.parse(null))
        assertNull(PetBookFile.parse(ByteArray(0)))
        assertNull(PetBookFile.parse(good.copyOf(PetBookFile.STATE_BYTES - 1)))
        assertNull(PetBookFile.parse(good.copyOf().also { it[0] = 0 }))
        assertNull(PetBookFile.parse(good.copyOf().also { it[2] = 2 }))            // another version
        assertNull(PetBookFile.parse(good.copyOf().also { it[20] = (it[20] + 1).toByte() }))   // a flipped counter: the checksum says no
        // A mark in a slot its day does not belong to (with a matching checksum) is refused as well.
        assertNotNull(PetBookFile.parse(state(marks = listOf(intArrayOf(20_100, 1, 1, 1)))))
        assertNull(PetBookFile.parse(state(marks = listOf(intArrayOf(20_100, 1, 1, 1, 1)))))
        assertNull(PetBookFile.parse(state(marks = listOf(intArrayOf(20_100, 5, 1, 1)))))      // a size past band 4
        assertNull(PetBookFile.parse(state(marks = listOf(intArrayOf(20_100, 1, 4, 1)))))      // a cat that does not exist
        assertNull(PetBookFile.epochOf(ByteArray(10)))
    }

    @Test
    fun levelIsTheCoresRule() {
        fun level(g: Long, pets: Long, falls: Long) = PetBookFile.level(g, PetBookFile.Contacts(pets, falls, 99))
        assertEquals(0, level(199, 1, 1))
        assertEquals(1, level(199, 2, 1))            // three contacts are a point
        assertEquals(1, level(200, 0, 0))
        assertEquals(100, level(1_000_000, 0, 0))
        assertEquals(100, level(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE))
    }

    // ---- the ledger -------------------------------------------------------------------------------------------

    @Test
    fun readingTheSameFileTwiceChangesNothing() {
        val ledger = StatsLedger.empty()
        val read = book(resource("petbook-golden.bin"))
        assertTrue(ledger.absorb(read))
        val once = ledger.toJson()
        assertFalse(ledger.absorb(read))
        assertEquals(once, ledger.toJson())
        assertEquals(400, ledger.dayMap.size)
        assertEquals(3L, ledger.epoch)
    }

    @Test
    fun daysTheRingForgetsAreKept() {
        val ledger = StatsLedger.empty()
        ledger.absorb(book(state(epoch = 1, graphemes = 5_000, marks = (20_000 until 20_400).map { intArrayOf(it, 2, 1, 1) })))
        // 150 days later the ring holds 20_150..20_549: the first 150 days are gone from the file but not from the ledger.
        ledger.absorb(book(state(epoch = 1, graphemes = 9_000, marks = (20_150 until 20_550).map { intArrayOf(it, 3, 2, 2) })))
        assertEquals(550, ledger.dayMap.size)
        assertEquals(20_000, ledger.dayMap.firstKey())
        assertEquals(StatsLedger.Day(2, 1, 1), ledger.dayMap[20_000])
        // A day both reads hold combines the way the paw book combines it.
        assertEquals(StatsLedger.Day(3, 3, 3), ledger.dayMap[20_200])
        assertEquals(9_000L, ledger.graphemes)
    }

    @Test
    fun anOlderCopyOfTheFileNeverTakesAnythingAway() {
        val ledger = StatsLedger.empty()
        ledger.absorb(book(state(epoch = 2, graphemes = 9_000, mimimo = longArrayOf(40, 3, 9), marks = listOf(intArrayOf(20_001, 4, 3, 31)))))
        ledger.absorb(book(state(epoch = 2, graphemes = 100, mimimo = longArrayOf(1, 0, 0), marks = listOf(intArrayOf(20_001, 0, 1, 1)))))
        assertEquals(9_000L, ledger.graphemes)
        assertEquals(40L, ledger.mimimo.pets)
        assertEquals(StatsLedger.Day(4, 3, 31), ledger.dayMap[20_001])
        // A copy from before a clearing (a lower epoch) is not read at all.
        ledger.absorb(book(state(epoch = 3)))
        assertFalse(ledger.absorb(book(state(epoch = 2, graphemes = 50_000, marks = listOf(intArrayOf(20_002, 1, 1, 1))))))
        assertEquals(0L, ledger.graphemes)
        assertTrue(ledger.dayMap.isEmpty())
    }

    @Test
    fun clearingEmptiesTheLedgerAndOnlyANewerEpochIsBelievedAgain() {
        val ledger = StatsLedger.empty()
        ledger.absorb(book(state(epoch = 0, graphemes = 7_000, marks = listOf(intArrayOf(20_001, 3, 1, 1)))))
        ledger.absorbDistance(1_234)
        ledger.clear(1)
        assertTrue(ledger.isEmpty)
        assertTrue(ledger.cleared)
        assertEquals(1L, ledger.epoch)
        // The running keyboard's last flush of the old counts (epoch 0) is from before the clearing.
        assertFalse(ledger.absorb(book(state(epoch = 0, graphemes = 7_000, marks = listOf(intArrayOf(20_001, 3, 1, 1))))))
        assertTrue(ledger.cleared)
        // The empty file the app wrote is the same epoch and adds nothing: the page stays calm.
        assertFalse(ledger.absorb(book(PetBookFile.cleared(1))))
        assertTrue(ledger.cleared)
        // New typing after the clearing (epoch 1) starts the records again.
        assertTrue(ledger.absorb(book(state(epoch = 1, graphemes = 40, marks = listOf(intArrayOf(20_900, 0, 2, 4))))))
        assertFalse(ledger.cleared)
        assertEquals(1, ledger.dayMap.size)
    }

    @Test
    fun theDistanceOnlyGrowsAndClearingTakesItToo() {
        val ledger = StatsLedger.empty()
        assertTrue(ledger.absorbDistance(500))
        assertFalse(ledger.absorbDistance(499))
        assertFalse(ledger.absorbDistance(500))
        assertEquals(500L, ledger.distance)
        ledger.clear(5)
        assertEquals(0L, ledger.distance)
    }

    @Test
    fun theStoredFormRoundTripsAndIsNeverHalfUnderstood() {
        val ledger = StatsLedger.empty()
        ledger.absorb(book(resource("petbook-golden.bin")))
        ledger.absorbDistance(6_820)
        val json = ledger.toJson()
        val back = StatsLedger.parse(json)
        assertNotNull(back.ledger)
        assertEquals(json, back.ledger!!.toJson())
        assertTrue(back.writable)
        // Nothing in it is text: the keys are fixed and every value is a number.
        assertTrue(json.length < 20_000)
        assertFalse(StatsLedger.parse("{\"version\":2}").writable)      // a newer ledger is left alone
        assertNull(StatsLedger.parse("{\"version\":2}").ledger)
        assertTrue(StatsLedger.parse("not json").damaged)
        assertTrue(StatsLedger.parse("{\"version\":1}").damaged)
        assertNull(StatsLedger.parse(null).ledger)
        assertFalse(StatsLedger.parse(null).damaged)
        // A day out of range is a damaged ledger, not a day.
        assertTrue(StatsLedger.parse(json.replace(Regex("\"days\":\\[[^\\]]*\\]"), "\"days\":[${(20_000L shl 24) or (9L shl 16)}]")).damaged)
    }

    // ---- what it adds up to ----------------------------------------------------------------------------------

    @Test
    fun theTotalsOfTheGoldenFile() {
        val ledger = StatsLedger.empty()
        ledger.absorb(book(resource("petbook-golden.bin")))
        ledger.absorbDistance(6_820)
        val stats = JourneyStats.of(ledger)
        assertEquals(400, stats.daysMet)
        assertEquals(248_316L, stats.graphemes)
        assertEquals(100, stats.sharedAffection)
        assertEquals(100, stats.mimimo.level)
        // The golden file's days: who is 1 (Mimimo), 2 (Momo) or 3 (both) by day % 3; 400 days from 20100.
        assertEquals((20_100 until 20_500).count { it % 3 == 2 }, stats.bothCatsDays)
        assertEquals((20_100 until 20_500).count { it % 3 != 1 }, stats.mimimo.days)
        assertEquals((20_100 until 20_500).count { it % 3 != 0 }, stats.momo.days)
        // "at least": every day reaches band 0, and a band holds every day of its own height and above.
        assertEquals(400, stats.daysReaching[0])
        for (k in 1..4) assertTrue(stats.daysReaching[k] <= stats.daysReaching[k - 1])
        assertEquals((20_100 until 20_500).count { it % 5 == 4 }, stats.daysReaching[4])
        assertEquals(stats.days.size, stats.daysReaching[0])
        assertEquals(stats.days.sumOf { Integer.bitCount(it.part) }, stats.dayParts.sum())
        assertEquals(stats.daysMet, stats.months.sumOf { it.days })
        assertEquals(stats.days.first().date.year, stats.months.first().year)
        assertFalse(stats.isEmpty)
        assertFalse(stats.cleared)
    }

    @Test
    fun anEmptyLedgerIsEmptyAndAClearedOneSaysSo() {
        assertTrue(JourneyStats.of(StatsLedger.empty()).isEmpty)
        assertFalse(JourneyStats.of(StatsLedger.empty()).cleared)
        val ledger = StatsLedger.empty()
        ledger.absorb(book(state(graphemes = 10, marks = listOf(intArrayOf(20_001, 0, 1, 1)))))
        ledger.clear(1)
        val stats = JourneyStats.of(ledger)
        assertTrue(stats.isEmpty)
        assertTrue(stats.cleared)
        assertEquals(1, stats.stop)                // the cats stand in the first place
        assertEquals(0, stats.daysMet)
    }

    @Test
    fun festivalsAreMetOnTheDaysTheyHaveAndOnlyWhenTyped() {
        val spring = LocalDate.of(2026, 2, 17).toEpochDay().toInt()
        val lantern = LocalDate.of(2026, 3, 3).toEpochDay().toInt()
        val fests = listOf(
            FestivalOccurrence("spring_festival", "春節", "春节", spring - 1, spring, spring + 6),
            FestivalOccurrence("lantern_festival", "元宵", "元宵", lantern, lantern, lantern),
            FestivalOccurrence("spring_festival", "春節", "春节", spring + 355, spring + 356, spring + 362),   // a year not typed in
            FestivalOccurrence("mid_autumn", "中秋", "中秋", spring + 200, spring + 200, spring + 200),
        )
        val ledger = StatsLedger.empty()
        ledger.absorb(book(state(marks = listOf(intArrayOf(spring + 3, 2, 1, 1), intArrayOf(spring + 4, 2, 1, 1), intArrayOf(lantern, 1, 2, 2), intArrayOf(lantern + 1, 1, 2, 2)))))
        val stats = JourneyStats.of(ledger, fests)
        assertEquals(listOf("spring_festival", "lantern_festival"), stats.festivals.map { it.id })
        assertEquals(1, stats.festivals[0].years)
        assertEquals(spring + 3, stats.festivals[0].firstDay)            // the first day typed inside its days
        assertEquals("春节", stats.festivals[0].name(true))
        assertEquals("spring_festival", stats.days.first { it.day == spring + 3 }.festival?.id)
        assertNull(stats.days.first { it.day == spring + 4 }.festival)    // one day stands for a festival
        assertNull(stats.days.first { it.day == lantern + 1 }.festival)
        // Typing in the year before it comes round again meets it a second time.
        ledger.absorb(book(state(marks = listOf(intArrayOf(spring + 357, 0, 1, 1)))))
        assertEquals(2, JourneyStats.of(ledger, fests).festivals.first { it.id == "spring_festival" }.years)
    }

    @Test
    fun theShippedFestivalsAreMetByTypingOnTheirDays() {
        val list = com.krysta1ll.meowpuut.android.app.stats.ManifestFestivals.from(
            com.krysta1ll.meowpuut.android.ime.Festivals.parse(java.io.File("src/main/assets/festivals/festivals.json").readText()),
        )
        val midAutumn = list.first { it.id == "mid_autumn" && it.main == LocalDate.of(2026, 9, 25).toEpochDay().toInt() }
        val ledger = StatsLedger.empty()
        ledger.absorb(book(state(marks = listOf(intArrayOf(midAutumn.main - 1, 1, 1, 1)))))
        assertTrue(JourneyStats.of(ledger, list).festivals.none { it.id == "mid_autumn" })      // the day before is no meeting
        ledger.absorb(book(state(marks = listOf(intArrayOf(midAutumn.main, 1, 1, 1)))))
        val met = JourneyStats.of(ledger, list).festivals.first { it.id == "mid_autumn" }
        assertEquals("中秋", met.name(false))
        assertEquals(midAutumn.main, met.firstDay)
    }

    // ---- the journey -----------------------------------------------------------------------------------------

    @Test
    fun theDistanceFindsItsPlaceTheWayTheKeyboardWalksIt() {
        val journey = GlyphJourney()
        var before = JourneyStats.places(0)
        assertEquals(1, before.stop)
        assertEquals(1, before.visits.sum())
        var lastStop = 1
        for (distance in listOf(0L, 1L, 89L, 90L, 269L, 270L, 1_000L, 6_820L, 100_000L, 248_316L)) {
            val p = JourneyStats.places(distance, journey)
            // The keyboard's own walk: the place a distance falls in, and where it begins.
            val (n, from) = GlyphJourney().at(distance)
            assertEquals(n + 1, p.stop)
            assertEquals(journey.placeAt(n).scene, p.here)
            assertTrue(from <= distance && distance < from + journey.placeAt(n).len)
            assertEquals(p.stop, p.visits.sum())
            assertEquals(GlyphScenes.ALL.size, p.visits.size)
            assertTrue(p.stop >= lastStop)
            lastStop = p.stop
        }
        // Past the first place the second begins; no place is ever reached "less often" for walking further.
        val first = journey.placeAt(0).len.toLong()
        assertEquals(1, JourneyStats.places(first - 1).stop)
        assertEquals(2, JourneyStats.places(first).stop)
    }

    @Test
    fun noTotalEverGoesDownWhateverOrderTheReadsComeIn() {
        val rng = Random(20_261_121L)
        repeat(60) { round ->
            val ledger = StatsLedger.empty()
            var epoch = 0L
            var previous: IntArray? = null
            var visits: IntArray? = null
            repeat(25) { step ->
                // A read: sometimes stale (fewer days, smaller counters), sometimes ahead; the epoch only rises with a clearing, below.
                val base = 19_900 + rng.nextInt(300)
                val marks = (0 until rng.nextInt(60)).map { intArrayOf(base + rng.nextInt(400), rng.nextInt(5), 1 + rng.nextInt(3), 1 + rng.nextInt(31)) }
                    .groupBy { it[0] }.map { it.value.last() }
                val g = rng.nextInt(400_000).toLong()
                ledger.absorb(book(state(epoch = epoch, graphemes = g, mimimo = longArrayOf(rng.nextInt(900).toLong(), rng.nextInt(90).toLong(), rng.nextInt(99).toLong()),
                    momo = longArrayOf(rng.nextInt(900).toLong(), rng.nextInt(90).toLong(), rng.nextInt(99).toLong()), marks = marks)))
                ledger.absorbDistance(rng.nextInt(300_000).toLong())
                val stats = JourneyStats.of(ledger)
                val now = intArrayOf(
                    stats.graphemes.toInt(), stats.daysMet, stats.bothCatsDays, stats.mimimo.level, stats.momo.level,
                    stats.mimimo.pets.toInt(), stats.mimimo.falls.toInt(), stats.mimimo.plays.toInt(), stats.momo.pets.toInt(), stats.momo.falls.toInt(), stats.momo.plays.toInt(),
                    stats.mimimo.days, stats.momo.days, stats.stop, stats.sharedAffection, *stats.dayParts, *stats.daysReaching, stats.months.size,
                )
                previous?.let { old -> for (i in now.indices) assertTrue("round $round step $step total $i went from ${old[i]} to ${now[i]}", now[i] >= old[i]) }
                previous = now
                val v = stats.visits
                visits?.let { old -> for (i in v.indices) assertTrue("visits of scene $i went down", v[i] >= old[i]) }
                visits = v
            }
        }
    }

    // ---- the keyboard's counter -------------------------------------------------------------------------------

    @Test
    fun theOdometerCountsUnderEverySkinAndWritesLittle() {
        var stored = 1_000L
        var writes = 0
        val odometer = JourneyOdometer({ stored }, { stored = it; writes++ })
        // 字景 counts for itself while its world runs: nothing is added here then.
        odometer.committed(30, worldCounts = true)
        assertEquals(1_000L, stored); assertEquals(0L, odometer.waiting)
        repeat(24) { odometer.committed(1, worldCounts = false) }
        assertEquals(0, writes); assertEquals(24L, odometer.waiting)       // under 25 characters nothing is written
        odometer.committed(1, worldCounts = false)
        assertEquals(1, writes); assertEquals(1_025L, stored)
        odometer.committed(0, false); odometer.committed(-3, false)
        assertEquals(0L, odometer.waiting)
        odometer.committed(7, false)
        odometer.flush()                                                    // the keyboard goes away
        assertEquals(1_032L, stored)
        odometer.flush()
        assertEquals(2, writes)
        // Cleared records drop what was counted and not yet stored.
        odometer.committed(10, false)
        odometer.reset()
        odometer.flush()
        assertEquals(1_032L, stored)
        // Another skin's long stretch of typing adds to what 字景 left, never replaces it.
        stored = 50_000L
        odometer.committed(100, false)
        assertEquals(50_100L, stored)
    }
}
