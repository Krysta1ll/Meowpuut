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
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeProvider
import com.krysta1ll.meowpuut.android.R
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
    internal var usesLensKeycaps = false
        set(value) {
            if (field == value) return
            field = value
            shaderHeight = -1f
            invalidate()
        }
    var idiom = Idiom.PHONE
        private set
    var metrics: KeyMetrics = KeyMetrics.of(Idiom.PHONE, 393f, 4)
        private set
    var showKeyPopups = true
    var keyTextSize = KeyboardTextSize.STANDARD
        set(value) {
            if (field == value) return
            field = value
            keySet?.let(::layoutLabels)
            invalidate()
        }
    val keySet: KeySet? get() = touches.keySet

    /** Height (dp) from the top that the expanded candidate panel covers; 0 when collapsed. */
    var coveredHeight = 0f
        set(value) { if (field != value) { field = value; accessibility.invalidateAll() } }

    private val touches = KeyTouches(this)
    private val density = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pixelPaint = Paint()
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.65f
    }
    private val lensRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.55f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }
    private val digitPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val groupPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 600, false)
        else Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cursorHint = context.getString(R.string.cursor_drag_hint)
    private val rect = RectF()
    private data class LabelLayout(val size: Float, val baselineOffset: Float, val digitSize: Float = 0f, val digitOffset: Float = 0f)
    private var labels: List<List<LabelLayout?>> = emptyList()
    private var faceShader: Shader? = null
    private var utilityShader: Shader? = null
    private var shaderHeight = -1f
    private var feedbackRow = -1
    private var feedbackColumn = -1
    private val clearPress = Runnable {
        feedbackRow = -1
        invalidate()
    }

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
        val widthChanged = old?.maximumWidth != set.maximumWidth
        val sameShape = old != null && !widthChanged && old.page == set.page && old.specs == set.specs &&
            old.rows.size == set.rows.size && old.rows.indices.all { old.rows[it].size == set.rows[it].size } &&
            this.metrics == metrics && this.idiom == idiom
        this.metrics = metrics
        this.idiom = idiom
        previews.idiom = idiom
        if (sameShape) {
            touches.layout(set, touches.placements, width / density, sameShape = true)
        } else {
            shaderHeight = -1f
            clearKeyPress()
            touches.layout(set, place(set), width / density, sameShape = false)
        }
        layoutLabels(set)
        if (widthChanged) requestLayout()
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
        clearKeyPress()
        touches.layout(set, place(set), w / density, sameShape = false)
        layoutLabels(set)
    }

    // ---------------------------------------------------------------- drawing

    private fun layoutLabels(set: KeySet) {
        val systemScale = resources.configuration.fontScale
        val bounds = Rect()
        labels = touches.placements.mapIndexed { row, placed ->
            placed.map { item ->
                val key = set.rows[row][item.column]
                val frame = item.frame
                if (key.icon != null || key.label.isEmpty()) null else if (key.digit != null) {
                    val gap = if (frame.height < 36f) 1f else 2f
                    digitPaint.textSize = keyTextSize.fontSize(min(11f, frame.height * 0.34f), systemScale)
                    groupPaint.textSize = keyTextSize.fontSize(min(19f, frame.height * 0.54f), systemScale)
                    digitPaint.getTextBounds(key.digit, 0, key.digit.length, bounds)
                    val digitTop = bounds.top
                    val digitHeight = bounds.height()
                    groupPaint.getTextBounds(key.label, 0, key.label.length, bounds)
                    val groupTop = bounds.top
                    val textHeight = digitHeight + bounds.height()
                    val textWidth = maxOf(digitPaint.measureText(key.digit), groupPaint.measureText(key.label))
                    val fit = minOf(1f, (frame.height - gap - 4f).coerceAtLeast(1f) / textHeight,
                        (frame.width - 8f).coerceAtLeast(1f) / textWidth)
                    digitPaint.textSize *= fit
                    groupPaint.textSize *= fit
                    val top = -(textHeight * fit + gap) / 2
                    LabelLayout(groupPaint.textSize, top + digitHeight * fit + gap - groupTop * fit,
                        digitPaint.textSize, top - digitTop * fit)
                } else {
                    val size = key.fontSize ?: metrics.letterFont
                    val base = if (key.style != KeyStyle.CHARACTER || key.action == KeyAction.Space) min(size, metrics.utilityFont + 1) else size
                    labelPaint.textSize = keyTextSize.fontSize(base, systemScale)
                    val width = labelPaint.measureText(key.label)
                    val height = labelPaint.descent() - labelPaint.ascent()
                    labelPaint.textSize *= minOf(1f, (frame.width - 8f).coerceAtLeast(1f) / width,
                        (frame.height - 4f).coerceAtLeast(1f) / height)
                    val lift = if (key.isCharacter && key.label.length == 1 && key.label[0].isLowerCase()) frame.height * 0.04f else 0f
                    LabelLayout(labelPaint.textSize, -lift - (labelPaint.ascent() + labelPaint.descent()) / 2)
                }
            }
        }
    }

    private fun capRadius(key: Key, frame: Box): Float {
        val extra = when {
            key.action == KeyAction.Space -> 2f
            key.digit != null || key.style != KeyStyle.CHARACTER -> 1f
            else -> 0f
        }
        val base = if (usesLensKeycaps) min(metrics.cornerRadius, 7f) else metrics.cornerRadius
        return min(base + extra, min(frame.width, frame.height) * 0.37f)
    }

    override fun onDraw(canvas: Canvas) {
        val set = keySet ?: return
        canvas.save()
        canvas.scale(density, density)
        val pixel = look.usesSteppedMaterial
        val simple = look.effects == EffectsLevel.LIGHTWEIGHT || look.opaque || pixel
        val full = look.effects.hasFullEffects && !simple
        val ultra = look.effects == EffectsLevel.ULTRA && !simple
        val glass = full || look.tintedGlass
        val pairedRim = !usesLensKeycaps && (ultra || look.tintedGlass)
        if (!simple && shaderHeight != metrics.keyHeight) {
            prepareMaterial(glass, ultra, pairedRim)
        }
        val cursorMode = touches.cursorMode
        val drawRim = !simple && !cursorMode
        val fingers = touches.active
        shadowPaint.color = look.keyShadow
        pressPaint.color = Look.withAlpha(if (look.dark) Color.WHITE else Color.BLACK, if (look.dark) 0.20f else 0.15f)
        for ((r, row) in touches.placements.withIndex()) {
            val held = if (fingers) touches.pressedColumns(r) else 0
            for (placement in row) {
                val key = set.rows.getOrNull(r)?.getOrNull(placement.column) ?: continue
                val f = placement.frame
                val radius = capRadius(key, f)
                val pressed = !cursorMode && key.enabled && (held and (1 shl placement.column) != 0 ||
                    r == feedbackRow && placement.column == feedbackColumn)
                if (pixel) {
                    val depth = if (pressed) 1f else 0f
                    val fill = when {
                        key.style == KeyStyle.ACCENT -> if (pressed) look.pixelAccentPress else look.pixelAccent
                        pressed -> look.pixelPress
                        key.style == KeyStyle.UTILITY || cursorMode -> look.pixelUtility
                        else -> look.pixelSurface
                    }
                    rect.set(f.left, f.top + depth, f.right, f.bottom + depth)
                    drawPixelPanel(canvas, rect, fill, look.pixelEdge, pixelPaint)
                    drawKeyLegend(canvas, key, f, labels.getOrNull(r)?.getOrNull(placement.column), depth, cursorMode)
                    continue
                }
                val depth = if (pressed && !simple) 1f else 0f
                if (full && !ultra && !pressed) {
                    // A fine ledge under the richer keycap.
                    rect.set(f.left + 0.4f, f.top + 0.8f, f.right - 0.4f, f.bottom + 0.8f)
                    canvas.drawRoundRect(rect, radius, radius, shadowPaint)
                }
                facePaint.shader = null
                when {
                    key.style == KeyStyle.ACCENT -> facePaint.color = look.accent
                    key.style == KeyStyle.UTILITY -> if (simple) facePaint.color = look.keyUtility else facePaint.shader = utilityShader
                    simple -> facePaint.color = look.keyFaceTop
                    else -> facePaint.shader = faceShader
                }
                if (cursorMode) facePaint.alpha = 170
                if (facePaint.shader == null && !drawRim) {
                    rect.set(f.left, f.top + depth, f.right, f.bottom + depth)
                    canvas.drawRoundRect(rect, radius, radius, facePaint)
                    if (pressed) canvas.drawRoundRect(rect, radius, radius, pressPaint)
                } else {
                    canvas.save()
                    canvas.translate(f.left, f.top + depth)
                    rect.set(0f, 0f, f.width, f.height)
                    canvas.drawRoundRect(rect, radius, radius, facePaint)
                    if (pressed) canvas.drawRoundRect(rect, radius, radius, pressPaint)
                    if (drawRim) {
                        if (pairedRim && !pressed) {
                            rect.inset(0.4f, 0.4f)
                            canvas.drawRoundRect(rect, radius - 0.4f, radius - 0.4f, lensRim)
                            rect.inset(0.75f, 0.75f)
                            canvas.drawRoundRect(rect, radius - 1.15f, radius - 1.15f, rimPaint)
                        } else {
                            rect.inset(0.5f, 0.5f)
                            canvas.drawRoundRect(rect, radius - 0.5f, radius - 0.5f, rimPaint)
                        }
                    }
                    canvas.restore()
                }
                facePaint.alpha = 255
                drawKeyLegend(canvas, key, f, labels.getOrNull(r)?.getOrNull(placement.column), depth, cursorMode)
            }
        }
        canvas.restore()
    }

    private fun drawKeyLegend(canvas: Canvas, key: Key, frame: Box, layout: LabelLayout?, depth: Float, cursorMode: Boolean) {
        if (!cursorMode) {
            drawLabel(canvas, key, frame, layout, depth)
        } else if (key.action == KeyAction.Space) {
            labelPaint.textSize = keyTextSize.fontSize(12f, resources.configuration.fontScale)
            val available = (frame.width - 12f).coerceAtLeast(1f)
            val text = if (labelPaint.measureText(cursorHint) <= available) cursorHint else "←  →"
            labelPaint.color = look.keyText
            val baseline = frame.centerY - (labelPaint.ascent() + labelPaint.descent()) / 2
            canvas.drawText(text, frame.centerX, baseline, labelPaint)
        }
    }

    /** All caps share these local shaders, which are rebuilt on theme or size changes. */
    private fun prepareMaterial(glass: Boolean, ultra: Boolean, pairedRim: Boolean) {
        shaderHeight = metrics.keyHeight
        if (glass) {
            val top = if (usesLensKeycaps) { if (look.dark) 0.36f else 0.64f }
                else if (look.dark) 0.64f else 0.86f
            val center = when (look.strength) {
                GlassStrength.SOFT -> if (look.dark) 0.61f else 0.80f
                GlassStrength.BALANCED -> if (look.dark) 0.52f else 0.67f
                GlassStrength.VIVID -> if (look.dark) 0.45f else 0.57f
            } - if (ultra) 0.12f else 0f
            fun face(utility: Boolean): Shader {
                val color = look.keyGlassTone(utility)
                return LinearGradient(0f, 0f, 0f, shaderHeight,
                    intArrayOf(Look.withAlpha(color, top), Look.withAlpha(color, center),
                        Look.withAlpha(color, center + 0.06f)),
                    floatArrayOf(0f, 0.38f, 1f), Shader.TileMode.CLAMP)
            }
            faceShader = face(false)
            utilityShader = face(true)
            // Grazing light and the reflected palette stay close to the edge.
            val reflection = Look.withAlpha(look.keyGlassReflection, if (pairedRim) 0.47f else 0.26f)
            val topRim = if (usesLensKeycaps) { if (look.dark) 0.28f else 0.54f }
                else if (look.dark) 0.48f else 0.87f
            rimPaint.shader = LinearGradient(0f, 0f, 0f, shaderHeight, intArrayOf(
                Look.withAlpha(Color.WHITE, topRim),
                Look.withAlpha(Color.WHITE, if (look.dark) 0.14f else 0.34f),
                Look.withAlpha(Color.WHITE, 0.035f), reflection,
                Look.withAlpha(Color.WHITE, if (look.dark) 0.18f else 0.48f),
            ), floatArrayOf(0f, 0.10f, 0.55f, 0.92f, 1f), Shader.TileMode.CLAMP)
            if (pairedRim) {
                lensRim.shader = LinearGradient(0f, 0f, 0f, shaderHeight,
                    Look.withAlpha(look.keyGlassReflection, if (look.dark) 0.28f else 0.42f),
                    Look.withAlpha(Color.BLACK, if (look.dark) 0.29f else 0.12f), Shader.TileMode.CLAMP)
            }
        } else {
            faceShader = LinearGradient(0f, 0f, 0f, shaderHeight, look.keyFaceTop, look.keyFaceBottom, Shader.TileMode.CLAMP)
            utilityShader = LinearGradient(0f, 0f, 0f, shaderHeight, look.keyUtilityTop, look.keyUtilityBottom, Shader.TileMode.CLAMP)
            rimPaint.shader = LinearGradient(0f, 0f, 0f, shaderHeight, intArrayOf(
                Look.withAlpha(Color.WHITE, if (look.dark) 0.20f else 0.78f),
                Look.withAlpha(Color.WHITE, if (look.dark) 0.02f else 0.07f),
                Look.withAlpha(Color.BLACK, if (look.dark) 0.14f else 0.09f),
            ), floatArrayOf(0f, 0.44f, 1f), Shader.TileMode.CLAMP)
        }
    }

    private fun drawLabel(canvas: Canvas, key: Key, f: Box, layout: LabelLayout?, depth: Float) {
        val color = when {
            key.style == KeyStyle.ACCENT -> Color.WHITE
            !key.enabled -> look.keyTextDisabled
            else -> look.keyText
        }
        val icon = key.icon
        if (icon != null) {
            iconPaint.color = color
            val size = metrics.utilityFont * 1.25f
            val centerY = f.centerY + depth
            when (icon) {
                KeyIcon.SHIFT -> Glyphs.shift(canvas, f.centerX, centerY, size, filled = false, lock = false, paint = iconPaint)
                KeyIcon.SHIFT_ON -> Glyphs.shift(canvas, f.centerX, centerY, size, filled = true, lock = false, paint = iconPaint)
                KeyIcon.CAPS_LOCK -> Glyphs.shift(canvas, f.centerX, centerY - size * 0.08f, size, filled = true, lock = true, paint = iconPaint)
                KeyIcon.DELETE -> Glyphs.delete(canvas, f.centerX, centerY, size, iconPaint)
                KeyIcon.GLOBE -> Glyphs.globe(canvas, f.centerX, centerY, size * 0.9f, iconPaint)
            }
            return
        }
        if (layout == null) return
        key.digit?.let { digit ->
            digitPaint.textSize = layout.digitSize
            groupPaint.textSize = layout.size
            digitPaint.color = if (look.opaque) color else Look.withAlpha(color, if (look.dark) 0.82f else 0.72f)
            groupPaint.color = color
            val centerY = f.centerY + depth
            canvas.drawText(digit, f.centerX, centerY + layout.digitOffset, digitPaint)
            canvas.drawText(key.label, f.centerX, centerY + layout.baselineOffset, groupPaint)
            return
        }
        labelPaint.textSize = layout.size
        labelPaint.color = color
        canvas.drawText(key.label, f.centerX, f.centerY + layout.baselineOffset + depth, labelPaint)
    }

    // ---------------------------------------------------------------- touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
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

    override fun pulseKeyPress(row: Int, column: Int) {
        feedbackRow = row
        feedbackColumn = column
        handler.removeCallbacks(clearPress)
        handler.postDelayed(clearPress, 56L)
        invalidate()
    }

    private fun clearKeyPress() {
        feedbackRow = -1
        handler.removeCallbacks(clearPress)
    }

    override fun showBalloon(id: Int, key: Key, frame: Box) {
        // A tablet darkens the key instead.
        if (!showKeyPopups || idiom == Idiom.TABLET) return
        val size = when {
            idiom == Idiom.PHONE_LANDSCAPE -> 28f
            key.label.length > 1 -> 24f
            else -> BALLOON_FONT
        }
        previews.showBalloon(id, frame.offset(left / density, top / density), key.label, capRadius(key, frame),
            keyTextSize.fontSize(size, resources.configuration.fontScale))
    }

    override fun hideBalloon(id: Int) = previews.hideBalloon(id)

    override fun showAlternates(strip: AlternatesStrip, options: List<String>, selected: Int) =
        previews.showAlternates(strip.offset(left / density, top / density), options, selected,
            keyTextSize.fontSize(ALTERNATES_FONT, resources.configuration.fontScale))

    override fun selectAlternate(index: Int) = previews.selectAlternate(index)

    override fun hideAlternates() = previews.hideAlternates()

    override fun clearVisuals() {
        clearKeyPress()
        previews.clear()
    }

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

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider? = accessibility.takeIf { isEnabled }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        (isEnabled && accessibility.onHover(event)) || super.dispatchHoverEvent(event)

    companion object {
        const val BALLOON_FONT = 34f
        const val ALTERNATES_FONT = 24f
    }
}
