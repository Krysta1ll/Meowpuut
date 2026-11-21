package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.ime.glyph.CatId
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphColors
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphEngine
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphLayout
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphPools
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphScape
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphSeam
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphTheme
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphWorld
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The keyboard is called up and the picture is typed into being: seven ticks, from loose type to the whole place, the cats last. */
class GlyphArrivalTest {
    private val pools = GlyphPools()
    private val shipped = GlyphLayout(393.0, 298.0, doubleArrayOf(109.0, 163.0, 217.0, 271.0), 32.0, 57.0, 34.0, 46.0,
        skylineFloor = 0.0, lift = GlyphSeam.LIFT, inset = GlyphSeam.INSET)

    // ---- against the prototype ---------------------------------------------------------------------------------

    @Test
    fun theWholePictureAtEachMomentIsThePrototypes() {
        val reference = JSONArray(javaClass.getResourceAsStream("/glyph-arrival-parity.json")!!.bufferedReader().readText())
        for (i in 0 until reference.length()) {
            val e = reference.getJSONObject(i)
            val world = GlyphWorld(GlyphTheme(dark = e.getBoolean("dark"), level = 2))
            world.pos = 100; world.target = 100; world.epoch = 0; world.tick = 100
            world.formFix = e.getDouble("f")
            val scape = GlyphScape(world, pools)
            scape.wear(e.getInt("sc"), shipped)
            scape.hush(hasCandidates = false, enabled = true)
            val sink = RecordingSink()
            scape.draw(sink, GlyphColors.of(e.getBoolean("dark")))
            val where = "scene ${e.getInt("sc")} at ${e.getDouble("f")} dark ${e.getBoolean("dark")}"
            assertEquals("$where: formed", e.getDouble("f"), scape.formed, 0.0)
            assertEquals("$where: glyphs", e.getInt("nT").toDouble(), sink.texts.size.toDouble(), 2.0 + 0.004 * e.getInt("nT"))
            // (a cell that is wholly one layer's is covered by one block, not four: fewer calls, the same area)
            assertTrue("$where: blocks ${sink.rects.size} of ${e.getInt("nR")}", sink.rects.size <= e.getInt("nR") + 3)
            assertEquals("$where: erased", e.getInt("nE").toDouble(), sink.erases.size.toDouble(), 3.0 + 0.004 * e.getInt("nE"))
            assertEquals("$where: area", e.getDouble("aR"), sink.rects.sumOf { it.w * it.h }, 0.006 * e.getDouble("aR") + 30)
            val red = GlyphColors.of(e.getBoolean("dark")).red
            assertEquals("$where: vermilion", e.getInt("red").toDouble(), sink.rects.count { it.color == red }.toDouble(), 2.0 + 0.01 * e.getInt("red"))
            assertEquals("$where: glyph choice", e.getDouble("sCP"), sink.texts.sumOf { it.ch.codePointAt(0) % 1009 }.toDouble(), 0.01 * e.getDouble("sCP") + 40)
            assertEquals("$where: glyph x", e.getDouble("sX"), sink.texts.sumOf { Math.round(it.cx * 10) % 997 }.toDouble(), 0.01 * e.getDouble("sX") + 60)
            assertEquals("$where: glyph y", e.getDouble("sY"), sink.texts.sumOf { Math.round(it.cy * 10) % 991 }.toDouble(), 0.01 * e.getDouble("sY") + 60)
        }
    }

    // ---- the arrival --------------------------------------------------------------------------------------------------

    private fun engine(level: Int = 2, still: Boolean = false, scene: Int = 1): GlyphEngine {
        val e = GlyphEngine(GlyphPools())
        e.configure(false, level, still, false, false)
        e.setLayout(shipped)
        e.force(scene)
        e.prime(3)
        e.scape.prepare()
        return e
    }

    @Test
    fun itTakesSevenTicksAndThenIsWhole() {
        val e = engine()
        assertEquals("a keyboard that has been up is whole", 1.0, e.world.formed(), 0.0)
        e.arrive()
        val seen = ArrayList<Double>()
        repeat(9) { e.step(); seen += e.world.formed() }
        assertEquals((1..7).map { it / 7.0 } + listOf(1.0, 1.0), seen)
        assertTrue(seen.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(7, GlyphWorld.FORM)
        // asked again while arriving it is the same arrival; after it, a new one
        val g = engine()
        g.arrive(); repeat(3) { g.step() }
        val born = g.world.born
        g.arrive()
        assertEquals(born, g.world.born)
        repeat(8) { g.step() }
        g.arrive()
        assertNotEquals(born, g.world.born)
    }

    @Test
    fun theClockKeepsRunningThroughTheArrivalAndNoLongerThanNeeded() {
        val e = engine()
        e.runDownTicks()                                    // rest first
        assertFalse(e.busy())
        e.arrive()
        assertTrue(e.busy())
        var steps = 0
        while (e.busy() && steps < 400) { e.step(); steps++ }
        assertTrue("took $steps", steps in 8..10)          // a keyboard at rest is only kept going for the arrival itself
        // each of the arrival's own ticks wants drawing
        val f = engine()
        f.runDownTicks()
        f.arrive()
        assertTrue((0 until 8).all { f.step() })
    }

    private fun GlyphEngine.runDownTicks() { var n = 0; while (busy() && n++ < 600) { step(); scape.prepare() } }

    @Test
    fun notAtTheLowestLevelNorWithAStillPicture() {
        for (e in listOf(engine(level = 0), engine(still = true))) {
            e.arrive()
            repeat(3) { e.step() }
            assertEquals(1.0, e.world.formed(), 0.0)
        }
        val held = engine()
        held.world.formFix = 0.3
        assertEquals(0.3, held.world.formed(), 0.0)
    }

    @Test
    fun theCatsComeLastEyesFirst() {
        val e = engine()
        fun cats(f: Double): List<Pair<CatId, Boolean>> { e.world.formFix = f; e.scape.prepare(); return e.front().cats.map { it.cat to it.night } }
        assertEquals("none at first", emptyList<Pair<CatId, Boolean>>(), cats(0.0))
        assertEquals(emptyList<Pair<CatId, Boolean>>(), cats(0.44))
        assertEquals("then only their eyes", listOf(CatId.MOMO to true, CatId.MIMI to true), cats(0.45))
        assertEquals(listOf(CatId.MOMO to true, CatId.MIMI to true), cats(0.79))
        assertEquals("then the whole of them", listOf(CatId.MOMO to false, CatId.MIMI to false), cats(0.8))
        assertEquals(listOf(CatId.MOMO to false, CatId.MIMI to false), cats(1.0))
    }

    @Test
    fun thePlaceIsTypedFirstAndFormsAfter() {
        fun count(f: Double, scene: Int, test: (RecordingSink) -> Int): Int {
            val world = GlyphWorld(GlyphTheme(level = 2))
            world.pos = 100; world.target = 100; world.tick = 100; world.formFix = f
            val s = GlyphScape(world, pools)
            s.wear(scene, shipped)
            s.hush(false, true)
            val sink = RecordingSink()
            s.draw(sink, GlyphColors.LIGHT)
            return test(sink)
        }
        val ink = GlyphColors.LIGHT.ink
        val solid = { f: Double -> count(f, 2) { it.rects.count { r -> r.color == ink } } }
        val glyphs = { f: Double -> count(f, 2) { it.texts.size } }
        assertEquals("at the start nothing is typed yet", 0, glyphs(0.0))
        assertTrue("more is typed as it goes: ${glyphs(0.3)} ${glyphs(0.5)} ${glyphs(1.0)}", glyphs(0.3) > 0 && glyphs(0.5) > glyphs(0.3))
        // the heavy tones are still glyphs until the middle of the arrival, and close into blocks after
        assertEquals("no solid ground before the middle", solid(0.0), solid(0.5))
        assertTrue("blocks ${solid(0.5)} ${solid(0.75)} ${solid(1.0)}", solid(0.5) < solid(0.75) && solid(0.75) < solid(1.0))
        // the sun fills in from scattered dots (the red of the sea's sun, in blocks of three points)
        val red = GlyphColors.LIGHT.red
        val sun = { f: Double -> count(f, 1) { it.rects.count { r -> r.color == red && r.w == 3.0 } } }
        assertEquals(0, sun(0.25))
        assertTrue("${sun(0.3)} ${sun(0.6)} ${sun(1.0)}", sun(0.3) in 1 until sun(0.6) && sun(0.6) < sun(1.0))
    }

    @Test
    fun theEdgeAboveStartsLooseAndDrawsIn() {
        // a strip of twenty points over the app has room for the loose edge to show (the shipped seam's fourteen is nearly all taken by the band)
        val strip = GlyphLayout(393.0, 298.0, doubleArrayOf(109.0, 163.0, 217.0, 271.0), 32.0, 57.0, 34.0, 46.0, skylineFloor = 0.0, lift = 20.0)
        fun erased(f: Double, layout: GlyphLayout): List<RecordingSink.Erase> {
            val world = GlyphWorld(GlyphTheme(level = 2))
            world.pos = 100; world.target = 100; world.tick = 100; world.formFix = f
            val s = GlyphScape(world, pools)
            s.wear(0, layout)
            s.prepare()
            val sink = RecordingSink()
            s.drawSeam(sink, GlyphColors.LIGHT)
            return sink.erases
        }
        val loose = erased(0.0, strip)
        val drawn = erased(1.0, strip)
        assertNotEquals(loose.size, drawn.size)
        assertTrue("the loose edge reaches further down: ${loose.count { it.y > 0 }} against ${drawn.count { it.y > 0 }}", loose.count { it.y > 0 } != drawn.count { it.y > 0 })
        // and it has drawn in by the end
        val steps = listOf(0.0, 0.3, 0.6, 1.0).map { erased(it, strip).count { e -> e.y > 4 } }
        assertTrue("$steps", steps.first() != steps.last() && steps.last() == erased(1.0, strip).count { e -> e.y > 4 })
    }

    @Test
    fun theBlackCatsNightStaysAMatterOfTheDarkAndTheArrivalOnlyAddsToIt() {
        val e = GlyphEngine(GlyphPools())
        e.configure(true, 2, false, false, false)
        e.setLayout(shipped); e.force(1); e.prime(3)
        e.world.formFix = 1.0
        assertEquals(listOf(false, true), e.front().cats.map { it.night })
    }
}
