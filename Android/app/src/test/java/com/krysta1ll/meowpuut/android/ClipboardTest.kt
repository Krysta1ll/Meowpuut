package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.engine.InputMode
import com.krysta1ll.meowpuut.android.ime.ClipboardClipExtractor
import com.krysta1ll.meowpuut.android.ime.ClipboardRules
import com.krysta1ll.meowpuut.android.ime.ClipboardShelf
import com.krysta1ll.meowpuut.android.ime.ClipboardSlip
import com.krysta1ll.meowpuut.android.ime.KeyStrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The clipboard shelf and slip, and the items of the language key's strip. */
class ClipboardTest {
    @get:Rule val folder = TemporaryFolder()

    private var clock = 1_000_000L
    private fun shelf(file: File? = null) = ClipboardShelf(file) { clock }
    private val hour = ClipboardShelf.LIFETIME_MS

    // ---- the shelf

    @Test
    fun unpinnedItemsAreNewestFirstAndGoAfterAnHour() {
        val s = shelf()
        s.add("one"); clock += 1000; s.add("two")
        assertEquals(listOf("two", "one"), s.items().map { it.text })
        clock += hour - 1000
        assertEquals(listOf("two"), s.items().map { it.text })      // "one" is exactly an hour old
        clock += 2000
        assertTrue(s.isEmpty)
    }

    @Test
    fun theSameTextAgainMovesToTheTopAndTheShelfKeepsTwenty() {
        val s = shelf()
        s.add("a"); clock++; s.add("b"); clock++; s.add("a")
        assertEquals(listOf("a", "b"), s.items().map { it.text })
        for (i in 1..30) { clock++; s.add("item $i") }
        assertEquals(ClipboardShelf.MAX_ITEMS, s.items().size)
        assertEquals("item 30", s.items().first().text)
    }

    @Test
    fun sensitiveAndEmptyTextIsNeverKeptAndLongTextIsCut() {
        val s = shelf()
        assertFalse(s.add("hunter2", sensitive = true))
        assertFalse(s.add("   "))
        assertTrue(s.isEmpty)
        s.add("x".repeat(ClipboardShelf.MAX_CHARS + 500))
        assertEquals(ClipboardShelf.MAX_CHARS, s.items().single().text.length)
    }

    @Test
    fun pinnedItemsStayFirstNeverExpireAndSurviveARestartOnTheirOwn() {
        val file = File(folder.newFolder(), ClipboardShelf.FILE_NAME)
        val s = shelf(file)
        s.add("loose"); clock++; s.add("keep\nthis\t\"one\" 😀"); clock++; s.add("newest")
        s.pin("keep\nthis\t\"one\" 😀")
        assertEquals(listOf("keep\nthis\t\"one\" 😀", "newest", "loose"), s.items().map { it.text })
        clock += 5 * hour
        assertEquals(listOf("keep\nthis\t\"one\" 😀"), s.items().map { it.text })
        // Only pinned items are on disk: a new shelf from the same file has just that one.
        val again = shelf(file)
        assertEquals(listOf("keep\nthis\t\"one\" 😀"), again.items().map { it.text })
        assertTrue(again.items().single().pinned)
        again.unpin("keep\nthis\t\"one\" 😀")
        assertFalse(file.exists())
    }

    @Test
    fun deletingClearingAndAMissingOrDamagedFileAreHarmless() {
        val file = File(folder.newFolder(), ClipboardShelf.FILE_NAME)
        val s = shelf(file)
        s.add("a"); s.pin("a"); s.add("b")
        s.remove("a")
        assertEquals(listOf("b"), s.items().map { it.text })
        assertFalse(file.exists())
        s.add("c"); s.pin("c")
        s.clear()
        assertTrue(s.isEmpty)
        assertFalse(file.exists())
        file.writeText("12 999999\nnot a shelf")
        assertTrue(shelf(file).isEmpty)
    }

    // ---- the slip

    private fun slipAt(time: Long, sensitive: Boolean = false) = ClipboardSlip().also { it.copied("copied text", time, sensitive) }

    @Test
    fun aSlipIsOfferedForThreeMinutes() {
        val slip = slipAt(clock)
        slip.present(clock + 60_000, fieldAllowed = true)
        assertNotNull(slip.visible(clock + 60_000, composing = false, fieldAllowed = true))
        assertNotNull(slip.visible(clock + ClipboardSlip.WINDOW_MS, composing = false, fieldAllowed = true))
        assertNull(slip.visible(clock + ClipboardSlip.WINDOW_MS + 1, composing = false, fieldAllowed = true))
        val old = slipAt(clock)
        old.present(clock + ClipboardSlip.WINDOW_MS + 1, fieldAllowed = true)
        assertFalse(old.isPresented)
    }

    @Test
    fun aSlipIsOfferedOnceAndNotWhileComposingOrInTheWrongField() {
        val slip = slipAt(clock)
        slip.present(clock, fieldAllowed = false)
        assertNull("a password field never gets it", slip.visible(clock, composing = false, fieldAllowed = false))
        slip.present(clock, fieldAllowed = true)
        assertNull("not while a reading is being typed", slip.visible(clock, composing = true, fieldAllowed = true))
        assertNotNull(slip.visible(clock, composing = false, fieldAllowed = true))
        slip.dismiss()                                    // tapped, typed over, or the field was left
        assertNull(slip.visible(clock, composing = false, fieldAllowed = true))
        slip.present(clock, fieldAllowed = true)
        assertNull("it does not come back", slip.visible(clock, composing = false, fieldAllowed = true))
        assertFalse("the same clip read again is not new", slip.copied("copied text", clock, false))
        assertTrue(slip.copied("copied text", clock + 5, false))
    }

    @Test
    fun aSensitiveSlipShowsDotsButKeepsTheText() {
        val slip = slipAt(clock, sensitive = true)
        slip.present(clock, fieldAllowed = true)
        val clip = slip.visible(clock, composing = false, fieldAllowed = true)!!
        assertEquals(ClipboardSlip.MASK, clip.shown)
        assertEquals("copied text", clip.text)
    }

    @Test
    fun passwordAndNoLearningFieldsAreLeftOut() {
        val text = 0x1; val number = 0x2
        assertTrue(ClipboardRules.fieldAllowed(text, 0))
        assertTrue(ClipboardRules.fieldAllowed(text or 0x20 /* email */, 0))
        assertFalse(ClipboardRules.fieldAllowed(text or 0x80, 0))
        assertFalse(ClipboardRules.fieldAllowed(text or 0x90, 0))
        assertFalse(ClipboardRules.fieldAllowed(text or 0xe0, 0))
        assertFalse(ClipboardRules.fieldAllowed(number or 0x10, 0))
        assertTrue(ClipboardRules.fieldAllowed(number, 0))
        assertFalse(ClipboardRules.fieldAllowed(text, 0x1000000))
    }

    // ---- the strip

    @Test
    fun theStripStartsOnTheCurrentModeThenTheRestOfTheRotationThenScriptAndHide() {
        val e = KeyStrip.entries(InputMode.PINYIN, null, simplified = false, numericField = false)
        assertEquals(listOf("拼音", "日語", "注音", "繁→简", KeyStrip.HIDE_ICON), e.map { it.label })
        assertEquals(KeyStrip.Action.Stay, e.first().action)
        assertEquals(KeyStrip.Action.Mode(InputMode.JAPANESE), e[1].action)
        assertEquals(KeyStrip.Action.Script, e[3].action)
        assertEquals(KeyStrip.Action.Hide, e.last().action)
    }

    @Test
    fun theStripFollowsTheTickedRotationAndTheScriptReadsBothWays() {
        val all = KeyStrip.entries(InputMode.ZHUYIN, setOf(InputMode.ZHUYIN, InputMode.NINE_KEY, InputMode.JAPANESE, InputMode.PINYIN), true, false)
        assertEquals(listOf("注音", "拼音", "九键", "日语", "简→繁", KeyStrip.HIDE_ICON), all.map { it.label })
        val outside = KeyStrip.entries(InputMode.NINE_KEY, setOf(InputMode.ZHUYIN, InputMode.JAPANESE), false, false)
        assertEquals(listOf("九鍵", "注音", "日語"), outside.take(3).map { it.label })
    }

    @Test
    fun noModeItemsWhereTheCatsWouldNotSwitchEither() {
        val numeric = KeyStrip.entries(InputMode.ZHUYIN, null, false, numericField = true)
        assertEquals(listOf("注音", "繁→简", KeyStrip.HIDE_ICON), numeric.map { it.label })
        val off = KeyStrip.entries(InputMode.ZHUYIN, setOf(InputMode.ZHUYIN), false, false)
        assertTrue(off.none { it.action is KeyStrip.Action.Mode })
    }

    // ---- sync, unpin, and ClipData extraction

    @Test
    fun unpinningAnOldPinnedItemGivesItAFreshHour() {
        val s = shelf()
        s.add("pinned earlier")
        s.pin("pinned earlier")
        clock += 5 * hour
        s.unpin("pinned earlier")
        assertEquals(listOf("pinned earlier"), s.items().map { it.text })
        clock += hour - 1000
        assertEquals(listOf("pinned earlier"), s.items().map { it.text })
        clock += 2000
        assertTrue(s.isEmpty)
    }

    @Test
    fun ingestSyncsExternalZeroTimestampClipToShelfWithoutStaleSlip() {
        val s = shelf()
        val slip = ClipboardSlip()
        val clip = ClipboardShelf.ExtractedClip("copied outside", timestamp = 0L, sensitive = false, fromListener = false)
        assertTrue(s.ingest(clip, slip, at = clock))
        slip.present(clock, fieldAllowed = true)
        assertNull("unknown-age external clip must not flash a stale slip", slip.visible(clock, composing = false, fieldAllowed = true))
        assertEquals(listOf("copied outside"), s.items().map { it.text })
        assertFalse("re-syncing the same clip after reopen is a no-op", s.ingest(clip, slip, at = clock + 1000))
        s.remove("copied outside")
        assertFalse("deleting the clip stays deleted on next window show", s.ingest(clip, slip, at = clock + 2000))
    }

    @Test
    fun ingestHandlesRecentExternalSyncConsecutiveCopiesAndSensitiveClips() {
        val s = shelf()
        val slip = ClipboardSlip()
        // External copy 10s before IME opens: both shelf and slip receive it.
        assertTrue(s.ingest(ClipboardShelf.ExtractedClip("first", clock - 10_000, sensitive = false, fromListener = false), slip, at = clock))
        slip.present(clock, fieldAllowed = true)
        assertEquals("first", slip.visible(clock, composing = false, fieldAllowed = true)?.text)
        // Consecutive copy via listener with zero timestamp: still offers slip and moves to top.
        clock += 1000
        assertTrue(s.ingest(ClipboardShelf.ExtractedClip("second", timestamp = 0L, sensitive = false, fromListener = true), slip, at = clock))
        slip.present(clock, fieldAllowed = true)
        assertEquals("second", slip.visible(clock, composing = false, fieldAllowed = true)?.text)
        assertEquals(listOf("second", "first"), s.items().map { it.text })
        // Sensitive clip: masked slip only, never persisted to shelf.
        clock += 1000
        assertTrue(s.ingest(ClipboardShelf.ExtractedClip("otp-1234", clock, sensitive = true, fromListener = true), slip, at = clock))
        slip.present(clock, fieldAllowed = true)
        assertEquals(ClipboardSlip.MASK, slip.visible(clock, composing = false, fieldAllowed = true)?.shown)
        assertEquals(listOf("second", "first"), s.items().map { it.text })
    }

    @Test
    fun clipExtractorHandlesHtmlMultiItemNonTextMimeAndSecurityExceptions() {
        val html = ClipboardClipExtractor.extract(
            hasTextMime = true,
            listOf(ClipboardClipExtractor.ItemSource(plainText = null, htmlText = "<b>hello</b>", coerceToText = { "hello" })),
        )
        assertEquals("hello", html)

        val nonTextMime = ClipboardClipExtractor.extract(
            hasTextMime = false,
            listOf(ClipboardClipExtractor.ItemSource(plainText = "from custom mime", coerceToText = { "from custom mime" })),
        )
        assertEquals("from custom mime", nonTextMime)

        val multi = ClipboardClipExtractor.extract(
            hasTextMime = true,
            listOf(
                ClipboardClipExtractor.ItemSource(uriScheme = "content", coerceToText = { throw SecurityException("denied") }),
                ClipboardClipExtractor.ItemSource(plainText = "   ", coerceToText = { "   " }),
                ClipboardClipExtractor.ItemSource(plainText = "line A"),
                ClipboardClipExtractor.ItemSource(plainText = "line B"),
            ),
        )
        assertEquals("line A\nline B", multi)

        val contentUriOnly = ClipboardClipExtractor.extract(
            hasTextMime = true,
            listOf(ClipboardClipExtractor.ItemSource(uriScheme = "content", coerceToText = { "content://media/external/images/1" })),
        )
        assertNull(contentUriOnly)

        val webUrl = ClipboardClipExtractor.extract(
            hasTextMime = true,
            listOf(ClipboardClipExtractor.ItemSource(uriScheme = "https", coerceToText = { "https://meowpuut.app" })),
        )
        assertEquals("https://meowpuut.app", webUrl)
    }
}
