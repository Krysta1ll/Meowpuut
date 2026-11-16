package com.krysta1ll.meowpuut.android.engine

import java.text.Normalizer
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal object CustomDictionary {
    const val MAX_ENTRIES = 256
    private const val MAX_READING = 48
    private const val MAX_TEXT = 100
    val modes = listOf(InputMode.PINYIN, InputMode.ZHUYIN, InputMode.JAPANESE)

    data class Entry(val mode: InputMode, val reading: String, val text: String)

    fun normalizeReading(mode: InputMode, input: String): String? {
        val reading = Normalizer.normalize(input.trim(), Normalizer.Form.NFC)
        val normalized = when (mode) {
            InputMode.PINYIN -> buildString {
                for (ch in reading) when (ch) {
                    ' ', '\'', '’' -> Unit
                    'ü', 'Ü' -> append('v')
                    in 'a'..'z', in 'A'..'Z' -> append(ch.lowercaseChar())
                    else -> return null
                }
            }
            InputMode.ZHUYIN -> reading.filterNot { it.isWhitespace() || it in "ˉ'’" }
                .takeIf { body -> body.any { it in 'ㄅ'..'ㄩ' } && body.all { it in 'ㄅ'..'ㄩ' || it in "ˊˇˋ˙" } }
            InputMode.JAPANESE -> reading.takeIf { body ->
                body.any(::latinLetter) && body.all { latinLetter(it) || it == '\'' || it == '-' }
            }?.lowercase()
            InputMode.NINE_KEY -> null
        }
        return normalized?.takeIf { it.length in 1..MAX_READING }
    }

    fun normalizeText(input: String): String? {
        if (input.any { it in "\u0000\t\n\r\u000B\u000C\u0085\u2028\u2029" }) return null
        val text = input.trim()
        return text.takeIf { it.isNotEmpty() && it.codePointCount(0, it.length) <= MAX_TEXT }
    }

    fun matches(entries: List<Entry>, mode: InputMode, input: String): List<String> {
        if (entries.isEmpty()) return emptyList()
        if (mode == InputMode.NINE_KEY) {
            val raw = input.filterNot { it == ' ' || it == '\'' || it == '’' }
            return entries.asSequence().filter { it.mode == InputMode.PINYIN && matchesNineKey(it.reading, raw) }
                .map(Entry::text).distinct().toList()
        }
        val reading = normalizeReading(mode, input) ?: return emptyList()
        return entries.asSequence().filter { it.mode == mode && it.reading == reading }.map(Entry::text).toList()
    }

    private fun matchesNineKey(reading: String, raw: String): Boolean =
        reading.length == raw.length && reading.indices.all { index ->
            val key = raw[index]
            val letter = reading[index]
            if (key in 'A'..'Z') key.lowercaseChar() == letter else key == NineKeySyllables.digit(letter)
        }

    private fun latinLetter(ch: Char) = ch in 'a'..'z' || ch in 'A'..'Z'

    fun decode(raw: String?): List<Entry> {
        if (raw == null) return emptyList()
        val array = try { JSONArray(raw) } catch (_: JSONException) { return emptyList() }
        return buildList {
            for (index in 0 until minOf(array.length(), MAX_ENTRIES)) {
                val item = array.optJSONObject(index) ?: continue
                val mode = modes.firstOrNull { it.key == item.optString("mode") } ?: continue
                val reading = normalizeReading(mode, item.opt("reading") as? String ?: continue) ?: continue
                val text = normalizeText(item.opt("text") as? String ?: continue) ?: continue
                add(Entry(mode, reading, text))
            }
        }.distinct()
    }

    fun encode(entries: List<Entry>): String = JSONArray().apply {
        entries.forEach { entry ->
            put(JSONObject().put("mode", entry.mode.key).put("reading", entry.reading).put("text", entry.text))
        }
    }.toString()
}
