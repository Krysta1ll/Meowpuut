package com.krysta1ll.meowpuut.android.engine

import org.json.JSONArray
import org.json.JSONObject

/** Exact word lookups in the shared Chinese and Japanese association table. */
class SymbolAssociations private constructor(
    private val chinese: Map<String, Marks>,
    private val japanese: Map<String, Marks>,
) {
    data class Marks(val emoji: List<String>, val kaomoji: List<String>)

    fun of(word: String, japanese: Boolean = false): Marks? = (if (japanese) this.japanese else chinese)[word]

    companion object {
        val EMPTY = SymbolAssociations(emptyMap(), emptyMap())

        fun parse(text: String, renderEmoji: (String) -> String? = { it }): SymbolAssociations {
            val table = JSONObject(text)
            // Each emoji occurs in many rows; check device glyphs only once.
            val glyphs = HashMap<String, String?>()
            fun words(language: String): Map<String, Marks> {
                val rows = table.optJSONObject(language) ?: return emptyMap()
                return buildMap {
                    for (word in rows.keys()) {
                        val entry = rows.optJSONArray(word) ?: continue
                        val emoji = entry.optJSONArray(0).strings().mapNotNull { value ->
                            if (!glyphs.containsKey(value)) glyphs[value] = renderEmoji(value)
                            glyphs[value]
                        }
                        val kaomoji = entry.optJSONArray(1).strings()
                        if (emoji.isNotEmpty() || kaomoji.isNotEmpty()) put(word, Marks(emoji, kaomoji))
                    }
                }
            }
            return SymbolAssociations(words("zh"), words("ja"))
        }

        private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else buildList {
            for (i in 0 until length()) optString(i).takeIf { it.isNotEmpty() }?.let(::add)
        }
    }
}
