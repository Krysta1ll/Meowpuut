package com.krysta1ll.meowpuut.android.ime

import android.animation.ValueAnimator
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
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeProvider
import com.krysta1ll.meowpuut.android.R
import com.krysta1ll.meowpuut.android.ime.glyph.CapRole
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphCanvasSink
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphColors
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphController
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphKey
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphKeyArt
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphKeyPainter
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphPixelFonts
import com.krysta1ll.meowpuut.android.ime.glyph.KeyCap
import kotlin.math.PI
import kotlin.math.cos
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
        set(value) { if (field != value) { field = value; if (accessibility.assistiveCached) accessibility.invalidateAll() } }

    /** Hides the three left-column punctuation caps when the nine-key syllable rail covers them. */
    var hideLeftMarks = false
        set(value) { if (field != value) { field = value; if (accessibility.assistiveCached) accessibility.invalidateAll(); invalidate() } }

    /** The 字景 world, while that skin is on: it hears every key that goes down. */
    internal var glyph: GlyphController? = null

    /** A reading is being typed: the space bar of 字景 shows it by its shape. */
    var composing = false
        set(value) { if (field != value) { field = value; if (look.isGlyph) invalidate() } }

    private val touches = KeyTouches(this)
    var topTouchExtension: Float
        get() = touches.topTouchExtension
        set(value) { touches.topTouchExtension = value }
    private val density = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pixelPaint = Paint()
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
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
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val groupPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 600, false)
        else Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val tonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.4f
        strokeCap = Paint.Cap.ROUND
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sealPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val cursorHint = context.getString(R.string.cursor_drag_hint)
    private val rect = RectF()
    private val visibleHintBounds = Rect()
    private var hintOpacity = 1f
    private data class LabelLayout(
        val size: Float,
        val baselineOffset: Float,
        val secondarySize: Float = 0f,
        val secondaryOffset: Float = 0f,
    )
    private var labels: List<List<LabelLayout?>> = emptyList()
    private var faceShader: Shader? = null
    private var utilityShader: Shader? = null
    private var accentShader: Shader? = null
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
        if (accessibility.assistiveCached) accessibility.invalidateAll()
        invalidate()
    }

    fun refreshAccessibilityState() {
        accessibility.refreshAssistive()
    }

    /** Before the first layout there is no width to place keys in. */
    private fun place(set: KeySet): List<List<KeyPlacement>> =
        if (width == 0) emptyList() else KeyGrid.place(set.specs, width / density, set.referenceColumns, metrics)

    /** Bounding box (dp, in this view's coordinates) of column 0 across rows 0..2. */
    fun leftColumnBox(widthDp: Float = width / density): Box? {
        val set = keySet ?: return null
        if (widthDp <= 0f || set.specs.size < 3) return null
        val rows = touches.placements.takeIf { it.size >= 3 && width > 0 }
            ?: KeyGrid.place(set.specs, widthDp, set.referenceColumns, metrics)
        val top = rows.getOrNull(0)?.firstOrNull()?.frame ?: return null
        val bottom = rows.getOrNull(2)?.firstOrNull()?.frame ?: return null
        return Box(top.left, top.top, top.width, bottom.bottom - top.top)
    }

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
                val pinyinHint = key.zhuyinPinyinHint
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
                } else if (pinyinHint != null) {
                    layoutZhuyinPair(key, pinyinHint, frame, systemScale, bounds)
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

    private fun layoutZhuyinPair(key: Key, hint: String, frame: Box, systemScale: Float, bounds: Rect): LabelLayout {
        val padding = min(2.8f, frame.height * 0.10f)
        val textWidth = (frame.width - 8f).coerceAtLeast(1f)
        hintPaint.textSize = minOf(keyTextSize.fontSize(8.5f, systemScale), 9.5f, frame.height * 0.26f)
        hintPaint.textSize *= min(1f, textWidth / hintPaint.measureText(hint))
        val hintBaseline = frame.height / 2 - padding - hintPaint.descent()
        val mainTop = -frame.height / 2 + padding
        val mainBottom = hintBaseline + hintPaint.ascent() - 1.6f

        // Keep the stock symbol size until the available space actually needs a reduction.
        labelPaint.textSize = keyTextSize.fontSize(key.fontSize ?: metrics.letterFont, systemScale)
        labelPaint.getTextBounds(key.label, 0, key.label.length, bounds)
        val stockHeight = labelPaint.descent() - labelPaint.ascent()
        val fit = minOf(1f, textWidth / labelPaint.measureText(key.label),
            (frame.height - 4f).coerceAtLeast(1f) / stockHeight,
            (mainBottom - mainTop).coerceAtLeast(1f) / bounds.height().coerceAtLeast(1))
        labelPaint.textSize *= fit
        labelPaint.getTextBounds(key.label, 0, key.label.length, bounds)
        val mainBaseline = (mainTop + mainBottom - bounds.top - bounds.bottom) / 2f
        return LabelLayout(labelPaint.textSize, mainBaseline, hintPaint.textSize, hintBaseline)
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

    // ---------------------------------------------------------------- 字景

    private val glyphPainter by lazy { GlyphKeyPainter(GlyphPixelFonts(context)) }
    private val glyphSink by lazy { GlyphCanvasSink(density, gather = false) }

    /** The vertical middle of each row of keys, in dp from this view's top. */
    internal fun rowCentersDp(): DoubleArray {
        val out = ArrayList<Double>(5)
        for (row in touches.placements) row.firstOrNull()?.let { out += it.frame.centerY.toDouble() }
        return out.toDoubleArray()
    }

    private fun glyphArt(key: Key): String? = when (key.icon) {
        KeyIcon.SHIFT -> "SHIFT"
        KeyIcon.SHIFT_ON -> "SHIFT_ON"
        KeyIcon.CAPS_LOCK -> "CAPS_LOCK"
        KeyIcon.DELETE -> "DELETE"
        KeyIcon.GLOBE -> "GLOBE"
        null -> key.label.takeIf(GlyphKeyArt::hasBits)
    }

    /** The keys of 字景 in the open style: no caps, only legends cut in pixels, over whatever the land is doing behind them. */
    private fun drawGlyphKeys(canvas: Canvas, set: KeySet) {
        val colors = GlyphColors.of(look.dark)
        val cursorMode = touches.cursorMode
        val fingers = touches.active
        glyphSink.begin(canvas)
        glyphSink.eclipse = if (look.opaque) null else glyph?.eclipse(colors, left / density.toDouble(), top / density.toDouble())
        glyphSink.nearCut = if (look.opaque || look.dark) null else glyph?.nearCut(colors, left / density.toDouble(), top / density.toDouble())
        for ((r, row) in touches.placements.withIndex()) {
            val held = if (fingers) touches.pressedColumns(r) else 0
            for (placement in row) {
                if (hideLeftMarks && r in 0..2 && placement.column == 0) continue
                val key = set.rows.getOrNull(r)?.getOrNull(placement.column) ?: continue
                val f = placement.frame
                val pressed = !cursorMode && key.enabled && (held and (1 shl placement.column) != 0 ||
                    r == feedbackRow && placement.column == feedbackColumn)
                val role = when (key.action) {
                    KeyAction.Return -> CapRole.ACCENT
                    KeyAction.Space -> CapRole.SPACE
                    else -> CapRole.PLAIN
                }
                val cap = KeyCap(f.left.toDouble(), f.top.toDouble(), f.width.toDouble(), f.height.toDouble(), key.label,
                    glyphArt(key), role, pressed, enabled = key.enabled && !cursorMode)
                glyphPainter.key(glyphSink, colors, cap, composing, chips = look.opaque)          // the keys are only type, in the street as everywhere
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val set = keySet ?: return
        if (look.isGlyph) {
            drawGlyphKeys(canvas, set)
            return
        }
        hintOpacity = if (set.hasZhuyinPinyinHints && coveredHeight <= 0f && !touches.cursorMode &&
            !look.opaque && look.effects != EffectsLevel.LIGHTWEIGHT && ValueAnimator.areAnimatorsEnabled() &&
            isShown && getLocalVisibleRect(visibleHintBounds)) {
            postInvalidateDelayed(50)
            val phase = (SystemClock.uptimeMillis() % 4_200L) / 4_200.0
            (0.78 + 0.12 * cos(phase * 2 * PI)).toFloat()
        } else if (look.opaque) 1f else 0.88f
        canvas.save()
        canvas.scale(density, density)
        val pixel = look.usesSteppedMaterial
        // A custom skin's keys are painted solid, like the high-contrast setting: its colours are validated as they are.
        val simple = look.effects == EffectsLevel.LIGHTWEIGHT || look.opaque || pixel || look.overlay != null
        val full = look.effects.hasFullEffects && !simple
        val ultra = look.effects == EffectsLevel.ULTRA && !simple
        val glass = (full || look.tintedGlass) && !look.isCentenary
        val pairedRim = !usesLensKeycaps && (ultra || look.tintedGlass)
        if (!simple && shaderHeight != metrics.keyHeight) {
            prepareMaterial(glass, ultra, pairedRim)
        }
        val cursorMode = touches.cursorMode
        val drawRim = !simple && !cursorMode
        val fingers = touches.active
        shadowPaint.color = look.keyShadow
        pressPaint.color = if (look.isCentenary) {
            if (look.dark) Look.withAlpha(0xFFDFBD7A.toInt(), 0.22f) else Look.withAlpha(0xFF4A3518.toInt(), 0.12f)
        } else {
            Look.withAlpha(if (look.dark) Color.WHITE else Color.BLACK, if (look.dark) 0.20f else 0.15f)
        }
        for ((r, row) in touches.placements.withIndex()) {
            val held = if (fingers) touches.pressedColumns(r) else 0
            for (placement in row) {
                if (hideLeftMarks && r in 0..2 && placement.column == 0) continue
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
                    key.style == KeyStyle.ACCENT -> if (look.isCentenary && !simple) facePaint.shader = accentShader else facePaint.color = look.accent
                    key.style == KeyStyle.UTILITY -> if (simple) facePaint.color = look.keyUtility else facePaint.shader = utilityShader
                    simple -> facePaint.color = look.keyFaceTop
                    else -> facePaint.shader = faceShader
                }
                if (cursorMode) facePaint.alpha = 170
                if (facePaint.shader == null && !drawRim) {
                    rect.set(f.left, f.top + depth, f.right, f.bottom + depth)
                    canvas.drawRoundRect(rect, radius, radius, facePaint)
                    if (pressed) canvas.drawRoundRect(rect, radius, radius, pressPaint)
                    if (look.keyRim && key.style != KeyStyle.ACCENT) {
                        // `keys.rim`: the skin's keys are under 3:1 against its deck, so each gets an outline.
                        outlinePaint.color = Look.withAlpha(look.keyText, 0.42f)
                        rect.inset(0.5f, 0.5f)
                        canvas.drawRoundRect(rect, radius - 0.5f, radius - 0.5f, outlinePaint)
                    }
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
            if (look.isCentenary && key.action == KeyAction.Space && layout != null) {
                drawCentenarySeal(canvas, key, frame, layout, depth)
            }
        } else if (key.action == KeyAction.Space) {
            labelPaint.textSize = keyTextSize.fontSize(12f, resources.configuration.fontScale)
            val available = (frame.width - 12f).coerceAtLeast(1f)
            val text = if (labelPaint.measureText(cursorHint) <= available) cursorHint else "←  →"
            labelPaint.color = look.keyText
            val baseline = frame.centerY - (labelPaint.ascent() + labelPaint.descent()) / 2
            canvas.drawText(text, frame.centerX, baseline, labelPaint)
        }
    }

    /** A small maker's seal uses only free space beside the unchanged, centred space-key label. */
    private fun drawCentenarySeal(canvas: Canvas, key: Key, frame: Box, layout: LabelLayout, depth: Float) {
        if (frame.width < 124f || frame.height < 22f) return
        labelPaint.textSize = layout.size
        val sealW = 32f
        val left = frame.left + 9f
        val right = left + sealW
        if (right + 6f > frame.centerX - labelPaint.measureText(key.label) / 2) return
        val centerY = frame.centerY + depth
        rect.set(left, centerY - 8f, right, centerY + 8f)
        if (!look.opaque) {
            sealPaint.style = Paint.Style.FILL
            sealPaint.color = Look.withAlpha(look.tint, if (look.dark) 0.14f else 0.10f)
            canvas.drawRoundRect(rect, 8f, 8f, sealPaint)
        }
        sealPaint.style = Paint.Style.STROKE
        sealPaint.strokeWidth = if (look.opaque) 1f else 0.8f
        sealPaint.color = Look.withAlpha(look.tint, if (look.dark) 0.72f else 0.65f)
        canvas.drawRoundRect(rect, 8f, 8f, sealPaint)
        sealPaint.style = Paint.Style.FILL
        sealPaint.textSize = 9f
        sealPaint.color = if (look.dark) look.tint else 0xFF76542A.toInt()
        canvas.drawText("100", rect.centerX(), centerY - (sealPaint.ascent() + sealPaint.descent()) / 2, sealPaint)
    }

    /** All caps share these local shaders, which are rebuilt on theme or size changes. */
    private fun prepareMaterial(glass: Boolean, ultra: Boolean, pairedRim: Boolean) {
        shaderHeight = metrics.keyHeight
        accentShader = null
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
            accentShader = if (look.isCentenary) {
                LinearGradient(0f, 0f, 0f, shaderHeight, look.selectedPill.first, look.selectedPill.second, Shader.TileMode.CLAMP)
            } else null
            rimPaint.shader = if (look.isCentenary) LinearGradient(0f, 0f, 0f, shaderHeight, intArrayOf(
                Look.withAlpha(look.keyGlassReflection, if (look.dark) 0.85f else 0.92f),
                Look.withAlpha(look.tint, if (look.dark) 0.34f else 0.28f),
                Look.withAlpha(look.tint, if (look.dark) 0.65f else 0.56f),
            ), floatArrayOf(0f, 0.40f, 1f), Shader.TileMode.CLAMP)
            else LinearGradient(0f, 0f, 0f, shaderHeight, intArrayOf(
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
            // The delete outline is 1.18×size plus its stroke; narrow Zhuyin caps still need an inset.
            val size = minOf(metrics.utilityFont * 1.25f, (f.width - 6f).coerceAtLeast(1f) / 1.27f,
                (f.height - 6f).coerceAtLeast(1f) / 1.12f)
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
            digitPaint.textSize = layout.secondarySize
            groupPaint.textSize = layout.size
            digitPaint.color = if (look.opaque) color else Look.withAlpha(color, if (look.dark) 0.82f else 0.72f)
            groupPaint.color = color
            val centerY = f.centerY + depth
            canvas.drawText(digit, f.centerX, centerY + layout.secondaryOffset, digitPaint)
            canvas.drawText(key.label, f.centerX, centerY + layout.baselineOffset, groupPaint)
            return
        }
        labelPaint.textSize = layout.size
        labelPaint.color = color
        canvas.drawText(key.label, f.centerX, f.centerY + layout.baselineOffset + depth, labelPaint)
        key.zhuyinPinyinHint?.let { hint ->
            hintPaint.textSize = layout.secondarySize
            hintPaint.color = Look.withAlpha(color, hintOpacity)
            canvas.drawText(hint, f.centerX, f.centerY + layout.secondaryOffset + depth, hintPaint)
        }
        key.swipeUpTone?.let { tone ->
            tonePaint.color = if (look.opaque) color else Look.withAlpha(color, if (look.dark) 0.82f else 0.72f)
            Glyphs.pinyinTone(canvas, f.right - 7.5f, f.top + min(5.5f, f.height * 0.18f) + depth, tone.digit, tonePaint)
        }
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
                touches.down(
                    event.getPointerId(index),
                    event.getX(index) / density,
                    event.getY(index) / density,
                    event.eventTime,
                )
            }
            MotionEvent.ACTION_MOVE -> {
                val history = event.historySize
                val count = event.pointerCount
                for (h in 0 until history) {
                    val histTime = event.getHistoricalEventTime(h)
                    for (i in 0 until count) {
                        touches.move(
                            event.getPointerId(i),
                            event.getHistoricalX(i, h) / density,
                            event.getHistoricalY(i, h) / density,
                            histTime,
                        )
                    }
                }
                val now = event.eventTime
                for (i in 0 until count) {
                    touches.move(event.getPointerId(i), event.getX(i) / density, event.getY(i) / density, now)
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
        // A tablet darkens the key instead, except for explicit delete-all swipe confirmation.
        if (key.action != KeyAction.Delete && (!showKeyPopups || idiom == Idiom.TABLET)) return
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

    override fun keyDown(key: Key, centerX: Float, centerY: Float) {
        val world = glyph ?: return
        val kind = when {
            key.action == KeyAction.Delete -> GlyphKey.DELETE
            key.action == KeyAction.Space -> GlyphKey.SPACE
            key.action == KeyAction.Return -> GlyphKey.RETURN
            key.isCharacter || key.action == KeyAction.Segment || key.action is KeyAction.PinyinTone -> GlyphKey.LETTER
            else -> GlyphKey.OTHER
        }
        // In the keyboard's own dp: this view sits at (left, top) in it.
        val x = left / density + (if (centerX.isNaN()) 0f else centerX)
        val y = top / density + (if (centerY.isNaN()) 0f else centerY)
        world.key(kind, x, y, composing, key.label.take(1))
    }

    // ---------------------------------------------------------------- accessibility

    private fun accessibleKeys(): List<VirtualAccessibility.Item> {
        val set = keySet ?: return emptyList()
        val out = ArrayList<VirtualAccessibility.Item>()
        for ((r, row) in touches.placements.withIndex()) for (placement in row) {
            if (hideLeftMarks && r in 0..2 && placement.column == 0) continue
            val key = set.rows.getOrNull(r)?.getOrNull(placement.column) ?: continue
            val f = placement.frame
            if (f.top < coveredHeight) continue
            val custom = when {
                key.swipeUpTone != null -> listOf(VirtualAccessibility.LongClick(key.swipeUpTone.spoken) {
                    touches.activateTone(r, placement.column)
                })
                key.action == KeyAction.Delete -> listOf(
                    VirtualAccessibility.LongClick(if (key.spoken == "删除") "删除全部" else "刪除全部") {
                        touches.deleteAll(r, placement.column)
                    },
                )
                else -> emptyList()
            }
            out += VirtualAccessibility.Item(r * 100 + placement.column,
                Rect((f.left * density).toInt(), (f.top * density).toInt(), (f.right * density).toInt(), (f.bottom * density).toInt()),
                key.spoken.ifEmpty { key.label }, enabled = key.enabled,
                longClick = key.holdLabel?.let { VirtualAccessibility.LongClick(it) { touches.hold(r, placement.column) } },
                custom = custom)
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
