package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.ime.glyph.GlyphColors
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphDepthFore
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphScenes
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlyphDepthForeTest {
    private data class R(val x: Double, val y: Double, val w: Double, val h: Double, val color: Int)

    private class Recorder : GlyphSink {
        val rects = ArrayList<R>()
        override fun rect(x: Double, y: Double, w: Double, h: Double, color: Int) { rects.add(R(x, y, w, h, color)) }
        override fun text(ch: String, cx: Double, cy: Double, size: Int, mono: Boolean, color: Int) {}
    }

    private val width = 360.0
    private val height = 300.0
    private val names = listOf("富士", "山野", "紅葉", "雪山", "竹林", "海", "街", "香港", "火山", "沙漠", "月", "月球")

    private fun state(name: String, dark: Boolean = false, still: Boolean = false, clock: Double = 1000.0, keyAt: Double = 900.0, gust: Boolean = true) =
        GlyphDepthFore.State(name, width, height, 0.0, dark, still, clock, keyAt, gust)

    private fun frame(s: GlyphDepthFore.State): List<R> {
        val r = Recorder()
        GlyphDepthFore.drawFrame(r, GlyphColors.of(s.dark), s)
        return r.rects
    }

    private fun fore(s: GlyphDepthFore.State, at: Double, top: Double = 0.0): List<R> {
        val r = Recorder()
        GlyphDepthFore.drawFore(r, GlyphColors.of(s.dark), s, at, top)
        return r.rects
    }

    @Test
    fun everyScenePlaceHasAFrameAndAFore() {
        for (n in names) {
            assertTrue("frame for $n", GlyphDepthFore.hasFrame(n))
            assertTrue("fore for $n", GlyphDepthFore.hasFore(n))
        }
        for (scene in GlyphScenes.ALL) {
            assertTrue("no frame or fore for ${scene.name}", GlyphDepthFore.hasFrame(scene.name) || GlyphDepthFore.hasFore(scene.name))
        }
    }

    @Test
    fun unknownNameDrawsNothing() {
        val s = state("nowhere")
        assertTrue(frame(s).isEmpty())
        assertTrue(fore(s, 100.0).isEmpty())
    }

    @Test
    fun frameStaysInItsCornerAndInsideTheKeyboard() {
        for (n in names) for (dark in listOf(false, true)) {
            val rects = frame(state(n, dark = dark))
            assertTrue("$n draws a frame", rects.isNotEmpty())
            for (r in rects) {
                assertTrue("$n x ${r.x}", r.x >= -0.001 && r.x + r.w <= 90.0)
                assertTrue("$n y ${r.y}", r.y >= -0.001 && r.y <= height + 12.0)   // the prototype: a snow bank's grain may stand a few points past the foot (the picture is clipped there)
            }
        }
    }

    @Test
    fun foreStaysInsideTheHorizontalMarginsAndNotAboveTheTop() {
        // The trunk's bough and its blossoms reach up to about 20 points above `top` in the prototype itself; the caller clips those.
        val trunks = setOf("富士", "山野", "紅葉", "雪山", "火山")
        for (n in names) for (dark in listOf(false, true)) {
            val s = state(n, dark = dark)
            var at = -90.0
            while (at <= width + 60) {
                val rects = fore(s, at)
                assertTrue("$n draws at $at", rects.isNotEmpty())
                val ceiling = if (n in trunks) -24.0 else -0.001
                for (r in rects) {
                    assertTrue("$n x ${r.x} at $at", r.x >= -135.0 && r.x + r.w <= width + 200.0)
                    assertTrue("$n y ${r.y} at $at", r.y >= ceiling)
                }
                at += 7.0
            }
        }
    }

    @Test
    fun foreOutOfThePictureDrawsNothing() {
        for (n in names) {
            val s = state(n)
            assertTrue(fore(s, -91.0).isEmpty())
            assertTrue(fore(s, width + 61).isEmpty())
        }
    }

    @Test
    fun stillMotionDoesNotFlicker() {
        for (n in names) for (dark in listOf(false, true)) {
            val a = state(n, dark = dark, still = true, clock = 1000.0, keyAt = 990.0)
            val b = state(n, dark = dark, still = true, clock = 123456.0, keyAt = 123000.0)
            assertEquals("$n frame", frame(a), frame(b))
            assertEquals("$n fore", fore(a, 120.0), fore(b, 120.0))
            assertEquals(0.0, GlyphDepthFore.sway(b), 0.0)
        }
    }

    @Test
    fun swayIsZeroWhenNotLiveAndSettles() {
        assertEquals(0.0, GlyphDepthFore.sway(state("山野", gust = false)), 0.0)
        assertEquals(0.0, GlyphDepthFore.sway(state("山野", clock = 10000.0, keyAt = 1000.0)), 0.0)
        assertTrue(kotlin.math.abs(GlyphDepthFore.sway(state("山野", clock = 1100.0, keyAt = 1000.0))) > 0.0)
    }

    @Test
    fun passesAtWrapsLikeThePrototype() {
        // lead 2400, speed 18, span 9000: at mileage 0 it sits at 2400 - 120; a full span of mileage later it is back.
        assertEquals(2280.0, GlyphDepthFore.passesAt(0.0, 0.0), 1e-9)
        assertEquals(2280.0, GlyphDepthFore.passesAt(9000.0 / 18.0, 0.0), 1e-9)
        assertEquals(2280.0 - 18.0, GlyphDepthFore.passesAt(1.0, 0.0), 1e-9)
        for (p in listOf(-1234.5, 0.0, 77.0, 100000.0)) {
            val v = GlyphDepthFore.passesAt(p, 3.0)
            assertTrue(v >= -120.0 && v < 9000.0 - 120.0)
        }
    }
}
