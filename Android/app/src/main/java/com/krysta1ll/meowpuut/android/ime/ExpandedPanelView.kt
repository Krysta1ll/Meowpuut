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
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeProvider
import android.widget.OverScroller
import com.krysta1ll.meowpuut.android.engine.Candidate
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Every candidate of the composition in rows that scroll vertically. Short
 * candidates share a row in quarter widths; long ones get a wider cell.
 * Covers the key rows above the bottom row while the bar is expanded.
 */
@SuppressLint("ViewConstructor")
class ExpandedPanelView(context: Context) : View(context) {
    interface Listener {
        fun onCandidate(index: Int)
        /** Scrolled near the end; the keyboard may append more candidates. */
        fun onNeedMore()
    }

    var listener: Listener? = null
    var look: Look = Look(false, GlassShape.CAPSULE, Palette.ROSE, GlassStrength.BALANCED, true)
        set(value) { field = value; rimShader = null; invalidate() }
    var words = Words(false)
    var candidateTextSize = KeyboardTextSize.STANDARD
        set(value) {
            if (field == value) return
            field = value
            pressed = -1
            scroller.forceFinished(true)
            relayout()
            accessibility.invalidateAll()
            invalidate()
        }

    private var candidates: List<Candidate> = emptyList()
    private var highlighted = 0
    private var japaneseMarks = true
    private var emoji = false
    private val density = resources.displayMetrics.density
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { typeface = Typeface.DEFAULT }
    private val bold = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = if (android.os.Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 500, false) else Typeface.DEFAULT
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pill = PillPainter()
    private val rect = RectF()
    private var rimShader: Shader? = null

    private class Cell(val index: Int, val left: Float, val top: Float, val width: Float, val shown: String, val japanese: Boolean)
    private var cells: List<Cell> = emptyList()
    private var contentHeight = 0f
    private var scroll = 0f
    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop / density
    private var downY = 0f
    private var downScroll = 0f
    private var dragging = false
    private var pressed = -1

    private val accessibility = VirtualAccessibility(this, ::accessibleCells) { id -> listener?.onCandidate(id) }

    fun show(list: List<Candidate>, highlighted: Int, japaneseMarks: Boolean, resetScroll: Boolean, emoji: Boolean = false) {
        if (!resetScroll && candidates === list && this.highlighted == highlighted &&
            this.japaneseMarks == japaneseMarks && this.emoji == emoji) return
        val changed = candidates !== list || this.japaneseMarks != japaneseMarks || this.emoji != emoji
        candidates = list
        this.highlighted = highlighted
        this.japaneseMarks = japaneseMarks
        this.emoji = emoji
        if (resetScroll) { scroll = 0f; scroller.forceFinished(true) }
        if (changed) { pressed = -1; relayout() }
        accessibility.invalidateAll()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rimShader = null
        relayout()
    }

    private fun relayout() {
        val width = this.width / density
        if (width <= 0) return
        val fontSize = candidateTextSize.fontSize(18f, resources.configuration.fontScale)
        text.textSize = fontSize
        bold.textSize = fontSize
        val margin = 10f
        val usable = width - 2 * margin
        val totalColumns = if (emoji && width >= 320f) 6 else 4
        val unit = (usable - (totalColumns - 1) * GAP) / totalColumns
        val mark = fontSize * 0.45f + CandidateBarView.PAW_GAP
        val list = ArrayList<Cell>(candidates.size)
        var x = 0f
        var y = TOP
        var columnsUsed = 0
        for ((i, c) in candidates.withIndex()) {
            val japanese = c.japanese && japaneseMarks
            val natural = bold.measureText(c.text) + 28f + if (japanese) mark else 0f
            val columns = if (emoji) 1 else min(totalColumns, max(1, ceil((natural + GAP) / (unit + GAP)).toInt()))
            if (columnsUsed + columns > totalColumns) { y += ROW; x = 0f; columnsUsed = 0 }
            val w = columns * unit + (columns - 1) * GAP
            val shown = if (!emoji && natural > w) TextUtils.ellipsize(c.text, text, w - 28f - (if (japanese) mark else 0f), TextUtils.TruncateAt.MIDDLE).toString() else c.text
            list += Cell(i, margin + x, y, w, shown, japanese)
            x += w + GAP
            columnsUsed += columns
        }
        cells = list
        contentHeight = y + ROW + TOP
        scroll = scroll.coerceIn(0f, maxScroll)
    }

    private val maxScroll get() = max(0f, contentHeight - height / density)

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.scale(density, density)
        val w = width / density
        val h = height / density
        // An opaque sheet: without a backdrop blur the keys would show through as ghosts.
        rect.set(4f, 2f, w - 4f, h - 2f)
        paint.style = Paint.Style.FILL
        if (look.usesSteppedMaterial) {
            drawPixelPanel(canvas, rect, look.pixelSurface, look.pixelEdge, paint)
        } else {
            paint.color = if (look.dark) Color.rgb(40, 38, 42) else Color.rgb(246, 245, 249)
            canvas.drawRoundRect(rect, 15f, 15f, paint)
            // The rim is lit from the upper left like the bar's, in the same colour.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            paint.shader = rimShader ?: LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                Look.withAlpha(look.rim, if (look.dark) 0.6f else 0.95f), Look.withAlpha(look.rim, if (look.dark) 0f else 0.3f),
                Shader.TileMode.CLAMP).also { rimShader = it }
            canvas.drawRoundRect(rect, 15f, 15f, paint)
            paint.shader = null
            paint.style = Paint.Style.FILL
        }
        canvas.clipRect(rect)
        canvas.translate(0f, -scroll)
        for ((n, cell) in cells.withIndex()) {
            if (cell.top + ROW < scroll || cell.top > scroll + h) continue
            val top = cell.top + 3f
            val cellHeight = ROW - 6f
            val selected = cell.index == highlighted
            if (selected) {
                pill.draw(canvas, cell.left, top, cell.width, cellHeight, look, shadow = false)
            }
            if (n == pressed) {
                paint.color = if (selected) Look.withAlpha(Color.BLACK, 0.16f)
                else Look.withAlpha(look.pill.second, if (look.dark) 0.30f else 0.22f)
                rect.set(cell.left, top, cell.left + cell.width, top + cellHeight)
                if (look.usesSteppedMaterial) canvas.drawRect(rect, paint)
                else canvas.drawRoundRect(rect, cellHeight / 2, cellHeight / 2, paint)
            }
            val p = if (selected) bold else text
            p.color = if (selected) Color.WHITE else look.candidateText
            val textWidth = p.measureText(cell.shown)
            val pawSize = p.textSize * 0.45f
            val markWidth = if (cell.japanese) pawSize + CandidateBarView.PAW_GAP else 0f
            val tx = cell.left + (cell.width - textWidth - markWidth) / 2
            val cy = top + cellHeight / 2
            canvas.drawText(cell.shown, tx, cy - (p.ascent() + p.descent()) / 2, p)
            if (cell.japanese) {
                paint.color = if (selected) Look.withAlpha(Color.WHITE, 0.85f) else look.candidateSecondary
                Glyphs.paw(canvas, tx + textWidth + CandidateBarView.PAW_GAP + pawSize / 2, cy, pawSize, paint)
            }
            // Hairline between rows.
            if (cell.left < 11f && cell.top > TOP) {
                paint.color = Look.withAlpha(if (look.dark) Color.WHITE else Color.BLACK, if (look.dark) 0.08f else 0.06f)
                canvas.drawRect(16f, cell.top - 0.25f, w - 16f, cell.top + 0.25f, paint)
            }
        }
        if (candidates.isEmpty()) {
            text.color = look.candidateSecondary
            val label = words("暫無候選", "暂无候选")
            canvas.drawText(label, (w - text.measureText(label)) / 2, h / 2, text)
        }
        canvas.restore()
        if (scroller.computeScrollOffset()) {
            scroll = (scroller.currY / density).coerceIn(0f, maxScroll)
            maybeNeedMore()
            postInvalidateOnAnimation()
        }
    }

    private fun maybeNeedMore() {
        if (!emoji && scroll > maxScroll - 3 * ROW) listener?.onNeedMore()
    }

    private fun cellAt(x: Float, y: Float): Int {
        val cy = y + scroll
        return cells.indexOfFirst {
            x >= it.left - GAP / 2 && x < it.left + it.width + GAP / 2 && cy >= it.top && cy < it.top + ROW
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x / density
        val y = event.y / density
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(event) }
                downY = y; downScroll = scroll; dragging = false
                pressed = cellAt(x, y)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(event)
                if (!dragging && abs(y - downY) > slop) { dragging = true; pressed = -1 }
                if (dragging) {
                    scroll = (downScroll - (y - downY)).coerceIn(0f, maxScroll)
                    maybeNeedMore()
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
                    val n = cellAt(x, y)
                    if (n >= 0 && n == pressed) listener?.onCandidate(cells[n].index)
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

    private fun accessibleCells(): List<VirtualAccessibility.Item> {
        val h = height / density
        return cells.filter { it.top + ROW >= scroll && it.top <= scroll + h }.map { cell ->
            val top = cell.top - scroll
            VirtualAccessibility.Item(cell.index,
                Rect(((cell.left - GAP / 2) * density).toInt(), (max(0f, top) * density).toInt(),
                    ((cell.left + cell.width + GAP / 2) * density).toInt(), (min(h, top + ROW) * density).toInt()),
                (when {
                    emoji -> words("表情符號：", "表情符号：")
                    cell.japanese -> words("日語候選：", "日语候选：")
                    else -> words("候選：", "候选：")
                }) + candidates[cell.index].text,
                selected = cell.index == highlighted)
        }
    }

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = accessibility

    override fun dispatchHoverEvent(event: MotionEvent): Boolean = accessibility.onHover(event) || super.dispatchHoverEvent(event)

    companion object {
        private const val ROW = 48f
        private const val TOP = 6f
        private const val GAP = 4f
    }
}
