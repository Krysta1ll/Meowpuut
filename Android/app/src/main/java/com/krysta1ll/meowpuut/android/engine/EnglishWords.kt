package com.krysta1ll.meowpuut.android.engine

import android.content.res.AssetFileDescriptor

/** What the keyboard asks of the English word list (mimo_en.bin, read by the core). */
interface EnglishSource {
    /** Words the typed [prefix] may become; [before] is the context (see [EnglishSuggestions.context]). */
    fun complete(prefix: String, before: String, flags: Int): List<String>

    /** Corrections of [word]; empty for a known word. */
    fun correct(word: String, before: String, flags: Int): List<String>

    /** The words that may follow [before]. */
    fun predict(before: String, flags: Int): List<String>

    /** The words keypad [digits] (2-9) spell. */
    fun nineKey(digits: String, before: String, flags: Int): List<String>
}

/**
 * The core's English word list, mapped in place from the APK (nothing is copied to the heap). One
 * thread only: the keyboard's main thread. Nothing it is given is kept after a call.
 */
class NativeEnglishSource private constructor(private var handle: Long) : EnglishSource, AutoCloseable {
    val wordCount get() = if (handle == 0L) 0 else NativeEnglish.wordCount(handle)

    private fun ask(mode: Int, word: String, before: String, flags: Int): List<String> =
        if (handle == 0L) emptyList() else NativeEnglish.query(handle, mode, word, before, flags).filter { it.isNotEmpty() }

    override fun complete(prefix: String, before: String, flags: Int) = ask(NativeEnglish.COMPLETE, prefix, before, flags)
    override fun correct(word: String, before: String, flags: Int) = ask(NativeEnglish.CORRECT, word, before, flags)
    override fun predict(before: String, flags: Int) = ask(NativeEnglish.PREDICT, "", before, flags)
    override fun nineKey(digits: String, before: String, flags: Int) = ask(NativeEnglish.NINE_KEY, digits, before, flags)

    override fun close() {
        if (handle != 0L) NativeEnglish.close(handle)
        handle = 0L
    }

    companion object {
        /** Null when the file is missing or damaged. */
        fun open(fd: AssetFileDescriptor): NativeEnglishSource? {
            val handle = NativeEnglish.openFromFd(fd.parcelFileDescriptor.fd, fd.startOffset, fd.length)
            return if (handle == 0L) null else NativeEnglishSource(handle)
        }
    }
}

/**
 * English suggestions: what the core is asked and what is passed to it (docs/PRIVACY.md). Only the
 * last two words before the one being typed go to the core, with the sentence marks between them;
 * numbers, other scripts and everything earlier stay in the editor. A word is replaced only when its
 * suggestion is tapped: nothing here changes the text on its own.
 */
object EnglishSuggestions {
    const val SHIFT = 1
    const val CAPS_LOCK = 2
    const val LIMIT = 8
    private const val LONGEST_WORD = 24
    private const val CONTEXT_WORDS = 2

    /** What to show, and what a tap does with the word before the cursor. */
    data class Plan(val word: String, val words: List<String>)

    private fun isWordChar(c: Char) = (c in 'a'..'z') || (c in 'A'..'Z') || c == '\'' || c == '’' || c == '-'

    /** The letters, apostrophes and hyphens right before the cursor. */
    fun wordBefore(text: CharSequence): String = scan(text).first

    /** The word before the cursor and the length of the quote marks or dashes in front of it (not replaced by a tap). */
    private fun scan(text: CharSequence): Pair<String, Int> {
        var start = text.length
        while (start > 0 && isWordChar(text[start - 1])) start--
        // A longer run is not a word the list could know, and one glued to a digit (abc1de) is a code.
        if (text.length - start > LONGEST_WORD || (start > 0 && text[start - 1] in '0'..'9')) return "" to 0
        var junk = 0
        while (start + junk < text.length && !(text[start + junk] in 'a'..'z' || text[start + junk] in 'A'..'Z')) junk++
        return text.substring(start + junk) to junk
    }

    /**
     * The text handed to the core for the cursor after [text] minus the [wordLength] letters being typed: at most
     * [CONTEXT_WORDS] words with the white space (as one space) and sentence marks between them. A run of
     * anything else (a digit, a Chinese character, an emoji) ends the context and is passed as a comma.
     */
    fun context(text: CharSequence, wordLength: Int): String {
        var i = (text.length - wordLength).coerceAtLeast(0)
        val parts = ArrayList<String>()   // from the cursor backwards
        var words = 0
        while (i > 0) {
            var start = i
            if (isWordChar(text[i - 1])) {
                if (words == CONTEXT_WORDS) break
                while (start > 0 && isWordChar(text[start - 1])) start--
                if (i - start > LONGEST_WORD) { parts += ","; break }
                parts += text.substring(start, i)
                words++
            } else {
                var foreign = false
                while (start > 0 && !isWordChar(text[start - 1])) {
                    if (!isSeparator(text[start - 1])) foreign = true
                    start--
                }
                if (foreign) { parts += ","; break }
                // What stands before the earliest word is not passed.
                if (words == CONTEXT_WORDS) break
                parts += tidy(text.substring(start, i))
            }
            i = start
        }
        return parts.asReversed().joinToString("")
    }

    private fun isSeparator(c: Char) = c.isWhitespace() || c in ".,!?;:…\"“”()"

    /** White space becomes one space; marks stay. */
    private fun tidy(run: String): String {
        val out = StringBuilder()
        for (c in run) {
            if (c.isWhitespace()) { if (out.lastOrNull() != ' ') out.append(' ') } else out.append(c)
        }
        return if (out.length > 6) out.substring(out.length - 6) else out.toString()
    }

    /** Shift as the core takes it. */
    fun flags(shift: Boolean, locked: Boolean) = if (locked) CAPS_LOCK else if (shift) SHIFT else 0

    /**
     * The cells for the text before the cursor: for a word being typed, its completions and, when they leave room,
     * the correction of a word the list does not know; after a space or at the start of the text, the next words.
     * Nothing right after a mark or a number: the next word is not predictable from it.
     */
    fun plan(source: EnglishSource, before: CharSequence, flags: Int): Plan {
        val (word, junk) = scan(before)
        // A word the list cannot take (too long, or glued to a digit) gets nothing, and so does what follows it.
        if (word.isEmpty() && before.lastOrNull()?.let(::isWordChar) == true) return Plan("", emptyList())
        val context = context(before, word.length + junk)
        if (word.isEmpty()) {
            val last = before.lastOrNull()
            if (last != null && !last.isWhitespace()) return Plan("", emptyList())
            return Plan("", source.predict(context, flags).distinct().take(LIMIT))
        }
        // Completions first. A correction is for a word that goes nowhere (becuase, teh): the core offers one for
        // any prefix that is not a word (hel), and those must not crowd the completions out. It leads only when
        // nothing continues the letters, or the first completion is a proper noun or an acronym that changes the
        // casing typed (teh -> Tehran), which is a worse guess than the typo.
        // The correction search is the costly lookup: skip it when the completions already fill the bar.
        val completions = source.complete(word, context, flags)
        val properFirst = completions.firstOrNull()?.let { it[0].isUpperCase() && !word[0].isUpperCase() } ?: false
        val full = !properFirst && completions.distinct().size >= LIMIT
        val corrections = if (full) emptyList() else source.correct(word, context, flags)
        val words = LinkedHashSet<String>()
        words += if (properFirst) corrections + completions else completions + corrections
        return Plan(word, words.take(LIMIT))
    }

    /** What a tap on [choice] does: delete [Edit.deleteBefore] characters before the cursor, then insert [Edit.insert]. */
    data class Edit(val deleteBefore: Int, val insert: String)

    fun apply(plan: Plan, choice: String) = Edit(plan.word.length, "$choice ")
}
