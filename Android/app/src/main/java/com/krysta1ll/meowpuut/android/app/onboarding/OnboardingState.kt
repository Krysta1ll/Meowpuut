package com.krysta1ll.meowpuut.android.app.onboarding

import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise.h2
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise.q
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.reflect.KMutableProperty0

/** Every word the guide says, in the script of the interface. Filled from resources; a test fills it with anything. */
internal class OnboardingCopy(
    val skip: String, val skipLabel: String,
    val a1Line: String, val a1Hint: String, val a1After: String, val a1Alt: String, val a1AltAfter: String,
    val a2Lines: List<String>, val a2Steps: List<String>, val a2Note: String, val a2Go: String, val a2GoLabel: String, val a2Back: String,
    val a2Done: String, val a2Announce: String, val a2Alt: String, val a2AltDone: String,
    val a3Lines: List<String>, val a3TagSwitch: String, val a3TagType: String, val a3TagPick: String, val a3TagLabel: String, val a3Other: String,
    val a3Onward: String, val a3Eyes: String, val a3Mimi: String, val a3Pick: String, val a3Momo: String,
    val a3AnnounceEyes: String, val a3AnnounceMimi: String, val a3AnnounceMomo: String, val a3Alt: String,
    val a4Lines: List<String>, val a4Note: String, val order: List<Scheme>, val schemes: Map<Scheme, String>, val a4Chosen: String, val a4Announce: String, val a4Alt: String,
    val a5Line: String, val a5Alt: String,
    val a6Lines: List<String>, val a6Foot: List<String>, val a6Wear: String, val a6Worn: String, val a6WornLine: String, val a6WearLabel: String,
    val a6Enter: String, val a6Alt: String, val a6Announce: String,
)

/** The names of the guide's strings (the resources are these with "onboarding_" before them), in the order [onboardingCopy] reads them. */
internal val ONBOARDING_KEYS = listOf(
    "skip", "skip_label", "a1_line", "a1_hint", "a1_after", "a1_alt", "a1_alt_after",
    "a2_line1", "a2_line2", "a2_step1", "a2_step2", "a2_step3", "a2_note", "a2_go", "a2_go_label", "a2_back", "a2_done", "a2_announce", "a2_alt", "a2_alt_done",
    "a3_line1", "a3_line2", "a3_tag_switch", "a3_tag_type", "a3_tag_pick", "a3_tag_label", "a3_other", "a3_onward", "a3_eyes", "a3_mimi", "a3_pick", "a3_momo",
    "a3_announce_eyes", "a3_announce_mimi", "a3_announce_momo", "a3_alt",
    "a4_line1", "a4_line2", "a4_note", "a4_order", "scheme_zhuyin", "scheme_pinyin", "scheme_nine_key", "a4_chosen", "a4_announce", "a4_alt",
    "a5_line", "a5_alt", "a6_line1", "a6_line2", "a6_foot1", "a6_foot2", "a6_wear", "a6_worn", "a6_worn_line", "a6_wear_label", "a6_enter", "a6_alt", "a6_announce",
)

/** The copy, from whatever holds the strings: [s] answers one of [ONBOARDING_KEYS]. The order of the three roads follows the script of the interface. */
internal fun onboardingCopy(s: (String) -> String) = OnboardingCopy(
    skip = s("skip"), skipLabel = s("skip_label"),
    a1Line = s("a1_line"), a1Hint = s("a1_hint"), a1After = s("a1_after"), a1Alt = s("a1_alt"), a1AltAfter = s("a1_alt_after"),
    a2Lines = listOf(s("a2_line1"), s("a2_line2")), a2Steps = listOf(s("a2_step1"), s("a2_step2"), s("a2_step3")), a2Note = s("a2_note"), a2Go = s("a2_go"),
    a2GoLabel = s("a2_go_label"), a2Back = s("a2_back"), a2Done = s("a2_done"), a2Announce = s("a2_announce"), a2Alt = s("a2_alt"), a2AltDone = s("a2_alt_done"),
    a3Lines = listOf(s("a3_line1"), s("a3_line2")), a3TagSwitch = s("a3_tag_switch"), a3TagType = s("a3_tag_type"), a3TagPick = s("a3_tag_pick"), a3TagLabel = s("a3_tag_label"),
    a3Other = s("a3_other"), a3Onward = s("a3_onward"), a3Eyes = s("a3_eyes"), a3Mimi = s("a3_mimi"), a3Pick = s("a3_pick"), a3Momo = s("a3_momo"),
    a3AnnounceEyes = s("a3_announce_eyes"), a3AnnounceMimi = s("a3_announce_mimi"), a3AnnounceMomo = s("a3_announce_momo"), a3Alt = s("a3_alt"),
    a4Lines = listOf(s("a4_line1"), s("a4_line2")), a4Note = s("a4_note"),
    order = s("a4_order").split(",").mapNotNull { key -> Scheme.entries.firstOrNull { it.key == key.trim() } }.distinct().let { if (it.size == 3) it else Scheme.entries.toList() },
    schemes = mapOf(Scheme.ZHUYIN to s("scheme_zhuyin"), Scheme.PINYIN to s("scheme_pinyin"), Scheme.NINE_KEY to s("scheme_nine_key")),
    a4Chosen = s("a4_chosen"), a4Announce = s("a4_announce"), a4Alt = s("a4_alt"), a5Line = s("a5_line"), a5Alt = s("a5_alt"),
    a6Lines = listOf(s("a6_line1"), s("a6_line2")), a6Foot = listOf(s("a6_foot1"), s("a6_foot2")), a6Wear = s("a6_wear"), a6Worn = s("a6_worn"), a6WornLine = s("a6_worn_line"),
    a6WearLabel = s("a6_wear_label"), a6Enter = s("a6_enter"), a6Alt = s("a6_alt"), a6Announce = s("a6_announce"),
)

/** The three ways to type that the road parts into. [key] is the value the keyboard's setting takes. */
internal enum class Scheme(val key: String) { ZHUYIN("zhuyin"), PINYIN("pinyin"), NINE_KEY("nine-key") }

/** Something on the page that can be touched: a real control is laid over it, so it can be reached without sight. Points. */
internal class Hot(val id: String, val x: Double, val y: Double, val w: Double, val h: Double, val label: String)

/** What the guide asks of the app. Nothing here carries what was typed. */
internal interface OnboardingHost {
    fun openKeyboardSettings()
    fun showKeyboardPicker()
    /** Raise the keyboard on the line to type on, or put it away and empty the line. */
    fun keyboard(show: Boolean)
    /** Spoken to a screen reader: a line of narration, or a step that has just been completed. */
    fun say(text: String)
    /** What the picture now shows, for someone who cannot see it. */
    fun describe(text: String)
    fun hots(list: List<Hot>)
    fun chose(scheme: Scheme)
    fun keepSkin()
    /** The guide has been seen through, or skipped: it does not come up by itself again. */
    fun done()
    /** Leave for the home screen. */
    fun leave()
}

/** The page, in points. The width is always 393; the height is the screen's. */
internal class OnboardingGeo(val h: Double, val top: Double, val big: Boolean, val bottom: Double = 0.0) {
    val w = 393.0
    val ridge = q(h * 0.634, 2.0)
    val nx = 28.0
    val ny = top + 64
    val lineY = top + 198
    val s2 = if (big) 3 else 2
    val s1 = if (big) 2 else 1
}

/**
 * The guide itself (第一行字), without a screen: what the person does goes in ([tap], [enabled], [keyboardIsOurs], [typed], [committed],
 * [returned], [skip], and [tick] twelve times a second), and what comes out is how far every part of the picture has got, the narration,
 * what can be touched, and a few requests of the app ([OnboardingHost]). It never receives the text that is typed: only that a key was
 * pressed and that a word was committed. One thread owns it.
 *
 * Six acts. There is no "next": an act ends when the thing it asks for has been done.
 *   1 a sheet of paper and a caret; a touch, and the first glyphs fall and pile into ground
 *   2 a signpost through the system's settings; when the keyboard is found enabled the sun comes up and the far land grows
 *   3 a line to type on; with this keyboard in use a pair of eyes, then 米米莫; at the first committed word 莫莫 is typed into being
 *   4 the road parts in three: the one taken is the way of typing
 *   5 a gate that stands open (Android has no Full Access to ask for): one line about privacy, and the cats walk through
 *   6 fog, then the sea: the name is typed, and the skin worn on the way can be kept
 * Ported from design-preview/specs/glyph-onboarding.js; see design-preview/onboarding/README.md.
 */
internal class OnboardingState(val copy: OnboardingCopy, private val host: OnboardingHost, val geo: OnboardingGeo, private val advance: (String, Int) -> Int) {
    /** The width of [text] in pixels of the type's grid, set [s] points to the pixel. */
    private fun adv(text: String, s: Int = 2): Int = text.sumOf { advance(it.toString(), s) }
    // ---- the picture's dials: 0 not there, 1 there ------------------------------------------------------
    var pile = 0.0; var stars = 0.0; var horizon = 0.0; var post = 0.0; var postDone = 0.0; var sun = 0.0; var rise = 0.0
    var far = 0.0; var mid = 0.0; var near = 0.0; var trees = 0.0; var sky = 0.0; var line = 0.0; var grove = 0.0; var tag = 0.0
    var signs = 0.0; var signDrop = 0.0; var gate = 0.0; var home = 0.0; var mark = 0.0; var foot = 0.0; var wear = 0.0; var hint = 0.0
    var fog = 1.0
    var cam = 0.0
    val roadEnd = doubleArrayOf(196.0, 196.0, 196.0)
    val roadWide = doubleArrayOf(1.0, 1.0, 1.0)

    class Cat(var on: Int = 0, var x: Double = 0.0) { var form = 1.0; var walk = false }
    val mimi = Cat(x = 176.0)
    val momo = Cat(x = 96.0)

    // ---- where things are -----------------------------------------------------------------------------
    var act = 0; private set
    var atHome = false; private set
    var done = false; private set
    var worn = false; private set
    var pick = -1; private set
    var scheme: Scheme? = null; private set
    var sunrise = false; private set
    var pos = START; private set
    var target = START; private set
    var tick = 0; private set
    var epoch = 0; private set
    var dirty = true
    var still = false
    /** Stills and tests: no waiting, and no moving on by itself. */
    var instant = false
    var hold = false
    var signPos = 0.0; private set
    var gatePos = 0.0; private set
    var gateOn = false; private set
    val gateX = 246.0
    var grovePos = 0.0; private set
    /** How much of the page the keyboard leaves, in points from the top. */
    var visibleBottom = geo.h; private set

    // the typing act
    var keys = 0; private set
    var otherKeys = 0; private set
    /** 0 nobody, 1 a pair of eyes, 2 out. */
    var mimiStage = 0; private set
    private var eyesAt = 0
    var committed = false; private set
    var onward = false; private set
    var leaving = false; private set
    private var toldOther = false
    private var lastKey = 0
    private var ours = false
    private var enabledNow = false

    // the cats' own small life
    private var lastMove = 0
    private var moodUntil = 0
    private var hopAt = -100

    // ---- time -----------------------------------------------------------------------------------------
    private class Tween(val get: () -> Double, val set: (Double) -> Unit, val to: Double, val n: Int, var d: Int, val done: (() -> Unit)?) { var i = 0; var from = Double.NaN }
    private class Timer(val at: Int, val run: () -> Unit)
    private val tweens = ArrayList<Tween>()
    private val timers = ArrayList<Timer>()
    private val quick: Boolean get() = still || instant

    private fun tween(p: KMutableProperty0<Double>, to: Double, n: Int, delay: Int = 0, done: (() -> Unit)? = null) = tween({ p.get() }, { p.set(it) }, to, n, delay, done)
    private fun tween(get: () -> Double, set: (Double) -> Unit, to: Double, n: Int, delay: Int = 0, done: (() -> Unit)? = null) {
        if (quick || n <= 0) { set(to); dirty = true; done?.invoke(); return }
        tweens += Tween(get, set, to, n, delay, done)
    }
    private fun after(n: Int, run: () -> Unit) { if (instant) run() else timers += Timer(tick + n, run) }
    /** On to the next act; not while a still is being taken. */
    private fun advance(n: Int, run: () -> Unit) { if (!hold) timers += Timer(tick + n, run) }

    // ---- the narration: someone on the other side of the paper, typing --------------------------------------
    class Line(val text: String, val small: Boolean = false, val mute: Boolean = false)
    class Glyph(val ch: String, val x: Double)
    class Row(val chars: List<Glyph>, val y: Double, val s: Int, val mute: Boolean)
    class Fall(val ch: String, val x: Double, var y: Double, val s: Int, var d: Int, val land: Double) { var v = 0.0 }
    class Drop(val ch: String, val x: Double, var y: Double, val v: Double, val s: Int)

    var rows: List<Row> = emptyList(); private set
    /** How many letters of the page are shown. */
    var shown = 0.0; private set
    var total = 0; private set
    val falls = ArrayList<Fall>()
    val rain = ArrayList<Drop>()
    private var pages: List<List<Row>> = listOf(emptyList())
    private var page = 0
    private var narHold = 0
    private var narThen: (() -> Unit)? = null

    /** Where the words go. A line that will not fit under the ones above starts a new page: large type takes more pages, not more room. */
    internal fun layout(lines: List<Line>): List<List<Row>> {
        val out = arrayListOf(ArrayList<Row>())
        val floor = if (act == 3) geo.lineY - 52 else geo.top + 286
        var y = geo.ny
        for (l in lines) {
            val s = if (l.small) geo.s1 else geo.s2
            val maxW = (geo.w - 2 * geo.nx) / s
            val pitch = 15.0 * s + 2
            val made = ArrayList<List<Glyph>>()
            var row = ArrayList<Glyph>()
            var w = 0
            for (unit in UNIT.findAll(l.text).map { it.value }) {            // a Latin word is not broken
                val a = adv(unit, s)
                if (w + a > maxW && w > 0 && unit !in CLOSERS) { made += row; row = ArrayList(); w = 0 }
                for (ch in unit) { row += Glyph(ch.toString(), geo.nx + w * s); w += adv(ch.toString(), s) }
            }
            if (row.isNotEmpty()) made += row
            val need = made.size * pitch - 4 * s
            if (y + need > floor && out.last().isNotEmpty() && geo.ny + need <= floor) { out += ArrayList<Row>(); y = geo.ny }
            for (r in made) {
                if (y + 11 * s > floor && out.last().isNotEmpty()) { out += ArrayList<Row>(); y = geo.ny }
                out.last() += Row(r, y, s, l.mute); y += pitch
            }
            y += 3 * s
        }
        return out
    }

    private fun showPage(i: Int) { page = i; rows = pages.getOrElse(i) { emptyList() }; total = rows.sumOf { it.chars.size }; shown = if (quick) total.toDouble() else 0.0; dirty = true }

    /** Lets the shown letters go: they drop into the land. */
    private fun drop(toY: Double? = null) {
        if (quick) return
        var i = 0
        for (r in rows) for (g in r.chars) {
            if (i >= shown) return
            falls += Fall(g.ch, g.x, r.y, r.s, i % 6, toY ?: (geo.ridge + cam + 22 + h2(i, 9) * 180)); i++
        }
    }

    private fun hush(toY: Double? = null) { drop(toY); pages = listOf(emptyList()); showPage(0); narThen = null }

    private fun narrate(lines: List<Line>, holdFor: Int = 3, then: (() -> Unit)? = null) {
        drop(); pages = layout(lines); showPage(0)
        narHold = holdFor; narThen = then
        host.say(lines.joinToString("") { it.text })
        if (instant) narThen?.let { narThen = null; it() }
    }
    private fun narrate(vararg text: String, then: (() -> Unit)? = null) = narrate(text.map { Line(it) }, then = then)

    private fun narTick() {
        if (narHold > 0) { narHold--; return }
        if (shown >= total) {
            if (page < pages.size - 1) { drop(); showPage(page + 1); narHold = if (quick) max(30, total * 2) else 3; return }   // turn the page
            narThen?.let { narThen = null; it() }
            return
        }
        shown = min(total.toDouble(), shown + 1.5); dirty = true
        var i = shown.toInt() - 1
        var ch = ""
        for (r in rows) { if (i < r.chars.size) { ch = r.chars[max(0, i)].ch; break }; i -= r.chars.size }
        if (shown >= total && page < pages.size - 1) narHold = max(26, (total * 1.6).roundToInt())      // time to read it before it goes
        else if (ch == "。") narHold = 5 else if (ch == "，" || ch == "：") narHold = 2
    }

    // ---- what can be touched --------------------------------------------------------------------------
    private var hotList: List<Hot> = emptyList()
    private fun setHots(list: List<Hot>) { hotList = list; host.hots(list) }
    private val skipHot get() = Hot("skip", geo.w - 66, geo.top + 2, 60.0, 44.0, copy.skipLabel)
    /** The way out is always there until the end. */
    private fun hots(vararg list: Hot) = setHots(if (atHome) list.toList() else list.toList() + skipHot)

    // ---- the acts -------------------------------------------------------------------------------------
    /** Begins at the beginning. */
    fun start() { enter(1) }

    private fun enter(n: Int) {
        act = n; dirty = true
        when (n) {
            1 -> {
                host.describe(copy.a1Alt)
                hots(Hot("paper", 0.0, geo.top + 50, geo.w, geo.h - geo.top - 50, copy.a1Hint))
                narrate(listOf(Line(copy.a1Line)), holdFor = 12) { after(10) { tween(::hint, 1.0, 4) } }
            }
            2 -> {
                host.describe(copy.a2Alt)
                hots()
                narrate(copy.a2Lines.map { Line(it) } + Line(copy.a2Note, small = true, mute = true))
                tween(::stars, 1.0, 10); tween(::horizon, 1.0, 8, 4); tween(::post, 1.0, 14, 10) { postHots() }
                if (enabledNow) after(30) { sunrise() }              // already enabled before the app was ever opened
            }
            3 -> {
                host.describe(copy.a3Alt)
                grovePos = target; keys = 0; otherKeys = 0; mimiStage = 0; committed = false; onward = false; leaving = false; toldOther = false; lastKey = tick
                if (!quick) for ((i, t) in copy.a2Steps.withIndex()) for ((k, ch) in t.withIndex()) falls += Fall(ch.toString(), 37.0 + k * 24, postY0() + i * postPitch() + cam, 2, i + k, geo.h + 30)
                post = 0.0
                narrate(*copy.a3Lines.toTypedArray())
                host.keyboard(true)
                tween(::line, 1.0, 8, 4); tween(::grove, 1.0, 8, 6); tween(::tag, 1.0, 3, 12) { tagHots() }
                tagHots(); oursChanged()
            }
            4 -> {
                host.describe(copy.a4Alt.format(copy.order.joinToString("、") { copy.schemes.getValue(it) }))
                grove = 0.0; target += 24; signPos = target
                hots()
                narrate(copy.a4Lines.map { Line(it) } + Line(copy.a4Note, small = true, mute = true))
                tween({ roadEnd[0] }, { roadEnd[0] = it }, FORK_X[0], 10, 8); tween({ roadEnd[2] }, { roadEnd[2] = it }, FORK_X[2], 10, 8)
                tween(::signs, 1.0, 9, 14) { forkHots() }
            }
            5 -> {
                host.describe(copy.a5Alt)
                signs = 0.0
                val at = roadEnd[if (pick < 0) 1 else pick]
                for (k in 0..2) { roadWide[k] = 1.0; roadEnd[k] = at }
                target += 50; gateOn = true; gatePos = target
                hots()
                tween(::gate, 1.0, 8, 6)
                for (k in 0..2) tween({ roadEnd[k] }, { roadEnd[k] = it }, gateX + 55, 12, 4)
                narrate(copy.a5Line) { advance(34) { pass() } }      // the gate stands open: nothing is asked
            }
            6 -> arrive(direct = false)
        }
    }

    private fun act1Tap() {
        hots(); hint = 0.0; hush(geo.h - 60)
        if (!quick) for (i in 0 until 150) rain += Drop(BOPO[(h2(i, 3) * BOPO.length).toInt()].toString(), q(h2(i, 4) * (geo.w - 14), 8.0), -20 - h2(i, 5) * 700, 30 + h2(i, 6) * 26, if (h2(i, 7) > 0.72) 2 else 1)
        tween(::pile, 1.0, 18, 3)
        after(24) { host.describe(copy.a1AltAfter); narrate(copy.a1After) { advance(16) { enter(2) } } }
    }

    // The signpost: how tall it stands depends on how much page there is under the horizon.
    fun postPitch(): Double = min(34.0, (geo.h - 44 - postY0() - 34) / copy.a2Steps.size)
    fun postY0(): Double = geo.ridge + if (copy.a2Steps.size > 4) 32 else 72
    private fun postHots() {
        if (act != 2 || sunrise) return
        val y = postY0() + copy.a2Steps.size * postPitch() + 4
        hots(Hot("post", 12.0, postY0() - 12, max(190.0, adv(copy.a2Go) * 2 + 60.0), y - postY0() + 44, copy.a2GoLabel))
    }

    private fun sunrise() {
        if (sunrise || act != 2) return
        sunrise = true; hots(); host.say(copy.a2Announce); host.describe(copy.a2AltDone)
        tween(::postDone, 1.0, 6); tween(::rise, 1.0, 20, 4); tween(::sun, 1.0, 18, 4); tween(::far, 1.0, 12, 10); tween(::mid, 1.0, 12, 13); tween(::near, 1.0, 10, 16)
        after(20) { if (!quick) ripple = Triple(SUN_X, geo.ridge - 110, tick) }
        after(12) { narrate(copy.a2Done) }
        advance(50) { enter(3) }
    }
    /** A ring that runs out through the land as the sun clears the range: where it began, and when. */
    var ripple: Triple<Double, Double, Int>? = null; private set

    // The slip of paper tied to the end of the line: what to do now.
    fun tagText(): String? = when {
        act != 3 || committed -> null
        mimiStage == 2 -> copy.a3TagPick
        mimiStage == 1 || ours -> copy.a3TagType
        else -> copy.a3TagSwitch
    }
    fun tagIsAsk(): Boolean = act == 3 && mimiStage == 0 && !ours
    private fun tagHots() {
        if (act != 3 || leaving) return
        val list = ArrayList<Hot>()
        val t = tagText()
        val w = if (t == null) 0.0 else adv(t) * 2 + 20.0
        if (t != null && tagIsAsk()) list += Hot("tag", geo.w - geo.nx - w - 4, geo.lineY + 10, w + 8, 42.0, copy.a3TagLabel)   // the system's picker
        if (onward && !committed) { val ow = adv(copy.a3Onward) * 2 + 36.0; list += Hot("onward", geo.w - geo.nx - ow, geo.lineY + 8 + (if (t != null) 44 else 0), ow, 38.0, copy.a3Onward) }
        hots(*list.toTypedArray())
    }

    private fun oursChanged() {
        if (act != 3 || leaving) return
        if (ours && mimiStage == 0) showEyes()
        tagHots(); dirty = true
    }
    private fun showEyes() { mimiStage = 1; eyesAt = keys; mimi.on = 1; mimi.x = 18.0; narrate(copy.a3Eyes); host.say(copy.a3AnnounceEyes) }
    private fun walkOut(fast: Boolean) {
        mimiStage = 2; mimi.on = 2; mimi.walk = true
        if (mimi.x > 100) mimi.x = 18.0
        tween({ mimi.x }, { mimi.x = it }, 176.0, if (fast) 5 else 12) { mimi.walk = false }
        if (!fast) narrate(copy.a3Mimi, copy.a3Pick)
        host.say(copy.a3AnnounceMimi); tagHots()
    }

    private fun leave3() {
        if (act != 3 || leaving) return
        leaving = true; hots()
        if (!quick) for (n in 0 until 8) falls += Fall(BOPO[(h2(n, 5) * BOPO.length).toInt()].toString(), geo.nx + 10 + n * 30, geo.lineY - 43, 3, n % 5, geo.ridge + cam + 30 + h2(n, 5) * 60)
        host.keyboard(false)                                         // what was typed is not kept: the line is emptied and gone
        tween(::line, 0.0, 4); tag = 0.0
        after(9) { enter(4) }
    }

    private fun forkHots() {
        if (act != 4 || pick >= 0) return
        hots(*copy.order.mapIndexed { k, s -> Hot("road$k", k * geo.w / 3, geo.ridge - 160, geo.w / 3, geo.h - geo.ridge + 140, copy.schemes.getValue(s)) }.toTypedArray())
    }
    private fun choose(k: Int) {
        if (act != 4 || pick >= 0 || k !in copy.order.indices) return
        val s = copy.order[k]
        val name = copy.schemes.getValue(s)
        pick = k; scheme = s; host.chose(s); hots(); host.say(copy.a4Announce.format(name)); narrate(copy.a4Chosen.format(name))
        for (j in 0..2) if (j != k) tween({ roadWide[j] }, { roadWide[j] = it }, 0.0, 7)
        tween(::signDrop, 1.0, 8); tween({ roadEnd[k] }, { roadEnd[k] = it }, 196.0, 16, 5)
        after(7) { target += 96 }
        advance(34) { enter(5) }
    }

    private var passing = false
    private fun pass() {
        if (passing) return
        passing = true; hots(); target += 70
        advance(24) { enter(6) }
    }

    private fun arrive(direct: Boolean) {
        act = 6; hots(); hush()
        val land = {
            atHome = true; gateOn = false; grove = 0.0; signs = 0.0; post = 0.0; line = 0.0; tag = 0.0; home = 0.0; fog = if (quick) 1.0 else 0.0
            pos = HOME_POS; target = HOME_POS; lastMove = tick - 30
            mimi.walk = false; momo.walk = false; mimi.form = 1.0; momo.form = 1.0; mimi.on = 2; momo.on = 2
            host.describe(copy.a6Alt)
            hots()
            tween(::fog, 1.0, 8); tween(::home, 1.0, 11)
            after(15) {
                tween(::mark, 8.0, 16, 6)
                narrate(*copy.a6Lines.toTypedArray()) {
                    after(12) {
                        tween(::foot, 1.0, 6)
                        done = true; host.done(); host.say(copy.a6Announce)
                        if (direct) homeHots() else tween(::wear, 1.0, 4, 8) { homeHots() }
                        advance(if (direct) 60 else 180) { host.leave() }   // it is the home screen already: after a while, it simply is
                    }
                }
            }
        }
        if (direct || quick) land() else { target += 40; tween(::fog, 0.0, 10, 0, land) }
    }
    /** The foot of the last page, from the bottom up: two small lines, the offer, the name. */
    fun footTop(): Double = geo.h - maxOf(36.0, geo.bottom + 14) - copy.a6Foot.size * (13 * geo.s1 + 3) + 3
    fun wearLabel(): String = if (worn) copy.a6Worn else copy.a6Wear
    fun wearWidth(): Double = adv(wearLabel()) * 2 + 24.0
    private fun homeHots() {
        if (!atHome) return
        val list = arrayListOf(Hot("home", 0.0, geo.top, geo.w, geo.h - geo.top, copy.a6Enter))
        if (wear > 0 && !worn) list += Hot("wear", (geo.w - wearWidth()) / 2 - 8, footTop() - 46, wearWidth() + 16, 44.0, copy.a6WearLabel)
        setHots(list)
    }
    private fun keepSkin() {
        if (!atHome || worn || wear <= 0) return
        worn = true; host.keepSkin(); narrate(copy.a6WornLine) { advance(30) { host.leave() } }; homeHots(); dirty = true
    }

    // ---- what the person does, and what the system says ----------------------------------------------------
    fun tap(id: String) {
        when {
            id == "skip" -> skip()
            id == "paper" && act == 1 -> act1Tap()
            id == "post" && act == 2 && !sunrise -> host.openKeyboardSettings()
            id == "tag" && act == 3 -> host.showKeyboardPicker()
            id == "onward" && act == 3 && onward -> leave3()
            id.startsWith("road") && act == 4 -> choose(id.removePrefix("road").toIntOrNull() ?: -1)
            id == "wear" -> keepSkin()
            id == "home" && done -> host.leave()
        }
    }

    /** The system's list of enabled keyboards has this one in it, or not. */
    fun enabled(now: Boolean) {
        enabledNow = now
        if (now && act == 2) sunrise()
    }
    /** Back in front after the system's settings, and the keyboard is still not enabled: say so once, calmly. */
    fun returned() { if (act == 2 && !sunrise && !enabledNow) narrate(copy.a2Back) }

    /** The keyboard now in use is this one, or another. */
    fun keyboardIsOurs(now: Boolean) { if (ours != now) { ours = now; oursChanged() } }

    /** A key was pressed on the line (the text changed). Which key, and what the text is, never comes here. */
    fun typed() {
        if (act != 3 || leaving) return
        lastKey = tick; moved(if (ours) 3.0 else 1.0)
        if (!ours) {                                                 // another keyboard: say so once, and after a while offer the way on
            otherKeys++
            if (otherKeys == 3 && !toldOther) { toldOther = true; narrate(copy.a3Other) }
            if (otherKeys == 8) { onward = true; tagHots() }
            return
        }
        keys++; epoch++
        trees = min(1.0, keys / 10.0); sky = min(1.0, keys / 14.0)
        if (mimiStage == 0) showEyes() else if (mimiStage == 1 && keys - eyesAt >= 2) walkOut(false)
        tagHots()
    }
    /** A word was chosen and put on the line. */
    fun committed() {
        if (act != 3 || leaving || !ours) return
        lastKey = tick; moved(2.0)
        if (committed) { feel(12); return }
        committed = true
        if (mimiStage < 2) walkOut(true)
        momo.on = 2; momo.form = 0.0
        tween({ momo.form }, { momo.form = it }, 1.0, 9, 2) { feel(14) }   // she is typed into being, and then both of them jump
        if (!quick) for (n in 0 until 9) falls += Fall(BOPO[(h2(n, 21) * BOPO.length).toInt()].toString(), momo.x + 6 + n * 6, geo.lineY - 30, 1, n % 4, geo.ridge + cam - 30)
        narrate(copy.a3Momo); host.say(copy.a3AnnounceMomo)
        trees = 1.0; sky = 1.0; tagHots()
    }

    /** The keyboard has come up or gone: the land rides up to stay above it. */
    fun visible(bottom: Double) {
        if (bottom == visibleBottom) return
        visibleBottom = bottom
        tween(::cam, min(0.0, bottom - (geo.ridge + 34)), 6)
    }

    fun skip() {
        if (atHome) return
        tweens.clear(); timers.clear(); falls.clear(); rain.clear(); narThen = null
        if (act == 3) host.keyboard(false)
        pile = 1.0; cam = 0.0; hint = 0.0; fog = 1.0
        arrive(direct = true)
    }

    private fun moved(by: Double) { target += by; lastMove = tick; if (quick) pos = target; dirty = true }
    private fun feel(ticks: Int) { moodUntil = tick + ticks; hopAt = tick; dirty = true }

    // ---- the cats -------------------------------------------------------------------------------------
    class CatLook(val pose: String, val face: String, val z: Boolean, val hop: Int)
    fun look(cat: Cat): CatLook {
        val isMimi = cat === mimi
        val idle = (tick - lastMove) / 12.0
        val moving = (pos < target && !still) || cat.walk
        val step = if ((tick + if (isMimi) 0 else 1) and 1 == 1) "walkA" else "walkB"
        if (tick < moodUntil) return CatLook(if (moving && !atHome) step else "sit", "happy", false, HOP.getOrElse(tick - hopAt) { 0 })
        if (moving && !atHome) return CatLook(step, if (isMimi) "focus" else "neutral", false, 0)
        if (idle < 1.2 && !atHome) return CatLook("walkA", "neutral", false, 0)
        if (!hold && isMimi && idle > 12) return CatLook("sleep", "neutral", true, 0)
        if (!hold && !isMimi && idle > 16) return CatLook("sleep", "neutral", false, 0)
        val blink = (tick + if (isMimi) 0 else 17) % 46 < 2
        return CatLook("sit", if (blink) "blink" else if (isMimi && idle > 7) "smug" else "neutral", false, 0)
    }

    // ---- the clock ------------------------------------------------------------------------------------
    /** One tick, twelve a second. True when the picture changed. */
    fun tick(): Boolean {
        tick++
        if (pos < target) { pos += max(1.0, min(6.0, Math.floor((target - pos) * 0.42 + 0.5))); if (pos > target) pos = target; lastMove = tick; dirty = true }
        for (t in timers.toList()) if (tick >= t.at) { timers.remove(t); t.run() }
        for (a in tweens.toList()) {
            if (a.d > 0) { a.d--; continue }
            if (a.from.isNaN()) a.from = a.get()
            a.i++
            val u = a.i.toDouble() / a.n
            a.set(if (a.i >= a.n) a.to else a.from + (a.to - a.from) * (1 - (1 - u) * (1 - u))); dirty = true
            if (a.i >= a.n) { tweens.remove(a); a.done?.invoke() }
        }
        narTick()
        if (falls.isNotEmpty()) { val it = falls.iterator(); while (it.hasNext()) { val p = it.next(); if (p.d > 0) { p.d--; continue }; p.v += 7; p.y += p.v; if (p.y >= p.land) it.remove() }; dirty = true }
        if (rain.isNotEmpty()) { val it = rain.iterator(); while (it.hasNext()) { val p = it.next(); p.y += p.v; if (p.y >= rainFloor(p.x) - 8) it.remove() }; dirty = true }
        if (act == 3 && committed && !leaving && !hold && tick - lastKey > 40) leave3()
        if (!still && tick % 6 == 0) { epoch++; dirty = true }       // the type turns, and the caret blinks
        if (tick % 6 == 0) dirty = true
        val changed = dirty
        dirty = false
        return changed
    }
    /** Where the pile has got to above the column at x: set by whoever draws the land. */
    var rainFloor: (Double) -> Double = { geo.h }

    /** The moment a still is taken of: [step] is how far into the act. For the preview tool and tests. */
    fun stillOf(n: Int, step: Int) {
        instant = true; hold = true
        if (n >= 2) pile = 1.0
        if (n >= 3) { stars = 1.0; horizon = 1.0; sun = 1.0; rise = 1.0; far = 1.0; mid = 1.0; near = 1.0; sunrise = true; enabledNow = true }
        if (n >= 4) { trees = 1.0; sky = 1.0; mimi.on = 2; momo.on = 2; pos += 40; target = pos }
        if (n >= 5) { scheme = copy.order[0]; pos += 120; target = pos }
        if (n == 6) arrive(direct = step == 9) else enter(n)
        if (n == 1 && step >= 1) act1Tap()
        if (n == 2 && step >= 1) { enabled(true) }
        if (n == 3 && step == 8) repeat(8) { typed() }
        else if (n == 3 && step >= 1) { keyboardIsOurs(true); typed() }
        if (n == 3 && step in 2..7) repeat(4) { typed() }
        if (n == 3 && step in 3..7) { committed(); repeat(4) { typed() } }
        if (n == 4 && step >= 1) choose(step - 1)
        if (n == 6 && step == 1) keepSkin()
        pos = target; instant = false; dirty = true
    }

    companion object {
        const val START = 165.0
        const val HOME_POS = 52.0
        const val SUN_X = 166.0
        val FORK_X = doubleArrayOf(66.0, 196.0, 326.0)
        private val HOP = listOf(0, -5, -9, -5, 0)
        private const val BOPO = "ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦ"
        private val UNIT = Regex("[A-Za-z0-9]+|[\\s\\S]")
        private val CLOSERS = setOf("，", "。", "、", "：", "；", "」", "”", "）")
    }
}
