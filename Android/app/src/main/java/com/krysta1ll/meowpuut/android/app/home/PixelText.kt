package com.krysta1ll.meowpuut.android.app.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.View
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphKeyArt
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphKeyPainter
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphPixelFonts
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphPixelType
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The pixel type of 字景 on a page: the same two bitmap faces as the keyboard's keys (Cubic 11 and Fusion Pixel, the system font for what
 * they lack), cut to one bit and set a whole number of device pixels to the pixel, so the grid stays square at any density. One per page; it
 * keeps what it cut. Main thread only.
 */
internal class PixelType(context: Context) {
    private val painter = GlyphKeyPainter(GlyphPixelFonts(context))
    private val advances = HashMap<Int, Int>()
    private val bitmaps = HashMap<String, Bitmap>()

    fun mask(text: String): GlyphKeyArt.Mask = painter.mask(text, GlyphPixelType.EM, 400, false)

    /** The width of a run in type pixels (without the mask's one pixel of margin on each side). */
    fun width(text: String): Int = max(0, mask(text).w - 2)

    /** One character's advance, for breaking a paragraph into lines. */
    fun advance(cp: Int): Int = advances.getOrPut(cp) { width(String(Character.toChars(cp))) }

    /** The run as a bitmap of one pixel per type pixel, ink and clear. */
    fun bitmap(text: String, ink: Int): Bitmap {
        if (bitmaps.size > 160) bitmaps.values.forEach { it.recycle() }.also { bitmaps.clear() }
        return bitmaps.getOrPut("$ink/$text") {
            val m = mask(text)
            val argb = IntArray(m.w * m.h)
            for (i in argb.indices) if (m.on[i]) argb[i] = m.paint(i, ink)
            Bitmap.createBitmap(argb, m.w, m.h, Bitmap.Config.ARGB_8888)
        }
    }
}

/**
 * A line or a paragraph of pixel type. [em] is the size in dp of the type's 12 pixel em (12 is the body, 24 a heading, 48 the big number);
 * the pixel is a whole number of device pixels and follows the system's font scale. Words wrap at any character (Chinese needs no spaces).
 * Its words are its description for TalkBack; a heading is announced as one.
 */
@SuppressLint("ViewConstructor")
internal class PixelText(
    context: Context, private val type: PixelType, text: CharSequence, private val em: Int, private var ink: Int,
    private val gravity: Int = Gravity.START, private val lineSpacing: Float = 1.5f, heading: Boolean = false,
) : View(context) {
    private val density = context.resources.displayMetrics.density
    private val fontScale get() = context.resources.configuration.fontScale
    private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private var lines: List<String> = emptyList()
    private var unit = 1
    private var text: String = text.toString()
    private val src = Rect()
    private val dst = Rect()

    init {
        contentDescription = this.text
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        if (heading && Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }

    fun setText(value: CharSequence) {
        text = value.toString()
        contentDescription = text
        requestLayout(); invalidate()
    }

    fun setInk(color: Int) { ink = color; invalidate() }

    /** Device pixels to a pixel of the type. */
    private fun unitPx(): Int = max(1, (em * density * fontScale / GlyphPixelType.EM).roundToInt())

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        unit = unitPx()
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val room = if (mode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE / 2 else MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        lines = wrap(text, max(1, room / unit))
        val widest = lines.maxOfOrNull { type.width(it) } ?: 0
        val w = if (mode == MeasureSpec.EXACTLY) MeasureSpec.getSize(widthMeasureSpec) else min(widest * unit + paddingLeft + paddingRight, MeasureSpec.getSize(widthMeasureSpec).takeIf { mode == MeasureSpec.AT_MOST } ?: Int.MAX_VALUE)
        val h = if (lines.isEmpty()) 0 else (lineAdvance() * (lines.size - 1) + GlyphPixelType.ROWS * unit).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(w, h)
    }

    private fun min(a: Int, b: Int) = if (a < b) a else b

    /** Device pixels from one line to the next. */
    private fun lineAdvance(): Float = GlyphPixelType.EM * unit * lineSpacing

    private fun wrap(text: String, columns: Int): List<String> {
        val out = ArrayList<String>()
        for (paragraph in text.split('\n')) {
            var line = StringBuilder()
            var used = 0
            var i = 0
            while (i < paragraph.length) {
                val cp = paragraph.codePointAt(i)
                val n = Character.charCount(cp)
                val a = type.advance(cp)
                if (used + a > columns && line.isNotEmpty() && !noStart(cp)) { out += line.toString(); line = StringBuilder(); used = 0 }
                line.appendCodePoint(cp); used += a
                i += n
            }
            out += line.toString()
        }
        return out
    }

    /** Marks that may not begin a line (closing and stopping punctuation): they stay at the end of the line before. */
    private fun noStart(cp: Int) = cp.toChar() in "。，、．；：！？）」』》〉】〕…—,.;:!?)]}"

    override fun onDraw(canvas: Canvas) {
        var y = paddingTop.toFloat()
        for (line in lines) {
            if (line.isNotEmpty()) {
                val bitmap = type.bitmap(line, ink)
                val w = (bitmap.width - 2) * unit
                val x = when (gravity and Gravity.HORIZONTAL_GRAVITY_MASK) {
                    Gravity.END, Gravity.RIGHT -> width - paddingRight - w
                    Gravity.CENTER_HORIZONTAL -> paddingLeft + (width - paddingLeft - paddingRight - w) / 2
                    else -> paddingLeft
                } - unit                                        // the mask has one pixel of margin on its left
                src.set(0, 0, bitmap.width, bitmap.height)
                dst.set(x, y.toInt(), x + bitmap.width * unit, y.toInt() + bitmap.height * unit)
                canvas.drawBitmap(bitmap, src, dst, paint)
            }
            y += lineAdvance()
        }
    }
}
