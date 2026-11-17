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
        fun onSyllable(syllable: String) {}
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
    private var segmentLength = 0
    private var syllables: List<String> = emptyList()
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

    private class Cell(
        val index: Int, val left: Float, val top: Float, val width: Float, val shown: String,
        val japanese: Boolean, val continues: Boolean,
    )
    private class Chip(val index: Int, val left: Float, val width: Float, val text: String)
    private var cells: List<Cell> = emptyList()
    private var chips: List<Chip> = emptyList()
    private var contentHeight = 0f
    private var chipWidth = 0f
    private var scroll = 0f
    private var chipScroll = 0f
    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop / density
    private var downX = 0f
    private var downY = 0f
    private var downScroll = 0f
    private var inSyllableBar = false
    private var dragging = false
    private var pressed = -1
    private var pressedChip = -1

    private val accessibility = VirtualAccessibility(this, ::accessibleCells) { id ->
        if (id >= CHIP_ID_BASE) syllables.getOrNull(id - CHIP_ID_BASE)?.let { listener?.onSyllable(it) }
        else listener?.onCandidate(id)
    }

    fun show(
        list: List<Candidate>, highlighted: Int, japaneseMarks: Boolean, resetScroll: Boolean,
        emoji: Boolean = false, syllables: List<String> = emptyList(), segmentLength: Int = 0,
    ) {
        val changed = candidates !== list || this.japaneseMarks != japaneseMarks || this.emoji != emoji ||
            this.syllables != syllables || this.segmentLength != segmentLength
        if (!resetScroll && !changed && this.highlighted == highlighted) return
        val syllablesChanged = this.syllables != syllables
        candidates = list
        this.syllables = syllables
        this.highlighted = highlighted
        this.japaneseMarks = japaneseMarks
        this.emoji = emoji
        this.segmentLength = segmentLength
        if (resetScroll) { scroll = 0f; scroller.forceFinished(true) }
        if (resetScroll || syllablesChanged) chipScroll = 0f
        if (changed) { pressed = -1; pressedChip = -1; relayout() }
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
        val hasBar = !emoji && syllables.isNotEmpty()
        if (hasBar) {
            bold.textSize = candidateTextSize.fontSize(14.5f, resources.configuration.fontScale)
            var cx = margin
            chips = syllables.mapIndexed { idx, syl ->
                val cw = max(44f, bold.measureText(syl) + 22f)
                Chip(idx, cx, cw, syl).also { cx += cw + 6f }
            }
            chipWidth = cx + margin - 6f
            bold.textSize = fontSize
        } else {
            chips = emptyList()
            chipWidth = 0f
        }
        val barOffset = if (hasBar) SYLLABLE_BAR else 0f
        val list = ArrayList<Cell>(candidates.size)
        var x = 0f
        var y = TOP + barOffset
        var columnsUsed = 0
        for ((i, c) in candidates.withIndex()) {
            val japanese = c.japanese && japaneseMarks
            val continues = c.continues(segmentLength)
            val label = if (continues) c.text + words("（續）", "（续）") else c.text
            val natural = bold.measureText(label) + 28f + if (japanese) mark else 0f
            val columns = if (emoji) 1 else min(totalColumns, max(1, ceil((natural + GAP) / (unit + GAP)).toInt()))
            if (columnsUsed + columns > totalColumns) { y += ROW; x = 0f; columnsUsed = 0 }
            val w = columns * unit + (columns - 1) * GAP
            val shown = if (!emoji && natural > w) TextUtils.ellipsize(label, text, w - 28f - (if (japanese) mark else 0f), TextUtils.TruncateAt.MIDDLE).toString() else label
            list += Cell(i, margin + x, y, w, shown, japanese, continues)
            x += w + GAP
            columnsUsed += columns
        }
        cells = list
        contentHeight = y + ROW + TOP
        scroll = scroll.coerceIn(0f, maxScroll)
        chipScroll = chipScroll.coerceIn(0f, maxChipScroll)
    }

    private val barOffset get() = if (chips.isNotEmpty()) SYLLABLE_BAR else 0f
    private val maxScroll get() = max(0f, contentHeight - height / density)
    private val maxChipScroll get() = max(0f, chipWidth - width / density)

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
            paint.color = look.overlay?.palette(look.dark)?.paper2 ?: if (look.dark) Color.rgb(40, 38, 42) else Color.rgb(246, 245, 249)
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
        canvas.save()
        rect.set(4f, 2f + barOffset, w - 4f, h - 2f)
        canvas.clipRect(rect)
        canvas.translate(0f, -scroll)
        val firstGridTop = TOP + barOffset
        for ((n, cell) in cells.withIndex()) {
            if (cell.top + ROW < scroll + barOffset || cell.top > scroll + h) continue
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
            p.color = if (selected) look.pillText else look.candidateText
            val textWidth = p.measureText(cell.shown)
            val pawSize = p.textSize * 0.45f
            val markWidth = if (cell.japanese) pawSize + CandidateBarView.PAW_GAP else 0f
            val tx = cell.left + (cell.width - textWidth - markWidth) / 2
            val cy = top + cellHeight / 2
            canvas.drawText(cell.shown, tx, cy - (p.ascent() + p.descent()) / 2, p)
            if (cell.japanese) {
                paint.color = if (selected) Look.withAlpha(look.pillText, 0.85f) else look.candidateSecondary
                Glyphs.paw(canvas, tx + textWidth + CandidateBarView.PAW_GAP + pawSize / 2, cy, pawSize, paint)
            }
            // Hairline between rows.
            if (cell.left < 11f && cell.top > firstGridTop) {
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
        if (chips.isNotEmpty()) drawSyllableBar(canvas, w)
        canvas.restore()
        if (scroller.computeScrollOffset()) {
            scroll = (scroller.currY / density).coerceIn(0f, maxScroll)
            maybeNeedMore()
            postInvalidateOnAnimation()
        }
    }

    private fun drawSyllableBar(canvas: Canvas, w: Float) {
        canvas.save()
        rect.set(5f, 3f, w - 5f, 2f + SYLLABLE_BAR)
        canvas.clipRect(rect)
        paint.color = Look.withAlpha(if (look.dark) Color.WHITE else Color.BLACK, if (look.dark) 0.08f else 0.06f)
        canvas.drawRect(12f, 2f + SYLLABLE_BAR - 0.5f, w - 12f, 2f + SYLLABLE_BAR, paint)
        canvas.translate(-chipScroll, 0f)
        val chipSize = candidateTextSize.fontSize(14.5f, resources.configuration.fontScale)
        val oldBold = bold.textSize
        bold.textSize = chipSize
        val top = 6f
        val ch = SYLLABLE_BAR - 8f
        for ((i, chip) in chips.withIndex()) {
            if (chip.left + chip.width < chipScroll || chip.left > chipScroll + w) continue
            paint.color = when {
                i == pressedChip -> Look.withAlpha(look.pill.second, if (look.dark) 0.34f else 0.24f)
                i == 0 -> Look.withAlpha(look.pill.second, if (look.dark) 0.20f else 0.14f)
                else -> Look.withAlpha(if (look.dark) Color.WHITE else Color.BLACK, if (look.dark) 0.09f else 0.06f)
            }
            rect.set(chip.left, top, chip.left + chip.width, top + ch)
            if (look.usesSteppedMaterial) canvas.drawRect(rect, paint)
            else canvas.drawRoundRect(rect, ch / 2f, ch / 2f, paint)
            bold.color = look.candidateText
            val cy = top + ch / 2f
            canvas.drawText(chip.text, chip.left + (chip.width - bold.measureText(chip.text)) / 2f, cy - (bold.ascent() + bold.descent()) / 2f, bold)
        }
        bold.textSize = oldBold
        canvas.restore()
    }

    private fun maybeNeedMore() {
        if (!emoji && scroll > maxScroll - 3 * ROW) listener?.onNeedMore()
    }

    private fun chipAt(x: Float): Int {
        val cx = x + chipScroll
        return chips.indexOfFirst { cx >= it.left - 3f && cx < it.left + it.width + 3f }
    }

    private fun cellAt(x: Float, y: Float): Int {
        if (y < 2f + barOffset) return -1
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
                downX = x; downY = y; dragging = false
                inSyllableBar = chips.isNotEmpty() && y < 2f + SYLLABLE_BAR
                downScroll = if (inSyllableBar) chipScroll else scroll
                pressed = if (inSyllableBar) -1 else cellAt(x, y)
                pressedChip = if (inSyllableBar) chipAt(x) else -1
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(event)
                val delta = if (inSyllableBar) abs(x - downX) else abs(y - downY)
                if (!dragging && delta > slop) { dragging = true; pressed = -1; pressedChip = -1 }
                if (dragging) {
                    if (inSyllableBar) {
                        chipScroll = (downScroll - (x - downX)).coerceIn(0f, maxChipScroll)
                    } else {
                        scroll = (downScroll - (y - downY)).coerceIn(0f, maxScroll)
                        maybeNeedMore()
                    }
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                velocity?.addMovement(event)
                if (dragging) {
                    if (!inSyllableBar) {
                        velocity?.computeCurrentVelocity(1000)
                        val vy = -(velocity?.yVelocity ?: 0f)
                        scroller.fling(0, (scroll * density).roundToInt(), 0, vy.roundToInt(), 0, 0, 0, (maxScroll * density).roundToInt())
                        postInvalidateOnAnimation()
                    }
                } else if (inSyllableBar) {
                    val idx = chipAt(x)
                    if (idx >= 0 && idx == pressedChip) listener?.onSyllable(chips[idx].text)
                } else {
                    val n = cellAt(x, y)
                    if (n >= 0 && n == pressed) listener?.onCandidate(cells[n].index)
                }
                velocity?.recycle(); velocity = null
                pressed = -1; pressedChip = -1
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                velocity?.recycle(); velocity = null
                pressed = -1; pressedChip = -1
                invalidate()
            }
        }
        return true
    }

    private fun accessibleCells(): List<VirtualAccessibility.Item> {
        val h = height / density
        val w = width / density
        val barItems = chips.filter { it.left + it.width >= chipScroll && it.left <= chipScroll + w }.map { chip ->
            val left = chip.left - chipScroll
            VirtualAccessibility.Item(CHIP_ID_BASE + chip.index,
                Rect((max(0f, left) * density).toInt(), (4f * density).toInt(),
                    (min(w, left + chip.width) * density).toInt(), ((2f + SYLLABLE_BAR) * density).toInt()),
                words("拼音音節：", "拼音音节：") + chip.text, selected = chip.index == 0)
        }
        val gridItems = cells.filter { it.top + ROW >= scroll + barOffset && it.top <= scroll + h }.map { cell ->
            val top = cell.top - scroll
            VirtualAccessibility.Item(cell.index,
                Rect(((cell.left - GAP / 2) * density).toInt(), (max(barOffset, top) * density).toInt(),
                    ((cell.left + cell.width + GAP / 2) * density).toInt(), (min(h, top + ROW) * density).toInt()),
                (when {
                    emoji -> words("表情符號：", "表情符号：")
                    cell.japanese -> words("日語候選：", "日语候选：")
                    else -> words("候選：", "候选：")
                }) + candidates[cell.index].text +
                    if (cell.continues) words("，保留後續讀音", "，保留后续读音") else "",
                selected = cell.index == highlighted)
        }
        return barItems + gridItems
    }

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = accessibility

    override fun dispatchHoverEvent(event: MotionEvent): Boolean = accessibility.onHover(event) || super.dispatchHoverEvent(event)

    companion object {
        private const val ROW = 48f
        private const val TOP = 6f
        private const val GAP = 4f
        private const val SYLLABLE_BAR = 38f
        private const val CHIP_ID_BASE = 100_000
    }
}
