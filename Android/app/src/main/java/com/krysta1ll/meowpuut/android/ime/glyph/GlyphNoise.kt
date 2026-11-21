package com.krysta1ll.meowpuut.android.ime.glyph

import kotlin.math.floor

/**
 * Noise for the 字景 skin, anchored to whole numbers so the world never shimmers: the same cell of the world
 * always gets the same answer. Pure functions, no state (design-preview/specs/glyph-skin.template.html, "noise").
 */
internal object GlyphNoise {
    /** A hash of two whole numbers into [0, 1). */
    fun h2(x: Int, y: Int): Double {
        var n = x * 374761393 + y * 668265263
        n = (n xor (n ushr 13)) * 1274126177
        return ((n xor (n ushr 16)).toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0
    }

    /** One-dimensional value noise. */
    fun n1(x: Double, s: Int): Double {
        val fl = floor(x)
        val i = fl.toInt()
        val f = x - fl
        val u = f * f * (3 - 2 * f)
        return h2(i, s) * (1 - u) + h2(i + 1, s) * u
    }

    /** Three octaves of [n1]. */
    fun fbm1(x: Double, s: Int): Double = n1(x, s) * 0.58 + n1(x * 2.13, s + 7) * 0.28 + n1(x * 4.7, s + 13) * 0.14

    /** Two-dimensional value noise; [s] picks one of many independent fields. */
    fun n2(x: Double, y: Double, s: Int): Double {
        val fx = floor(x)
        val fy = floor(y)
        val xi = fx.toInt()
        val yi = fy.toInt()
        val xf = x - fx
        val yf = y - fy
        val u = xf * xf * (3 - 2 * xf)
        val v = yf * yf * (3 - 2 * yf)
        val a = h2(xi + s * 57, yi)
        val b = h2(xi + 1 + s * 57, yi)
        val c = h2(xi + s * 57, yi + 1)
        val d = h2(xi + 1 + s * 57, yi + 1)
        return (a * (1 - u) + b * u) * (1 - v) + (c * (1 - u) + d * u) * v
    }

    /** [y] snapped to a multiple of [step]. */
    fun q(y: Double, step: Double): Double = Math.floor(y / step + 0.5) * step

    /** JavaScript's Math.round: halves go up. */
    fun round(x: Double): Int = Math.floor(x + 0.5).toInt()

    /** A true modulo: never negative for a positive [m]. */
    fun mod(a: Double, m: Double): Double = ((a % m) + m) % m
    fun mod(a: Int, m: Int): Int = ((a % m) + m) % m
}
