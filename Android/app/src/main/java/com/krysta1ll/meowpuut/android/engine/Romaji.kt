package com.krysta1ll.meowpuut.android.engine

/** Standard romaji spelling rules; the same table as Sources/JapaneseBackend.swift. */
object Romaji {
    private val kana: Map<String, String> = buildMap {
        val rows = listOf(
            "a i u e o|あいうえお", "ka ki ku ke ko|かきくけこ", "sa shi su se so|さしすせそ",
            "ta chi tsu te to|たちつてと", "na ni nu ne no|なにぬねの", "ha hi fu he ho|はひふへほ",
            "ma mi mu me mo|まみむめも", "ya yu yo|やゆよ", "ra ri ru re ro|らりるれろ", "wa wo|わを",
            "ga gi gu ge go|がぎぐげご", "za ji zu ze zo|ざじずぜぞ", "da di du de do|だぢづでど",
            "ba bi bu be bo|ばびぶべぼ", "pa pi pu pe po|ぱぴぷぺぽ", "va vi vu ve vo|ゔぁ,ゔぃ,ゔ,ゔぇ,ゔぉ",
            "xa xi xu xe xo|ぁぃぅぇぉ", "xya xyu xyo|ゃゅょ", "xtsu|っ", "xwa|ゎ",
        )
        for (row in rows) {
            val (keys, kanaText) = row.split("|")
            val values = if (kanaText.contains(",")) kanaText.split(",") else kanaText.map { it.toString() }
            keys.split(" ").zip(values).forEach { (k, v) -> put(k, v) }
        }
        val prefixes = listOf("ky" to "き", "gy" to "ぎ", "sh" to "し", "ch" to "ち", "ny" to "に", "hy" to "ひ",
            "by" to "び", "py" to "ぴ", "my" to "み", "ry" to "り", "j" to "じ")
        for ((prefix, base) in prefixes) {
            for ((vowel, small) in listOf("a" to "ゃ", "u" to "ゅ", "o" to "ょ", "e" to "ぇ")) put(prefix + vowel, base + small)
        }
        val aliases = mapOf("si" to "shi", "ti" to "chi", "tu" to "tsu", "hu" to "fu", "zi" to "ji", "sya" to "sha",
            "syu" to "shu", "syo" to "sho", "tya" to "cha", "tyu" to "chu", "tyo" to "cho", "cya" to "cha",
            "cyu" to "chu", "cyo" to "cho", "zya" to "ja", "zyu" to "ju", "zyo" to "jo", "jya" to "ja",
            "jyu" to "ju", "jyo" to "jo", "xtu" to "xtsu")
        for ((alias, standard) in aliases) get(standard)?.let { put(alias, it) }
        // Copy the pairs first: the builder's own entries refuse to be read after a put.
        for ((k, v) in entries.map { it.key to it.value }) if (k.startsWith("x")) put("l" + k.drop(1), v)
        val extra = mapOf("fa" to "ふぁ", "fi" to "ふぃ", "fe" to "ふぇ", "fo" to "ふぉ", "wi" to "うぃ", "we" to "うぇ",
            "who" to "うぉ", "she" to "しぇ", "che" to "ちぇ", "thi" to "てぃ", "dhi" to "でぃ", "twu" to "とぅ",
            "dwu" to "どぅ", "fyu" to "ふゅ", "ye" to "いぇ")
        putAll(extra)
    }

    fun reading(raw: String): String {
        val input = raw.lowercase()
        val out = StringBuilder()
        var position = 0
        while (position < input.length) {
            val key = input[position]
            val next = input.getOrNull(position + 1)
            if (key == '-') { out.append('ー'); position++; continue }
            if (key == '\'') { position++; continue }
            if (key == 'n') {
                if (next == '\'') { out.append('ん'); position += 2; continue }
                if (next == null) { out.append('ん'); position++; continue }
                if (next == 'n') {
                    out.append('ん')
                    position += if (position + 2 == input.length) 2 else 1
                    continue
                }
                if (next !in "aiueoy") { out.append('ん'); position++; continue }
            }
            if (key == next && key !in "aiueon") { out.append('っ'); position++; continue }
            var matched = false
            for (count in minOf(4, input.length - position) downTo 1) {
                val value = kana[input.substring(position, position + count)]
                if (value != null) { out.append(value); position += count; matched = true; break }
            }
            if (!matched) { out.append(key); position++ }
        }
        return out.toString()
    }

    fun katakana(hiragana: String): String = buildString(hiragana.length) {
        for (c in hiragana) append(if (c in 'ぁ'..'ゖ' || c == 'ゝ' || c == 'ゞ') c + 0x60 else c)
    }

    fun isKana(text: String) = text.isNotEmpty() && text.all { it in '぀'..'ヿ' }
}
