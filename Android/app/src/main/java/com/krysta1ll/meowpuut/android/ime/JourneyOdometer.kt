package com.krysta1ll.meowpuut.android.ime

/**
 * How far the cats' journey has got (committed characters), counted under every skin: 行旅 (the statistics) and the home page show
 * the same journey whichever keyboard was worn. While 字景's own world is running it counts for itself and writes the number; under any
 * other skin this counts the commits and adds them to the same stored number, a few at a time. Only the sum is ever stored; no text.
 *
 * The count is one addition per commit (not per key); the settings are written once per [SAVE_EVERY] characters, when the keyboard goes
 * away or the look changes, and the write is the settings' usual asynchronous one.
 */
internal class JourneyOdometer(private val read: () -> Long, private val write: (Long) -> Unit) {
    private var pending = 0L

    /** [graphemes] were committed; [worldCounts] is whether the 字景 world is running and counts them itself. */
    fun committed(graphemes: Int, worldCounts: Boolean) {
        if (worldCounts || graphemes <= 0) return
        pending += graphemes
        if (pending >= SAVE_EVERY) flush()
    }

    /** Adds what has been counted to the stored distance. */
    fun flush() {
        val add = pending
        if (add <= 0L) return
        pending = 0L
        val now = read()
        write(if (now > Long.MAX_VALUE - add) Long.MAX_VALUE else now + add)
    }

    /** The records were cleared: what has been counted and not yet stored goes too. */
    fun reset() {
        pending = 0L
    }

    /** How many characters are waiting to be stored (the tests). */
    val waiting: Long get() = pending

    companion object {
        const val SAVE_EVERY = 25L
    }
}
