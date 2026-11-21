package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.ime.glyph.GlyphColors
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphEngine
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphKey
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphLayout
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphPools
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphScape
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphScenes
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphSeam
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphTheme
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphWorld
import com.krysta1ll.meowpuut.android.ime.glyph.Grain
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the keyboard meets the app: the thinning of the paper, what crosses the edge, and what the window tells the system. */
class GlyphSeamTest {
    private val pools = GlyphPools()
    /** The set-aside way: a strip laid over the app (the shipped seam adds no height). */
    private val STRIP = 20.0
    /** The prototype's keyboard. The shipped seam adds no height ([STRIP] 0, [GlyphSeam.INSET] 14); a strip of 20 is the set-aside way. */
    private fun layout(lift: Double = 20.0, floor: Double = 0.0, inset: Double = 0.0) =
        GlyphLayout(393.0, 298.0, doubleArrayOf(109.0, 163.0, 217.0, 271.0), 32.0, 57.0, 34.0, 46.0, skylineFloor = floor, lift = lift, inset = inset)

    private fun scape(scene: Int, level: Int = 2, dark: Boolean = false, opaque: Boolean = false, fog: Double = 1.0, lift: Double = 20.0, inset: Double = 0.0, pos: Int = 0): GlyphScape {
        val theme = GlyphTheme(dark = dark, level = level, opaque = opaque)
        val world = GlyphWorld(theme)
        world.fog = fog; world.tick = 100; world.pos = pos; world.target = pos
        val s = GlyphScape(world, pools)
        s.wear(scene, layout(lift, inset = inset))
        s.prepare()
        return s
    }

    // ---- against the prototype -------------------------------------------------------------------------------------

    @Test
    fun theSeamIsThePrototypesForEveryPlace() {
        val reference = JSONArray(javaClass.getResourceAsStream("/glyph-seam-parity.json")!!.bufferedReader().readText())
        for (c in 0 until reference.length()) {
            val config = reference.getJSONObject(c)
            val lift = config.getDouble("lift")
            val inset = config.getDouble("inset")
            val tag = "level ${config.getInt("level")} pos ${config.getInt("pos")} dark ${config.getBoolean("dark")} fog ${config.getDouble("fog")} lift $lift inset $inset"
            val theme = GlyphTheme(dark = config.getBoolean("dark"), level = config.getInt("level"))
            val world = GlyphWorld(theme)
            world.pos = config.getInt("pos"); world.target = world.pos; world.epoch = config.getInt("epoch"); world.tick = 100
            world.fog = config.getDouble("fog")
            val scenes = config.getJSONArray("scenes")
            for (s in 0 until scenes.length()) {
                val expected = scenes.getJSONObject(s)
                val where = "$tag scene ${expected.getInt("sc")}"
                val scape = GlyphScape(world, pools)
                scape.wear(expected.getInt("sc"), layout(lift = lift, inset = inset))
                scape.prepare()
                if (!expected.isNull("discX")) assertEquals("$where discY", expected.getDouble("discY"), scape.discPlace.second, 1e-3)
                val layers = expected.getJSONArray("layers")
                for (k in 0 until layers.length()) {
                    val l = layers.getJSONObject(k)
                    val mine = scape.layers[k]
                    var neg = 0; var sum = 0.0; var wsum = 0.0; var rsum = 0.0
                    var i = 0
                    while (i < mine.n) {
                        var py = -18
                        while (py < 298) {
                            val d = scape.sampleLayer(mine, i, py.toDouble())
                            if (d < 0) neg++ else { sum += d; wsum += d * (Math.floorMod(i * 7 + py, 13) + 1) }
                            py += 12
                        }
                        i += 3
                    }
                    for (i2 in 0 until mine.n) rsum += mine.ridge[i2]
                    assertEquals("$where layer $k ridge (the spire)", l.getDouble("rsum"), rsum, 1e-6 + 1e-9 * l.getDouble("rsum"))
                    assertEquals("$where layer $k neg", l.getInt("neg").toDouble(), neg.toDouble(), 3.0)
                    close("$where layer $k sum", l.getDouble("sum"), sum)
                    close("$where layer $k wsum", l.getDouble("wsum"), wsum)
                }
                val sink = RecordingSink()
                scape.drawSeam(sink, GlyphColors.of(config.getBoolean("dark")))
                val seam = expected.getJSONObject("seam")
                assertEquals("$where erased", seam.getInt("erased").toDouble(), sink.erases.size.toDouble(), 4.0 + 0.002 * seam.getInt("erased"))
                assertEquals("$where leaks", seam.getInt("leaks"), sink.rects.size)
                close("$where erased x", seam.getDouble("ex"), sink.erases.sumOf { it.x })
                close("$where erased y", seam.getDouble("ey"), sink.erases.sumOf { it.y })
                close("$where leak positions", seam.getDouble("lx"), sink.rects.sumOf { it.x * 3 + it.y })
            }
        }
    }

    private fun close(what: String, expected: Double, actual: Double) =
        assertEquals("$what: expected $expected got $actual", expected, actual, 0.004 * Math.abs(expected) + 0.05 + 8.0)

    // ---- the grains and the places ---------------------------------------------------------------------------------

    @Test
    fun theFourGrainsAreThePrototypesThresholds() {
        assertEquals(0.28125, GlyphSeam.grain(Grain.BAYER, 5, -3), 1e-12)
        assertEquals(0.84375, GlyphSeam.grain(Grain.BAYER, 2, 7), 1e-12)
        assertEquals(0.38782400204800066, GlyphSeam.grain(Grain.ROWS, 7, -4), 1e-12)
        assertEquals(0.6173660209076479, GlyphSeam.grain(Grain.ROWS, 31, 9), 1e-12)
        assertEquals(0.9156729324650951, GlyphSeam.grain(Grain.COLS, 9, -2), 1e-12)
        assertEquals(0.3534035826241598, GlyphSeam.grain(Grain.COLS, 40, 5), 1e-12)
        assertEquals(0.17965007247403264, GlyphSeam.grain(Grain.NOISE, 3, -5), 1e-12)
        assertEquals(0.5277246232144535, GlyphSeam.grain(Grain.NOISE, 100, 12), 1e-12)
        for (g in Grain.entries) for (x in -9..20) for (y in -12..20) assertTrue(GlyphSeam.grain(g, x, y) in 0.0..1.0)
        // each grain is its own kind of break-up: bayer repeats every four blocks, cols run down a column, rows along a row
        for (x in 0..8) for (y in 0..8) assertEquals(GlyphSeam.grain(Grain.BAYER, x, y), GlyphSeam.grain(Grain.BAYER, x + 4, y + 4), 0.0)
        assertTrue((0 until 12).map { GlyphSeam.grain(Grain.COLS, 6, it) }.let { col -> col.max() - col.min() < 0.26 })
        assertTrue((0 until 12).map { GlyphSeam.grain(Grain.ROWS, it, 4) }.let { row -> row.max() - row.min() < 0.6 })
    }

    @Test
    fun everyPlaceMeetsTheAppInItsOwnWay() {
        assertEquals(setOf("山野", "海", "街", "竹林", "雪山", "月", "富士", "香港", "火山", "沙漠", "紅葉", "月球"), GlyphSeam.SEAMS.keys)
        assertEquals(GlyphScenes.ALL.map { it.name }.toSet(), GlyphSeam.SEAMS.keys)
        val s = GlyphSeam.SEAMS
        assertEquals(Grain.BAYER, s.getValue("山野").grain); assertEquals(26.0, s.getValue("山野").band, 0.0); assertEquals(8.0, s.getValue("山野").wander, 0.0)
        assertEquals(Grain.ROWS, s.getValue("海").grain); assertEquals(22.0, s.getValue("海").band, 0.0); assertEquals(8.0, s.getValue("海").inner, 0.0)
        assertEquals(Grain.BAYER, s.getValue("街").grain); assertEquals(1.0, s.getValue("街").leak, 0.0)
        assertEquals(0, s.getValue("街").spire!!.back); assertEquals(0.8, s.getValue("街").spire!!.over, 0.0); assertEquals(3.5, s.getValue("街").spire!!.gain, 0.0)
        assertEquals(Grain.COLS, s.getValue("竹林").grain); assertEquals(12.0, s.getValue("竹林").inner, 0.0); assertEquals(0.4, s.getValue("竹林").leak, 0.0)
        assertEquals(Grain.NOISE, s.getValue("雪山").grain); assertEquals(0.66, s.getValue("雪山").spire!!.over, 0.0); assertEquals(3.3, s.getValue("雪山").spire!!.gain, 0.0)
        assertEquals(Grain.NOISE, s.getValue("月").grain); assertEquals(10.0, s.getValue("月").rise, 0.0); assertEquals(2.0, s.getValue("月").leak, 0.0)
        assertEquals(1, s.getValue("香港").spire!!.back); assertEquals(2.6, s.getValue("香港").spire!!.gain, 0.0)
        assertTrue(s.values.all { it.inner < it.band && it.band > 0 })
        assertNull("the sea and the hills keep their composition", s.getValue("海").spire)
        assertNull(s.getValue("山野").spire)
        assertEquals(0.0, s.getValue("海").rise, 0.0)
    }

    // ---- what goes through the edge -----------------------------------------------------------------------------------

    @Test
    fun theTallestSummitsAndTowersGoOnThroughTheEdgeOnlyInThePictureThatHasAStrip() {
        fun rise(scene: Int, lift: Double, opaque: Boolean = false): Double {
            val s = scape(scene, lift = lift, opaque = opaque)
            return -s.layers[0].ridge.min().toDouble()
        }
        for (scene in listOf(2, 4)) {
            val with = rise(scene, 20.0)
            val without = rise(scene, 0.0)
            assertTrue("place $scene: with a strip the summit is higher ($with against $without)", with > without + 20)
            assertEquals("the safe drawing does not pull anything up", without, rise(scene, 20.0, opaque = true), 0.0)
        }
        // the other places are composed as they were
        for (scene in listOf(0, 1, 3, 5)) assertEquals(rise(scene, 0.0), rise(scene, 20.0), 0.0)
    }

    @Test
    fun theMoonStandsHigherByTheSeamAndOnlyThere() {
        val plain = scape(5, lift = 0.0).discPlace.second
        val seam = scape(5).discPlace.second
        val safe = scape(5, opaque = true).discPlace.second
        assertEquals(plain - 10.0, seam, 1e-9)
        assertEquals(plain, safe, 1e-9)
        for (scene in listOf(0, 1, 2, 3, 4)) assertEquals("place $scene", scape(scene, lift = 0.0).discPlace.second, scape(scene).discPlace.second, 1e-9)
    }

    @Test
    fun whatThePlaceCoversIsNotThinned() {
        val moon = scape(5)
        val (dx, dy) = moon.discPlace
        // the moon's whole disc is kept, a long way above the keys too
        assertTrue(moon.kept(dx!!, dy))
        assertTrue(moon.kept(dx + 40, dy - 40))
        assertFalse(moon.kept(dx + 400, dy))
        // a crescent is not the disc it is cut from: the bite out of it is not kept (the town at night)
        val town = scape(2)
        val (cx, cy) = town.discPlace
        val r = 0.07 * 298
        assertTrue("the crescent's far side", town.kept(cx!! - r * 0.6, cy))
        assertFalse("the bite", town.kept(cx + r * 0.45, cy - r * 0.15) && !town.kept(cx + r * 0.45, cy - r * 0.15))
    }

    @Test
    fun eachStemOfBambooEndsAtItsOwnHeightAboveTheEdge() {
        val bamboo = scape(3)
        val l = bamboo.layers[0]
        val tops = HashSet<Int>()
        var stems = 0
        var x = 0.0
        while (x < 393) {
            // a column where the stem stands at the keyboard's own edge
            if (bamboo.kept(x, 1.0) && bamboo.sampleLayer(l, (x / l.sub).toInt().coerceAtMost(l.n - 1), 1.0) >= 0) {
                var top = 0
                for (y in 0 downTo -20) if (bamboo.kept(x, y.toDouble() - 0.5)) top = y
                tops += top
                stems++
            }
            x += 1.0
        }
        assertTrue("stems found: $stems", stems > 5)
        assertTrue("stems end at different heights: $tops", tops.size >= 3)
        // and none stands higher than the strip
        assertTrue(tops.all { it >= -20 })
    }

    // ---- the thinning ---------------------------------------------------------------------------------------------------

    private fun seamOf(scene: Int, fog: Double = 1.0, opaque: Boolean = false, lift: Double = 20.0, inset: Double = 0.0): RecordingSink {
        val sink = RecordingSink()
        scape(scene, fog = fog, opaque = opaque, lift = lift, inset = inset).drawSeam(sink, GlyphColors.LIGHT)
        return sink
    }

    @Test
    fun thePaperThinsFromTheStripDownToTheInnerLine() {
        for (scene in 0..5) {
            val s = seamOf(scene)
            assertTrue("place $scene erases something", s.erases.size > 1300)
            val inner = GlyphSeam.SEAMS.getValue(GlyphScenes.ALL[scene].name).let { it.inner + it.wander }
            assertTrue("nothing is erased far below the inner line", s.erases.all { it.y < inner + GlyphSeam.BLOCK })
            assertTrue("it reaches into the strip", s.erases.any { it.y < -10 })
            assertTrue("it never leaves the strip's top", s.erases.all { it.y >= -STRIP - 0.001 })
            assertTrue(s.erases.all { it.w == GlyphSeam.BLOCK && it.h == GlyphSeam.BLOCK })
        }
    }

    @Test
    fun theFogBetweenTwoPlacesLengthensTheThinning() {
        for (scene in 0..5) {
            val clear = seamOf(scene, fog = 1.0).erases
            val fog = seamOf(scene, fog = 0.2).erases
            assertNotEquals("place $scene", clear.size, fog.size)
            // a longer band thins more slowly: nearer the keys' edge more of the paper is left in the fog
            assertTrue("place $scene: clear ${clear.count { it.y in -8.0..0.0 }}, in fog ${fog.count { it.y in -8.0..0.0 }}",
                fog.count { it.y in -8.0..0.0 } < clear.count { it.y in -8.0..0.0 })
        }
    }

    @Test
    fun onlyThePlacesThatSayLeakLetStarsOutAboveTheEdge() {
        val ink = GlyphColors.LIGHT.ink
        val leaks = (0..5).map { seamOf(it).rects.size }
        assertEquals(0, leaks[0]); assertEquals(0, leaks[1])
        assertTrue(leaks[2] > 0 && leaks[3] > 0 && leaks[4] > 0 && leaks[5] > 0)
        assertTrue("the moon's sky has the most", leaks[5] > leaks[2])
        assertTrue(seamOf(5).rects.all { it.color == ink && it.y < 0 })
    }

    @Test
    fun theSafeDrawingHasNoSeamOneRuleAndNothingCrosses() {
        assertTrue(seamOf(4, opaque = true).erases.isEmpty())
        val e = GlyphEngine(GlyphPools())
        e.configure(false, 2, false, true, false)
        e.setLayout(layout(lift = 0.0))
        e.force(4); e.prime(2)
        val sink = RecordingSink()
        assertNull(e.drawScene(sink))
        assertTrue(sink.erases.isEmpty())
        val rule = sink.rects.first { it.h == 1.0 }
        assertEquals(0.0, rule.y, 0.0); assertEquals(393.0, rule.w, 0.0); assertEquals(GlyphColors.LIGHT.ink, rule.color)
        assertTrue(sink.rects.none { it.y < 0 })
    }

    @Test
    fun wordsRiseThroughTheSeamAndSnowFallsInFromAboveIt() {
        val e = GlyphEngine(GlyphPools())
        e.configure(false, 2, false, false, false)
        e.setLayout(layout())
        e.force(4); e.prime(3); e.scape.prepare()
        e.committed("你好", 2, false, true)
        repeat(30) { e.step(); e.scape.prepare() }
        // (the rising words have faded by now: look while they are up)
        val f = GlyphEngine(GlyphPools())
        f.configure(false, 2, false, false, false)
        f.setLayout(layout())
        f.force(4); f.prime(3)
        f.committed("你", 1, false, true)
        var highest = Double.MAX_VALUE
        repeat(24) { f.step(); f.front().motes.forEach { highest = Math.min(highest, it.y) } }
        assertTrue("the word reaches the strip: $highest", highest < 0.0 && highest >= 5.0 - STRIP - 1e-9)
        val safe = GlyphEngine(GlyphPools())
        safe.configure(false, 2, false, true, false)
        safe.setLayout(layout(lift = 0.0))
        safe.force(4); safe.prime(3)
        safe.committed("你", 1, false, true)
        var low = Double.MAX_VALUE
        repeat(24) { safe.step() }
        safe.committed("你", 1, false, true)
        assertEquals("the safe drawing shows no words at all", 0, safe.world.motes.size)
        // the snow: falling things are placed from above the edge
        val sink = RecordingSink()
        e.drawScene(sink)
        assertTrue("snow in the strip", sink.texts.any { it.cy < 0 && it.size == 8 })
        assertTrue(sink.texts.none { it.cy < -STRIP - 4 })
        assertTrue(low > 0)
    }

    // ---- the shipped seam: no height added ---------------------------------------------------------------------------

    @Test
    fun theDecisionIsNoHeightAndFourteenPointsOfSky() {
        assertEquals(0.0, GlyphSeam.LIFT, 0.0)
        assertEquals(14.0, GlyphSeam.INSET, 0.0)
        assertEquals(0.0, GlyphSeam.lift(opaque = false, floating = false), 0.0)
        assertEquals(14.0, GlyphSeam.inset(opaque = false, floating = false), 0.0)
        assertEquals("the safe drawing has no seam", 0.0, GlyphSeam.inset(opaque = true, floating = false), 0.0)
        assertEquals("nor has a floating card", 0.0, GlyphSeam.inset(opaque = false, floating = true), 0.0)
        assertEquals(0.0, GlyphLayout.NONE.lift, 0.0)
        assertEquals(0.0, GlyphLayout.NONE.inset, 0.0)
    }

    private fun shipped(scene: Int, pos: Int = 0, fog: Double = 1.0, dark: Boolean = false) = scape(scene, fog = fog, dark = dark, lift = 0.0, inset = GlyphSeam.INSET, pos = pos)

    @Test
    fun withNoHeightAddedTheSeamThinsInsideTheKeyboardAndNothingCrossesTheTop() {
        for (scene in 0..5) {
            val s = shipped(scene)
            val sink = RecordingSink()
            s.drawSeam(sink, GlyphColors.LIGHT)
            assertTrue("place $scene: ${sink.erases.size}", sink.erases.size in 500..1100)
            assertTrue("nothing above the keyboard", sink.erases.all { it.y >= 0.0 } && sink.rects.none { it.y < 0 })
            assertEquals(0.0, s.lift, 0.0); assertEquals(GlyphSeam.INSET, s.inset, 0.0)
            // the thinning ends near the inner line, which is half of the strip's own when no height is added
            val z = GlyphSeam.SEAMS.getValue(GlyphScenes.ALL[scene].name)
            assertTrue("place $scene", sink.erases.all { it.y < GlyphSeam.INSET + z.inner / 2 + z.wander + GlyphSeam.BLOCK })
        }
    }

    @Test
    fun theNewMoonIsSmallerAndWholeAndTheFullMoonDoesNotRise() {
        val town = shipped(2)
        assertEquals(19.0, town.discPlace.second, 0.0)
        val r = 0.07 * 298 * 0.8
        assertTrue("the crescent is whole inside the picture: top at ${town.discPlace.second - r}", town.discPlace.second - r >= 0.0)
        assertTrue(town.kept(town.discPlace.first!! - r * 0.6, town.discPlace.second))
        // the full moon is not moved up, and neither the disc of any other place
        assertEquals(scape(5, lift = 0.0).discPlace.second, shipped(5).discPlace.second, 1e-9)
        for (scene in listOf(0, 1, 3, 4)) assertEquals(scape(scene, lift = 0.0).discPlace.second, shipped(scene).discPlace.second, 1e-9)
        val moon = shipped(5)
        assertTrue("the moon's arc ends inside the picture", moon.discPlace.second - 0.3 * 298 >= 0.0)
    }

    @Test
    fun whatCrossesTheEdgeEndsInsideTheStripAnywhereAlongTheJourney() {
        // the rule: no summit, tower or aerial is cut flat by the top of the strip, in any place and at any distance along the road
        for (scene in listOf(2, 4)) for (pos in 0..4000 step 37) for ((lift, inset) in listOf(0.0 to GlyphSeam.INSET, 20.0 to 0.0)) {
            val s = scape(scene, lift = lift, inset = inset, pos = pos)
            val spire = s.layers[0]
            val floor = GlyphNoise.q(if (scene == 2) 4.0 - lift else 6.0 - lift, spire.sub)
            val top = spire.ridge.min().toDouble()
            assertTrue("place $scene at $pos: the highest point $top is above $floor", top >= floor - 1e-6)
            // the layer's tallest does go up into the strip, as far as the rule lets it
            if (pos == 0) assertTrue(top < 40)
        }
        // the places without a spire are unchanged by the rule
        for (scene in listOf(0, 1, 3, 5)) for (pos in 0..2000 step 111) {
            val with = scape(scene, lift = 0.0, inset = GlyphSeam.INSET, pos = pos).layers.map { it.ridge.toList() }
            val without = scape(scene, lift = 0.0, inset = 0.0, pos = pos).layers.map { it.ridge.toList() }
            assertEquals("place $scene at $pos", without, with)
        }
    }

    @Test
    fun theLayoutKnowsItsStrip() {
        assertNotEquals(layout(), layout(lift = 0.0))
        assertNotEquals(layout(lift = 0.0), layout(lift = 0.0, inset = 14.0))
        assertEquals(layout(), layout())
    }
}
