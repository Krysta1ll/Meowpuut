package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.ime.glyph.CatId
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphColors
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphController
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphEngine
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphJourney
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphKey
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphLayout
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphPools
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphScenes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlyphEngineTest {
    private var clock = 0L
    private fun engine(level: Int = 2, still: Boolean = false, opaque: Boolean = false, dark: Boolean = false, crab: Boolean = false, scene: Int? = 0): GlyphEngine {
        val e = GlyphEngine(GlyphPools(), nowMs = { clock })
        e.configure(dark, level, still, opaque, crab)
        e.setLayout(GlyphLayout.NONE)
        e.force(scene)
        e.prime(3)
        e.scape.prepare()
        return e
    }

    /** One tick, as the screen takes it: the world steps, and the land is worked out again where it now lies. */
    private fun GlyphEngine.tick(): Boolean { val drew = step(); scape.prepare(); return drew }

    private fun GlyphEngine.runDown(limit: Int = 5000): Pair<Int, Int> {
        var steps = 0
        var draws = 0
        while (busy() && steps < limit) { if (tick()) draws++; steps++ }
        return steps to draws
    }

    private fun type(e: GlyphEngine, ch: String = "a") = e.key(GlyphKey.LETTER, 100.0, 109.0, false, ch)

    // ---- the clock: nothing changes, nothing is drawn --------------------------------------------------------

    @Test
    fun theClockStopsAboutTwentySecondsAfterTheLastKey() {
        val e = engine()
        type(e)
        val (steps, draws) = e.runDown()
        assertTrue("took $steps steps", steps in 240..290)
        assertTrue("drew $draws times", draws in 20..140)
        assertFalse(e.busy())
        assertFalse("a stopped world draws nothing", e.step())
        // a key wakes it again, and it runs down again
        type(e)
        assertTrue(e.busy())
        val (again, _) = e.runDown()
        assertTrue(again in 240..290)
    }

    @Test
    fun aStillPictureIsDrawnOnlyWhenSomethingHappens() {
        val e = engine(still = true)
        assertFalse("rested", e.runDown().first > 4)
        type(e)
        val (steps, draws) = e.runDown()
        assertTrue("steps $steps", steps <= 3)
        assertTrue("draws $draws", draws <= 2)
        // no scrolling: the land is where the key put it, at once
        assertEquals(e.world.target, e.world.pos)
        assertTrue(e.world.ripples.isEmpty())
    }

    @Test
    fun theFastestRateNeverExceedsTwelveFramesASecond() {
        assertTrue(1000.0 / GlyphController.FRAME_MS <= 12.0)
        assertTrue(1000.0 / GlyphController.FRAME_MS > 11.0)
    }

    @Test
    fun theLevelsSetHowMuchMoves() {
        val light = engine(level = 0)
        type(light)
        assertEquals("level 0: one redraw a key, the land is there at once", light.world.target, light.world.pos)
        assertTrue(light.world.ripples.isEmpty())
        val standard = engine(level = 1)
        type(standard)
        assertTrue("level 1: the land scrolls", standard.world.pos < standard.world.target)
        assertTrue(standard.world.ripples.isEmpty())
        val full = engine(level = 2)
        type(full)
        assertEquals("level 2: rings from the keys", 1, full.world.ripples.size)
        val before = full.world.epoch
        full.runDown(60)
        assertTrue("level 2: the type turns by itself", full.world.epoch > before + 3)
        val lightEpoch = light.world.epoch
        light.runDown(60)
        assertEquals("level 0: the type never turns by itself", lightEpoch, light.world.epoch)
    }

    @Test
    fun theHighLevelsSetTheTypeSmallerAndDenser() {
        val counts = (0..3).map { level ->
            val e = engine(level = level, scene = 3)
            val sink = RecordingSink()
            e.drawScene(sink)
            sink.texts.size
        }
        assertTrue("counts $counts", counts[0] < counts[1] && counts[1] < counts[2] && counts[2] < counts[3])
    }

    @Test
    fun theSafePictureIsOnePlainSheet() {
        val e = engine(opaque = true)
        val sink = RecordingSink()
        assertNull(e.drawScene(sink))
        assertTrue(sink.texts.isEmpty())
        val colors = GlyphColors.of(false)
        assertEquals(colors.paper, sink.rects.first().color)
        assertTrue(sink.rects.all { it.color == colors.paper || it.color == colors.ink })
        type(e)
        assertTrue("nothing moves", e.world.ripples.isEmpty())
        assertTrue(e.runDown().first <= 3)
        // the cats still stand on something: the ridge was worked out although nothing was painted from it
        val cat = e.front().cats.first()
        assertTrue("feet at ${cat.feetY}", cat.feetY > 60)
    }

    @Test
    fun theLandThinsRoundTheCandidates() {
        val e = engine()
        val bar = GlyphLayout.NONE.barMid
        fun glyphsInBar(): Int {
            val sink = RecordingSink()
            e.drawScene(sink)
            return sink.texts.count { Math.abs(it.cy - bar) < 14 } + sink.rects.count { Math.abs(it.y - bar) < 14 }
        }
        val without = glyphsInBar()
        e.setCandidates(true)
        val with = glyphsInBar()
        assertTrue("without $without, with $with", with < without)
    }

    @Test
    fun theSameWorldDrawsTheSamePicture() {
        val a = RecordingSink()
        val b = RecordingSink()
        engine().drawScene(a)
        engine().drawScene(b)
        assertEquals(a.ops, b.ops)
        assertTrue(a.layerEnds >= 4)
    }

    @Test
    fun everyPlaceDrawsSomethingAndTheSeaHasItsSun() {
        val light = GlyphColors.of(false)
        for (i in GlyphScenes.ALL.indices) {
            val sink = RecordingSink()
            engine(scene = i).drawScene(sink)
            assertTrue("place $i draws glyphs", sink.texts.size > 200)
            assertTrue("place $i draws blocks", sink.rects.size > 200)
        }
        val sea = RecordingSink()
        engine(scene = 1).drawScene(sea)
        val day = sea.rects.count { it.color == light.red }
        assertTrue("the sun is vermilion by day", day > 100)
        val night = RecordingSink()
        engine(scene = 1, dark = true).drawScene(night)
        val dark = night.rects.count { it.color == GlyphColors.of(true).red }
        assertTrue("and the moon is not, by night: $dark against $day", dark < day / 4)
    }

    @Test
    fun aGlitchTearsThePictureForUnderASecond() {
        val e = engine()
        assertNull(e.drawScene(RecordingSink()))
        e.world.glitch(22)
        val tear = e.drawScene(RecordingSink())
        assertNotNull(tear)
        assertEquals(4, tear!!.bands.size)
        e.runDown(40)
        e.world.glitch(0)
        // after the glitch the world is whole again
        repeat(30) { e.step() }
        assertNull(e.drawScene(RecordingSink()))
        // and the low levels never glitch
        val low = engine(level = 1)
        low.world.glitch(22)
        assertNull(low.drawScene(RecordingSink()))
        val calm = engine(still = true)
        calm.world.glitch(22)
        assertNull(calm.drawScene(RecordingSink()))
    }

    // ---- the cats ---------------------------------------------------------------------------------------------------

    private fun cat(e: GlyphEngine, id: CatId) = e.front().cats.first { it.cat == id }

    @Test
    fun theCatsStandOnTheGroundAndWalkWhileTheLandMoves() {
        val e = engine(scene = 2)
        val sit = cat(e, CatId.MOMO)
        assertTrue(sit.feetY > 0)
        assertEquals(sit.x, cat(e, CatId.MOMO).x)
        e.runDown(40)
        // a long while idle: both asleep
        e.runDown(400)
        assertEquals("sleep", cat(e, CatId.MOMO).pose)
        assertEquals("sleep", cat(e, CatId.MIMI).pose)
        assertTrue(cat(e, CatId.MIMI).zzz)
        assertFalse(cat(e, CatId.MOMO).zzz)
        type(e)
        e.tick()
        val poses = (0 until 6).map { e.tick(); cat(e, CatId.MIMI).pose }.toSet()
        assertTrue("walking: $poses", poses.any { it.startsWith("walk") })
    }

    @Test
    fun theCatsKeepApartAndFollowTheWidth() {
        val e = engine()
        val narrow = cat(e, CatId.MOMO).x to cat(e, CatId.MIMI).x
        assertEquals(104, narrow.first)
        assertEquals(172, narrow.second)
        val wide = GlyphEngine(GlyphPools())
        wide.configure(false, 2, false, false, false)
        wide.setLayout(GlyphLayout(1000.0, 298.0, doubleArrayOf(109.0, 163.0, 217.0, 271.0), 30.0, 57.0, 34.0, 46.0))
        wide.prime(3)
        assertTrue("a tablet keeps them together", wide.front().cats.first { it.cat == CatId.MIMI }.x < 300)
    }

    @Test
    fun miMimoChangesRowOnFastTypingAndGoesBackWhenItStops() {
        val e = engine()
        val ridge = cat(e, CatId.MIMI).feetY
        repeat(14) { type(e); e.tick() }
        repeat(13) { e.tick() }          // still under a second and a half since the last key: the typing is fast
        val low = cat(e, CatId.MIMI).feetY
        assertEquals("the lowest lane", Math.round(GlyphLayout.NONE.laneFeet(2)!!).toInt(), low)
        assertEquals("momo comes down one lane", Math.round(GlyphLayout.NONE.laneFeet(1)!!).toInt(), cat(e, CatId.MOMO).feetY)
        assertTrue(cat(e, CatId.MIMI).platform)
        assertTrue(low > ridge)
        repeat(100) { e.tick() }
        assertEquals("back on the ridge, wherever the land has got to", Math.round(e.scape.ridgeAt(e.world.catX(CatId.MIMI) + 20.0) + 2).toInt(), cat(e, CatId.MIMI).feetY)
        assertFalse(cat(e, CatId.MIMI).platform)
    }

    @Test
    fun theCatsKeepToTheRidgeWithoutMotionOrWithoutRows() {
        val calm = engine(level = 0)
        val start = cat(calm, CatId.MIMI).feetY
        repeat(14) { type(calm); calm.tick() }
        assertEquals(start, cat(calm, CatId.MIMI).feetY)
        // the sea is framed as a skyline: no gaps between rows to go down to
        val sea = engine(scene = 1)
        val ridge = cat(sea, CatId.MIMI).feetY
        repeat(14) { type(sea); sea.tick() }
        assertEquals(ridge, cat(sea, CatId.MIMI).feetY)
        assertNull(sea.world.lanes)
    }

    @Test
    fun theCatsMeetTheTypingWithFacesAndAHop() {
        val e = engine()
        e.committed("好", 1, false, secret = true)
        e.step()
        val happy = cat(e, CatId.MOMO)
        assertEquals("happy", happy.face)
        val hops = (0 until 6).map { e.step(); cat(e, CatId.MOMO).hopY }
        assertTrue("hops $hops", hops.any { it < 0 })
        assertEquals(0, hops.last())
        // backspace: 米米莫 looks aside
        e.key(GlyphKey.DELETE, 0.0, 0.0, false)
        e.step()
        assertEquals("left", cat(e, CatId.MIMI).face)
        // return with nothing composed: a meow; with a reading, the commit does it
        e.key(GlyphKey.RETURN, 0.0, 0.0, false)
        assertEquals("meow", e.world.mood!![CatId.MIMI])
    }

    @Test
    fun spaceOrReturnWhileComposingLeavesTheWorldToTheCommit() {
        val e = engine()
        val target = e.world.target
        e.key(GlyphKey.SPACE, 0.0, 0.0, composing = true)
        e.key(GlyphKey.RETURN, 0.0, 0.0, composing = true)
        assertEquals(target, e.world.target)
        e.key(GlyphKey.SPACE, 0.0, 0.0, composing = false)
        assertEquals(target + 1, e.world.target)
        e.key(GlyphKey.OTHER, 0.0, 0.0, false)
        assertEquals(target + 1, e.world.target)
        type(e)
        assertEquals(target + 4, e.world.target)
    }

    @Test
    fun fiveQuickBackspacesTearThePicture() {
        val e = engine()
        repeat(4) { e.key(GlyphKey.DELETE, 0.0, 0.0, false); e.step() }
        assertTrue(e.world.glitch <= e.world.tick)
        e.key(GlyphKey.DELETE, 0.0, 0.0, false)
        assertTrue(e.world.glitch > e.world.tick)
        val slow = engine()
        repeat(8) { slow.key(GlyphKey.DELETE, 0.0, 0.0, false); repeat(15) { slow.step() } }
        assertTrue("slow ones do not", slow.world.glitch <= slow.world.tick)
    }

    @Test
    fun theWordsThatDoSomethingWakeTheirThings() {
        val e = engine(crab = true)
        e.committed("claude", 6, false, secret = true)
        assertTrue(e.world.guest > e.world.tick)
        e.step()
        assertTrue("the crab is here", e.front().crabFrame >= 0)
        e.committed("晚安", 2, false, secret = true)
        assertTrue(e.world.goodnight > e.world.tick)
        repeat(40) { e.tick() }
        assertEquals("sleep", cat(e, CatId.MIMI).pose)
        e.committed("寿司", 2, true, secret = true)
        e.step(); e.step(); e.step()
        assertNotNull("the fish clears the bar", e.front().fish)
        e.committed("glitch", 6, false, secret = true)
        assertTrue(e.world.glitch > e.world.tick)
    }

    @Test
    fun theCrabNeedsTheSettingAndTheWordsNeedAFieldThatMayBeLearnedFrom() {
        val closed = engine(crab = false)
        closed.committed("claude", 6, false, secret = true)
        assertTrue(closed.world.guest <= closed.world.tick)
        assertEquals(-1, closed.front().crabFrame)
        val password = engine(crab = true)
        password.committed("claude", 6, false, secret = false)
        assertTrue("a password field is never read", password.world.guest <= password.world.tick)
        password.committed("晚安", 2, false, secret = false)
        assertTrue(password.world.goodnight <= password.world.tick)
    }

    @Test
    fun theCatsCanBeSwitchedOff() {
        val e = engine()
        e.showCats = false
        assertTrue(e.front().cats.isEmpty())
    }

    // ---- the journey and what is kept ------------------------------------------------------------------------------

    @Test
    fun theDistanceIsTheCountOfCommittedCharacters() {
        val e = engine()
        e.committed("你好", 2, false, true)
        e.committed("世界", 2, false, true)
        e.committed("x", -4, false, true)
        assertEquals(4L, e.distance)
        e.restore(1000)
        assertEquals(1000L, e.distance)
        e.restore(-5)
        assertEquals(0L, e.distance)
    }

    @Test
    fun theJourneyChangesPlaceWithTheDistanceAndTheNameShowsOnlyOnArriving() {
        val e = engine(scene = null)
        val journey = GlyphJourney()
        e.step()
        assertEquals(journey.placeAt(0).scene, e.world.scene)
        assertEquals("the first place is not announced", 0f, e.front().bannerAlpha, 0f)
        e.committed("x", journey.placeAt(0).len + 5, false, false)
        e.step()
        assertEquals(journey.placeAt(1).scene, e.world.scene)
        val banner = e.front()
        assertTrue(banner.bannerAlpha > 0.5f)
        assertEquals(GlyphScenes.ALL[journey.placeAt(1).scene].name, banner.bannerText)
        // with candidates on the bar the name keeps away
        e.setCandidates(true)
        assertEquals(0f, e.front().bannerAlpha, 0f)
        e.setCandidates(false)
        e.runDown(60)
        assertEquals(0f, e.front().bannerAlpha, 0f)
    }

    @Test
    fun aRestoredJourneyStartsWhereItStoppedWithoutAnnouncingIt() {
        val journey = GlyphJourney()
        val distance = journey.placeAt(0).len + journey.placeAt(1).len + 10L
        val e = engine(scene = null)
        e.restore(distance)
        e.step(); e.step()
        assertEquals(journey.placeAt(2).scene, e.world.scene)
        assertEquals(0f, e.front().bannerAlpha, 0f)
    }

    @Test
    fun theFogGathersAtABorderAndTheLandThinsIntoIt() {
        val e = engine(scene = null)
        val len = GlyphJourney().placeAt(0).len
        e.restore((len - 2).toLong())
        repeat(30) { e.step() }
        assertTrue("fog ${e.world.fog}", e.world.fog < 0.3)
        val thin = RecordingSink()
        e.drawScene(thin)
        val clear = RecordingSink()
        engine(scene = 0).drawScene(clear)
        assertTrue(thin.texts.size < clear.texts.size)
    }

    @Test
    fun whatWasTypedIsForgottenWhenTheKeyboardGoes() {
        val e = engine()
        type(e)
        e.committed("cla", 3, false, true)
        e.forget()
        assertTrue(e.world.ripples.isEmpty())
        assertTrue(e.world.motes.isEmpty())
        e.committed("ude", 3, false, true)
        assertTrue("the first half was dropped", e.world.guest <= e.world.tick)
    }

    @Test
    fun committedWordsRiseOffTheBarAtTheHigherLevelsOnly() {
        val e = engine(level = 2)
        e.committed("你好 世界吧嗎啊啊啊", 10, false, true)
        assertTrue(e.world.motes.size in 1..8)
        assertTrue(e.world.motes.none { it.ch.isBlank() })
        assertEquals(1, engine(level = 1).also { it.committed("好", 1, false, true) }.world.motes.size)
        assertEquals(0, engine(level = 0).also { it.committed("好", 1, false, true) }.world.motes.size)
        assertEquals(0, engine(still = true).also { it.committed("好", 1, false, true) }.world.motes.size)
        assertEquals(0, engine(opaque = true).also { it.committed("好", 1, false, true) }.world.motes.size)
        e.step()
        assertTrue(e.front().motes.isNotEmpty())
    }
}
