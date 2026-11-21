package com.krysta1ll.meowpuut.android.ime

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.krysta1ll.meowpuut.android.R
import com.krysta1ll.meowpuut.android.engine.NativePets
import kotlin.random.Random

/** How much the cats do by themselves (settings: 貓的小事). Taps, holds and drops always answer. */
enum class PetMoments(val key: String, val label: Int, val mode: Int) {
    /** Greeting, peekaboo, doze, duet. */
    FULL("full", R.string.pet_moments_full, NativePets.MODE_FULL),
    /** Only what the user starts, plus the greeting and the doze. */
    QUIET("quiet", R.string.pet_moments_quiet, NativePets.MODE_QUIET),
    /** The cats keep their old behaviour. */
    OFF("off", R.string.pet_moments_off, NativePets.MODE_OFF);

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: FULL
    }
}

/**
 * What a key press does for the pets, and all it does: `pulse.keys++`. No clock read, no
 * core call, no file, no allocation (docs/PETS.md, "the key path"). Whoever looks later
 * (the idle timer, the yield watcher) compares the count with what it saw before.
 */
internal class PetPulse {
    @JvmField var keys = 0
}

/**
 * Plays the moments of the pets core (docs/PETS.md A1-A6) on the candidate bar and keeps the
 * promises around them:
 *  - the core is asked only when the keyboard appears, text is committed, the single idle
 *    timer fires, a cat is touched or the keyboard goes away; never on the key path;
 *  - one idle timer, armed lazily with [Handler.postDelayed]; it only looks at the key count
 *    when it fires;
 *  - typing ends every moment within 0.18 s (the commit callback, a new composition, or the
 *    watcher that runs only while a moment is playing);
 *  - TalkBack and other assistive services get no moments of their own and no doze.
 *
 * Main thread only.
 */
internal class CatMoments(
    private val affinity: LocalCatAffinity,
    private val prefs: Prefs,
    private val haptics: KeyHaptics,
    private val bar: () -> CandidateBarView?,
) {
    val pulse = PetPulse()

    init {
        // The book is read on a background thread; a keyboard that appeared before it was ready is seated and greeted now.
        affinity.onReady = { if (presented) bar()?.let { appear(it) } }
    }

    /** The cats' own count of committed characters (0 until their book has been read), and a call for when it has. */
    fun committedCharacters(): Long = affinity.committedCharacters()
    fun whenBookOpened(block: () -> Unit) { affinity.onOpened = block }

    private val handler = Handler(Looper.getMainLooper())
    private val clock = PetClock()
    private val ambience = PetAmbience()
    private var presented = false
    private var appearing = false
    private var assistive = false
    private var seenKeys = 0
    private var lastActive = NO_ACTIVITY
    private var dozeAfterMs = 25_000L
    // 米米莫 was seated asleep by the late-night rest pose (no token: it is a pose, not a moment).
    private var restSleep = false

    // Tokens of the moments being played; 0 when none.
    private var helloToken = 0
    private var peekToken = 0
    private var peekFade = false
    private var foundToken = 0
    private var duetToken = 0
    private var dozeToken = 0
    private var purrToken = 0
    private var purrCat = Cat.MIMIMO

    private val active get() = helloToken != 0 || peekToken != 0 || foundToken != 0 || duetToken != 0 || dozeToken != 0 || purrToken != 0 || restSleep

    private val directed get() = affinity.ready && prefs.petMoments != PetMoments.OFF

    // ---------------------------------------------------------------- the keyboard shows and hides

    /** The keyboard window appeared: seat the cats by the time of day, greet, maybe play peekaboo. */
    fun appear(bar: CandidateBarView) {
        presented = true
        bar.directed = directed
        assistive = bar.assistiveTechnology
        clock.sample()
        fill(bar, clock.mono)
        var rest: CatRest? = null
        if (directed) {
            rest = CatRest.of(
                affinity.restPose(NativePets.MIMIMO, ambience, clock.minute),
                affinity.restPose(NativePets.MOMO, ambience, clock.minute),
            )
        }
        // Showing the cats counts as no activity: the quiet before the keyboard appeared still holds.
        appearing = true
        bar.newPresentation(rest)
        appearing = false
        restSleep = rest?.mimimoAsleep == true
        if (affinity.decide(NativePets.APPEAR, 0, ambience, clock, Random.nextLong())) play(affinity.plan, bar)
        ambience.quietMs = 0
        val after = affinity.idleDelayMs(ambience, clock)
        if (after != NativePets.NEVER) dozeAfterMs = after.coerceAtLeast(MIN_IDLE_MS)
        markActive(clock.mono)
        armIdle(dozeAfterMs)
        watch()
    }

    /** The keyboard window went away. */
    fun hidden() {
        if (!presented) return
        presented = false
        stopAll(NativePets.INTERRUPTED)
        handler.removeCallbacks(idle)
        bar()?.cancelMoments()
        if (affinity.ready) {
            clock.sample()
            bar()?.let { fill(it, clock.mono) }
            affinity.decide(NativePets.DISAPPEAR, 0, ambience, clock, 0L)
        }
        affinity.flush()
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        haptics.stopPurr()
        affinity.release()
    }

    /** The setting changed while the keyboard shows. */
    fun settingChanged() {
        val bar = bar() ?: return
        bar.directed = directed
        if (!directed) {
            stopAll(NativePets.INTERRUPTED)
            handler.removeCallbacks(idle)
            bar.cancelMoments()
        } else if (presented) {
            armIdle(dozeAfterMs)
        }
    }

    /** "Clear cat records" (settings): every count, record and daily limit starts again. */
    fun clear() {
        affinity.clear()
    }

    // ---------------------------------------------------------------- what the user does

    /** Text was committed (a real commit, never a key press on its own). */
    fun committed(text: String) {
        val graphemes = TextUnits.graphemeCount(text)
        if (graphemes <= 0) return
        stopAll(NativePets.INTERRUPTED)
        // A cat asleep by the rest pose or the doze wakes on the first commit, even without a composition.
        bar()?.stirCats()
        if (affinity.ready) {
            val bar = bar()
            clock.sample()
            if (bar != null) fill(bar, clock.mono)
            // A flow moment would glow the glass's rim (A7); it is not shown on Android yet, so it earns nothing.
            if (affinity.decide(NativePets.COMMIT, graphemes, ambience, clock, 0L)) {
                affinity.resolve(affinity.plan.token, NativePets.CANCELLED, clock)
            }
        }
        activity()
    }

    /** A tap on a cat (its own reaction plays as before); two cats touched within a minute may make a duet. */
    fun tapped(cat: Cat) {
        if (!decideTouch(NativePets.TAP, cat)) return
        // The cat's own reaction to the pet has just started: the duet follows it.
        // Copy now: another decide() may refill the shared plan before the post runs.
        val plan = PetPlan().also { it.copyFrom(affinity.plan) }
        handler.post { bar()?.let { play(plan, it) } }
    }

    /** A finger has held a cat still for 0.5 s: it purrs. */
    fun held(cat: Cat) {
        if (decideTouch(NativePets.HOLD, cat)) bar()?.let { play(affinity.plan, it) }
    }

    /** The finger left a purring cat, or began to drag it. */
    fun holdEnded(cat: Cat, interrupted: Boolean) {
        bar()?.endPurr(cat)
        haptics.stopPurr()
        handler.removeCallbacks(endPurr)
        val token = purrToken
        purrToken = 0
        if (token != 0) finish(token, if (interrupted) NativePets.INTERRUPTED else NativePets.COMPLETED)
        armIdle(dozeAfterMs)
    }

    /** A cat dropped back from a drag was accepted ("fell" in the old words): it only counts. */
    fun dropped(cat: Cat) {
        decideTouch(NativePets.DROP, cat)
    }

    /** A tap on the seat of a hiding cat: found. */
    fun seatTouched(cat: Cat) {
        val bar = bar() ?: return
        handler.removeCallbacks(surfaceByItself)
        val hiding = peekToken != 0
        peekToken = 0
        if (hiding && decideTouch(NativePets.SEAT, cat)) play(affinity.plan, bar) else bar.surface(peekFade)
    }

    /** Something stirred the cats (a touch, a commit, a composition): the doze starts counting again. */
    fun activity() {
        if (appearing || !presented) return
        markActive(SystemClock.elapsedRealtime())
        armIdle(dozeAfterMs)
    }

    /** A composition began: typing has started. */
    fun composing(composing: Boolean) {
        if (composing) stopAll(NativePets.INTERRUPTED)
    }

    /** A dozing cat woke. */
    fun catsWoke() {
        restSleep = false
        val token = dozeToken
        if (token == 0) return
        dozeToken = 0
        finish(token, NativePets.INTERRUPTED)
    }

    // ---------------------------------------------------------------- playing a plan

    private fun play(plan: PetPlan, bar: CandidateBarView) {
        val fade = plan.fade
        when (plan.moment) {
            NativePets.HELLO -> {
                helloToken = plan.token
                bar.playHello(plan.who, fade)
                handler.postDelayed(endHello, plan.durationMs.toLong())
            }
            NativePets.PEEKABOO -> {
                peekToken = plan.token
                peekFade = fade
                bar.sink(plan.who, fade, plan.ripple)
                handler.postDelayed(surfaceByItself, plan.delayMs.toLong())
            }
            NativePets.FOUND -> {
                foundToken = plan.token
                bar.found(plan.who, fade)
                handler.postDelayed(endFound, plan.durationMs.toLong())
            }
            NativePets.DOZE -> {
                dozeToken = plan.token
                bar.doze(plan.who)
            }
            NativePets.DUET -> {
                duetToken = plan.token
                bar.playDuet(fade)
                handler.postDelayed(endDuet, plan.durationMs.toLong())
            }
            NativePets.PURR -> {
                purrToken = plan.token
                purrCat = if (plan.who == NativePets.MIMIMO) Cat.MIMIMO else Cat.MOMO
                bar.purr(purrCat, fade)
                haptics.purr(bar)
                handler.postDelayed(endPurr, plan.durationMs.toLong())
            }
            else -> affinity.resolve(plan.token, NativePets.CANCELLED, clock)
        }
        watch()
    }

    private val endHello = Runnable { helloToken.also { helloToken = 0 }.let(::finishCompleted) }
    private val endFound = Runnable { foundToken.also { foundToken = 0 }.let(::finishCompleted) }
    private val endDuet = Runnable { duetToken.also { duetToken = 0 }.let(::finishCompleted) }
    private val surfaceByItself = Runnable {
        val token = peekToken
        peekToken = 0
        bar()?.surface(peekFade)
        finishCompleted(token)
    }

    /** The purr's 4 s are up (the finger may still be down): the cat goes back to itself. */
    private val endPurr = Runnable {
        val token = purrToken
        purrToken = 0
        bar()?.endPurr(purrCat)
        haptics.stopPurr()
        finishCompleted(token)
    }

    private fun finishCompleted(token: Int) = finish(token, NativePets.COMPLETED)

    private fun finish(token: Int, outcome: Int) {
        if (token == 0) return
        affinity.resolve(token, outcome, clock.sample())
    }

    /** Typing, or the keyboard leaving: every moment ends. The bar brings the cats back within 0.18 s. */
    private fun stopAll(outcome: Int) {
        if (!active) return
        val bar = bar()
        val fade = peekFade
        val tokens = intArrayOf(helloToken, peekToken, foundToken, duetToken, dozeToken, purrToken)
        restSleep = false
        helloToken = 0; peekToken = 0; foundToken = 0; duetToken = 0; dozeToken = 0; purrToken = 0
        handler.removeCallbacks(endHello)
        handler.removeCallbacks(endFound)
        handler.removeCallbacks(endDuet)
        handler.removeCallbacks(endPurr)
        handler.removeCallbacks(surfaceByItself)
        handler.removeCallbacks(watcher)
        haptics.stopPurr()
        bar?.yieldMoments(fade)
        clock.sample()
        for (token in tokens) if (token != 0) affinity.resolve(token, outcome, clock)
    }

    // ---------------------------------------------------------------- asking the core

    private fun decideTouch(trigger: Int, cat: Cat): Boolean {
        if (!affinity.ready) return false
        val bar = bar()
        clock.sample()
        if (bar != null) fill(bar, clock.mono)
        return affinity.decide(trigger, if (cat == Cat.MIMIMO) NativePets.MIMIMO else NativePets.MOMO, ambience, clock, Random.nextLong())
    }

    private fun fill(bar: CandidateBarView, now: Long) {
        ambience.flags = bar.petFlags() or (if (assistive) NativePets.ASSISTIVE else 0)
        ambience.mode = prefs.petMoments.mode
        ambience.cats = bar.petCats
        ambience.quietMs = quietMs(now)
    }

    /** Time since the last key press, as well as it can be known without reading the clock on the key path. */
    private fun quietMs(now: Long): Int {
        if (pulse.keys != seenKeys) {
            seenKeys = pulse.keys
            lastActive = now
            return 0
        }
        if (lastActive == NO_ACTIVITY) return -1
        return (now - lastActive).coerceIn(0L, Int.MAX_VALUE - 1L).toInt()
    }

    private fun markActive(now: Long) {
        lastActive = now
        seenKeys = pulse.keys
    }

    // ---------------------------------------------------------------- the idle timer and the watcher

    /**
     * The one idle timer. It is armed when the keyboard appears and when something stirs the cats, and
     * it is not reset by key presses: when it fires it looks at the key count. Fresh keys mean the
     * user is typing, so it looks again in 5 s; otherwise the core decides whether the cats doze.
     */
    private val idle = Runnable {
        val bar = bar()
        if (bar == null || !presented) return@Runnable
        val now = SystemClock.elapsedRealtime()
        if (pulse.keys != seenKeys) {
            markActive(now)
            armIdle(SAMPLE_MS)
            return@Runnable
        }
        clock.sample()
        fill(bar, clock.mono)
        ambience.quietMs = (clock.mono - lastActive).coerceIn(0L, Int.MAX_VALUE - 1L).toInt()
        if (affinity.decide(NativePets.IDLE, 0, ambience, clock, 0L)) {
            play(affinity.plan, bar)
            return@Runnable
        }
        // Composing: the composition's end stirs the cats and arms the timer again.
        if (ambience.flags and NativePets.COMPOSING != 0) return@Runnable
        val delay = affinity.idleDelayMs(ambience, clock)
        if (delay != NativePets.NEVER) armIdle(delay)
    }

    private fun armIdle(delayMs: Long) {
        handler.removeCallbacks(idle)
        if (!presented || !directed || assistive) return
        handler.postDelayed(idle, delayMs.coerceIn(MIN_IDLE_MS, MAX_IDLE_MS))
    }

    /** While a moment plays, key presses are noticed within 60 ms (120 ms for a sleeping cat) and end it. */
    private val watcher = object : Runnable {
        override fun run() {
            if (!active) return
            if (pulse.keys != seenKeys) {
                markActive(SystemClock.elapsedRealtime())
                stopAll(NativePets.INTERRUPTED)
                armIdle(dozeAfterMs)
                return
            }
            handler.postDelayed(this, if (!playing) 120L else 60L)
        }
    }

    private val playing get() = helloToken != 0 || peekToken != 0 || foundToken != 0 || duetToken != 0 || purrToken != 0

    private fun watch() {
        handler.removeCallbacks(watcher)
        if (active) handler.postDelayed(watcher, 60L)
    }

    private companion object {
        const val NO_ACTIVITY = Long.MIN_VALUE
        const val MIN_IDLE_MS = 1_000L
        const val MAX_IDLE_MS = 60_000L
        const val SAMPLE_MS = 5_000L
    }
}
