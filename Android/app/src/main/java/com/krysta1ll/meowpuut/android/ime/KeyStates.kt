package com.krysta1ll.meowpuut.android.ime

// Shift and delete-repeat rules (docs/DESIGN.md §6), the same as iOS
// CapitalizationState.swift and RepeatDeleteDriver. Nothing here touches
// android.*, so the JVM unit tests cover it.

/**
 * Shift: one tap capitalises the next letter, a second tap within
 * [DOUBLE_TAP_MS] locks capitals. The capital an editor asks for at the start
 * of a sentence is kept apart from a Shift the user tapped.
 */
class Capitalization {
    var state = ShiftState.OFF
        private set
    private var lastTap = -1L
    /** [ShiftState.ONCE] came from a tap, not from the start of a sentence. */
    private var manual = false
    /** The user turned the sentence capital off; it is not offered again until a letter is typed. */
    private var declinedAutomatic = false

    fun tap(now: Long) {
        // The second of two quick taps locks, whatever the first one did
        // (it may have turned off the capital at the start of a sentence).
        if (state != ShiftState.LOCKED && lastTap >= 0 && now >= lastTap && now - lastTap <= DOUBLE_TAP_MS) {
            state = ShiftState.LOCKED
            manual = true
            lastTap = -1
            return
        }
        when (state) {
            ShiftState.OFF -> { state = ShiftState.ONCE; manual = true; lastTap = now }
            ShiftState.ONCE -> { state = ShiftState.OFF; declinedAutomatic = !manual; manual = false; lastTap = now }
            ShiftState.LOCKED -> { state = ShiftState.OFF; manual = false; lastTap = -1 }
        }
    }

    /** A letter was typed: a one-letter Shift is used up. */
    fun letterTyped() {
        if (state == ShiftState.ONCE) { state = ShiftState.OFF; manual = false }
        lastTap = -1
        declinedAutomatic = false
    }

    /** Follows the editor's auto-capitalisation without undoing a Shift the user tapped. */
    fun applyAutomatic(capitalize: Boolean) {
        if (state == ShiftState.LOCKED) return
        if (capitalize) {
            if (state == ShiftState.OFF && !declinedAutomatic) { state = ShiftState.ONCE; manual = false }
        } else {
            declinedAutomatic = false
            if (state == ShiftState.ONCE && !manual) state = ShiftState.OFF
        }
    }

    fun reset() {
        state = ShiftState.OFF
        manual = false
        lastTap = -1
        declinedAutomatic = false
    }

    companion object {
        const val DOUBLE_TAP_MS = 300L
    }
}

/**
 * Held delete: one character on touch-down, characters about twelve times a
 * second after [INITIAL_DELAY_MS], then whole words once it has repeated
 * [WORD_THRESHOLD] times (about 2.5 s).
 */
object DeleteRepeat {
    const val INITIAL_DELAY_MS = 420L
    const val INTERVAL_MS = 83L
    const val WORD_THRESHOLD = 26
    /** A word goes on every third tick, so a release lands where intended. */
    const val TICKS_PER_WORD = 3

    enum class Step { CHARACTER, WORD, WAIT }

    /** What the [count]th repeat does; the first repeat is 1. */
    fun step(count: Int): Step = when {
        count < WORD_THRESHOLD -> Step.CHARACTER
        (count - WORD_THRESHOLD) % TICKS_PER_WORD == 0 -> Step.WORD
        else -> Step.WAIT
    }
}
