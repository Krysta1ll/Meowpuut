package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.ime.Box
import com.krysta1ll.meowpuut.android.ime.CandidateRegionLayout
import com.krysta1ll.meowpuut.android.ime.Idiom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrozenBarTest {
    private class Bar(
        val width: Float, val idiom: Idiom, val rim: Float, val height: Float,
        val glassTop: Float, val glassHeight: Float, val font: Float, val pill: Float,
    )

    private val bars = listOf(
        Bar(320f, Idiom.PHONE, 0f, 74f, 30.8f, 41.4f, 19f, 29f),
        Bar(360f, Idiom.PHONE, 0f, 75f, 31.030533f, 42.137405f, 19f, 29f),
        Bar(393f, Idiom.PHONE, 0f, 82f, 34f, 46f, 20f, 32f),
        Bar(393f, Idiom.PHONE, 0.065f, 82f, 34f, 46f, 20f, 32f),
        Bar(393f, Idiom.PHONE, 0.16f, 82f, 34f, 46f, 20f, 32f),
        Bar(412f, Idiom.PHONE, 0.065f, 86f, 35.679386f, 48.22392f, 21f, 34f),
        Bar(440f, Idiom.PHONE, 0.16f, 92f, 38.259537f, 51.501274f, 21f, 36f),
        Bar(480f, Idiom.PHONE, 0f, 94f, 38.8f, 52.9f, 21f, 37f),
        Bar(852f, Idiom.PHONE_LANDSCAPE, 0f, 63f, 25f, 36f, 18f, 25f),
        Bar(852f, Idiom.PHONE_LANDSCAPE, 0.16f, 63f, 25f, 36f, 18f, 25f),
        Bar(820f, Idiom.TABLET, 0f, 94f, 38.7f, 53f, 21f, 37f),
        Bar(1024f, Idiom.TABLET, 0.065f, 94f, 38.7f, 53f, 21f, 37f),
    )

    @Test
    fun barKeepsItsHeightAndLetsCatsDetermineHorizontalRoom() {
        for (b in bars) {
            val r = CandidateRegionLayout(b.width, b.idiom, rim = b.rim)
            val tag = "${b.width} ${b.idiom} ${b.rim}"
            assertEquals(tag, b.height, r.height, 0f)
            assertEquals(tag, b.glassTop, r.glass.top, 0.001f)
            assertEquals(tag, b.glassHeight, r.glass.height, 0.001f)
            assertEquals(tag, r.glass.left, r.width - r.glass.right, 0.001f)
            assertTrue(tag, r.row.left < 40f)
            assertEquals(tag, r.row.right, r.chevron.left, 0.001f)
            assertEquals(tag, 44f, r.chevron.width, 0f)
            assertEquals(tag, b.font, r.fontSize, 0f)
            assertEquals(tag, b.pill, r.pillHeight, 0f)
        }
        val plain = CandidateRegionLayout(393f, Idiom.PHONE, cats = false)
        assertEquals(58f, plain.height, 0f)
        assertEquals(Box(4f, 6f, 385f, 46f), plain.glass)
        assertEquals(Box(17.8f, 6f, 321.2f, 46f), plain.row)
        assertEquals(Box(339f, 7f, 44f, 44f), plain.chevron)
    }
}
