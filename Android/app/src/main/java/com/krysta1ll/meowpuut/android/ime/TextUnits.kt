package com.krysta1ll.meowpuut.android.ime

import java.text.BreakIterator
import kotlin.math.max

/**
 * How many UTF-16 units the delete key removes from the end of the text
 * before the cursor. On Android java.text.BreakIterator is ICU, so an emoji
 * sequence is one character and Chinese is split into words.
 */
object TextUnits {
    /** One user-perceived character. */
    fun lastGrapheme(text: CharSequence): Int {
        if (text.isEmpty()) return 0
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(text.toString())
        val end = iterator.last()
        val start = iterator.previous()
        return if (start == BreakIterator.DONE) text.length else end - start
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
