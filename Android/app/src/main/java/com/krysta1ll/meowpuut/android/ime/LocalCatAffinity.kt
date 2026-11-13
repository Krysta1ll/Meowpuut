package com.krysta1ll.meowpuut.android.ime

import android.content.Context
import android.os.Handler
import android.os.Looper

internal class CatAffinityCounters(
    initialGraphemes: Long = 0L,
    initialPets: (Cat) -> Long = { 0L },
    initialFalls: (Cat) -> Long = { 0L },
) {
    var graphemes = initialGraphemes.coerceAtLeast(0L)
        private set
    private val pets = Cat.entries.map { initialPets(it).coerceAtLeast(0L) }.toLongArray()
    private val falls = Cat.entries.map { initialFalls(it).coerceAtLeast(0L) }.toLongArray()

    fun addGraphemes(count: Int) { graphemes = add(graphemes, count.toLong().coerceAtLeast(0L)) }
    fun pet(cat: Cat) { pets[cat.ordinal] = add(pets(cat), 1) }
    fun fall(cat: Cat) { falls[cat.ordinal] = add(falls(cat), 1) }
    fun pets(cat: Cat) = pets[cat.ordinal]
    fun falls(cat: Cat) = falls[cat.ordinal]

    /** One point per 200 committed graphemes and per three contacts with this cat. */
    fun score(cat: Cat): Int =
        (graphemes / 200 + add(pets(cat), falls(cat)) / 3).coerceAtMost(100).toInt()

    private fun add(current: Long, delta: Long): Long = current + delta.coerceAtMost(Long.MAX_VALUE - current)
}

/** Only local counters persist. Text is examined for boundaries and immediately released. */
internal class LocalCatAffinity(context: Context) {
    private val store = context.getSharedPreferences("cat_affinity", Context.MODE_PRIVATE)
    private val counters = CatAffinityCounters(
        store.getLong("committed_graphemes", 0L),
        { store.getLong("pets_${it.key}", 0L) },
        { store.getLong("falls_${it.key}", store.getLong("throws_${it.key}", 0L)) },
    )
    private val handler = Handler(Looper.getMainLooper())
    private val pendingWrite = Runnable(::flush)
    private var dirty = false

    fun committed(text: String) {
        if (text.isEmpty()) return
        counters.addGraphemes(TextUnits.graphemeCount(text))
        scheduleWrite()
    }

    fun pet(cat: Cat) {
        counters.pet(cat)
        scheduleWrite()
    }

    fun fall(cat: Cat) {
        counters.fall(cat)
        scheduleWrite()
    }

    fun flush() {
        if (!dirty) return
        dirty = false
        handler.removeCallbacks(pendingWrite)
        store.edit().apply {
            putLong("committed_graphemes", counters.graphemes)
            for (cat in Cat.entries) {
                putLong("pets_${cat.key}", counters.pets(cat))
                putLong("falls_${cat.key}", counters.falls(cat))
                remove("throws_${cat.key}")
            }
        }.apply()
    }

    private fun scheduleWrite() {
        if (dirty) return
        dirty = true
        handler.postDelayed(pendingWrite, 5_000L)
    }
}
