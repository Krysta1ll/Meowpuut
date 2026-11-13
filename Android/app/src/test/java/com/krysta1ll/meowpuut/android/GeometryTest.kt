package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.engine.InputMode
import com.krysta1ll.meowpuut.android.ime.Box
import com.krysta1ll.meowpuut.android.ime.CandidateRegionLayout
import com.krysta1ll.meowpuut.android.ime.KeyboardTextSize
import com.krysta1ll.meowpuut.android.ime.EffectsLevel
import com.krysta1ll.meowpuut.android.ime.GlassShape
import com.krysta1ll.meowpuut.android.ime.GlassStrength
import com.krysta1ll.meowpuut.android.ime.Idiom
import com.krysta1ll.meowpuut.android.ime.KeyBalloon
import com.krysta1ll.meowpuut.android.ime.KeyAction
import com.krysta1ll.meowpuut.android.ime.KeyBuilder
import com.krysta1ll.meowpuut.android.ime.KeyContext
import com.krysta1ll.meowpuut.android.ime.KeyGrid
import com.krysta1ll.meowpuut.android.ime.KeyMetrics
import com.krysta1ll.meowpuut.android.ime.KeyboardHeight
import com.krysta1ll.meowpuut.android.ime.Look
import com.krysta1ll.meowpuut.android.ime.Palette
import com.krysta1ll.meowpuut.android.ime.PanelAdjustment
import com.krysta1ll.meowpuut.android.ime.ShiftState
import com.krysta1ll.meowpuut.android.ime.TextUnits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryTest {
    private fun context(mode: InputMode = InputMode.PINYIN, english: Boolean = false, page: Int = 0, globe: Boolean = false) =
        KeyContext(mode, english, page, ShiftState.OFF, false, globe, "空格", "換行", false, true)

    @Test
    fun ultraAddsTransmissionWhileKeepingTheOpaqueAccessibilityFill() {
        assertEquals(EffectsLevel.STANDARD, EffectsLevel.of(null))
        assertEquals(EffectsLevel.FULL, EffectsLevel.of("full"))
        assertEquals(EffectsLevel.ULTRA, EffectsLevel.of("ultra"))
        assertTrue(EffectsLevel.ULTRA.hasFullEffects)
        for (dark in listOf(false, true)) {
            val full = Look(dark, GlassShape.WAVE, Palette.ROSE, GlassStrength.BALANCED, true, effects = EffectsLevel.FULL)
            val ultra = full.copy(effects = EffectsLevel.ULTRA)
            assertTrue(ultra.glassFill.ushr(24) < full.glassFill.ushr(24))
            assertEquals(full.copy(opaque = true).glassFill, ultra.copy(opaque = true).glassFill)
        }
    }

    @Test
    fun barFitsTheKeyboard() {
        for (width in listOf(360f, 393f, 430f, 480f)) {
            val r = CandidateRegionLayout(width, Idiom.PHONE)
            assertTrue("row starts inside the glass at $width", r.row.left > r.glass.left)
            assertTrue("expand key inside the glass", r.chevron.right <= r.glass.right)
            assertTrue("glass ends inside the keyboard at $width", r.glass.right <= width)
            assertTrue("pill fits the glass", r.pillHeight < r.glass.height)
        }
    }

    @Test
    fun candidateTouchesUseTheSpaceAboveTheKeysWithoutEnteringTheExpandButton() {
        for ((width, idiom) in listOf(320f to Idiom.PHONE, 852f to Idiom.PHONE_LANDSCAPE, 820f to Idiom.TABLET)) {
            for (cats in listOf(true, false)) {
                val region = CandidateRegionLayout(width, idiom, cats, rim = 0.16f)
                val target = region.candidateTarget(region.row)
                assertEquals(minOf(56f, region.height), target.height, 0.001f)
                assertTrue(target.top <= region.row.top && target.bottom >= region.row.bottom)
                assertTrue(target.top >= 0f && target.bottom <= region.height)
                assertTrue(!target.intersects(region.expandTarget(region.chevron)))
            }
        }
    }

    @Test
    fun keyboardTextChoicesUseExistingPreferenceKeysAndSystemFontLimit() {
        assertEquals(KeyboardTextSize.STANDARD, KeyboardTextSize.of(null))
        assertEquals(KeyboardTextSize.LARGE, KeyboardTextSize.of("large"))
        val standard = KeyboardTextSize.STANDARD.fontSize(20f, 2f)
        val large = KeyboardTextSize.LARGE.fontSize(20f, 2f)
        assertEquals(17f, KeyboardTextSize.of("small").fontSize(20f, 1f), 0.001f)
        assertEquals(23f, standard, 0.001f)
        assertEquals(27.6f, large, 0.001f)
    }

    @Test
    fun dockedPanelReservesNavigationAndFitsTheAvailableHeight() {
        val phone = Idiom.PHONE
        val normal = KeyMetrics.fitted(phone, 390f, 4, 4)
        val raised = KeyMetrics.fitted(phone, 390f, 4, 4, panelHeightDelta = 8)
        assertEquals(32f, raised.areaHeight(4) - normal.areaHeight(4), 0.01f)

        val landscape = Idiom.PHONE_LANDSCAPE
        val nav = 24f
        val available = PanelAdjustment.availableHeight(360f, landscape, nav)
        val bar = CandidateRegionLayout(800f, landscape).height
        val tall = KeyMetrics.fitted(landscape, 800f, 5, 5, panelHeightDelta = PanelAdjustment.MAX_HEIGHT,
            availableHeight = available - bar)
        val symbols = KeyMetrics.fitted(landscape, 800f, 4, 5, panelHeightDelta = PanelAdjustment.MAX_HEIGHT,
            availableHeight = available - bar)
        val content = bar + tall.areaHeight(5)
        val gap = PanelAdjustment.bottomGap(PanelAdjustment.MAX_BOTTOM_GAP, available, content)
        assertTrue(tall.keyHeight >= 26f)
        assertTrue(gap < PanelAdjustment.MAX_BOTTOM_GAP)
        assertTrue(content + gap + nav <= 360f - 80f)
        assertEquals(tall.areaHeight(5), symbols.areaHeight(4), 0.01f)
        assertEquals(0f, PanelAdjustment.bottomGap(72, 100f, 120f), 0.01f)
    }

    @Test
    fun hitAreasTileEveryRowWithoutGaps() {
        for ((set, width) in listOf(
            KeyBuilder.build(context()) to 393f,
            KeyBuilder.build(context(InputMode.ZHUYIN)) to 360f,
            KeyBuilder.build(context(page = 1, globe = true)) to 430f,
        )) {
            val metrics = KeyMetrics.of(Idiom.PHONE, width, set.rows.size)
            val rows = KeyGrid.place(set.specs, width, set.referenceColumns, metrics)
            assertEquals(set.rows.size, rows.size)
            var expectedTop = 0f
            for ((r, row) in rows.withIndex()) {
                assertEquals("row $r key count", set.rows[r].size, row.size)
                assertEquals(0f, row.first().hitFrame.left, 0.001f)
                assertEquals(width, row.last().hitFrame.right, 0.001f)
                for (i in 1 until row.size) assertEquals(row[i - 1].hitFrame.right, row[i].hitFrame.left, 0.001f)
                assertEquals("rows meet", expectedTop, row.first().hitFrame.top, 0.001f)
                expectedTop = row.first().hitFrame.bottom
                for (p in row) assertTrue("frame inside its hit area", p.frame.left >= p.hitFrame.left && p.frame.right <= p.hitFrame.right)
            }
            assertEquals(metrics.areaHeight(set.rows.size), expectedTop, 0.001f)
        }
    }

    @Test
    fun touchesInGapsAndEdgesFindAKey() {
        val set = KeyBuilder.build(context())
        val metrics = KeyMetrics.of(Idiom.PHONE, 393f, 4)
        val rows = KeyGrid.place(set.specs, 393f, 10, metrics)
        val q = rows[0][0].frame
        val w = rows[0][1].frame
        // In the gap between q and w, and between rows 1 and 2.
        assertEquals(0 to 0, KeyGrid.keyAt((q.right + w.left) / 2 - 0.1f, q.centerY, rows))
        assertEquals(0 to 1, KeyGrid.keyAt((q.right + w.left) / 2 + 0.1f, q.centerY, rows))
        assertNotNull(KeyGrid.keyAt(q.centerX, q.bottom + metrics.rowGap / 2 - 0.1f, rows))
        // Left of "a" (row 2 is indented) still types "a".
        assertEquals(1 to 0, KeyGrid.keyAt(1f, rows[1][0].frame.centerY, rows))
        assertEquals(0 to 9, KeyGrid.keyAt(392.9f, q.centerY, rows))
    }

    @Test
    fun lettersRowsMatchApplesProportions() {
        val metrics = KeyMetrics.of(Idiom.PHONE, 393f, 4)
        val rows = KeyGrid.place(KeyBuilder.build(context()).specs, 393f, 10, metrics)
        val q = rows[0][0].frame
        assertEquals(3f, q.left, 0.01f)
        assertEquals((393f - 6 - 9 * 6) / 10, q.width, 0.01f)
        // Shift and delete sit at the edges of the third row.
        assertEquals(3f, rows[2].first().frame.left, 0.01f)
        assertEquals(390f, rows[2].last().frame.right, 0.01f)
        // Space stretches between the fixed bottom keys.
        val space = rows[3][2].frame
        assertTrue(space.width > 150f)
    }

    @Test
    fun balloonsStayOnScreen() {
        for (x in listOf(0f, 3f, 180f, 352f)) {
            val key = Box(x, 100f, 33f, 42f)
            val bubble = KeyBalloon.bubble(key, 393f, Idiom.PHONE)
            assertTrue(bubble.left >= 2f && bubble.right <= 391f)
            assertEquals(key.top - 52f, bubble.top, 0.01f)
        }
    }

    @Test
    fun symbolPagesKeepTheHeightOfTheirLetters() {
        for (width in listOf(360f, 393f, 430f)) {
            // Zhuyin has five rows; its symbol pages have four.
            val zhuyin = KeyMetrics.fitted(Idiom.PHONE, width, 5, 5)
            val symbols = KeyMetrics.fitted(Idiom.PHONE, width, 4, 5)
            assertEquals(KeyMetrics.of(Idiom.PHONE, width, 5), zhuyin)
            assertEquals(zhuyin.areaHeight(5), symbols.areaHeight(4), 0.001f)
            assertTrue("taller keys fill the room", symbols.keyHeight > KeyMetrics.of(Idiom.PHONE, width, 4).keyHeight)
            // The hit areas still cover the whole height.
            val set = KeyBuilder.build(context(InputMode.ZHUYIN, page = 1))
            val rows = KeyGrid.place(set.specs, width, set.referenceColumns, symbols)
            assertEquals(symbols.areaHeight(4), rows.last().first().hitFrame.bottom, 0.001f)
            // Letters keep their own height.
            assertEquals(KeyMetrics.of(Idiom.PHONE, width, 4), KeyMetrics.fitted(Idiom.PHONE, width, 4, 4))
        }
    }

    @Test
    fun emojiEntryLivesOnBothSymbolPages() {
        for (mode in InputMode.entries) {
            val letters = KeyBuilder.build(context(mode))
            assertTrue(letters.rows.flatten().none { it.action == KeyAction.Emoji })
            for (page in 1..2) {
                val symbols = KeyBuilder.build(context(mode, page = page).copy(expanded = true, emoji = true))
                assertTrue(symbols.rows.last().any { it.action == KeyAction.Emoji })
                assertTrue(symbols.rows.last().any { it.action == KeyAction.Delete })
            }
        }
    }

    @Test
    fun keyboardHeightStaysTheSameOnZhuyinSymbolPages() {
        for (height in KeyboardHeight.entries) {
            val zhuyin = KeyMetrics.fitted(Idiom.PHONE, 360f, 5, 5, height)
            val symbols = KeyMetrics.fitted(Idiom.PHONE, 360f, 4, 5, height)
            assertEquals(zhuyin.areaHeight(5), symbols.areaHeight(4), 0.01f)
            assertTrue(zhuyin.keyHeight >= 32f)
        }
        val (compact, standard, tall) = KeyboardHeight.entries.map {
            KeyMetrics.fitted(Idiom.PHONE, 360f, 4, 4, it).areaHeight(4)
        }
        assertTrue(compact < standard && standard < tall)
        val landscape = KeyMetrics.fitted(Idiom.PHONE_LANDSCAPE, 820f, 4, 4)
        assertEquals(landscape, KeyMetrics.fitted(Idiom.PHONE_LANDSCAPE, 820f, 4, 4, KeyboardHeight.TALL))
    }

    @Test
    fun alternatesStartOverTheKeyOnBothHalves() {
        val width = 393f
        val metrics = KeyMetrics.of(Idiom.PHONE, width, 4)
        val rows = KeyGrid.place(KeyBuilder.build(context(english = true)).specs, width, 10, metrics)
        // "e" on the left half, "o" on the right half, "q" and "p" at the edges.
        for ((column, count) in listOf(2 to 7, 8 to 8, 0 to 2, 9 to 2)) {
            val key = rows[0][column].frame
            val strip = KeyBalloon.alternates(key, count, width)
            assertEquals(count, strip.count)
            assertTrue("on screen", strip.box.left >= 2f && strip.box.right <= width - 2f)
            assertTrue("above the key", strip.box.bottom <= key.top)
            assertEquals("the first option is the one over the key", 0, strip.indexAt(key.centerX))
            assertEquals(column >= 5, strip.mirrored)
            // Every option has its own cell, and each cell answers for its option.
            for (i in 0 until count) assertEquals(i, strip.indexAt(strip.cellLeft(i) + strip.cell / 2))
            // Far outside the strip the nearest end is chosen.
            assertEquals(if (strip.mirrored) count - 1 else 0, strip.indexAt(-50f))
            assertEquals(if (strip.mirrored) 0 else count - 1, strip.indexAt(width + 50f))
        }
    }

    @Test
    fun aLongPunctuationStripFitsBelowTheBarOnANarrowWindow() {
        val mark = Box(100f, 6f, 34f, 42f)
        val strip = KeyBalloon.alternates(mark, 8, 240f, keepOnScreen = true)
        assertTrue(strip.box.left >= 2f && strip.box.right <= 238f)
        assertTrue(strip.box.top >= mark.bottom)
        for (i in 0 until strip.count) assertEquals(i, strip.indexAt(strip.cellLeft(i) + strip.cell / 2))
    }

    @Test
    fun deleteRemovesWholeCharactersAndWords() {
        assertEquals(1, TextUnits.lastGrapheme("abc"))
        assertEquals(1, TextUnits.lastGrapheme("你好"))
        // A character outside the BMP is two UTF-16 units.
        assertEquals(2, TextUnits.lastGrapheme("字\uD842\uDFB7"))
        assertEquals(0, TextUnits.lastGrapheme(""))
        assertEquals(5, TextUnits.wordLength("hello world"))
        // Trailing spaces go with the word before them.
        assertEquals(7, TextUnits.wordLength("hello world  "))
        assertEquals(3, TextUnits.wordLength("   "))
        assertTrue(TextUnits.wordLength("你好世界") in 1..4)
        assertEquals(1, TextUnits.wordLength("好，"))
    }

    @Test
    fun landscapeAndTabletAreShorter() {
        val portrait = CandidateRegionLayout(393f, Idiom.PHONE)
        val landscape = CandidateRegionLayout(852f, Idiom.PHONE_LANDSCAPE)
        assertTrue(landscape.height < portrait.height)
        // DESIGN.md §5: 63 high in landscape, 94 on a tablet.
        assertEquals(63f, landscape.height, 0f)
        assertEquals(94f, CandidateRegionLayout(820f, Idiom.TABLET).height, 0f)
        assertEquals(Idiom.PHONE_LANDSCAPE, Idiom.resolve(393f, 393f))
        assertEquals(Idiom.TABLET, Idiom.resolve(1180f, 820f))
        assertEquals(Idiom.PHONE, Idiom.resolve(852f, 393f))
    }

    @Test
    fun theExpandKeyFollowsTheRimOfTheShape() {
        val capsule = CandidateRegionLayout(393f, Idiom.PHONE)
        val cloud = CandidateRegionLayout(393f, Idiom.PHONE, rim = 0.16f)
        val drop = 0.16f * cloud.glass.height
        assertEquals(capsule.glass, cloud.glass)
        assertEquals(capsule.chevron.centerY + drop / 2, cloud.chevron.centerY, 0.001f)
        // The expand key stays inside the region.
        assertTrue(cloud.chevron.bottom <= cloud.height)
    }

    @Test
    fun statusFontFollowsTheIdiom() {
        assertEquals(13f, CandidateRegionLayout(393f, Idiom.PHONE).statusFontSize, 0f)
        assertEquals(12f, CandidateRegionLayout(852f, Idiom.PHONE_LANDSCAPE).statusFontSize, 0f)
        assertEquals(15f, CandidateRegionLayout(820f, Idiom.TABLET).statusFontSize, 0f)
    }
}
