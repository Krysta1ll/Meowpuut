package com.krysta1ll.meowpuut.android.engine

/**
 * The reviewed mimo-jp word list (native-japanese.tsv, `readings|text|weight` per line,
 * readings comma separated), also read by Windows. macOS and iOS read mimo_jp.bin instead.
 * Fields after the weight are ignored, so a later column does not empty the list.
 */
class JapaneseWords private constructor(private val byReading: Map<String, List<Pair<String, Int>>>) {
    /** Best word for an exact reading, or null. */
    fun word(reading: String): String? = byReading[reading]?.firstOrNull()?.first

    /** All words for a reading, strongest first. */
    fun words(reading: String): List<String> = byReading[reading]?.map { it.first }.orEmpty()

    /** The strongest text per reading, for the engine's mixed decoder. */
    fun best(): List<Triple<String, String, Int>> =
        byReading.entries.sortedBy { it.key }.mapNotNull { (reading, list) -> list.firstOrNull()?.let { Triple(reading, it.first, it.second) } }

    val size get() = byReading.size

    companion object {
        val EMPTY = JapaneseWords(emptyMap())

        fun parse(text: String): JapaneseWords {
            val map = HashMap<String, MutableList<Pair<String, Int>>>()
            for (rawLine in text.lineSequence()) {
                val line = rawLine.trimEnd('\r')
                if (line.isEmpty() || line.startsWith("#")) continue
                val parts = line.split('|')
                if (parts.size < 3) continue
                val weight = parts[2].trim().toIntOrNull() ?: continue
                val word = parts[1]
                if (word.isEmpty()) continue
                for (reading in parts[0].split(',')) {
                    val key = reading.trim().lowercase()
                    if (key.isEmpty()) continue
                    val list = map.getOrPut(key) { mutableListOf() }
                    if (list.none { it.first == word }) list += word to weight
                }
            }
            return JapaneseWords(map.mapValues { (_, list) -> list.sortedByDescending { it.second } })
        }
    }
}
