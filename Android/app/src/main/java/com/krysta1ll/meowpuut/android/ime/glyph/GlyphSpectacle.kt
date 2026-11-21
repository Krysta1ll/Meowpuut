package com.krysta1ll.meowpuut.android.ime.glyph

import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise.h2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A grain of dust a running cat kicks up, in points on the front layer. */
internal class DustDraw(val ch: String, val x: Double, val y: Double, val alpha: Float, val size: Int = 8)

/** A glyph the world throws into the air: where it stands, how large its type is, and whether it is the red one. */
internal class FxGlyph(val ch: String, val x: Double, val y: Double, val size: Int, val red: Boolean = false)

/**
 * What the world does when it is excited, and what it does for a line sent: the wind that comes on harder the faster the person types,
 * the cats that run and kick up dust, the storm of type that sweeps the sky after a few seconds of it, a firework of type over the horizon
 * (by day a flock of birds put to flight), and the birds that settle on the candidates and are startled off by new ones.
 *
 * Nothing here is stored: each effect is a function of the tick it began at (and of the tick), and nothing typed goes in; only the speed
 * of typing is looked at, never what was typed. All of it needs the picture to move (not a still one, not the lowest levels, not the safe
 * drawing), and stops by itself.
 */
internal class GlyphSpectacle(private val world: GlyphWorld) {
    private val theme get() = world.theme

    /** How worked up the world is, 0..1, following the typing speed. */
    var excite = 0.0; private set
    private var fastTicks = 0
    var stormAt = NEVER; private set
    var burstAt = NEVER; private set
    private var burstX = 0.0
    private var hurryFrom = NEVER
    private var hurryLast = NEVER

    /** Whether any of this may happen: only a moving picture, and not at the lightest level of effects. */
    val on: Boolean get() = !theme.still && theme.level >= 1 && !theme.opaque

    /** The cats are running: the keys are coming fast. */
    val hurrying: Boolean get() = on && world.tick - hurryLast <= 1

    // ---- the clock -----------------------------------------------------------------------------------------

    /** Debug: one effect held at one moment, as "storm:6", "burst:5" or "run". */
    private var held: String? = null
    fun hold(what: String?) { held = what }

    private fun applyHold() {
        val h = held ?: return
        val age = h.substringAfter(':', "0").toIntOrNull() ?: 0
        when (h.substringBefore(':')) {
            "storm" -> stormAt = world.tick - age
            "burst" -> { if (world.tick - burstAt != age) { burstX = world.catSpace * 0.5 }; burstAt = world.tick - age }
            "run" -> { excite = 1.0; hurryFrom = world.tick - DUST_LIFE; hurryLast = world.tick }
        }
    }

    /** One tick. True while something is on the move, so the picture is drawn again. */
    fun step(): Boolean {
        if (!on) { reset(); return false }
        applyHold()
        if (held != null) return true
        val idle = world.idleSeconds
        val want = if (idle < 1.5) ((world.rate - CALM) / (FRANTIC - CALM)).coerceIn(0.0, 1.0) else 0.0
        excite += (want - excite) * 0.3
        if (abs(excite - want) < 0.01) excite = want
        if (idle < 1.0 && world.rate > HURRY) {
            if (hurryFrom == NEVER || world.tick - hurryLast > DUST_LIFE) hurryFrom = world.tick
            hurryLast = world.tick
            fastTicks++
        } else fastTicks = max(0, fastTicks - 2)
        if (fastTicks >= STORM_AFTER && world.tick - stormAt > STORM_GAP) { stormAt = world.tick; fastTicks = 0 }
        return busy()
    }

    fun busy(): Boolean = on && (held != null || excite > 0.0 || world.tick - stormAt <= STORM + 1 || world.tick - burstAt <= BURST + 1 ||
        world.tick - hurryLast <= DUST_LIFE + 1 || birdsBusy())

    fun reset() {
        excite = 0.0; fastTicks = 0; stormAt = NEVER; burstAt = NEVER; hurryFrom = NEVER; hurryLast = NEVER
        for (b in birds) { b.landAt = NEVER; b.flushAt = NEVER }
        signature = 0; spans = DoubleArray(0)
    }

    /** A line was sent (Return with nothing being composed): at night a firework of type, by day a flock put to flight. One at a time. */
    fun sent() {
        if (!on || world.tick - burstAt <= BURST + 4) return
        burstAt = world.tick
        burstX = world.catSpace * (0.22 + 0.56 * h2(world.tick, 31))
    }

    // ---- the storm -------------------------------------------------------------------------------------------

    /**
     * The type that sweeps the sky left to right in [STORM] ticks, as glyphs of [glyphOf] (a different one for each): big and small, now and
     * then a red one, each trailing a streak of dashes behind it, so the wind is seen as it blows.
     */
    fun storm(width: Double, skyBottom: Double, glyphOf: (Int) -> String): List<FxGlyph> {
        val age = world.tick - stormAt
        if (!on || age < 0 || age > STORM) return emptyList()
        val out = ArrayList<FxGlyph>(STORM_COUNT * 2)
        for (k in 0 until STORM_COUNT) {
            val speed = 26 + 30 * h2(k, 1)
            val x = -70 - 230 * h2(k, 2) + age * speed
            if (x < -30 || x > width + 40) continue
            val lane = 2 + (skyBottom - 4) * h2(k, 3)
            val y = lane + sin(age * 0.9 + k) * (3 + 5 * h2(k, 4))
            val big = h2(k, 5)
            val size = if (big < 0.5) 12 else if (big < 0.85) 16 else 22
            val red = h2(k, 6) < 0.12
            out += FxGlyph(glyphOf(k), x, y, size, red)
            if (size >= 16) { out += FxGlyph("ー", x - size * 0.9, y, 8, red); out += FxGlyph("ー", x - size * 1.8, y, 8) }
        }
        return out
    }

    // ---- a line sent ------------------------------------------------------------------------------------------

    /**
     * The firework (when [night]) or the flock of birds. [ground] is the ridge's height, [width] the keyboard's. [shell] and [spark] are
     * the glyphs a burst is made of.
     */
    fun burst(night: Boolean, width: Double, ground: Double, shell: String, spark: String, flock: String): List<FxGlyph> {
        val age = world.tick - burstAt
        if (!on || age < 0 || age > BURST) return emptyList()
        val x0 = min(width - 40, max(40.0, burstX))
        val out = ArrayList<FxGlyph>(26)
        if (night) {
            val cy = max(42.0, ground - 36)
            if (age < ROCKET) {
                val run = (ground - cy) * (age + 1) / ROCKET
                out += FxGlyph(shell, x0, ground - run, 14)
                if (age > 0) { out += FxGlyph("・", x0 + (h2(age, 6) - 0.5) * 3, ground - run + 12, 10); out += FxGlyph("゜", x0, ground - run + 22, 8) }
            } else {
                val t = age - ROCKET
                if (t < 2) {                                                 // the flash
                    out += FxGlyph(spark[0].toString(), x0, cy, 30, red = true)
                    for (d in 0 until 8) out += FxGlyph("ー", x0 + cos(d * Math.PI / 4) * (12 + 10 * t), cy + sin(d * Math.PI / 4) * (12 + 10 * t), 12)
                }
                // two shells, one inside the other: the big ring of red and ink, the small one that follows it
                for (ring in 0 until 2) {
                    val n = if (ring == 0) 34 else 16
                    val tt = t - ring * 2
                    if (tt < 0) continue
                    for (k in 0 until n) {
                        val a = 2 * Math.PI * k / n + h2(burstAt, k + ring * 50) * 0.25
                        val reach = (if (ring == 0) 7 else 4) + tt * (if (ring == 0) 8.5 else 6.0) * (0.8 + 0.4 * h2(k, 7))
                        val fall = 0.42 * tt * tt
                        val px = x0 + cos(a) * reach
                        val py = cy + sin(a) * reach * 0.9 + fall
                        if (tt > 5 && h2(k, age + ring) > 0.62) continue            // the sparks gutter out
                        val red = (k + ring) % 3 == 0
                        out += FxGlyph(spark[(k + tt) % spark.length].toString(), px, py, if (tt > 5) 12 else 16, red)
                        if (tt in 1..6) out += FxGlyph("・", x0 + cos(a) * (reach - 8), cy + sin(a) * (reach - 8) * 0.9 + fall * 0.7, 8, red)    // its trail
                    }
                }
            }
        } else {
            // the flock goes up from the ground all at once, fanning out, and is off the top edge in a dozen ticks
            for (k in 0 until FLOCK) {
                val dx = (h2(k, 8) - 0.5) * 9.0
                val rise = 9.0 + 5.0 * h2(k, 9)
                val bx = x0 + (h2(k, 10) - 0.5) * width * 0.7 + dx * age
                val by = ground - 4 - rise * age + (if (age < 3) 0.0 else 0.0)
                val size = if (h2(k, 11) < 0.5) 16 else 22
                out += FxGlyph(if ((age + k) and 1 == 0) "へ" else "ー", bx, by, size)
            }
        }
        return out
    }

    // ---- the dust of the running cats ----------------------------------------------------------------------------------

    /** The dust behind a cat whose left edge is at [x] and whose feet are at [feet]: a few grains for each tick of the run, drifting back and up. */
    fun dust(x: Double, feet: Double, who: Int): List<DustDraw> {
        if (!on || hurryFrom == NEVER) return emptyList()
        val out = ArrayList<DustDraw>(8)
        for (age in 0 until DUST_LIFE) {
            val born = world.tick - age
            if (born < hurryFrom || born > hurryLast) continue
            for (k in 0 until 3) {
                val j = h2(born, who * 5 + k + 40)
                out += DustDraw(DUST[((born * 3 + k + who) and 0xFF) % DUST.length].toString(),
                    x + 8 - age * (3.5 + 2.5 * j) - k * 6, feet - 4 - age * (1.2 + 1.2 * j) - k * 4, 1f - age.toFloat() / DUST_LIFE, 12 - 2 * k)
            }
            if (age < 4) out += DustDraw("ー", x - 10 - age * 9.0, feet - 12 - who * 3.0, 1f - age / 4f, 12)        // and the streak of the speed
        }
        return out
    }

    // ---- the birds that settle on the candidates ----------------------------------------------------------------------

    private class Bird {
        var spanAt = 0.0
        var landAt = NEVER
        var fromX = 0.0
        var flushAt = NEVER
        var flushX = 0.0
        var flushDir = 1
    }

    private val birds = arrayOf(Bird(), Bird())
    private var signature = 0
    private var spans = DoubleArray(0)
    private var top = 0.0

    private fun birdsBusy(): Boolean = birds.any { it.landAt != NEVER && world.tick - it.landAt <= LAND + 1 || it.flushAt != NEVER && world.tick - it.flushAt <= FLY + 1 } ||
        birds.any { it.landAt != NEVER && world.tick >= it.landAt }

    /**
     * The candidates now on the bar: [sig] says which they are (it changes when they do), [spans] are the left and right edge of each one's
     * type, and [textTop] is the line the type's top stands on. A new set startles the birds that were sitting on the old one; one or two
     * settle on the new one a moment later.
     */
    fun candidates(sig: Int, edges: DoubleArray, textTop: Double) {
        if (sig == signature) return
        signature = sig
        spans = edges
        top = textTop
        if (!on) return
        for ((k, b) in birds.withIndex()) {
            if (b.landAt != NEVER && world.tick >= b.landAt + LAND) {           // it was sitting: it is startled
                b.flushAt = world.tick; b.flushX = b.spanAt; b.flushDir = if (h2(sig, k + 3) < 0.5) -1 else 1
            }
            b.landAt = NEVER
            val n = edges.size / 2
            if (n > 0 && k < (if (n >= 3) 2 else 1)) {
                val pick = (h2(sig, k + 11) * n).toInt().coerceAtMost(n - 1)
                val l = edges[2 * pick]
                val r = edges[2 * pick + 1]
                b.spanAt = l + (r - l) * (0.25 + 0.5 * h2(sig, k + 17))
                b.fromX = b.spanAt + (if (h2(sig, k + 23) < 0.5) -1 else 1) * 40
                b.landAt = world.tick + SETTLE + k * 7
            }
        }
    }

    /** The birds, startled and settled, as glyphs in the picture. */
    fun perched(): List<FxGlyph> {
        if (!on) return emptyList()
        val out = ArrayList<FxGlyph>(3)
        for (b in birds) {
            val f = world.tick - b.flushAt
            if (b.flushAt != NEVER && f in 0..FLY) {
                // two or three beats on the spot, then away
                val away = max(0, f - 2)
                out += FxGlyph(if (f and 1 == 0) "へ" else "ー", b.flushX + b.flushDir * 7.0 * away, top - 4 - 1.5 * min(f, 2) - 5.0 * away, 10)
            }
            val a = world.tick - b.landAt
            if (b.landAt != NEVER && a >= 0) {
                if (a < LAND) {
                    val t = (a + 1.0) / LAND
                    out += FxGlyph(if (a and 1 == 0) "へ" else "ー", b.fromX + (b.spanAt - b.fromX) * t, top - 4 - 26 * (1 - t) * (1 - t), 10)
                } else out += FxGlyph(if ((world.tick / 3) % 9 == 0) "ー" else "へ", b.spanAt, top - 4, 12)
            }
        }
        return out
    }

    companion object {
        const val NEVER = -1_000_000_000
        /** Keys per second at which the wind begins to rise, and at which it is as hard as it gets. */
        const val CALM = 4.0
        const val FRANTIC = 12.0
        /** Keys per second at which the cats run. */
        const val HURRY = 7.0
        /** Ticks of running (three seconds) before the storm, ticks it lasts, and the pause before another. */
        const val STORM_AFTER = 36
        const val STORM = 15
        const val STORM_GAP = 12 * 30
        const val STORM_COUNT = 110
        /** Ticks the rocket climbs, and the whole of a firework or flock. */
        const val ROCKET = 3
        const val BURST = 13
        const val FLOCK = 18
        const val DUST_LIFE = 6
        const val DUST = "・˙､,゜"
        /** Ticks after new candidates before a bird settles, the ticks of its landing, and of the flight of one put to flight. */
        const val SETTLE = 16
        const val LAND = 5
        const val FLY = 14
    }
}
