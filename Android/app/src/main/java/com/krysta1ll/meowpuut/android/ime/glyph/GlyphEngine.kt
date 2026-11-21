package com.krysta1ll.meowpuut.android.ime.glyph

import kotlin.math.max
import kotlin.math.min

/** What a key press is, for the world: the keys themselves are the keyboard's. */
internal enum class GlyphKey { LETTER, DELETE, SPACE, RETURN, OTHER }

/**
 * The 字景 picture and the world it is the picture of, without a screen: settings go in, key presses and committed text go in, and
 * what comes out is a scene drawn into a [GlyphSink] and a frame of what stands in front of the keys. Pure Kotlin, so all of it
 * runs in the JVM tests. One thread owns an engine (the controller's render thread); nothing here is shared.
 *
 * The clock is the caller's: [step] is one tick, twelve a second, and [busy] says whether another is worth taking. Nothing moves
 * and nothing is drawn while [busy] is false, which is how the keyboard comes to a full stop about twenty seconds after the last key.
 */
internal class GlyphEngine(val pools: GlyphPools, clockHm: () -> Int = { -1 }, nowMs: () -> Long = { System.nanoTime() / 1_000_000L }) {
    val theme = GlyphTheme()
    val world = GlyphWorld(theme, clockHm, nowMs)
    val scape = GlyphScape(world, pools)

    var layout: GlyphLayout = GlyphLayout.NONE
        private set
    /** The candidate bar has candidates on it: the land thins round them, and the name of the place keeps out of their way. */
    var hasCandidates = false
        private set
    /** Where the bar's label stands (left and right, in points), or null when it shows none: the land thins round it. */
    var barLabel: DoubleArray? = null
        private set

    fun setBarLabel(left: Double, right: Double) {
        val now = barLabel
        if (now != null && now[0] == left && now[1] == right) return
        barLabel = doubleArrayOf(left, right); world.dirty = true
    }

    fun clearBarLabel() {
        if (barLabel != null) { barLabel = null; world.dirty = true }
    }

    /** Show the two cats (the setting) and the guest crab (a debug build's setting). */
    var showCats = true
    private var worn = -1
    private var wornLevel = -1
    private var wornLayout: GlyphLayout? = null
    private var dirtyLayout = true

    /** How far the journey has got, in committed characters: the one number that is kept. */
    val distance: Long get() = world.distance

    fun restore(distance: Long, reset: Boolean = false) {
        world.distance = max(0L, distance)
        // The picture starts from what has been committed, and in one showing never goes back (a rewind of the records is the one way back).
        world.target = if (reset) world.distance.toInt() else max(world.target, world.distance.toInt())
        world.pos = max(world.pos.takeIf { !reset } ?: 0.0, world.target.toDouble())
        if (reset) world.coast = 0.0
        world.journey.step = -1
        world.sceneChanged = true
        dirtyLayout = true
    }

    /** 0 輕量, 1 標準, 2 完整, 3 極致. [opaque]: the system asks for more contrast, so the picture is a plain sheet and nothing moves. */
    fun configure(dark: Boolean, level: Int, still: Boolean, opaque: Boolean, crab: Boolean) {
        val lv = level.coerceIn(0, 3)
        if (theme.level != lv) { theme.level = lv; world.ripples.clear(); dirtyLayout = true }
        if (theme.dark != dark) { theme.dark = dark; world.dirty = true }
        val reduced = still || opaque
        if (theme.still != reduced) {
            theme.still = reduced
            world.ripples.clear(); world.motes.clear()
            world.pos = world.target.toDouble()
            world.dirty = true
        }
        if (theme.opaque != opaque) { theme.opaque = opaque; world.dirty = true }
        world.crabAllowed = crab
        world.dirty = true
    }

    fun setLayout(next: GlyphLayout) {
        if (next == layout) return
        layout = next
        world.catSpace = min(next.width, GlyphWorld.CAT_SPACE)
        dirtyLayout = true
        world.dirty = true
    }

    /** Debug and tests: stay in one place, or null to travel again. */
    fun force(scene: Int?) {
        world.journey.forced = scene
        world.sceneChanged = true
        world.dirty = true
    }

    fun setCandidates(has: Boolean) {
        if (hasCandidates != has) { hasCandidates = has; world.dirty = true }
    }

    // ---- what the person does -----------------------------------------------------------------------

    /** A key went down at ([x], [y]) in keyboard points. [composing]: a reading is being typed, so a commit follows Space or Return. */
    fun key(kind: GlyphKey, x: Double, y: Double, composing: Boolean, letter: String = "") {
        val at = if (theme.level >= 2 && !theme.opaque) x to y else null
        when (kind) {
            GlyphKey.LETTER -> world.letter(letter, at)
            GlyphKey.DELETE -> world.backspace(at)
            GlyphKey.SPACE -> if (!composing) world.plain(at, enter = false) else world.chosen(at)
            GlyphKey.RETURN -> if (!composing) world.plain(at, enter = true) else world.chosen(at)
            GlyphKey.OTHER -> world.other(at)
        }
    }

    fun committed(text: String, graphemes: Int, japanese: Boolean, secret: Boolean) {
        // A word rises through the seam, into the strip above the keyboard, unless the picture is the safe one.
        world.committed(text, graphemes, japanese, secret, scape.skyOffset, layout.barTop, if (theme.opaque) 10.0 else 5.0 - layout.lift)
    }

    /** Debug and tests: hold one moment of the arrival (0..1), or null to let it run. */
    fun hold(form: Double?) { world.formFix = form; world.dirty = true }

    /** The candidates now on the bar (see [GlyphSpectacle.candidates]): birds may settle on their tops. */
    fun candidates(sig: Int, edges: DoubleArray, textTop: Double) { world.spectacle.candidates(sig, edges, textTop); world.dirty = true }

    /** Debug: hold one moment of an effect, as [GlyphSpectacle.hold] says. */
    fun holdFx(what: String?) { world.spectacle.hold(what); world.dirty = true }

    /** The keyboard was called up: the picture is typed into being (see [GlyphWorld.formed]). */
    fun arrive() = world.arrive()

    /** The keyboard went away. Nothing that was typed is remembered. */
    fun forget() = world.forget()

    // ---- the clock ----------------------------------------------------------------------------------

    /** Frames the place for the keyboard. True when it did (the place, the level or the layout is new). */
    private fun wear(): Boolean {
        val scene = world.scene
        if (scene == worn && theme.level == wornLevel && wornLayout == layout && !dirtyLayout) return false
        scape.wear(scene, layout)
        world.gain = scape.walkGain()
        worn = scene; wornLevel = theme.level; wornLayout = layout; dirtyLayout = false
        world.lanes = null
        world.sceneChanged = false
        world.dirty = true
        return true
    }

    /** One tick. True when the picture changed and wants drawing again. */
    fun step(): Boolean {
        val first = wear()
        var changed = world.step(layout, scape.rowsFraming, scape.ready, scape::ridgeAt)
        if (theme.deep) {
            // What passes in front, left in the picture when the typing stops, goes on out of it by itself (three units a tick).
            if (rolling()) { world.coast += 3.0; world.posMoved = true }
            // The picture moved on, and nothing else changed: it is drawn again only if something has gone a cell.
            if (world.posMoved) {
                world.posMoved = false
                val key = scape.motionKey()
                if (key != motionKey) { motionKey = key; changed = true }
            }
        }
        return wear() || changed || first
    }

    private var motionKey = 0L

    /** The foot comes down (see [GlyphWorld.land]): true when the picture is to be drawn again (a layer, a row of the ground has gone a cell). */
    fun land(): Boolean {
        if (!world.land()) return false
        val key = scape.motionKey()
        var changed = world.dirty || wear()
        world.dirty = false
        if (key != motionKey) { motionKey = key; changed = true }
        return changed
    }

    /** What passes in front is in the picture, the typing has stopped (0.35 s) and it is to go on out by itself. */
    private fun rolling(): Boolean =
        !theme.still && !theme.opaque && theme.level >= 1 && world.idleSeconds >= 0.35 && GlyphDepthFore.hasFore(scape.sceneName) &&
            GlyphMotion.visible(GlyphDepthFore.passesAt(world.foreRun, 0.0), scape.width)

    /** Whether another tick is worth taking. False means the keyboard is at rest. */
    fun busy(): Boolean = !theme.opaque && world.busy() || world.dirty || world.sceneChanged || (theme.deep && rolling())

    // ---- the picture --------------------------------------------------------------------------------

    /** The land, the sky and the air, behind the keys. Returns the glitch's tear, if there is one, for the screen to apply. */
    fun drawScene(g: GlyphSink): Tear? {
        wear()
        val colors = GlyphColors.of(theme.dark)
        scape.hush(hasCandidates, enabled = !theme.opaque, label = barLabel)
        if (theme.opaque) {
            // The safe picture: one plain sheet, and a rule for the cats to stand on.
            g.rect(0.0, 0.0, layout.width, layout.height + layout.tail, colors.paper)
            g.rect(0.0, 0.0, layout.width, 1.0, colors.ink)                    // one ruled line, and nothing crosses it
            scape.prepare()
            g.rect(0.0, scape.ridgeAt(world.catX(CatId.MOMO) + 20.0) + 2, layout.width, 2.0, colors.ink)
            return null
        }
        scape.draw(g, colors)
        return scape.tear()
    }

    /** What is nearest (see [GlyphNear]), or null without depth or in the safe picture. */
    fun near(): GlyphNear? = if (theme.deep && !theme.opaque) GlyphNear(scape.sceneName, scape.width, scape.height, scape.lift, theme.dark, theme.still, theme.level, world.fog, scape.lightSide, scape.lowLight(),
            foreHold?.let { it * scape.width } ?: GlyphDepthFore.passesAt(world.foreRun, 0.0), world.tick * 1000.0 / GlyphWorld.FPS, world.lastMove * 1000.0 / GlyphWorld.FPS) else null

    /** Debug and shots: what passes in front is held at this part of the width (null lets it go by). */
    @Volatile var foreHold: Double? = null

    /** The sun or moon of the picture, for the keyboard's type to be cut out of. */
    fun disc(): DiscInfo? = if (theme.opaque) null else scape.disc()

    /** What stands in front of the keys. */
    fun front(): GlyphFrontFrame {
        wear()
        val groundY = scape.groundTop
        val out = world.front(scape.rowsFraming, scape::ridgeAt, scape.skyOffset, groundY, scape.sceneName, showCats, scape.formed)
        // The name of the place keeps off the bar while there are candidates on it.
        return if (hasCandidates && out.bannerAlpha > 0f) out.copy(bannerAlpha = 0f) else out
    }

    /** Everything is where it was left: used before a first frame. */
    fun prime(ticks: Int = 2) { repeat(ticks) { step() } }
}
