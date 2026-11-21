package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.ime.GlassShape
import com.krysta1ll.meowpuut.android.ime.GlassStrength
import com.krysta1ll.meowpuut.android.ime.KeyboardSkin
import com.krysta1ll.meowpuut.android.ime.Look
import com.krysta1ll.meowpuut.android.ime.Palette
import com.krysta1ll.meowpuut.android.ime.SkinCatArtwork
import com.krysta1ll.meowpuut.android.ime.SkinMaterial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How 字景 sits among the skins: its own paper and ink, and no change to what the others look like. */
class GlyphSkinTest {
    private fun look(skin: KeyboardSkin, dark: Boolean = false, opaque: Boolean = false) =
        Look(dark, GlassShape.CAPSULE, Palette.ROSE, GlassStrength.BALANCED, true, opaque = opaque, skin = skin)

    @Test
    fun theSkinIsKnownByItsKeyAndIsFreeToChoose() {
        assertEquals(KeyboardSkin.GLYPH, KeyboardSkin.of("glyph"))
        assertEquals("glyph", KeyboardSkin.GLYPH.key)
        assertFalse("no quiz", KeyboardSkin.GLYPH.requiresQuiz)
        assertEquals(SkinMaterial.GLYPH, KeyboardSkin.GLYPH.material)
        assertEquals(SkinCatArtwork.PIXEL, KeyboardSkin.GLYPH.catArtwork)
        assertEquals(KeyboardSkin.CURRENT, KeyboardSkin.of("nothing"))
        // free: only the pixel skin asks to be earned
        assertEquals(listOf(KeyboardSkin.PIXEL), KeyboardSkin.entries.filter { it.requiresQuiz })
    }

    @Test
    fun theKeyboardIsPaperAndInkInBothAppearances() {
        val day = look(KeyboardSkin.GLYPH)
        assertTrue(day.isGlyph)
        assertEquals(0xFFECE8DC.toInt(), day.keyboardTop)
        assertEquals(0xFFECE8DC.toInt(), day.keyboardBottom)
        assertEquals(0xFF1E1C1A.toInt(), day.keyText)
        assertEquals(0xFF1E1C1A.toInt(), day.candidateText)
        assertEquals(0xFFC4402A.toInt(), day.accent)
        val night = look(KeyboardSkin.GLYPH, dark = true)
        assertEquals(0xFF151413.toInt(), night.keyboardTop)
        assertEquals(0xFFE9E4D6.toInt(), night.keyText)
        assertEquals(0xFFE2573A.toInt(), night.accent)
        assertTrue("the panels and balloons take the stepped paper material", day.usesSteppedMaterial)
        assertEquals(day.keyText, day.pixelEdge)
    }

    @Test
    fun theSystemsGroundColourLiesBehindThePaper() {
        assertEquals(0xFFFFFFFF.toInt(), look(KeyboardSkin.GLYPH).groundPlate)
        assertEquals(0xFF000000.toInt(), look(KeyboardSkin.GLYPH, dark = true).groundPlate)
        for (skin in KeyboardSkin.entries - KeyboardSkin.GLYPH) for (dark in listOf(false, true)) assertEquals(skin.name, null, look(skin, dark).groundPlate)
    }

    @Test
    fun theTextOnItsPaperIsLegible() {
        fun lum(c: Int): Double {
            fun ch(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
            return 0.2126 * ch(c shr 16 and 255) + 0.7152 * ch(c shr 8 and 255) + 0.0722 * ch(c and 255)
        }
        fun ratio(a: Int, b: Int): Double { val x = lum(a); val y = lum(b); return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05) }
        for (dark in listOf(false, true)) {
            val l = look(KeyboardSkin.GLYPH, dark)
            assertTrue(ratio(l.keyText, l.keyboardTop) > 12)
            assertTrue(ratio(l.candidateSecondary, l.keyboardTop) > 4.5)
            assertTrue(ratio(l.accent, l.keyboardTop) > 3)
        }
    }

    @Test
    fun theOtherSkinsAreNotGlyphAndKeepTheirMaterial() {
        for (skin in KeyboardSkin.entries - KeyboardSkin.GLYPH) {
            val l = look(skin)
            assertFalse(skin.name, l.isGlyph)
            assertEquals(skin.name, skin.material == SkinMaterial.PIXEL, l.usesSteppedMaterial)
        }
        assertFalse(look(KeyboardSkin.CURRENT).usesSteppedMaterial)
    }
}
