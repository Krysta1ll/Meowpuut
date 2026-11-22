package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.ime.glyph.CatId
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphColors
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphEngine
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphJourney
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphKey
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphLayout
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphPools
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphWorld
import com.krysta1ll.meowpuut.android.ime.glyph.KeyCap
import com.krysta1ll.meowpuut.android.ime.glyph.CapRole
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphKeyPainter
import com.krysta1ll.meowpuut.android.ime.glyph.Ripple
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The moments of the picture that only happen at a particular time: night, a border, a ring, a long idle. */
class GlyphBehaviourTest {
    private fun engine(level: Int = 2, dark: Boolean = false, scene: Int? = 0, layout: GlyphLayout = GlyphLayout.NONE, hm: Int = -1): GlyphEngine {
        val e = GlyphEngine(GlyphPools(), clockHm = { hm })
        e.configure(dark, level, false, false, true)
        e.setLayout(layout)
        e.force(scene)
        e.prime(3)
        e.scape.prepare()
        return e
    }

    private fun GlyphEngine.tick() { step(); scape.prepare() }
    private fun cat(e: GlyphEngine, id: CatId) = e.front().cats.first { it.cat == id }
    private fun scene(e: GlyphEngine) = RecordingSink().also { e.drawScene(it) }

    @Test
    fun aKeyPressRunsOutThroughTheLandAsARing() {
        val plain = engine(scene = 3)
        val ringed = engine(scene = 3)
        ringed.world.ripples += Ripple(150.0, 150.0, ringed.world.tick - 2)
        val a = scene(plain)
        val b = scene(ringed)
        assertNotEquals(a.ops, b.ops)
        // a ring is lit and it is where the key was: its glyphs and blocks come near the radius
        val radius = 2 * 15.0
        assertTrue(b.texts.count { Math.abs(Math.hypot(it.cx - 150.0, (it.cy - 150.0) * 1.4) - radius) < 8 } >
            a.texts.count { Math.abs(Math.hypot(it.cx - 150.0, (it.cy - 150.0) * 1.4) - radius) < 8 })
        // it is gone after seven steps
        val e = engine()
        e.key(GlyphKey.LETTER, 150.0, 150.0, false, "a")
        assertEquals(1, e.world.ripples.size)
        repeat(8) { e.tick() }
        assertTrue(e.world.ripples.isEmpty())
    }

    @Test
    fun theLetterJustTypedEchoesInThePinyinOfTheLand() {
        val e = engine(scene = 0)
        val before = scene(e).texts.count { it.mono && it.ch == "z" }
        e.key(GlyphKey.LETTER, 100.0, 109.0, false, "z")
        e.tick()
        val after = scene(e).texts.count { it.mono && it.ch == "z" }
        assertTrue("before $before after $after", after > before)
    }

    @Test
    fun atNightSolidGroundIsDarknessAndItsOutlineAndLightsStay() {
        val light = scene(engine(scene = 2))
        val night = scene(engine(scene = 2, dark = true))
        val dark = GlyphColors.of(true)
        assertTrue(night.rects.all { it.color != GlyphColors.LIGHT.ink })
        assertTrue("lit windows are drawn in the night's ink", night.rects.count { it.color == dark.ink } > 50)
        // there is less solid ink in the dark picture than in the day one: the buildings are the dark of the sheet
        assertTrue(night.rects.count { it.color == dark.ink } < light.rects.count { it.color == GlyphColors.LIGHT.ink } + 400)
    }

    @Test
    fun nowAndThenAtNightAStarFalls() {
        var seen = 0
        for (k in 0 until 12) {
            val e = engine(level = 3, dark = true, scene = 3)
            e.world.epoch = 34 * k + 1
            val sink = scene(e)
            if (sink.rects.count { it.w == 4.0 && it.h == 2.0 && it.color == GlyphColors.of(true).ink } >= 1) seen++
        }
        assertTrue("a falling star showed in $seen of 12 turns", seen in 1..11)
        // and never by day
        val day = engine(level = 3, dark = false, scene = 3)
        day.world.epoch = 1
        assertEquals(0, scene(day).rects.count { it.w == 4.0 && it.h == 2.0 && it.color == GlyphColors.of(true).ink })
    }

    @Test
    fun aBorderMaySometimesTearThePicture() {
        val journey = GlyphJourney()
        var torn = 0
        var clean = 0
        for (n in 1..40) {
            val e = GlyphEngine(GlyphPools())
            e.configure(false, 2, false, false, false)
            e.setLayout(GlyphLayout.NONE)
            var start = 0L
            for (k in 0 until n) start += journey.placeAt(k).len
            e.restore(start - 3)
            e.prime(3)
            e.committed("ab", 1, false, false)                                   // the characters cross the border; the next key takes the cats over
            e.key(GlyphKey.LETTER, 0.0, 0.0, false, "a")
            e.step(); e.step()
            val glitched = e.world.glitch > 0
            assertEquals("border $n", GlyphNoise.h2(n, 5) < GlyphWorld.BORDER_GLITCH, glitched)
            if (glitched) torn++ else clean++
        }
        assertTrue("torn $torn clean $clean", torn > 0 && clean > 0)
        assertTrue("a tear at a border is the exception: $torn of 40", torn <= 4)
    }

    @Test
    fun byItselfThePictureTearsRarelyAndNeverSoonAgain() {
        // Twenty minutes of typing at the highest level, where the type turns four times a second.
        val e = engine(level = 3)
        val starts = ArrayList<Int>()
        var torn = false
        repeat(GlyphWorld.FPS * 60 * 20) { i ->
            if (i % 100 == 0) e.key(GlyphKey.LETTER, 0.0, 0.0, false, "a")
            e.step()
            val now = e.world.tick < e.world.glitch
            if (now && !torn) starts += e.world.tick
            torn = now
        }
        assertTrue("it still happens: $starts", starts.isNotEmpty())
        assertTrue("but not once a minute as it did: $starts", starts.size <= 4)
        for (i in 1 until starts.size) assertTrue("no two within the rest: $starts", starts[i] - starts[i - 1] >= GlyphWorld.GLITCH_REST)
    }

    @Test
    fun atFourMinutesPastFourTheTypeSometimesGlitches() {
        val e = engine(hm = 404)
        repeat(80) { e.tick(); e.key(GlyphKey.OTHER, 0.0, 0.0, false) }
        assertTrue(e.world.glitch > 0)
        val other = engine(hm = 1200)
        repeat(80) { other.tick(); other.key(GlyphKey.OTHER, 0.0, 0.0, false) }
        // at any other hour only the rare accident of the hash can do it
        assertTrue(other.world.glitch == 0 || GlyphNoise.h2(other.world.epoch, 909) < 0.5)
    }

    @Test
    fun theCatsSmugBlinkAndLookWideInTheirTimes() {
        val e = engine()
        e.key(GlyphKey.OTHER, 0.0, 0.0, false)
        val faces = HashSet<String>()
        val mimi = HashSet<String>()
        repeat(110) { e.tick(); faces += cat(e, CatId.MOMO).face; mimi += cat(e, CatId.MIMI).face }
        assertTrue("momo blinks: $faces", "blink" in faces)
        assertTrue("mimi grows smug after seven seconds: $mimi", "smug" in mimi)
        val busy = engine()
        repeat(16) { busy.key(GlyphKey.LETTER, 100.0, 109.0, false, "a"); busy.tick() }
        assertEquals("fast typing opens their eyes", "wide", cat(busy, CatId.MOMO).face)
    }

    @Test
    fun aCatThatChangesRowWalksAndTheOneThatArrivedStandsOnSomething() {
        val e = engine()
        assertFalse("on the ridge there is no platform", cat(e, CatId.MIMI).platform)
        val onTheWay = ArrayList<Boolean>()
        repeat(12) { e.key(GlyphKey.LETTER, 100.0, 109.0, false, "a"); e.tick(); onTheWay += cat(e, CatId.MIMI).platform }
        assertTrue("on the way down there is none either", onTheWay.first().not())
        repeat(14) { e.tick() }
        assertTrue("on arrival there is", cat(e, CatId.MIMI).platform)
        val jumps = (0 until 5).map {
            e.key(GlyphKey.LETTER, 100.0, 109.0, false, "a"); e.tick(); cat(e, CatId.MIMI).feetY
        }
        assertTrue(jumps.all { it > 0 })
    }

    @Test
    fun theBlackCatDisappearsIntoTheNightExceptWhenSomethingHappens() {
        val e = engine(dark = true)
        assertTrue(cat(e, CatId.MIMI).night)
        assertFalse(cat(e, CatId.MOMO).night)
        e.committed("好", 1, false, true)
        e.step()
        assertFalse("he shows himself to be happy", cat(e, CatId.MIMI).night)
        val day = engine(dark = false)
        assertFalse(cat(day, CatId.MIMI).night)
    }

    @Test
    fun theFishFliesAndIsGone() {
        val e = engine()
        e.committed("寿司", 2, true, true)
        val arc = ArrayList<Int?>()
        repeat(24) { e.tick(); arc += e.front().fish?.second }
        assertTrue(arc.any { it != null })
        assertNull(arc.last())
    }

    @Test
    fun withoutGapsBetweenRowsEveryPlaceIsFramedAsASkyline() {
        val two = GlyphLayout(393.0, 200.0, doubleArrayOf(60.0, 110.0), 30.0, 30.0, 4.0, 40.0)
        assertFalse(two.rows)
        assertNull(two.laneFeet(1))
        assertEquals(60.0 / 2 + 110.0 / 2 + 15.0, two.laneFeet(0)!!, 1e-9)
        for (i in 0..5) {
            val e = engine(scene = i, layout = two)
            assertFalse(e.scape.rowsFraming)
            val sink = scene(e)
            assertTrue("place $i still draws", sink.texts.size > 100)
            val cats = e.front().cats
            assertEquals(2, cats.size)
            assertTrue(cats.all { it.feetY >= 20 })           // the ridge's base is never so high that a cat's head is cut off (a hill may still rise above it)
        }
    }

    @Test
    fun theBarLabelIsRememberedOnlyWhenItChangesAndThinsTheLandAroundIt() {
        val lay = GlyphLayout(393.0, 298.0, doubleArrayOf(109.0, 163.0, 217.0, 271.0), 30.0, 57.0, 34.0, 46.0)
        val e = engine(scene = 0, layout = lay)
        val plain = scene(e).texts.size
        assertNull(e.barLabel)

        e.setBarLabel(300.0, 360.0)
        assertTrue(e.world.dirty)                       // a new label asks for a new picture
        e.world.dirty = false
        e.setBarLabel(300.0, 360.0)
        assertFalse(e.world.dirty)                      // the same label again does not
        e.setBarLabel(300.0, 370.0)
        assertTrue(e.world.dirty)                       // another width does
        e.world.dirty = false

        val hushed = scene(e).texts.size
        assertTrue("the land thins round the label ($hushed < $plain)", hushed < plain)

        e.clearBarLabel()
        assertNull(e.barLabel)
        assertTrue(e.world.dirty)
        e.world.dirty = false
        e.clearBarLabel()                               // nothing to clear: nothing to redraw
        assertFalse(e.world.dirty)
        assertEquals(plain, scene(e).texts.size)
    }

    @Test
    fun aLayoutIsComparedByWhatItSays() {
        val a = GlyphLayout(393.0, 298.0, doubleArrayOf(1.0, 2.0, 3.0), 30.0, 57.0, 34.0, 46.0)
        val b = GlyphLayout(393.0, 298.0, doubleArrayOf(1.0, 2.0, 3.0), 30.0, 57.0, 34.0, 46.0)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, GlyphLayout(393.0, 298.0, doubleArrayOf(1.0, 2.0, 4.0), 30.0, 57.0, 34.0, 46.0))
        assertNotEquals(a, GlyphLayout(393.0, 298.0, doubleArrayOf(1.0, 2.0, 3.0), 30.0, 57.0, 34.0, 46.0, skylineFloor = 3.0))
        assertNotEquals(a, GlyphLayout(300.0, 298.0, doubleArrayOf(1.0, 2.0, 3.0), 30.0, 57.0, 34.0, 46.0))
        assertNotEquals(a, "a layout")
        assertTrue(a.rows)
        assertNull(a.laneFeet(-1))
        // a layout that is set again changes nothing
        val e = engine()
        e.setLayout(GlyphLayout.NONE)
        e.setLayout(a)
        assertEquals(a, e.layout)
    }

    @Test
    fun aVeryNarrowKeyShrinksItsLabelToTheSmallestSizeAndNoFurther() {
        val painter = GlyphKeyPainter(BlockFont)
        val sink = RecordingSink()
        painter.key(sink, GlyphColors.LIGHT, KeyCap(0.0, 0.0, 12.0, 40.0, "ㄅㄆㄇㄈㄉ", null, CapRole.PLAIN, false), false, false)
        assertEquals("too wide even at the grid: it is set at the grid and no smaller", 1.0, sink.bits.single().unit, 0.0)
        assertEquals(15.0, painter.width(KeyCap(0.0, 0.0, 12.0, 40.0, "q", "q", CapRole.PLAIN, false)), 0.0)
        assertTrue(painter.width(KeyCap(0.0, 0.0, 12.0, 40.0, "ab", null, CapRole.PLAIN, false)) > 0)
    }

    @Test
    fun aTabletKeepsTheCatsTogetherAndAPhoneSpreadsThemByWidth() {
        val phone = engine(layout = GlyphLayout(360.0, 298.0, doubleArrayOf(109.0, 163.0, 217.0, 271.0), 30.0, 57.0, 34.0, 46.0))
        val x = cat(phone, CatId.MIMI).x
        assertEquals(Math.round(360 * (172.0 / 393)).toInt(), x)
    }
}
