package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.engine.EnglishSource
import com.krysta1ll.meowpuut.android.engine.EnglishSuggestions
import com.krysta1ll.meowpuut.android.engine.InputMode
import com.krysta1ll.meowpuut.android.ime.KeyAction
import com.krysta1ll.meowpuut.android.ime.KeyBuilder
import com.krysta1ll.meowpuut.android.ime.KeyContext
import com.krysta1ll.meowpuut.android.ime.ShiftState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** English suggestions: what is passed to the core, what is shown, and how the keyboard switches. The core is faked here. */
class EnglishSuggestionsTest {
    private class Fake : EnglishSource {
        val asked = ArrayList<String>()
        var known = setOf("hello", "help", "the")
        override fun complete(prefix: String, before: String, flags: Int): List<String> {
            asked += "complete($prefix|$before|$flags)"
            return known.filter { it.startsWith(prefix.lowercase()) }
        }
        override fun correct(word: String, before: String, flags: Int): List<String> {
            asked += "correct($word|$before|$flags)"
            return if (word.lowercase() == "teh") listOf("the") else emptyList()
        }
        override fun predict(before: String, flags: Int): List<String> {
            asked += "predict($before|$flags)"
            return listOf("you", "the")
        }
    }

    @Test
    fun wordBeforeTheCursorIsTheLettersAndApostrophes() {
        assertEquals("hel", EnglishSuggestions.wordBefore("say hel"))
        assertEquals("don't", EnglishSuggestions.wordBefore("I don't"))
        assertEquals("", EnglishSuggestions.wordBefore("hello "))
        assertEquals("", EnglishSuggestions.wordBefore(""))
        // A code with a digit, and a run too long for a word, get no word.
        assertEquals("", EnglishSuggestions.wordBefore("abc1de"))
        assertEquals("", EnglishSuggestions.wordBefore("a".repeat(30)))
        assertEquals("hi", EnglishSuggestions.wordBefore("你好hi"))
        // A quote or dash in front stays out of the word a tap replaces.
        assertEquals("hel", EnglishSuggestions.wordBefore("'hel"))
        assertEquals("hel", EnglishSuggestions.wordBefore("a -hel"))
        assertEquals("", EnglishSuggestions.wordBefore("hello '-"))
    }

    @Test
    fun onlyTheLastTwoWordsAreGivenToTheCore() {
        assertEquals("is the ", EnglishSuggestions.context("My name is the ", 0))
        // The word being typed counts as one of the two; with its length given it is left out.
        assertEquals("the hel", EnglishSuggestions.context("My name is the hel", 0))
        assertEquals("is the ", EnglishSuggestions.context("My name is the hel", 3))
        // White space is one space, sentence marks stay, what is earlier is gone.
        assertEquals("Hi. Then ", EnglishSuggestions.context("Oh well.  Hi.\n\nThen ", 0))
        assertEquals("", EnglishSuggestions.context("", 0))
        assertEquals(" ", EnglishSuggestions.context("   ", 0))
        assertEquals("Hello. ", EnglishSuggestions.context("Hello. ", 0))
    }

    @Test
    fun otherTextStopsTheContext() {
        // A number or Chinese text is not passed: a comma stands for it.
        assertEquals(",in ", EnglishSuggestions.context("x 555 in ", 0))
        assertEquals(",", EnglishSuggestions.context("你好 ", 0))
        assertFalse(EnglishSuggestions.context("pin 4921 ok ", 0).contains("4921"))
        assertFalse(EnglishSuggestions.context("secret 4921 ok ", 0).contains("secret"))
        assertEquals("word word ", EnglishSuggestions.context("word ".repeat(40), 0))
    }

    @Test
    fun completionsAndCorrectionAreOfferedForAWord() {
        val source = Fake()
        val plan = EnglishSuggestions.plan(source, "Say hel", 0)
        assertEquals("hel", plan.word)
        assertEquals(listOf("hello", "help"), plan.words)
        assertEquals(listOf("complete(hel|Say |0)", "correct(hel|Say |0)"), source.asked)
        // A word the list does not know: the correction first, then its completions.
        assertEquals(listOf("the"), EnglishSuggestions.plan(Fake(), "teh", 0).words)
        // The core offers corrections for any prefix that is not a word: they never crowd out completions.
        val crowded = object : EnglishSource by Fake() {
            override fun complete(prefix: String, before: String, flags: Int) = (0 until 8).map { "c$it" }
            override fun correct(word: String, before: String, flags: Int) = (0 until 8).map { "k$it" }
        }
        assertEquals((0 until 8).map { "c$it" }, EnglishSuggestions.plan(crowded, "hel", 0).words)
        // Completions that fill the bar: the costly correction search is not asked for at all.
        val counted = object : EnglishSource by Fake() {
            val asked = ArrayList<String>()
            override fun complete(prefix: String, before: String, flags: Int) = (0 until 8).map { "c$it" }
            override fun correct(word: String, before: String, flags: Int): List<String> { asked += word; return emptyList() }
        }
        EnglishSuggestions.plan(counted, "hel", 0)
        assertTrue(counted.asked.isEmpty())
        // A proper noun that changes the typed casing (teh -> Tehran) does not lead over the correction.
        val proper = object : EnglishSource by Fake() {
            override fun complete(prefix: String, before: String, flags: Int) = listOf("Tehran")
        }
        assertEquals(listOf("the", "Tehran"), EnglishSuggestions.plan(proper, "teh", 0).words)
    }

    @Test
    fun afterASpaceTheNextWordsAreOffered() {
        val source = Fake()
        val plan = EnglishSuggestions.plan(source, "How are ", EnglishSuggestions.SHIFT)
        assertEquals("", plan.word)
        assertEquals(listOf("you", "the"), plan.words)
        assertEquals(listOf("predict(How are |1)"), source.asked)
        assertEquals(listOf("predict(|0)"), Fake().also { EnglishSuggestions.plan(it, "", 0) }.asked)
    }

    @Test
    fun nothingRightAfterAMarkOrANumber() {
        val source = Fake()
        assertTrue(EnglishSuggestions.plan(source, "Hello,", 0).words.isEmpty())
        assertTrue(EnglishSuggestions.plan(source, "abc1de", 0).words.isEmpty())
        assertTrue(source.asked.isEmpty())
    }

    @Test
    fun aTapReplacesTheWordAndAddsASpace() {
        val plan = EnglishSuggestions.Plan("hel", listOf("hello"))
        val edit = EnglishSuggestions.apply(plan, "hello")
        assertEquals(3, edit.deleteBefore)
        assertEquals("hello ", edit.insert)
        // A next word only follows.
        val next = EnglishSuggestions.apply(EnglishSuggestions.Plan("", listOf("you")), "you")
        assertEquals(0, next.deleteBefore)
        assertEquals("you ", next.insert)
    }

    @Test
    fun shiftFlagsFollowTheShiftState() {
        assertEquals(0, EnglishSuggestions.flags(false, false))
        assertEquals(EnglishSuggestions.SHIFT, EnglishSuggestions.flags(true, false))
        assertEquals(EnglishSuggestions.CAPS_LOCK, EnglishSuggestions.flags(true, true))
    }

    @Test
    fun switchingFromChineseNineKeyShowsAllTwentySixEnglishLetters() {
        val context = KeyContext(InputMode.NINE_KEY, false, 0, ShiftState.OFF, false, false,
            "space", "換行", false, true)
        val chinese = KeyBuilder.build(context)
        assertTrue(context.showsNineKey)
        assertEquals("ABC", chinese.rows[0][2].label)
        assertEquals("WXYZ", chinese.rows[2][3].label)
        assertEquals("英", chinese.rows.last().single { it.action == KeyAction.ToggleEnglish }.label)

        val englishContext = context.copy(english = true)
        val english = KeyBuilder.build(englishContext)
        assertFalse(englishContext.showsNineKey)
        assertEquals("qwertyuiopasdfghjklzxcvbnm",
            english.rows.flatten().filter { it.action is KeyAction.Engine }.joinToString("") { it.label })
        assertEquals("中", english.rows.last().single { it.action == KeyAction.ToggleEnglish }.label)
    }
}
