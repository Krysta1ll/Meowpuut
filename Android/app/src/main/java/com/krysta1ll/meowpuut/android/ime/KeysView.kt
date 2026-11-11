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
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeProvider
import kotlin.math.min

/**
 * Draws the keys and passes every touch on them to [KeyTouches] (DESIGN.md
 * §6): no dead zones between keys, multi-finger rollover, balloons on
 * touch-down, delete repeat that speeds up to whole words, a cursor drag on
 * the held space bar, sliding from Shift or a page key to a character, and
 * long-press alternates.
 */
@SuppressLint("ViewConstructor")
class KeysView(context: Context, private val previews: PreviewOverlay) : View(context), KeyTouches.Host {
    var listener: KeyListener?
        get() = touches.listener
        set(value) { touches.listener = value }
    var look: Look = Look(false, GlassShape.CAPSULE, Palette.ROSE, GlassStrength.BALANCED, true)
        set(value) { if (field != value) { field = value; shaderHeight = -1f; invalidate() } }
    var idiom = Idiom.PHONE
        private set
    var metrics: KeyMetrics = KeyMetrics.of(Idiom.PHONE, 393f, 4)
        private set
    val keySet: KeySet? get() = touches.keySet

    /** Height (dp) from the top that the expanded candidate panel covers; 0 when collapsed. */
    var coveredHeight = 0f
        set(value) { if (field != value) { field = value; accessibility.invalidateAll() } }

    private val touches = KeyTouches(this)
    private val density = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var faceShader: Shader? = null
    private var shaderHeight = -1f

    private val accessibility = VirtualAccessibility(this, ::accessibleKeys) { id -> touches.activate(id / 100, id % 100) }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /** The keyboard was measured: the same keys at a new size. */
    fun setMetrics(metrics: KeyMetrics, idiom: Idiom) {
        if (this.metrics == metrics && this.idiom == idiom) return
        val set = keySet
        if (set != null) {
            setKeys(set, metrics, idiom)
        } else {
            this.metrics = metrics
            this.idiom = idiom
            previews.idiom = idiom
        }
    }

    fun setKeys(set: KeySet, metrics: KeyMetrics, idiom: Idiom) {
        val old = keySet
        if (old === set && this.metrics == metrics && this.idiom == idiom) return
        // Same places, new labels (Shift, titles): fingers that are down stay down.
        val sameShape = old != null && old.page == set.page && old.specs == set.specs &&
            old.rows.map { it.size } == set.rows.map { it.size } && this.metrics == metrics && this.idiom == idiom
        this.metrics = metrics
        this.idiom = idiom
        previews.idiom = idiom
        if (sameShape) {
            touches.layout(set, touches.placements, width / density, sameShape = true)
        } else {
            shaderHeight = -1f
            touches.layout(set, place(set), width / density, sameShape = false)
        }
        accessibility.invalidateAll()
        invalidate()
    }

    /** Before the first layout there is no width to place keys in. */
    private fun place(set: KeySet): List<List<KeyPlacement>> =
        if (width == 0) emptyList() else KeyGrid.place(set.specs, width / density, set.referenceColumns, metrics)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Key places depend on the width and the metrics, not on the height.
        if (w == oldw) return
        val set = keySet ?: return
        touches.layout(set, place(set), w / density, sameShape = false)
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        val set = keySet ?: return
        canvas.save()
        canvas.scale(density, density)
        val radius = metrics.cornerRadius
        if (shaderHeight != metrics.keyHeight) {
            shaderHeight = metrics.keyHeight
            faceShader = LinearGradient(0f, 0f, 0f, metrics.keyHeight, look.keyFaceTop, look.keyFaceBottom, Shader.TileMode.CLAMP)
        }
        val cursorMode = touches.cursorMode
        val fingers = touches.active
        for ((r, row) in touches.placements.withIndex()) {
            for (placement in row) {
                val key = set.rows.getOrNull(r)?.getOrNull(placement.column) ?: continue
                val f = placement.frame
                val pressed = fingers && touches.isPressed(r, placement.column)
                // Crisp 1 dp bottom edge, as on Apple's keys.
                shadowPaint.color = look.keyShadow
                rect.set(f.left, f.top + 1f, f.right, f.bottom + 1f)
                canvas.drawRoundRect(rect, radius, radius, shadowPaint)
                facePaint.shader = null
                when {
                    key.style == KeyStyle.ACCENT -> facePaint.color = if (pressed) Look.lighten(look.accent, 0.15f) else look.accent
                    key.style == KeyStyle.UTILITY -> facePaint.color = if (pressed) look.keyPressed else look.keyUtility
                    // A phone shows the balloon instead of darkening the key.
                    pressed && (!key.preview || idiom == Idiom.TABLET) -> facePaint.color = look.keyCharacterPressed
                    else -> facePaint.shader = faceShader
                }
                if (cursorMode) facePaint.alpha = 170
                canvas.save()
                canvas.translate(f.left, f.top)
                rect.set(0f, 0f, f.width, f.height)
                canvas.drawRoundRect(rect, radius, radius, facePaint)
                canvas.restore()
                facePaint.alpha = 255
                // While the space bar moves the cursor the labels are hidden.
                if (!cursorMode) drawLabel(canvas, key, f)
            }
        }
        canvas.restore()
    }

    private fun drawLabel(canvas: Canvas, key: Key, f: Box) {
        val color = when {
            key.style == KeyStyle.ACCENT -> Color.WHITE
            !key.enabled -> look.keyTextDisabled
            else -> look.keyText
        }
        val icon = key.icon
        if (icon != null) {
            iconPaint.color = color
            val size = metrics.utilityFont * 1.25f
            when (icon) {
                KeyIcon.SHIFT -> Glyphs.shift(canvas, f.centerX, f.centerY, size, filled = false, lock = false, paint = iconPaint)
                KeyIcon.SHIFT_ON -> Glyphs.shift(canvas, f.centerX, f.centerY, size, filled = true, lock = false, paint = iconPaint)
                KeyIcon.CAPS_LOCK -> Glyphs.shift(canvas, f.centerX, f.centerY - size * 0.08f, size, filled = true, lock = true, paint = iconPaint)
                KeyIcon.DELETE -> Glyphs.delete(canvas, f.centerX, f.centerY, size, iconPaint)
                KeyIcon.GLOBE -> Glyphs.globe(canvas, f.centerX, f.centerY, size * 0.9f, iconPaint)
            }
            return
        }
        if (key.label.isEmpty()) return
        val size = key.fontSize ?: metrics.letterFont
        labelPaint.textSize = if (key.style != KeyStyle.CHARACTER || key.action == KeyAction.Space) min(size, metrics.utilityFont + 1) else size
        labelPaint.color = color
        // Lower-case letters sit a little higher, as Apple draws them.
        val lift = if (key.isCharacter && key.label.length == 1 && key.label[0].isLowerCase()) f.height * 0.04f else 0f
        val maxWidth = f.width - 8f
        val width = labelPaint.measureText(key.label)
        if (width > maxWidth) labelPaint.textSize *= maxWidth / width
        canvas.drawText(key.label, f.centerX, f.centerY - lift - (labelPaint.ascent() + labelPaint.descent()) / 2, labelPaint)
    }

    // ---------------------------------------------------------------- touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // A first finger while others are still recorded means an UP was lost.
                if (event.actionMasked == MotionEvent.ACTION_DOWN && touches.active) touches.cancelAll()
                val index = event.actionIndex
                touches.down(event.getPointerId(index), event.getX(index) / density, event.getY(index) / density)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    touches.move(event.getPointerId(i), event.getX(i) / density, event.getY(i) / density)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                touches.up(event.getPointerId(index), event.getX(index) / density, event.getY(index) / density)
            }
            MotionEvent.ACTION_CANCEL -> touches.cancelAll()
        }
        return true
    }

    override fun onDetachedFromWindow() {
        touches.cancelAll()
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------------- KeyTouches.Host

    override fun post(delayMs: Long, action: Runnable) {
        handler.postDelayed(action, delayMs)
    }

    override fun cancel(action: Runnable) = handler.removeCallbacks(action)

    override fun showBalloon(id: Int, key: Key, frame: Box) {
        // A tablet darkens the key instead.
        if (idiom == Idiom.TABLET) return
        val size = when {
            idiom == Idiom.PHONE_LANDSCAPE -> 28f
            key.label.length > 1 -> 24f
            else -> BALLOON_FONT
        }
        previews.showBalloon(id, frame.offset(0f, top / density), key.label, metrics.cornerRadius, size)
    }

    override fun hideBalloon(id: Int) = previews.hideBalloon(id)

    override fun showAlternates(strip: AlternatesStrip, options: List<String>, selected: Int) =
        previews.showAlternates(strip.offset(0f, top / density), options, selected, ALTERNATES_FONT)

    override fun selectAlternate(index: Int) = previews.selectAlternate(index)

    override fun hideAlternates() = previews.hideAlternates()

    override fun clearVisuals() = previews.clear()

    override fun redraw() = invalidate()

    // ---------------------------------------------------------------- accessibility

    private fun accessibleKeys(): List<VirtualAccessibility.Item> {
        val set = keySet ?: return emptyList()
        val out = ArrayList<VirtualAccessibility.Item>()
        for ((r, row) in touches.placements.withIndex()) for (placement in row) {
            val key = set.rows.getOrNull(r)?.getOrNull(placement.column) ?: continue
            val f = placement.frame
            if (f.top < coveredHeight) continue
            out += VirtualAccessibility.Item(r * 100 + placement.column,
                Rect((f.left * density).toInt(), (f.top * density).toInt(), (f.right * density).toInt(), (f.bottom * density).toInt()),
                key.spoken.ifEmpty { key.label }, enabled = key.enabled)
        }
        return out
    }

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = accessibility

    override fun dispatchHoverEvent(event: MotionEvent): Boolean = accessibility.onHover(event) || super.dispatchHoverEvent(event)

    companion object {
        const val BALLOON_FONT = 34f
        const val ALTERNATES_FONT = 24f
    }
}
