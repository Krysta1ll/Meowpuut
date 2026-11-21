package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The noise is the prototype's, number for number: the values below were read from the browser prototype. */
class GlyphNoiseTest {
    @Test
    fun matchesThePrototype() {
        assertEquals(0.09490608680061996, GlyphNoise.h2(3, 5), 1e-12)
        assertEquals(0.40548270219005644, GlyphNoise.h2(-7, 12), 1e-12)
        assertEquals(0.7660055821761489, GlyphNoise.h2(123456, -98765), 1e-12)
        assertEquals(0.0762297202311456, GlyphNoise.n1(2.3, 4), 1e-12)
        assertEquals(0.7895484256441705, GlyphNoise.n1(-5.75, 9), 1e-12)
        assertEquals(0.3472505347226242, GlyphNoise.fbm1(7.9, 9), 1e-12)
        assertEquals(0.6117115856031887, GlyphNoise.n2(1.5, 2.5, 3), 1e-12)
        assertEquals(0.26488235407121197, GlyphNoise.n2(-3.2, 40.7, 31), 1e-12)
        assertEquals(12.0, GlyphNoise.q(13.4, 4.0), 0.0)
        assertEquals(-2.0, GlyphNoise.q(-3.0, 2.0), 0.0)
    }

    @Test
    fun hashStaysInTheUnitInterval() {
        for (x in -50..50) for (y in -50..50) {
            val v = GlyphNoise.h2(x, y)
            assertTrue(v >= 0.0 && v < 1.0)
        }
    }

    @Test
    fun roundingAndModuloFollowJavaScriptAndMathNotTheOperator() {
        assertEquals(3, GlyphNoise.round(2.5))
        assertEquals(-2, GlyphNoise.round(-2.5))
        assertEquals(2.0, GlyphNoise.mod(-1.0, 3.0), 0.0)
        assertEquals(2, GlyphNoise.mod(-1, 3))
        assertEquals(1, GlyphNoise.mod(7, 3))
    }
}
