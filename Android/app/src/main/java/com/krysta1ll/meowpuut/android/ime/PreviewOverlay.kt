package com.krysta1ll.meowpuut.android.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.view.View
import kotlin.math.min

/**
 * Key balloons and the long-press alternates strip, drawn above everything
 * else so the top row's balloons can rise over the candidate bar. It never
 * takes touches.
 */
@SuppressLint("ViewConstructor")
class PreviewOverlay(context: Context) : View(context) {
    private class Balloon(val key: Box, val bubble: Box, val label: String, val radius: Float, val fontSize: Float)
    private class Strip(val layout: AlternatesStrip, val options: List<String>, var selected: Int, val fontSize: Float, val fromBar: Boolean)

    private val density = resources.displayMetrics.density
    private val balloons = LinkedHashMap<Int, Balloon>()
    private var strip: Strip? = null
    var look: Look = Look(false, GlassShape.CAPSULE, Palette.ROSE, GlassStrength.BALANCED, true)
    var idiom: Idiom = Idiom.PHONE

    private val face = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }
    private val path = Path()
    private val rect = RectF()

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** [key] is in this view's dp coordinates. */
    fun showBalloon(id: Int, key: Box, label: String, keyRadius: Float, fontSize: Float) {
        val bubble = KeyBalloon.bubble(key, width / density, idiom)
        balloons[id] = Balloon(key, bubble, label, keyRadius, fontSize)
        invalidate()
    }

    fun hideBalloon(id: Int) {
        if (balloons.remove(id) != null) invalidate()
    }

    /** [layout] is in this view's dp coordinates. */
    fun showAlternates(layout: AlternatesStrip, options: List<String>, selected: Int, fontSize: Float, fromBar: Boolean = false) {
        val fitted = if (layout.box.top < 2f) layout.offset(0f, 2f - layout.box.top) else layout
        strip = Strip(fitted, options, selected, fontSize, fromBar)
        invalidate()
    }

    fun selectAlternate(index: Int, fromBar: Boolean = false) {
        val s = strip?.takeIf { it.fromBar == fromBar } ?: return
        if (s.selected != index) { s.selected = index; invalidate() }
    }

    fun hideAlternates(fromBar: Boolean = false) {
        if (strip?.fromBar == fromBar) { strip = null; invalidate() }
    }

    fun clear() {
        balloons.clear()
        strip = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (balloons.isEmpty() && strip == null) return
        canvas.save()
        canvas.scale(density, density)
        for (b in balloons.values) drawBalloon(canvas, b)
        strip?.let { drawStrip(canvas, it) }
        canvas.restore()
    }

    private fun shadow(paint: Paint) {
        if (Build.VERSION.SDK_INT >= 28) paint.setShadowLayer(4f, 0f, 2f, Look.withAlpha(Color.BLACK, if (look.dark) 0.45f else 0.20f))
    }

    private fun drawBalloon(canvas: Canvas, b: Balloon) {
        face.style = Paint.Style.FILL
        if (look.usesSteppedMaterial) {
            rect.set(b.bubble.left, b.bubble.top, b.bubble.right, b.bubble.bottom)
            drawPixelPanel(canvas, rect, look.pixelSurface, look.pixelEdge, face)
        } else {
            balloonPath(b.key, b.bubble, b.radius)
            face.color = look.balloonFace
            shadow(face)
            canvas.drawPath(path, face)
            face.clearShadowLayer()
            face.style = Paint.Style.STROKE
            face.strokeWidth = 0.5f
            face.color = Look.withAlpha(if (look.dark) Color.WHITE else Color.BLACK, if (look.dark) 0.10f else 0.06f)
            canvas.drawPath(path, face)
        }
        text.color = look.keyText
        text.textSize = b.fontSize
        text.textSize *= minOf(1f, (b.bubble.width - 6f).coerceAtLeast(1f) / text.measureText(b.label),
            (b.bubble.height - 8f).coerceAtLeast(1f) / (text.descent() - text.ascent()))
        val cy = b.bubble.top + b.bubble.height * 0.46f
        canvas.drawText(b.label, b.bubble.centerX, cy - (text.ascent() + text.descent()) / 2, text)
    }

    private fun drawStrip(canvas: Canvas, s: Strip) {
        val box = s.layout.box
        val cell = s.layout.cell
        rect.set(box.left, box.top, box.right, box.bottom)
        face.style = Paint.Style.FILL
        if (look.usesSteppedMaterial) {
            drawPixelPanel(canvas, rect, look.pixelSurface, look.pixelEdge, face)
        } else {
            face.color = look.balloonFace
            shadow(face)
            canvas.drawRoundRect(rect, 11f, 11f, face)
            face.clearShadowLayer()
        }
        for ((i, option) in s.options.withIndex()) {
            val left = s.layout.cellLeft(i)
            if (i == s.selected) {
                face.color = if (look.usesSteppedMaterial) look.pixelAccent else look.accent
                rect.set(left + 1, box.top + 5, left + cell - 1, box.bottom - 5)
                if (look.usesSteppedMaterial) canvas.drawRect(rect, face)
                else canvas.drawRoundRect(rect, 8f, 8f, face)
                text.color = Color.WHITE
            } else {
                text.color = look.keyText
            }
            // Long options (^_^) shrink to their cell.
            text.textSize = s.fontSize
            val wide = text.measureText(option)
            if (wide > cell - 4) text.textSize = s.fontSize * (cell - 4) / wide
            canvas.drawText(option, left + cell / 2, box.centerY - (text.ascent() + text.descent()) / 2, text)
        }
    }

    /** The key's own stem widening into a rounded bubble through an S-curve on each side. */
    private fun balloonPath(key: Box, bubble: Box, keyRadius: Float) {
        val r = min(12f, bubble.width / 4)
        val k = min(keyRadius, key.width / 3)
        // The curve starts a little inside the key and ends a little inside the bubble.
        val low = key.top + min(9f, key.height * 0.22f)
        val high = bubble.bottom - min(10f, bubble.height * 0.2f)
        val middle = (low + high) / 2
        path.reset()
        path.moveTo(key.left + k, key.bottom)
        path.quadTo(key.left, key.bottom, key.left, key.bottom - k)
        path.lineTo(key.left, low)
        path.cubicTo(key.left, middle, bubble.left, middle, bubble.left, high)
        path.lineTo(bubble.left, bubble.top + r)
        path.quadTo(bubble.left, bubble.top, bubble.left + r, bubble.top)
        path.lineTo(bubble.right - r, bubble.top)
        path.quadTo(bubble.right, bubble.top, bubble.right, bubble.top + r)
        path.lineTo(bubble.right, high)
        path.cubicTo(bubble.right, middle, key.right, middle, key.right, low)
        path.lineTo(key.right, key.bottom - k)
        path.quadTo(key.right, key.bottom, key.right - k, key.bottom)
        path.close()
    }
}
