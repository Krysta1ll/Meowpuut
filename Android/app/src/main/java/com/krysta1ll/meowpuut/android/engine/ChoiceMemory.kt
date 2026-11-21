package com.krysta1ll.meowpuut.android.engine

import android.content.Context
import android.content.SharedPreferences

/** The keyboard and its settings run in one process. Disk writes use apply(). */
internal class ChoiceMemory(private val store: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("choice-memory", Context.MODE_PRIVATE))

    fun preferred(mode: InputMode, simplified: Boolean, reading: String): Map<String, Int> =
        read(key(mode, simplified, reading))?.filterValues { it >= 2 }.orEmpty()

    fun remember(mode: InputMode, simplified: Boolean, reading: String, text: String) {
        val key = key(mode, simplified, reading)
        val previous = read(key)
        val choices = afterSelection(previous.orEmpty(), text)
        val encoded = choices.entries.joinToString("\n", prefix = "${System.currentTimeMillis()}\n") { "${it.value}\t${it.key}" }
        val edit = store.edit()
        if (previous == null) {
            val count = store.getInt(READING_COUNT, 0)
            if (count >= MAX_READINGS) {
                // Readings committed once (every sentence taken with Space adds one) go first,
                // oldest first; a word chosen twice is evicted only when nothing else is left.
                store.all.entries.asSequence().filter { it.key != READING_COUNT }
                    .minWithOrNull(compareBy<Map.Entry<String, *>>({ isLearned(it.value) }, { lastPicked(it.value) }))
                    ?.let { edit.remove(it.key) }
            } else {
                edit.putInt(READING_COUNT, count + 1)
            }
        }
        edit.putString(key, encoded).apply()
    }

    fun clear() = store.edit().clear().apply()

    private fun read(key: String): MutableMap<String, Int>? {
        val saved = store.getString(key, null) ?: return null
        val choices = LinkedHashMap<String, Int>()
        saved.lineSequence().drop(1).forEach { line ->
            val uses = line.substringBefore('\t').toIntOrNull()
            val text = line.substringAfter('\t', "")
            if (uses != null && text.isNotEmpty()) choices[text] = uses
        }
        return choices
    }

    private fun key(mode: InputMode, simplified: Boolean, reading: String): String {
        val trimmed = reading.trimEnd('\'').lowercase()
        // Nine-key is kept under the digits typed, so a pick made with a syllable locked on the
        // rail (WANG'468 -> wang) counts for 9264 too.
        val normalized = if (mode == InputMode.NINE_KEY) {
            buildString { for (c in trimmed) if (c != '\'') append(NineKeySyllables.digit(c) ?: c) }
        } else trimmed
        return "${mode.key}:${if (simplified) 's' else 't'}:$normalized"
    }

    private fun lastPicked(value: Any?): Long = (value as? String)?.substringBefore('\n')?.toLongOrNull() ?: 0L

    private fun isLearned(value: Any?): Boolean = (value as? String)?.lineSequence()?.drop(1)
        ?.any { (it.substringBefore('\t').toIntOrNull() ?: 0) >= 2 } == true

    companion object {
        private const val MAX_READINGS = 768
        private const val MAX_CHOICES = 6
        private const val READING_COUNT = "count"

        /** Store equal-frequency choices in last-picked order in the existing record format. */
        fun afterSelection(previous: Map<String, Int>, text: String): Map<String, Int> {
            val counts = previous + (text to ((previous[text] ?: 0) + 1).coerceAtMost(30))
            val sorted = counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }
                .thenByDescending { it.key == text })
            var kept = sorted.take(MAX_CHOICES)
            // A full reading keeps the word just picked in place of its weakest one; otherwise a
            // new pick (one use) would be cut every time and never reach the two uses it needs.
            if (kept.none { it.key == text }) kept = kept.dropLast(1) + sorted.first { it.key == text }
            return kept.associate { it.key to it.value }
        }

        fun selectionReading(input: CharSequence, candidate: Candidate): String {
            if (candidate.kind != CandidateKind.CHINESE || candidate.coreIndex < 0 ||
                candidate.consumed !in 1..input.length || candidate.text.isEmpty() ||
                !candidate.text.codePoints().allMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN }
            ) return ""
            return input.substring(0, candidate.consumed).trimEnd('\'').lowercase()
        }

        /** Only exchange complete Chinese choices, leaving the other candidate slots alone. */
        fun rank(choices: List<Candidate>, length: Int, preferred: Map<String, Int>): List<Candidate> {
            if (preferred.isEmpty()) return choices
            val slots = choices.indices.filter {
                val choice = choices[it]
                choice.kind == CandidateKind.CHINESE && choice.coreIndex >= 0 && choice.consumed == length
            }
            val recency = preferred.keys.withIndex().associate { (index, text) -> text to index }
            val sorted = slots.map(choices::get).sortedWith(compareByDescending<Candidate> { preferred[it.text] ?: 0 }
                .thenBy { recency[it.text] ?: Int.MAX_VALUE })
            val result = choices.toMutableList()
            slots.forEachIndexed { index, slot -> result[slot] = sorted[index] }
            return result
        }
    }
}
