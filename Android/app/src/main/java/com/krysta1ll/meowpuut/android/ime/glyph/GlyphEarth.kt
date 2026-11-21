package com.krysta1ll.meowpuut.android.ime.glyph

import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise.n1
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise.n2
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The Earth, as it is from the Moon: the one thing in the whole picture in its own colours. It turns as the type turns. [u] and [v] are
 * the point on the disc in parts of its radius (-1..1).
 */
internal object GlyphEarth {
    private fun rgb(hex: Long): Int = (0xFF000000L or hex).toInt()
    // lit side, shaded side (the shading is the Earth's own: the terminator, not the keyboard's night)
    private val AIR = rgb(0x8FCBF4) to rgb(0x1A3559)
    private val CLOUD = rgb(0xFFFFFF) to rgb(0x8E9BB0)
    private val DESERT = rgb(0xC9A766) to rgb(0x6B5A3A)
    private val LAND = rgb(0x3F9A4E) to rgb(0x235233)
    private val SHALLOW = rgb(0x3C8BE0) to rgb(0x3C8BE0)
    private val DEEP = rgb(0x1F64B8) to rgb(0x102F5E)

    /** Every colour the Earth is painted in (the keyboard's type over it is cut out by finding these in the picture). */
    val PALETTE: IntArray = intArrayOf(AIR.first, AIR.second, CLOUD.first, CLOUD.second, DESERT.first, DESERT.second, LAND.first, LAND.second, SHALLOW.first, DEEP.first, DEEP.second)

    fun color(u: Double, v: Double, epoch: Int): Int {
        val spin = epoch * 0.012
        val rr = u * u + v * v
        val lon = asin(max(-1.0, min(1.0, u / sqrt(max(0.02, 1 - v * v))))) / 1.5708
        val shaded = u * 0.75 + v * 0.55 > 0.4
        fun pick(p: Pair<Int, Int>) = if (shaded) p.second else p.first
        if (rr > 0.86) return pick(AIR)                                           // the air, seen edge on
        val k = n2(lon * 1.5 + spin + 20, v * 2.3, 71) * 0.62 + n2(lon * 4.2 + spin * 2.6 + 9, v * 5.5, 72) * 0.38
        val cloud = n2(lon * 2.4 - spin * 1.7 + 4, v * 6.5, 73) * 0.7 + n2(lon * 7 - spin * 3 + 1, v * 3, 74) * 0.3
        if (abs(v) > 0.8 - 0.06 * n1(lon * 4 + spin, 3) || cloud > 0.63) return pick(CLOUD)
        if (k > 0.53) return if (n2(lon * 3 + spin * 2 + 50, v * 3, 75) > 0.58 && abs(v) < 0.5) pick(DESERT) else pick(LAND)
        return if (shaded) DEEP.second else if (k > 0.47) SHALLOW.first else DEEP.first
    }
}
