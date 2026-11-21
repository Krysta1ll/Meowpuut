package com.krysta1ll.meowpuut.android.ime.glyph

import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise.h2
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise.mod
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise.n1
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise.round
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * The 字景 skin's deep foreground: what is always in the nearest corner ([drawFrame]: reeds, a bank, snow, rock, a kerb and its lamp,
 * embers) and what passes in front of everything, faster than the land ([drawFore]: a trunk and its bough, bamboo, a mast or a
 * pole, a cactus, plumes, an antenna). A port of the prototype's FRAMES / FORES tables and drawFrame / drawFore
 * (design-preview/specs/glyph-scenes.template.html), pixel for pixel: the arithmetic and the rounding are the prototype's own.
 * Everything is drawn in points into a [GlyphSink]; nothing here knows the screen.
 */
internal object GlyphDepthFore {
    /**
     * What the prototype reads besides the scene: S.H / S.W ([height] / [width]), S.lift ([lift], not used by the drawing: the
     * caller passes `top` itself), theme.dark, theme.still, motion.clock ([clockMs]), motion.keyAt ([keyAtMs], same clock) and
     * `motion.fore != null` ([gust]: motion is live, so the reeds bend and the bough sways).
     */
    class State(
        val sceneName: String,
        val width: Double,
        val height: Double,
        val lift: Double,
        val dark: Boolean,
        val still: Boolean,
        val clockMs: Double,
        val keyAtMs: Double,
        val gust: Boolean,
    )

    /** What passes: [span] points of world per pass, [speed] points per unit of mileage, [lead] where it starts. */
    private const val PASS_SPAN = 9000.0
    private const val PASS_SPEED = 18.0
    private const val PASS_LEAD = 2400.0

    /** The pixel of the picture, in points (the prototype's FINE). */
    private const val FINE = 2.0

    /** The coarse pixel of the foreground, in points. */
    private const val U = 4.0

    private val NIGHT_BODY = 0xFF0B0A0A.toInt()

    private class Fore(
        val bloom: String? = null, val snow: Boolean = false, val thin: Boolean = false, val bare: Boolean = false,
        val bamboo: Boolean = false, val mast: Boolean = false, val pole: Boolean = false, val cactus: Boolean = false,
        val plumes: Boolean = false, val antenna: Boolean = false,
    )

    private class Frame(
        val reeds: Int = 0, val tip: String? = null, val rock: Boolean = false, val snow: Boolean = false,
        val wall: Boolean = false, val lamp: String? = null, val ember: String? = null,
    )

    private val FORES: Map<String, Fore> = mapOf(
        "富士" to Fore(bloom = "pink"), "山野" to Fore(bloom = "green"), "紅葉" to Fore(bloom = "orange"),
        "雪山" to Fore(bloom = null, snow = true), "竹林" to Fore(bamboo = true), "海" to Fore(mast = true),
        "街" to Fore(pole = true), "香港" to Fore(pole = true), "火山" to Fore(bloom = null, thin = true, bare = true),
        "沙漠" to Fore(cactus = true), "月" to Fore(plumes = true), "月球" to Fore(antenna = true),
    )

    private val FRAMES: Map<String, Frame> = mapOf(
        "山野" to Frame(reeds = 5, tip = "green"), "海" to Frame(rock = true), "街" to Frame(wall = true, lamp = "gold"),
        "竹林" to Frame(reeds = 4, tip = "green"), "雪山" to Frame(snow = true, reeds = 2), "月" to Frame(reeds = 5),
        "富士" to Frame(reeds = 5, tip = "green"), "香港" to Frame(wall = true, lamp = "magenta"),
        "火山" to Frame(rock = true, ember = "orange"), "沙漠" to Frame(rock = true, reeds = 3, tip = "ochre"),
        "紅葉" to Frame(reeds = 4, tip = "orange"), "月球" to Frame(rock = true),
    )

    /** The reeds: x, height, lean. */
    private val REEDS = listOf(
        Triple(6, 58, 0.22), Triple(18, 44, 0.3), Triple(30, 50, -0.12), Triple(46, 30, 0.4), Triple(60, 22, 0.1),
    )

    /** Plumes: dx, lean, height. */
    private val PLUMES = listOf(
        Triple(0, 0.1, 150), Triple(18, -0.06, 190), Triple(34, 0.16, 130), Triple(-16, 0.02, 170),
    )

    fun hasFrame(sceneName: String): Boolean = FRAMES.containsKey(sceneName)
    fun hasFore(sceneName: String): Boolean = FORES.containsKey(sceneName)

    /**
     * Where what passes is, in points from the keyboard's left edge (the prototype's foreAt without S): [pos] is the world's
     * mileage (or motion.fore when it is set) and [coast] the extra the passing thing keeps rolling by itself. It wraps every
     * 9000 points / 18 per unit and is only ever on the keyboard for a part of that.
     */
    fun passesAt(pos: Double, coast: Double): Double = mod(PASS_LEAD - (pos + coast) * PASS_SPEED, PASS_SPAN) - 120

    /** The bough's sway: it answers the typing and settles in four seconds; 0 when the motion is still or not live. */
    fun sway(s: State): Double =
        if (!s.gust || s.still) 0.0 else 2.5 * sin(s.clockMs / 700) * max(0.0, 1 - (s.clockMs - s.keyAtMs) / 4000)

    /** One of the place's inks: by day, by night. Null when there is no such ink. */
    private fun hue(name: String?, dark: Boolean): Int? =
        if (name == null) null else GlyphScenes.HUES[name]?.let { if (dark) it.second else it.first }

    private fun snap(v: Double, step: Double): Double = round(v / step) * step

    /** The corner that is always there, bottom left. Draws nothing for a place without one. */
    fun drawFrame(g: GlyphSink, c: GlyphColors, s: State) {
        val fr = FRAMES[s.sceneName] ?: return
        val u = U
        val body = if (s.dark) NIGHT_BODY else c.ink
        val base = s.height
        val tip = hue(fr.tip, s.dark)
        fun lit(x: Double, y: Double, w: Double) {
            if (s.dark) g.rect(x, y, w, 1.0, c.ink)
        }
        if (fr.wall) {                                         // a town: the kerb, a post, its lamp
            g.rect(0.0, base - 12, 72.0, 12.0, body); lit(0.0, base - 12, 72.0)
            g.rect(36.0, base - 44, 8.0, 32.0, body); lit(36.0, base - 44, 8.0)
            val lamp = hue(fr.lamp, s.dark) ?: c.red
            g.rect(34.0, base - 52, 12.0, 8.0, lamp); g.rect(32.0, base - 50, 16.0, 4.0, lamp)
            return
        }
        val f = FINE
        var x = 0
        while (x < 84) {
            val xd = x.toDouble()
            val smooth = 30 * Math.pow(1 - xd / 84, 1.3) + 5 * n1(xd * 0.07, 71)
            val h = snap(
                if (fr.rock) 36 * Math.pow(1 - xd / 84, 0.9) * (0.45 + 0.7 * h2(Math.floor(xd / 10).toInt(), 73)) + 3 * n1(xd * 0.3, 79) else smooth,
                f,
            )
            if (h > 0) {
                if (fr.snow) {
                    g.rect(xd, base - h, f, h, c.paper); g.rect(xd, base - h, f, 2.0, c.ink)
                    if (h2(x, 75) < 0.25) g.rect(xd, base - h + 8 + (h2(x, 77) * 3).toInt() * 4, 2.0, 2.0, c.ink)
                } else {
                    g.rect(xd, base - h, f, h, body); lit(xd, base - h, f)
                }
            }
            x += FINE.toInt()
        }
        if (fr.ember != null) {
            val col = hue(fr.ember, s.dark) ?: c.red
            listOf(14 to 44, 30 to 34, 8 to 58).forEachIndexed { n, (ex, ey) ->
                if (s.still || (s.clockMs / 500 + n).toInt() % 3 != 0) g.rect(ex.toDouble(), base - ey, u, u, col)
            }
        }
        // The reeds bend to the typing, and come back.
        val gust = if (!s.gust || s.still) 0.0 else max(0.0, 1 - (s.clockMs - s.keyAtMs) / 3000)
        REEDS.take(fr.reeds).forEachIndexed { n, (rx, tall, lean0) ->
            val lean = lean0 + gust * 0.22 * sin(s.clockMs / 260 + n * 1.7)
            var k = 0
            while (k < tall) {
                val col = if (k > tall - 12 && tip != null) tip else body
                g.rect(snap(rx + lean * k, f), base - 8 - k - f, if (k > tall * 0.6) 2.0 else 4.0, f, col)
                k += FINE.toInt()
            }
        }
    }

    /**
     * What passes in front, with its left edge at [at] (the caller adds [sway] to the passing position, as the prototype does) and
     * its upper edge at [top] (the prototype's -lift). Nothing when [at] is out of the picture. The trunk's bough and its blossoms
     * reach up to about 20 points above [top], and the plumes grow from the floor up to about 194 points: the caller clips.
     */
    fun drawFore(g: GlyphSink, c: GlyphColors, s: State, at: Double, top: Double) {
        val fo = FORES[s.sceneName] ?: return
        val dark = s.dark
        val u = U
        val h = s.height
        if (at < -90 || at > s.width + 60) return
        val q4 = { v: Double -> snap(v, u) }
        val bar = { x: Double, y: Double, w: Double, hh: Double, col: Int -> g.rect(q4(x), q4(y), w, hh, col) }
        val body = if (dark) NIGHT_BODY else c.ink
        if (fo.bamboo) {                                       // three stalks, each with its joints
            val green = hue("green", dark) ?: c.ink
            listOf(Triple(0.0, 14.0, body), Triple(34.0, 10.0, green), Triple(-22.0, 6.0, body)).forEachIndexed { n, (dx, w, col) ->
                var y = q4(top)
                while (y < h) {
                    val joint = ((y + n * 17) % 52) < u
                    val x = at + dx + (h - y) * 0.03 * (n - 1)
                    bar(x, y, w, u, if (joint) c.paper else col)
                    if (joint) bar(x - 2, y + u, w + 4, u, col)
                    else if (dark && col == body) bar(x, y, 2.0, u, c.ink)   // at night the dark stalks are darkness with one lit edge
                    y += u
                }
            }
            return
        }
        if (fo.mast || fo.pole) {                              // a post, and what hangs from it
            var y = q4(top)
            while (y < h) { bar(at, y, 8.0, u, body); if (dark) bar(at, y, 2.0, u, c.ink); y += u }
            val hang = if (dark) c.ink else body
            if (fo.mast) {
                for (k in 0 until 30) g.rect(round(at + 8 + k * 2).toDouble(), round((top + 30 + k * 2.8) / 2) * 2.0, 2.0, 2.0, hang)
                g.rect(round(at - 24).toDouble(), round(top + 26).toDouble(), 56.0, 2.0, hang)
            } else {
                bar(at - 28, top + 22, 64.0, u, hang)
                val gold = hue("gold", dark) ?: c.red
                for (k in 0 until 3) {
                    bar(at - 28 + k * 28, top + 26, 4.0, 8.0, hang)
                    val lx = q4(at - 30 + k * 28)
                    val ly = q4(top + 34)
                    g.rect(lx + 2, ly, 4.0, 8.0, gold); g.rect(lx, ly + 2, 8.0, 4.0, gold)
                }
            }
            return
        }
        if (fo.cactus) {                                       // a saguaro: one column and its two arms
            var y = q4(top + 24)
            while (y < h) { bar(at, y, 20.0, u, body); y += u }
            bar(at + 4, top + 16, 12.0, 8.0, body); bar(at + 2, top + 20, 16.0, 4.0, body)
            g.rect(q4(at - 28) + 2, q4(h * 0.52 - 44) - 2, 8.0, 2.0, body)        // rounded tops
            g.rect(q4(at + 36) + 2, q4(h * 0.36 - 32) - 2, 8.0, 2.0, body)
            for (k in 0 until 6) bar(at - 4 - k * 4, h * 0.52, u, 8.0, body)
            y = h * 0.52 - 44
            while (y < h * 0.52) { bar(at - 28, y, 12.0, u, body); y += u }
            for (k in 0 until 5) bar(at + 20 + k * 4, h * 0.36, u, 8.0, body)
            y = h * 0.36 - 32
            while (y < h * 0.36) { bar(at + 36, y, 12.0, u, body); y += u }
            if (dark) {
                y = q4(top + 24)
                while (y < h) { bar(at, y, 2.0, u, c.ink); y += u }
            }
            return
        }
        if (fo.plumes) {                                       // tall grass gone to seed, the heads catching the light
            PLUMES.forEachIndexed { n, (dx, lean, tall) ->
                var k = 0
                while (k < tall) {
                    val x = at + dx + lean * k
                    val y = h - k - u
                    val head = k > tall - 44
                    bar(x, y, u, u, body)
                    if (head && h2(n, k) < 0.7) {
                        bar(x + (if ((k and 4) != 0) u else -u), y, u, u, if (dark) c.ink else body)
                        if (dark) bar(x, y, u, u, c.ink)
                    }
                    k += u.toInt()
                }
            }
            return
        }
        if (fo.antenna) {                                      // a mast, its dish, the light on top of it
            var y = q4(top + 20)
            while (y < h) { bar(at, y, u, u, c.ink); y += u }
            listOf(0.3, 0.5, 0.7).forEach { f -> bar(at - 8, top + (h - top) * f, 20.0, u, c.ink) }
            for (k in 0 until 6) bar(at - 12 + k * 4, top + 36 + abs(k - 2.5) * 4, u, u, c.ink)
            if (s.still || (s.clockMs / 600).toInt() % 2 != 0) bar(at, top + 14, u, u, c.red)
            return
        }
        val fine = FINE
        var y = snap(top, fine)
        while (y < h) {                                        // the trunk: wider toward the ground, never quite straight
            val r = y / 4
            val f = (y - top) / (h - top)
            val w = snap((10 + 12 * f * f + 4 * n1(r * 0.2, 51)) * (if (fo.thin) 0.6 else 1.0), fine)
            val x = snap(at + (1 - f) * 14 + 8 * (n1(r * 0.09, 53) - 0.5), fine)
            g.rect(x, y, w, fine, body)
            if (dark) g.rect(x, y, 2.0, fine, c.ink)           // at night: darkness, with the edge the moon is on lit
            y += fine
        }
        val tint = if (fo.bloom != null) (hue(fo.bloom, dark) ?: c.red) else null
        for (k in 0 until 46) {                                // a bough near the top: it thins as it goes
            g.rect(
                snap(at + 20 + k * 2, fine), snap(top + 42 - k * 0.75 + 5 * n1(k * 0.16, 59), fine), 2.0,
                if (k < 14) 8.0 else if (k < 30) 6.0 else if (k < 40) 4.0 else 2.0, body,
            )
        }
        for (k in 0 until 11) {                                // ... and what is on it
            val bx = at + 22 + k * 8
            val by = top + 40 - k * 3 + 5 * n1(k * 8 * 0.08, 59)
            if (fo.snow && k % 2 != 0) bar(bx, by - u, 12.0, u, c.paper)
            if (fo.bare && k > 2 && k % 3 == 0) for (j in 1 until 5) bar(bx + j * 3, by - j * 6, u, 8.0, body)   // bare twigs
            if (tint != null && k > 1) for (j in 0 until 7) {  // small, and many: each a cross of five pixels, the middle one the paper's
                val fx = snap(bx + (h2(k, j + 61) - 0.3) * 30, fine)
                val fy = snap(by - 6 - h2(k, j + 63) * 22, fine)
                g.rect(fx - 2, fy, 6.0, 2.0, tint); g.rect(fx, fy - 2, 2.0, 6.0, tint)
                if (j % 3 == 0) g.rect(fx, fy, 2.0, 2.0, c.paper)
            }
        }
    }
}
