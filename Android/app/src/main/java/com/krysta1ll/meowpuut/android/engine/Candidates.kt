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

        /** The order the five touches of 米米莫 go through: 注音, 全鍵拼音, 九鍵拼音, 日文. */
        val SWITCH_ORDER = listOf(ZHUYIN, PINYIN, NINE_KEY, JAPANESE)

        /** What is ticked when nothing has been chosen: the three the touches have always gone through. */
        val SWITCH_DEFAULT = setOf(ZHUYIN, PINYIN, JAPANESE)

        /**
         * The way the five touches go to from [current], among the [enabled] ones, in [SWITCH_ORDER]; null when fewer than two are ticked
         * (the gesture is off). A way that is not ticked goes to the first one that is.
         */
        fun nextAmong(current: InputMode, enabled: Set<InputMode>): InputMode? {
            val on = SWITCH_ORDER.filter { it in enabled }
            if (on.size < 2) return null
            val at = on.indexOf(current)
            return if (at < 0) on.first() else on[(at + 1) % on.size]
        }
    }
}

enum class CandidateKind { CHINESE, JAPANESE_WORD, MIXED, MIXED_PREFIX, KANA, LITERAL, EMOJI, KAOMOJI, SYMBOL_EXTRA, CUSTOM }

/**
 * One choice. [coreIndex] addresses the engine's own list for Chinese
 * candidates; [consumed] is how many input bytes choosing it uses up.
 */
data class Candidate(val text: String, val kind: CandidateKind, val coreIndex: Int = -1, val consumed: Int = 0) {
    val japanese get() = kind == CandidateKind.JAPANESE_WORD || kind == CandidateKind.MIXED ||
        kind == CandidateKind.MIXED_PREFIX || kind == CandidateKind.KANA
    internal val selection get() = text to consumed
    internal fun continues(inputLength: Int) = consumed in 1 until inputLength
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
        val seen = HashSet<Pair<String, Int>>()
        for (c in chinese) {
            if (c.text.isEmpty()) continue
            val choice = c.choice()
            if (seen.add(choice.selection)) list += choice
        }
        val top = chinese.firstOrNull()
        // "sushi": 蘇軾 is an exact word and stays first; 寿司 still shows up front.
        val topComplete = top != null && top.exact && top.consumed >= length

        val best: Candidate? = when {
            japaneseWord != null && (japaneseWord to length) !in seen -> Candidate(japaneseWord, CandidateKind.JAPANESE_WORD, consumed = length)
            mixedWhole != null && mixedWhole.isNotEmpty() && (mixedWhole to length) !in seen -> Candidate(mixedWhole, CandidateKind.MIXED, consumed = length)
            mixedPrefix != null && mixedPrefix !in seen && mixedPrefix.second in 1 until length ->
                Candidate(mixedPrefix.first, CandidateKind.MIXED_PREFIX, consumed = mixedPrefix.second)
            else -> null
        }
        if (best != null) {
            seen += best.selection
            val position = when {
                list.isEmpty() -> 0
                best.consumed >= length && !topComplete -> 0
                else -> min(2, list.size)
            }
            list.add(position, best)
        }

        val extras = ArrayList<Candidate>()
        if (mixedPrefix != null && mixedPrefix.second in 1 until length && seen.add(mixedPrefix)) {
            extras += Candidate(mixedPrefix.first, CandidateKind.MIXED_PREFIX, consumed = mixedPrefix.second)
        }
        if (kana != null && length >= 2 && Romaji.isKana(kana)) {
            if (seen.add(kana to length)) extras += Candidate(kana, CandidateKind.KANA, consumed = length)
            val katakana = Romaji.katakana(kana)
            if (seen.add(katakana to length)) extras += Candidate(katakana, CandidateKind.KANA, consumed = length)
        }
        for (word in moreJapanese) {
            if (seen.add(word to length)) extras += Candidate(word, CandidateKind.JAPANESE_WORD, consumed = length)
        }

        if (typed.isNotEmpty() && seen.add(typed to length)) {
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

    internal fun withCustom(
        choices: List<Candidate>, matches: List<String>, inputLength: Int,
    ): List<Candidate> {
        if (matches.isEmpty()) return choices
        val texts = matches.toHashSet()
        val kept = choices.filterNot { it.text in texts && !it.continues(inputLength) }
        return matches.map { Candidate(it, CandidateKind.CUSTOM, consumed = inputLength) } + kept
    }

    internal fun customTexts(choices: List<Candidate>): Set<String> {
        if (choices.firstOrNull()?.kind != CandidateKind.CUSTOM) return emptySet()
        return choices.asSequence().takeWhile { it.kind == CandidateKind.CUSTOM }.map(Candidate::text).toSet()
    }

    /** Keep the complete literal reading after all choices, including newly loaded ones. */
    internal fun withZhuyinLiteral(choices: List<Candidate>, text: String, inputLength: Int): List<Candidate> {
        if (text.isEmpty()) return choices
        val existing = choices.indexOfFirst { it.text == text && it.consumed == inputLength }
        if (existing < 0) return choices + Candidate(text, CandidateKind.LITERAL, consumed = inputLength)
        if (existing == choices.lastIndex || choices[existing].kind != CandidateKind.LITERAL) return choices
        return choices.toMutableList().apply { add(removeAt(existing)) }
    }

    /** Keep the interpreted word first and associate only a full reading. */
    fun withSymbols(choices: List<Candidate>, symbols: SymbolAssociations, inputLength: Int): List<Candidate> {
        val first = choices.firstOrNull() ?: return choices
        if (first.kind == CandidateKind.LITERAL || first.kind == CandidateKind.CUSTOM || first.consumed != inputLength) return choices
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

    /** Keep the leading choice and put a native prefix on the first screen in segmented modes. */
    fun collapsed(all: List<Candidate>, limitJapanese: Boolean = true, segmentLength: Int = 0): List<Int> {
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
        val lead = out.firstOrNull()?.let(all::get)
        if (segmentLength > 0 && lead != null && !lead.continues(segmentLength)) {
            val whole = out.asSequence().map(all::get).firstOrNull {
                it.kind == CandidateKind.CHINESE && it.consumed == segmentLength
            }
            var prefix = -1
            for ((position, i) in out.withIndex()) {
                val candidate = all[i]
                if (candidate.kind != CandidateKind.CHINESE || !candidate.continues(segmentLength)) continue
                if (prefix < 0) prefix = position
                if (whole?.text?.startsWith(candidate.text) == true) {
                    prefix = position
                    break
                }
            }
            val position = if (out.getOrNull(1)?.let { all[it].kind } == CandidateKind.EMOJI) 2 else 1
            if (prefix > position) out.add(position, out.removeAt(prefix))
        }
        return out
    }
}
