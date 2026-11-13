package com.krysta1ll.meowpuut.android.engine

import kotlin.math.min

enum class InputMode(val key: String) {
    PINYIN("pinyin"), ZHUYIN("zhuyin"), JAPANESE("japanese"), NINE_KEY("nine-key");

    val pinyinBased get() = this == PINYIN || this == NINE_KEY

    fun next(): InputMode = when (this) {
        ZHUYIN -> PINYIN
        PINYIN, NINE_KEY -> JAPANESE
        JAPANESE -> ZHUYIN
    }

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: ZHUYIN
    }
}

enum class CandidateKind { CHINESE, JAPANESE_WORD, MIXED, MIXED_PREFIX, KANA, LITERAL, EMOJI, KAOMOJI, SYMBOL_EXTRA }

/**
 * One choice. [coreIndex] addresses the engine's own list for Chinese
 * candidates; [consumed] is how many input bytes choosing it uses up.
 */
data class Candidate(val text: String, val kind: CandidateKind, val coreIndex: Int = -1, val consumed: Int = 0) {
    val japanese get() = kind == CandidateKind.JAPANESE_WORD || kind == CandidateKind.MIXED ||
        kind == CandidateKind.MIXED_PREFIX || kind == CandidateKind.KANA
}

/** A Chinese candidate as the engine reports it. */
data class CoreCandidate(val text: String, val index: Int, val consumed: Int, val exact: Boolean, val japanese: Boolean = false) {
    fun choice() = Candidate(text, if (japanese) CandidateKind.MIXED else CandidateKind.CHINESE, index, consumed)
}

/**
 * Orders a pinyin composition's choices (docs/DESIGN.md §4, README):
 * Chinese first, one Japanese interpretation near the front, the raw
 * letters kept reachable, and every other Japanese reading after the first
 * page so the collapsed bar never fills with them.
 */
object CandidateMerge {
    fun pinyin(
        input: String,
        typed: String,
        chinese: List<CoreCandidate>,
        japaneseWord: String?,
        mixedWhole: String?,
        mixedPrefix: Pair<String, Int>?,
        kana: String?,
        /** Further mimo-jp words with the same reading, weaker than [japaneseWord]. */
        moreJapanese: List<String> = emptyList(),
    ): List<Candidate> {
        val length = input.length
        val list = ArrayList<Candidate>(chinese.size + 6)
        val seen = HashSet<String>()
        for (c in chinese) {
            if (c.text.isNotEmpty() && seen.add(c.text)) list += Candidate(c.text, CandidateKind.CHINESE, c.index, c.consumed)
        }
        val top = chinese.firstOrNull()
        // "sushi": 蘇軾 is an exact word and stays first; 寿司 still shows up front.
        val topComplete = top != null && top.exact && top.consumed >= length

        val best: Candidate? = when {
            japaneseWord != null && japaneseWord !in seen -> Candidate(japaneseWord, CandidateKind.JAPANESE_WORD, consumed = length)
            mixedWhole != null && mixedWhole.isNotEmpty() && mixedWhole !in seen -> Candidate(mixedWhole, CandidateKind.MIXED, consumed = length)
            mixedPrefix != null && mixedPrefix.first !in seen && mixedPrefix.second in 1 until length ->
                Candidate(mixedPrefix.first, CandidateKind.MIXED_PREFIX, consumed = mixedPrefix.second)
            else -> null
        }
        if (best != null) {
            seen += best.text
            val position = when {
                list.isEmpty() -> 0
                best.consumed >= length && !topComplete -> 0
                else -> min(2, list.size)
            }
            list.add(position, best)
        }

        val extras = ArrayList<Candidate>()
        if (mixedPrefix != null && mixedPrefix.second in 1 until length && seen.add(mixedPrefix.first)) {
            extras += Candidate(mixedPrefix.first, CandidateKind.MIXED_PREFIX, consumed = mixedPrefix.second)
        }
        if (kana != null && length >= 2 && Romaji.isKana(kana)) {
            if (seen.add(kana)) extras += Candidate(kana, CandidateKind.KANA, consumed = length)
            val katakana = Romaji.katakana(kana)
            if (seen.add(katakana)) extras += Candidate(katakana, CandidateKind.KANA, consumed = length)
        }
        for (word in moreJapanese) {
            if (seen.add(word)) extras += Candidate(word, CandidateKind.JAPANESE_WORD, consumed = length)
        }

        if (typed.isNotEmpty() && seen.add(typed)) {
            val literal = Candidate(typed, CandidateKind.LITERAL, consumed = length)
            val position = when {
                length == 1 -> min(6, list.size)
                !topComplete && best == null -> min(1, list.size)
                else -> min(8, list.size)
            }
            list.add(position, literal)
        }
        list.addAll(min(9, list.size), extras)
        return list
    }

    /** Keep the interpreted word first and associate only a full reading. */
    fun withSymbols(choices: List<Candidate>, symbols: SymbolAssociations, inputLength: Int): List<Candidate> {
        val first = choices.firstOrNull() ?: return choices
        if (first.kind == CandidateKind.LITERAL || first.consumed != inputLength) return choices
        val marks = symbols.of(first.text, japanese = first.japanese) ?: return choices
        val primaryEmoji = marks.emoji.firstOrNull { it != first.text }
        val primaryKaomoji = if (first.text.codePointCount(0, first.text.length) > 1) {
            marks.kaomoji.firstOrNull { it != first.text }
        } else null
        val related = (marks.emoji + marks.kaomoji).distinct().filterNot { it == first.text }
        if (related.isEmpty()) return choices
        val result = ArrayList<Candidate>(choices.size + related.size)
        result += choices.filterNot { it.text in related }
        if (primaryEmoji != null) result.add(1, Candidate(primaryEmoji, CandidateKind.EMOJI, consumed = inputLength))
        if (primaryKaomoji != null && primaryKaomoji != primaryEmoji) {
            val position = min(result.size, if (primaryEmoji == null) 3 else 4)
            result.add(position, Candidate(primaryKaomoji, CandidateKind.KAOMOJI, consumed = inputLength))
        }
        var expandedPosition = min(9, result.size)
        for (mark in related) if (mark != primaryEmoji && mark != primaryKaomoji) {
            result.add(expandedPosition++, Candidate(mark, CandidateKind.SYMBOL_EXTRA, consumed = inputLength))
        }
        return result
    }

    /** Secondary symbols and repeated Japanese suggestions wait in the panel. */
    fun collapsed(all: List<Candidate>, limitJapanese: Boolean = true): List<Int> {
        var japaneseShown = false
        val out = ArrayList<Int>(all.size)
        for ((i, c) in all.withIndex()) {
            if (c.kind == CandidateKind.SYMBOL_EXTRA) continue
            if (limitJapanese && c.japanese) {
                if (japaneseShown) continue
                japaneseShown = true
            }
            out += i
        }
        return out
    }
}
