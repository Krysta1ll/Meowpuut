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

    /** A picture the sink holds ([picture]: what it can draw, a layer's strip) laid in at [dx] device pixels from the left, its top at picture height [y] points. */
    fun blit(picture: Any, dx: Int, y: Double) {}

    /** [text] in a weight of the face (400 regular, 500, 700 heavy): a sink with no weights draws it regular. */
    fun textW(ch: String, cx: Double, cy: Double, size: Int, mono: Boolean, color: Int, weight: Int) = text(ch, cx, cy, size, mono, color)

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
internal class DiscInfo(val x: Double, val y: Double, val r: Double, val sun: Boolean, val earth: Boolean = false, val hue: Int = 0)

internal class GlyphColors(val paper: Int, val ink: Int, val red: Int) {
    companion object {
        val LIGHT = GlyphColors(0xFFECE8DC.toInt(), 0xFF1E1C1A.toInt(), 0xFFC4402A.toInt())
        val DARK = GlyphColors(0xFF151413.toInt(), 0xFFE9E4D6.toInt(), 0xFFE2573A.toInt())
        fun of(dark: Boolean) = if (dark) DARK else LIGHT
        fun withAlpha(color: Int, alpha: Double): Int =
            (round(alpha.coerceIn(0.0, 1.0) * 255) shl 24) or (color and 0xFFFFFF)
    }
}

/** Nothing above [minY]: the picture's strip above the keyboard is not for what is near. */
internal class GlyphTopClip(val g: GlyphSink, val minY: Double) : GlyphSink {
    override fun rect(x: Double, y: Double, w: Double, h: Double, color: Int) {
        if (y + h <= minY) return
        if (y < minY) g.rect(x, minY, w, y + h - minY, color) else g.rect(x, y, w, h, color)
    }
    override fun text(ch: String, cx: Double, cy: Double, size: Int, mono: Boolean, color: Int) = g.text(ch, cx, cy, size, mono, color)
}

/** Nothing below [maxY]: the ground that lies there is drawn live, under the picture, and what is drawn before it must not show through over it. */
internal class GlyphBottomClip(val g: GlyphSink, val maxY: Double) : GlyphSink {
    override fun rect(x: Double, y: Double, w: Double, h: Double, color: Int) {
        if (y >= maxY) return
        if (y + h > maxY) g.rect(x, y, w, maxY - y, color) else g.rect(x, y, w, h, color)
    }
    override fun text(ch: String, cx: Double, cy: Double, size: Int, mono: Boolean, color: Int) = g.text(ch, cx, cy, size, mono, color)
}

/** What is nearest, as the views that draw it live need it: the place, the picture's size, the light, and how much of the motion is on. */
internal class GlyphNear(val scene: String, val width: Double, val height: Double, val lift: Double, val dark: Boolean, val still: Boolean, val level: Int) {
    /** Something passes in front of the place at all: not with the lightest setting, and not with the system's reduced motion. */
    val passes: Boolean get() = level >= 1 && !still && GlyphDepthFore.hasFore(scene)
    fun state(clockMs: Double, keyAtMs: Double) = GlyphDepthFore.State(scene, width, height, lift, dark, still, clockMs, keyAtMs, !still)
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

    class Layer(
        val spec: LayerSpec, val cell: Int, val n: Int, val fs: Double, val baseY: Double, level: Int,
        /** The size of one block of the layer's grid, and how many of them make a side of a cell (two in the first version; with depth the block is a fine pixel). */
        val sub: Double = cell / 2.0, val m: Int = 2,
        /** How far toward the paper this plane's ink goes (0 for the near ones). */
        val air: Double = 0.0,
        deep: Boolean = false,
    ) {
        var offI = 0
        /** Drawn into a strip of its own (see [GlyphScape.layerBlit]): where it lies is set by whoever draws the strip, not by the world's position. */
        var pinned = false
        /**
         * How large a cell of a landmark's picture is, in points: [sub] times [big] (with depth 2 x 2: four points, at every setting, as the prototype has it).
         */
        fun stamp(): Double = sub * big
        var amp = spec.amp
        var haze = spec.haze
        var big = if (level >= 2 || deep) 2 else 1                  // with depth, a landmark is four points to the cell at every setting (the prototype's)
        var trees: TreeKind? = spec.trees
        /** The seam: this layer's tallest summits (or towers) go on through the edge. */
        var spire: SpireSpec? = null
        /** The highest point (smallest y) this layer can answer "here" at, worked out with its ridge: rows wholly above it are not sampled. */
        var minY = -1e9
        val sparse = if (spec.sparse != 0.0) spec.sparse - 0.04 * (level - 2) else 0.0
        val ridge = FloatArray(n)
        /** CONE: where each block lies against the mountain's axis; DUNES: whether it is the short face (1) or the long back (0). */
        val apex = FloatArray(n)
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
        /** A thing, not the ground: a landmark or the one mountain's outline. The mist round the keyboard's type does not thin it. */
        var hard = false
        /** Drawn in pixels, with no type among them (a crown of leaves). */
        var bare = false
        var prio = 0; var pool = "dust"; var ox = 0
        /** The place's own ink for this block (ARGB), or 0 for the vermilion (when [acc]) or the ink. */
        var hue = 0
        fun reset() { lit = false; acc = false; rim = false; sign = null; prio = 0; hue = 0; hard = false; bare = false }
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
    /** A phone's keyboard, shorter than the prototype's: some places are drawn a little differently (see [SceneSpec.fit]). */
    private var short = false
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
    /** The Earth stands behind everything, so it is drawn first. */
    private var early = false
    /** Set once the first frame has been prepared: the ridge the cats walk on is known. */
    var ready = false; private set
    var rowsFraming = false; private set
    /** A stretch of the picture (left to right, in points) where the places' own stamps (a torii, a lamp, a tower) are left out: a festival stands there. */
    var clearMarks: ClosedFloatingPointRange<Double>? = null

    private val probe = Probe()
    private val v = DoubleArray(144)
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
        return DiscInfo(x, discY, d.r * height * discScale, d.sun, d.earth, hue(d.hue))
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
        val p = GlyphScenes.ALL[index]
        preset = p
        val wanted = p.kb ?: Framing.SKYLINE
        framing = if (forLayout.wide) Framing.WIDE else if (wanted == Framing.ROWS && !forLayout.rows) Framing.SKYLINE else wanted
        rowsFraming = framing == Framing.ROWS
        val all = p.front + p.back
        val walk = all[p.walk]
        val ridge = if (framing == Framing.ROWS) (forLayout.laneFeet(0) ?: 150.0) - 2 else if (forLayout.horizon > 0) forLayout.horizon else max(forLayout.barTop + 6, forLayout.skylineFloor)
        // The prototype's keyboard has about thirty points more sky above its cats than a phone's candidate bar leaves: where the ridge is lower
        // than that, the tall places are drawn a little lower and smaller (see SceneSpec.fit). The prototype's own layout is drawn as it draws it.
        short = framing == Framing.ROWS && ridge < PROTOTYPE_RIDGE - 10
        fs = if (framing == Framing.WIDE) 1.0 else (if (framing == Framing.ROWS) 0.62 else 0.22) * (if (short) p.fit else 1.0)
        discScale = if (short) p.discFit else 1.0
        fo = if (framing == Framing.WIDE) 0.0 else ridge / height - walk.base * fs
        fun make(l: LayerSpec): Layer {
            val cell = theme.sized(l.cell)
            val fine = theme.deep && cell % FINE.toInt() == 0
            val sub = if (fine) FINE else cell / 2.0
            val m = (cell / sub).toInt()
            val n = ceil((width + (if (theme.deep) STRIP_MARGIN_CELLS * cell else 0)) / sub).toInt() + 2 + (if (m > 2) m else 0)
            val speed = l.speed
            val air = if (!theme.deep || l.pool == "dust" || speed >= 0.45) 0.0 else if (speed >= 0.15) AIR_MID else AIR_FAR   // planes: the near one as it is, the middle a little paler, the far one more
            return Layer(l, cell, n, fs, fy(l.base), theme.level, sub, m, air, theme.deep)
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
        } else if (framing == Framing.ROWS) {
            walkOn.amp = 6 / (height * fs); walkOn.haze = 16 / (height * fs); walkOn.big = 1
        }
        val spec = if (framing == Framing.WIDE) p.ground else p.kbGround ?: p.ground
        groundSpec = spec
        groundY = if (spec == null) 1e9 else if (framing == Framing.WIDE) q(fy(spec.top), 4.0) else q(fy(walk.base) + (if (framing == Framing.ROWS) 6 else 10), 4.0)
        groundFirst = framing == Framing.ROWS && p.kbFirst
        val disc = p.sky.disc
        val r = if (disc != null) disc.r * height else 0.0
        discX = disc?.let { (if (short && p.discKbX > 0.0) p.discKbX else it.x) * width }
        discY = when {
            disc == null -> 0.0
            disc.crescent && framing != Framing.WIDE -> 14.0
            framing == Framing.SKYLINE -> fy(walk.base) + r * (if (disc.r >= 0.25) 0.58 else 0.2)
            else -> if (short) max(fy(disc.y), r + 4.0 - lift) else fy(disc.y)      // on a phone the sun of a place framed in rows stays whole (the strip above counts as picture)
        }
        sunSide = if (disc != null && disc.x < 0.5) -1 else 1
        early = disc?.earth == true
        discLate = framing == Framing.SKYLINE || early
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
            if (l.pinned) continue                                            // (its strip has set where it lies)
            l.offI = floor(p * l.spec.speed * 8 / l.cell).toInt() * l.m
            if (l !== sky) when (l.spec.kind) {
                LayerKind.LAND -> prepareLand(l)
                LayerKind.SKYLINE -> prepareSkyline(l)
                LayerKind.STEMS -> Unit
            }
        }
        if (theme.level == 3 && !theme.still) sky.offI += sky.m * (world.epoch / 5)       // the sky drifts by itself
        ready = true
    }

    /** The cache of drawn layers (see [GlyphLayerStrips]): puts a layer it has drawn into the picture and says so, or false for it to be drawn here. */
    var layerBlit: ((Layer, GlyphSink) -> Boolean)? = null

    /** Every layer is laid out by the world's position again. */
    internal fun unpin() { for (l in front + back + listOf(sky)) l.pinned = false }

    /** The layers a strip can hold, far to near: the land and the sky that move with the mileage, not the one mountain that stays where it stands, nor a sky with a sun in it. */
    internal fun strippableLayers(): List<Layer> {
        if (!theme.deep || theme.opaque || theme.still || theme.level !in 1..2) return emptyList()
        val all = listOf(sky) + back.reversed() + front.reversed()
        return all.filter { it.spec.shape != LandShape.CONE && !(it === sky && discX != null && !discLate) && !(it !== sky && it.spec.kind == LayerKind.STEMS) }
    }

    /** The rows (points, picture coordinates) of a layer that can be seen: from the highest it can reach to where land in front of it takes over. */
    internal fun stripBand(l: Layer): Pair<Double, Double> {
        val reach = when (l.spec.kind) {
            LayerKind.SKYLINE -> l.spec.amp * height * l.fs * 3.6 + 40.0
            else -> l.spec.amp * height * l.fs + 150.0
        }
        val top = if (l === sky) -lift else max(-lift, l.baseY - reach)
        val later = laterOf(l).filter { it.haze == 0.0 && it.spec.kind != LayerKind.STEMS }
        var bottom = height + tail
        if (l !== sky && l.baseY.isFinite()) {
            // Land that comes after covers it from its own ridge down: the highest of those bases is where this one stops being seen.
            var cut = if (liveGroundActive() && later.none { true } && front.contains(l).not()) groundY else Double.MAX_VALUE
            for (o in later) cut = min(cut, o.baseY)
            if (!groundFirst && groundSpec != null && !front.contains(l)) cut = min(cut, groundY)
            if (cut < Double.MAX_VALUE / 2) bottom = min(bottom, cut + 2 * l.cell)
        } else if (l === sky) {
            var cut = min(groundY, Double.MAX_VALUE)
            for (o in later) cut = min(cut, o.baseY)
            if (cut < 1e8) bottom = min(bottom, cut + 2 * l.cell)
        }
        return top to max(top + l.cell, bottom)
    }

    /** The layers drawn after [l]. */
    internal fun laterOf(l: Layer): List<Layer> = when {
        l === sky -> front + back
        back.contains(l) -> front + back.subList(0, back.indexOf(l))
        else -> emptyList()
    }

    /** Lays [l] out as it is [off] cells into the world, and draws its cells [colFrom] until [colTo] (and rows within its band) into [g]. */
    internal fun renderStrip(l: Layer, g: GlyphSink, c: GlyphColors, off: Int, colFrom: Int, colTo: Int, band: Pair<Double, Double>) {
        l.pinned = true
        l.offI = off * l.m
        formed = world.formed()
        if (l !== sky) when (l.spec.kind) {
            LayerKind.LAND -> prepareLand(l)
            LayerKind.SKYLINE -> prepareSkyline(l)
            LayerKind.STEMS -> Unit
        }
        val limit = hiddenBelow(l, laterOf(l), groundFirst = if (liveGroundActive()) false else if (l === sky) null else groundFirst)
        val deepMist = theme.deep && (if (back.contains(l)) back.indexOf(l) < back.size - 1 else front.contains(l) && back.isNotEmpty())
        val first = floor(band.first / l.cell).toInt()
        val last = ceil(band.second / l.cell).toInt()
        if (deepMist) mist(g, c, l, colFrom * l.m, min(l.n, colTo * l.m))
        if (l === sky) drawLayer(g, c, sky, cover = false, rowLimit = limit, colFrom = colFrom, colTo = colTo, rowFrom = first, rowTo = last) { ll, i, py -> skySample(ll, i, py) }
        else drawLayer(g, c, l, cover = true, rowLimit = limit, colFrom = colFrom, colTo = colTo, rowFrom = first, rowTo = last) { ll, k, py -> sample(ll, k, py) }
    }

    /** With the ground drawn live by its own view (under this picture): this picture leaves its place bare. Set by the controller. */
    var groundLive = false

    /** Whether the ground is drawn live here: asked for, with depth, in a place that has a ground in front of the land, and not in the safe picture. */
    fun liveGroundActive(): Boolean = groundLive && theme.deep && !theme.opaque && groundSpec != null && !groundFirst

    /** Debug builds: where the time of a picture goes, in nanoseconds by part (sky, back land, mist, ground, front land, seam, air, near, disc). */
    var profile: LongArray? = null

    private inline fun timed(slot: Int, work: () -> Unit) {
        val prof = profile
        if (prof == null) { work(); return }
        val t = System.nanoTime(); work(); prof[slot] += System.nanoTime() - t
    }

    fun draw(g: GlyphSink, c: GlyphColors) {
        prepare()
        // The paper lies over the strip above the keyboard too, when there is one.
        val liveGround = liveGroundActive()
        g.rect(0.0, -lift, width, (if (liveGround) groundY else height + tail) + lift, c.paper)      // (the live ground lies under this picture, and shows where there is none of it)
        val cull = (theme.deep || CULL_ALWAYS) && !CULL_OFF
        if (layerBlit?.invoke(sky, g) != true)
            timed(0) { drawLayer(g, c, sky, cover = false, rowLimit = if (cull) hiddenBelow(sky, front + back, groundFirst = if (liveGround) false else null) else null) { l, i, py -> skySample(l, i, py) } }
        if (early) timed(8) { drawDisc(g, c) }                   // the Earth stands behind everything
        if (groundFirst && !liveGround) timed(3) { drawGround(g, c) }           // where the land in front of the ground is what matters: bamboo
        for (i in back.indices.reversed()) {
            if (discLate && !early && back[i] === walkOn) timed(8) { drawDisc(g, c) }
            if (layerBlit?.invoke(back[i], g) == true) continue
            if (theme.deep && i < back.size - 1) timed(2) { mist(g, c, back[i]) }
            timed(1) { drawLayer(g, c, back[i], cover = true, rowLimit = if (cull) hiddenBelow(back[i], front + back.subList(0, i), groundFirst = groundFirst) else null) { l, k, py -> sample(l, k, py) } }
        }
        if (discLate && !early && !back.contains(walkOn)) timed(8) { drawDisc(g, c) }
        if (!groundFirst && !liveGround) timed(3) { drawGround(g, c) }
        if (liveGround) drawGlitter(g, c)
        for (i in front.indices.reversed()) {
            if (layerBlit?.invoke(front[i], g) == true) continue
            if (theme.deep && back.isNotEmpty()) timed(2) { mist(g, c, front[i]) }
            timed(4) { drawLayer(g, c, front[i], cover = true) { l, k, py -> sample(l, k, py) } }
        }
        timed(5) { drawSeam(g, c) }
        timed(6) { drawAir(g, c) }
        g.layerEnd()
        if (theme.deep && !theme.opaque && !liveNear) timed(7) { drawNear(g, c, front = false) }
    }

    /** With depth in the keyboard, the corner and what passes are drawn live by their own view (see [GlyphNear]), not into this picture. */
    var liveNear = false

    /** The sun or the moon on the water, when the ground is drawn live: its sparks stay under it, whatever the water does. */
    private fun drawGlitter(g: GlyphSink, c: GlyphColors) {
        val spec = groundSpec ?: return
        val dx = discX ?: return
        if (spec.kind != GroundKind.SEA || world.fog < 0.9 || formed < 1) return
        val geo = groundGeometry() ?: return
        val sub = FINE
        val color = if (!theme.dark) c.red else c.ink
        for (row in 0 until geo.rows) for (b in 0 until geo.m) {
            val y = geo.y(row) + b * sub
            val gg = min(1.0, (y + sub / 2 - groundY) / geo.span)
            val half = 8 + 70 * gg
            val fineRow = row * geo.m + b
            val run = 1 + (h2(fineRow, 5) * 2).toInt()
            var bx = floor((dx - half) / sub).toInt()
            while (bx * sub < dx + half) {
                val px = bx * sub + sub / 2
                if (abs(px - dx) < half && h2(floorDiv(bx, run), fineRow * 3) > 0.54 + 0.26 * gg) g.rect(bx * sub, y, sub, sub, color)
                bx++
            }
        }
    }

    /** Debug and shots: hold what passes in front at this part of the width, or null to let it go by with the mileage. */
    var foreFix: Double? = null
    /** Debug and shots: leave out the corner's own foreground. */
    var noFrame = false

    internal fun nearState(): GlyphDepthFore.State {
        val live = !theme.still
        return GlyphDepthFore.State(preset.name, width, height, lift, theme.dark, theme.still, world.tick * 1000.0 / 12, world.lastMove.toDouble() * 1000.0 / 12, live)
    }

    /** Where what passes in front stands, left edge, in points: [GlyphDepthFore.passesAt] and its sway, or the held place. */
    fun foreAt(): Double = foreFix?.let { it * width } ?: (GlyphDepthFore.passesAt(world.pos.toDouble(), 0.0) + GlyphDepthFore.sway(nearState()))

    /**
     * What is nearest: the corner's bank in the bottom left and, now and then, something that passes in front of everything. Nothing is drawn
     * above the keyboard's top edge. [front]: the second drawing, in front of the cats (the caller clips it to where they stand).
     */
    fun drawNear(g: GlyphSink, c: GlyphColors, front: Boolean) {
        val clip = GlyphTopClip(g, 0.0)
        val st = nearState()
        if (!front && !noFrame) GlyphDepthFore.drawFrame(clip, c, st)
        if (GlyphDepthFore.hasFore(preset.name)) GlyphDepthFore.drawFore(clip, c, st, foreAt(), 0.0)
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

    // ---- the places' own inks ------------------------------------------------------------------------

    /** The ink of this name by day or by night, or 0 for none. */
    private fun hue(name: String?): Int = if (name.isNullOrEmpty()) 0 else GlyphScenes.HUES[name]?.let { if (theme.dark) it.second else it.first } ?: 0

    /** The place's ink for [key] ("grass", "leaf"...), set on the block being asked about; whether it has one. */
    private fun tint(key: String): Boolean {
        val v = hue(preset.tint[key]?.firstOrNull())
        if (v == 0) return false
        probe.acc = true; probe.hue = v
        return true
    }

    /** The ink [name] on the block being asked about (lit as well when [lit]). */
    private fun inked(name: String?, lit: Boolean = false): Int {
        val v = hue(name)
        if (v != 0) { probe.acc = true; probe.hue = v; if (lit) probe.lit = true }
        return v
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
                LandShape.CONE -> {                                // one mountain, alone and far off: it keeps its place while the land goes by
                    val axis = i * l.sub + l.sub / 2 - s.at * width
                    val d = abs(axis) / (s.spread * l.amp * height * (if (framing == Framing.ROWS) 0.62 else 0.22))   // as wide as the prototype's even where it is drawn lower
                    l.apex[i] = axis.toFloat()
                    var v0 = min(if (s.crater) 0.9 else 0.95, max(0.0, 1 - d).pow(1.75) * 1.06) + 0.012 * n1(wi * 0.5, s.seed)
                    if (s.crater && d < 0.085) v0 -= 0.045 * (1 - d / 0.085)
                    v0
                }
                LandShape.DUNES -> {                               // long backs, short faces
                    val t = w * s.freq + s.seed * 0.37
                    val k = floor(t)
                    val f = t - k
                    val crest = if (s.crest != 0.0) s.crest else 0.7
                    val sx = if (f < crest) f / crest else (1 - f) / (1 - crest)
                    l.apex[i] = if (f >= crest) 1f else 0f
                    (0.4 + 0.6 * h2(k.toInt(), s.seed + 5)) * sx * sx * (3 - 2 * sx) * 0.9 + 0.1 * fbm1(w * s.freq * 3, s.seed + 2)
                }
                LandShape.MESA -> {                                // table rock: walls, a flat top, a little scree
                    val t = w * s.freq + s.seed * 0.37
                    val k = floor(t)
                    val f = t - k
                    val up = if (h2(k.toInt(), s.seed + 5) > 0.42) 0.45 + 0.55 * h2(k.toInt(), s.seed + 6) else 0.0
                    val m = min(f - 0.2, 0.8 - f)
                    if (m >= 0) up * min(1.0, 0.3 + m / 0.03) else up * 0.3 * max(0.0, 1 + m / 0.1)
                }
                LandShape.CRATERS -> {                             // bowls, each with a raised lip
                    val t = w * s.freq + s.seed * 0.37
                    val k = floor(t)
                    val f = t - k
                    val r = 0.12 + 0.16 * h2(k.toInt(), s.seed + 6)
                    val d = abs(f - 0.5) / r
                    var v0 = 0.5 + 0.25 * fbm1(w * s.freq * 2.3, s.seed)
                    if (h2(k.toInt(), s.seed + 5) > 0.3) { if (d < 1) v0 -= 0.34 * (1 - d * d) else if (d < 1.35) v0 += 0.12 * (1 - (d - 1) / 0.35) }
                    v0
                }
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
            if (s.cap != 0.0 && s.shape == LandShape.CONE) l.capAt[i] = max(0.0, base - amp * 0.95 + s.cap * amp * (0.7 + 0.7 * n1(i * 0.6, s.seed + 31)) - y).toFloat()   // snow lies by height, in streaks
        }
        val marks = s.marks
        if (marks != null) {                                      // one stretch of road in two has something standing on it
            l.markCol.fill(-1)
            val stretch = if (s.every != 0) s.every else 190
            for (slot in floorDiv(l.offI - 20, stretch)..floorDiv(l.offI + l.n + 20, stretch)) {
                if (s.every == 0 && h2(slot, s.seed + 500) < 0.74 - 0.12 * theme.level) continue
                val pat = GlyphScenes.STAMPS.getValue(marks[(h2(slot, s.seed + 502) * marks.size).toInt()])
                val first = slot * stretch + (if (s.every != 0) 6 else 24) + (h2(slot, s.seed + 501) * min(60.0, stretch * 0.45)).toInt() - l.offI
                val unit = l.stamp()
                val widthPt = pat[0].length * unit
                val blocks = ceil(widthPt / l.sub).toInt()                                                                                  // how many blocks the picture lies over
                clearMarks?.let { c -> val mid = first * l.sub + widthPt / 2; if (mid in (c.start - 20)..(c.endInclusive + 20)) continue }       // (a festival stands here)
                if (short && s.avoid > 0.0 && abs(first * l.sub + widthPt / 2 - s.avoidAt * width) < s.avoid * width) continue   // not built over the mountain
                val foot = l.ridge[max(0, min(l.n - 1, first + (blocks shr 1)))]
                for (k in 0 until blocks) {
                    val at = first + k
                    if (at in 0 until l.n) {
                        l.markCol[at] = min(pat[0].length - 1, floor((k + 0.5) * l.sub / unit).toInt())
                        l.markTop[at] = (foot - pat.size * unit).toFloat()
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
            if (s.landmarks != 0.0) {                                                                      // a few towers whose outline everyone knows
                val o = wi - b * s.bw
                val kind = if (h2(b, s.seed + 77) < s.landmarks) 1 + floor(h2(b, s.seed + 78) * 3).toInt() else 0
                if (kind != 0) {
                    y = q(base - amp * (0.84 + 0.3 * h2(b, s.seed + 79)), l.cell.toDouble())
                    y += when (kind) {
                        1 -> max(0, 3 - min(o, s.bw - 1 - o)) * l.cell.toDouble()                           // a crown that steps in
                        2 -> floor(o * 5.0 / s.bw) * l.cell - (if (o < 2) 2.0 * l.cell else 0.0)             // a blade cut on the slant, with two masts
                        else -> if (abs(o - (s.bw shr 1)) < 1) -4.0 * l.cell else if (o < 2 || o > s.bw - 3) l.cell.toDouble() else 0.0   // a needle
                    }
                }
            }
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
            val row = floor((py - l.markTop[i]) / l.stamp()).toInt()
            val mc = l.markCol[i]
            val ch = if (row >= 0 && row < pat.size) pat[row][mc] else '.'
            p.hard = true                                          // a landmark is a thing: the mist round the keyboard's type does not thin it
            if (ch == 'X') {
                p.prio = 3
                if (theme.dark) {                                  // at night a solid stamp is dark: only its outline is drawn
                    fun on(r: Int, k: Int) = r in pat.indices && k in 0 until pat[r].length && pat[r][k] != '.'
                    p.rim = !(on(row - 1, mc) && on(row + 1, mc) && on(row, mc - 1) && on(row, mc + 1))
                }
                return 1.0
            }
            if (ch == 'R') { p.prio = 3; p.lit = true; p.acc = true; return 1.0 }
            val own = when (ch) { 'G' -> "green"; 'Y' -> "gold"; 'B' -> "blue"; 'P' -> "pink"; 'O' -> "orange"; 'C' -> "cyan"; 'M' -> "magenta"; else -> null }
            if (own != null) { p.prio = 3; p.lit = true; p.hue = hue(own); p.acc = p.hue != 0 || ch == 'M' || ch == 'C' || ch == 'P'; return 1.0 }
            if (ch == 'o') { p.prio = 3; if (theme.dark) { p.lit = true; return 1.0 }; return 0.01 }
            p.hard = false
        }
        val trees = l.trees
        if (trees != null && l.treeY[i] < 1e8 && py < top) {
            val dx = l.treeDx[i] * l.sub / u
            val dy = (py - l.treeY[i]) / u
            val tall = l.treeTall[i]
            if (trees == TreeKind.PINE) {
                val h = dy + 12 * tall
                if (h >= 0 && dy < 12 && abs(dx) <= h * 0.36 + 1) { if (tint("pine")) p.lit = true; return 1.0 }
                if (dx == 0.0 && dy >= 12) return 1.0
            } else {
                val rr = dx * dx + dy * dy
                val bloom = trees == TreeKind.SAKURA || trees == TreeKind.MAPLE      // these two are all colour
                if (theme.deep) {                                                    // a crown in pixels, not in type: clumps of a few and a few alone, close in its heart, thinning to its edge
                    val edge = 81 + 30 * tall
                    if (rr > edge) return if (dx == 0.0 && dy > 0) 1.0 else -1.0
                    val fx = i + l.offI
                    val fy = floor(py / l.sub).toInt()
                    val heart = rr <= 26 + 10 * tall
                    val close = if (heart) 0.7 else 0.62 - 0.42 * rr / edge
                    val on = n2(fx * 0.55, fy * 0.55, s.seed + 7) * 0.72 + h2(fx, fy + s.seed + 303) * 0.28 > 1 - close
                    p.bare = true                                                    // no type among them
                    if (!on) return if (dx == 0.0 && dy > 0) 1.0 else -1.0
                    p.prio = 2
                    if (bloom) { p.acc = true; p.lit = true; p.hue = hue(if (trees == TreeKind.SAKURA) "pink" else listOf("", "orange", "gold")[min(2, floor((tall - 1) * 3).toInt())]) }
                    else if (!heart && tint("leaf")) p.lit = true
                    return 1.0
                }
                val petal = if (!bloom) 0 else hue(if (trees == TreeKind.SAKURA) "pink" else listOf("", "orange", "gold")[min(2, floor((tall - 1) * 3).toInt())])   // a maple wood is three reds; without the second ink, one
                if (rr <= 26 + 10 * tall) {                                          // the heart of the crown is solid
                    if (bloom) { p.acc = true; p.lit = true; p.hue = petal; p.pool = "leaf"; p.prio = 2; return 0.82 }
                    return 1.0
                }
                if (rr <= 81 + 30 * tall) {                                          // round it, leaves
                    p.pool = "leaf"; p.prio = 2
                    if (bloom) { p.acc = true; p.lit = true; p.hue = petal } else tint("leaf")
                    return 0.55
                }
                if (dx == 0.0 && dy > 0) return 1.0
            }
        }
        if (py < top) return -1.0
        val depth = py - top
        if (depth < l.sub) {
            p.rim = true
            if (s.shape == LandShape.CONE) p.hard = true             // the one mountain keeps its outline through the mist
            if (s.lava && abs(l.apex[i]) < 0.1 * s.spread * l.amp * height * l.fs) { p.acc = true; p.lit = true; p.hue = hue("gold") }   // the lip of the crater glows
            if (l.edge < 0.9) {                                   // a far ridge is one stroke, and the stroke leans the way the ridge runs
                val lean = l.ridge[min(l.n - 1, i + 2)] - l.ridge[max(0, i - 2)]
                p.pool = if (lean < -l.sub * 0.6) "rise" else if (lean > l.sub * 0.6) "fall" else "flat"; p.prio = 2
            }
            return l.edge
        }
        if (s.lava) {                                             // what runs down from the crater: it winds, it thins, it cools at its ends
            val a = l.apex[i] / (depth + 6) * 3 + 0.22 * sin(depth * 0.085 + s.seed)
            val n = round(a)
            val reach = (0.3 + 0.6 * h2(n, s.seed + 61)) * l.amp * height * l.fs
            val off = abs(a - n)
            if (abs(n) <= 2 && h2(n, s.seed + 62) > 0.25 && depth < reach && off < 0.05 + 0.17 * (1 - depth / reach)) {
                p.acc = true; p.lit = true; p.prio = 3; p.pool = "wave"; p.hue = if (depth > reach * 0.78) 0 else hue(if (off < 0.06) "gold" else "orange"); return 0.92
            }
        }
        if (s.grass && depth < l.cell * 1.6) { p.pool = "grass"; p.prio = 1; tint("grass"); return l.tone * 0.75 }
        if (s.cap != 0.0 && depth < l.capAt[i]) return 0.02     // snow lies on the top of a peak
        if (s.shape == LandShape.DUNES && l.apex[i] != 0f && depth < (l.baseY - top) * 0.9) {       // the short face of a dune is in its own shadow
            p.prio = 1; inked("ochre", true)
            return if (((floor(py / l.sub).toInt() + i) and 1) != 0 || depth < l.sub * 2) 0.94 else 0.2
        }
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
        if (s.hue != null && h2(p.ox, floor(py / l.cell).toInt() + s.seed) < (if (s.hueShare != 0.0) s.hueShare else 0.4)) inked(s.hue)   // some of its type is set in the place's own ink
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
            val words = s.signs ?: GlyphScenes.SIGNS
            val word = words[mod(b, words.size)]
            if (h2(b, s.seed + 21) > 0.5 && wc == ceil(b * s.bw / 2.0).toInt() + 2 && storey >= 1 && storey <= word.length) {
                p.lit = true; p.sign = word[storey - 1].toString()
                val inks = preset.tint["signs"]
                if (inks != null) inked(inks[mod(b, inks.size)])
                return 0.6
            }
            if (pane && (i and 1) == 0 && (floor((py - top) / l.sub).toInt() and 1) == 0) {   // a window: a small square, lit at night, a hole by day
                if (theme.dark) { p.lit = true; if (h2(wc, storey + 5) < 0.6) tint("window"); return 1.0 }
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
            inked(s.hue, true)
            return if (mod(py + h2(b, s.seed + 2) * 40, 5.0 * l.cell) < l.sub) 0.1 else l.tone
        }
        if (abs(o - at) <= 3 && py < height * 0.62 && h2(wi, (py / l.cell).toInt() + s.seed) > 0.86) { p.pool = "blade"; p.prio = 1; inked(s.hue); return 0.3 + 0.3 * l.tone }
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
                p.lit = true; p.acc = disc.sun && !theme.dark; inked(disc.hue); return 1.0
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
    /** With depth, the pixel being asked about on the ground: its column and row in the picture's pixels, or null. */
    private var fineCol: Int? = null
    private var fineRow = 0

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
                if (spec.reflect != 0) {                              // the mountain, upside down in the lake
                    val lay = back[spec.reflect - 1]
                    val hgt = groundY - lay.ridge[max(0, min(lay.n - 1, floor(px / lay.sub).toInt()))]
                    if (theme.deep) {                                 // it is clear at the shore and breaks up the farther it runs
                        val far = g * (height - groundY) / max(1.0, hgt * 0.9)
                        if (hgt > 8 && far < 1 && h2(round(ux / 4), row) > 0.1 + 0.5 * far) { p.pool = "flat"; inked("blue"); return 0.75 - 0.4 * far }
                    } else if (hgt > 8 && g * (height - groundY) < hgt * 0.62 && h2(round(ux / 4), row) > 0.22) { p.pool = "flat"; inked("blue"); return 0.36 }
                }
                val dx = discX
                if (dx != null && abs(px - dx) < 8 + 70 * g && !liveGroundActive()) {         // the sun on the water, or the moon (with depth: sparks one pixel high, one or two long; with the live ground they are drawn apart, under the sun)
                    val fc = fineCol
                    val spark = if (fc != null) h2(floorDiv(fc, 1 + (h2(fineRow, 5) * 2).toInt()), fineRow * 3) > 0.54 + 0.26 * g else h2(round(ux / 3), row * 3) > 0.52 + 0.3 * g
                    if (spark) { p.lit = true; p.acc = !theme.dark; return 1.0 }
                }
                val drift = if (theme.level >= 2 && !theme.still) world.epoch * (if (theme.level == 3) 0.6 else 0.3) else 0.0
                val wave = sin(row * 1.9 + n2(ux * 0.014, row * 0.6, 3) * 6 + drift)
                if (wave > 0.45) { if (h2(round(ux / 6), row) < 0.5) tint("water"); return 0.3 + 0.36 * g }
                return 0.03 + 0.1 * g
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
            GroundKind.REGOLITH -> {                                 // the Moon's own ground: fine dust, and craters seen from the side
                p.pool = "dust"
                if ((row == 2 || row == 5) && px < width * 0.5) {     // paw prints behind the cats, left and right
                    val n = round(ux / 26)
                    if (abs(ux - n * 26) < 4 && (n and 1) == (if (row == 5) 1 else 0)) { p.rim = true; return 1.0 }
                }
                val k = round(ux / 110)
                val u = (ux - k * 110) / (16 + 26 * h2(k, 5))
                val v = (g - (0.18 + 0.64 * h2(k, 6))) / (0.045 + 0.06 * h2(k, 7))
                val d = u * u + v * v
                if (h2(k, 8) > 0.3 && d < 1.3) { if (d > 0.78) { p.rim = v < 0.2; return if (v < 0.2) 1.0 else 0.34 }; return 0.02 }
                return if (h2(round(ux / 5), row * 3) > 0.87) 0.32 else 0.04 + 0.07 * g
            }
            GroundKind.SAND -> {                                     // ripples the wind left, and grains
                p.pool = if (g < 0.3) "dust" else "flat"
                if (sin(row * 2.6 + n2(ux * 0.008, row * 0.35, 9) * 7) > 0.74) return 0.16 + 0.2 * g
                return if (h2(round(ux / 4), row * 5) > 0.9) 0.3 else 0.02
            }
            GroundKind.FLOOR -> {                                    // under the bamboo: fallen leaves, patches of shade
                p.pool = "blade"
                if (spec.red && h2(round(ux / 5), row + 3) > 0.8) {  // what has fallen is the colour of the trees
                    p.acc = true; p.lit = true; p.hue = hue(listOf("", "orange", "gold")[floor(h2(round(ux / 5), row + 9) * 3).toInt()]); return 0.7
                }
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

    /**
     * The ground in pixels of one size and type of one size. What tells near from far is how close the type is set, how dark it is, and how
     * fast it goes by; the pattern itself (the rows the waves, the furrows, the kerb lines are counted in) still opens out toward the viewer.
     */
    private fun drawGroundFine(g: GlyphSink, c: GlyphColors) {
        val geo = groundGeometry() ?: return
        for (row in 0 until geo.rows) {
            val exact = world.pos * geo.speed(row) * 8 / geo.cell
            drawGroundRow(g, c, geo, row, 0, geo.cols, round(exact))
        }
        g.layerEnd()
    }

    /** What the fine ground is made of: rows of cells [cell] points high, one run per row, and the pattern rows its pixels are counted in. */
    internal class GroundGeometry(val cell: Int, val m: Int, val rows: Int, val cols: Int, val top: Double, val span: Double, val rowAt: IntArray) {
        fun y(row: Int) = top + row * cell
        fun g0(row: Int) = min(1.0, (y(row) + cell / 2.0 - top) / span)
        /** How fast the row goes by, against the world's mileage (the near rows faster): the speed of the ground in the picture. */
        fun speed(row: Int) = 0.5 + 2.2 * g0(row)
    }

    /** The ground's rows for this place, or null when it has none (or the picture is not a deep one). */
    internal fun groundGeometry(): GroundGeometry? {
        if (!theme.deep) return null
        groundSpec ?: return null
        val span = height - groundY
        val level = theme.level
        val cell = intArrayOf(12, 10, 6, 4)[level]
        val sub = FINE
        val m = (cell / sub).toInt()
        val least = intArrayOf(8, 6, 4, 4)[level]
        val most = intArrayOf(12, 10, 8, 8)[level]
        val rowAt = ArrayList<Int>()
        var y = groundY
        var at = 0.0
        while (y < height + tail + cell) {
            val g0 = min(1.0, (y - groundY) / span)
            rowAt.add(floor(at).toInt())
            at += 2 * sub / max(least, min(most, round((least + (most - least + 1) * g0.pow(1.15)) / 2) * 2))
            y += sub
        }
        var rows = 0
        y = groundY
        while (y < height + tail) { rows++; y += cell }
        return GroundGeometry(cell, m, rows, ceil(width / cell).toInt(), groundY, span, rowAt.toIntArray())
    }

    /** One row of the fine ground, cells [colFrom] until [colTo] of it, as it lies when the row has gone [off] cells. */
    internal fun drawGroundRow(g: GlyphSink, c: GlyphColors, geo: GroundGeometry, row: Int, colFrom: Int, colTo: Int, off: Int) {
        val spec = groundSpec ?: return
        val cell = geo.cell
        val m = geo.m
        val sub = FINE
        val y = geo.y(row)
        val g0 = geo.g0(row)
        grid = m
        firm = false
        plane = 0.34 * (1 - g0) * (1 - g0)
        weight = if (g0 > 0.5) 700 else if (g0 > 0.22) 500 else 400
        dense = NEAR_CLOSE * g0 * g0 - NEAR_FAR * (1 - g0) * (1 - g0)
        val wide = 2.0 - 1.3 * g0
        for (col in colFrom until colTo) {
            var accent = 0
            var lit = false
            for (k in 0 until m * m) {
                val a = k % m
                val b = k / m
                val px = (col * m + a) * sub + sub / 2
                val py = y + b * sub + sub / 2
                probe.reset()
                fineCol = (col + off) * m + a
                fineRow = row * m + b
                v[k] = finish(ground(spec, (px - width / 2 + off * cell) * wide, geo.rowAt[min(geo.rowAt.size - 1, row * m + b)], min(1.0, (py - groundY) / geo.span), px), px, py)
                if (probe.acc) accent = if (probe.hue != 0) probe.hue else VERMILION
                if (probe.lit) lit = true
            }
            firm = lit
            paintCell(g, c, col * cell.toDouble(), y, cell, probe.pool, col + off, row, 40, true, accent, null)
        }
        grid = 2; weight = 400; plane = 0.0; dense = 0.0; firm = false; fineCol = null
    }

    /** The air between two planes: what is behind thins into the paper just above the ridge of the plane about to be drawn. */
    private fun mist(g: GlyphSink, c: GlyphColors, l: Layer, iFrom: Int = 0, iTo: Int = Int.MAX_VALUE) {
        val sub = l.sub
        val h = MIST_HEIGHT
        val disc = preset.sky.disc
        val dxc = discX
        val r2 = if (dxc != null && disc != null) (disc.r * height * discScale + 1).let { it * it } else -1.0
        val ep = world.epoch
        for (i in iFrom until min(iTo, l.n)) {
            val top = l.ridge[i].toDouble()
            if (!(top > 0) || top > height) continue
            val shiftCol = (i + l.offI) * sub
            val thick = 0.7 + 0.6 * n1(shiftCol * 0.015 + ep * 0.04, 81)
            var y = floor(top - h).toInt()
            while (y < top) {
                val share = ((y - (top - h)) / h).pow(1.4) * MIST_SHARE * thick
                if (share > 0) for (a in 0 until sub.toInt()) {
                    val x = (i * sub + a)
                    val xw = ((i + l.offI) * sub).toInt() + a
                    if (BAYER[(y and 3) * 4 + (xw and 3)] < share && (r2 < 0 || (x - dxc!!).let { it * it } + (y - discY).let { it * it } > r2)) g.rect(x, y.toDouble(), 1.0, 1.0, c.paper)
                }
                y++
            }
        }
    }

    private fun drawGround(g: GlyphSink, c: GlyphColors) {
        if (theme.deep) return drawGroundFine(g, c)
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
                var accent = 0
                for (k in 0 until 4) {
                    val px = (col * 2 + (k and 1)) * sub + sub / 2
                    val py = y + (k shr 1) * sub + sub / 2
                    probe.reset()
                    v[k] = finish(ground(spec, (px - width / 2 + off * cell) * wide, row * 2 + (k shr 1), min(1.0, (py - groundY) / span), px), px, py)
                    if (probe.acc) accent = if (probe.hue != 0) probe.hue else VERMILION
                }
                paintCell(g, c, col * cell.toDouble(), y, cell, probe.pool, col + off, row, 40, true, accent, null)
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
        val u = if (theme.deep) 1.0 else GlyphSeam.BLOCK           // with depth, the edge in the finest grain
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

    private fun drawDisc(out: GlyphSink, c: GlyphColors) {
        val g: GlyphSink = if (liveGroundActive()) GlyphBottomClip(out, groundY) else out          // the ground comes after the sun and covers its lower part
        val dx0 = discX ?: return
        val P = preset.sky.disc ?: return
        val r = P.r * height * discScale
        val u = if (theme.deep) 1.0 else 3.0                          // with depth, a rounder sun
        val own = hue(P.hue)
        val color = GlyphColors.withAlpha(if (own != 0) own else if (P.sun && !theme.dark) c.red else c.ink, world.fog)
        if (theme.deep && !P.earth && formed >= 1) {                  // its own air: a ring of its colour thinning away from it
            val far = r * 1.7 + 6
            var yy = floor(discY - far).toInt()
            while (yy < discY + far) {
                var xx = floor(dx0 - far).toInt()
                while (xx < dx0 + far) {
                    val dd = (hypot(xx + 0.5 - dx0, yy + 0.5 - discY) - r) / (far - r)
                    if (dd > 0.04 && dd < 1 && BAYER[(yy and 3) * 4 + (xx and 3)] < 0.42 * (1 - dd) * (1 - dd)) g.rect(xx.toDouble(), yy.toDouble(), 1.0, 1.0, color)
                    xx++
                }
                yy++
            }
        }
        var y = floor((discY - r) / u) * u
        while (y < discY + r) {
            var x = floor((dx0 - r) / u) * u
            while (x < dx0 + r) {
                val dx = x + u / 2 - dx0
                val dy = y + u / 2 - discY
                if ((formed >= 1 || h2(x.toInt(), y.toInt() + 5) < (formed - 0.25) / 0.6) && dx * dx + dy * dy <= r * r &&
                    !(P.crescent && (dx - r * 0.5).pow(2) + (dy + r * 0.15).pow(2) < r * r * 0.85)) {
                    g.rect(x, y, u, u, if (P.earth) GlyphEarth.color(dx / r, dy / r, if (theme.still) 0 else world.epoch) else color)
                }
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
                val plain = (if (fall.hues == null) hue(fall.hue) else 0).let { if (it != 0) it else if (fall.red) c.red else c.ink }
                for (k in 0 until 22 * many) {
                    val speed = 0.6 + 0.8 * h2(k, 71)
                    val x = mod(mod(h2(k, 72) * width + t * fall.dx * speed * 3, width) + width, width)
                    val y = mod(mod(h2(k, 73) * top + t * fall.dy * speed * 3, top) + top, top) - lift      // it falls in from above the edge
                    val inks = fall.hues
                    val ink = if (inks != null) hue(inks[k % inks.size]).let { if (it != 0) it else c.red } else plain
                    g.text(fall.glyphs[k % fall.glyphs.length].toString(), q(x, 2.0), q(y, 2.0), 8, false, ink)
                }
            }
            if (P.plume) {                                           // what the mountain breathes out: it glows as it leaves, then it is smoke, and the wind has it
                val fire = back.firstOrNull { it.spec.lava }
                if (fire != null) {
                    val ax = fire.spec.at * width
                    val ay = fire.baseY - fire.amp * height * fire.fs * 0.9
                    for (k in 0 until 30 * many) {
                        val age = mod(t * 0.9 + k * 2.3, 32.0)
                        val x = ax + sin(k * 1.9 + age * 0.22) * (2 + age * 0.8) + age * age * 0.06
                        val y = ay - 4 - age * 3.6
                        val gold = hue("gold")
                        val ink = if (age < 2) (if (gold != 0) gold else c.red) else if (age < 4.5) c.red else c.ink
                        val size = if (age < 6) 9 else if (age < 16) 13 else 17
                        g.text("・。◦○◯〇〇●"[min(7, floor(age / 4).toInt())].toString(), q(x, 2.0), q(y, 2.0), size, false, GlyphColors.withAlpha(ink, max(0.2, 1 - age / 34)))
                    }
                    for (k in 0 until 5 * many) {                        // what it throws: up, out, and down again
                        val span = 12 + (k % 3) * 3
                        val age = (t + k * 4) % (span + 6)
                        if (age > span) continue
                        val u = age.toDouble() / span
                        val x = ax + (h2(k, 91) - 0.5) * 150 * u
                        val y = ay - 46 * sin(Math.PI * min(1.0, u * 0.9 + 0.05)) * (0.7 + 0.6 * h2(k, 92)) + 30 * u * u
                        val ink = hue(if (u < 0.5) "gold" else "orange").let { if (it != 0) it else c.red }
                        g.rect(q(x, 2.0), q(y, 2.0), 3.0, 3.0, ink)
                    }
                }
            }
            if (P.tumble) {                                          // tumbleweed, bowling along the ridge
                val ochre = hue("ochre").let { if (it != 0) it else c.ink }
                for (k in 0 until many) {
                    val x = mod(mod(h2(k, 81) * width - t * (3 + k), width + 40) + width + 40, width + 40) - 20
                    val y = ridgeAt(max(0.0, min(width - 1, x))) - 5 - abs(sin(t * 0.9 + k)) * 5
                    g.text(if (t and 1 != 0) "◯" else "〇", q(x, 2.0), q(y, 2.0), 11, false, ochre)
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
        if (framing != Framing.WIDE && tail <= 0 && py > height - fade) d *= (max(0.0, min(1.0, (height - py) / fade))).pow(1.15)   // and at the keyboard's bottom edge
        val m = quiet
        return if (m != null && !p.lit && !p.hard) d * m[max(0, min(qh - 1, py.toInt() shr 1)) * qw + min(qw - 1, px.toInt() shr 1)] else d   // and it thins round the keyboard's own type
    }

    /**
     * What a layer need not draw: a cell wholly below the top of land that is drawn after it (and covers everything from there down) is
     * hidden. For each column of cells, the first row that is. [later]: the layers drawn after this one; the ground covers too when it is
     * drawn after the land ([groundFirst] false); null for [groundFirst] means the sky, for which only the layers count.
     */
    private fun hiddenBelow(l: Layer, later: List<Layer>, groundFirst: Boolean?): IntArray {
        val cell = l.cell
        val m = l.m
        val cols = l.n / m
        val covers = later.filter { it.haze == 0.0 && it.spec.kind != LayerKind.STEMS && it !== l }
        val groundCut = if (groundFirst == false && groundSpec != null) groundY else Double.MAX_VALUE
        val limit = IntArray(cols)
        val rowsAll = ceil((height + tail) / cell).toInt()
        for (col in 0 until cols) {
            var lowest = 0.0                                          // the lowest of the "topmost cover" across the cell's width
            for (k in 0 until m) {
                val x = (col * m + k) * l.sub + l.sub / 2
                var top = groundCut
                for (o in covers) top = min(top, o.ridge[max(0, min(o.n - 1, floor(x / o.sub).toInt()))].toDouble())
                if (top > lowest) lowest = top
            }
            limit[col] = if (lowest >= Double.MAX_VALUE / 2) rowsAll else min(rowsAll, ceil(lowest / cell).toInt() + 2)
        }
        return limit
    }

    private fun drawLayer(g: GlyphSink, c: GlyphColors, l: Layer, cover: Boolean, rowLimit: IntArray? = null, colFrom: Int = 0, colTo: Int = -1, rowFrom: Int = Int.MIN_VALUE, rowTo: Int = Int.MAX_VALUE, sampler: (Layer, Int, Double) -> Double) {
        val cell = l.cell
        val sub = l.sub
        val m = l.m
        val mm = m * m
        val rows = min(ceil((height + tail) / cell).toInt(), rowTo)
        val cols = if (colTo >= 0) min(colTo, l.n / m) else min(ceil(width / cell).toInt(), l.n / m)
        val p = probe
        grid = m
        plane = l.air
        // Rows wholly above everything the layer has are skipped: nothing there is the layer's.
        val firstRow = max(max(-ceil(lift / cell).toInt(), rowFrom), if (l.spec.kind == LayerKind.STEMS || l === sky) Int.MIN_VALUE else floor(l.minY / cell).toInt() - 1)
        for (r in firstRow until rows) for (col in colFrom until cols) {
            if (rowLimit != null && col < rowLimit.size && r >= rowLimit[col]) continue          // hidden by land that comes after
            var pool = l.pool
            var best = -1
            var accent = 0
            var sign: String? = null
            var firmCell = false
            var bareCell = false
            for (k in 0 until mm) {
                val i = col * m + (k % m)
                val py = (r * m + (k / m)) * sub + sub / 2
                p.reset()
                val d = sampler(l, i, py)
                v[k] = if (d < 0) -1.0 else finish(d, i * sub + sub / 2, py)
                if (d >= 0.9 || p.hard || p.rim) firmCell = true
                if (p.bare) bareCell = true
                if (d >= 0 && p.prio > best) { best = p.prio; pool = p.pool }
                if (p.acc && (d >= 0.5 || p.hue != 0) && d > 0.02) accent = if (p.hue != 0) p.hue else VERMILION
                if (p.sign != null) sign = p.sign
            }
            firm = firmCell; noType = bareCell
            paintCell(g, c, col * cell.toDouble(), r * cell.toDouble(), cell, pool, col + (l.offI / m), r, l.seed, cover, accent, sign)
        }
        grid = 2; plane = 0.0; noType = false; firm = false
        g.layerEnd()
    }

    /** What the cell being painted is made of: [grid] blocks to a side; how pale its plane is, and whether the outline in it holds its ink better. */
    private var grid = 2
    private var plane = 0.0
    private var firm = false
    private var noType = false
    /** On the ground: the weight of the type, and how much closer it is set. */
    private var weight = 400
    private var dense = 0.0

    /** [a] a part [t] of the way to [b] (both opaque ARGB). */
    private fun mix(a: Int, b: Int, t: Double): Int {
        fun ch(s: Int) = round(((a shr s and 255) + ((b shr s and 255) - (a shr s and 255)) * t)).coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /**
     * One cell of a grid: [v] holds its blocks ([grid] to a side; -1 where the layer is not). What is behind is covered, then the cell is either
     * mosaic (outlines and heavy tones) or one glyph. Pale areas leave cells empty and nudge the glyphs, so the type scatters.
     */
    private fun paintCell(
        g: GlyphSink, c: GlyphColors, x: Double, y: Double, cell: Int, part: String, wx: Int, row: Int, seed: Int,
        cover: Boolean, accent: Int, sign: String?,
    ) {
        val m = grid
        val mm = m * m
        val sub = cell.toDouble() / m
        var mean = 0.0
        var top = 0.0
        var any = false
        var whole = true
        for (k in 0 until mm) if (v[k] >= 0) { any = true; mean += v[k]; if (v[k] > top) top = v[k] } else whole = false
        if (!any) return
        // What is behind is covered: one block if the cell is all the layer's, else block by block.
        if (cover) {
            if (whole) g.rect(x, y, cell.toDouble(), cell.toDouble(), c.paper)
            else for (k in 0 until mm) if (v[k] >= 0) g.rect(x + (k % m) * sub, y + (k / m) * sub, sub, sub, c.paper)
        }
        mean /= mm
        if (mean < 0.03) return
        var ink = if (accent == 0) c.ink else if (accent == VERMILION) c.red else accent        // the ink, the vermilion, or one of the place's own
        if (plane != 0.0) ink = mix(ink, c.paper, if (firm) plane * 0.3 else plane)             // far off: paler, its outline less so
        val pool = pools.script(part, wx, row, seed)
        val faceOf = if (sign != null) "kata" else pool
        val size = round(cell * GlyphPools.faceScale(faceOf))
        val mono = GlyphPools.isMono(faceOf)
        if (sign != null) { g.textW(sign, x + cell / 2.0, y + cell / 2.0 + 0.5, size, mono, ink, weight); return }
        if (top >= 0.9 && (formed >= 1 || h2(wx, row + seed + 222) < (formed - 0.5) / 0.4)) {          // arriving: type first, form after
            for (k in 0 until mm) if (v[k] >= 0.9) g.rect(x + (k % m) * sub, y + (k / m) * sub, sub, sub, ink)
            if (mean > 0.6) return                                  // a cell that is mostly solid is only solid
        }
        if (noType) return                                          // what is drawn in pixels (a crown) has no type among it
        val t = min(1.0, mean / 0.6)
        if (h2(wx, row * 13 + seed + 5) > doubleArrayOf(0.12, 0.22, 0.3, 0.44)[theme.level] + 0.95 * t + dense) return
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
        g.textW(ch, x + cell / 2.0 + loose * (h2(wx, row + seed + 91) - 0.5) * cell * 0.34, y + cell / 2.0 + 0.5 + loose * (h2(wx, row + seed + 57) - 0.5) * cell * 0.3, size, mono, color, weight)
    }

    companion object {
        /** The picture fades out over this many points at the bottom edge. */
        const val FADE = 46.0
        /** Where the cats' ridge lies on the prototype's keyboard, in points. */
        const val PROTOTYPE_RIDGE = 149.0
        /** Tests: leave out what is hidden even without depth (the numbers do not change; the picture is the same). */
        var CULL_ALWAYS = false
        /** Tests and shots: draw what is hidden too, to see that leaving it out changes nothing. */
        var CULL_OFF = false
        /** Cells a layer's strip is drawn beyond the screen on the right. */
        const val STRIP_MARGIN_CELLS = 6
        /** The pixel of the picture, in points, with depth (see [GlyphTheme.deep]). */
        const val FINE = 2.0
        /** How far toward the paper the middle and the far planes go, and the mist's height and strength between planes. */
        const val AIR_MID = 0.14
        const val AIR_FAR = 0.3
        const val MIST_HEIGHT = 26.0
        const val MIST_SHARE = 0.8
        /** On the ground: how much closer the type is set at the viewer's feet, how much thinner at the horizon. */
        const val NEAR_CLOSE = 0.7
        const val NEAR_FAR = 0.14
        /** The 4x4 ordered-dither thresholds (the prototype's BAYER), for the pixel-grained mist and halos. */
        val BAYER = doubleArrayOf(0.0, 8.0, 2.0, 10.0, 12.0, 4.0, 14.0, 6.0, 3.0, 11.0, 1.0, 9.0, 15.0, 7.0, 13.0, 5.0).map { (it + 0.5) / 16 }.toDoubleArray()
        /** The marker for the vermilion where a cell is drawn in an accent: any other non-zero value is the ARGB of a place's own ink. */
        private const val VERMILION = 1
        /** The skyline framing's ridge is never higher than this: the cats stand on it and are thirty points tall. */
        const val MIN_SKYLINE_RIDGE = 46.0
    }
}
