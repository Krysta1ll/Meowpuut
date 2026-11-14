package com.krysta1ll.meowpuut.android

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.krysta1ll.meowpuut.android.engine.Candidate
import com.krysta1ll.meowpuut.android.engine.CandidateKind
import com.krysta1ll.meowpuut.android.engine.CandidateMerge
import com.krysta1ll.meowpuut.android.engine.ChoiceMemory
import com.krysta1ll.meowpuut.android.engine.Composer
import com.krysta1ll.meowpuut.android.engine.CoreCandidate
import com.krysta1ll.meowpuut.android.engine.InputMode
import com.krysta1ll.meowpuut.android.engine.JapaneseWords
import com.krysta1ll.meowpuut.android.engine.Romaji
import com.krysta1ll.meowpuut.android.engine.SymbolAssociations
import com.krysta1ll.meowpuut.android.ime.Capitalization
import com.krysta1ll.meowpuut.android.ime.CatAffection
import com.krysta1ll.meowpuut.android.ime.DeleteRepeat
import com.krysta1ll.meowpuut.android.ime.FestivalDays
import com.krysta1ll.meowpuut.android.ime.FestivalInsets
import com.krysta1ll.meowpuut.android.ime.FestivalWardrobe
import com.krysta1ll.meowpuut.android.ime.Festivals
import com.krysta1ll.meowpuut.android.ime.GlassOutline
import com.krysta1ll.meowpuut.android.ime.GlassShape
import com.krysta1ll.meowpuut.android.ime.Box
import com.krysta1ll.meowpuut.android.ime.PathSink
import com.krysta1ll.meowpuut.android.ime.KeyAction
import com.krysta1ll.meowpuut.android.ime.KeyBuilder
import com.krysta1ll.meowpuut.android.ime.KeyContext
import com.krysta1ll.meowpuut.android.ime.KeyStyle
import com.krysta1ll.meowpuut.android.ime.Look
import com.krysta1ll.meowpuut.android.ime.MeowpuutIME
import com.krysta1ll.meowpuut.android.ime.ShiftState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import kotlin.math.abs

class LogicTest {
    @Test
    fun memoryPreservesCandidateSlotsAndLearnsOnlyConsumedReading() {
        val lead = Candidate("你好", CandidateKind.CHINESE, 4, 5)
        val alternate = lead.copy(text = "擬好", coreIndex = 81)
        val prefix = lead.copy(text = "你", coreIndex = 5, consumed = 2)
        val japanese = Candidate("に", CandidateKind.KANA, consumed = 5)
        val choices = listOf(lead, japanese, prefix, alternate)
        assertEquals(listOf(alternate, japanese, prefix, lead),
            ChoiceMemory.rank(choices, 5, mapOf("擬好" to 2, "你" to 8)))
        assertEquals("ni", ChoiceMemory.selectionReading("NIhaox", prefix))
        assertEquals("", ChoiceMemory.selectionReading("nihao", japanese))
        assertEquals("", ChoiceMemory.selectionReading("nihao", alternate.copy(consumed = 6)))
        assertEquals("", ChoiceMemory.selectionReading("nihao", alternate.copy(text = "nihao")))
    }

    @Test
    fun memoryIgnoresPrivateEditors() {
        val text = InputType.TYPE_CLASS_TEXT
        assertTrue(MeowpuutIME.allowsChoiceMemory(text, 0))
        assertTrue(!MeowpuutIME.allowsChoiceMemory(text, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
        assertTrue(!MeowpuutIME.allowsChoiceMemory(text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, 0))
        assertTrue(!MeowpuutIME.allowsChoiceMemory(InputType.TYPE_NULL, 0))
    }
    @Test
    fun associatedSymbolsKeepTheLeadAndCoreIndicesAndFitCollapsed() {
        val associations = SymbolAssociations.parse(File("../../Engine/Resources/Lexicon/associations.json").readText())
        val lead = Candidate("你好", CandidateKind.CHINESE, coreIndex = 7, consumed = 5)
        val chinese = listOf(lead, lead.copy(text = "您好", coreIndex = 9), lead.copy(text = "擬好", coreIndex = 13))
        val choices = CandidateMerge.withSymbols(chinese, associations, 5)
        assertEquals(lead, choices.first())
        assertEquals(Candidate("👋", CandidateKind.EMOJI, consumed = 5), choices[1])
        assertEquals(CandidateKind.KAOMOJI, choices[4].kind)
        assertEquals(listOf(7, 9, 13), choices.filter { it.coreIndex >= 0 }.map { it.coreIndex })
        assertEquals(listOf(0, 1, 2, 3, 4), CandidateMerge.collapsed(choices))
        assertTrue(choices.drop(5).all { it.kind == CandidateKind.SYMBOL_EXTRA })
        assertEquals(chinese, CandidateMerge.withSymbols(chinese, associations, 6)) // nihaox
        val long = chinese + (20..35).map { lead.copy(text = "詞$it", coreIndex = it) }
        val expanded = CandidateMerge.withSymbols(long, associations, 5)
        assertEquals(CandidateKind.SYMBOL_EXTRA, expanded[9].kind)
        assertEquals(long, CandidateMerge.collapsed(expanded).map(expanded::get).filter { it.coreIndex >= 0 })
        assertEquals("😊", associations.of("開心")?.emoji?.first())
        assertEquals("😊", associations.of("开心")?.emoji?.first())
        assertNull(associations.of("手紙"))
        assertEquals("✉️", associations.of("手紙", japanese = true)?.emoji?.first())
        for (word in listOf("猫", "貓", "火")) {
            val single = Candidate(word, CandidateKind.CHINESE, 3, 3)
            val result = CandidateMerge.withSymbols(listOf(single), associations, 3)
            assertEquals(if (word == "火") "🔥" else "🐱", result[1].text)
            assertEquals(listOf(0, 1), CandidateMerge.collapsed(result))
        }
    }

    @Test
    fun japaneseAssociatedEmojiCommitsTheSymbolAloneAndEndsComposition() {
        val composer = Composer()
        composer.attach(InputMode.JAPANESE, 0)
        composer.japaneseWords = JapaneseWords.parse("sushi|寿司|5000")
        for (key in "sushi") assertTrue(composer.type(key.code).handled)
        assertEquals("寿司", composer.candidates[0].text)
        composer.highlighted = 2
        val previousSelection = composer.candidates[composer.highlighted]
        composer.symbols = SymbolAssociations.parse("""{"ja":{"寿司":[["🍣","🍱"]]}}""") { if (it == "🍱") null else it }
        assertEquals(previousSelection, composer.candidates[composer.highlighted])
        assertEquals("🍣", composer.candidates[1].text)
        assertTrue(composer.candidates.none { it.text == "🍱" })
        assertEquals("🍣", composer.choose(1).commit)
        assertTrue(!composer.composing)
        assertTrue(composer.candidates.isEmpty())
        composer.symbolsAllowed = false
        for (key in "sushi") assertTrue(composer.type(key.code).handled)
        assertTrue(composer.candidates.none { it.kind == CandidateKind.EMOJI })
    }

    @Test
    fun romajiMatchesTheMacAndIosTable() {
        assertEquals("すし", Romaji.reading("sushi"))
        assertEquals("こんにちは", Romaji.reading("konnichiha"))
        assertEquals("きって", Romaji.reading("kitte"))
        assertEquals("しんぶん", Romaji.reading("shinbun"))
        assertEquals("んあ", Romaji.reading("n'a"))
        assertEquals("らーめん", Romaji.reading("ra-men"))
        assertEquals("しゃしん", Romaji.reading("syasinn"))
        assertEquals("スシ", Romaji.katakana("すし"))
        assertTrue(Romaji.isKana("ラーメン"))
    }

    @Test
    fun romajiLeavesASingleFinalNPendingWhileEditing() {
        val composer = Composer()
        composer.attach(InputMode.JAPANESE, 0)
        for (key in "mon") assertTrue(composer.type(key.code).handled)
        assertEquals("もn", composer.preedit)
        assertTrue(composer.candidates.any { it.text == "もん" })
        composer.type('n'.code)
        assertEquals("もん", composer.preedit)
        composer.backspace()
        assertEquals("もn", composer.preedit)
        composer.type('a'.code)
        assertEquals("もな", composer.preedit)
        assertEquals("もんだい", Romaji.reading("mondai", editing = true))
        assertEquals("もんだい", Romaji.reading("monndai", editing = true))
        assertEquals("こんにちは", Romaji.reading("konnichiha", editing = true))
        assertEquals("こんにちは", Romaji.reading("konnnichiha", editing = true))
    }

    @Test
    fun romajiOffersSmallKanaWithXAndLAliases() {
        assertEquals("ぁぃぅぇぉゃゅょっっゎ", Romaji.reading("xaxixuxexoxyaxyuxyoxtsuxtuxwa", editing = true))
        assertEquals("ぁぃぅぇぉゃゅょっっゎ", Romaji.reading("lalilulelolyalyulyoltsultulwa", editing = true))
        assertEquals("キャッ", Romaji.katakana(Romaji.reading("kixyaxtsu")))
        val composer = Composer()
        composer.attach(InputMode.JAPANESE, 0)
        for (key in "xaxyuxtsu") assertTrue(composer.type(key.code).handled)
        assertEquals("ぁゅっ", composer.preedit)
        assertEquals("ぁゅっ", composer.chooseHighlighted(confirm = true).commit)
    }

    @Test
    fun japaneseWordListKeepsTheStrongestFirst() {
        // A column after the weight is ignored; a line without a weight is skipped.
        val words = JapaneseWords.parse("# test\nsushi|寿司|9000\nsushi,susi|すし|7000\nneko|猫|8000|noun\nbad line\ninu|犬\n")
        assertEquals("寿司", words.word("sushi"))
        assertEquals(listOf("寿司", "すし"), words.words("sushi"))
        assertEquals("すし", words.word("susi"))
        assertEquals("猫", words.word("neko"))
        assertNull(words.word("inu"))
        assertEquals(3, words.best().size)
    }

    @Test
    fun sushiKeepsChineseFirstAndJapaneseInView() {
        val chinese = listOf(
            CoreCandidate("蘇軾", 0, 5, exact = true),
            CoreCandidate("速食", 1, 5, exact = true),
            CoreCandidate("素食", 2, 5, exact = true),
            CoreCandidate("蘇", 3, 2, exact = true),
        )
        val list = CandidateMerge.pinyin("sushi", "sushi", chinese, "寿司", null, null, "すし")
        assertEquals("蘇軾", list[0].text)
        assertEquals("寿司", list[2].text)
        assertEquals(CandidateKind.JAPANESE_WORD, list[2].kind)
        val collapsed = CandidateMerge.collapsed(list).map { list[it] }
        assertEquals(1, collapsed.count { it.japanese })
        assertTrue(list.any { it.text == "すし" } && list.any { it.text == "スシ" })
        assertTrue(list.any { it.kind == CandidateKind.LITERAL && it.text == "sushi" })
    }

    @Test
    fun otherJapaneseWordsWaitInThePanel() {
        val chinese = listOf(CoreCandidate("蘇軾", 0, 5, exact = true), CoreCandidate("速食", 1, 5, exact = true))
        val list = CandidateMerge.pinyin("sushi", "sushi", chinese, "寿司", null, null, "すし", listOf("すし", "鮨", "寿し"))
        // Every mimo-jp word for the reading can be chosen, once each.
        for (word in listOf("寿司", "すし", "鮨", "寿し")) assertEquals(word, 1, list.count { it.text == word })
        assertTrue(list.first { it.text == "鮨" }.japanese)
        assertTrue(list.indexOfFirst { it.text == "寿司" } < list.indexOfFirst { it.text == "鮨" })
        // The collapsed bar still shows one Japanese candidate, the strongest.
        val collapsed = CandidateMerge.collapsed(list).map { list[it] }
        assertEquals(listOf("寿司"), collapsed.filter { it.japanese }.map { it.text })
    }

    @Test
    fun aCompleteMixedReadingLeadsWhenChineseOnlyGuesses() {
        val chinese = listOf(CoreCandidate("挖他是哇", 0, 9, exact = false), CoreCandidate("哇", 1, 2, exact = true))
        val list = CandidateMerge.pinyin("watashiwa", "watashiwa", chinese, null, "私は", null, "わたしわ")
        assertEquals("私は", list[0].text)
        assertEquals(CandidateKind.MIXED, list[0].kind)
    }

    @Test
    fun singleLettersKeepTheLetterNearTheEnd() {
        val chinese = (0 until 10).map { CoreCandidate("字$it", it, 1, exact = true) }
        val list = CandidateMerge.pinyin("d", "d", chinese, null, null, null, null)
        assertEquals(CandidateKind.LITERAL, list[6].kind)
        assertEquals("d", list[6].text)
    }

    @Test
    fun englishWordsOfferTheLettersSecond() {
        val chinese = listOf(CoreCandidate("和", 0, 2, exact = true))
        val list = CandidateMerge.pinyin("hello", "Hello", chinese, null, null, null, null)
        assertEquals("Hello", list[1].text)
    }

    @Test
    fun cloudAndWaveOutlinesStayInTheirBox() {
        val w = 360f
        val h = 48f
        var previous = GlassOutline.cloudTop(h / 2, w, h)
        var x = h / 2
        while (x < w - h / 2) {
            val y = GlassOutline.cloudTop(x, w, h)
            assertTrue("cloud top at $x", y in -0.01f..(0.16f * h + 0.01f))
            assertTrue("no cusp at $x", abs(y - previous) < 1.5f)
            previous = y
            x += 0.5f
        }
        val radius = h / 2 - 0.065f * h
        assertEquals(0f, GlassOutline.waveOffset(radius, w, h), 0.001f)
        assertEquals(0f, GlassOutline.waveOffset(w - radius, w, h), 0.001f)
        for (i in 0..100) assertTrue(abs(GlassOutline.waveOffset(radius + (w - 2 * radius) * i / 100, w, h)) <= 0.065f * h + 0.001f)
        assertEquals(0.16f * h, GlassOutline.contentBand(GlassShape.CLOUD, h).first, 0.001f)
    }

    @Test
    fun insetWaveStrokesKeepTheOutlinesWaveCount() {
        // 425 dp rounds to three waves; the box inset by 4 dp alone would round to two.
        val box = Box(0f, 0f, 425f, 46f)
        fun topEdgeCrossings(inset: Float): Int {
            val ys = mutableListOf<Float>()
            GlassOutline.trace(GlassShape.WAVE, box, inset, object : PathSink {
                var onTop = true
                override fun moveTo(x: Float, y: Float) { ys.add(y) }
                override fun lineTo(x: Float, y: Float) { if (onTop) ys.add(y) }
                override fun arc(cx: Float, cy: Float, radius: Float, startDegrees: Float, sweepDegrees: Float) { onTop = false }
                override fun close() {}
            })
            val mid = 0.065f * box.height + inset
            val signs = ys.map { it - mid }.filter { abs(it) > 0.05f }.map { it > 0 }
            return signs.zipWithNext().count { (a, b) -> a != b }
        }
        assertEquals(3, GlassOutline.waveCount(425f))
        assertEquals(topEdgeCrossings(0f), topEdgeCrossings(4f))
        assertEquals(5, topEdgeCrossings(4f))
    }

    @Test
    fun keySetsFollowTheModes() {
        fun ctx(mode: InputMode, english: Boolean, shift: ShiftState = ShiftState.OFF, page: Int = 0) =
            KeyContext(mode, english, page, shift, false, false, "空格", "換行", false, true)
        val pinyin = KeyBuilder.build(ctx(InputMode.PINYIN, false))
        assertEquals(4, pinyin.rows.size)
        assertTrue(pinyin.rows.flatten().none { it.alternates.isNotEmpty() })
        val english = KeyBuilder.build(ctx(InputMode.PINYIN, true, ShiftState.ONCE))
        assertEquals("Q", english.rows[0][0].label)
        assertTrue(english.rows[0][2].alternates.contains("É"))
        // Shift shows capitals in Pinyin too; accents stay an English thing.
        val pinyinShift = KeyBuilder.build(ctx(InputMode.PINYIN, false, ShiftState.ONCE))
        assertEquals("Q", pinyinShift.rows[0][0].label)
        assertEquals(KeyAction.Engine('q'.code), pinyinShift.rows[0][0].action)
        assertTrue(pinyinShift.rows.flatten().none { it.alternates.isNotEmpty() })
        val zhuyin = KeyBuilder.build(ctx(InputMode.ZHUYIN, false))
        assertEquals(5, zhuyin.rows.size)
        assertEquals(11, zhuyin.referenceColumns)
        assertEquals(KeyAction.Engine('1'.code), zhuyin.rows[0][0].action)
        val symbols = KeyBuilder.build(ctx(InputMode.PINYIN, false, page = 1))
        assertTrue(symbols.rows[2].any { it.label == "。" })
        assertEquals("拼音", symbols.rows[3][0].label)
        assertEquals(1, symbols.page)
        assertEquals(0, pinyin.page)
        // An active Shift turns white; characters and space roll over, Return does not.
        assertEquals(KeyStyle.UTILITY, pinyin.rows[2][0].style)
        assertEquals(KeyStyle.CHARACTER, english.rows[2][0].style)
        assertTrue(pinyin.rows[0][0].rollsOver && pinyin.rows[3].first { it.action == KeyAction.Space }.rollsOver)
        assertTrue(!pinyin.rows[3].last().rollsOver && !pinyin.rows[2][0].rollsOver)
    }

    @Test
    fun punctuationHoldsGroupScriptsWithoutChangingTheirPrimaryMark() {
        for ((mark, related) in listOf(
            "，" to listOf(",", "、"), "。" to listOf(".", "…"), "？" to listOf("?", "！"),
            "「" to listOf("“", "『"), "」" to listOf("”", "』"), "\"" to listOf("“", "”", "「", "」", "『", "』"),
            "／" to listOf("/", "\\", "＼"), "）" to listOf(")", "】", "｝"),
        )) {
            val options = KeyBuilder.punctuationAlternates(mark)
            assertEquals(mark, options.first())
            assertTrue(options.containsAll(related))
            assertEquals(options.size, options.toSet().size)
        }
        for ((mode, english, simplified) in listOf(
            Triple(InputMode.ZHUYIN, false, false), Triple(InputMode.PINYIN, false, true),
            Triple(InputMode.JAPANESE, false, false), Triple(InputMode.PINYIN, true, false),
        )) {
            val keys = KeyBuilder.build(KeyContext(mode, english, 1, ShiftState.OFF, simplified,
                false, "空格", "換行", false, true)).rows.flatten()
            val slash = keys.single { it.label == "/" }
            assertEquals(KeyAction.Text("/"), slash.action)
            assertTrue("／" in slash.alternates)
        }
    }

    @Test
    fun shiftOnceThenLockOnAQuickSecondTap() {
        val caps = Capitalization()
        caps.tap(1000)
        assertEquals(ShiftState.ONCE, caps.state)
        caps.letterTyped()
        assertEquals(ShiftState.OFF, caps.state)
        // Two taps within 0.3 s lock; a third tap unlocks.
        caps.tap(2000)
        caps.tap(2250)
        assertEquals(ShiftState.LOCKED, caps.state)
        caps.letterTyped()
        assertEquals(ShiftState.LOCKED, caps.state)
        caps.tap(4000)
        assertEquals(ShiftState.OFF, caps.state)
        // Two slow taps turn it on and off again.
        caps.tap(5000)
        caps.tap(5400)
        assertEquals(ShiftState.OFF, caps.state)
        // A letter between two taps: the second tap is a new first tap.
        caps.tap(6000)
        caps.letterTyped()
        caps.tap(6200)
        assertEquals(ShiftState.ONCE, caps.state)
    }

    @Test
    fun sentenceCapitalsDoNotUndoATappedShift() {
        val caps = Capitalization()
        // The start of a sentence offers a capital; typing a letter uses it up.
        caps.applyAutomatic(true)
        assertEquals(ShiftState.ONCE, caps.state)
        caps.letterTyped()
        caps.applyAutomatic(false)
        assertEquals(ShiftState.OFF, caps.state)
        // A tapped Shift stays when the editor reports the middle of a sentence.
        caps.tap(1000)
        caps.applyAutomatic(false)
        assertEquals(ShiftState.ONCE, caps.state)
        caps.letterTyped()
        // Turned off by hand at the start of a sentence, it is not offered again...
        caps.applyAutomatic(true)
        caps.tap(3000)
        assertEquals(ShiftState.OFF, caps.state)
        caps.applyAutomatic(true)
        assertEquals(ShiftState.OFF, caps.state)
        // ...and a quick second tap locks, as on Apple's keyboard.
        caps.tap(3200)
        assertEquals(ShiftState.LOCKED, caps.state)
        caps.applyAutomatic(false)
        assertEquals(ShiftState.LOCKED, caps.state)
        caps.reset()
        assertEquals(ShiftState.OFF, caps.state)
    }

    @Test
    fun heldDeleteGoesFromCharactersToWords() {
        for (count in 1 until DeleteRepeat.WORD_THRESHOLD) assertEquals(DeleteRepeat.Step.CHARACTER, DeleteRepeat.step(count))
        assertEquals(DeleteRepeat.Step.WORD, DeleteRepeat.step(26))
        assertEquals(DeleteRepeat.Step.WAIT, DeleteRepeat.step(27))
        assertEquals(DeleteRepeat.Step.WAIT, DeleteRepeat.step(28))
        assertEquals(DeleteRepeat.Step.WORD, DeleteRepeat.step(29))
        // Words start about 2.5 s after the key went down.
        val start = DeleteRepeat.INITIAL_DELAY_MS + (DeleteRepeat.WORD_THRESHOLD - 1) * DeleteRepeat.INTERVAL_MS
        assertTrue(start in 2400..2600)
    }

    @Test
    fun fivePetsSwitchThenRest() {
        val a = CatAffection()
        var t = 1000L
        repeat(4) { assertTrue(a.pet(t) is CatAffection.Outcome.Pleased); t += 300 }
        assertEquals(CatAffection.Outcome.Happy, a.pet(t))
        assertEquals(CatAffection.Outcome.Resting, a.pet(t + 500))
        assertEquals(CatAffection.Outcome.Pleased(1), a.pet(t + 1300))
        // A long pause starts over.
        assertEquals(CatAffection.Outcome.Pleased(1), a.pet(t + 5000))
    }

    @Test
    fun modeCycleAndInterruptedPetStreak() {
        val affection = CatAffection()
        repeat(4) { affection.pet(it * 200L) }
        affection.resetStreak()
        assertEquals(CatAffection.Outcome.Pleased(1), affection.pet(800L))
        val cycle = generateSequence(InputMode.ZHUYIN) { it.next() }.take(4).toList()
        assertEquals(listOf(InputMode.ZHUYIN, InputMode.PINYIN, InputMode.JAPANESE, InputMode.ZHUYIN), cycle)
        assertEquals(InputMode.JAPANESE, InputMode.NINE_KEY.next())
    }

    // The same cases as iOS/tests/FestivalsTests.swift and Windows' layout_test.
    @Test
    fun festivalsManifest() {
        assertTrue(Festivals.parse("""{"version":1,"outfits":[]}""").themes.isEmpty())
        assertTrue(Festivals.parse("not json").themes.isEmpty())
        val fixture = Festivals.parse(javaClass.classLoader!!.getResource("festivals/fixture.json").readText())
        assertEquals(listOf("alpha", "beta", "gamma", "delta"), fixture.themes.map { it.id })
        val alpha = fixture.themes[0]
        assertEquals(FestivalInsets(0f, 70f, 0f, 70f), alpha.trim.caps)
        assertEquals(90f, alpha.trim.safeText.left)
        assertEquals("alpha_momo", alpha.momo.image)
        assertEquals("アルファ", alpha.ja)
        assertEquals(listOf(FestivalDays(LocalDate.of(2027, 2, 5), LocalDate.of(2027, 2, 6), LocalDate.of(2027, 2, 12))), alpha.days)
        assertEquals(listOf("#AA0000", "#990000"), alpha.colors.dark)

        fun worn(day: String, wardrobe: FestivalWardrobe = FestivalWardrobe()) =
            Festivals.theme(LocalDate.parse(day), fixture, wardrobe)?.id ?: "-"
        val days = listOf("2027-01-28", "2027-01-29", "2027-02-02", "2027-02-06", "2027-02-07", "2027-02-09", "2027-02-10",
            "2027-02-12", "2027-02-13", "2027-02-14", "2027-02-15")
        assertEquals(listOf("-", "alpha", "alpha", "alpha", "alpha", "delta", "alpha", "alpha", "beta", "gamma", "-"), days.map { worn(it) })
        assertEquals("gamma", worn("2027-02-13", FestivalWardrobe(skipped = setOf("beta"))))
        assertEquals("gamma", worn("2027-02-14", FestivalWardrobe(skipped = setOf("beta"))))
        assertEquals("delta", worn("2027-02-06", FestivalWardrobe(skipped = setOf("alpha"))))
        assertEquals("-", worn("2027-02-06", FestivalWardrobe(on = false)))
        fun met(since: String, today: String) = Festivals.met(LocalDate.parse(since), LocalDate.parse(today), fixture).map { it.id }
        assertEquals(listOf("alpha"), met("2027-02-01", "2027-02-01"))
        assertEquals(listOf("alpha", "beta", "gamma", "delta"), met("2027-02-01", "2027-02-07"))
        assertEquals(listOf("beta", "gamma"), met("2027-02-13", "2027-02-13"))
        assertEquals(listOf("beta"), met("2027-03-01", "2028-02-07"))
    }

    /** Every day of 2026–2030 against the file the Swift tests write, with all themes on and with four off. */
    @Test
    fun festivalsEveryDay() {
        val shipped = Festivals.parse(java.io.File("src/main/assets/festivals/festivals.json").readText())
        assertEquals(24, shipped.themes.size)
        val lines = javaClass.classLoader!!.getResource("festivals/worn-2026-2030.txt").readText().lines()
        val skipped = lines[0].substringAfter(": ").split(" ").toSet()
        assertEquals(4, skipped.size)
        var compared = 0
        for (line in lines.drop(1).filter { it.isNotEmpty() }) {
            val (day, all, some) = line.split(" ")
            val date = LocalDate.parse(day)
            assertEquals(line, all, Festivals.theme(date, shipped, FestivalWardrobe())?.id ?: "-")
            assertEquals(line, some, Festivals.theme(date, shipped, FestivalWardrobe(skipped = skipped))?.id ?: "-")
            compared++
        }
        assertEquals(1826, compared)
        // Each theme's dressed cats sit at the ends and its cluster fits the right end, on the three shapes.
        for (theme in shipped.themes) {
            for (shape in com.krysta1ll.meowpuut.android.ime.GlassShape.values()) {
                val layout = com.krysta1ll.meowpuut.android.ime.CandidateRegionLayout(393f, com.krysta1ll.meowpuut.android.ime.Idiom.PHONE,
                    rim = com.krysta1ll.meowpuut.android.ime.GlassOutline.contentBand(shape, 1f).first)
                val stage = layout.stage(shape)
                val places = com.krysta1ll.meowpuut.android.ime.CatSeats.ends(stage, theme.mimimo.catSprite()!!, theme.momo.catSprite()!!)
                assertTrue("${theme.id} $shape cats", places != null)
                val fitted = checkNotNull(stage.fitting(checkNotNull(places), theme.right))
                val box = Festivals.placeRight(theme.right!!, layout.glass, com.krysta1ll.meowpuut.android.ime.GlassOutline.contentBand(shape, layout.glass.height),
                    layout.width, fitted.arrow.right - 1f)
                assertTrue("${theme.id} $shape ornament", box != null && box.right <= layout.width && box.left > fitted.arrow.centerX)
            }
        }
        // 光明節 2026 is met once its lead-up week begins.
        assertTrue(Festivals.met(LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 27), shipped).isEmpty())
        assertEquals(listOf("hanukkah"), Festivals.met(LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 28), shipped).map { it.id })
    }

    @Test
    fun festivalPillColour() {
        assertEquals(0xFFC8463C.toInt(), Look.parseColor("#C8463C"))
        assertNull(Look.parseColor("C8463C"))
        assertNull(Look.parseColor("#12345"))
        val look = Look(false, GlassShape.CAPSULE, com.krysta1ll.meowpuut.android.ime.Palette.ROSE,
            com.krysta1ll.meowpuut.android.ime.GlassStrength.BALANCED, true, pillOverride = 0xFFC8463C.toInt())
        assertEquals(0xFFC8463C.toInt(), look.pill.second)
    }

    @Test
    fun candidateFlags() {
        assertTrue(Candidate("寿司", CandidateKind.JAPANESE_WORD).japanese)
        assertTrue(!Candidate("你好", CandidateKind.CHINESE).japanese)
        assertTrue(!Candidate("hello", CandidateKind.LITERAL).japanese)
    }
}
