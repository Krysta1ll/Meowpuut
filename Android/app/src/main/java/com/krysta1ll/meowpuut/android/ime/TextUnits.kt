package com.krysta1ll.meowpuut.android.ime

import java.text.BreakIterator
import java.util.Locale
import kotlin.math.max

/**
 * How many UTF-16 units the delete key removes from the end of the text
 * before the cursor. On Android java.text.BreakIterator is ICU, so an emoji
 * sequence is one character and Chinese is split into words.
 */
object TextUnits {
    private val characters = ThreadLocal.withInitial { BreakIterator.getCharacterInstance(Locale.ROOT) }

    /** One user-perceived character. */
    fun lastGrapheme(text: CharSequence): Int {
        if (text.isEmpty()) return 0
        val iterator = checkNotNull(characters.get())
        iterator.setText(text.toString())
        val end = iterator.last()
        val start = iterator.previous()
        iterator.setText("")
        return if (start == BreakIterator.DONE) text.length else end - start
    }

    fun graphemeCount(text: String): Int {
        if (text.length == 1 || text.all { it.code < 128 && it != '\r' }) return text.length
        val iterator = checkNotNull(characters.get())
        iterator.setText(text)
        var count = 0
        while (iterator.next() != BreakIterator.DONE) count++
        iterator.setText("")
        return count
    }

    /** Trailing spaces and the word before them (a Chinese word, not one character). */
    fun wordLength(text: CharSequence): Int {
        var end = text.length
        while (end > 0 && text[end - 1].isWhitespace()) end--
        if (end == 0) return text.length
        val iterator = BreakIterator.getWordInstance()
        iterator.setText(text.subSequence(0, end).toString())
        val start = iterator.preceding(end)
        val word = if (start == BreakIterator.DONE) 1 else end - start
        return text.length - end + max(1, word)
    }
}
