package com.krysta1ll.meowpuut.android.ime.glyph

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/**
 * 縱深 in motion: not one scroll at several speeds. Every plane has its own weight (how soon it follows the typing and how long it takes to
 * come to rest), and what passes in front is a spring that runs past and comes back. This is the prototype's `advance` (glyph-scenes.template.html),
 * without a screen: it is stepped once a frame with the time that has passed, and says whether anything is still moving.
 *
 *  - every plane's *run* eases toward the world's mileage ([target]) with its own time constant: the near land 0.11 s, the middle distance 0.3,
 *    the far distance 0.8, the sky 1.2; on the ground 0.1 s at the viewer's feet to 0.4 at the horizon;
 *  - what passes in front is a spring (k 190, damping 15) and never stops in the picture: if it is in view 0.35 s after the last key it
 *    goes on by itself, [COAST] units a second, until it is out;
 *  - the turn of the type ([epochF]) follows the world's whole-number epoch smoothly (0.3 s), so each glyph turns at its own moment and a few
 *    change in any one frame, never a great many at once.
 * With the pictured motion off ([still]: Reduce motion, power saving, 輕量) nothing here moves: everything is where the typing put it.
 */
internal class GlyphMotion {
    /** The clock of this motion (milliseconds of stepping), and when the last key was pressed on it. */
    var clock = 0.0; private set
    var keyAt = -1e9; private set
    /** The smoothed turn of the type. NaN until first stepped. */
    var epochF = Double.NaN; private set
    /** The ground's runs at the viewer's feet and at the horizon (each row in between is interpolated by the caller). NaN until stepped. */
    var near = Double.NaN; private set
    var far = Double.NaN; private set
    /** What passes in front: its spring position and speed, and how far it has gone on by itself. */
    var fore = Double.NaN; private set
    var foreV = 0.0; private set
    var coast = 0.0; private set
    /** One run per layer (the caller's order; the sky last). NaN until stepped. */
    var run = DoubleArray(0); private set

    /** A key went down: the planes are to follow, and what passes in front is not to coast away yet. */
    fun keyed() { keyAt = clock }

    /** Nothing is remembered between two showings of the keyboard. */
    fun reset() {
        clock = 0.0; keyAt = -1e9; epochF = Double.NaN; near = Double.NaN; far = Double.NaN
        fore = Double.NaN; foreV = 0.0; coast = 0.0; run = DoubleArray(0)
    }

    /**
     * One step of [dt] seconds. [target] is the world's mileage, [epoch] its whole-number turn, [tau] each layer's time constant.
     * [foreFree]: what passes may be left coasting out (false when a shot holds it in place); [inView] says whether it stands in the picture
     * at the given left edge. Returns true while anything still moves, so the caller knows to keep stepping.
     */
    fun advance(dt: Double, target: Double, epoch: Int, still: Boolean, tau: DoubleArray, foreFree: Boolean, inView: (Double) -> Boolean): Boolean {
        clock += dt * 1000
        if (run.size != tau.size) run = DoubleArray(tau.size) { Double.NaN }
        epochF = if (epochF.isNaN() || still) epoch.toDouble() else epochF + (epoch - epochF) * (1 - exp(-dt / EPOCH_TAU))
        fun ease(v: Double, t: Double) = if (v.isNaN() || still) target else if (abs(target - v) < SETTLED) target else v + (target - v) * (1 - exp(-dt / t))
        var busy = (clock - keyAt < ACTIVE_MS || abs(epoch - epochF) > 0.002) && !still
        for (i in run.indices) { val was = run[i]; run[i] = ease(run[i], tau[i]); if (run[i] != was) busy = true }
        val n0 = near; val f0 = far
        near = ease(near, GROUND_NEAR); far = ease(far, GROUND_FAR)
        if (near != n0 || far != f0) busy = true
        if (fore.isNaN() || still) { fore = target; foreV = 0.0 } else {            // a spring: it runs past and comes back
            foreV += (FORE_K * (target - fore) - FORE_D * foreV) * dt
            fore += foreV * dt
            if (abs(target - fore) > SETTLED || abs(foreV) > 0.02) busy = true else { fore = target; foreV = 0.0 }
            // It never stops in front of the keys: left there when the typing stops, it goes on by itself until it is out of the picture.
            if (foreFree && inView(GlyphDepthFore.passesAt(fore, coast)) && clock - keyAt > COAST_AFTER_MS) { coast += COAST * dt; busy = true }
        }
        return busy
    }

    companion object {
        const val TAU_NEAR = 0.11
        const val TAU_MID = 0.3
        const val TAU_FAR = 0.8
        const val TAU_SKY = 1.2
        const val GROUND_NEAR = 0.1
        const val GROUND_FAR = 0.4
        const val EPOCH_TAU = 0.3
        const val FORE_K = 190.0
        const val FORE_D = 15.0
        const val COAST = 14.0
        const val COAST_AFTER_MS = 350.0
        /** Planes follow the typing this long after the last key, whatever they are doing (the cats' small movements). */
        const val ACTIVE_MS = 4200.0
        private const val SETTLED = 0.004

        /** A layer's time constant by what it is: the sky and dust 1.2, the near land (speed from 0.45) 0.11, the middle (from 0.15) 0.3, the far 0.8. */
        fun tauOf(pool: String, speed: Double): Double = if (pool == "dust") TAU_SKY else if (speed >= 0.45) TAU_NEAR else if (speed >= 0.15) TAU_MID else TAU_FAR

        /** The ground's run at height [g0] (0 the horizon … 1 the feet): [near] at the feet, [far] at the horizon, linear between. */
        fun groundRun(near: Double, far: Double, g0: Double): Double = far + (near - far) * min(1.0, g0)

        /** Where what passes in front stands in the picture of [width] points (its left edge) while it can be seen. */
        fun visible(at: Double, width: Double): Boolean = at > -110 && at < width + 40
    }
}
