package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.app.home.Stamps
import com.krysta1ll.meowpuut.android.app.stats.FestivalOccurrence
import com.krysta1ll.meowpuut.android.app.stats.JourneyStats
import com.krysta1ll.meowpuut.android.app.stats.PetBookFile
import com.krysta1ll.meowpuut.android.app.stats.QuietDays
import com.krysta1ll.meowpuut.android.app.stats.StatsLedger
import com.krysta1ll.meowpuut.android.app.stats.WorldFestivals
import com.krysta1ll.meowpuut.android.ime.Festivals
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** The world's festivals as the app reads them: the two shipped files, the rules for a bad entry, and what meeting a festival means. */
class WorldFestivalsTest {
    private val assets = "src/main/assets"
    private val daysJson get() = File("$assets/festivals-world/festivals-world.days.json").readText()
    private val stampsJson get() = File("$assets/festivals-world/festivals-world.stamps.json").readText()
    private val wardrobe get() = Festivals.parse(File("$assets/festivals/festivals.json").readText())
    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).toEpochDay().toInt()

    private fun ledgerOf(vararg days: Int): StatsLedger {
        val b = ByteArray(PetBookFile.STATE_BYTES)
        b[0] = 0x9C.toByte(); b[1] = 0xA7.toByte(); b[2] = 1
        for (d in days) { val at = 80 + (d % 400) * 7; for (i in 0 until 4) b[at + i] = (d shr (8 * i)).toByte(); b[at + 4] = 1; b[at + 5] = 1; b[at + 6] = 1 }
        var hash = 0x811C9DC5L
        for (i in 0 until PetBookFile.STATE_BYTES - 4) { hash = hash xor (b[i].toLong() and 0xFF); hash = (hash * 0x01000193L) and 0xFFFFFFFFL }
        for (i in 0 until 4) b[PetBookFile.STATE_BYTES - 4 + i] = (hash shr (8 * i)).toByte()
        return StatsLedger.empty().also { it.absorb(PetBookFile.parse(b)!!) }
    }

    @Test
    fun theShippedFilesReadWholeAndEveryStampExists() {
        val loaded = WorldFestivals.load(daysJson, stampsJson, wardrobe)
        assertEquals(108, loaded.stamps.size)
        val ids = loaded.occurrences.map { it.id }.toSet()
        assertEquals("286 world festivals and the 24 that have a costume", 286 + 24, ids.size)
        for (o in loaded.occurrences) {
            assertTrue("${o.id} out of order", o.first <= o.main && o.main <= o.last)
            assertTrue("${o.id} outside the table's years", LocalDate.ofEpochDay(o.main.toLong()).year in 2026..2030)
            assertTrue(o.drift in 0..3)
            assertTrue("${o.id} has a name", o.hant.isNotEmpty() && o.hans.isNotEmpty())
            assertNotNull("${o.id} has a stamp that exists", loaded.stamps[o.stamp])
        }
        // The 24 keep the keyboard's own days and take the stamps the table names for them.
        val costumed = loaded.occurrences.filter { it.id == "mid_autumn" }
        assertEquals(5, costumed.size)
        assertEquals("rabbit", costumed[0].stamp)
        assertEquals(wardrobe.themes.first { it.id == "mid_autumn" }.days[0].main.toEpochDay().toInt(), costumed[0].main)
        // Every stamp reads: any size up to sixteen, only the marks a stamp has.
        for ((name, rows) in loaded.stamps) {
            assertTrue(name, rows.size in 1..16 && rows[0].length in 1..16)
            assertTrue(name, rows.all { it.length == rows[0].length && it.all { c -> c in ".XRoGYBPOCM" } })
        }
    }

    @Test
    fun aFestivalCanComeSeveralTimesAYearAndSkipYearsItHasNoDataFor() {
        val loaded = WorldFestivals.load(daysJson, stampsJson, wardrobe)
        val friday = loaded.occurrences.filter { it.id == "friday_13" }
        if (friday.isNotEmpty()) assertTrue("more than one in a year", friday.groupBy { LocalDate.ofEpochDay(it.main.toLong()).year }.values.any { it.size > 1 })
        val krathong = loaded.occurrences.filter { it.id == "loy_krathong" }.map { LocalDate.ofEpochDay(it.main.toLong()).year }
        assertEquals("no day is made up for a year with no data", listOf(2026, 2027, 2028, 2029), krathong.sorted())
        assertTrue(loaded.occurrences.any { LocalDate.ofEpochDay(it.last.toLong()).year == 2031 })    // the end of 2030's Ramadan is in 2031
    }

    @Test
    fun anEntryThatDoesNotReadIsLeftOutAndTheRestStays() {
        val stamps = WorldFestivals.parseStamps(
            """{"ok":["X.X",".R."],"ragged":["XX","X"],"mark":["XZ"],"empty":[],"big":["${"X".repeat(17)}"],"bad id!":["X"],"number":5}""",
        )
        assertEquals(setOf("ok"), stamps.keys)
        assertTrue(WorldFestivals.parseStamps("not json").isEmpty())
        val (list, existing) = WorldFestivals.parseDays(
            """{"existingStamps":{"mid_autumn":"ok","christmas":"missing"},"festivals":[
              {"id":"good","names":{"hant":"好","hans":"好"},"stamp":"ok","drift":1,"days":[["2026-05-01","2026-05-02","2026-05-03"],["2026-12-30","2026-12-31","2027-01-01"]]},
              {"id":"bad id","names":{"hant":"x"},"days":[["2026-05-01","2026-05-01","2026-05-01"]]},
              {"id":"noname","names":{"hant":""},"days":[["2026-05-01","2026-05-01","2026-05-01"]]},
              {"id":"order","names":{"hant":"序"},"days":[["2026-05-03","2026-05-02","2026-05-01"],["2026-06-01","2026-06-01","2026-06-01"]]},
              {"id":"dates","names":{"hant":"日"},"days":[["2026-13-01","2026-01-01","2026-01-01"],["2026-02-30","2026-02-28","2026-02-28"],["2026-07-01"],"x"]},
              {"id":"nostamp","names":{"hant":"無"},"stamp":"nowhere","drift":99,"days":[["2026-07-07","2026-07-07","2026-07-07"]]},
              "junk", {"names":{}}]}""",
            stamps.keys,
        )
        assertEquals(listOf("good", "good", "order", "nostamp"), list.map { it.id })
        assertEquals("the second of a festival's entries stands alone, across the year's end", day(2027, 1, 1), list[1].last)
        assertEquals("好", list[0].hans)
        assertEquals(1, list[0].drift)
        assertNull("a stamp that is not there is no stamp", list[3].stamp)
        assertEquals(7, list[3].drift)                       // a drift beyond reason is held to a week
        assertEquals(mapOf("mid_autumn" to "ok"), existing)
        assertTrue(WorldFestivals.parseDays("[]", emptySet()).first.isEmpty())
    }

    @Test
    fun aDayTypedWithinTheDriftOfAFestivalMeetsItAndAQuietDayMeetsNothing() {
        val f = FestivalOccurrence("moon", "月", "月", day(2026, 9, 25), day(2026, 9, 25), day(2026, 9, 26), drift = 1, stamp = "moon")
        // The day before its first (within the drift) and the day after its last meet it; two days out do not.
        assertEquals(listOf("moon"), JourneyStats.of(ledgerOf(day(2026, 9, 24)), listOf(f)).festivals.map { it.id })
        assertEquals(listOf("moon"), JourneyStats.of(ledgerOf(day(2026, 9, 27)), listOf(f)).festivals.map { it.id })
        assertTrue(JourneyStats.of(ledgerOf(day(2026, 9, 23)), listOf(f)).festivals.isEmpty())
        assertTrue(JourneyStats.of(ledgerOf(day(2026, 9, 28)), listOf(f)).festivals.isEmpty())
        // A quiet day (month and day, any year) marks nothing: typing on it meets no festival, and no day carries a mark.
        val quiet = QuietDays(listOf(9 to 25))
        val onIt = JourneyStats.of(ledgerOf(day(2026, 9, 25)), listOf(f), quiet)
        assertTrue(onIt.festivals.isEmpty())
        assertNull(onIt.days.single().festival)
        val sameDayAnotherYear = JourneyStats.of(ledgerOf(day(2027, 9, 25)), listOf(FestivalOccurrence("moon", "月", "月", day(2027, 9, 25), day(2027, 9, 25), day(2027, 9, 25))), quiet)
        assertTrue(sameDayAnotherYear.festivals.isEmpty())
        // The festival's other days still meet it: only the quiet one is left out.
        val other = JourneyStats.of(ledgerOf(day(2026, 9, 25), day(2026, 9, 26)), listOf(f), quiet)
        assertEquals(day(2026, 9, 26), other.festivals.single().firstDay)
        // The shipped list is empty for now; nothing is quiet.
        assertFalse(QuietDays.SHIPPED.isQuiet(day(2026, 6, 4)))
        assertFalse(QuietDays.NONE.isQuiet(0))
    }

    @Test
    fun oneMarkADayAndItIsTheFestivalWithAPictureOfItsOwn() {
        val d = day(2026, 3, 3)
        val generic = FestivalOccurrence("generic", "通", "通", d, d, d, stamp = "gen_traditional_1")
        val own = FestivalOccurrence("own", "專", "專", d, d, d, stamp = "rabbit")
        val ownElsewhere = FestivalOccurrence("elsewhere", "他", "他", d - 1, d - 1, d + 1, stamp = "tangyuan")
        assertEquals("own", JourneyStats.mark(listOf(generic, own), d)!!.id)
        assertEquals("own", JourneyStats.mark(listOf(own, generic), d)!!.id)
        assertEquals("with two of their own, the one whose main day it is", "own", JourneyStats.mark(listOf(ownElsewhere, own), d)!!.id)
        assertEquals("with none of their own, the first listed", "generic", JourneyStats.mark(listOf(generic, FestivalOccurrence("g2", "二", "二", d, d, d, stamp = "gen_fun_2")), d)!!.id)
        assertNull(JourneyStats.mark(emptyList(), d))
        val stats = JourneyStats.of(ledgerOf(d), listOf(generic, own, ownElsewhere))
        assertEquals("own", stats.days.single().festival!!.id)
        assertEquals("all three are met, the day carries one", 3, stats.festivals.size)
    }

    @Test
    fun theFestivalOfTheDayStandsInTheSceneryUnlessTheDayIsQuiet() {
        val d = day(2026, 9, 25)
        val list = listOf(
            FestivalOccurrence("a", "甲", "甲", d, d, d + 1, drift = 2, stamp = "gen_fun_1"),
            FestivalOccurrence("b", "乙", "乙", d, d, d, stamp = "rabbit"),
        )
        assertEquals("b", JourneyStats.today(list, d)!!.id)
        assertEquals("a", JourneyStats.today(list, d + 1)!!.id)
        assertNull("the drift does not make a day the festival's", JourneyStats.today(list, d + 2))
        assertNull(JourneyStats.today(list, d, QuietDays(listOf(9 to 25))))
    }

    @Test
    fun theStampsSecondInksAreThePlacesInks() {
        val colors = GlyphColors.of(false)
        assertNull(Stamps.colour('.', colors, false))
        assertEquals(colors.red, Stamps.colour('R', colors, false))
        assertEquals(colors.ink, Stamps.colour('X', colors, false))
        assertEquals(colors.paper, Stamps.colour('o', colors, false))
        assertEquals(GlyphColors.of(true).ink, Stamps.colour('o', GlyphColors.of(true), true))            // a light at night is lit
        val inks = "GYBPOCM".map { Stamps.colour(it, colors, false) }
        assertEquals(7, inks.toSet().size)
        assertTrue(inks.none { it == colors.ink || it == colors.red })
        assertTrue("G Y B P O C M differ between day and night", "GYBPOCM".all { Stamps.colour(it, colors, false) != Stamps.colour(it, GlyphColors.of(true), true) })
        // A name the table does not have falls back to the gate.
        assertEquals(Stamps.rows("torii"), Stamps.ofFestival("nowhere", null))
        assertEquals(Stamps.rows("torii"), Stamps.ofFestival(null, null))
        assertEquals(listOf("X"), Stamps.ofFestival("one") { listOf("X") })
    }
}
