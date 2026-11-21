package com.krysta1ll.meowpuut.android.app.onboarding

import com.krysta1ll.meowpuut.android.ime.glyph.GlyphColors
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise.fbm1
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise.h2
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise.mod
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise.n1
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise.n2
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise.q
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise.round
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphPools
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphSink
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The land of the guide (第一行字): the 字景 picture cut for a whole tall page, and built a part at a time.
 *
 * It is drawn the way the keyboard's picture is (layers of land far to near, each on its own grid of type; a cell is four blocks and is
 * either mosaic or one glyph), and uses the skin's own noise, type pools and sink, so it is the same world. What it has that the
 * keyboard's picture has not: every layer has its own progress ([Land.form]) and its own height ([Land.grow]), the ground has a road
 * that can part in three, the sun gathers out of scattered points, and the page can end at the sea. It is a separate drawing on purpose:
 * the keyboard's picture (ime/glyph/GlyphScape) frames a keyboard, is being extended elsewhere, and must not change by a pixel for this.
 * Nothing here knows the screen. Ported from design-preview/specs/glyph-onboarding.js and the engine it sits on.
 */
internal class OnboardingScape(private val pools: GlyphPools) {
    enum class Shape { ROLL, PEAKS, ISLAND }

    /** One layer of land. [base] and [amp] are parts of the picture's height, as the skin's places give them. */
    class Land(
        val cell: Int, val shape: Shape, val base: Double, val amp: Double, val freq: Double, val speed: Double, val tone: Double,
        val edge: Double, val pool: String, val seed: Int, val trees: Boolean, val grass: Boolean, val haze: Double,
    ) {
        /** 0 not there, 1 there: the type comes first and closes into blocks after. */
        var form = 1.0
        /** How much of its height it has grown to. */
        var grow = 1.0
        /** A column carries a tree where the world's hash is at least this. Above 1: no trees. */
        var sparse = 1.02
        val sub = cell / 2.0
        var n = 0
        var offI = 0
        var baseY = 0.0
        var ridge = DoubleArray(0)
        var treeY = DoubleArray(0)
        var treeDx = IntArray(0)
        var treeTall = DoubleArray(0)
        var top = 0.0
        var bottom = 0.0
    }

    /** What one answer of a layer says besides its density. */
    private class Probe {
        var pool = ""; var rim = false; var lit = false; var acc = false; var prio = 0; var ox = 0
        fun reset() { rim = false; lit = false; acc = false; prio = 0 }
    }

    class Ripple(val x: Double, val y: Double, val t: Int)

    // ---- the page -------------------------------------------------------------------------------------
    var width = 393.0; private set
    var height = 852.0; private set
    /** Where the land the cats walk lies. */
    var ridgeY = 540.0; private set
    var groundY = 548.0; private set
    private var fs = 0.3
    private var fo = 0.0
    private var fade = 96.0
    var home = false; private set
    var dark = false
    var still = false

    // ---- the moment -----------------------------------------------------------------------------------
    var pos = 0.0
    var epoch = 0
    var tick = 0
    var fog = 1.0
    val ripples = ArrayList<Ripple>()

    // ---- the place that is built: 山野 for a tall page, the far range in peaks -----------------------------
    val near = Land(6, Shape.ROLL, 0.74, 0.2, 0.026, 0.5, 0.64, 1.0, "bopo", 3, trees = true, grass = true, haze = 0.4)
    val mid = Land(6, Shape.ROLL, 0.64, 0.42, 0.03, 0.24, 0.44, 0.8, "hira", 11, trees = true, grass = false, haze = 0.26)
    val far = Land(4, Shape.PEAKS, 0.56, 0.62, 0.02, 0.1, 0.3, 0.7, "pinyin", 23, trees = false, grass = false, haze = 0.3)
    // ---- the place it ends at: the sea ------------------------------------------------------------------
    private val shore = Land(6, Shape.ROLL, 0.84, 0.09, 0.022, 0.5, 0.4, 1.0, "bopo", 4, trees = false, grass = true, haze = 0.4)
    private val island = Land(4, Shape.ISLAND, 0.5, 0.2, 0.006, 0.07, 0.34, 0.7, "pinyin", 8, trees = false, grass = false, haze = 0.0)
    private val sky = Land(6, Shape.ROLL, 0.0, 0.0, 0.0, 0.05, 0.0, 0.0, "dust", 0, trees = false, grass = false, haze = 0.0)

    /** The ground has been piled this far (0..1); the sky's clouds and stars; the sun: how far it has gathered and risen. */
    var pile = 1.0
    var clouds = 0.0
    var stars = 0.0
    var birds = false
    var sun = 0.0
    var rise = 0.0
    /** The whole of the last place: one progress, as the keyboard's picture has. */
    var homeForm = 1.0
    /** The road: where each of its three far ends is, and how wide each is (0 gone, 1 whole). While the ends agree it is one road. */
    val roadEnd = doubleArrayOf(196.0, 196.0, 196.0)
    val roadWide = doubleArrayOf(1.0, 1.0, 1.0)

    val sunX = 166.0
    val sunR = 72.0
    val sunY: Double get() = ridgeY - 150
    var discX = 0.0; private set
    var discY = 0.0; private set
    var discR = 0.0; private set

    private val probe = Probe()
    private val v = DoubleArray(4)
    private var f = 1.0

    /** Frames the picture for a page [w] by [h] points: the place that is built, or ([toHome]) the sea. */
    fun wear(w: Double, h: Double, toHome: Boolean) {
        width = w; height = h; home = toHome
        ridgeY = q(h * (if (toHome) 0.587 else 0.634), 2.0)
        fs = if (toHome) 0.5 else 0.3
        val walk = if (toHome) shore else near
        fo = ridgeY / h - walk.base * fs
        fade = if (toHome) 210.0 else 96.0
        groundY = q(ridgeY + 10, 4.0)
        for (l in listOf(near, mid, far, shore, island, sky)) {
            l.n = ceil(w / l.sub).toInt() + 2
            l.baseY = (l.base * fs + fo) * h
            l.ridge = DoubleArray(l.n); l.treeY = DoubleArray(l.n); l.treeDx = IntArray(l.n); l.treeTall = DoubleArray(l.n)
        }
        discR = 0.12 * h
        discX = 0.62 * w
        discY = ridgeY - discR * 0.15
    }

    fun ridgeAt(x: Double): Double { val l = if (home) shore else near; return l.ridge[(x / l.sub).toInt().coerceIn(0, l.n - 1)] }
    fun farRidgeAt(x: Double): Double = far.ridge[(x / far.sub).toInt().coerceIn(0, far.n - 1)]
    /** How far the near land has scrolled, in points: what stands on the land moves by this. */
    fun scrolled(at: Double): Double = floor(at * 2 / 3) * 6

    // ---- one layer ------------------------------------------------------------------------------------
    private fun prepare(l: Land) {
        l.offI = floor(pos * l.speed * 8 / l.cell).toInt() * 2
        if (l === sky) { l.top = 0.0; l.bottom = ridgeY; return }
        val amp = l.amp * height * fs * l.grow
        var lo = 1e9
        var hi = -1e9
        for (i in 0 until l.n) {
            val wi = i + l.offI
            val w = wi * l.sub / 4
            val value = when (l.shape) {
                Shape.PEAKS -> { val t = w * l.freq + l.seed * 0.37; val k = floor(t); val fr = t - k
                    (1 - abs(2 * fr - 1)) * (0.5 + 0.5 * h2(k.toInt(), l.seed + 5)) * 0.85 + 0.15 * fbm1(w * l.freq * 5, l.seed + 2) }
                Shape.ISLAND -> max(0.0, fbm1(w * l.freq, l.seed) - 0.5) * 2
                Shape.ROLL -> fbm1(w * l.freq, l.seed)
            }
            var y = q(l.baseY - amp * value, l.sub)
            if (l.grass) { val k = h2(wi, l.seed + 9); if (k > 0.93) y -= l.sub * 2 else if (k > 0.72) y -= l.sub }
            l.ridge[i] = y
            if (y < lo) lo = y
            if (y > hi) hi = y
        }
        val u = l.cell / 8.0
        if (l.trees) {                                              // a tree stands where the world says so; its crown spans a few blocks either side
            l.treeY.fill(1e9)
            val reach = 3
            for (i in -reach until l.n + reach) {
                val wi = i + l.offI
                if (wi and 1 != 0 || h2(wi shr 1, l.seed + 40) < l.sparse) continue
                val root = l.ridge[i.coerceIn(0, l.n - 1)]
                val tall = 1 + h2(wi shr 1, l.seed + 41)
                for (k in -reach..reach) { val at = i + k; if (at in 0 until l.n) { l.treeY[at] = root - (16 + 6 * tall) * u; l.treeDx[at] = k; l.treeTall[at] = tall } }
            }
        }
        l.top = max(0.0, lo - (if (l.trees) 40 * u else 0.0) - l.cell)
        l.bottom = min(height, if (l.haze > 0) hi + l.haze * height * fs + l.cell else height)
    }

    private fun sample(l: Land, i: Int, py: Double): Double {
        val top = l.ridge[i]
        val u = l.cell / 8.0
        probe.pool = l.pool; probe.ox = i + l.offI
        if (l.trees && l.treeY[i] < 1e8 && py < top) {
            val dx = l.treeDx[i] * l.sub / u
            val dy = (py - l.treeY[i]) / u
            val tall = l.treeTall[i]
            val rr = dx * dx + dy * dy
            if (rr <= 26 + 10 * tall) return 1.0                      // the heart of the crown is solid
            if (rr <= 81 + 30 * tall) { probe.pool = "leaf"; probe.prio = 2; return 0.55 }
            if (l.treeDx[i] == 0 && dy > 0) return 1.0
        }
        if (py < top) return -1.0
        val depth = py - top
        if (depth < l.sub) {
            probe.rim = true
            if (l.edge < 0.9) {                                       // a far ridge is one stroke, and the stroke leans the way the ridge runs
                val lean = l.ridge[min(l.n - 1, i + 2)] - l.ridge[max(0, i - 2)]
                probe.pool = if (lean < -l.sub * 0.6) "rise" else if (lean > l.sub * 0.6) "fall" else "flat"; probe.prio = 2
            }
            return l.edge
        }
        if (l.grass && depth < l.cell * 1.6) { probe.pool = "grass"; probe.prio = 1; return l.tone * 0.75 }
        var d = l.tone * (0.35 + 0.65 * n2(probe.ox * l.sub * 0.04, py * 0.11, l.seed))
        val lean = (l.ridge[min(l.n - 1, i + 4)] - l.ridge[max(0, i - 4)]) / (8 * l.sub)     // form: lit where the slope faces the light
        val facing = (lean * (if (home) 1 else -1) * 1.5).coerceIn(-1.0, 1.0)
        d *= 1 - (if (facing > 0) 0.7 else 0.95) * facing * max(0.0, 1 - depth / (0.2 * height))
        if (l.haze > 0) { val left = 1 - depth / (l.haze * height * fs); if (left <= 0) return -1.0; d *= max(0.12, left) }
        return min(0.97, d)
    }

    private var skyTop = 0.0
    private fun skySample(l: Land, i: Int, py: Double): Double {
        val wi = i + l.offI
        probe.pool = "dust"; probe.ox = wi
        if (clouds > 0 && py >= skyTop && py < ((if (home) 0.4 else 0.34) * fs + fo) * height) {
            val k = n2(wi * 0.03, py * 0.1, 99) * 0.7 + n2(wi * 0.1, py * 0.28, 5) * 0.3
            val edge = 1 - 0.42 * clouds
            if (k > edge) { probe.pool = "cloud"; return min(0.42, (k - edge) * 2.2 + 0.08) }
        }
        if (stars > 0 && h2(wi shr 1, floor(py / l.cell).toInt() + 300) > 1 - 0.03 * stars) { probe.lit = true; probe.pool = "star"; return 0.2 + 0.3 * h2(wi, py.toInt()) }
        return 0.0
    }

    /** Ripples and the night, the arrival, the fog and the foot of the page, applied to whatever a layer answered. */
    private fun finish(d0: Double, px: Double, py: Double): Double {
        var d = d0
        for (p in ripples) {
            val dx = px - p.x; val dy = (py - p.y) * 1.4
            if (abs(Math.sqrt(dx * dx + dy * dy) - (tick - p.t) * 15) < 6) { d = if (d > 0.5) 0.06 else 0.9; probe.lit = true }
        }
        if (dark && d > 0.95 && !probe.lit) d = if (probe.rim) 1.0 else 0.0   // at night solid ground is darkness, but its outline stays
        if (f < 1 && f * 1.4 < px / width * 0.4 + h2(px.toInt() shr 3, (py.toInt() shr 3) + 17) * 0.5) return 0.0   // not set yet
        d *= fog
        return d * max(0.0, min(1.0, (height - py) / fade)).pow(1.15)
    }

    /** One cell of a grid: [v] holds its four blocks (-1 where the layer is not). */
    private fun paintCell(g: GlyphSink, c: GlyphColors, x: Double, y: Double, cell: Int, part: String, wx: Int, row: Int, seed: Int, cover: Boolean, red: Boolean) {
        val sub = cell / 2.0
        var mean = 0.0
        var top = 0.0
        var any = false
        for (k in 0..3) if (v[k] >= 0) { any = true; mean += v[k]; if (v[k] > top) top = v[k] }
        if (!any) return
        if (cover) for (k in 0..3) if (v[k] >= 0) g.rect(x + (k and 1) * sub, y + (k shr 1) * sub, sub, sub, c.paper)
        mean /= 4
        if (mean < 0.03) return
        val colour = if (red) c.red else c.ink
        val pool = pools.script(part, wx, row, seed)
        if (top >= 0.9 && (f >= 1 || h2(wx, row + seed + 222) < (f - 0.5) / 0.4)) {     // type first, form after
            for (k in 0..3) if (v[k] >= 0.9) g.rect(x + (k and 1) * sub, y + (k shr 1) * sub, sub, sub, colour)
            if (mean > 0.6) return
        }
        val t = min(1.0, mean / 0.6)
        if (h2(wx, row * 13 + seed + 5) > 0.3 + 0.95 * t) return
        val turn = floor((epoch + h2(wx, row * 5 + seed + 11) * 12) / 12).toInt() + if (f < 1) 3 + floor(f * 7).toInt() * 5 else 0
        val line = pools.poem(pool)
        val said = line?.get(mod(wx + row * 17 + turn * 5, line.size))
        var ch = if (said != null && abs((pools.rank(pool, said) ?: 9.0) - t) < 0.4) said else pools.glyph(pool, t, h2(wx + turn * 7919, row * 7 + seed + 3))
        if (!still && (GlyphPools.SHARES.containsKey(part) || part == "grass") && mod(wx * 0.5 + row * 0.9 - epoch * 2, 70.0) < 2.2) ch = pools.glyph("blade", t, h2(wx, row + 3))
        val loose = if (pool == "pixel") 0.0 else 1.0                // a pixel stays on its grid; only the grace notes are nudged
        g.text(ch, x + sub + loose * (h2(wx, row + seed + 91) - 0.5) * cell * 0.34, y + sub + 0.5 + loose * (h2(wx, row + seed + 57) - 0.5) * cell * 0.3,
            round(cell * GlyphPools.faceScale(pool)), GlyphPools.isMono(pool), colour)
    }

    private fun drawLayer(g: GlyphSink, c: GlyphColors, l: Land, cover: Boolean) {
        f = if (home) homeForm else l.form
        if (f <= 0) return
        val cell = l.cell
        val cols = ceil(width / cell).toInt()
        val isSky = l === sky
        for (r in floor(l.top / cell).toInt() until ceil(l.bottom / cell).toInt()) for (col in 0 until cols) {
            var pool = l.pool
            var best = -1
            var red = false
            for (k in 0..3) {
                val i = col * 2 + (k and 1)
                val py = (r * 2 + (k shr 1)) * l.sub + l.sub / 2
                probe.reset()
                val d = if (isSky) skySample(l, i, py) else sample(l, i, py)
                v[k] = if (d < 0) -1.0 else finish(d, i * l.sub + l.sub / 2, py)
                if (d >= 0 && probe.prio > best) { best = probe.prio; pool = probe.pool }
                if (probe.acc && d >= 0.5) red = true
            }
            paintCell(g, c, (col * cell).toDouble(), (r * cell).toDouble(), cell, pool, col + (l.offI shr 1), r, l.seed, cover, red)
        }
        g.layerEnd()
    }

    // ---- the ground -----------------------------------------------------------------------------------
    /** The field of 山野 with the river taken out and a road put in: one road up to two thirds of the way, then each to its own end. */
    private fun way(ux: Double, row: Int, g: Double, px: Double): Double {
        val sway = sin(g * 2.2 + floor(pos / 6) * 0.05) * 5 * g
        var inside = false
        var rim = false
        for (k in 0..2) {
            val wk = roadWide[k]
            if (wk < 0.03) continue
            val t = ((0.66 - g) / 0.66).coerceIn(0.0, 1.0)
            val cx = width / 2 + sway + (roadEnd[k] - width / 2) * t * t * (3 - 2 * t)
            val half = (7 + 28 * g * g + 12 * g) * wk
            val off = abs(px - cx)
            if (off < half - 4.5) inside = true else if (off < half) rim = true
        }
        if (inside) { probe.pool = "flat"; return 0.01 }
        if (rim) { probe.pool = "flat"; probe.rim = true; return 1.0 }
        probe.pool = if (g < 0.28) "flat" else "kata"
        val slab = 0.74 + 0.5 * g                                    // fewer dark slabs than 山野, only far off: things stand here
        if (n2(ux * 0.011, row * 0.9, 31) > slab) { probe.rim = n2(ux * 0.011, (row - 1) * 0.9, 31) <= slab; return 1.0 }
        val furrow = if (sin(row * 1.25 + n2(ux * 0.006, row * 0.3, 23) * 2.4) > 0.15) 1.0 else 0.22
        return furrow * (0.18 + 0.5 * g) * (0.55 + 0.45 * n2(ux * 0.05, row * 1.3, 17))
    }

    private fun sea(ux: Double, row: Int, g: Double, px: Double): Double {
        probe.pool = "wave"
        if (row == 0) { probe.rim = true; return 1.0 }                // the horizon is one ruled line
        if (g < 0.07) {                                              // far out, a small sail
            val sx = width * 0.84 - px
            val sy = g / 0.07
            if (sx > -7 && sx < 7 && sy < 0.9 && abs(sx) < 7 * sy + 0.5) { probe.rim = true; probe.lit = true; return 1.0 }
        }
        if (abs(px - discX) < 8 + 70 * g && h2(round(ux / 3), row * 3) > 0.52 + 0.3 * g) { probe.lit = true; probe.acc = !dark; return 1.0 }   // the sun on the water, or the moon
        val wave = sin(row * 1.9 + n2(ux * 0.014, row * 0.6, 3) * 6 + if (still) 0.0 else epoch * 0.3)
        return if (wave > 0.45) 0.3 + 0.36 * g else 0.03 + 0.1 * g
    }

    /** Where the pile has got to above the column at [x]: the first glyphs stop there. */
    fun pileTop(x: Double): Double {
        val top = groundY - 14
        return q(height - (height - top) * (pile * 1.5 - h2(x.toInt() shr 3, 77) * 0.5).coerceIn(0.0, 1.0), 4.0)
    }

    private fun drawGround(g: GlyphSink, c: GlyphColors) {
        f = if (home) homeForm else min(1.0, 0.35 + pile * 0.75)
        if (f <= 0 || (!home && pile <= 0)) return
        val span = height - groundY
        var y = groundY
        var row = 0
        while (y < height) {
            val g0 = (y - groundY) / span
            val cell = (round((4 + 5 * g0.pow(1.15)) / 2) * 2).coerceIn(4, 8)
            val sub = cell / 2.0
            val off = round(pos * (0.5 + 2.2 * g0) * 8 / cell)
            val wide = 2.0 - 1.3 * g0
            for (col in 0 until ceil(width / cell).toInt()) {
                if (!home && pile < 1 && y + cell <= pileTop(col * cell.toDouble())) continue
                var red = false
                for (k in 0..3) {
                    val px = (col * 2 + (k and 1)) * sub + sub / 2
                    val py = y + (k shr 1) * sub + sub / 2
                    probe.reset()
                    val ux = (px - width / 2 + off * cell) * wide
                    val gg = (py - groundY) / span
                    v[k] = finish(if (home) sea(ux, row * 2 + (k shr 1), gg, px) else way(ux, row * 2 + (k shr 1), gg, px), px, py)
                    if (probe.acc) red = true
                }
                paintCell(g, c, col * cell.toDouble(), y, cell, probe.pool, col + off, row, 40, true, red)
            }
            y += cell; row++
        }
        g.layerEnd()
    }

    // ---- the sun --------------------------------------------------------------------------------------
    /** While the place is built the disc gathers out of scattered points as it comes up from behind the far range. */
    private fun drawSun(g: GlyphSink, c: GlyphColors) {
        if (sun <= 0) return
        val u = 3.0
        val up = 1 - (1 - rise) * (1 - rise)
        val cy = sunY + (1 - up) * (sunR + 40)
        val colour = GlyphColors.withAlpha(c.red, fog)
        var y = floor((cy - sunR) / u) * u
        while (y < cy + sunR) {
            var x = floor((sunX - sunR) / u) * u
            while (x < sunX + sunR) {
                val dx = x + u / 2 - sunX
                val dy = y + u / 2 - cy
                if (dx * dx + dy * dy <= sunR * sunR && y + u <= farRidgeAt(x)) {
                    val a = (sun * 1.7 - h2(x.toInt(), y.toInt() + 5) * 0.7).coerceIn(0.0, 1.0)
                    if (a > 0) {
                        val away = (1 - a) * (1 - a)
                        g.rect(q(x + (h2(x.toInt() + 91, y.toInt()) - 0.5) * 300 * away, u), q(y + (h2(x.toInt(), y.toInt() + 177) - 0.5) * 220 * away, u), u, u, colour)
                    }
                }
                x += u
            }
            y += u
        }
    }

    /** At the sea the disc is the skin's own: the sun by day, the moon at night, set a block at a time as the place arrives. */
    private fun drawDisc(g: GlyphSink, c: GlyphColors) {
        val u = 3.0
        val colour = GlyphColors.withAlpha(if (dark) c.ink else c.red, fog)
        var y = floor((discY - discR) / u) * u
        while (y < discY + discR) {
            var x = floor((discX - discR) / u) * u
            while (x < discX + discR) {
                val dx = x + u / 2 - discX
                val dy = y + u / 2 - discY
                if ((homeForm >= 1 || h2(x.toInt(), y.toInt() + 5) < (homeForm - 0.25) / 0.6) && dx * dx + dy * dy <= discR * discR) g.rect(x, y, u, u, colour)
                x += u
            }
            y += u
        }
    }

    private fun drawBirds(g: GlyphSink, c: GlyphColors) {
        if (!birds || still) return
        for (k in 0 until if (home) 4 else 3) {
            val x = mod(h2(k, 61) * (width + 60) + epoch * (2.2 + h2(k, 62) * 1.6), width + 60) - 30
            val y = ((0.08 + 0.2 * h2(k, 63)) * fs + fo) * height + sin(epoch * 0.3 + k) * 4
            g.text(if (((epoch + k) shr 1) and 1 == 1) "へ" else "ー", q(x, 2.0), q(y, 2.0), 10, false, c.ink)
        }
        g.layerEnd()
    }

    /** Everything is where the moment puts it: call before anything is asked about the ridge. */
    fun prepare() {
        if (home) { prepare(island); prepare(shore) } else { prepare(far); prepare(mid); prepare(near) }
        prepare(sky)
        ripples.removeAll { tick - it.t >= 7 }
    }

    /** The land and the sky, far to near. What stands on the land (signs, the gate, the cats) is the painter's, drawn after. */
    fun draw(g: GlyphSink, c: GlyphColors) {
        g.rect(0.0, 0.0, width, height, c.paper)
        skyTop = if (home) 214.0 else 330.0
        if (home) {
            if (homeForm <= 0) return
            drawLayer(g, c, sky, false); drawLayer(g, c, island, true); drawDisc(g, c); drawGround(g, c); drawLayer(g, c, shore, true)
        } else {
            sky.form = if (stars > 0 || clouds > 0) 1.0 else 0.0
            drawLayer(g, c, sky, false); drawSun(g, c); drawLayer(g, c, far, true); drawLayer(g, c, mid, true); drawLayer(g, c, near, true); drawGround(g, c)
        }
        if (if (home) homeForm >= 1 else clouds >= 0.5) drawBirds(g, c)
    }
}
