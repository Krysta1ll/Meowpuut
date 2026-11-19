package com.krysta1ll.meowpuut.android.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.text.TextPaint
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeProvider
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Scrollable vertical Pinyin syllable filter rail placed over the left utility column
 * (`rows 0..2, column 0`) of the nine-key layout while composing.
 */
@SuppressLint("ViewConstructor")
class NineKeySyllableRailView(context: Context) : View(context) {
    var onSelect: ((String) -> Unit)? = null
    var look: Look = Look(false, GlassShape.CAPSULE, Palette.ROSE, GlassStrength.BALANCED, true)
        set(value) { if (field != value) { field = value; shaderHeight = -1f; invalidate() } }
    var words = Words(false)
    var keyTextSize = KeyboardTextSize.STANDARD
        set(value) { if (field != value) { field = value; invalidate() } }

    private var syllables: List<String> = emptyList()
    private var cornerRadius = 8f
    private val density = resources.displayMetrics.density
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.65f
    }
    private val itemPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pixelPaint = Paint()
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 500, false)
        else Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val rect = RectF()
    private var shaderHeight = -1f
    private var scroll = 0f
    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop / density
    private var downY = 0f
    private var downScroll = 0f
    private var dragging = false
    private var pressed = -1

    private val accessibility = VirtualAccessibility(this, ::accessibleItems) { index ->
        syllables.getOrNull(index)?.let { onSelect?.invoke(it) }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun update(list: List<String>, radiusDp: Float) {
        cornerRadius = radiusDp
        if (syllables == list) return
        syllables = list
        scroll = 0f
        pressed = -1
        scroller.forceFinished(true)
        accessibility.invalidateAll()
        invalidate()
    }

    private val contentHeight get() = PAD * 2 + syllables.size * ITEM_HEIGHT + max(0, syllables.size - 1) * ITEM_GAP
    private val maxScroll get() = max(0f, contentHeight - height / density)

    private fun prepareShader(h: Float) {
        shaderHeight = h
        facePaint.shader = LinearGradient(0f, 0f, 0f, h, look.keyUtilityTop, look.keyUtilityBottom, Shader.TileMode.CLAMP)
        rimPaint.shader = if (look.isCentenary) LinearGradient(0f, 0f, 0f, h, intArrayOf(
            Look.withAlpha(look.keyGlassReflection, if (look.dark) 0.80f else 0.90f),
            Look.withAlpha(look.tint, if (look.dark) 0.34f else 0.28f),
            Look.withAlpha(look.tint, if (look.dark) 0.62f else 0.54f),
        ), floatArrayOf(0f, 0.40f, 1f), Shader.TileMode.CLAMP)
        else LinearGradient(0f, 0f, 0f, h, intArrayOf(
            Look.withAlpha(Color.WHITE, if (look.dark) 0.22f else 0.78f),
            Look.withAlpha(Color.WHITE, if (look.dark) 0.04f else 0.12f),
            Look.withAlpha(Color.BLACK, if (look.dark) 0.18f else 0.09f),
        ), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        if (syllables.isEmpty() || width <= 0 || height <= 0) return
        canvas.save()
        canvas.scale(density, density)
        val w = width / density
        val h = height / density
        val r = min(cornerRadius, min(w, h) * 0.37f)
        rect.set(0f, 0f, w, h)
        if (look.usesSteppedMaterial) {
            drawPixelPanel(canvas, rect, look.pixelUtility, look.pixelEdge, pixelPaint)
        } else {
            val simple = look.effects == EffectsLevel.LIGHTWEIGHT || look.opaque || look.overlay != null
            if (!simple && shaderHeight != h) prepareShader(h)
            facePaint.shader = if (simple) null else facePaint.shader
            if (simple) facePaint.color = look.keyUtility
            canvas.drawRoundRect(rect, r, r, facePaint)
            if (!simple) {
                rect.inset(0.5f, 0.5f)
                canvas.drawRoundRect(rect, r - 0.5f, r - 0.5f, rimPaint)
            }
        }
        rect.set(1f, 1f, w - 1f, h - 1f)
        canvas.clipRect(rect)
        canvas.translate(0f, -scroll)

        val scale = resources.configuration.fontScale
        val baseSize = keyTextSize.fontSize(15f, scale)
        for ((i, syl) in syllables.withIndex()) {
            val top = PAD + i * (ITEM_HEIGHT + ITEM_GAP)
            if (top + ITEM_HEIGHT < scroll || top > scroll + h) continue
            val lead = i == 0
            if (lead || i == pressed) {
                itemPaint.color = when {
                    i == pressed -> Look.withAlpha(look.pill.second, if (look.dark) 0.34f else 0.24f)
                    else -> Look.withAlpha(if (look.dark) Color.WHITE else Color.BLACK, if (look.dark) 0.12f else 0.07f)
                }
                rect.set(3f, top, w - 3f, top + ITEM_HEIGHT)
                val cr = min(r - 1f, ITEM_HEIGHT * 0.38f)
                if (look.usesSteppedMaterial) canvas.drawRect(rect, itemPaint)
                else canvas.drawRoundRect(rect, cr, cr, itemPaint)
            }
            labelPaint.textSize = baseSize
            val measured = labelPaint.measureText(syl)
            if (measured > w - 10f && measured > 0f) {
                labelPaint.textSize *= ((w - 10f) / measured).coerceAtLeast(0.68f)
            }
            labelPaint.color = if (lead) look.keyText else Look.withAlpha(look.keyText, 0.86f)
            val cy = top + ITEM_HEIGHT / 2f
            canvas.drawText(syl, w / 2f, cy - (labelPaint.ascent() + labelPaint.descent()) / 2f, labelPaint)
            if (i < syllables.lastIndex) {
                itemPaint.color = Look.withAlpha(if (look.dark) Color.WHITE else Color.BLACK, if (look.dark) 0.08f else 0.06f)
                val lineY = top + ITEM_HEIGHT + ITEM_GAP / 2f
                canvas.drawRect(8f, lineY - 0.25f, w - 8f, lineY + 0.25f, itemPaint)
            }
        }
        canvas.restore()
        if (scroller.computeScrollOffset()) {
            scroll = (scroller.currY / density).coerceIn(0f, maxScroll)
            postInvalidateOnAnimation()
        }
    }

    private fun itemAt(y: Float): Int {
        if (syllables.isEmpty() || y < -2f || y > height / density + 2f) return -1
        val cy = max(0f, y + scroll - PAD)
        val step = ITEM_HEIGHT + ITEM_GAP
        val index = (cy / step).toInt()
        return index.coerceIn(0, syllables.lastIndex)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (syllables.isEmpty()) return false
        val y = event.y / density
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(event) }
                downY = y; downScroll = scroll; dragging = false
                pressed = itemAt(y)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(event)
                if (!dragging && abs(y - downY) > slop) { dragging = true; pressed = -1 }
                if (dragging) {
                    scroll = (downScroll - (y - downY)).coerceIn(0f, maxScroll)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                velocity?.addMovement(event)
                if (dragging) {
                    velocity?.computeCurrentVelocity(1000)
                    val vy = -(velocity?.yVelocity ?: 0f)
                    scroller.fling(0, (scroll * density).roundToInt(), 0, vy.roundToInt(), 0, 0, 0, (maxScroll * density).roundToInt())
                    postInvalidateOnAnimation()
                } else {
                    val idx = itemAt(y)
                    val x = event.x / density
                    val target = when {
                        idx >= 0 && idx == pressed -> idx
                        pressed in syllables.indices && abs(y - downY) <= slop &&
                            x >= -4f && x <= width / density + 4f -> pressed
                        else -> -1
                    }
                    if (target >= 0) onSelect?.invoke(syllables[target])
                }
                velocity?.recycle(); velocity = null
                pressed = -1
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                velocity?.recycle(); velocity = null
                pressed = -1
                invalidate()
            }
        }
        return true
    }

    private fun accessibleItems(): List<VirtualAccessibility.Item> {
        val h = height / density
        val w = width / density
        return syllables.indices.mapNotNull { i ->
            val top = PAD + i * (ITEM_HEIGHT + ITEM_GAP) - scroll
            if (top + ITEM_HEIGHT < 0f || top > h) null
            else VirtualAccessibility.Item(i,
                Rect(0, (max(0f, top) * density).toInt(), (w * density).toInt(), (min(h, top + ITEM_HEIGHT) * density).toInt()),
                words("拼音音節：", "拼音音节：") + syllables[i], selected = i == 0)
        }
    }

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = accessibility
    override fun dispatchHoverEvent(event: MotionEvent): Boolean = accessibility.onHover(event) || super.dispatchHoverEvent(event)

    companion object {
        private const val PAD = 4f
        private const val ITEM_HEIGHT = 34f
        private const val ITEM_GAP = 3f
    }
}
