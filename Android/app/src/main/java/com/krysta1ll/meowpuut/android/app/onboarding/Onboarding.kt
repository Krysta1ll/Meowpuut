package com.krysta1ll.meowpuut.android.app.onboarding

import android.content.Context
import android.content.SharedPreferences
import com.krysta1ll.meowpuut.android.ime.KeyboardSkin
import java.io.File

/**
 * Whether the guide (第一行字) comes up by itself, where it has got to, and the skin the keyboard wears while it is on.
 *
 * It comes up once on every device, whoever it is for: what decides is only whether this device has decided before (seen it, skipped
 * it, or been shown it and not finished). Whether the person is new or has been using the app only decides the way it goes
 * ([usingNow]): someone already using the keyboard is not asked to do again what is done, and nothing of theirs is changed unless they
 * choose it. What is kept is a few values in the app's own settings store, and none of them says anything about what was typed:
 *   [STATE]       "due" (shown, or about to be, and not finished) or "done" (seen, or skipped)
 *   [KIND]        "using" or "new": the way it goes, decided the first time the app is opened with the guide in it
 *   [PROGRESS]    where the guide has got to (act, road, time), for a guide the system ended; gone when it is done
 *   [SKIN_UNTIL]  while the guide is on, the time until which the keyboard wears 字景 whatever skin is chosen
 */
internal object Onboarding {
    const val STATE = "onboarding"
    const val KIND = "onboardingKind"
    const val PROGRESS = "onboardingProgress"
    const val SKIN_UNTIL = "onboardingSkinUntil"
    const val DUE = "due"
    const val DONE = "done"
    const val USING = "using"
    const val NEW = "new"
    /** Long enough for the slowest walk through; short enough that a crash does not leave the skin on. */
    const val WEAR_MS = 20 * 60_000L
    /** How long after it was left a guide that the system ended is still taken up where it was. */
    const val RESUME_MS = 30 * 60_000L
    private const val UPDATE_GAP_MS = 60_000L

    /** What is shown, and what is written down: [stored] is the value kept so far. No record is a device that has not decided: shown, and noted as due. */
    fun decide(stored: String?): Pair<Boolean, String?> = when (stored) {
        DONE -> false to null
        DUE -> true to null
        else -> true to DUE
    }

    /**
     * A new install: never updated, and the keyboard has left none of the files it writes by itself once it is used (the same signs the
     * settings' own first run reads, [com.krysta1ll.meowpuut.android.ime.Prefs]). The settings store itself is no sign: it is written
     * as soon as the app starts.
     */
    fun fresh(updatedAfterMs: Long, hasKeyboardFiles: Boolean): Boolean = updatedAfterMs <= UPDATE_GAP_MS && !hasKeyboardFiles

    /** Whether to show the guide now. Decides, and writes the decision (and the way it will go) down, the first time it is asked. */
    fun due(context: Context, store: SharedPreferences): Boolean {
        val (show, write) = decide(store.getString(STATE, null))
        if (write != null) store.edit().putString(STATE, write).putString(KIND, if (isFresh(context)) NEW else USING).apply()
        return show
    }

    /** Whether the guide goes the way of someone already using the app (a replay always does; a guide first shown to a new install does not). */
    fun usingNow(store: SharedPreferences, replay: Boolean): Boolean = replay || store.getString(KIND, null) == USING

    private fun isFresh(context: Context): Boolean {
        val dir = File(context.applicationInfo.dataDir, "shared_prefs")
        val files = listOf("cat_affinity", "choice-memory").any { File(dir, "$it.xml").exists() }
        val gap = try {
            context.packageManager.getPackageInfo(context.packageName, 0).let { it.lastUpdateTime - it.firstInstallTime }
        } catch (_: Exception) {
            Long.MAX_VALUE                                               // cannot tell: do not interrupt
        }
        return fresh(gap, files)
    }

    fun markDone(store: SharedPreferences) = store.edit().putString(STATE, DONE).remove(PROGRESS).apply()

    // ---- where it has got to: a guide the system ended is taken up again --------------------------------------
    /** Act 2…6 and the road taken (-1: none yet). */
    class Progress(val act: Int, val pick: Int)

    /** Pure: what [text] says, if it is a recent one. */
    fun readProgress(text: String?, now: Long): Progress? {
        val part = text?.split(',') ?: return null
        if (part.size != 3) return null
        val act = part[0].toIntOrNull() ?: return null
        val pick = part[1].toIntOrNull() ?: return null
        val at = part[2].toLongOrNull() ?: return null
        if (act !in 2..6 || pick !in -1..2 || now < at || now - at > RESUME_MS) return null
        return Progress(act, pick)
    }

    fun progress(store: SharedPreferences, now: Long = System.currentTimeMillis()): Progress? = readProgress(store.getString(PROGRESS, null), now)

    fun reached(store: SharedPreferences, act: Int, pick: Int, now: Long = System.currentTimeMillis()) {
        if (act in 2..5) store.edit().putString(PROGRESS, "$act,$pick,$now").apply()
        else store.edit().remove(PROGRESS).apply()             // the last act is the end: nothing is left to take up
    }

    fun forget(store: SharedPreferences) { if (store.contains(PROGRESS)) store.edit().remove(PROGRESS).apply() }

    /** A guide the system ended a moment ago, to be taken up where it was, even by someone who has seen it before. */
    fun resumable(store: SharedPreferences, now: Long = System.currentTimeMillis()): Boolean = progress(store, now) != null

    /** Pure: the keyboard wears the guide's skin while [now] is before [until] (and not absurdly far before it: a clock set back). */
    fun wearing(until: Long, now: Long): Boolean = now < until && until - now <= WEAR_MS

    /** The skin the keyboard wears for the guide right now, or null for the one that is chosen. Read by the keyboard. */
    fun wornSkin(store: SharedPreferences, now: Long = System.currentTimeMillis()): KeyboardSkin? =
        if (wearing(store.getLong(SKIN_UNTIL, 0L), now)) KeyboardSkin.GLYPH else null

    fun wear(store: SharedPreferences, now: Long = System.currentTimeMillis()) = store.edit().putLong(SKIN_UNTIL, now + WEAR_MS).apply()
    fun takeOff(store: SharedPreferences) { if (store.contains(SKIN_UNTIL)) store.edit().remove(SKIN_UNTIL).apply() }

    /** Once, as the app's process starts: no guide is on the screen of a process that has only just begun, so a skin left by one that was ended is let go. */
    fun processStarted(context: Context) = takeOff(context.getSharedPreferences("meowpuut", Context.MODE_PRIVATE))
}

/**
 * What happens on the line to type on, from numbers alone: how long the text is, how much of it is a reading still being composed, and
 * how long a piece of text the keyboard has just committed. The text itself is never looked at, so nothing that was typed can be kept.
 */
internal class OnboardingTyping {
    private var length = 0
    private var composing = 0

    /** The line is now [newLength] long with [newComposing] of it still a reading. True when that is a change: a key was pressed. */
    fun changed(newLength: Int, newComposing: Int): Boolean {
        val changed = newLength != length || newComposing != composing
        length = newLength; composing = newComposing
        return changed
    }

    fun reset() { length = 0; composing = 0 }

    companion object {
        /**
         * A word was chosen: the keyboard committed text ([committedLength] long) while a reading was being composed. A keyboard that
         * composes nothing puts its letters straight on the line, and a reading that is only let go commits nothing: neither is a word chosen.
         */
        fun isCommit(wasComposing: Boolean, committedLength: Int): Boolean = wasComposing && committedLength > 0
    }
}
