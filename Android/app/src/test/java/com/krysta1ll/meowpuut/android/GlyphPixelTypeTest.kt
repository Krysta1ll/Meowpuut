package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.ime.glyph.FaceRun
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphCoverage
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphFontChain
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphPixelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The two bitmap faces and the system font, in the order they are tried, and the coverage lists the build script writes. */
class GlyphPixelTypeTest {
    private fun shipped(name: String) = GlyphCoverage.parse(File("src/main/assets/fonts/$name").readText())

    private val chain get() = GlyphFontChain(listOf(shipped("pixel_a.cov"), shipped("pixel_b.cov")))

    @Test
    fun aCoverageListIsRangesOfHexCodePoints() {
        val c = GlyphCoverage.parse("20-7E\n3105\n\n  4E00-4E02  \nnot hex\n9FFF-")
        assertTrue(c.has(0x20) && c.has(0x7E) && c.has(0x41) && c.has(0x3105) && c.has(0x4E01) && c.has(0x4E02))
        assertFalse(c.has(0x1F) && c.has(0x7F)); assertFalse(c.has(0x3104)); assertFalse(c.has(0x4E03)); assertFalse(c.has(0x9FFF))
        assertEquals(0x7E - 0x20 + 1 + 1 + 3, c.size)
        assertFalse(GlyphCoverage.NONE.has(0x41))
        assertEquals(0, GlyphCoverage.NONE.size)
    }

    @Test
    fun theFirstFaceThatHasACharacterDrawsIt() {
        val a = GlyphCoverage.parse("41-5A\n4F60")
        val b = GlyphCoverage.parse("4F60\n8FD9")
        val chain = GlyphFontChain(listOf(a, b))
        assertEquals(0, chain.faceOf('A'.code))
        assertEquals("A has it, so B never draws it", 0, chain.faceOf(0x4F60))
        assertEquals(1, chain.faceOf(0x8FD9))
        assertEquals(GlyphPixelType.SYSTEM, chain.faceOf(0x1F408))
        assertEquals(GlyphPixelType.SYSTEM, GlyphFontChain(emptyList()).faceOf('A'.code))
    }

    @Test
    fun anEmojiStandsAloneAndKeepsItsColours() {
        val chain = GlyphFontChain(listOf(GlyphCoverage.parse("41-5A\n2764\n266A"), GlyphCoverage.parse("")))
        fun cut(text: String) = chain.plan(text).map { it.face to it.text }
        val colour = GlyphPixelType.COLOUR
        // two pictures side by side are two runs; a skin tone, a joined family, a flag pair and a keycap are one each
        assertEquals(listOf(colour to "👋", colour to "👋🏽", 0 to "A"), cut("👋👋🏽A"))
        assertEquals(listOf(colour to "👨‍👩‍👧"), cut("👨‍👩‍👧"))
        assertEquals(listOf(colour to "🇯🇵", colour to "🇹🇼"), cut("🇯🇵🇹🇼"))
        assertEquals(listOf(colour to "1️⃣"), cut("1️⃣"))
        // a symbol a face has stays set in that face, unless U+FE0F asks for the picture
        assertEquals(listOf(0 to "❤"), cut("❤"))
        assertEquals(listOf(colour to "❤️"), cut("❤️"))
        // a plain symbol nobody has is the system's, in one bit; a default emoji outside the big block is a picture
        assertEquals(listOf(GlyphPixelType.SYSTEM to "☆"), cut("☆"))
        assertEquals(listOf(colour to "⭐", colour to "✅"), cut("⭐✅"))
        // a face's own character before a selector that is not an emoji one is left alone
        assertEquals(listOf(0 to "A"), cut("A"))
    }

    @Test
    fun aTextIsCutIntoRunsOfOneFace() {
        val chain = GlyphFontChain(listOf(GlyphCoverage.parse("41-5A\n4F60"), GlyphCoverage.parse("8FD9")))
        fun cut(text: String) = chain.plan(text).map { it.face to it.text }
        assertEquals(listOf(0 to "AB你", 1 to "这", GlyphPixelType.COLOUR to "🐈", 0 to "A"), cut("AB你这🐈A"))
        assertEquals(emptyList<FaceRun>(), chain.plan("").map { it })
        // a variation selector or a joiner stays with the character it follows
        assertEquals(listOf(0 to "A️"), cut("A️"))
        assertEquals(listOf(GlyphPixelType.COLOUR to "🐈‍🐈"), cut("🐈‍🐈"))
        assertEquals(1.0, chain.drawnShare(""), 0.0)
        assertEquals(0.75, chain.drawnShare("AB这🐈"), 0.0)
    }

    @Test
    fun theFacesLieOnTheSamePixelsAndShareABaseline() {
        // 12 px em, 100 font units a pixel in both faces; both draw ideographs 11 pixels tall, A one pixel lower in its design, so it is drawn one higher
        assertEquals(12, GlyphPixelType.EM)
        assertEquals(1, GlyphPixelType.FACE_A_RISE)
        assertEquals(GlyphPixelType.baselineRow(1) - 1, GlyphPixelType.baselineRow(0))
        // B's ideographs span [-1, 10] above its baseline, A's [-2, 9] above its own: both then fill the rows 2 to 12 from the top
        val top = GlyphPixelType.baselineRow(1) - 10
        val bottom = GlyphPixelType.baselineRow(1) + 1
        assertEquals(2, top); assertEquals(13, bottom)   // rows 2 to 12, the bottom one exclusive
        assertEquals(top, GlyphPixelType.baselineRow(0) - 9)
        assertEquals(bottom, GlyphPixelType.baselineRow(0) + 2)
        assertTrue("the mask is tall enough for a tall mark and a descender", GlyphPixelType.ROWS >= bottom + 1)
    }

    @Test
    fun theShippedFacesDrawWhatAKeyboardNeeds() {
        val a = shipped("pixel_a.cov")
        val b = shipped("pixel_b.cov")
        assertTrue(a.size in 7000..8000)
        assertTrue(b.size in 800..2000)
        // nothing is in both: B holds only what A lacks
        val overlap = (0x20..0x2FFFF).count { a.has(it) && b.has(it) }
        assertEquals(0, overlap)
        val chain = chain
        for (ch in "你好喵貓的了是不在ㄅㄆㄇㄈˊˇˋ˙あいうえおアイウエオABCxyz0123，。？！、「」") assertTrue("$ch", chain.faceOf(ch.code) == 0)
        for (ch in "这个们说为时来会对关发开机") assertTrue("simplified $ch is drawn by a pixel face", chain.faceOf(ch.code) >= 0)
        for (ch in "寿司麺読") assertTrue("Japanese $ch", chain.faceOf(ch.code) >= 0)
        assertEquals("an emoji is drawn by the system", GlyphPixelType.SYSTEM, chain.faceOf(0x1F600))
        assertTrue(chain.drawnShare("你好，世界") == 1.0)
    }

    @Test
    fun theCharacterSetIsDrawnByThePixelFacesExceptWhatNoFaceHas() {
        val wanted = File("../../scripts/glyph-pixel-chars.txt").readText(Charsets.UTF_8).trim('\n')
        val chain = chain
        val points = wanted.codePoints().toArray()
        val drawn = points.count { chain.faceOf(it) >= 0 }
        // the characters of the set that neither face has (symbols, emoji in the strings of the app) are few
        assertTrue("$drawn of ${points.size}", drawn >= points.size * 0.95)
        assertTrue(points.size in 8000..11000)
    }
}
