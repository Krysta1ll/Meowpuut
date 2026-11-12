package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.ime.Box
import com.krysta1ll.meowpuut.android.ime.CandidateRegionLayout
import com.krysta1ll.meowpuut.android.ime.Idiom
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// TEMPORARY: the layout exactly as it was at HEAD, to read the bar's numbers from.
class OldRegion(rawWidth: Float, val idiom: Idiom, val cats: Boolean = true, val rim: Float = 0f) {
    val width: Float = if (rawWidth.isFinite() && rawWidth > 1f) rawWidth else 320f
    val scale: Float
    val height: Float
    val glass: Box
    val mimimo: Box
    val momo: Box
    val tail: Box
    val row: Box
    val chevron: Box
    val fontSize: Float
    val statusFontSize: Float
    val pillHeight: Float

    init {
        val s: Float
        val regionHeight: Float
        val glassHeight: Float
        val mimimoHeight: Float
        val momoHeight: Float
        val tailHeight: Float
        val font: Float
        when (idiom) {
            Idiom.PHONE -> {
                s = min(1.15f, max(0.9f, width / 393f))
                regionHeight = 82 * s; glassHeight = 46 * s
                mimimoHeight = 62 * s; momoHeight = 54 * s; tailHeight = 28 * s
                font = min(21f, max(19f, 20 * s))
            }
            Idiom.PHONE_LANDSCAPE -> {
                s = 1f
                regionHeight = 63f; glassHeight = 36f
                mimimoHeight = 46f; momoHeight = 40f; tailHeight = 22f
                font = 18f
            }
            Idiom.TABLET -> {
                s = 1.15f
                regionHeight = 94f; glassHeight = 53f
                mimimoHeight = 71f; momoHeight = 62f; tailHeight = 32f
                font = 21f
            }
        }
        scale = s
        fontSize = font.roundToInt().toFloat()
        pillHeight = (glassHeight * 0.70f).roundToInt().toFloat()
        statusFontSize = when (idiom) {
            Idiom.TABLET -> 15f
            Idiom.PHONE_LANDSCAPE -> 12f
            Idiom.PHONE -> (13 * s).roundToInt().toFloat()
        }
        val rimY = min(0.3f, max(0f, rim)) * glassHeight

        if (cats) {
            height = regionHeight.roundToInt().toFloat()
            val glassBottom = height - 2 * s
            val top = glassBottom - glassHeight
            val mimimoWidth = mimimoHeight * MIMIMO_ASPECT
            // His eyes peek just over the glass's upper rim.
            mimimo = Box(2 * s, top + rimY - mimimoHeight * MIMIMO_EYE_BOTTOM, mimimoWidth, mimimoHeight)
            val momoWidth = momoHeight * MOMO_ASPECT
            // Her chin rests on the glass, eyes just above its rim.
            momo = Box(width - 4 * s - momoWidth, top + rimY - 1 * s - momoHeight * MOMO_EYE_LINE, momoWidth, momoHeight)
            val glassLeft = mimimo.left + 0.24f * mimimoWidth
            glass = Box(glassLeft, top, max(1f, width - 6 * s - glassLeft), glassHeight)
            // The tail leaves his body at the lower left and runs along the
            // glass's bottom rim, in front of it.
            val tailWidth = tailHeight * TAIL_ASPECT
            tail = Box(mimimo.left + 3 * s, glassBottom + 1 * s - tailHeight, tailWidth, tailHeight)
            val chevronWidth = 34 * s
            val chevronY = glass.centerY + rimY / 2 + 2 * s
            val chevronHeight = min(min(44f, glassHeight), 2 * (height - chevronY))
            chevron = Box(momo.left - 6 * s - chevronWidth / 2, chevronY - chevronHeight / 2, chevronWidth, chevronHeight)
            val rowLeft = mimimo.right + 2 * s
            row = Box(rowLeft, glass.top, max(1f, chevron.left - rowLeft), glassHeight)
        } else {
            height = (glassHeight + 12 * s).roundToInt().toFloat()
            glass = Box(4 * s, (height - glassHeight) / 2, max(1f, width - 8 * s), glassHeight)
            mimimo = Box.ZERO; momo = Box.ZERO; tail = Box.ZERO
            val chevronWidth = 40 * s
            val chevronHeight = min(44f, glassHeight)
            val chevronY = min(glass.centerY + rimY / 2, height - chevronHeight / 2)
            chevron = Box(glass.right - 6 * s - chevronWidth, chevronY - chevronHeight / 2, chevronWidth, chevronHeight)
            val rowLeft = glass.left + glassHeight * 0.3f
            row = Box(rowLeft, glass.top, max(1f, chevron.left - rowLeft), glassHeight)
        }
    }

    val maximumCandidateWidth: Float get() = max(60f, row.width * 0.70f)

    companion object {
        const val MIMIMO_ASPECT = 0.9135f
        const val MIMIMO_EYE_BOTTOM = 0.538f
        const val MOMO_ASPECT = 1.0543f
        const val MOMO_EYE_LINE = 0.50f
        const val TAIL_ASPECT = 4.02f
    }
}

class OldBarProbe {
    @Test
    fun sameAsBefore() {
        val out = StringBuilder()
        var checked = 0
        for (idiom in Idiom.entries) {
            for (rim in listOf(0f, 0.065f, 0.16f)) {
                for (cats in listOf(true, false)) {
                    var w = 280f
                    while (w <= 1400f) {
                        val old = OldRegion(w, idiom, cats, rim)
                        val now = CandidateRegionLayout(w, idiom, cats, rim)
                        val tag = "$idiom $rim $cats $w"
                        assertEquals(tag, old.glass, now.glass)
                        assertEquals(tag, old.row, now.row)
                        assertEquals(tag, old.chevron, now.chevron)
                        assertEquals(tag, old.height, now.height, 0f)
                        assertEquals(tag, old.scale, now.scale, 0f)
                        assertEquals(tag, old.fontSize, now.fontSize, 0f)
                        assertEquals(tag, old.statusFontSize, now.statusFontSize, 0f)
                        assertEquals(tag, old.pillHeight, now.pillHeight, 0f)
                        assertEquals(tag, old.maximumCandidateWidth, now.maximumCandidateWidth, 0f)
                        checked++
                        w += 0.5f
                    }
                }
            }
        }
        val cases = listOf(
            Triple(320f, Idiom.PHONE, 0f), Triple(360f, Idiom.PHONE, 0f), Triple(393f, Idiom.PHONE, 0f),
            Triple(393f, Idiom.PHONE, 0.065f), Triple(393f, Idiom.PHONE, 0.16f),
            Triple(412f, Idiom.PHONE, 0.065f), Triple(440f, Idiom.PHONE, 0.16f), Triple(480f, Idiom.PHONE, 0f),
            Triple(852f, Idiom.PHONE_LANDSCAPE, 0f), Triple(852f, Idiom.PHONE_LANDSCAPE, 0.16f),
            Triple(820f, Idiom.TABLET, 0f), Triple(1024f, Idiom.TABLET, 0.065f),
        )
        for ((w, idiom, rim) in cases) {
            val r = OldRegion(w, idiom, true, rim)
            fun b(x: Box) = "Box(${x.left}f, ${x.top}f, ${x.width}f, ${x.height}f)"
            out.append("Bar(${w}f, Idiom.$idiom, ${rim}f, ${r.height}f, ${b(r.glass)}, ${b(r.row)}, ${b(r.chevron)}, ${r.fontSize}f, ${r.pillHeight}f),\n")
        }
        val r = OldRegion(393f, Idiom.PHONE, false, 0f)
        out.append("nocats 393: ${r.height} ${r.glass} ${r.row} ${r.chevron} ${r.fontSize} ${r.pillHeight}\n")
        out.append("checked $checked\n")
        java.io.File("build/old-bar-numbers.txt").writeText(out.toString())
    }
}
