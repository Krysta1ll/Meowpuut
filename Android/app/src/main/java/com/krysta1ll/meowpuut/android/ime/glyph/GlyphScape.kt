package com.krysta1ll.meowpuut.android.ime.glyph

import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise.fbm1
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise.h2
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise.mod
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise.n1
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise.n2
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise.q
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise.round
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Where the picture goes. All coordinates are in points; colours are ARGB. */
internal interface GlyphSink {
    fun rect(x: Double, y: Double, w: Double, h: Double, color: Int)
    /** One glyph, centred on ([cx], [cy]); [size] in points, [mono]: set in the monospaced face. */
    fun text(ch: String, cx: Double, cy: Double, size: Int, mono: Boolean, color: Int)

    /** Makes the block transparent (the seam thins the paper into what is behind the keyboard). */
    fun erase(x: Double, y: Double, w: Double, h: Double) {}

    /** One layer of the picture is finished: a sink that gathers its glyphs to draw them in one go draws them now. */
    fun layerEnd() {}

    /** A picture of pixels (a cat), [scale] points to the pixel, its top left at ([x], [y]). */
    fun raster(r: Raster, x: Double, y: Double, scale: Double) {
        for (py in 0 until r.h) for (px in 0 until r.w) {
            val c = r.pixels[py * r.w + px]
            if (c ushr 24 != 0) rect(x + px * scale, y + py * scale, scale, scale, c)
        }
    }

    /**
     * A mask (type cut to one bit, or hand-cut pixels) in [ink], [unit] points to the pixel, its top left at ([x], [y]). With a
     * [halo] colour the shape is first grown to a clear edge (round for set type, square for hand-cut pixels): the grown picture is
     * then [GlyphKeyArt.reach] pixels larger on every side, and ([x], [y]) is still where the mask itself begins.
     */
    fun bits(mask: GlyphKeyArt.Mask, x: Double, y: Double, unit: Double, ink: Int, halo: Int?, round: Boolean) {
        if (halo != null) {
            val grown = GlyphKeyArt.grown(mask, round)
            val reach = GlyphKeyArt.reach(round)
            for (r in 0 until grown.h) for (k in 0 until grown.w) if (grown[k, r]) rect(x + (k - reach) * unit, y + (r - reach) * unit, unit, unit, halo)
        }
        for (r in 0 until mask.h) for (k in 0 until mask.w) if (mask[k, r]) rect(x + k * unit, y + r * unit, unit, unit, mask.paint(r * mask.w + k, ink))
    }
}

/** A sun or moon, where it is and how large, in points. */
internal class DiscInfo(val x: Double, val y: Double, val r: Double, val sun: Boolean)

internal class GlyphColors(val paper: Int, val ink: Int, val red: Int) {
    companion object {
        val LIGHT = GlyphColors(0xFFECE8DC.toInt(), 0xFF1E1C1A.toInt(), 0xFFC4402A.toInt())
        val DARK = GlyphColors(0xFF151413.toInt(), 0xFFE9E4D6.toInt(), 0xFFE2573A.toInt())
        fun of(dark: Boolean) = if (dark) DARK else LIGHT
        fun withAlpha(color: Int, alpha: Double): Int =
            (round(alpha.coerceIn(0.0, 1.0) * 255) shl 24) or (color and 0xFFFFFF)
    }
}

/** A band of the picture that tears sideways in a glitch, and the band that turns to its negative. */
internal class Tear(val bands: List<Triple<Int, Int, Int>>, val negativeY: Int, val negativeH: Int)

/**
 * The picture itself: layers of land, far to near, each set in its own size of type on its own grid, then the ground, the sky and
 * the air. Depth is drawn the way a painter does it: far things first, small and thin; near things last, large and heavy.
 * A layer answers one block of its grid at a time with a density, or -1 for "not here"; a cell is four blocks, and is either
 * mosaic (outlines and heavy tones) or one glyph. Nothing here knows the screen: it draws into a [GlyphSink].
 */
internal class GlyphScape(private val world: GlyphWorld, private val pools: GlyphPools) {
    private val theme get() = world.theme

    class Layer(val spec: LayerSpec, val cell: Int, val n: Int, val fs: Double, val baseY: Double, level: Int) {
        val sub = cell / 2.0
        var offI = 0
        var amp = spec.amp
        var haze = spec.haze
        var big = if (level >= 2) 2 else 1
        var trees: TreeKind? = spec.trees
        /** The seam: this layer's tallest summits (or towers) go on through the edge. */
        var spire: SpireSpec? = null
        /** The highest point (smallest y) this layer can answer "here" at, worked out with its ridge: rows wholly above it are not sampled. */
        var minY = -1e9
        val sparse = if (spec.sparse != 0.0) spec.sparse - 0.04 * (level - 2) else 0.0
        val ridge = FloatArray(n)
        val capAt = FloatArray(n)
        val treeY = FloatArray(n)
        val treeDx = IntArray(n)
        val treeTall = FloatArray(n)
        val markCol = IntArray(n) { -1 }
        val markTop = FloatArray(n)
        val markPat = arrayOfNulls<List<String>>(n)
        val pool get() = spec.pool
        val seed get() = spec.seed
        val tone get() = spec.tone
        val edge get() = spec.edge
    }

    /** What a sampler says besides the density: which pool the glyph comes from, whether it is lit, accent, a rim, a sign. */
    private class Probe {
        var lit = false; var acc = false; var rim = false; var sign: String? = null
        var prio = 0; var pool = "dust"; var ox = 0
        fun reset() { lit = false; acc = false; rim = false; sign = null; prio = 0 }
    }

    var width = 393.0; private set
    var height = 298.0; private set
    var layout = GlyphLayout.NONE; private set
    private var framing: Framing? = Framing.ROWS
    /** The strip above the keyboard the picture also paints, and how this place meets it. */
    var lift = 0.0; private set
    var inset = 0.0; private set
    /** The picture runs on this far below the keyboard's own height, faintly, under the navigation strip. */
    private var tail = 0.0
    private var discScale = 1.0
    /** How far the picture has come (see [GlyphWorld.formed]), worked out once a frame. */
    var formed = 1.0; private set
    private var seamSpec: SeamSpec? = null
    var preset: SceneSpec = GlyphScenes.ALL[0]; private set
    private var fs = 1.0
    private var fo = 0.0
    private var front: List<Layer> = emptyList()
    private var back: List<Layer> = emptyList()
    private lateinit var sky: Layer
    private lateinit var walkOn: Layer
    private var groundSpec: GroundSpec? = null
    private var groundY = 1e9
    private var groundFirst = false
    private var discX: Double? = null
    private var discY = 0.0
    private var sunSide = 1
    private var discLate = false
    /** Set once the first frame has been prepared: the ridge the cats walk on is known. */
    var ready = false; private set
    var rowsFraming = false; private set

    private val probe = Probe()
    private val v = DoubleArray(4)
    private var quiet: FloatArray? = null
    private var qw = 0
    private var qh = 0
    private var quietKey = ""

    private fun fy(f: Double) = (f * fs + fo) * height

    val skyOffset: Int get() = sky.offI
    val groundTop: Double get() = groundY
    val sceneName: String get() = preset.name

    fun ridgeAt(x: Double): Double {
        val l = walkOn
        return l.ridge[max(0, min(l.n - 1, floor(x / l.sub).toInt()))].toDouble()
    }

    // ---- what the tests look at ---------------------------------------------------------------------------

    /** The layers far to near, as the picture is painted (the order of the prototype's `back` then `front`). */
    internal var layers: List<Layer> = emptyList(); private set
    internal val skyLayer: Layer get() = sky
    internal fun sampleLayer(l: Layer, i: Int, py: Double): Double { probe.reset(); return sample(l, i, py) }
    internal fun sampleSky(i: Int, py: Double): Double { probe.reset(); return skySample(sky, i, py) }
    internal val groundKind: GroundSpec? get() = groundSpec
    internal fun sampleGround(ux: Double, row: Int, g: Double, px: Double): Double { probe.reset(); return ground(groundSpec!!, ux, row, g, px) }
    internal val discPlace: Pair<Double?, Double> get() = discX to discY

    /** The sun or moon the views cut their type out of (x, y, radius in points; whether it is the red sun by day), or null when the place has none. */
    internal fun disc(): DiscInfo? {
        val x = discX ?: return null
        val d = preset.sky.disc ?: return null
        return DiscInfo(x, discY, d.r * height * discScale, d.sun)
    }

    // ---- framing -----------------------------------------------------------------------------------

    /**
     * Where a height given as a part of the picture falls. The keyboard frames each place the way that shows what it is:
     *   rows     the ridge the cats walk on lies in the gap between the first two rows of keys (the cats are as tall as that gap, so
     *            they pass between the keys), and the mountains or the town stand full height behind the first row and the candidates
     *   skyline  the ridge is at the very top and the ground (the sea, the forest floor) runs the whole height below it
     * The first (or a single) row of keys leaves no gap, so every place takes the skyline framing then.
     */
    fun wear(index: Int, forLayout: GlyphLayout) {
        layout = forLayout
        width = forLayout.width; height = forLayout.height
        lift = forLayout.lift
        inset = forLayout.inset
        tail = forLayout.tail
        discScale = 1.0
        val p = GlyphScenes.ALL[index]
        preset = p
        val wanted = p.kb ?: Framing.SKYLINE
        framing = if (wanted == Framing.ROWS && !forLayout.rows) Framing.SKYLINE else wanted
        rowsFraming = framing == Framing.ROWS
        val all = p.front + p.back
        val walk = all[p.walk]
        fs = if (framing == Framing.ROWS) 0.62 else 0.22
        val ridge = if (framing == Framing.ROWS) (forLayout.laneFeet(0) ?: 150.0) - 2 else if (forLayout.horizon > 0) forLayout.horizon else max(forLayout.barTop + 6, forLayout.skylineFloor)
        fo = ridge / height - walk.base * fs
        fun make(l: LayerSpec): Layer {
            val cell = theme.sized(l.cell)
            val n = ceil(width / (cell / 2.0)).toInt() + 2
            return Layer(l, cell, n, fs, fy(l.base), theme.level)
        }
        front = p.front.map(::make)
        back = p.back.map(::make)
        sky = make(LayerSpec(LayerKind.LAND, cell = 6, pool = "dust", seed = 0, speed = 0.05, tone = 0.0))
        walkOn = (front + back)[p.walk]
        layers = back + front
        if (framing == Framing.SKYLINE) {
            for (l in back) { if (l.amp != 0.0) l.amp *= 0.5; if (l !== walkOn) l.trees = null }
            if (front.contains(walkOn)) walkOn.amp *= 0.5
            walkOn.haze = 0.4; walkOn.big = 1
        } else {
            walkOn.amp = 6 / (height * fs); walkOn.haze = 16 / (height * fs); walkOn.big = 1
        }
        groundSpec = p.kbGround ?: p.ground
        groundY = if (groundSpec == null) 1e9 else q(fy(walk.base) + (if (framing == Framing.ROWS) 6 else 10), 4.0)
        groundFirst = framing == Framing.ROWS && p.kbFirst
        val disc = p.sky.disc
        val r = if (disc != null) disc.r * height else 0.0
        discX = disc?.let { it.x * width }
        discY = when {
            disc == null -> 0.0
            disc.crescent -> 14.0
            framing == Framing.SKYLINE -> fy(walk.base) + r * (if (disc.r >= 0.25) 0.58 else 0.2)
            else -> max(fy(disc.y), r + 4.0 - lift)      // the sun of a place framed in rows stays whole (the strip above counts as picture)
        }
        sunSide = if (disc != null && disc.x < 0.5) -1 else 1
        discLate = framing == Framing.SKYLINE
        // The seam: each place meets what is above it in its own way. The safe drawing has none of it.
        seamSpec = if (lift > 0 || inset > 0) GlyphSeam.SEAMS[p.name] else null
        seamSpec?.let { z ->
            if (!theme.opaque) {
                z.spire?.let { back[it.back].spire = it }
                if (z.rise != 0.0 && discX != null) discY -= min(z.rise, lift)
                // Nothing is added above: the new moon is a little smaller, and whole.
                if (inset != 0.0 && discX != null && preset.sky.disc?.crescent == true) { discScale = 0.8; discY = 19.0 }
            }
        }
        ready = false
        quietKey = ""
    }

    // ---- the keyboard's own type thins the land beneath it ----------------------------------------

    /** A thin band of mist lies along each row of keys and, while there are candidates, along the bar. Its edge wanders. */
    fun hush(hasCandidates: Boolean, enabled: Boolean, label: DoubleArray? = null) {
        val key = "$enabled|$hasCandidates|${layout.hashCode()}|${layout.rowCenters.joinToString(",")}|${label?.joinToString(",")}"
        if (key == quietKey) return
        quietKey = key
        if (!enabled) { quiet = null; return }
        val mw = ceil(width / 2).toInt() + 8
        val mh = ceil((height + tail) / 2).toInt() + 8
        val m = FloatArray(mw * mh) { 1f }
        fun band(cy: Double, half: Double, low: Double) {
            val seed = cy.toInt()
            for (x in 0 until mw) {
                val mid = cy + (n1(x * 0.045, seed) - 0.5) * 7
                val reach = half + (n1(x * 0.07, seed + 9) - 0.5) * 5
                for (y in max(0, floor((mid - reach - 10) / 2).toInt())..min(mh - 1, ceil((mid + reach + 10) / 2).toInt())) {
                    val dist = max(0.0, abs(y * 2 + 1 - mid) - reach)
                    val value = if (dist >= 9) 1.0 else low + (1 - low) * (dist / 9).pow(1.4)
                    if (value < m[y * mw + x]) m[y * mw + x] = value.toFloat()
                }
            }
        }
        for (c in layout.rowCenters) band(c, 4.0, 0.62)
        if (hasCandidates) band(layout.barMid, 8.0, 0.3)
        // The bar's label (the input mode) is read like a candidate: the land thins round it, whatever it stands on.
        if (label != null) {
            val xc = (label[0] + label[1]) / 2
            val hw = (label[1] - label[0]) / 2
            for (y in max(0, floor((layout.barMid - 22) / 2).toInt())..min(mh - 1, ceil((layout.barMid + 22) / 2).toInt())) {
                for (x in max(0, floor((xc - hw - 22) / 2).toInt())..min(mw - 1, ceil((xc + hw + 22) / 2).toInt())) {
                    val dx = max(0.0, abs(x * 2 + 1 - xc) - hw - 3)
                    val dy = max(0.0, abs(y * 2 + 1 - layout.barMid) - 9)
                    val dist = hypot(dx, dy)
                    val value = if (dist >= 12) 1.0 else 0.15 + 0.85 * (dist / 12).pow(1.4)
                    if (value < m[y * mw + x]) m[y * mw + x] = value.toFloat()
                }
            }
        }
        quiet = m; qw = mw; qh = mh
    }

    // ---- one frame -------------------------------------------------------------------------------------

    /** Works out where the land lies for the world as it is now: the ridge the cats walk on, the sky's drift. */
    fun prepare() {
        val p = world.pos
        formed = world.formed()
        for (l in front + back + listOf(sky)) {
            l.offI = floor(p * l.spec.speed * 8 / l.cell).toInt() * 2
            if (l !== sky) when (l.spec.kind) {
                LayerKind.LAND -> prepareLand(l)
                LayerKind.SKYLINE -> prepareSkyline(l)
                LayerKind.STEMS -> Unit
            }
        }
        if (theme.level == 3 && !theme.still) sky.offI += 2 * (world.epoch / 5)           // the sky drifts by itself
        ready = true
    }

    fun draw(g: GlyphSink, c: GlyphColors) {
        prepare()
        // The paper lies over the strip above the keyboard too, when there is one.
        g.rect(0.0, -lift, width, height + lift + tail, c.paper)
        drawLayer(g, c, sky, cover = false) { l, i, py -> skySample(l, i, py) }
        if (groundFirst) drawGround(g, c)                        // where the land in front of the ground is what matters: bamboo
        for (i in back.indices.reversed()) {
            if (discLate && back[i] === walkOn) drawDisc(g, c)
            drawLayer(g, c, back[i], cover = true) { l, k, py -> sample(l, k, py) }
        }
        if (discLate && !back.contains(walkOn)) drawDisc(g, c)
        if (!groundFirst) drawGround(g, c)
        for (i in front.indices.reversed()) drawLayer(g, c, front[i], cover = true) { l, k, py -> sample(l, k, py) }
        drawSeam(g, c)
        drawAir(g, c)
        g.layerEnd()
    }

    /** The glitch's bands, for the screen to apply to what has been drawn (see [GlyphWorld.glitch]). */
    fun tear(): Tear? {
        if (world.tick >= world.glitch) return null
        val t = world.tick
        val bands = (0 until 4).map { k ->
            Triple((h2(t, k * 3 + 1) * height).toInt(), (4 + h2(t, k * 3 + 2) * 22).toInt(), ((h2(t, k * 3 + 3) - 0.5) * 64).let { round(it) })
        }
        return Tear(bands, (h2(t, 41) * height).toInt(), (6 + h2(t, 42) * 16).toInt())
    }

    // ---- layers ------------------------------------------------------------------------------------------

    private fun prepareLand(l: Layer) {
        val base = l.baseY
        val amp = l.amp * height * l.fs
        val u = l.cell / 8.0
        val s = l.spec
        for (i in 0 until l.n) {
            val wi = i + l.offI
            val w = wi * l.sub / 4
            var value = when (s.shape) {
                LandShape.PEAKS -> {
                    val t = w * s.freq + s.seed * 0.37
                    val k = floor(t)
                    val f = t - k
                    (1 - abs(2 * f - 1)) * (0.5 + 0.5 * h2(k.toInt(), s.seed + 5)) * 0.85 + 0.15 * fbm1(w * s.freq * 5, s.seed + 2)
                }
                LandShape.ISLAND -> max(0.0, fbm1(w * s.freq, s.seed) - 0.5) * 2
                LandShape.FLAT -> 0.0
                LandShape.HILLS -> fbm1(w * s.freq, s.seed)
            }
            val sp = l.spire
            if (sp != null && value > sp.over) value += (value - sp.over) * sp.gain                        // the seam: the tallest summits go on through the edge
            var y = q(base - amp * value, l.sub)
            if (s.grass) {
                val k = h2(wi, s.seed + 9)
                if (k > 0.93) y -= l.sub * 2 else if (k > 0.72) y -= l.sub
            }
            if (l.spire != null) y = max(y, q(6 - lift, l.sub))                                            // a summit ends inside the strip; the strip's own top never cuts it
            l.ridge[i] = y.toFloat()
            if (s.cap != 0.0) l.capAt[i] = ((0.5 + 0.7 * n1(w * 0.09, s.seed + 31)) * s.cap * height * l.fs).toFloat()
        }
        val marks = s.marks
        if (marks != null) {                                      // one stretch of road in two has something standing on it
            l.markCol.fill(-1)
            val stretch = 190
            for (slot in floorDiv(l.offI - 20, stretch)..floorDiv(l.offI + l.n + 20, stretch)) {
                if (h2(slot, s.seed + 500) < 0.74 - 0.12 * theme.level) continue
                val pat = GlyphScenes.STAMPS.getValue(marks[(h2(slot, s.seed + 502) * marks.size).toInt()])
                val first = slot * stretch + 24 + (h2(slot, s.seed + 501) * 60).toInt() - l.offI
                val big = l.big
                val foot = l.ridge[max(0, min(l.n - 1, first + ((pat[0].length * big) shr 1)))]
                for (k in 0 until pat[0].length * big) {
                    val at = first + k
                    if (at in 0 until l.n) {
                        l.markCol[at] = k / big
                        l.markTop[at] = (foot - pat.size * l.sub * big).toFloat()
                        l.markPat[at] = pat
                    }
                }
            }
        }
        val trees = l.trees
        if (trees != null) {                                      // a tree stands where the world says so; its crown spans a few blocks either side
            l.treeY.fill(1e9f)
            val reach = 3
            for (i in -reach until l.n + reach) {
                val wi = i + l.offI
                if (wi and 1 != 0 || h2(wi shr 1, s.seed + 40) < l.sparse) continue
                val root = l.ridge[max(0, min(l.n - 1, i))]
                val tall = 1 + h2(wi shr 1, s.seed + 41)
                for (k in -reach..reach) {
                    val at = i + k
                    if (at in 0 until l.n) {
                        l.treeY[at] = (root - (if (trees == TreeKind.PINE) 14 + 10 * tall else 16 + 6 * tall) * u).toFloat()
                        l.treeDx[at] = k
                        l.treeTall[at] = tall.toFloat()
                    }
                }
            }
        }
        // The highest point this layer can reach: its ridge, the crowns of its trees, the tops of what stands on its road.
        var top = Float.MAX_VALUE
        for (i in 0 until l.n) {
            if (l.ridge[i] < top) top = l.ridge[i]
            if (l.treeY[i] < 1e8f && l.treeY[i] - 30 * u < top) top = (l.treeY[i] - 30 * u).toFloat()
            if (l.markCol[i] >= 0 && l.markTop[i] < top) top = l.markTop[i]
        }
        l.minY = top - 2.0 * l.cell
    }

    private fun prepareSkyline(l: Layer) {
        val base = l.baseY
        val amp = l.amp * height * l.fs
        val s = l.spec
        for (i in 0 until l.n) {
            val wi = i + l.offI
            val b = floorDiv(wi, s.bw)
            var tall = if (h2(b, s.seed + 3) < 0.16) 0.14 else 0.32 + 0.68 * h2(b, s.seed)
            val sp = l.spire
            if (sp != null && tall > sp.over) tall += (tall - sp.over) * sp.gain                           // the seam: a tower or two
            var y = q(base - amp * tall, l.cell.toDouble())
            if (h2(b, s.seed + 8) > 0.66 && wi - b * s.bw == (s.bw shr 1)) y -= l.cell * 1.5      // an aerial
            if (l.spire != null) y = max(y, q(4 - lift, l.sub))                                            // a tower ends inside the strip
            l.ridge[i] = y.toFloat()
        }
        l.minY = (l.ridge.min() - 2.0 * l.cell)
    }

    private fun floorDiv(a: Int, b: Int) = Math.floorDiv(a, b)

    private fun sample(l: Layer, i: Int, py: Double): Double = when (l.spec.kind) {
        LayerKind.LAND -> sampleLand(l, i, py)
        LayerKind.SKYLINE -> sampleSkyline(l, i, py)
        LayerKind.STEMS -> sampleStems(l, i, py)
    }

    private fun sampleLand(l: Layer, i: Int, py: Double): Double {
        val s = l.spec
        val top = l.ridge[i]
        val u = l.cell / 8.0
        val p = probe
        p.pool = s.pool; p.ox = i + l.offI
        val marks = s.marks
        if (marks != null && l.markCol[i] >= 0) {
            val pat = l.markPat[i]!!
            val row = floor((py - l.markTop[i]) / (l.sub * l.big)).toInt()
            val ch = if (row >= 0 && row < pat.size) pat[row][l.markCol[i]] else '.'
            if (ch == 'X') { p.prio = 3; return 1.0 }
            if (ch == 'R') { p.prio = 3; p.lit = true; p.acc = true; return 1.0 }
            if (ch == 'o') { p.prio = 3; if (theme.dark) { p.lit = true; return 1.0 }; return 0.01 }
        }
        val trees = l.trees
        if (trees != null && l.treeY[i] < 1e8 && py < top) {
            val dx = l.treeDx[i] * l.sub / u
            val dy = (py - l.treeY[i]) / u
            val tall = l.treeTall[i]
            if (trees == TreeKind.PINE) {
                val h = dy + 12 * tall
                if (h >= 0 && dy < 12 && abs(dx) <= h * 0.36 + 1) return 1.0
                if (dx == 0.0 && dy >= 12) return 1.0
            } else {
                val rr = dx * dx + dy * dy
                if (rr <= 26 + 10 * tall) return 1.0              // the heart of the crown is solid
                if (rr <= 81 + 30 * tall) { p.pool = "leaf"; p.prio = 2; return 0.55 }   // round it, leaves
                if (dx == 0.0 && dy > 0) return 1.0
            }
        }
        if (py < top) return -1.0
        val depth = py - top
        if (depth < l.sub) {
            p.rim = true
            if (l.edge < 0.9) {                                   // a far ridge is one stroke, and the stroke leans the way the ridge runs
                val lean = l.ridge[min(l.n - 1, i + 2)] - l.ridge[max(0, i - 2)]
                p.pool = if (lean < -l.sub * 0.6) "rise" else if (lean > l.sub * 0.6) "fall" else "flat"; p.prio = 2
            }
            return l.edge
        }
        if (s.grass && depth < l.cell * 1.6) { p.pool = "grass"; p.prio = 1; return l.tone * 0.75 }
        if (s.cap != 0.0 && depth < l.capAt[i]) return 0.02     // snow lies on the top of a peak
        var d = l.tone * (0.35 + 0.65 * n2(p.ox * l.sub * 0.04, py * 0.11, s.seed))
        if (s.shape != LandShape.FLAT) {                         // form: lit where the slope faces the light, dark where it turns away
            val lean = (l.ridge[min(l.n - 1, i + 4)] - l.ridge[max(0, i - 4)]) / (8 * l.sub)
            val facing = max(-1.0, min(1.0, lean * sunSide * 1.5))
            val near = max(0.0, 1 - depth / (0.2 * height))
            d *= 1 - (if (facing > 0) 0.7 else 0.95) * facing * near
        }
        if (l.haze != 0.0) {
            val left = 1 - depth / (l.haze * height * l.fs)
            if (left <= 0) return -1.0
            d *= max(0.12, left)
        }
        return min(0.97, d)
    }

    private fun sampleSkyline(l: Layer, i: Int, py: Double): Double {
        val s = l.spec
        val top = l.ridge[i]
        if (py < top) return -1.0
        val p = probe
        val wi = i + l.offI
        val wc = wi shr 1
        val storey = floor((py - top) / l.cell).toInt()
        p.pool = s.pool; p.ox = wi
        val flicker = if (h2(wc, storey) < 0.14) (world.epoch shr 3) else 0
        val pane = (wc and 1) != 0 && (storey and 1) != 0 && h2(wc, storey + s.seed * 7 + flicker) > 0.3       // a few windows go on and off
        if (l.tone >= 0.95) {                                    // a dark front, a character in each lit window, now and then a sign down its side
            val b = floorDiv(wi, s.bw)
            if (py - top < l.sub || l.ridge[max(0, i - 1)] > py || l.ridge[min(l.n - 1, i + 1)] > py || wi - b * s.bw == 0) { p.rim = true; return 1.0 }
            val word = GlyphScenes.SIGNS[mod(b, GlyphScenes.SIGNS.size)]
            if (h2(b, s.seed + 21) > 0.5 && wc == ceil(b * s.bw / 2.0).toInt() + 2 && storey >= 1 && storey <= word.length) {
                p.lit = true; p.sign = word[storey - 1].toString(); return 0.6
            }
            if (pane && (i and 1) == 0 && (floor((py - top) / l.sub).toInt() and 1) == 0) {   // a window: a small square, lit at night, a hole by day
                if (theme.dark) { p.lit = true; return 1.0 }
                return 0.01
            }
            return 1.0
        }
        return if (pane) 0.03 else l.tone
    }

    /** Bamboo: thin stems with a gap at each node, a few leaves beside them. */
    private fun sampleStems(l: Layer, i: Int, py: Double): Double {
        val s = l.spec
        val wi = i + l.offI
        val b = floorDiv(wi, s.stride)
        if (h2(b, s.seed + 1) < 0.22) return -1.0
        val o = wi - b * s.stride
        val at = 1 + (h2(b, s.seed) * (s.stride - 3)).toInt()
        val p = probe
        p.pool = s.pool; p.ox = wi
        if (o >= at && o < at + s.w) {
            if (o == at) p.rim = true
            return if (mod(py + h2(b, s.seed + 2) * 40, 5.0 * l.cell) < l.sub) 0.1 else l.tone
        }
        if (abs(o - at) <= 3 && py < height * 0.62 && h2(wi, (py / l.cell).toInt() + s.seed) > 0.86) { p.pool = "blade"; p.prio = 1; return 0.3 + 0.3 * l.tone }
        return -1.0
    }

    private fun skySample(l: Layer, i: Int, py: Double): Double {
        val p = probe
        val P = preset.sky
        val px = i * l.sub + l.sub / 2
        p.pool = "dust"
        val dx0 = discX
        val disc = P.disc
        if (dx0 != null && disc != null && !discLate) {
            val r = disc.r * height * discScale
            val dx = px - dx0
            val dy = py - discY
            if (dx * dx + dy * dy <= r * r && !(disc.crescent && (dx - r * 0.5).pow(2) + (dy + r * 0.15).pow(2) < r * r * 0.85)) {
                p.lit = true; p.acc = disc.sun && !theme.dark; return 1.0
            }
        }
        val wi = i + l.offI
        p.ox = wi
        if (P.clouds != 0.0 && py < fy(P.floor)) {
            val k = n2(wi * 0.03, py * 0.1, 99) * 0.7 + n2(wi * 0.1, py * 0.28, 5) * 0.3
            val edge = 1 - 0.42 * P.clouds
            if (k > edge) { p.pool = "cloud"; return min(0.42, (k - edge) * 2.2 + 0.08) }
        }
        if (P.stars != 0.0 && h2(wi shr 1, floor(py / l.cell).toInt() + 300) > 1 - 0.03 * P.stars) {
            p.lit = true; p.pool = "star"
            return 0.2 + 0.3 * h2(wi + (if (theme.level == 3 && !theme.still) (world.epoch shr 2) * 13 else 0), py.toInt())
        }
        return 0.0
    }

    // ---- the ground in front of the land -------------------------------------------------------------

    /**
     * Drawn in bands that grow from small type at the horizon to large type at the feet, and the nearer a band, the faster it runs.
     * g is 0 at the horizon and 1 at the bottom; ux is the band's own world, in points.
     */
    private fun ground(spec: GroundSpec, ux: Double, row: Int, g: Double, px: Double): Double {
        val p = probe
        when (spec.kind) {
            GroundKind.PLAIN -> {
                p.pool = if (g < 0.28) "flat" else spec.pool          // far off, the ground is only dashes
                val furrow = if (sin(row * 1.25 + n2(ux * 0.006, row * 0.3, 23) * 2.4) > 0.15) 1.0 else 0.22   // furrows across the field
                return furrow * (0.13 + 0.3 * g) * (0.55 + 0.45 * n2(ux * 0.05, row * 1.3, 17))
            }
            GroundKind.SEA -> {
                p.pool = "wave"
                if (row == 0) { p.rim = true; return 1.0 }          // the horizon is one ruled line
                if (g < 0.07) {                                      // far out, a small sail that keeps its place as the shore goes by
                    val sx = width * 0.84 - px
                    val sy = g / 0.07
                    if (sx > -7 && sx < 7 && sy < 0.9 && abs(sx) < 7 * sy + 0.5) { p.rim = true; p.lit = true; return 1.0 }
                }
                val dx = discX
                if (dx != null && abs(px - dx) < 8 + 70 * g && h2(round(ux / 3), row * 3) > 0.52 + 0.3 * g) { p.lit = true; p.acc = !theme.dark; return 1.0 }   // the sun on the water, or the moon
                val drift = if (theme.level >= 2 && !theme.still) world.epoch * (if (theme.level == 3) 0.6 else 0.3) else 0.0
                val wave = sin(row * 1.9 + n2(ux * 0.014, row * 0.6, 3) * 6 + drift)
                return if (wave > 0.45) 0.3 + 0.36 * g else 0.03 + 0.1 * g
            }
            GroundKind.STREET -> {                                   // the road: a kerb, a broken line down the middle, a little grit
                p.pool = "flat"
                if (row == 0) { p.rim = true; return 1.0 }
                if (abs(g - 0.565) < 0.03 && (floor(ux / 36).toInt() and 1) != 0) { p.rim = true; return 1.0 }
                val along = mod(ux, 900.0)                           // a zebra crossing every so often
                if (along < 110 && g > 0.2 && g < 0.72 && (floor(g * 22).toInt() and 1) != 0) { p.lit = true; return 0.56 }
                if (g < 0.36 && h2(round(ux / 7), row * 3) > 0.9) { p.lit = true; p.pool = "star"; return 0.5 }   // lights on the wet road
                return if (h2(round(ux / 5), row) > 0.9) 0.4 else 0.05 + 0.1 * g
            }
            GroundKind.FLOOR -> {                                    // under the bamboo: fallen leaves, patches of shade
                p.pool = "blade"
                if (n2(ux * 0.01, row * 0.8, 31) > 0.72) return 0.3 + 0.16 * g
                return if (h2(round(ux / 6), row + 7) > 0.9) 0.26 else 0.02
            }
            GroundKind.SNOW -> {                                     // a field of drifts: wind-lines, a soft shadow here and there
                p.pool = if (g < 0.3) "flat" else "wave"
                if (n2(ux * 0.009, row * 0.7, 31) > 0.68) return 0.5 + 0.2 * g
                return if (sin(row * 2.3 + n2(ux * 0.02, row * 0.5, 9) * 5) > 0.55) 0.22 + 0.2 * g else 0.04
            }
        }
    }

    private fun drawGround(g: GlyphSink, c: GlyphColors) {
        val spec = groundSpec ?: return
        val span = height - groundY
        var y = groundY
        var row = 0
        val level = theme.level
        val least = intArrayOf(8, 6, 4, 4)[level]
        val most = intArrayOf(12, 10, 8, 8)[level]
        while (y < height + tail) {
            val g0 = min(1.0, (y - groundY) / span)
            val cell = max(least, min(most, round((least + (most - least + 1) * g0.pow(1.15)) / 2) * 2))
            val sub = cell / 2.0
            val off = round(world.pos * (0.5 + 2.2 * g0) * 8 / cell)
            val wide = 2.0 - 1.3 * g0
            val cols = ceil(width / cell).toInt()
            for (col in 0 until cols) {
                var red = false
                for (k in 0 until 4) {
                    val px = (col * 2 + (k and 1)) * sub + sub / 2
                    val py = y + (k shr 1) * sub + sub / 2
                    probe.reset()
                    v[k] = finish(ground(spec, (px - width / 2 + off * cell) * wide, row * 2 + (k shr 1), min(1.0, (py - groundY) / span), px), px, py)
                    if (probe.acc) red = true
                }
                paintCell(g, c, col * cell.toDouble(), y, cell, probe.pool, col + off, row, 40, true, red, null)
            }
            y += cell; row++
        }
        g.layerEnd()
    }

    // ---- the seam: where the keyboard meets whatever is above it -------------------------------------------

    /** Whether the place itself covers the point (so the thinning leaves it): a layer answers "here", or it is inside the disc. */
    internal fun kept(px: Double, py: Double): Boolean {
        val d = preset.sky.disc
        val dx0 = discX
        if (d != null && dx0 != null) {
            val r = d.r * height * discScale
            val dx = px - dx0
            val dy = py - discY
            if (dx * dx + dy * dy <= r * r && !(d.crescent && (dx - r * 0.5).pow(2) + (dy + r * 0.15).pow(2) < r * r * 0.85)) return true
        }
        val reach = lift + inset
        for (l in layers) {
            val i = max(0, min(l.n - 1, floor(px / l.sub).toInt()))
            // each stem of bamboo ends at its own height above the edge
            if (l.spec.kind == LayerKind.STEMS && py < inset && py - inset < -reach * (0.15 + 0.85 * h2(floorDiv(i + l.offI, l.spec.stride), l.spec.seed + 77))) continue
            if (sampleLayer(l, i, py) >= 0) return true
        }
        return false
    }

    /**
     * The blocks the seam takes away, as the picture draws them: the paper is not ended in a line but thinned out in the picture's own
     * pixels. With a strip above the keys ([lift]) it thins up into the strip; with [inset] (no height added) it thins inside the keyboard's
     * own top, the edge standing [inset] points down.
     */
    internal fun drawSeam(g: GlyphSink, c: GlyphColors) {
        val z = seamSpec ?: return
        if ((lift <= 0 && inset <= 0) || theme.opaque) return
        val u = GlyphSeam.BLOCK
        val cols = ceil(width / u).toInt()
        val reach = lift + inset
        val band = z.band * (1 + 0.6 * (1 - world.fog) + 0.9 * (1 - formed))   // in the fog between two places, and while arriving, the edge loosens further
        val drift = sky.offI * sky.sub
        val inner = z.inner * (if (lift > 0) 1.0 else 0.5)
        val first = -(lift / u).toInt()
        for (bx in 0 until cols) {
            val px = bx * u + u / 2
            val bot = inner + (if (z.wander != 0.0) (fbm1((px + drift) * 0.03, 77) - 0.5) * 2 * z.wander else 0.0)
            val top = max(-reach, bot - band)
            var by = first
            while (by * u < inset + bot) {
                val py = by * u + u / 2
                if ((py - inset - top) / (bot - top) < GlyphSeam.grain(z.grain, bx, by) && !kept(px, py)) g.erase(px - u / 2, py - u / 2, u, u)
                by++
            }
        }
        if (z.leak != 0.0) {                                         // a few stars have got out
            val off = floor(drift / u).toInt()
            for (bx in 0 until cols) for (by in first until (inset / u).toInt()) {
                if (h2(bx + off, by + 700) < 0.016 * z.leak * (1 + ((by + 0.5) * u - inset) / reach)) g.rect(bx * u, by * u, u, u, c.ink)
            }
        }
    }

    // ---- the sun or the moon, and what moves in the air ----------------------------------------------

    private fun drawDisc(g: GlyphSink, c: GlyphColors) {
        val dx0 = discX ?: return
        val P = preset.sky.disc ?: return
        val r = P.r * height * discScale
        val u = 3.0
        val color = GlyphColors.withAlpha(if (P.sun && !theme.dark) c.red else c.ink, world.fog)
        var y = floor((discY - r) / u) * u
        while (y < discY + r) {
            var x = floor((dx0 - r) / u) * u
            while (x < dx0 + r) {
                val dx = x + u / 2 - dx0
                val dy = y + u / 2 - discY
                if ((formed >= 1 || h2(x.toInt(), y.toInt() + 5) < (formed - 0.25) / 0.6) && dx * dx + dy * dy <= r * r &&
                    !(P.crescent && (dx - r * 0.5).pow(2) + (dy + r * 0.15).pow(2) < r * r * 0.85)) g.rect(x, y, u, u, color)
                x += u
            }
            y += u
        }
    }

    /**
     * Nothing here is stored: where each bird, flake or leaf is follows from the turn of the type (world.epoch), so they move when
     * the type does and stop when it stops.
     */
    private fun drawAir(g: GlyphSink, c: GlyphColors) {
        val P = preset.sky
        val t = world.epoch
        val many = intArrayOf(0, 0, 1, 2)[theme.level]
        if (theme.still) return
        if (many > 0) {
            if (theme.dark) {                                        // at night, now and then, a falling star
                val k = floorDiv(t, 34)
                val a = t - k * 34
                if (a < 5 && h2(k, 88) < 0.6) {
                    val x0 = h2(k, 89) * width * 0.7
                    val y0 = 3 + h2(k, 90) * 10
                    for (i in 0 until 7) g.rect(q(x0 + a * 16 - i * 4, 2.0), q(y0 + a * 4 - i, 2.0), if (i != 0) 3.0 else 4.0, 2.0, c.ink)
                }
            }
            if (P.birds != 0) {
                for (k in 0 until P.birds * many) {
                    val x = ((h2(k, 61) * (width + 60) + t * (2.2 + h2(k, 62) * 1.6)) % (width + 60)) - 30
                    val y = fy(0.08 + 0.2 * h2(k, 63)) + sin(t * 0.3 + k) * 4
                    g.text(if (((t + k) shr 1) and 1 != 0) "へ" else "ー", q(x, 2.0), q(y, 2.0), 10, false, c.ink)
                }
            }
            val fall = P.fall
            if (fall != null) {
                val top = height * 0.86
                for (k in 0 until 22 * many) {
                    val speed = 0.6 + 0.8 * h2(k, 71)
                    val x = mod(mod(h2(k, 72) * width + t * fall.dx * speed * 3, width) + width, width)
                    val y = mod(mod(h2(k, 73) * top + t * fall.dy * speed * 3, top) + top, top) - lift      // it falls in from above the edge
                    g.text(fall.glyphs[k % fall.glyphs.length].toString(), q(x, 2.0), q(y, 2.0), 8, false, c.ink)
                }
            }
        }
        spectacle(g, c)
        g.layerEnd()
    }

    /** The storm of type, the firework or the flock, and the birds on the candidates (see [GlyphSpectacle]): all in the world's own type, in its own grain. */
    private fun spectacle(g: GlyphSink, c: GlyphColors) {
        val sp = world.spectacle
        if (!sp.on) return
        val pool = (preset.back + preset.front).firstOrNull()?.pool ?: "dust"
        val ground = ridgeAt(width / 2)
        for (f in sp.storm(width, max(60.0, ground + 30)) { k -> pools.glyph(pool, 0.5, h2(k, world.epoch / 4 + 9)) }) g.text(f.ch, q(f.x, 2.0), q(f.y, 2.0), f.size, false, if (f.red) c.red else c.ink)
        for (f in sp.burst(theme.dark, width, ground, "｜", "＊゜・゛☆", "へ")) g.text(f.ch, q(f.x, 2.0), q(f.y, 2.0), f.size, false, if (f.red) c.red else c.ink)
        if (preset.sky.birds > 0) for (f in sp.perched()) g.text(f.ch, q(f.x, 2.0), q(f.y, 2.0), f.size, false, c.ink)
    }

    // ---- cells ---------------------------------------------------------------------------------------------

    /** Ripples and the night, applied to whatever a layer answered. */
    private fun finish(density: Double, px: Double, py: Double): Double {
        var d = density
        val p = probe
        val ripples = world.ripples
        for (n in 0 until ripples.size) {                         // a key press runs out through the land as a ring
            val r = ripples[n]
            val dist = hypot(px - r.x, (py - r.y) * 1.4)
            val ring = (world.tick - r.t) * 15
            if (abs(dist - ring) < 6) { d = if (d > 0.5) 0.06 else 0.9; p.lit = true }
        }
        if (theme.dark && d > 0.95 && !p.lit) d = if (p.rim) 1.0 else 0.0      // at night solid ground is darkness, but its outline stays, and so do lights
        if (formed < 1 && formed * 1.4 < px / width * 0.4 + h2(px.toInt() shr 3, (py.toInt() shr 3) + 17) * 0.5) return 0.0   // arriving: not set yet
        d *= world.fog                                            // near a border the land dissolves into its own type, then into nothing
        val fade = FADE
        if (tail <= 0 && py > height - fade) d *= (max(0.0, min(1.0, (height - py) / fade))).pow(1.15)   // and at the keyboard's bottom edge
        val m = quiet
        return if (m != null && !p.lit) d * m[max(0, min(qh - 1, py.toInt() shr 1)) * qw + min(qw - 1, px.toInt() shr 1)] else d   // and it thins round the keyboard's own type
    }

    private fun drawLayer(g: GlyphSink, c: GlyphColors, l: Layer, cover: Boolean, sampler: (Layer, Int, Double) -> Double) {
        val cell = l.cell
        val sub = l.sub
        val rows = ceil((height + tail) / cell).toInt()
        val cols = ceil(width / cell).toInt()
        val p = probe
        // Rows wholly above everything the layer has are skipped: nothing there is the layer's.
        val firstRow = max(-ceil(lift / cell).toInt(), if (l.spec.kind == LayerKind.STEMS || l === sky) Int.MIN_VALUE else floor(l.minY / cell).toInt() - 1)
        for (r in firstRow until rows) for (col in 0 until cols) {
            var pool = l.pool
            var best = -1
            var red = false
            var sign: String? = null
            for (k in 0 until 4) {
                val i = col * 2 + (k and 1)
                val py = (r * 2 + (k shr 1)) * sub + sub / 2
                p.reset()
                val d = sampler(l, i, py)
                v[k] = if (d < 0) -1.0 else finish(d, i * sub + sub / 2, py)
                if (d >= 0 && p.prio > best) { best = p.prio; pool = p.pool }
                if (p.acc && d >= 0.5) red = true
                if (p.sign != null) sign = p.sign
            }
            paintCell(g, c, col * cell.toDouble(), r * cell.toDouble(), cell, pool, col + (l.offI shr 1), r, l.seed, cover, red, sign)
        }
        g.layerEnd()
    }

    /**
     * One cell of a grid: [v] holds its four blocks (-1 where the layer is not). What is behind is covered, then the cell is either
     * mosaic (outlines and heavy tones) or one glyph. Pale areas leave cells empty and nudge the glyphs, so the type scatters.
     */
    private fun paintCell(
        g: GlyphSink, c: GlyphColors, x: Double, y: Double, cell: Int, part: String, wx: Int, row: Int, seed: Int,
        cover: Boolean, red: Boolean, sign: String?,
    ) {
        val sub = cell / 2.0
        var mean = 0.0
        var top = 0.0
        var any = false
        for (k in 0 until 4) if (v[k] >= 0) { any = true; mean += v[k]; if (v[k] > top) top = v[k] }
        if (!any) return
        // What is behind is covered: one block if the cell is all the layer's, else block by block.
        if (cover) {
            if (v[0] >= 0 && v[1] >= 0 && v[2] >= 0 && v[3] >= 0) g.rect(x, y, cell.toDouble(), cell.toDouble(), c.paper)
            else for (k in 0 until 4) if (v[k] >= 0) g.rect(x + (k and 1) * sub, y + (k shr 1) * sub, sub, sub, c.paper)
        }
        mean /= 4
        if (mean < 0.03) return
        val ink = if (red) c.red else c.ink
        val pool = pools.script(part, wx, row, seed)
        val faceOf = if (sign != null) "kata" else pool
        val size = round(cell * GlyphPools.faceScale(faceOf))
        val mono = GlyphPools.isMono(faceOf)
        if (sign != null) { g.text(sign, x + sub, y + sub + 0.5, size, mono, ink); return }
        if (top >= 0.9 && (formed >= 1 || h2(wx, row + seed + 222) < (formed - 0.5) / 0.4)) {          // arriving: type first, form after
            for (k in 0 until 4) if (v[k] >= 0.9) g.rect(x + (k and 1) * sub, y + (k shr 1) * sub, sub, sub, ink)
            if (mean > 0.6) return                                  // a cell that is mostly solid is only solid
        }
        val t = min(1.0, mean / 0.6)
        if (h2(wx, row * 13 + seed + 5) > doubleArrayOf(0.12, 0.22, 0.3, 0.44)[theme.level] + 0.95 * t) return
        val turn = floorDiv(world.epoch + (h2(wx, row * 5 + seed + 11) * 12).toInt(), 12) + (if (formed < 1) 3 + floor(formed * GlyphWorld.FORM).toInt() * 5 else 0)
        val e = world.echo
        var ch: String
        if (pool == "pinyin" && e != null && world.tick - e.t < 30 && h2(wx + e.n * 31, row + seed) < 0.14) ch = e.ch
        else {
            val line = pools.poem(pool)
            val said = line?.get(mod(wx + row * 17 + turn * 5, line.size))
            val rank = if (said != null) pools.rank(pool, said) else null
            ch = if (said != null && rank != null && abs(rank - t) < 0.4) said else pools.glyph(pool, t, h2(wx + turn * 7919, row * 7 + seed + 3))
        }
        var color = ink
        if (theme.level >= 2 && !theme.still) {
            // Wind: a slanting band crosses the land now and then, and the type under it leans.
            if ((GlyphPools.SHARES.containsKey(part) || part == "grass") && mod(wx * 0.5 + row * 0.9 - world.epoch * (2 + 3 * world.spectacle.excite), 70.0 - 36 * world.spectacle.excite) < 2.2 + 3.0 * world.spectacle.excite) ch = pools.glyph("blade", t, h2(wx, row + 3))
            if (world.tick < world.glitch && h2(wx + world.tick * 7, row * 3 + seed) < 0.1) {
                ch = pools.moji[(h2(wx, world.tick + row) * pools.moji.size).toInt()]; color = c.red
            }
        }
        val loose = if (pool == "pixel") 0.0 else 1.0                // a pixel stays on its grid; only the grace notes are nudged
        g.text(ch, x + sub + loose * (h2(wx, row + seed + 91) - 0.5) * cell * 0.34, y + sub + 0.5 + loose * (h2(wx, row + seed + 57) - 0.5) * cell * 0.3, size, mono, color)
    }

    companion object {
        /** The picture fades out over this many points at the bottom edge. */
        const val FADE = 46.0
        /** The skyline framing's ridge is never higher than this: the cats stand on it and are thirty points tall. */
        const val MIN_SKYLINE_RIDGE = 46.0
    }
}
