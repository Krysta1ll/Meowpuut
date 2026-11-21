package com.krysta1ll.meowpuut.android.ime.glyph

import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise.h2
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise.round
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * How the keyboard's own effects setting reads here. Higher means smaller, denser type, more trees and landmarks, and more
 * that moves:
 *   0 輕量  large type, one redraw a key, nothing moves by itself       2 完整  + rings from the keys, type that turns by itself
 *   1 標準  the land scrolls, the cats walk, words rise                 3 極致  + finer type, drifting sky, moving water, stars that flicker
 */
internal class GlyphTheme(var dark: Boolean = false, var still: Boolean = false, var level: Int = 1, var opaque: Boolean = false) {
    /**
     * 縱深: the picture in pixels of one small size, apart from the size of the type, with planes that are paler the farther they are,
     * mist between them, a ground whose type is set closer where it is near, and a corner of something close (see GLYPH-DEPTH.md). Off, the picture is
     * the one of the first version, pixel for pixel (the tests that hold it to the prototype's numbers run that way).
     */
    var deep = false
    /** Good pictures in type keep to one small size and let weight and spacing do the shading; so does this one. */
    fun sized(cell: Int): Int = intArrayOf(cell + 4, cell + 2, min(cell, 6), 4)[level]
}

/** What the picture is told about the keyboard it is drawn behind: sizes and rows, in dp from the keyboard's top-left. */
internal class GlyphLayout(
    val width: Double,
    val height: Double,
    /** The vertical middle of each row of keys. */
    val rowCenters: DoubleArray,
    /** How tall the visible legend of a key is: the cats are as tall as the gap that leaves between two rows. */
    val chipHeight: Double,
    /** The middle of the candidate bar, and its top and height. */
    val barMid: Double,
    val barTop: Double,
    val barHeight: Double,
    /** The skyline framing's ridge is never higher than this (the cats stand on it and are thirty points tall). The prototype has none. */
    val skylineFloor: Double = GlyphScape.MIN_SKYLINE_RIDGE,
    /** The strip above the keyboard that the picture also paints (the seam), in points; 0 for none. */
    val lift: Double = 0.0,
    /** Sky the keyboard already has above its keys (no height added): the seam thins into that instead of a strip. */
    val inset: Double = 0.0,
    /** Where the skyline places put their ridge (the cats' feet): just above the first row's legends, under the candidate bar. 0 keeps the old high ridge. */
    val horizon: Double = 0.0,
    /** How far below the keys the picture runs on, under the system's navigation strip (very faint). 0 when the keyboard ends there. */
    val tail: Double = 0.0,
    /** How many points one pixel of a cat is: two, or less where the keys stand too close for a cat of two to walk between them. */
    val catUnit: Double = 2.0,
    /** The app pages' pictures (home, 行旅): the place framed as a picture of its own, not for a keyboard (see [Framing.WIDE]). */
    val wide: Boolean = false,
) {
    /** Where the cats' feet are in the gap under key row [n] (0 = between the first two rows). Null when there is no such gap. */
    fun laneFeet(n: Int): Double? =
        if (n < 0 || n + 1 >= rowCenters.size) null else (rowCenters[n] + rowCenters[n + 1]) / 2 + chipHeight / 2

    val rows: Boolean get() = rowCenters.size >= 3

    override fun equals(other: Any?): Boolean = other is GlyphLayout && width == other.width && height == other.height &&
        rowCenters.contentEquals(other.rowCenters) && chipHeight == other.chipHeight && barMid == other.barMid &&
        barTop == other.barTop && barHeight == other.barHeight && skylineFloor == other.skylineFloor && lift == other.lift && inset == other.inset &&
        horizon == other.horizon && tail == other.tail && catUnit == other.catUnit && wide == other.wide

    override fun hashCode(): Int = width.hashCode() * 31 + height.hashCode()

    companion object {
        val NONE = GlyphLayout(393.0, 298.0, doubleArrayOf(109.0, 163.0, 217.0, 271.0), 30.0, 57.0, 34.0, 46.0)
    }
}

internal class Ripple(val x: Double, val y: Double, val t: Int)
internal class Mote(val ch: String, val x: Double, val y: Double, val top: Double, val rise: Double, val life: Int, val t: Int, val sky: Int)
internal class LaneState { var y: Double? = null; var from = 0.0; var to = 0.0; /** 0 is the ridge; the lanes below it have a little ground to stand on. */ var lane = 0 }

internal enum class CatId(val key: String) { MOMO("momo"), MIMI("mimi") }

internal class CatState(val pose: String, val face: String, val z: Boolean = false, val hop: Boolean = false, val bob: Int = 0)

/** How a cat is drawn this frame: the front layer reads this and nothing else. */
internal class CatDraw(
    val cat: CatId, val pose: String, val face: String, val x: Int, val feetY: Int, val hopY: Int,
    val night: Boolean, val platform: Boolean, val afterImageDx: Int?, val zzz: Boolean, val zPhase: Int, val suit: Boolean = false,
)

internal class MoteDraw(val ch: String, val x: Double, val y: Double, val alpha: Float)

/** Everything that is drawn on top of the keys, resolved: the cats, rising words, a guest, the name of the place. */
internal data class GlyphFrontFrame(
    val cats: List<CatDraw>,
    val motes: List<MoteDraw>,
    val crabFrame: Int,
    val crabX: Int,
    val crabFeetY: Int,
    val fish: Pair<Int, Int>?,
    /** The name of the place, 0..1: how much of it is still to show; 0 when it is not shown. */
    val bannerAlpha: Float,
    val bannerText: String,
    /** The grains the running cats kick up. */
    val dust: List<DustDraw> = emptyList(),
) {
    companion object {
        val EMPTY = GlyphFrontFrame(emptyList(), emptyList(), -1, 0, 0, null, 0f, "")
    }
}

/**
 * The journey: the place is wherever the two cats have walked to. How far they have gone is the count of what was committed
 * (a single number, kept), and how long each place lasts is decided by the journey itself and shown to nobody. Near a border the
 * land thins into fog; on the far side of the fog is somewhere else. One place in ten is the rare one.
 */
internal class GlyphJourney {
    class Place(val scene: Int, val len: Int)

    var step = -1
    private val places = ArrayList<Place>()
    /** Debug and tests: stay in one place. */
    var forced: Int? = null

    fun placeAt(n: Int): Place {
        for (k in places.size..n) {
            val before = if (k > 0) places[k - 1].scene else -1
            val pick = GlyphScenes.COMMON.filter { it != before }
            // The rare places, one after the other along a single draw: the Moon a tenth of the places, the Moon seen from the Moon four in a hundred.
            var rare = -1
            if (k > 1 && before !in GlyphScenes.RARES) {
                val u = h2(k, 4242)
                for ((n, i) in GlyphScenes.RARES.withIndex()) {
                    val lo = GlyphScenes.rareFrom(n)
                    if (u >= lo && u < lo + GlyphScenes.ALL[i].rare) { rare = i; break }
                }
            }
            // The first place is always the sea, where the first-run guide sets the two cats down; the others are as they were, and each one is
            // not the one before it (the sea, for the second) and may be a rare one only from the third on.
            val scene = if (k == 0 && START >= 0) START else if (rare >= 0) rare else pick[(h2(k, 99) * pick.size).toInt()]
            places += Place(scene, MIN_LEN + (h2(k, 777) * SPAN).toInt())
        }
        return places[n]
    }

    private var cursorN = 0
    private var cursorFrom = 0L

    /** The place that a total distance falls in (its number, and the distance at which it begins). Walks on from the last answer. */
    fun at(distance: Long): Pair<Int, Long> {
        if (distance < cursorFrom) { cursorN = 0; cursorFrom = 0L }
        while (distance >= cursorFrom + placeAt(cursorN).len) { cursorFrom += placeAt(cursorN).len; cursorN++ }
        return cursorN to cursorFrom
    }

    /** The fog (0 thick .. 1 clear) that [distance] stands in: it thickens over the last [FOG] units of a place (coming into a new one is on the clock). */
    fun fogAt(distance: Long): Double {
        val (n, from) = at(distance)
        val len = placeAt(n).len
        val out = (from + len - distance).toDouble() / FOG
        return max(0.0, min(1.0, out))
    }

    companion object {
        /** The place every journey begins in: the sea (found by its name, so a reordering of the scenes does not move it). -1 if there is none. */
        val START: Int = GlyphScenes.ALL.indexOfFirst { it.name == "海" }

        /** Units of the journey a committed character is worth (a Chinese character's worth of keys); the same on the iPhone. */
        const val UNITS_PER_CHARACTER = 8L

        /** How far the cats have walked for this many committed characters (the cats' own count; saturating). */
        fun distanceOf(characters: Long): Long =
            if (characters <= 0L) 0L else if (characters > Long.MAX_VALUE / UNITS_PER_CHARACTER) Long.MAX_VALUE else characters * UNITS_PER_CHARACTER

        /** In units of the journey. */
        const val MIN_LEN = 2400
        const val SPAN = 1600
        /** Units before the end of a place in which the land is in fog (two keys' worth): the arrival is on the clock, see [GlyphWorld.ARRIVE_TICKS]. */
        const val FOG = 6
    }
}

/**
 * A word that does something. Nobody is told which. Matched against the last few characters committed, in memory only: nothing
 * typed is ever stored, and what has been read is dropped as soon as it has done its work, after a pause, or when the keyboard goes.
 * The pause is measured in wall-clock time, because the world's own clock stops when nothing moves.
 */
internal class GlyphSecrets {
    enum class Trigger { GUEST, GLITCH, GOODNIGHT, MEOW, FISH }

    private val tail = StringBuilder()
    private var lastAt = Long.MIN_VALUE / 2

    /** [text] was just committed at [nowMs]. [japanese]: the word came from the Japanese candidates. Returns what it woke. */
    fun feed(text: String, nowMs: Long, japanese: Boolean): List<Trigger> {
        if (nowMs - lastAt > FORGET_MS) tail.setLength(0)
        lastAt = nowMs
        tail.append(text)
        if (tail.length > MAX_TAIL) tail.delete(0, tail.length - MAX_TAIL)
        val seen = tail.toString()
        val found = ArrayList<Trigger>(1)
        if (seen.contains("claude", ignoreCase = true)) found += Trigger.GUEST
        if (seen.contains("glitch", true) || seen.contains("bug", true) || seen.contains("404")) found += Trigger.GLITCH
        if (seen.contains("晚安")) found += Trigger.GOODNIGHT
        if (seen.contains("喵")) found += Trigger.MEOW
        if (japanese && text == "寿司") found += Trigger.FISH
        // What has done its work is dropped, so a word does not fire again with the next letter.
        if (found.isNotEmpty()) tail.setLength(0)
        return found
    }

    fun clear() = tail.setLength(0)

    /** How many characters are held, for the tests: never more than [MAX_TAIL]. */
    val held: Int get() = tail.length

    companion object {
        const val MAX_TAIL = 12
        /** A pause (half a minute) after which what was typed is forgotten. */
        const val FORGET_MS = 30_000L
    }
}

/**
 * Everything that changes while the keyboard is up: where the world is, the ripples from the keys, the cats' moods, the journey.
 * One thread owns it (the engine's), and it never touches the screen: it answers [tick] with whether the picture changed.
 */
internal class GlyphWorld(
    val theme: GlyphTheme,
    private val clockHm: () -> Int = { -1 },
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    /** Where the picture has got to, in units of the journey: a fraction while it walks (see [step]). */
    var pos = 0.0
    var target = 0
    /** How far the land goes by for [pos]: with depth the picture goes [GAIN] of the way (every layer then by whole cells of that). */
    val run: Double get() = if (theme.deep) pos * gain else pos
    /** [run] for what passes in front: it keeps rolling by [coast] units when the typing has stopped. */
    val foreRun: Double get() = if (theme.deep) (pos + coast) * gain else pos + coast
    /**
     * With depth, how many cells of each layer a unit of the journey is worth: a step (three units) of the layer the cats walk on is one cell of it
     * (two at the finest type), so that the foot and the ground change together. Set with the place (see [GlyphScape.walkGain]).
     */
    var gain = 0.5
    /** What passes in front has been left to roll out of the picture by itself this far (units). */
    var coast = 0.0
    /** The picture moved on in this step without anything else changing: drawn again only if a layer, a row of the ground or a step has crossed a cell. */
    var posMoved = false
    var tick = 0
    val ripples = ArrayList<Ripple>()
    val motes = ArrayList<Mote>()
    var lastKey = -1_000_000_000
    var lastMove = 0
    var rate = 0.0
    var mood: Map<CatId, String>? = null
    var moodUntil = 0
    var hop = false
    var hopAt = 0
    var epoch = 0
    var echo: Echo? = null
    var fog = 1.0
    private var fogTarget = 1.0
    var arrive = -1_000_000_000
    var guest = 0
    var goodnight = 0
    var fish = -1_000_000_000
    var glitch = 0
    private val backs = ArrayList<Int>()
    var dirty = true
    var lanes: HashMap<CatId, LaneState>? = null
    val journey = GlyphJourney()
    /** The wind, the run, the storm, the firework, the birds on the candidates (see [GlyphSpectacle]). */
    val spectacle = GlyphSpectacle(this)
    val secrets = GlyphSecrets()
    /** The place the picture shows, and the one the journey asks for. Set by [travel]. */
    var scene = 0
    /** How far the journey has got, in committed characters. */
    var distance = 0L
    /** The guest crab (Anthropic's mascot) is allowed: off unless the build and the setting both say so. */
    var crabAllowed = false
    /** The tick at which the keyboard was called up: the picture is typed into being over [FORM] ticks from then. */
    var born = -1_000_000_000
    /** Debug and tests: hold one moment of the arrival (0..1). */
    var formFix: Double? = null
    /** The width the cats' places are a part of: the keyboard's, up to [CAT_SPACE]. Set with the layout. */
    var catSpace = 393.0

    fun catX(cat: CatId): Double = catSpace * (if (cat == CatId.MOMO) MOMO_AT else MIMI_AT)

    class Echo(val ch: String, val t: Int, val n: Int)

    /**
     * How far the picture has come, 0..1: the keys and the candidates are there at once, the picture is set from left to right, first as type
     * alone, then the heavy tones close into blocks, the sun or moon fills in, the cats open their eyes. Not at the lowest effects level, and not
     * with a still picture.
     */
    fun formed(): Double {
        if (theme.still || theme.level == 0) return 1.0
        formFix?.let { return it }
        return max(0.0, min(1.0, (tick - born).toDouble() / FORM))
    }

    /** The keyboard was called up: the place begins to be typed. */
    fun arrive() {
        if (tick - born <= FORM) return          // already arriving: being asked twice (attached, then shown) is one arrival
        born = tick
        dirty = true
    }

    val idleSeconds: Double get() = (tick - lastMove) / FPS.toDouble()

    // ---- what the person does ------------------------------------------------------------------------

    /** The keys moved the world by [amount]; [at] is where a ring runs out from. */
    fun moved(amount: Int, at: Pair<Double, Double>?) {
        val now = tick
        val rested = now - lastMove > FPS                          // the cats were not at their stride: a key wakes them (one picture), not every key after
        rate = rate * 0.6 + (FPS.toDouble() / max(1, now - lastKey)) * 0.4
        lastKey = now
        lastMove = now
        target += amount
        if (theme.still || theme.level == 0) pos = target.toDouble()
        else if (at != null && theme.level >= 2 && !theme.deep) ripples += Ripple(at.first, at.second, now)   // deep: the ring is its own view, by the screen (GlyphRingView)
        if (!theme.deep || rested) dirty = true                    // a key does not draw the picture again with depth: the walk does, if a cell has gone
    }

    /** The step in hand comes down: the picture is where the keys have taken it. True when that moved anything. */
    fun land(): Boolean {
        if (pos >= target) return false
        pos = target.toDouble()
        lastMove = tick
        travel()
        return true
    }

    fun feel(m: Map<CatId, String>, ticks: Int, jump: Boolean) {
        mood = m; moodUntil = tick + ticks; hop = jump; hopAt = tick; dirty = true
    }

    /**
     * How far a key that types something takes the picture: three, but the picture and the committed journey are kept together. When the picture
     * has got ahead of what has been committed it steps shorter, when behind longer, so the difference closes without ever being jumped:
     * d >= 48 ahead: 1; 24..47: 2; within 24: 3; 24..47 behind: 4; 48 or more behind: 5. A key always moves it, at least one.
     */
    fun stride(): Int {
        val d = target - distance
        return when {
            d >= 48 -> 1
            d >= 24 -> 2
            d > -24 -> 3
            d > -48 -> 4
            else -> 5
        }
    }

    /** A key that types a letter, a sign, a kana: the picture goes on by a stride. */
    fun letter(ch: String, at: Pair<Double, Double>?) {
        if (!theme.still && theme.level >= 1 && !theme.deep) {      // deep: the type turns by the clock only, never by a key (that is a flicker the eye reads as noise)
            epoch++
            echo = Echo(ch, tick, epoch)
        }
        moved(if (theme.deep) STEP_UNITS.toInt() else stride(), at)       // with depth every key is one step (three units), whatever the picture is behind or ahead by
    }

    /** Backspace: the cat looks aside; five in quick succession tear the picture. */
    fun backspace(at: Pair<Double, Double>?) {
        feel(mapOf(CatId.MIMI to "left", CatId.MOMO to "neutral"), 6, false)
        moved(0, at)
        backs.removeAll { tick - it >= 14 }
        backs += tick
        if (backs.size >= 5) { backs.clear(); feel(mapOf(CatId.MIMI to "angry", CatId.MOMO to "neutral"), 16, false); glitch(4) }
    }

    /** Space or return with nothing being composed. */
    fun plain(at: Pair<Double, Double>?, enter: Boolean) {
        if (enter) { feel(mapOf(CatId.MIMI to "meow", CatId.MOMO to "meow"), 8, false); spectacle.sent() }
        moved(if (theme.deep) STEP_UNITS.toInt() else 1, at)
    }

    /** Any other key: delete, a switch, the symbols' page: the picture stays. */
    fun other(at: Pair<Double, Double>?) = moved(0, at)

    /** A space or a return that chooses (it is composing): the picture goes on by one, as for the real ones. */
    fun chosen(at: Pair<Double, Double>?) = moved(if (theme.deep) STEP_UNITS.toInt() else 1, at)

    /**
     * Text was committed. [graphemes] carry the journey on; [secret] says whether the text may be read for passwords (never in a field
     * that asked not to be learned from). [skyOffset] anchors the rising words to the drifting sky; [barY] is where they rise from.
     */
    fun committed(text: String, graphemes: Int, japanese: Boolean, secret: Boolean, skyOffset: Int, barY: Double, top: Double = 10.0) {
        distance += max(0, graphemes).toLong() * GlyphJourney.UNITS_PER_CHARACTER
        feel(if (japanese) mapOf(CatId.MIMI to "sparkle", CatId.MOMO to "love") else mapOf(CatId.MIMI to "happy", CatId.MOMO to "happy"), 12, true)
        if (secret) for (t in secrets.feed(text, nowMs(), japanese)) when (t) {
            GlyphSecrets.Trigger.GUEST -> if (crabAllowed) guest = tick + 30 * FPS
            GlyphSecrets.Trigger.GLITCH -> glitch(22)
            GlyphSecrets.Trigger.GOODNIGHT -> goodnight = tick + 40 * FPS
            GlyphSecrets.Trigger.MEOW -> feel(mapOf(CatId.MIMI to "meow", CatId.MOMO to "meow"), 14, true)
            GlyphSecrets.Trigger.FISH -> fish = tick + 2
        }
        if (!theme.still && theme.level >= 1) {
            var n = 0
            var i = 0
            while (i < text.length && n < MAX_MOTES) {
                val cp = text.codePointAt(i)
                val s = String(Character.toChars(cp))
                if (!Character.isWhitespace(cp)) motes += Mote(s, 44.0 + n * 22.0, barY + 12, top, 2.4, 26, tick + n, skyOffset)
                i += Character.charCount(cp); n++
            }
        }
        lastMove = tick; dirty = true                               // the cats are awake; the picture does not move for a commit
    }

    fun glitch(ticks: Int) {
        if (theme.still || theme.level < 2) return
        glitch = max(glitch, tick + ticks)
        dirty = true
    }

    /** The keyboard went away: nothing typed is remembered. */
    fun forget() {
        secrets.clear()
        ripples.clear(); motes.clear()
        spectacle.reset()
    }

    // ---- the journey -----------------------------------------------------------------------------------

    /** Sets the journey's place from the distance it has gone, and the fog from how near a border it is. */
    fun travel() {
        val forced = journey.forced
        if (forced != null) {
            if (scene != forced) { scene = forced; sceneChanged = true; dirty = true }
            fogTarget = 1.0; fog = 1.0
            return
        }
        val (n, _) = journey.at(target.toLong())                  // where the picture is decides the place (and the fog), not what has been committed
        if (n != journey.step) {
            val arrived = journey.step >= 0
            journey.step = n
            scene = journey.placeAt(n).scene
            sceneChanged = true
            dirty = true
            // The name of the place shows only when the places change under the person's hands, not when the keyboard first appears.
            if (arrived) {
                arrive = tick
                if (h2(n, 5) < 0.3) glitch(8)
            }
        }
        // The land goes into fog over the last few units of a place, and a new place is set by the clock whatever the typing does.
        val into = if (tick - arrive < ARRIVE_TICKS) (tick - arrive + 1).toDouble() / ARRIVE_TICKS else 1.0
        fogTarget = min(journey.fogAt(target.toLong()), into)
        if (fog != fogTarget) { fog = fogTarget; dirty = true }
    }

    /** True until the picture has been rebuilt for the place that [travel] chose. */
    var sceneChanged = true

    // ---- lanes ---------------------------------------------------------------------------------------

    private fun laneWant(cat: CatId, layout: GlyphLayout): Int {
        val idle = idleSeconds
        val fast = if (idle < 1.5) rate else 0.0
        val calm = theme.still || theme.level == 0
        if (calm) return 0
        val lanes = max(0, layout.rowCenters.size - 2)
        val want = if (theme.deep) when (cat) {
            // While typing the cats walk in place on their own row; Mimi goes down to look at another row only after the typing has stopped.
            CatId.MOMO -> 0
            CatId.MIMI -> if (idle < 1.5 || idle > 5) 0 else intArrayOf(0, 1, 0, 2)[(pos / 180).toInt() and 3]
        } else when (cat) {
            CatId.MOMO -> if (fast > 8) 1 else 0
            CatId.MIMI -> if (idle > 5) 0 else if (fast > 6) 2 else intArrayOf(0, 1, 0, 2)[(pos / 45).toInt() and 3]
        }
        return min(want, lanes)
    }

    /** Where each cat stands: the ridge, or a lane lower down. Lane 0 is the ridge; a change of lane is a small jump. */
    fun lanes(layout: GlyphLayout, rows: Boolean, ready: Boolean, ridgeY: (Double) -> Double) {
        if (!rows || !ready) { lanes = null; return }
        val calm = theme.still || theme.level == 0
        val map = lanes ?: HashMap<CatId, LaneState>().also {
            it[CatId.MOMO] = LaneState(); it[CatId.MIMI] = LaneState(); lanes = it
        }
        for (cat in CatId.entries) {
            val l = map.getValue(cat)
            val want = laneWant(cat, layout)
            val lowerFeet = if (want > 0) layout.laneFeet(want) else null
            l.lane = if (lowerFeet != null) want else 0
            val target = lowerFeet ?: (ridgeY(catX(cat) + 20.0) + 2)
            if (l.y == null || calm) { l.y = target; l.from = target; l.to = target; continue }
            if (l.to != target) {
                if (abs(l.to - target) > 12) l.from = l.y!!
                l.to = target
            }
            if (abs(l.y!! - target) > 0.5) {
                l.y = l.y!! + (target - l.y!!) * 0.45
                if (abs(l.y!! - target) <= 0.5) l.y = target
                dirty = true
            }
        }
    }

    // ---- the clock -------------------------------------------------------------------------------------

    /** One step of the world, twelve a second. Returns whether the picture needs drawing again. */
    fun step(layout: GlyphLayout, rows: Boolean, ready: Boolean, ridgeY: (Double) -> Double): Boolean {
        tick++
        if (pos < target) {
            if (theme.deep) {
                // With depth the foot comes down on its own timer (see [land]); this is only the net for a timer that did not come (the keyboard went away).
                if (tick - lastKey > 4) { pos = target.toDouble(); posMoved = true }
            } else {
                pos += max(1, min(6, round((target - pos) * 0.42)))
                dirty = true
            }
            lastMove = tick
        }
        travel()
        lanes(layout, rows, ready, ridgeY)
        if (spectacle.step()) dirty = true
        ripples.removeAll { tick - it.t >= 7 }; if (ripples.isNotEmpty()) dirty = true
        motes.removeAll { tick - it.t >= it.life }; if (motes.isNotEmpty()) dirty = true
        if (mood != null && tick <= moodUntil + 1) dirty = true
        if (tick - born <= FORM + 1) dirty = true
        if (tick - arrive <= 31 || tick - fish <= 19 || tick == guest || tick == goodnight) dirty = true
        val idle = idleSeconds
        val beat = tick % 46
        val turn = (if (theme.deep) intArrayOf(0, 6, 6, 3) else intArrayOf(0, 0, 6, 3))[theme.level]   // deep: twice a second from the standard level, four at the highest
        if (!theme.still && turn != 0 && idle < 20 && tick % turn == 0) {
            epoch++; dirty = true
            val hm = clockHm()
            if (h2(epoch, 909) < 0.008 || (hm == 404 && epoch % 9 == 0)) glitch(5)
        }
        if (tick <= glitch) dirty = true
        if (!theme.still && (doubleArrayOf(1.2, 4.0, 5.5, 9.0, 11.0, 12.0, 15.0, 16.0).any { abs(idle - it) < 0.05 } || (idle < 5.6 && (beat == 10 || beat == 14)) ||
                (idle < 12 && (beat == 0 || beat == 2 || beat == 17 || beat == 19)) || (idle in 12.0..<20.0 && tick % 8 == 0))) dirty = true
        val was = dirty
        dirty = false
        return was
    }

    /** Whether more steps are worth taking: something moves, or the cats are still going through their idle changes. */
    fun busy(): Boolean {
        if (pos < target || mood != null && tick <= moodUntil + 1 || ripples.isNotEmpty() || motes.isNotEmpty()) return true
        if (tick - born <= FORM + 1) return true
        if (spectacle.busy()) return true
        if (tick <= glitch + 1 || tick - arrive <= 32 || tick - fish <= 20 || tick <= guest + 1 || tick <= goodnight + 1) return true
        if (abs(fog - fogTarget) > 0.004) return true
        lanes?.let { map -> if (map.values.any { l -> l.y != null && abs(l.y!! - l.to) > 0.5 }) return true }
        // The cats' own small changes (sit, blink, doze) go on for twenty seconds after the last key.
        return !theme.still && idleSeconds < 20.8
    }

    // ---- the cats ------------------------------------------------------------------------------------

    fun catState(cat: CatId): CatState {
        val idle = idleSeconds
        val moving = pos < target && !theme.still
        val mimi = cat == CatId.MIMI
        // With depth the foot changes with the ground (every three units, a key's worth: at once when a step begins), the other cat half a step
        // behind, and every second step the body is a cat pixel higher; between two keys they stand in the stride they stopped in.
        val step = Math.round(target / STEP_UNITS) + (if (mimi) 0L else 1L)      // the step that has begun (the foot changes with the key)
        val foot = if (theme.deep) (if (step and 1L != 0L) "walkA" else "walkB") else null
        fun walk() = foot ?: (if (tick and 1 != 0) "walkA" else "walkB")
        val bob = 0                                                              // (the body's lift is the key's, drawn by the front view at once)
        if (tick < goodnight && !moving) return CatState("sleep", "neutral", z = mimi)
        if (tick < glitch) return CatState(if (moving) walk() else "sit", "dizzy")        // when the picture tears, so do they
        val m = mood
        if (m != null && tick < moodUntil) return CatState(if (moving) walk() else "sit", m[cat] ?: "neutral", hop = hop)
        if (moving) return CatState(foot ?: (if ((tick + (if (mimi) 0 else 1)) and 1 != 0) "walkA" else "walkB"),
            if (rate > 7) "wide" else if (mimi) "focus" else "neutral", bob = bob)
        if (idle < 1.2) return CatState(foot ?: "walkA", "neutral", bob = bob)
        if (tick - arrive < 26) return CatState("sit", "up")                                                                   // somewhere new: they look up at it
        // Left alone they have an evening of their own: she turns to watch the view, he follows; then the loaf, a yawn, sleep.
        if (idle > (if (mimi) 12 else 16)) return CatState("sleep", "neutral", z = mimi)
        if (idle > (if (mimi) 9 else 11)) return CatState("loaf", if (idle > (if (mimi) 11 else 15)) "yawn" else if (mimi) "smug" else "blink")
        if (idle > (if (mimi) 5.5 else 4.0) && !(mimi && theme.dark && !GlyphScenes.ALL[scene].suit)) return CatState("back", "neutral")
        val beat = (tick + (if (mimi) 0 else 17)) % 46
        val turn = tick / 46
        val face = when {
            beat < 2 -> "blink"
            mimi && h2(turn, 31) < 0.18 && beat < 14 -> "blep"
            !mimi && h2(turn, 32) < 0.12 && beat < 10 -> "wink"
            else -> "neutral"
        }
        return CatState("sit", face)
    }

    /** The picture of the front layer for this frame. [ridgeY] answers where the walking ridge is at x; [skyOffset] anchors the words. */
    fun front(rows: Boolean, ridgeY: (Double) -> Double, skyOffset: Int, groundY: Double, sceneName: String, showCats: Boolean, form: Double = 1.0): GlyphFrontFrame {
        val cats = ArrayList<CatDraw>(2)
        val dust = ArrayList<DustDraw>(0)
        // The cats come last, eyes first.
        if (showCats && form >= 0.45) for (cat in listOf(CatId.MOMO, CatId.MIMI)) {
            val st = catState(cat)
            val x = catX(cat)
            val lane = lanes?.get(cat)
            var feet: Double
            var moving = false
            var platform = false
            if (rows && lane?.y != null) {
                val span = abs(lane.to - lane.from)
                val left = abs(lane.to - lane.y!!)
                val jump = if (span > 12 && left > 0.5) Math.sin(Math.PI * (1 - left / span)) * 7 else 0.0
                feet = lane.y!! - jump
                moving = left > 0.5
                platform = left <= 0.5 && lane.lane > 0
            } else feet = ridgeY(x + 20.0) + 2
            // Running: the cats bound along, a little forward on every other step.
            val run = spectacle.hurrying && st.pose != "sleep"
            val hopY = (if (run) -(tick and 1) * 3 else if (st.hop) intArrayOf(0, -3, -5, -3, 0)[min(4, max(0, tick - hopAt))] else 0) - st.bob
            var pose = st.pose
            if (moving && pose != "sleep") pose = if (tick and 1 != 0) "walkA" else "walkB"
            val night = (theme.dark && cat == CatId.MIMI && !(mood != null && tick < moodUntil)) || form < 0.8
            val after = if (tick < glitch) -(6 + (10 * h2(tick, round(x))).toInt()) else null
            cats += CatDraw(cat, pose, st.face, round(x) + (if (run) (tick and 1) * 2 else 0), round(feet), hopY, night, platform, after, st.z, (tick / 8) and 0xFFFF, GlyphScenes.ALL[scene].suit)
            if (run) dust += spectacle.dust(x, feet, if (cat == CatId.MOMO) 0 else 1)
        }
        val motesOut = ArrayList<MoteDraw>(motes.size)
        for (m in motes) {
            val age = tick - m.t
            if (age < 0) continue
            val x = m.x - (skyOffset - m.sky) * 3.0 - age * 0.6
            val y = max(m.top, m.y - age * m.rise)
            motesOut += MoteDraw(m.ch, x, y, max(0.0, min(1.0, 1.4 - 1.4 * age / m.life)).toFloat())
        }
        val crabOn = tick < guest && crabAllowed
        val crabX = 62
        val crabFeet = round(ridgeY(crabX + 10.0) + 2)
        val age = tick - fish
        val fishAt = if (age in 0..17) {
            val fx = crabX - 40 + age * 9
            val fy = crabFeet - 8 - Math.sin(age / 18.0 * Math.PI) * 46
            fx to round(fy)
        } else null
        val since = tick - arrive
        val bannerAlpha = if (since in 0..29 && sceneName.isNotEmpty()) min(1.0, (30 - since) / 8.0).toFloat() * 0.9f else 0f
        return GlyphFrontFrame(cats, motesOut, if (crabOn) (if (pos < target) tick and 1 else 0) else -1, crabX, crabFeet, fishAt, bannerAlpha, sceneName, dust)
    }

    companion object {
        const val FPS = 12
        /** Units of the journey to a step of the cats (a typing key's worth). */
        const val STEP_UNITS = 3.0
        /** Ticks a new place takes to come out of the fog, whatever the typing does. */
        const val ARRIVE_TICKS = 8
        /** Where the cats sit, as a part of the keyboard's width (104 and 172 points on the prototype's 393). */
        const val MOMO_AT = 104.0 / 393.0
        const val MIMI_AT = 172.0 / 393.0
        /** Past this width the cats do not drift further apart: a tablet keeps them together. */
        const val CAT_SPACE = 480.0
        const val MAX_MOTES = 8
        /** Ticks the arrival takes: a little over half a second at twelve a second. */
        const val FORM = 7
    }
}
