package com.krysta1ll.meowpuut.android.app.onboarding

import android.content.Context
import android.content.SharedPreferences
import com.krysta1ll.meowpuut.android.ime.KeyboardSkin
import java.io.File

/**
 * Whether the guide (第一行字) comes up by itself, and the skin the keyboard wears while it is on.
 *
 * It comes up once, for a new install only. Someone who already had the app is never shown it unasked: they find it in the settings.
 * What is kept is two values in the app's own settings store, and neither says anything about what was typed:
 *   [STATE]       "due" (a new install that has not seen it) or "done" (seen, skipped, or not a new install)
 *   [SKIN_UNTIL]  while the guide is on, the time until which the keyboard wears 字景 whatever skin is chosen
 */
internal object Onboarding {
    const val STATE = "onboarding"
    const val SKIN_UNTIL = "onboardingSkinUntil"
    const val DUE = "due"
    const val DONE = "done"
    /** Long enough for the slowest walk through; short enough that a crash does not leave the skin on. */
    const val WEAR_MS = 20 * 60_000L
    private const val UPDATE_GAP_MS = 60_000L

    /** What is shown, and what is written down: [stored] is the value kept so far, [fresh] whether this is a new install. */
    fun decide(stored: String?, fresh: Boolean): Pair<Boolean, String?> = when (stored) {
        DUE -> true to null
        DONE -> false to null
        else -> if (fresh) true to DUE else false to DONE              // decided once, the first time the app is opened with the guide in it
    }

    /**
     * A new install: never updated, and the keyboard has left none of the files it writes by itself once it is used (the same signs the
     * settings' own first run reads, [com.krysta1ll.meowpuut.android.ime.Prefs]). The settings store itself is no sign: it is written
     * as soon as the app starts.
     */
    fun fresh(updatedAfterMs: Long, hasKeyboardFiles: Boolean): Boolean = updatedAfterMs <= UPDATE_GAP_MS && !hasKeyboardFiles

    /** Whether to show the guide now. Decides, and writes the decision down, the first time it is asked. */
    fun due(context: Context, store: SharedPreferences): Boolean {
        val (show, write) = decide(store.getString(STATE, null), isFresh(context))
        if (write != null) store.edit().putString(STATE, write).apply()
        return show
    }

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

    fun markDone(store: SharedPreferences) = store.edit().putString(STATE, DONE).apply()

    /** Pure: the keyboard wears the guide's skin while [now] is before [until] (and not absurdly far before it: a clock set back). */
    fun wearing(until: Long, now: Long): Boolean = now < until && until - now <= WEAR_MS

    /** The skin the keyboard wears for the guide right now, or null for the one that is chosen. Read by the keyboard. */
    fun wornSkin(store: SharedPreferences, now: Long = System.currentTimeMillis()): KeyboardSkin? =
        if (wearing(store.getLong(SKIN_UNTIL, 0L), now)) KeyboardSkin.GLYPH else null

    fun wear(store: SharedPreferences, now: Long = System.currentTimeMillis()) = store.edit().putLong(SKIN_UNTIL, now + WEAR_MS).apply()
    fun takeOff(store: SharedPreferences) { if (store.contains(SKIN_UNTIL)) store.edit().remove(SKIN_UNTIL).apply() }
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
