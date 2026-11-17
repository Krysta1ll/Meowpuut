package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.engine.CandidateKind
import com.krysta1ll.meowpuut.android.engine.CandidateMerge
import com.krysta1ll.meowpuut.android.engine.Composer
import com.krysta1ll.meowpuut.android.engine.CoreCandidate
import com.krysta1ll.meowpuut.android.engine.CustomDictionary
import com.krysta1ll.meowpuut.android.engine.InputMode
import com.krysta1ll.meowpuut.android.engine.JapaneseWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomDictionaryTest {
    @Test
    fun matchesOnlyCompleteReadingsAndRespectsNineKeyLocks() {
        val first = CustomDictionary.Entry(InputMode.PINYIN, "wangni", "王妮")
        val second = CustomDictionary.Entry(InputMode.PINYIN, "yangni", "楊妮")
        val zhuyin = CustomDictionary.Entry(InputMode.ZHUYIN, "ㄨㄤˊㄋㄧ", "王妮")
        val entries = listOf(first, second, zhuyin)
        assertEquals("lve", CustomDictionary.normalizeReading(InputMode.PINYIN, " Lü ’E "))
        assertEquals("lve", CustomDictionary.normalizeReading(InputMode.PINYIN, "Lu\u0308e"))
        assertEquals(listOf("王妮"), CustomDictionary.matches(entries, InputMode.PINYIN, "Wang' ni"))
        assertEquals(listOf("王妮", "楊妮"), CustomDictionary.matches(entries, InputMode.NINE_KEY, "926464"))
        assertEquals(listOf("王妮"), CustomDictionary.matches(entries, InputMode.NINE_KEY, "WANG'64"))
        assertEquals(listOf("楊妮"), CustomDictionary.matches(entries, InputMode.NINE_KEY, "YANG'64"))
        assertTrue(CustomDictionary.matches(entries, InputMode.NINE_KEY, "YANG'6").isEmpty())
        assertEquals(listOf("王妮"), CustomDictionary.matches(entries, InputMode.ZHUYIN, "ㄨㄤˊ ㄋㄧˉ"))
        assertEquals(listOf("王妮"), CustomDictionary.matches(entries, InputMode.ZHUYIN, "ㄨㄤˊ'ㄋㄧˉ"))
        assertTrue(CustomDictionary.matches(entries, InputMode.ZHUYIN, "ㄨㄤˋ ㄋㄧˉ").isEmpty())
    }

    @Test
    fun preservesPhrasesWithoutLearningOrNativeDuplicates() {
        val manual = CustomDictionary.Entry(InputMode.JAPANESE, "neko", "猫")
        val extra = manual.copy(text = "猫ちゃん 🐾")
        val composer = Composer().apply {
            attach(InputMode.JAPANESE, 0)
            japaneseWords = JapaneseWords.parse("neko|猫|5000")
            customDictionary = listOf(manual, extra)
        }
        "NEKO".forEach { composer.type(it.code) }
        assertTrue(composer.candidates.none { it.kind == CandidateKind.CUSTOM })
        composer.clear()
        composer.allowCustomEntries = true
        "NEKO".forEach { composer.type(it.code) }
        assertEquals(listOf("猫", "猫ちゃん 🐾"), composer.candidates.take(2).map { it.text })
        assertEquals(1, composer.candidates.count { it.text == "猫" })
        val chosen = composer.choose(1, confirm = true)
        assertEquals("猫ちゃん 🐾", chosen.commit)
        assertEquals("", chosen.confirmedReading)
    }

    @Test
    fun keepsASameTextNativePrefixInTheExpandedPanel() {
        val core = listOf(
            CoreCandidate("你好", index = 0, consumed = 7, exact = true),
            CoreCandidate("你好", index = 1, consumed = 5, exact = false),
            CoreCandidate("你好我", index = 2, consumed = 7, exact = true),
        )
        val native = CandidateMerge.pinyin("nihaowo", "nihaowo", core, null, null, null, null)
        val expanded = CandidateMerge.withCustom(native, listOf("你好"), 7, core)
        val matching = expanded.filter { it.text == "你好" }
        assertEquals(listOf(7, 5), matching.map { it.consumed })
        assertEquals(CandidateKind.CUSTOM, matching.first().kind)
        assertEquals(1, matching.last().coreIndex)
        val compact = CandidateMerge.collapsed(expanded).map(expanded::get)
        assertEquals(listOf(matching.first()), compact.filter { it.text == "你好" })
    }

    @Test
    fun storesValidatedEntriesAndLimitsByCodePoint() {
        val entry = CustomDictionary.Entry(InputMode.JAPANESE, "kan'i", "猫🐾")
        assertEquals(listOf(entry), CustomDictionary.decode(CustomDictionary.encode(listOf(entry, entry))))
        assertEquals("🐾".repeat(100), CustomDictionary.normalizeText("🐾".repeat(100)))
        assertNull(CustomDictionary.normalizeText("🐾".repeat(101)))
        assertNull(CustomDictionary.normalizeText("貓\n爪"))
        assertNull(CustomDictionary.normalizeText("貓\t爪"))
        assertNull(CustomDictionary.normalizeText("貓\u0000爪"))
        assertNull(CustomDictionary.normalizeReading(InputMode.JAPANESE, "kan i"))
        assertNull(CustomDictionary.normalizeReading(InputMode.PINYIN, "a".repeat(49)))
    }

    @Test
    fun japaneseApostropheCanContinueAManualReading() {
        val composer = Composer().apply {
            attach(InputMode.JAPANESE, 0)
            customDictionary = listOf(CustomDictionary.Entry(InputMode.JAPANESE, "kan'i", "簡易 🐾"))
        }
        "kan".forEach { composer.type(it.code) }
        assertTrue(!composer.acceptsJapaneseReadingMark('\''))
        composer.allowCustomEntries = true
        assertTrue(composer.acceptsJapaneseReadingMark('\''))
        composer.type('\''.code)
        composer.type('i'.code)
        assertEquals("簡易 🐾", composer.choose(0).commit)
    }
}
