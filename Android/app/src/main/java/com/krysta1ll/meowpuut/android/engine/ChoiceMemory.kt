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
        val choices = previous ?: LinkedHashMap()
        choices[text] = ((choices[text] ?: 0) + 1).coerceAtMost(30)
        val entry = choices.entries.sortedByDescending { it.value }.take(6)
        val encoded = entry.joinToString("\n", prefix = "${System.currentTimeMillis()}\n") { "${it.value}\t${it.key}" }
        val edit = store.edit()
        if (previous == null) {
            val count = store.getInt(READING_COUNT, 0)
            if (count >= MAX_READINGS) {
                store.all.entries.asSequence().filter { it.key != READING_COUNT }
                    .minByOrNull { (_, value) -> (value as? String)?.substringBefore('\n')?.toLongOrNull() ?: 0L }
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

    private fun key(mode: InputMode, simplified: Boolean, reading: String) =
        "${mode.key}:${if (simplified) 's' else 't'}:${reading.lowercase()}"

    companion object {
        private const val MAX_READINGS = 768
        private const val READING_COUNT = "count"

        fun selectionReading(input: CharSequence, candidate: Candidate): String {
            if (candidate.kind != CandidateKind.CHINESE || candidate.coreIndex < 0 ||
                candidate.consumed !in 1..input.length || candidate.text.isEmpty() ||
                !candidate.text.codePoints().allMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN }
            ) return ""
            return input.substring(0, candidate.consumed).lowercase()
        }

        /** Only exchange complete Chinese choices, leaving the other candidate slots alone. */
        fun rank(choices: List<Candidate>, length: Int, preferred: Map<String, Int>): List<Candidate> {
            if (preferred.isEmpty()) return choices
            val slots = choices.indices.filter {
                val choice = choices[it]
                choice.kind == CandidateKind.CHINESE && choice.coreIndex >= 0 && choice.consumed == length
            }
            val sorted = slots.map(choices::get).sortedByDescending { preferred[it.text] ?: 0 }
            val result = choices.toMutableList()
            slots.forEachIndexed { index, slot -> result[slot] = sorted[index] }
            return result
        }
    }
}
