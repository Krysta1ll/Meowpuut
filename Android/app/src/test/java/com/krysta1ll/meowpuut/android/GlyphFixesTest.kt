package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.ime.glyph.CandidateCap
import com.krysta1ll.meowpuut.android.ime.glyph.CapRole
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphColors
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphEngine
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphKey
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphKeyArt
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphKeyPainter
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphLayout
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphPixelFont
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphPools
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphSink
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphSpectacle
import com.krysta1ll.meowpuut.android.ime.glyph.KeyCap
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphWorld
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the Android keyboard asks of the picture that the prototype never had to, and the effects that ship with it: only what could ship wrong. */
class GlyphFixesTest {
    /** The keyboard of the phone the screenshots come from: a bar 61 points tall over five rows of keys. */
    private fun layout(tail: Double = 0.0, horizon: Double = 69.95) =
        GlyphLayout(411.4, 294.1, doubleArrayOf(84.5, 131.0, 177.5, 224.0, 270.5), 27.0, 30.5, 6.4, 48.2,
            lift = 0.0, inset = 14.0, horizon = horizon, tail = tail)

    private fun engine(scene: Int, l: GlyphLayout, dark: Boolean = false, level: Int = 3, opaque: Boolean = false, still: Boolean = false): GlyphEngine {
        val e = GlyphEngine(GlyphPools(), clockHm = { -1 })
        e.configure(dark, level, still, opaque, false)
        e.setLayout(l)
        e.force(scene)
        e.prime(3)
        e.scape.prepare()
        return e
    }

    private fun draw(e: GlyphEngine) = RecordingSink().also { e.drawScene(it) }

    // ---- the horizon lies under the bar --------------------------------------------------------------

    @Test
    fun aPlaceOnTheSkylineStandsItsCatsUnderTheBarNotInIt() {
        for (scene in intArrayOf(1, 5)) {
            val cats = engine(scene, layout()).front().cats
            assertEquals(2, cats.size)
            for (c in cats) assertTrue("feet ${c.feetY} in place $scene", c.feetY in 68..74)
        }
        assertTrue("the old high ridge is still there for a layout that gives no horizon", engine(1, layout(horizon = 0.0)).front().cats.all { it.feetY < 60 })
    }

    // ---- the picture runs on under the navigation strip -----------------------------------------------

    @Test
    fun thePictureRunsOnToTheBottomOfTheStripAndTheSafeDrawingCoversItToo() {
        val s = draw(engine(0, layout(tail = 49.0)))
        val paper = s.rects.first()
        assertEquals(294.1 + 49.0, paper.y + paper.h, 1e-9)
        assertTrue("type goes on below the keyboard", s.texts.count { it.cy > 294.1 + 4 } > 20)
        assertTrue(draw(engine(0, layout())).texts.none { it.cy > 294.1 + 8 })
        val safe = draw(engine(0, layout(tail = 40.0), opaque = true)).rects.first()
        assertEquals(294.1 + 40.0, safe.y + safe.h, 1e-9)
    }

    // ---- the four tones are keys you can see ---------------------------------------------------------------

    private object NoFont : GlyphPixelFont {
        override fun mask(text: String, size: Int, weight: Int, mono: Boolean): GlyphKeyArt.Mask = error("not for $text")
    }

    @Test
    fun theTonesAreCutByHandAsLargeAsTheLettersAreTall() {
        val letterHeight = GlyphKeyArt.mask(GlyphKeyArt.bits("h")!!).h
        for (tone in listOf("ˇ", "ˋ", "ˊ", "˙")) {
            val bits = GlyphKeyArt.bits(tone)
            assertNotNull("tone $tone has pixels", bits)
            val m = GlyphKeyArt.mask(bits!!)
            assertTrue("$tone is ${m.w} wide and ${m.h} tall", m.w >= 4 && m.h >= 4 && m.h <= letterHeight)
        }
        val painter = GlyphKeyPainter(NoFont)
        val sink = RecordingSink()
        painter.key(sink, GlyphColors.LIGHT, KeyCap(0.0, 0.0, 40.0, 46.0, "ˇ", "ˇ", CapRole.PLAIN, pressed = false), composing = false, chips = false)
        assertEquals(1, sink.bits.size)
        assertEquals(GlyphKeyPainter.BITS_UNIT, sink.bits[0].unit, 0.0)
    }

    // ---- an emoji is brought down to the grid of the type, in its own colours ------------------------------------

    /** A font that sets a picture as pixels of its own colours (red here), as the platform's does for an emoji. */
    private object PictureFont : GlyphPixelFont {
        override fun mask(text: String, size: Int, weight: Int, mono: Boolean): GlyphKeyArt.Mask {
            val w = 15
            val on = BooleanArray(w * 14) { it % w in 1..12 && it / w in 1..12 }
            val red = IntArray(w * 14) { if (on[it]) 0xFFD03020.toInt() else 0 }
            return GlyphKeyArt.Mask(w, 14, on, red)
        }
    }

    private class PlainSink : GlyphSink {
        val rects = ArrayList<RecordingSink.Rect>()
        override fun rect(x: Double, y: Double, w: Double, h: Double, color: Int) { rects += RecordingSink.Rect(x, y, w, h, color) }
        override fun text(ch: String, cx: Double, cy: Double, size: Int, mono: Boolean, color: Int) = Unit
    }

    @Test
    fun anEmojiOnTheBarIsMadeOfTheSamePixelsAsTheTypeAndKeepsItsColours() {
        val painter = GlyphKeyPainter(PictureFont)
        val face = GlyphKeyPainter.CandidateFace(12, 2.0)
        for (selected in listOf(false, true)) {
            val sink = PlainSink()
            painter.candidate(sink, GlyphColors.LIGHT, CandidateCap(100.0, 60.0, "x", selected, japanese = false), face, 30.0, 34.0, chips = false)
            val own = sink.rects.filter { it.color == 0xFFD03020.toInt() }
            assertEquals("selected $selected: one block of two points for each pixel of the picture", 12 * 12, own.size)
            assertTrue(own.all { it.w == 2.0 && it.h == 2.0 })                // the world's grain, a whole number of times
        }
        // a mask without colours paints every pixel in the ink, as before
        assertEquals(7, GlyphKeyArt.Mask(2, 1, booleanArrayOf(true, true)).paint(0, 7))
        assertEquals(0xFFD03020.toInt(), PictureFont.mask("x", 12, 400, false).paint(16, 7))
    }

    // ---- when the effects must not play ------------------------------------------------------------------------

    private fun fire(e: GlyphEngine, times: Int) {
        e.world.rate = 14.0
        repeat(times) {
            e.key(GlyphKey.LETTER, 100.0, 100.0, composing = false, letter = "a")
            e.world.rate = 14.0
            e.step()
        }
        e.key(GlyphKey.RETURN, 100.0, 100.0, composing = false)
    }

    @Test
    fun theEffectsPlayOnlyInAMovingPictureAndNotAtTheLightestLevel() {
        for ((level, still, opaque) in listOf(Triple(0, false, false), Triple(3, true, false), Triple(3, false, true))) {
            val e = engine(1, layout(), level = level, still = still, opaque = opaque, dark = true)
            fire(e, 50)
            val sp = e.world.spectacle
            assertTrue("level $level still $still opaque $opaque: nothing plays", !sp.on && sp.excite == 0.0 && sp.stormAt < 0 && sp.burstAt < 0)
            assertTrue(sp.burst(true, 411.0, 70.0, "｜", "＊", "へ").isEmpty() && sp.storm(411.0, 70.0) { "あ" }.isEmpty() && !sp.busy())
            assertTrue(sp.dust(100.0, 70.0, 0).isEmpty() && !sp.hurrying)
        }
        // ...and from the standard level up they do: the world is excited, the cats run, the storm comes, a line sent sets a firework off
        val e = engine(1, layout(), dark = true)
        fire(e, 50)
        val sp = e.world.spectacle
        assertTrue(sp.on && sp.excite > 0.5 && sp.hurrying)
        assertTrue("the storm began after three seconds of it", sp.stormAt > 0)
        assertTrue(sp.burstAt > 0)
        assertTrue(sp.burst(true, 411.0, 70.0, "｜", "＊゜・゛☆", "へ").isNotEmpty())
    }

    @Test
    fun aFireworkIsOneAtATimeAndEndsByItself() {
        val e = engine(1, layout(), dark = true)
        val sp = e.world.spectacle
        sp.sent()
        val first = sp.burstAt
        e.world.tick += 3
        sp.sent()                                              // while one is in the sky, a second line does not make another
        assertEquals(first, sp.burstAt)
        e.world.tick += GlyphSpectacle.BURST + 5
        assertTrue(sp.burst(true, 411.0, 70.0, "｜", "＊", "へ").isEmpty())
        sp.sent()
        assertTrue(sp.burstAt > first)
        // by day it is a flock of birds
        val day = engine(1, layout())
        day.world.spectacle.sent()
        assertEquals(GlyphSpectacle.FLOCK, day.world.spectacle.burst(false, 411.0, 70.0, "｜", "＊", "へ").size)
    }

    @Test
    fun aBirdSettlesOnACandidateAndANewOneStartlesItOff() {
        val e = engine(1, layout())
        val sp = e.world.spectacle
        val edges = doubleArrayOf(24.0, 70.0, 100.0, 150.0, 170.0, 230.0)
        sp.candidates(11, edges, 20.0)
        assertTrue("nothing sits yet", sp.perched().isEmpty() || sp.perched().all { it.y < 16.0 })
        e.world.tick += GlyphSpectacle.SETTLE + 12
        val sitting = sp.perched()
        assertTrue(sitting.isNotEmpty() && sitting.size <= 2)
        assertTrue("on the top of the type", sitting.all { it.y == 16.0 && (edges.indices step 2).any { i -> it.x in edges[i]..edges[i + 1] } })
        sp.candidates(12, doubleArrayOf(24.0, 90.0), 20.0)       // other candidates: the sitting bird is put to flight
        e.world.tick += 1
        assertTrue("it is in the air, above the top of the type", sp.perched().any { it.y < 16.0 })
        // a bar with nothing on it has no birds once they have gone
        e.world.tick += 80
        sp.candidates(13, DoubleArray(0), 20.0)
        e.world.tick += 80
        assertTrue(sp.perched().isEmpty())
    }

    // ---- the cats' evening, and the twelve places ---------------------------------------------------------------

    @Test
    fun theCatsHaveAnEveningOfTheirOwn() {
        fun world(scene: Int = 0, dark: Boolean = false) = com.krysta1ll.meowpuut.android.ime.glyph.GlyphWorld(com.krysta1ll.meowpuut.android.ime.glyph.GlyphTheme(dark = dark, level = 2)).also { it.tick = 1000; it.scene = scene }
        val mimi = com.krysta1ll.meowpuut.android.ime.glyph.CatId.MIMI
        val momo = com.krysta1ll.meowpuut.android.ime.glyph.CatId.MOMO
        fun at(w: com.krysta1ll.meowpuut.android.ime.glyph.GlyphWorld, idle: Double, cat: com.krysta1ll.meowpuut.android.ime.glyph.CatId): String {
            w.lastMove = w.tick - (idle * 12).toInt() - 1
            return w.catState(cat).let { it.pose + "/" + it.face }
        }
        val w = world()
        assertTrue(at(w, 3.0, momo).startsWith("sit/"))
        assertEquals("she turns to watch the view before he does", "back/neutral" to true, at(w, 5.0, momo) to (at(w, 5.0, mimi).startsWith("sit/")))
        assertEquals("back/neutral", at(w, 7.0, mimi))
        assertEquals("loaf/smug", at(w, 10.0, mimi))
        assertEquals("loaf/yawn", at(w, 11.5, mimi))
        assertEquals("loaf/blink", at(w, 12.0, momo))
        assertTrue(at(w, 13.0, mimi).startsWith("sleep/") && at(w, 17.0, momo).startsWith("sleep/"))
        // at night the black cat is only his eyes and does not turn his back, unless he is in a suit on the Moon
        assertTrue(at(world(dark = true), 7.0, mimi).startsWith("sit/"))
        assertEquals("back/neutral", at(world(scene = 11, dark = true), 7.0, mimi))
        // a run of deletions: both are dizzy while the picture tears, then he is angry
        val d = world()
        repeat(GlyphWorld.BACKSPACE_RUN) { d.backspace(null); d.tick++ }
        assertEquals("sit/dizzy" to "sit/dizzy", at(d, 0.0, mimi) to at(d, 0.0, momo))
        d.tick += 6
        assertEquals("sit/angry", at(d, 0.0, mimi).let { if (it.endsWith("angry")) it else d.catState(mimi).let { s -> s.pose + "/" + s.face } })
        // a Japanese word: she has hearts in her eyes
        val j = world()
        j.committed("寿司", 2, true, false, 0, 30.0)
        assertEquals("love" to "sparkle", j.catState(momo).face to j.catState(mimi).face)
    }

    @Test
    fun theTwelvePlacesAreWholeAndTheSafeDrawingKeepsOnlyInkAndPaper() {
        val inks = com.krysta1ll.meowpuut.android.ime.glyph.GlyphScenes.HUES.keys
        for (scene in com.krysta1ll.meowpuut.android.ime.glyph.GlyphScenes.ALL) {
            val names = ArrayList<String?>()
            names += scene.tint.values.flatten()
            names += scene.sky.disc?.hue
            names += scene.sky.fall?.hue
            names += scene.sky.fall?.hues.orEmpty().filter { it.isNotEmpty() }
            for (l in scene.front + scene.back) names += l.hue
            for (n in names.filterNotNull()) assertTrue("${scene.name} names an ink that does not exist: $n", n in inks)
        }
        // the safe drawing is ink and paper alone, in every place
        for (i in com.krysta1ll.meowpuut.android.ime.glyph.GlyphScenes.ALL.indices) for (dark in listOf(false, true)) {
            val e = engine(i, layout(), dark = dark, opaque = true)
            val c = GlyphColors.of(dark)
            val s = draw(e)
            assertTrue("place $i dark $dark", s.rects.all { it.color == c.paper || it.color == c.ink } && s.texts.isEmpty())
        }
        // and with a suit on the Moon, the cats are a row taller and a column wider
        val plain = com.krysta1ll.meowpuut.android.ime.glyph.GlyphCats.raster(com.krysta1ll.meowpuut.android.ime.glyph.CatId.MIMI, "sit", "neutral", false, false, GlyphColors.LIGHT)
        val suited = com.krysta1ll.meowpuut.android.ime.glyph.GlyphCats.raster(com.krysta1ll.meowpuut.android.ime.glyph.CatId.MIMI, "sit", "neutral", false, false, GlyphColors.LIGHT, suit = true)
        assertEquals(plain.h + 1, suited.h)
        assertEquals(plain.w + 1, suited.w)
    }
}
