package com.krysta1ll.meowpuut.android.app.onboarding

import com.krysta1ll.meowpuut.android.ime.glyph.CatId
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphCatArt
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphCats
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphColors
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphKeyArt
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise.h2
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise.q
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphPools
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphSink
import com.krysta1ll.meowpuut.android.ime.glyph.Raster
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Draws one moment of the guide: the land ([OnboardingScape]), what stands on it (the signpost, the boards where the road parts, the
 * gate, the thicket, the two cats), and what does not ride with the land (the narration in pixel type, the rule to type on and its slip
 * of paper, the way out, the name at the end). Everything goes into a [GlyphSink] in points, so the same code draws on a phone and in
 * the JVM preview. [glyph] cuts one character of pixel type to a mask (the skin's own faces).
 *
 * This file and [OnboardingScape] are the only two that use the skin's drawing code, and they use its lowest layer only: the noise, the
 * type pools, the sink, the cats' pixels, the hand-cut letters and the pixel type.
 */
internal class OnboardingPainter(pools: GlyphPools, private val glyph: (String) -> GlyphKeyArt.Mask) {
    val scape = OnboardingScape(pools)
    var dark = false
    /** Increase Contrast: the words stand on solid paper, and nothing is drawn faint. */
    var contrast = false
    private val masks = HashMap<String, GlyphKeyArt.Mask>()
    private val rasters = HashMap<String, Raster>()
    private val letters = HashMap<Char, GlyphKeyArt.Mask>()
    private var framed = Triple(0.0, 0.0, false)

    /**
     * The advance of a character, in pixels of the type's grid. The faces set an ideograph eleven pixels wide in thirteen; here it is
     * set in twelve, one pixel apart, as the prototype has it: fourteen to a line of the page instead of twelve.
     */
    fun adv(ch: String, s: Int = 2): Int = (mask(ch).w - 2).let { if (it >= 13 && s > 1) it - 1 else it }   // at one point to the pixel the face's own spacing is kept: any tighter and the letters touch
    private fun mask(ch: String) = masks.getOrPut(ch) { glyph(ch) }
    fun width(text: String, s: Int = 2): Int = text.sumOf { adv(it.toString(), s) }

    /** Pixel type: [x] is the left and [y] the top of the eleven-pixel box, [s] points to the pixel. */
    private fun text(g: GlyphSink, text: String, x: Double, y: Double, s: Int, ink: Int, halo: Int? = null): Double {
        var pen = x
        for (ch in text) { val one = ch.toString(); g.bits(mask(one), pen - s, y - 2 * s, s.toDouble(), ink, halo, false); pen += adv(one, s) * s }
        return pen - x
    }

    /** A sink that draws everything [dy] points lower: the land rides up when the keyboard comes. */
    private class Shifted(val g: GlyphSink, val dy: Double) : GlyphSink {
        override fun rect(x: Double, y: Double, w: Double, h: Double, color: Int) = g.rect(x, y + dy, w, h, color)
        override fun text(ch: String, cx: Double, cy: Double, size: Int, mono: Boolean, color: Int) = g.text(ch, cx, cy + dy, size, mono, color)
        override fun layerEnd() = g.layerEnd()
        override fun raster(r: Raster, x: Double, y: Double, scale: Double) = g.raster(r, x, y + dy, scale)
        override fun bits(mask: GlyphKeyArt.Mask, x: Double, y: Double, unit: Double, ink: Int, halo: Int?, round: Boolean) = g.bits(mask, x, y + dy, unit, ink, halo, round)
    }

    fun draw(out: GlyphSink, s: OnboardingState) {
        val c = GlyphColors.of(dark)
        val geo = s.geo
        if (framed != Triple(geo.w, geo.h, s.atHome)) { scape.wear(geo.w, geo.h, s.atHome); framed = Triple(geo.w, geo.h, s.atHome) }
        // the state's dials, onto the land
        scape.dark = dark; scape.still = s.still; scape.pos = s.pos; scape.epoch = s.epoch; scape.tick = s.tick; scape.fog = s.fog
        scape.pile = s.pile; scape.stars = if (dark) 0.8 * s.stars else 0.0; scape.clouds = 0.5 * s.sky; scape.birds = s.sky >= 1 || s.atHome
        scape.sun = s.sun; scape.rise = s.rise; scape.homeForm = s.home
        scape.far.form = s.far; scape.far.grow = s.far; scape.mid.form = s.mid; scape.mid.grow = s.mid; scape.near.form = s.near; scape.near.grow = max(0.001, s.near)
        scape.near.sparse = 1.02 - 0.16 * s.trees; scape.mid.sparse = 1.02 - 0.09 * s.trees
        for (k in 0..2) { scape.roadEnd[k] = s.roadEnd[k]; scape.roadWide[k] = s.roadWide[k] }
        s.ripple?.let { (x, y, t) -> if (s.tick - t < 7 && scape.ripples.none { it.t == t }) scape.ripples += OnboardingScape.Ripple(x, y, t) }
        scape.prepare()
        s.rainFloor = { x -> scape.pileTop(x) + s.cam }

        out.rect(0.0, 0.0, geo.w, geo.h, c.paper)
        val g = if (s.cam == 0.0) out else Shifted(out, Math.floor(s.cam + 0.5))
        scape.draw(g, c)
        if (!s.atHome) {
            if (s.horizon > 0 && s.near < 1) {                       // the horizon before it is lit: a broken line
                val ink = GlyphColors.withAlpha(c.ink, 0.5 * (1 - s.near) * s.fog)
                var x = 0.0
                while (x < geo.w * s.horizon) { g.rect(x, geo.ridge + 2, 6.0, 2.0, ink); x += 12 }
            }
            if (s.fog > 0.4) { grove(g, c, s); gate(g, c, s); signs(g, c, s); post(g, c, s) }
        }
        cats(g, c, s)
        // what does not ride with the land
        line(out, c, s); narration(out, c, s); corner(out, c, s)
        for (p in s.rain) text(out, p.ch, p.x, Math.floor(p.y + 0.5), p.s, c.ink)
        if (s.atHome) foot(out, c, s)
        out.layerEnd()
    }

    // ---- shapes cut in two-point steps ------------------------------------------------------------------
    /** A board that points right. */
    private fun plank(g: GlyphSink, x: Double, y: Double, w: Double, h: Double, fill: Int, edge: Int) {
        val u = 2.0
        val rows = (h / u).toInt()
        val mid = (rows - 1) / 2.0
        fun span(r: Int) = w - Math.floor(abs(r.coerceIn(0, rows - 1) - mid) + 0.5) * u
        for (r in -1..rows) g.rect(x - u, y + r * u, span(r) + 2 * u, u, edge)
        for (r in 0 until rows) g.rect(x, y + r * u, span(r), u, fill)
    }

    /** A box with stepped corners, as the keys have. */
    private fun stepped(g: GlyphSink, x: Double, y: Double, w: Double, h: Double, color: Int) {
        g.rect(x + 4, y, w - 8, h, color); g.rect(x + 2, y + 2, w - 4, h - 4, color); g.rect(x, y + 4, w, h - 8, color)
    }
    private fun board(g: GlyphSink, x: Double, y: Double, w: Double, h: Double, fill: Int, edge: Int) { stepped(g, x - 2, y - 2, w + 4, h + 4, edge); stepped(g, x, y, w, h, fill) }
    private fun plate(g: GlyphSink, c: GlyphColors, x: Double, y: Double, w: Double, h: Double) { g.rect(x - 2, y - 2, w + 4, h + 4, c.ink); g.rect(x, y, w, h, c.paper) }

    // ---- what stands on the land -----------------------------------------------------------------------
    private fun anchored(x: Double, at: Double, s: OnboardingState) = x - (scape.scrolled(s.pos) - scape.scrolled(at))

    /** The signpost: one board for each step of the way through the system's settings, and under them the way in. */
    private fun post(g: GlyphSink, c: GlyphColors, s: OnboardingState) {
        if (s.post <= 0) return
        val steps = s.copy.a2Steps
        val n = steps.size
        val x = 36.0
        val y0 = s.postY0()
        val pitch = s.postPitch()
        val ph = pitch - 4
        val shown = Math.ceil(s.post * (n + 1)).toInt()
        val tall = n * pitch + 44
        g.rect(x - 4, y0 - 12, 10.0, tall + 4, c.paper); g.rect(x - 2, y0 - 10, 6.0, tall, c.ink); g.rect(x - 6, y0 - 12, 14.0, 2.0, c.ink)
        for (i in 0 until min(n, shown)) {
            val last = i == n - 1
            val done = s.postDone * n > i
            val px = x - 10 + (i % 2) * 8
            val py = y0 + i * pitch
            plank(g, px, py, width(steps[i]) * 2 + 36.0, ph, if (last) c.red else if (done) c.ink else c.paper, c.ink)
            text(g, steps[i], px + 11, py + (ph - 22) / 2, 2, if (last || done) c.paper else c.ink)
        }
        if (shown > n && !s.sunrise) {                                // the way in: a tag in the sun's colour
            val label = s.copy.a2Go
            val py = y0 + n * pitch + 8
            plank(g, x - 10, py, width(label) * 2 + 40.0, 26.0, c.paper, c.red); text(g, label, x, py + 2, 2, c.red)
        }
    }

    /** The dark place the eyes are first seen in: a thicket on the ridge. By day it is solid; at night only its top catches the light. */
    private fun grove(g: GlyphSink, c: GlyphColors, s: OnboardingState) {
        if (s.grove <= 0 || s.act != 3) return
        val x0 = anchored(-10.0, s.grovePos, s)
        if (x0 < -140) return
        val u = 6.0
        val base = q(scape.ridgeAt((x0 + 56).coerceIn(0.0, s.geo.w - 1)) + 6, 2.0)
        fun inside(ix: Int, iy: Int): Boolean {
            if (iy < 0) return false
            val px = ix * u + 3
            val py = iy * u + 3
            return BLOBS.any { (bx, by, r) -> (px - bx) * (px - bx) + (py - by) * (py - by) <= r * r }
        }
        for (iy in 0 until 18) for (ix in -2 until 23) {
            if (!inside(ix, iy) || h2(ix + 9, iy + 31) > s.grove * 1.25) continue
            val rim = !(inside(ix - 1, iy) && inside(ix + 1, iy) && inside(ix, iy + 1) && (iy == 0 || inside(ix, iy - 1)))
            if (if (rim) h2(ix, iy + 70) > 0.74 else h2(ix * 3 + 1, iy * 5) > 0.95) continue   // a ragged edge, and a leaf's worth of light
            val x = x0 + ix * u
            val y = base - (iy + 1) * u
            if (!dark) { g.rect(x, y, u, u, c.ink); continue }
            g.rect(x, y, u, u, c.paper)
            if (!inside(ix, iy + 1)) g.rect(x, y, u, 2.0, c.ink) else if (h2(ix + 5, iy * 3) > 0.8) g.rect(x + 2, y + 2, 2.0, 2.0, c.ink)
        }
    }

    /** Three boards where the road parts: a mark for the way of typing, and its name. */
    private fun signs(g: GlyphSink, c: GlyphColors, s: OnboardingState) {
        if (s.signs <= 0) return
        val bw = 108.0
        val bh = 64.0
        for (k in 0..2) {
            if (s.signs * 3 <= k) continue
            val gone = s.pick >= 0 && s.pick != k
            if (gone && s.signDrop >= 1) continue
            val cx = anchored(OnboardingState.FORK_X[k], s.signPos, s)
            if (cx < -70) continue
            val ry = scape.ridgeAt(cx.coerceIn(0.0, s.geo.w - 1)) + 2
            val top = q(ry - 72 - bh, 2.0) + if (gone) q(s.signDrop * s.signDrop * 150, 2.0) else 0.0
            val mine = s.pick == k
            val stem = max(0.0, ry - top - bh)
            g.rect(cx - 4, top + bh, 8.0, stem, c.paper); g.rect(cx - 2, top + bh, 4.0, stem, c.ink)
            board(g, cx - bw / 2, top, bw, bh, if (mine) c.red else c.paper, c.ink)
            val ink = if (mine) c.paper else c.ink
            val scheme = s.copy.order[k]
            if (scheme == Scheme.NINE_KEY) { for (r in 0..2) for (n in 0..2) g.rect(cx - 12 + n * 9, top + 7 + r * 8, 6.0, 6.0, ink) }
            else { val icon = if (scheme == Scheme.ZHUYIN) "ㄅㄆㄇ" else "abc"; text(g, icon, Math.floor(cx - width(icon) + 0.5), top + 7, 2, ink) }
            val label = s.copy.schemes.getValue(scheme)
            text(g, label, Math.floor(cx - width(label) + 0.5), top + 36, 2, ink)
        }
    }

    /** The gate: wooden, under a small roof, its two leaves (vermilion) standing open, a lamp at each eave. */
    private fun gate(g: GlyphSink, c: GlyphColors, s: OnboardingState) {
        if (!s.gateOn || s.gate <= 0) return
        val u = 10.0
        val x = anchored(s.gateX, s.gatePos, s)
        if (x > s.geo.w + 10 || x < -130) return
        val y0 = q(scape.ridgeAt((x + 55).coerceIn(0.0, s.geo.w - 1)) + 6, 2.0) - GATE.size * u
        fun there(cx: Int, cy: Int) = GATE[cy][cx] != '.' && h2(cx + 3, cy + 40) < s.gate * 1.3
        for (cy in GATE.indices) for (cx in GATE[0].indices) if (there(cx, cy)) g.rect(x + cx * u - 2, y0 + cy * u - 2, u + 4, u + 4, c.paper)
        for (cy in GATE.indices) for (cx in GATE[0].indices) if (there(cx, cy)) g.rect(x + cx * u, y0 + cy * u, u, u, if (GATE[cy][cx] == 'R') c.red else c.ink)
        if (s.gate < 1) return
        val ly = y0 + 3 * u
        for (lx in doubleArrayOf(x - 9, x + 11 * u + 1)) {              // it stands open: the lamps are lit
            g.rect(lx - 4, ly - 2, 16.0, 24.0, c.paper)
            g.rect(lx + 3, ly - 6, 2.0, 12.0, c.ink); g.rect(lx - 2, ly + 6, 12.0, 14.0, c.ink); g.rect(if (lx < x) lx + 3 else lx - 2, ly - 6, 7.0, 2.0, c.ink)
            g.rect(lx, ly + 8, 8.0, 10.0, LAMP)
        }
    }

    // ---- the cats: a pair of eyes first; one of them is typed into being a square at a time -------------------
    private fun cats(g: GlyphSink, c: GlyphColors, s: OnboardingState) {
        if (s.fog < 0.4) return
        for ((id, cat) in listOf(CatId.MOMO to s.momo, CatId.MIMI to s.mimi)) {
            val on = if (s.atHome) (if (s.home < 0.45) 0 else if (s.home < 0.8) 1 else 2) else cat.on
            if (on == 0) continue
            val look = s.look(cat)
            val px = 3.0
            val x = Math.floor(cat.x + 0.5)
            val cw = GlyphCats.w(id)                                 // the two cats are not the same size
            val chh = GlyphCats.h(id)
            val y = Math.floor(scape.ridgeAt((x + cw * px / 2).coerceIn(0.0, s.geo.w - 1)) + 2 - chh * px + look.hop + 0.5)
            if (on == 1 || cat.form < 1) {
                val rows = GlyphCats.rows(id, "sit", "neutral")
                if (on == 1 && s.tick % 38 < 2) continue              // a blink
                for (cy in 0 until chh) for (cx in 0 until cw) {
                    val k = rows[cy][cx]
                    if (k == '.') continue
                    if (on == 1) { if (k == 'Y' || k == 'W' || k == 'U' || (k == 'P' && s.atHome)) g.rect(x + cx * px, y + cy * px, px, px, GlyphCatArt.PALETTE.getValue(k)) }
                    else { val at = h2(cx * 7 + 1, cy * 13 + 5)
                        if (at < cat.form) g.rect(x + cx * px, y + cy * px, px, px, GlyphCatArt.PALETTE.getValue(k)) else if (at < cat.form * 2 + 0.15) g.rect(x + cx * px + 1, y + cy * px + 1, 1.0, 1.0, c.ink) }
                }
                continue
            }
            val pose = if (s.atHome && look.pose != "sleep") "sit" else look.pose
            val r = rasters.getOrPut("${id.key}|$pose|${look.face}|$dark") { GlyphCats.raster(id, pose, look.face, dark, false, c) }
            g.raster(r, x - GlyphCats.MARGIN * px, y - GlyphCats.MARGIN * px, px)
            if (look.z) for (i in 0..2) text(g, "ㄗ", x + 54 + i * 12 + ((s.tick / 8 + i) % 2), y - 12 - i * 13, 1, c.ink, c.paper)
        }
    }

    // ---- what does not ride with the land ---------------------------------------------------------------
    private fun narration(g: GlyphSink, c: GlyphColors, s: OnboardingState) {
        val geo = s.geo
        var left = s.shown.toInt()
        var cx = geo.nx
        var cy = geo.ny
        var cs = geo.s2
        var any = false
        if (contrast && left > 0) {
            var bottom = geo.ny
            var n = left
            for (r in s.rows) { if (n <= 0) break; bottom = r.y + 12 * r.s; n -= r.chars.size }
            plate(g, c, geo.nx - 10, geo.ny - 10, geo.w - 2 * geo.nx + 20, bottom - geo.ny + 20)
        }
        for (r in s.rows) {
            if (left <= 0) break
            val n = min(left, r.chars.size)
            left -= r.chars.size; any = true
            val ink = if (r.mute && !contrast) GlyphColors.withAlpha(c.ink, 0.62) else c.ink
            var end = geo.nx
            for (i in 0 until n) { val ch = r.chars[i]; text(g, ch.ch, ch.x, r.y, r.s, ink, if (contrast) null else c.paper); end = ch.x + adv(ch.ch, r.s) * r.s }
            cx = end + r.s; cy = r.y; cs = r.s
            if (left > 0 && n == r.chars.size) { cx = geo.nx; cy = r.y + 15 * r.s + 2 }
        }
        for (p in s.falls) if (p.d <= 0) text(g, p.ch, p.x, Math.floor(p.y + 0.5), p.s, c.ink)
        // One caret on the page: here, until it is handed to the line to type on, and at the end it types the name.
        val here = s.line < 1 && !(s.atHome && s.mark > 0) && s.act != 0
        if (here && (s.shown < s.total || s.tick % 12 < 7)) g.rect(if (any) cx else geo.nx, cy, 5.0 * cs, 11.0 * cs, c.red)
    }

    /** The line to type on: the field itself is a real one laid over this; here are its rule and the slip of paper tied to its end. */
    private fun line(g: GlyphSink, c: GlyphColors, s: OnboardingState) {
        if (s.line <= 0) return
        val geo = s.geo
        val x0 = geo.nx
        val x1 = geo.nx + (geo.w - 2 * geo.nx) * s.line
        val y = geo.lineY
        if (contrast) plate(g, c, geo.nx - 10, y - 52, geo.w - 2 * geo.nx + 20, 66.0)
        g.rect(x0 - 3, y - 9, x1 - x0 + 6, 15.0, c.paper); g.rect(x0, y, x1 - x0, 3.0, c.ink); g.rect(x0, y - 6, 3.0, 6.0, c.ink)
        if (s.line >= 1) g.rect(x1 - 3, y - 6, 3.0, 6.0, c.ink)
        if (s.tag <= 0) return
        val t = s.tagText()
        var below = y + 14
        if (t != null) {
            val w = width(t) * 2 + 20.0
            g.rect(geo.w - geo.nx - 14, y + 3, 2.0, 12.0, c.ink)
            board(g, geo.w - geo.nx - w, below, w, 34.0, c.paper, c.ink)
            text(g, t, geo.w - geo.nx - w + 10, below + 6, 2, if (s.tagIsAsk()) c.red else c.ink)
            below += 44
        }
        if (s.onward && !s.committed) { val w = width(s.copy.a3Onward) * 2 + 36.0; plank(g, geo.w - geo.nx - w, below, w, 26.0, c.paper, c.ink); text(g, s.copy.a3Onward, geo.w - geo.nx - w + 10, below + 2, 2, c.ink) }
    }

    /** The way out, small, in the corner; and in the first act the one thing to do. */
    private fun corner(g: GlyphSink, c: GlyphColors, s: OnboardingState) {
        val geo = s.geo
        if (!s.atHome) {
            val w = width(s.copy.skip, geo.s1) * geo.s1.toDouble()
            val x = geo.w - 18 - w
            val y = geo.top + 18
            if (contrast) plate(g, c, x - 6, y - 6, w + 12, 11.0 * geo.s1 + 12)
            text(g, s.copy.skip, x, y, geo.s1, if (contrast) c.ink else GlyphColors.withAlpha(c.ink, 0.62), if (contrast) null else c.paper)
        }
        if (s.hint > 0 && s.tick % 16 < 11) { val l = s.copy.a1Hint; text(g, l, Math.floor((geo.w - width(l, geo.s2) * geo.s2) / 2 + 0.5), geo.h - 170 - geo.bottom, geo.s2, GlyphColors.withAlpha(c.ink, 0.62 * s.hint)) }
    }

    /** The foot of the last page: the name, typed last in the keys' own pixels; the offer to keep the skin; two small lines. */
    private fun foot(g: GlyphSink, c: GlyphColors, s: OnboardingState) {
        val geo = s.geo
        val footTop = s.footTop()
        val tagY = footTop - 38
        val markY = tagY - 24
        if (s.mark > 0) {
            val u = 3.0
            val n = s.mark.toInt()
            val x0 = Math.floor(geo.w / 2 - (WORD.length * 6 * u - u) / 2 + 0.5)
            for (i in 0 until min(n, WORD.length)) g.bits(letters.getOrPut(WORD[i]) { GlyphKeyArt.mask(GlyphKeyArt.bits(WORD[i].toString())!!) }, x0 + i * 6 * u, markY - 10.5, u, c.ink, c.paper, false)
            if (n < WORD.length || s.tick % 12 < 7) g.rect(x0 + n * 6 * u + (if (n == WORD.length) 2 else 0), markY - 12, 9.0, 24.0, c.red)
        }
        if (s.wear > 0) {                                             // an offer, not a step
            val label = s.wearLabel()
            val w = s.wearWidth()
            val x = Math.floor((geo.w - w) / 2 + 0.5)
            board(g, x, tagY, w, 30.0, if (s.worn) c.ink else c.paper, c.ink)
            text(g, label, x + 12, tagY + 4, 2, if (s.worn) c.paper else c.ink)
        }
        if (s.foot > 0) {
            val ink = if (contrast) c.ink else GlyphColors.withAlpha(c.ink, 0.62 * s.foot)
            for ((i, l) in s.copy.a6Foot.withIndex()) text(g, l, Math.floor((geo.w - width(l, geo.s1) * geo.s1) / 2 + 0.5), footTop + i * (13 * geo.s1 + 3), geo.s1, ink, c.paper)
        }
    }

    companion object {
        private const val WORD = "meowpuut"
        private const val LAMP = 0xFFF3CF4B.toInt()
        private val BLOBS = listOf(Triple(14.0, 22.0, 26.0), Triple(44.0, 40.0, 36.0), Triple(80.0, 34.0, 34.0), Triple(104.0, 18.0, 22.0), Triple(30.0, 66.0, 22.0), Triple(58.0, 80.0, 20.0), Triple(82.0, 64.0, 20.0))
        private val GATE = listOf("....XXX....", "..XXXXXXX..", "XXXXXXXXXXX", ".X.......X.", ".XRR...RRX.", ".XRR...RRX.", ".XRR...RRX.", ".XRR...RRX.", ".X.......X.")
    }
}
