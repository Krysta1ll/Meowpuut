package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.app.AppSkins
import com.krysta1ll.meowpuut.android.app.EntryStyle
import com.krysta1ll.meowpuut.android.app.HeroSlot
import com.krysta1ll.meowpuut.android.app.PaperTheme
import com.krysta1ll.meowpuut.android.app.PaperTokens
import com.krysta1ll.meowpuut.android.app.PhotoStyle
import com.krysta1ll.meowpuut.android.app.StatsFrame
import com.krysta1ll.meowpuut.android.app.home.StatsPictures
import com.krysta1ll.meowpuut.android.app.stats.JourneyStats
import com.krysta1ll.meowpuut.android.app.stats.ManifestFestivals
import com.krysta1ll.meowpuut.android.app.stats.PetBookFile
import com.krysta1ll.meowpuut.android.app.stats.StatsLedger
import com.krysta1ll.meowpuut.android.ime.Festivals
import com.krysta1ll.meowpuut.android.ime.KeyboardSkin
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** The app pages' skin answers, the festival source, the ridge's shape, and the rules the statistics keep (no network, no skin checks in a page). */
class HomeStatsTest {
    private val main = "src/main/java/com/krysta1ll/meowpuut/android"

    @Test
    fun everySkinButGlyphLeavesThePagesAsTheyWereAndGlyphFillsEverySlot() {
        for (dark in listOf(false, true)) for (skin in KeyboardSkin.entries) {
            val answer = AppSkins.of(skin, PaperTheme.OCEAN, dark)
            if (skin == KeyboardSkin.GLYPH) {
                assertEquals(HeroSlot.SCENE, answer.slots.hero)
                assertEquals(EntryStyle.SIGNPOSTS, answer.slots.entries)
                assertEquals(PhotoStyle.TYPE_FIRST, answer.slots.photos)
                assertEquals(StatsFrame.GLYPH, answer.slots.statsFrame)
                assertTrue(answer.pixelType)
                assertEquals(0, answer.corner(18))
                // The picture at the top of the page is the keyboard's own sheet, so it runs into the page with no edge.
                val sheet = GlyphColors.of(dark)
                assertEquals(sheet.paper, answer.palette.paper)
                assertEquals(sheet.ink, answer.palette.ink)
                assertEquals(sheet.red, answer.palette.stamp)
            } else {
                // The page as it was: the palette the person chose, the corners each surface asked for, and one more way in.
                assertEquals(HeroSlot.MASTHEAD, answer.slots.hero)
                assertEquals(EntryStyle.CARDS, answer.slots.entries)
                assertEquals(PhotoStyle.PHOTO, answer.slots.photos)
                assertEquals(StatsFrame.SKIN, answer.slots.statsFrame)
                assertFalse(answer.pixelType)
                assertEquals(18, answer.corner(18))
                assertEquals(PaperTokens.palette(PaperTheme.OCEAN, dark), answer.palette)
            }
        }
    }

    @Test
    fun theSkinAnswersAreTheOnlyPlaceASkinIsComparedForThePages() {
        // The pages ask AppSkin for slots and variables; none of them says which keyboard skin it is. (SettingsActivity keeps the two
        // comparisons it always had, for which settings rows apply to which skin; the home page's own pieces take no part in them.)
        val pages = listOf("app/StatsActivity.kt", "app/CatPhotoWall.kt", "app/home/HomeParts.kt", "app/home/PageFrames.kt", "app/home/PixelText.kt", "app/home/GlyphLive.kt")
        val settings = File("$main/app/SettingsActivity.kt").readText()
        assertEquals("SettingsActivity compares skins only in refreshSkin, as before", 2, Regex("skin == KeyboardSkin.GLYPH").findAll(settings).count() + Regex("skin != KeyboardSkin.GLYPH").findAll(settings).count())
        for (name in pages) {
            val text = File("$main/$name").readText()
            assertFalse("$name compares a skin", text.contains("KeyboardSkin.GLYPH") || text.contains("isGlyph") || text.contains("SkinMaterial.GLYPH"))
        }
    }

    @Test
    fun theStatisticsAndTheirPicturesDoNotReachTheNetworkOrAnyone() {
        val files = File("$main/app/stats").listFiles()!!.toList() + File("$main/app/home").listFiles()!!.toList()
        assertTrue(files.size >= 11)
        for (file in files) {
            val text = file.readText()
            for (word in listOf("java.net", "HttpURLConnection", "okhttp", "ACTION_SEND", "firebase", "analytics", "Analytics", "WebView", "INTERNET")) {
                assertFalse("${file.name} mentions $word", text.contains(word))
            }
        }
        // The ledger holds numbers only: a record of what was typed would need a string field, and there is none but its fixed keys.
        val ledger = StatsLedger.empty()
        ledger.absorb(PetBookFile.parse(javaClass.classLoader!!.getResourceAsStream("petbook-golden.bin")!!.readBytes())!!)
        assertFalse(Regex("\"[^\"]+\":\"").containsMatchIn(ledger.toJson()))
    }

    @Test
    fun theShippedFestivalsBecomeOccurrencesOfTheirOwnDays() {
        val loaded = Festivals.parse(File("src/main/assets/festivals/festivals.json").readText())
        val list = ManifestFestivals.from(loaded)
        assertEquals(loaded.themes.sumOf { it.days.size }, list.size)
        assertTrue(list.isNotEmpty())
        for (f in list) assertTrue("${f.id} days out of order", f.first <= f.main && f.main <= f.last)
        val midAutumn = list.first { it.id == "mid_autumn" && it.main == LocalDate.of(2026, 9, 25).toEpochDay().toInt() }
        assertEquals("中秋", midAutumn.name(false))
        assertEquals("中秋", midAutumn.name(true))
    }

    @Test
    fun theRidgeIsAsHighAsItsHighestDayAndOnlyThat() {
        fun days(sizes: List<Int>) = sizes.mapIndexed { i, s -> JourneyStats.DayEntry(20_000 + i, s, 1, 1, null) }
        fun months(d: List<JourneyStats.DayEntry>) = d.groupBy { it.date.year * 12 + it.date.monthValue }.map { (_, v) ->
            JourneyStats.Month(v[0].date.year, v[0].date.monthValue, v.size, v.maxOf { it.size })
        }
        val low = days(List(40) { 0 })
        val high = days(List(40) { if (it == 17) 4 else 1 })
        val a = StatsPictures.Ridge(low, months(low))
        val b = StatsPictures.Ridge(high, months(high))
        assertTrue("a ridge of small days is lower", a.height < b.height)
        assertEquals(17, b.peak)
        assertEquals(b.ys.min(), b.ys[17], 0.0)
        // A bigger band is higher even through the jitter of a day (0.86 to 1.12 of its height): the bands are spaced for it.
        for (size in 2..4) assertTrue(StatsPictures.Ridge.HEIGHTS[size] * 0.86 > StatsPictures.Ridge.HEIGHTS[size - 1] * 1.12)
        assertTrue(a.base in 150.0..250.0 && b.base in 150.0..250.0)
        assertEquals(40 * StatsPictures.Ridge.COL_W + 140, b.width, 0.0)
        // Everything drawn is inside the picture: no summit above the top, and the sun is on it.
        assertTrue(b.ys.all { it >= 0 })
        assertTrue(b.sunX < b.width && b.sunY < b.height)
    }

    @Test
    fun theCatsPictureGrowsWithTheStrokesAndStopsGrowingAtItsLimit() {
        fun stats(pets: Long): JourneyStats {
            val ledger = StatsLedger.empty()
            // A history is not needed for the shape; the counters come from a file the core would write.
            val bytes = ByteArray(PetBookFile.STATE_BYTES)
            bytes[0] = 0x9C.toByte(); bytes[1] = 0xA7.toByte(); bytes[2] = 1
            for (i in 0 until 8) bytes[16 + i] = (pets shr (8 * i)).toByte()
            var hash = 0x811C9DC5L
            for (i in 0 until PetBookFile.STATE_BYTES - 4) { hash = hash xor (bytes[i].toLong() and 0xFF); hash = (hash * 0x01000193L) and 0xFFFFFFFFL }
            for (i in 0 until 4) bytes[PetBookFile.STATE_BYTES - 4 + i] = (hash shr (8 * i)).toByte()
            ledger.absorb(PetBookFile.parse(bytes)!!)
            return JourneyStats.of(ledger)
        }
        val few = StatsPictures.catsHeight(stats(4))
        val some = StatsPictures.catsHeight(stats(300))
        val many = StatsPictures.catsHeight(stats(100_000))
        val most = StatsPictures.catsHeight(stats(10_000_000))
        assertTrue(few < some)
        assertTrue(some < many)
        assertEquals(many, most, 0.0)
    }

    @Test
    fun theRoadOfFestivalsIsAsLongAsItsFestivalsAskAndNeverShorterThanThePage() {
        assertEquals(357.0, StatsPictures.roadWidth(0, 357.0), 0.0)
        assertEquals(357.0, StatsPictures.roadWidth(2, 357.0), 0.0)
        assertTrue(StatsPictures.roadWidth(300, 357.0) > StatsPictures.roadWidth(30, 357.0))
        assertTrue(StatsPictures.roadWidth(30, 357.0) > 357.0)
        assertTrue("the road is a strip, not a page", StatsPictures.ROAD_HEIGHT < 200.0)
    }
}
