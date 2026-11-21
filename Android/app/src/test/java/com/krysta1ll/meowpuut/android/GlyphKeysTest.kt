package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.ime.glyph.CandidateCap
import com.krysta1ll.meowpuut.android.ime.glyph.CapRole
import com.krysta1ll.meowpuut.android.ime.glyph.CatId
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphCatArt
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphCats
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphColors
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphKeyArt
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphKeyPainter
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphPixelType
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphPools
import com.krysta1ll.meowpuut.android.ime.glyph.KeyCap
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlyphKeysTest {
    private val c = GlyphColors.LIGHT
    private val painter = GlyphKeyPainter(BlockFont)

    private fun cap(label: String = "a", art: String? = label, role: CapRole = CapRole.PLAIN, pressed: Boolean = false, enabled: Boolean = true) =
        KeyCap(100.0, 200.0, 40.0, 54.0, label, art, role, pressed, enabled)

    private fun draw(cap: KeyCap, composing: Boolean = false, chips: Boolean = false) =
        RecordingSink().also { painter.key(it, c, cap, composing, chips) }

    // ---- the hand-cut type and its mask ----------------------------------------------------------------------

    @Test
    fun everyLetterAndDigitIsCutByHand() {
        for (ch in ('a'..'z') + ('A'..'Z') + ('0'..'9')) {
            val rows = GlyphKeyArt.bits(ch.toString())
            assertNotNull("$ch", rows)
            assertEquals("$ch is seven rows", 7, rows!!.size)
            assertTrue("$ch is five wide", rows.all { it.length == 5 })
            assertTrue(rows.any { 'X' in it })
        }
        for (name in listOf("SHIFT", "SHIFT_ON", "CAPS_LOCK", "DELETE", "GLOBE", "確認", "符", "ABC")) assertTrue(name, GlyphKeyArt.hasBits(name))
        assertFalse(GlyphKeyArt.hasBits("ㄅ"))
        assertFalse(GlyphKeyArt.hasBits(""))
    }

    @Test
    fun theMaskIsTheDrawingAndTheHaloIsOneOrThreePixelsWider() {
        val a = GlyphKeyArt.mask(GlyphKeyArt.bits("a")!!)
        assertEquals(5, a.w); assertEquals(7, a.h)
        assertTrue(a[2, 2]); assertFalse(a[0, 0]); assertFalse("outside is empty", a[-1, 0] || a[9, 9])
        val square = GlyphKeyArt.grown(a, round = false)
        assertEquals(7, square.w); assertEquals(9, square.h)
        assertEquals(1, GlyphKeyArt.reach(false))
        // every ink pixel is inside its own halo, and so is each of its eight neighbours
        for (y in 0 until a.h) for (x in 0 until a.w) if (a[x, y]) for (dy in -1..1) for (dx in -1..1) assertTrue(square[x + 1 + dx, y + 1 + dy])
        val round = GlyphKeyArt.grown(a, round = true)
        assertEquals(11, round.w); assertEquals(13, round.h)
        assertEquals(3, GlyphKeyArt.reach(true))
        for (y in 0 until a.h) for (x in 0 until a.w) if (a[x, y]) assertTrue(round[x + 3, y + 3])
        assertTrue("the round halo is bigger than the square one", round.on.count { it } > square.on.count { it })
        // the all-ink mask grows to a rounded block, not a square: its corner pixel stays out
        val dot = GlyphKeyArt.grown(GlyphKeyArt.Mask(1, 1, booleanArrayOf(true)), round = true)
        assertTrue(dot[3, 3]); assertFalse(dot[0, 0])
    }

    @Test
    fun theSteppedBlockIsThreeRectanglesWithTheAreaOfItsOutline() {
        val sink = RecordingSink()
        GlyphKeyPainter.stepped(sink, 10.0, 20.0, 40.0, 30.0, 2.0, 7)
        assertEquals(3, sink.rects.size)
        // the union of the three: the middle sheet, and the strips that step in at either side
        val union = 32.0 * 30 + 2 * 2 * 26 + 2 * 2 * 22
        assertEquals(40.0 * 30 - 12 * 4, union, 1e-9)           // a rectangle less four corners of three steps
        val (a, b, c) = sink.rects
        assertEquals(union, a.w * a.h + 2 * (b.w - a.w) / 2 * b.h + 2 * (c.w - b.w) / 2 * c.h, 1e-9)
        assertTrue(sink.rects.all { it.color == 7 })
    }

    // ---- the keys -----------------------------------------------------------------------------------------------------

    @Test
    fun anOrdinaryKeyIsOnlyItsLegendWithAPaperHalo() {
        val sink = draw(cap("q"))
        assertTrue("no cap of its own", sink.rects.isEmpty())
        assertEquals(1, sink.bits.size)
        val bits = sink.bits.single()
        assertEquals(c.ink, bits.ink)
        assertEquals(c.paper, bits.halo)
        assertFalse("hand-cut pixels get a square halo", bits.round)
        assertEquals(GlyphKeyPainter.BITS_UNIT, bits.unit, 0.0)
        // and it is centred on the key
        val cx = bits.x + bits.mask.w * bits.unit / 2
        assertEquals(120.0, cx, 1.5)
    }

    @Test
    fun typeThatIsNotCutByHandIsSetAndGetsARoundHalo() {
        val sink = draw(cap("ㄅ", art = null))
        val bits = sink.bits.single()
        assertTrue(bits.round)
        assertEquals(2.0, bits.unit, 0.0)
        assertTrue(draw(cap("", art = null)).bits.isEmpty())
    }

    @Test
    fun aLabelIsSetAtTwiceTheGridOrAtTheGridIfItWouldNotFit() {
        fun unitOf(label: String, width: Double): Double {
            val sink = RecordingSink()
            painter.key(sink, c, KeyCap(0.0, 0.0, width, 54.0, label, null, CapRole.PLAIN, false), false, false)
            return sink.bits.single().unit
        }
        assertEquals("room for four at twice the grid", 2.0, unitOf("ㄅㄆㄇㄈ", 60.0), 0.0)
        assertEquals("not for five", 1.0, unitOf("ㄅㄆㄇㄈㄉ", 60.0), 0.0)
        // one size of pixel type, whole numbers to the pixel
        for (label in listOf("ㄅ", "q", "ABC", "123", "符號")) {
            val f = painter.face(label)
            assertEquals(GlyphPixelType.EM, f.size); assertEquals(2.0, f.scale, 0.0); assertFalse(f.mono)
        }
    }

    @Test
    fun aPressedKeyTurnsToInk() {
        val sink = draw(cap("q", pressed = true))
        // a paper frame, then ink, three rectangles each; the legend is paper and has no halo of its own
        assertEquals(6, sink.rects.size)
        assertTrue(sink.rects.take(3).all { it.color == c.paper })
        assertTrue(sink.rects.drop(3).all { it.color == c.ink })
        val bits = sink.bits.single()
        assertEquals(c.paper, bits.ink)
        assertNull(bits.halo)
    }

    @Test
    fun theConfirmKeyIsTheOnlyVermilionAndTurnsToInkWhenPressed() {
        val sink = draw(cap("確認", role = CapRole.ACCENT))
        assertEquals(3, sink.rects.count { it.color == c.red })
        assertEquals(c.paper, sink.bits.single().ink)
        val pressed = draw(cap("確認", role = CapRole.ACCENT, pressed = true))
        assertTrue(pressed.rects.none { it.color == c.red })
        for (plain in listOf("q", "ㄅ", "⌫")) assertTrue(draw(cap(plain, art = null)).rects.none { it.color == c.red })
    }

    @Test
    fun theSpaceBarSaysWhatItWillDoWithItsShapeAndNeverWithWords() {
        val idle = draw(cap("空格", art = null, role = CapRole.SPACE))
        val composing = draw(cap("選詞", art = null, role = CapRole.SPACE), composing = true)
        assertTrue(idle.bits.isEmpty() && composing.bits.isEmpty())
        // a rule with turned-up ends: paper under, then the rule and its two ends
        assertEquals(4, idle.rects.size)
        assertEquals("a second rule while a reading is typed", idle.rects.size + 1, composing.rects.size)
        val rule = composing.rects[1]
        val inner = composing.rects.last()
        assertTrue(inner.x > rule.x && inner.x + inner.w < rule.x + rule.w)
        assertEquals(c.ink, inner.color)
        // pressed, it is a block like any other key
        assertEquals(6, draw(cap("空格", art = null, role = CapRole.SPACE, pressed = true)).rects.size)
    }

    @Test
    fun aKeyThatCannotBeUsedIsPaler() {
        val on = draw(cap("q")).bits.single().ink
        val off = draw(cap("q", enabled = false)).bits.single().ink
        assertEquals(c.ink, on)
        assertNotEquals(on, off)
        assertTrue("alpha ${off ushr 24}", (off ushr 24) < 255)
    }

    @Test
    fun withHighContrastEveryKeyIsASolidChip() {
        for (k in listOf(cap("q"), cap("ㄅ", art = null), cap("確認", role = CapRole.ACCENT), cap("空格", art = null, role = CapRole.SPACE))) {
            val sink = draw(k, chips = true)
            assertEquals("a rim and a block, three rectangles each", 6, sink.rects.size)
            assertTrue(sink.rects.none { it.color == c.red })
            assertNull("no halo: the chip is the ground", sink.bits.single().halo)
        }
        val letter = draw(cap("q"), chips = true)
        assertTrue("letters are ink with a paper legend", letter.rects.takeLast(3).all { it.color == c.ink } && letter.bits.single().ink == c.paper)
        val symbol = draw(cap("ㄅ", art = null), chips = true)
        assertTrue("the other keys are paper", symbol.rects.takeLast(3).all { it.color == c.paper } && symbol.bits.single().ink == c.ink)
        val down = draw(cap("q", pressed = true), chips = true)
        assertTrue(down.rects.takeLast(3).all { it.color == c.paper })
        val sheet = RecordingSink()
        painter.barSheet(sheet, c, 393.0, 34.0, 46.0)
        assertEquals(6, sheet.rects.size)
    }

    // ---- the candidates ---------------------------------------------------------------------------------------------------

    private val face = GlyphKeyPainter.CandidateFace(16, 1.5)

    @Test
    fun aCandidateStandsOnTheLandAndTheChosenOneIsABlockOfInk() {
        val plain = RecordingSink().also { painter.candidate(it, c, CandidateCap(20.0, 60.0, "你好", false, false), face, 57.0, 34.0, chips = false) }
        assertTrue(plain.rects.isEmpty())
        assertEquals(c.paper, plain.bits.single().halo)
        assertTrue(plain.bits.single().round)
        val chosen = RecordingSink().also { painter.candidate(it, c, CandidateCap(20.0, 60.0, "你好", true, false), face, 57.0, 34.0, chips = false) }
        assertEquals("a paper frame and an ink block", 6, chosen.rects.size)
        assertEquals(c.paper, chosen.bits.single().ink)
        assertNull(chosen.bits.single().halo)
        val pressed = RecordingSink().also { painter.candidate(it, c, CandidateCap(20.0, 60.0, "你好", false, false, pressed = true), face, 57.0, 34.0, chips = false) }
        assertEquals(6, pressed.rects.size)
        val sheet = RecordingSink().also { painter.candidate(it, c, CandidateCap(20.0, 60.0, "你好", true, false), face, 57.0, 34.0, chips = true) }
        assertEquals("on its own sheet the block has no frame", 3, sheet.rects.size)
    }

    @Test
    fun aJapaneseWordCarriesAPaw() {
        val plain = RecordingSink().also { painter.candidate(it, c, CandidateCap(20.0, 80.0, "寿司", false, true), face, 57.0, 34.0, false) }
        assertEquals("paw: a halo and the ink, four blocks each", 8, plain.rects.size)
        val chosen = RecordingSink().also { painter.candidate(it, c, CandidateCap(20.0, 80.0, "寿司", true, true), face, 57.0, 34.0, false) }
        assertEquals(6 + 4, chosen.rects.size)
        assertEquals(c.paper, chosen.rects.last().color)
    }

    @Test
    fun theBarsOwnLettersAreCentredOrPlaced() {
        val sink = RecordingSink()
        painter.label(sink, c, "注音", 100.0, 57.0, 1.0, 0.8, centre = false, halo = true)
        painter.label(sink, c, "— 山野 —", 200.0, 57.0, 1.5, 1.0, centre = true, halo = false)
        painter.chevron(sink, c, 370.0, 57.0, up = false, halo = true)
        painter.chevron(sink, c, 370.0, 57.0, up = true, halo = false)
        assertEquals(4, sink.bits.size)
        assertEquals(100.0, sink.bits[0].x, 0.5)
        assertEquals("centred", 200.0, sink.bits[1].x + sink.bits[1].mask.w * 1.5 / 2, 1.0)
        assertNotNull(sink.bits[0].halo); assertNull(sink.bits[1].halo)
        assertEquals((0.8 * 255).toInt(), sink.bits[0].ink ushr 24)
    }

    // ---- the pools, the cats and the colours -----------------------------------------------------------------------

    @Test
    fun theTypeIsSortedAndMostOfItCanBeAPixel() {
        val pools = GlyphPools()
        assertEquals("・ㄑㄚㅁ○ㄐ□ㄇㄨㄍㄛㄤㄡ〇ㄝㄌㄞ円", pools.list("pixel").joinToString(""))
        for ((name, _) in GlyphPools.ORDER) assertTrue(name, pools.has(name) && pools.list(name).isNotEmpty())
        for ((part, shares) in GlyphPools.SHARES) assertEquals(part, 1.0, shares.sumOf { it.second }, 1e-9)
        // the share of each script over many cells of the near land: pixels most, Zhuyin and Pinyin as grace notes, kana a guest
        val counts = HashMap<String, Int>()
        val n = 40000
        for (i in 0 until n) { val s = pools.script("bopo", i, i / 7, 3); counts.merge(s, 1, Int::plus) }
        val pixel = counts.getValue("pixel") / n.toDouble()
        val kana = (counts["hira"] ?: 0) / n.toDouble() + (counts["kata"] ?: 0) / n.toDouble()
        assertTrue("pixel share $pixel", pixel in 0.70..0.84)
        assertTrue("kana share $kana", kana in 0.01..0.06)
        assertEquals("a part without shares is its own pool", "wave", pools.script("wave", 1, 1, 1))
    }

    @Test
    fun aToneFindsAGlyphOfItsWeightAndAPoolNeverRunsDry() {
        val pools = GlyphPools()
        val bopo = pools.list("bopo")
        assertEquals(bopo.first(), pools.glyph("bopo", 0.0, 0.5))
        assertEquals(bopo.last(), pools.glyph("bopo", 1.0, 0.5))
        for (t in listOf(0.0, 0.3, 0.9, 1.0)) for (h in listOf(0.0, 0.5, 0.99)) assertTrue(pools.glyph("pixel", t, h) in pools.list("pixel"))
        assertEquals(0.0, pools.rank("bopo", bopo.first())!!, 0.0)
        assertEquals(1.0, pools.rank("bopo", bopo.last())!!, 0.0)
        assertNull(pools.rank("bopo", "x"))
        assertNotNull(pools.poem("bopo")); assertNull(pools.poem("wave"))
        assertTrue(GlyphPools.isMono("pinyin")); assertFalse(GlyphPools.isMono("bopo"))
        assertTrue(GlyphPools.faceScale("pinyin") > GlyphPools.faceScale("bopo"))
        // a platform that lacks glyphs drops them; nothing is left empty and the verse keeps only what can be drawn
        val poor = GlyphPools { it == "ㄅ" || it == "ㄇ" }
        assertEquals(listOf("ㄅ", "ㄇ"), poor.list("bopo"))
        assertEquals("・", poor.list("wave").single())
        assertTrue(poor.poem("bopo")!!.all { it == "ㄅ" || it == "ㄇ" })
        assertEquals(listOf("縺"), poor.moji)
        assertTrue(GlyphPools().moji.size > 4)
    }

    @Test
    fun theCatsAreTheirPosesAndFacesOnAMargin() {
        for (cat in CatId.entries) for (pose in GlyphCats.POSES) for (face in GlyphCats.FACES) {
            val rows = GlyphCats.rows(cat, pose, face)
            assertEquals(GlyphCats.h(cat), rows.size)
            assertTrue(rows.all { it.size == GlyphCats.w(cat) })
            val r = GlyphCats.raster(cat, pose, face, dark = false, eyesOnly = false, colors = c)
            assertEquals(GlyphCats.w(cat) + 2 * GlyphCats.MARGIN, r.w)
            assertEquals(GlyphCats.h(cat) + 2 * GlyphCats.MARGIN, r.h)
            assertEquals(r.w * r.h, r.pixels.size)
            assertTrue("every colour is a colour of the palette, the margin or the edge", r.pixels.all {
                it == 0 || it == c.paper || it == 0xFF5C3D2E.toInt() || it in GlyphCatArt.PALETTE.values
            })
        }
        // a sleeping cat has shut eyes already: no face is laid over them
        assertArrayEquals(GlyphCats.rows(CatId.MOMO, "sleep", "neutral").map { String(it) }.toTypedArray(),
            GlyphCats.rows(CatId.MOMO, "sleep", "happy").map { String(it) }.toTypedArray())
        assertNotEquals(String(GlyphCats.rows(CatId.MIMI, "sit", "neutral")[5]), String(GlyphCats.rows(CatId.MIMI, "sit", "wide")[4]))
    }

    @Test
    fun theCatThatIsTheColourOfTheGroundNeedsNoOutline() {
        assertEquals("pale momo on pale paper has a brown outline", 0xFF5C3D2E.toInt(), GlyphCats.edgeColor(CatId.MOMO, dark = false, ink = c.ink))
        assertNull("black mimi by day", GlyphCats.edgeColor(CatId.MIMI, dark = false, ink = c.ink))
        assertNull("pale momo by night", GlyphCats.edgeColor(CatId.MOMO, dark = true, ink = GlyphColors.DARK.ink))
        assertEquals("black mimi in the dark has a pale outline", GlyphColors.DARK.ink, GlyphCats.edgeColor(CatId.MIMI, dark = true, ink = GlyphColors.DARK.ink))
    }

    @Test
    fun atNightTheBlackCatIsOnlyItsEyes() {
        val night = GlyphColors.DARK
        val r = GlyphCats.raster(CatId.MIMI, "sit", "neutral", dark = true, eyesOnly = true, colors = night)
        val visible = r.pixels.filter { it != 0 }
        assertTrue(visible.isNotEmpty())
        assertTrue("only the eyes show; the rest of him is the night's own colour", visible.all {
            it == night.paper || it == GlyphCatArt.PALETTE.getValue('Y') || it == GlyphCatArt.PALETTE.getValue('W') || it == GlyphCatArt.PALETTE.getValue('e')
        })
        assertTrue(visible.count { it == GlyphCatArt.PALETTE.getValue('Y') } >= 2)
    }

    @Test
    fun theGuestCrabAndTheFishAreSmallPictures() {
        val a = GlyphCats.crabRaster(0, c.paper)
        val b = GlyphCats.crabRaster(1, c.paper)
        assertEquals(13, a.w); assertEquals(10, a.h)
        assertFalse(a.pixels.contentEquals(b.pixels))
        assertTrue(a.pixels.any { it == GlyphCats.CRAB_ORANGE } && a.pixels.any { it == GlyphCats.CRAB_EYE } && a.pixels.any { it == c.paper })
        assertEquals(3, GlyphCats.FISH.size)
    }

    @Test
    fun theSinkDrawsPicturesAndMasksAsBlocksByDefault() {
        val sink = RecordingSink()
        sink.raster(GlyphCats.crabRaster(0, c.paper), 0.0, 0.0, 2.0)
        val crabPixels = GlyphCats.crabRaster(0, c.paper).pixels.count { it ushr 24 != 0 }
        assertEquals(crabPixels, sink.rects.size)
        val m = GlyphKeyArt.mask(listOf("X.X", ".X."))
        val plain = RecordingSink()
        object : com.krysta1ll.meowpuut.android.ime.glyph.GlyphSink {
            override fun rect(x: Double, y: Double, w: Double, h: Double, color: Int) { plain.rect(x, y, w, h, color) }
            override fun text(ch: String, cx: Double, cy: Double, size: Int, mono: Boolean, color: Int) = Unit
        }.bits(m, 0.0, 0.0, 2.0, 1, 2, round = false)
        val inks = plain.rects.count { it.color == 1 }
        assertEquals(3, inks)
        assertTrue(plain.rects.count { it.color == 2 } > inks)
    }

    @Test
    fun theColoursAreThePrototypesAndFadeByAlpha() {
        assertEquals(0xFFECE8DC.toInt(), GlyphColors.LIGHT.paper)
        assertEquals(0xFF151413.toInt(), GlyphColors.of(true).paper)
        assertEquals(0xFFC4402A.toInt(), GlyphColors.of(false).red)
        assertEquals(128, GlyphColors.withAlpha(0xFF123456.toInt(), 0.5) ushr 24)
        assertEquals(0x123456, GlyphColors.withAlpha(0xFF123456.toInt(), 0.2) and 0xFFFFFF)
        assertEquals(255, GlyphColors.withAlpha(0, 3.0) ushr 24)
        assertEquals(0, GlyphColors.withAlpha(0, -1.0) ushr 24)
    }
}
