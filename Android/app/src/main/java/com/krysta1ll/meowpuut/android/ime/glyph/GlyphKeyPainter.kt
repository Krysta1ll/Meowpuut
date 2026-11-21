package com.krysta1ll.meowpuut.android.ime.glyph

import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise.q
import kotlin.math.max
import kotlin.math.min

/**
 * Type cut to one bit: the platform draws [text] on the pixel grid (the two bitmap faces of the skin, then the system font for what they
 * lack: see [GlyphPixelType]), and every pixel that is more than a third ink is ink. The candidates, the Zhuyin on the keys and every other
 * legend that is not cut by hand come from here. The answer is cached by the caller.
 */
internal interface GlyphPixelFont {
    /** [size] is the em in pixels of the one-bit grid ([GlyphPixelType.EM]); [weight] and [mono] are kept for the system font's sake. */
    fun mask(text: String, size: Int, weight: Int, mono: Boolean): GlyphKeyArt.Mask
}

/** How a key is cut. */
internal enum class CapRole { PLAIN, ACCENT, SPACE }

/** One key, in points from the keyboard's top left. [art] names its hand-cut pixels (see [GlyphKeyArt.bits]), or is null for set type. */
internal class KeyCap(
    val x: Double, val y: Double, val w: Double, val h: Double,
    val label: String,
    val art: String?,
    val role: CapRole,
    val pressed: Boolean,
    val enabled: Boolean = true,
)

/** One candidate in the bar: its box, text and whether it is the chosen one or a Japanese word. */
internal class CandidateCap(val x: Double, val w: Double, val text: String, val selected: Boolean, val japanese: Boolean, val pressed: Boolean = false)

/**
 * The type on the keyboard in the open style: no cap, only the legend, cut by hand, with a clear paper halo; a pressed key is a block
 * of ink with the legend turned to paper; the confirm key is the one vermilion block; the space bar is a rule whose shape says what
 * it will do. The land thins round all of it ([GlyphScape.hush]), so the picture runs on behind the keys. With [chips] (the system
 * asks for more contrast) every key is a solid block with a rim instead, and nothing is left to the picture.
 *
 * Pure: it draws into a [GlyphSink] and asks a [GlyphPixelFont] for set type, so the JVM tests draw keys into a list.
 */
internal class GlyphKeyPainter(private val font: GlyphPixelFont) {
    private val masks = HashMap<String, GlyphKeyArt.Mask>()
    private val bitMasks = HashMap<String, GlyphKeyArt.Mask>()

    /** The size legends are set at: the pixel type's em and the points to a pixel (a whole number: the grid stays square). */
    class Face(val size: Int, val scale: Double, val mono: Boolean, val weight: Int)

    fun face(label: String): Face = Face(GlyphPixelType.EM, KEY_SCALE, false, 400)

    fun mask(text: String, size: Int, weight: Int, mono: Boolean): GlyphKeyArt.Mask {
        if (masks.size > 400) masks.clear()
        return masks.getOrPut("$size/$weight/$mono/$text") { font.mask(text, size, weight, mono) }
    }

    private fun bits(art: String): GlyphKeyArt.Mask? {
        val rows = GlyphKeyArt.bits(art) ?: return null
        return bitMasks.getOrPut(art) { GlyphKeyArt.mask(rows) }
    }

    /** The width a label is set at, in points, for the key's face; a legend of hand-cut pixels is [BITS_UNIT] points to the pixel. */
    fun width(cap: KeyCap): Double {
        cap.art?.let { a -> bits(a)?.let { return it.w * BITS_UNIT } }
        val f = face(cap.label)
        return mask(cap.label, f.size, f.weight, f.mono).w * f.scale
    }

    /** A legend that fits [room] points: at twice the grid if it does, else at the grid itself. */
    private fun fitted(label: String, room: Double): Pair<GlyphKeyArt.Mask, Double> {
        val m = mask(label, GlyphPixelType.EM, 400, false)
        return if (m.w * KEY_SCALE <= room) m to KEY_SCALE else m to 1.0
    }

    private fun legend(g: GlyphSink, cap: KeyCap, cx: Double, cy: Double, ink: Int, halo: Int?) {
        val art = cap.art?.let(::bits)
        if (art != null) {
            val x0 = Math.round(cx - art.w * BITS_UNIT / 2).toDouble()
            val y0 = Math.round(cy - art.h * BITS_UNIT / 2).toDouble()
            g.bits(art, x0, y0, BITS_UNIT, ink, halo, round = false)
            return
        }
        if (cap.label.isEmpty()) return
        val (m, scale) = fitted(cap.label, max(10.0, cap.w - 8))
        val x0 = Math.round(cx - m.w * scale / 2).toDouble()
        val y0 = Math.round(cy - m.h * scale / 2).toDouble()
        g.bits(m, x0, y0, scale, ink, halo, round = true)
    }

    /** How a key is hung in the street: no sign, a sign that is lit (night) or one that is not (day), a lit sign that has gone out for a moment. */
    enum class Sign { NONE, DAY, LIT, OUT }

    fun key(g: GlyphSink, c: GlyphColors, cap: KeyCap, composing: Boolean, chips: Boolean, sign: Sign = Sign.NONE) {
        val cx = cap.x + cap.w / 2
        val cy = cap.y + cap.h / 2
        val ink = if (cap.enabled) c.ink else GlyphColors.withAlpha(c.ink, 0.4)
        val latin = cap.label.length == 1 && cap.label[0].isLetter() && cap.label[0].code < 128
        val wide = width(cap)
        if (chips) { chip(g, c, cap, cx, cy, latin, wide); return }
        val w = q(max(26.0, wide + 14), 2.0)
        val h = 30.0
        val x = q(cx - w / 2, 2.0)
        val y = q(cy - h / 2, 2.0)
        when {
            cap.pressed -> {
                stepped(g, x - 2, y - 2, w + 4, h + 4, 2.0, c.paper)
                stepped(g, x, y, w, h, 2.0, c.ink)
            }
            cap.role == CapRole.ACCENT -> {                          // the one solid key, in the colour of the sun
                val aw = q(cap.w - 18, 2.0)
                val ax = q(cx - aw / 2, 2.0)
                stepped(g, ax - 2, y - 4, aw + 4, h + 8, 2.0, c.paper)
                stepped(g, ax, y - 2, aw, h + 4, 2.0, c.red)
            }
            cap.role == CapRole.SPACE -> space(g, c, cap, cy, composing, ink)
            sign != Sign.NONE -> {
                val sw = q(max(24.0, wide + 10), 2.0)
                sign(g, c, q(cx - sw / 2, 2.0), q(cy - 13.0, 2.0), sw, 26.0, sign)
            }
        }
        if (cap.role != CapRole.SPACE) {                              // the space bar says it with its shape, not with words
            val solid = cap.pressed || cap.role == CapRole.ACCENT
            val onSign = !solid && (sign == Sign.DAY || sign == Sign.LIT)
            val legendInk = if (solid) c.paper else if (sign == Sign.LIT) c.paper else ink
            legend(g, cap, cx, cy, legendInk, if (solid || onSign) null else c.paper)
        }
    }

    /**
     * A key in the street is a sign hung on the front of the street: paper in a frame of ink by day; by night a lit plate (the ink colour
     * is the light, the legend is cut out of it), and now and then one that has gone out and shows only its frame, like a neon with a bad
     * contact. Its corners are stepped like the cats'.
     */
    private fun sign(g: GlyphSink, c: GlyphColors, x: Double, y: Double, w: Double, h: Double, sign: Sign) {
        when (sign) {
            Sign.DAY -> { stepped(g, x - 2, y - 2, w + 4, h + 4, 2.0, c.ink); stepped(g, x, y, w, h, 2.0, c.paper) }
            Sign.LIT -> { stepped(g, x - 2, y - 2, w + 4, h + 4, 2.0, c.paper); stepped(g, x, y, w, h, 2.0, c.ink) }
            Sign.OUT -> { stepped(g, x - 2, y - 2, w + 4, h + 4, 2.0, c.ink); stepped(g, x, y, w, h, 2.0, c.paper) }
            Sign.NONE -> Unit
        }
    }

    /** The space bar: a rule with turned-up ends; while a reading is being typed a second rule lies in it: it will choose. */
    private fun space(g: GlyphSink, c: GlyphColors, cap: KeyCap, cy: Double, composing: Boolean, ink: Int) {
        val x0 = q(cap.x + 30, 2.0)
        val x1 = q(cap.x + cap.w - 30, 2.0)
        val yb = q(cy + 4, 2.0)
        g.rect(x0 - 3, yb - 9, x1 - x0 + 6, 14.0, c.paper)
        g.rect(x0, yb, x1 - x0, 3.0, ink)
        g.rect(x0, yb - 6, 3.0, 6.0, ink)
        g.rect(x1 - 3, yb - 6, 3.0, 6.0, ink)
        if (composing) g.rect(x0 + 6, yb - 6, x1 - x0 - 12, 3.0, ink)
    }

    private fun chip(g: GlyphSink, c: GlyphColors, cap: KeyCap, cx: Double, cy: Double, latin: Boolean, wide: Double) {
        val w = q(if (latin) 26.0 else min(cap.w - 12, wide + (if (cap.role == CapRole.SPACE) 120 else 18)), 2.0)
        val h = 30.0
        val dark = (latin || cap.role == CapRole.ACCENT) != cap.pressed
        val x = q(cx - w / 2, 2.0)
        val y = q(cy - h / 2, 2.0)
        stepped(g, x - 2, y - 2, w + 4, h + 4, 2.0, if (dark) c.paper else c.ink)   // a rim in the other colour: the chip reads on any ground
        stepped(g, x, y, w, h, 2.0, if (dark) c.ink else c.paper)
        legend(g, cap, cx, cy, if (dark) c.paper else (if (cap.enabled) c.ink else GlyphColors.withAlpha(c.ink, 0.4)), null)
    }

    // ---- the candidate bar ---------------------------------------------------------------------------------

    /** The size candidates are set at: the em of the one-bit grid and the points to a pixel. */
    class CandidateFace(val size: Int, val scale: Double)

    fun candidateWidth(text: String, face: CandidateFace): Double = mask(text, face.size, 400, false).w * face.scale

    /** The bar's own sheet, with chips: a ruled strip of paper. In the open style the bar has nothing of its own. */
    fun barSheet(g: GlyphSink, c: GlyphColors, width: Double, barTop: Double, barHeight: Double) {
        stepped(g, 8.0, barTop, width - 16, barHeight, 2.0, c.ink)
        stepped(g, 10.0, barTop + 2, width - 20, barHeight - 4, 2.0, c.paper)
    }

    /** One candidate. [mid] is the vertical middle of the bar. Returns nothing: the caller already has the box. */
    fun candidate(g: GlyphSink, c: GlyphColors, cand: CandidateCap, face: CandidateFace, mid: Double, pillHeight: Double, chips: Boolean) {
        val on = cand.selected || cand.pressed
        val open = !chips
        val half = (pillHeight / 2).coerceIn(15.0, 19.0)
        if (on) {
            if (open) stepped(g, cand.x - 2, mid - half - 2, cand.w + 4, half * 2 + 4, 2.0, c.paper)
            stepped(g, cand.x, mid - half, cand.w, half * 2, 2.0, c.ink)
        }
        val m = mask(cand.text, face.size, 400, false)
        val markW = if (cand.japanese) PAW_SPACE else 0.0
        val tw = m.w * face.scale
        val x0 = Math.round(cand.x + (cand.w - tw - markW) / 2).toDouble()
        val y0 = Math.round(mid - m.h * face.scale / 2).toDouble()
        g.bits(m, x0, y0, face.scale, if (on) c.paper else c.ink, if (open && !on) c.paper else null, round = true)
        if (cand.japanese) paw(g, c, x0 + tw + 4, mid - 5, on, open && !on)
    }

    /** Where a candidate's own type begins and ends (left and right, in points): the birds settle on it. */
    fun candidateSpan(cand: CandidateCap, face: CandidateFace): Pair<Double, Double> {
        val m = mask(cand.text, face.size, 400, false)
        val tw = m.w * face.scale
        val x0 = Math.round(cand.x + (cand.w - tw - (if (cand.japanese) PAW_SPACE else 0.0)) / 2).toDouble()
        return x0 to x0 + tw
    }

    /** The paw that marks a Japanese word, in the cats' own pixels. */
    fun paw(g: GlyphSink, c: GlyphColors, x: Double, y: Double, on: Boolean, halo: Boolean) {
        val u = 2.0
        if (halo) for (b in GlyphKeyArt.PAW) g.rect(x + b[0] * u - 2, y + b[1] * u - 2, b[2] * u + 4, b[3] * u + 4, c.paper)
        for (b in GlyphKeyArt.PAW) g.rect(x + b[0] * u, y + b[1] * u, b[2] * u, b[3] * u, if (on) c.paper else c.ink)
    }

    /** A plain label on the bar: the status of the keyboard, the name of the place. */
    fun label(g: GlyphSink, c: GlyphColors, text: String, x: Double, mid: Double, scale: Double, alpha: Double, centre: Boolean, halo: Boolean) {
        run(g, text, x, mid, scale, GlyphColors.withAlpha(c.ink, alpha), if (halo) GlyphColors.withAlpha(c.paper, alpha) else null, centre)
    }

    /** The expand key at the bar's end: a V that turns over when the panel is open. */
    fun chevron(g: GlyphSink, c: GlyphColors, cx: Double, mid: Double, up: Boolean, halo: Boolean) {
        val m = chevrons.getOrPut(up) { GlyphKeyArt.mask(if (up) CHEVRON_UP else CHEVRON_DOWN) }
        val x0 = Math.round(cx - m.w * 2.0 / 2).toDouble()
        val y0 = Math.round(mid - m.h * 2.0 / 2).toDouble()
        g.bits(m, x0, y0, 2.0, c.ink, if (halo) c.paper else null, round = false)
    }

    private val chevrons = HashMap<Boolean, GlyphKeyArt.Mask>()

    /**
     * A run of pixel type, [scale] points to the pixel, its middle on [mid] and its left (or, with [centre], its middle) on [x]; in [ink],
     * with a round halo of [halo] when given. The bar's labels and the words that rise use it.
     */
    fun run(g: GlyphSink, text: String, x: Double, mid: Double, scale: Double, ink: Int, halo: Int?, centre: Boolean) {
        if (text.isEmpty()) return
        val m = mask(text, GlyphPixelType.EM, 400, false)
        val x0 = Math.round(if (centre) x - m.w * scale / 2 else x).toDouble()
        val y0 = Math.round(mid - m.h * scale / 2).toDouble()
        g.bits(m, x0, y0, scale, ink, halo, round = true)
    }

    companion object {
        /** Points to one pixel of a key's legend and of a candidate: twice the grid. */
        const val KEY_SCALE = 2.0
        private val CHEVRON_DOWN = listOf("X.....X", ".X...X.", "..X.X..", "...X...")
        private val CHEVRON_UP = listOf("...X...", "..X.X..", ".X...X.", "X.....X")

        /** Points to one pixel of a hand-cut legend. */
        const val BITS_UNIT = 3.0
        /** Room a Japanese word's paw takes after the text. */
        const val PAW_SPACE = 14.0

        /** A rectangle with square steps at its corners, as three plain rectangles. */
        fun stepped(g: GlyphSink, x: Double, y: Double, w: Double, h: Double, s: Double, color: Int) {
            g.rect(x + 2 * s, y, w - 4 * s, h, color)
            g.rect(x + s, y + s, w - 2 * s, h - 2 * s, color)
            g.rect(x, y + 2 * s, w, h - 4 * s, color)
        }
    }
}
