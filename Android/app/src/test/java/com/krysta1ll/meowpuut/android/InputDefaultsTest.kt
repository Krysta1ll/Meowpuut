package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.engine.CandidateKind
import com.krysta1ll.meowpuut.android.engine.CandidateMerge
import com.krysta1ll.meowpuut.android.engine.Composer
import com.krysta1ll.meowpuut.android.engine.CoreCandidate
import com.krysta1ll.meowpuut.android.engine.InputDefaults
import com.krysta1ll.meowpuut.android.engine.InputMode
import com.krysta1ll.meowpuut.android.engine.JapaneseWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputDefaultsTest {
    private val zhuyinTraditional = InputDefaults.Start(InputMode.ZHUYIN, false)
    private val pinyinSimplified = InputDefaults.Start(InputMode.PINYIN, true)
    private val pinyinTraditional = InputDefaults.Start(InputMode.PINYIN, false)

    @Test
    fun startFollowsRegionAndPreferredLanguages() {
        // region, preferred languages, expected start
        val table = listOf(
            Triple("TW", listOf("zh-Hant-TW"), zhuyinTraditional),
            Triple("TW", listOf("en-US"), zhuyinTraditional),
            Triple("US", listOf("zh-Hant-TW", "en-US"), zhuyinTraditional),
            Triple("US", listOf("zh-TW"), zhuyinTraditional),
            Triple("CN", listOf("zh-Hans-CN"), pinyinSimplified),
            Triple("SG", listOf("en-SG"), pinyinSimplified),
            Triple("MY", listOf("ms-MY"), pinyinSimplified),
            Triple("US", listOf("en-US", "zh-Hans-US"), pinyinSimplified),
            Triple("JP", listOf("zh-CN"), pinyinSimplified),
            Triple("HK", listOf("zh-Hant-HK"), pinyinTraditional),
            Triple("MO", listOf("en"), pinyinTraditional),
            Triple("hk", listOf("en"), pinyinTraditional),
            Triple("US", listOf("en-US", "zh-Hant"), pinyinTraditional),
            Triple("JP", listOf("ja-JP", "zh-Hant-HK"), pinyinTraditional),
            Triple("US", listOf("en-US"), pinyinSimplified),
            Triple(null, emptyList(), pinyinSimplified),
            Triple("", listOf("zh"), pinyinSimplified),
            // Any zh-Hans language wins over the order (rule 2 comes before the fallback).
            Triple("FR", listOf("fr-FR", "zh-Hant", "zh-Hans"), pinyinSimplified),
            Triple("FR", listOf("fr-FR", "zh-Hant", "zh"), pinyinTraditional),
            Triple("FR", listOf("fr-FR", "zh-Hans", "zh-Hant"), pinyinSimplified),
        )
        for ((region, languages, expected) in table) {
            assertEquals("$region $languages", expected, InputDefaults.choose(region, languages))
        }
    }

    @Test
    fun existingInstallsKeepMixedTypingAndTheirMode() {
        val existing = InputDefaults.firstRun(true, false, "TW", listOf("zh-Hant-TW"))
        assertTrue(existing.mixedJapanese)
        assertNull(existing.start)
        assertNull(InputDefaults.firstRun(true, true, "CN", emptyList()).start)
    }

    @Test
    fun freshInstallsStartWithMixedTypingOffAndAModeOnlyWhenNoneIsSet() {
        val fresh = InputDefaults.firstRun(false, false, "TW", emptyList())
        assertFalse(fresh.mixedJapanese)
        assertEquals(zhuyinTraditional, fresh.start)
        assertNull(InputDefaults.firstRun(false, true, "TW", emptyList()).start)
    }

    @Test
    fun japaneseModeIgnoresTheMixedTypingSwitch() {
        // The separate Japanese mode ignores the switch.
        for (mixed in listOf(false, true)) {
            val composer = Composer()
            composer.mixedJapanese = mixed
            composer.japaneseWords = JapaneseWords.parse("sushi|寿司|5000")
            composer.attach(InputMode.JAPANESE, 0)
            for (c in "sushi") composer.type(c.code)
            assertTrue("mixed=$mixed", composer.candidates.any { it.text == "寿司" })
        }
    }

    @Test
    fun withMixedTypingOffThePinyinListHoldsNoJapanese() {
        val chinese = listOf(CoreCandidate("蘇軾", 0, 5, true, false))
        // What Composer passes with the switch off: no word, no mixed sentence, no kana.
        val off = CandidateMerge.pinyin("sushi", "sushi", chinese, null, null, null, null, emptyList())
        val japaneseKinds = setOf(CandidateKind.JAPANESE_WORD, CandidateKind.MIXED, CandidateKind.MIXED_PREFIX, CandidateKind.KANA)
        assertTrue(off.none { it.kind in japaneseKinds })
        assertEquals(listOf("蘇軾", "sushi"), off.map { it.text })
        // And with it on the same reading does offer the Japanese word.
        val on = CandidateMerge.pinyin("sushi", "sushi", chinese, "寿司", null, null, "すし", emptyList())
        assertTrue(on.any { it.kind == CandidateKind.JAPANESE_WORD && it.text == "寿司" })
    }
}
