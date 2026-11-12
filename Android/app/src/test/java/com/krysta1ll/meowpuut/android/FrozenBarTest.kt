package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.ime.Box
import com.krysta1ll.meowpuut.android.ime.CandidateRegionLayout
import com.krysta1ll.meowpuut.android.ime.Idiom
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The candidate bar's frame is frozen: these are the numbers the layout gave
 * with the earlier cat art (measured from that code before the new cats came
 * in), and it must keep giving them.
 */
class FrozenBarTest {
    private class Bar(
        val width: Float, val idiom: Idiom, val rim: Float, val height: Float,
        val glass: Box, val row: Box, val chevron: Box, val font: Float, val pill: Float,
    )

    private val bars = listOf(
        Bar(320.0f, Idiom.PHONE, 0.0f, 74.0f, Box(14.033592f, 30.8f, 300.5664f, 41.399998f), Box(54.5733f, 30.8f, 189.88771f, 41.399998f), Box(244.46101f, 32.6f, 30.599998f, 41.399998f), 19.0f, 29.0f),
        Bar(360.0f, Idiom.PHONE, 0.0f, 75.0f, Box(14.283554f, 31.030533f, 340.22028f, 42.137405f), Box(55.545345f, 31.030533f, 227.57019f, 42.137405f), Box(283.11554f, 32.862595f, 31.145039f, 42.137405f), 19.0f, 29.0f),
        Bar(393.0f, Idiom.PHONE, 0.0f, 82.0f, Box(15.59288f, 34.0f, 371.4071f, 46.0f), Box(60.637f, 34.0f, 248.43082f, 46.0f), Box(309.0678f, 37.0f, 34.0f, 44.0f), 20.0f, 32.0f),
        Bar(393.0f, Idiom.PHONE, 0.065f, 82.0f, Box(15.59288f, 34.0f, 371.4071f, 46.0f), Box(60.637f, 34.0f, 248.43082f, 46.0f), Box(309.0678f, 38.989998f, 34.0f, 43.010002f), 20.0f, 32.0f),
        Bar(393.0f, Idiom.PHONE, 0.16f, 82.0f, Box(15.59288f, 34.0f, 371.4071f, 46.0f), Box(60.637f, 34.0f, 248.43082f, 46.0f), Box(309.0678f, 43.36f, 34.0f, 38.64f), 20.0f, 32.0f),
        Bar(412.0f, Idiom.PHONE, 0.065f, 86.0f, Box(16.346733f, 35.679386f, 389.3632f, 48.22392f), Box(63.568554f, 35.679386f, 260.44147f, 48.22392f), Box(324.01f, 41.45531f, 35.643764f, 44.0f), 21.0f, 34.0f),
        Bar(440.0f, Idiom.PHONE, 0.16f, 92.0f, Box(17.457678f, 38.259537f, 415.82477f, 51.501274f), Box(67.888756f, 38.259537f, 278.14136f, 51.501274f), Box(346.03012f, 48.738937f, 38.06616f, 43.261063f), 21.0f, 36.0f),
        Bar(480.0f, Idiom.PHONE, 0.0f, 94.0f, Box(17.93181f, 38.8f, 455.16818f, 52.899998f), Box(69.73255f, 38.8f, 313.74545f, 52.899998f), Box(383.478f, 45.550003f, 39.1f, 44.0f), 21.0f, 37.0f),
        Bar(852.0f, Idiom.PHONE_LANDSCAPE, 0.0f, 63.0f, Box(12.08504f, 25.0f, 833.915f, 36.0f), Box(46.021f, 25.0f, 736.807f, 36.0f), Box(782.828f, 27.0f, 34.0f, 36.0f), 18.0f, 25.0f),
        Bar(852.0f, Idiom.PHONE_LANDSCAPE, 0.16f, 63.0f, Box(12.08504f, 25.0f, 833.915f, 36.0f), Box(46.021f, 25.0f, 736.807f, 36.0f), Box(782.828f, 32.760002f, 34.0f, 30.239998f), 18.0f, 25.0f),
        Bar(820.0f, Idiom.TABLET, 0.0f, 94.0f, Box(17.86604f, 38.699997f, 795.23395f, 53.0f), Box(69.4585f, 38.699997f, 654.12494f, 53.0f), Box(723.58344f, 45.5f, 39.1f, 44.0f), 21.0f, 37.0f),
        Bar(1024.0f, Idiom.TABLET, 0.065f, 94.0f, Box(17.86604f, 38.699997f, 999.23395f, 53.0f), Box(69.4585f, 38.699997f, 858.12494f, 53.0f), Box(927.58344f, 47.222504f, 39.1f, 44.0f), 21.0f, 37.0f),
    )

    @Test
    fun barIsWhereItWas() {
        for (b in bars) {
            val r = CandidateRegionLayout(b.width, b.idiom, rim = b.rim)
            val tag = "${b.width} ${b.idiom} ${b.rim}"
            assertEquals(tag, b.height, r.height, 0f)
            assertEquals(tag, b.glass, r.glass)
            assertEquals(tag, b.row, r.row)
            assertEquals(tag, b.chevron, r.chevron)
            assertEquals(tag, b.font, r.fontSize, 0f)
            assertEquals(tag, b.pill, r.pillHeight, 0f)
        }
        val plain = CandidateRegionLayout(393f, Idiom.PHONE, cats = false)
        assertEquals(58f, plain.height, 0f)
        assertEquals(Box(4f, 6f, 385f, 46f), plain.glass)
        assertEquals(Box(17.8f, 6f, 325.2f, 46f), plain.row)
        assertEquals(Box(343f, 7f, 40f, 44f), plain.chevron)
    }
}
