package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.ime.glyph.GlyphDepthFore
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphMotion
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphTheme
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphWorld
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp

/** 縱深 in motion: the planes' weights, the spring of what passes, and the rules that keep it out of the keyboard and keep the journey honest. */
class GlyphMotionTest {
    private val tau = doubleArrayOf(GlyphMotion.TAU_NEAR, GlyphMotion.TAU_MID, GlyphMotion.TAU_FAR, GlyphMotion.TAU_SKY)
    private val dt = 1.0 / 60

    private fun step(m: GlyphMotion, target: Double, epoch: Int = 0, still: Boolean = false, free: Boolean = true, width: Double = 393.0) =
        m.advance(dt, target, epoch, still, tau, free) { GlyphMotion.visible(it, width) }

    @Test
    fun everyPlaneFollowsTheTypingWithItsOwnWeight() {
        val m = GlyphMotion()
        step(m, 0.0)                                 // the first step puts every plane where the world is
        m.keyed()
        repeat(60) { step(m, 30.0) }                 // one second after a key that moved the world by 30
        // after a second a plane with time constant t has covered 1 - e^(-1/t) of the way
        for ((i, t) in tau.withIndex()) assertEquals("plane $i", 30 * (1 - exp(-1.0 / t)), m.run[i], 0.6)
        assertTrue(m.run[0] > m.run[1] && m.run[1] > m.run[2] && m.run[2] > m.run[3])       // near leads, sky trails
        assertTrue(m.near > m.far)                                                          // the ground at the feet goes ahead of the horizon
    }

    @Test
    fun aKeyIsNeverFollowedBackward() {
        val m = GlyphMotion()
        step(m, 0.0)
        var target = 0.0
        val last = DoubleArray(tau.size) { 0.0 }
        repeat(600) { n ->
            if (n % 17 == 0) { m.keyed(); target += 3.0 }                                    // the world only goes on
            step(m, target)
            for (i in last.indices) { assertTrue(m.run[i] >= last[i] - 1e-9); last[i] = m.run[i] }
        }
    }

    @Test
    fun whatPassesInFrontRunsPastAndComesBack() {
        val m = GlyphMotion()
        step(m, 0.0)
        m.keyed()
        var peak = 0.0
        repeat(120) { step(m, 20.0, free = false); peak = maxOf(peak, m.fore) }
        assertTrue("it overshoots: $peak", peak > 20.0)
        repeat(240) { step(m, 20.0, free = false) }
        assertEquals(20.0, m.fore, 0.01)
        assertEquals(0.0, m.foreV, 0.0)
    }

    @Test
    fun itNeverStopsInThePictureOnceTheTypingStops() {
        for (width in doubleArrayOf(360.0, 393.0, 430.0)) {
            val m = GlyphMotion()
            step(m, 0.0)
            var pos = 0.0
            while (!GlyphMotion.visible(GlyphDepthFore.passesAt(pos, 0.0), width)) pos += 0.5      // a mileage at which it stands in view
            step(m, pos)
            m.keyed()
            repeat(30) { step(m, pos, width = width) }
            var seconds = 0.0
            while (GlyphMotion.visible(GlyphDepthFore.passesAt(m.fore, m.coast), width) && seconds < 30) { step(m, pos, width = width); seconds += dt }
            assertTrue("width $width: out after $seconds s", seconds < 5.0)
            var back = false                                                                       // once out it does not come round again in a minute
            repeat(60 * 60) { step(m, pos, width = width); if (GlyphMotion.visible(GlyphDepthFore.passesAt(m.fore, m.coast), width)) back = true }
            assertFalse(back)
        }
    }

    @Test
    fun withReducedMotionNothingMovesAndEverythingIsWhereTheTypingPutIt() {
        val m = GlyphMotion()
        step(m, 0.0, still = true)
        m.keyed()
        step(m, 50.0, epoch = 9, still = true)                                              // one picture is wanted, where the typing has put things
        assertFalse(step(m, 50.0, epoch = 9, still = true))                                 // and then nothing is moving
        for (r in m.run) assertEquals(50.0, r, 0.0)
        assertEquals(50.0, m.near, 0.0); assertEquals(50.0, m.far, 0.0); assertEquals(50.0, m.fore, 0.0)
        assertEquals(9.0, m.epochF, 0.0)
        assertEquals(0.0, m.coast, 0.0)                                                      // nothing passes by itself
    }

    @Test
    fun theTurnOfTheTypeIsFollowedSmoothlyNotByWholeNumbers() {
        val m = GlyphMotion()
        step(m, 0.0, epoch = 0)
        var last = m.epochF
        var biggest = 0.0
        repeat(120) { n -> step(m, 0.0, epoch = if (n >= 10) 6 else 0); biggest = maxOf(biggest, abs(m.epochF - last)); last = m.epochF }
        assertTrue("no frame jumps a turn: $biggest", biggest < 0.7)
        assertEquals(6.0, m.epochF, 0.05)
    }

    @Test
    fun itComesToRestAndSaysSo() {
        val m = GlyphMotion()
        step(m, 0.0)
        m.keyed()
        var settledAt = -1
        for (n in 0 until 60 * 30) { if (!step(m, 6.0, free = false)) { settledAt = n; break } }
        assertTrue("rests some seconds after the last key, took $settledAt frames", settledAt in 1..(60 * 15))
    }

    @Test
    fun theJourneyCountsOnlyWhatWasCommittedWhateverTheKeysDoToThePicture() {
        val world = GlyphWorld(GlyphTheme(level = 2))
        val before = world.distance
        repeat(40) { world.letter("a", null) }                       // reading typed, not yet a word
        world.backspace(null); world.plain(null, enter = false); world.plain(null, enter = true); world.other(null)
        assertEquals(before, world.distance)                          // the journey did not move
        assertTrue(world.target > 0)                                  // the picture did
        world.committed("你好", 2, japanese = false, secret = false, skyOffset = 0, barY = 0.0)
        assertEquals(before + 2 * com.krysta1ll.meowpuut.android.ime.glyph.GlyphJourney.UNITS_PER_CHARACTER, world.distance)
        val target = world.target
        repeat(10) { world.step(com.krysta1ll.meowpuut.android.ime.glyph.GlyphLayout.NONE, true, true) { 150.0 } }
        assertTrue("the picture never goes back", world.target >= target)
        assertTrue(world.pos <= world.target)
    }
}
