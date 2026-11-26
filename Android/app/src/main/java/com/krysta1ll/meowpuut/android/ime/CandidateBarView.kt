package com.krysta1ll.meowpuut.android.ime

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeProvider
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import com.krysta1ll.meowpuut.android.engine.Candidate
import com.krysta1ll.meowpuut.android.engine.CandidateKind
import com.krysta1ll.meowpuut.android.engine.NativePets
import kotlin.math.PI
import com.krysta1ll.meowpuut.android.ime.glyph.CandidateCap
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphCanvasSink
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphColors
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphController
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphKeyPainter
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphPixelFonts
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphPixelType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The candidate region (DESIGN.md §1–4): glass bar, 米米莫 and 莫莫 with their
 * paws on its top edge, one scrolling row of candidates and the expand key.
 * When nothing is being composed, the row shows predictions or quick
 * punctuation.
 */
@SuppressLint("ViewConstructor")
class CandidateBarView(
    context: Context,
    private val sprites: CatSprites,
    private val catSchedule: CatSchedule = CatSchedule(),
    private val previews: PreviewOverlay? = null,
) : View(context) {
    interface Listener {
        fun onCandidate(index: Int)
        fun onPrediction(text: String)
        fun onPunctuation(mark: String)
        fun onPunctuationTick() {}
        fun onToggleExpanded()
        fun onNeedMore() {}
        fun onPet(cat: Cat)
        /** The cats were touched, typed over or woken: the pets' idle timer starts again. */
        fun onActivity() {}
        /** A dozing cat woke up. */
        fun onCatsWoke() {}
        /** A finger stayed on a cat for 0.5 s without moving: it purrs until [onHoldEnd]. */
        fun onHold(cat: Cat) {}
        /** The finger lifted ([interrupted] false) or began to drag the cat ([interrupted] true). */
        fun onHoldEnd(cat: Cat, interrupted: Boolean) {}
        /** A composition began or ended: typing makes every moment give way. */
        fun onComposing(composing: Boolean) {}
        /** A tap on the seat of a cat that is hiding in the glass. */
        fun onSeat(cat: Cat) {}
        /** Restores text removed by a swipe-up clear-all gesture on Delete. */
        fun onUndoClear() {}
        /** The clipboard slip was tapped: its text is committed. */
        fun onClipSlip() {}
    }

    /** What the row shows; set as a whole by the keyboard. */
    data class Content(
        val candidates: List<Candidate> = emptyList(),
        /** Indices into [candidates] shown in the collapsed row. */
        val visible: List<Int> = emptyList(),
        val highlighted: Int = 0,
        val predictions: List<String> = emptyList(),
        val punctuation: List<String> = emptyList(),
        val status: String = "",
        val expanded: Boolean = false,
        val canExpand: Boolean = false,
        val moreAvailable: Boolean = false,
        val composing: Boolean = false,
        val segmentLength: Int = 0,
        val japaneseMarks: Boolean = true,
        val emoji: Boolean = false,
        val japaneseMode: Boolean = false,
        /** The five touches of 米米莫 switch the way of typing (off in the settings: she is only petted). */
        val mimimoSwitches: Boolean = true,
        val words: Words = Words(false),
        val undoLabel: String? = null,
        /** The clipboard slip, first in the row while nothing is being composed: the text on one line, or dots when it is secret. */
        val clipSlip: String? = null,
        val clipSecret: Boolean = false,
    )

    var listener: Listener? = null
    /** The 字景 world, while that skin is on: it is told when there are candidates, so the land thins round them. */
    internal var glyph: GlyphController? = null
    /** A null cat identifies the equivalent accessibility action rather than a fall. */
    var onDismissKeyboard: ((Cat?) -> Unit)? = null
    var firstKeyTop = Float.POSITIVE_INFINITY
    private class BarEntity(var region: CandidateRegionLayout) {
        var row = region.row
        var chevron = region.chevron
        var content = Content()
        var scroll = 0f
    }

    private val bar = BarEntity(CandidateRegionLayout(393f, Idiom.PHONE))
    var region: CandidateRegionLayout
        get() = bar.region
        set(value) {
            clearPunctuationHold()
            bar.region = value
            caches = null
            lensPaint = null
            seats = null
            bar.row = value.row
            bar.chevron = value.chevron
            layoutRow()
            pressedItem = -1
            scroller.forceFinished(true)
            scroll = scroll.coerceIn(0f, maxScroll)
            invalidate()
        }
    var look: Look = Look(false, GlassShape.CAPSULE, Palette.ROSE, GlassStrength.BALANCED, true)
        set(value) {
            if (field == value) return
            val effectsChanged = field.effects != value.effects
            val catsChanged = field.cats != value.cats || field.petForm != value.petForm ||
                field.resolvedPetForm != value.resolvedPetForm || field.resolvedCatArtwork != value.resolvedCatArtwork
            val skinChanged = field.skin != value.skin
            field = value
            if (effectsChanged || skinChanged) {
                cancelTouch()
                leanAnimator?.cancel()
                lean = 0f
                for (cat in actors) { cat.motion = null; cat.hidden = false }
                rippleStart = -1L
                particles.clear()
            }
            if (effectsChanged || catsChanged || skinChanged) scheduleCatSleep()
            // 字景 sets the candidates in its own type: the widths of the row are measured in it.
            if (skinChanged) { regularWidths.clear(); selectedWidths.clear(); layoutRow() }
            caches = null
            lensPaint = null
            seats = null
            accessibility.invalidateAll()
            invalidate()
        }
    /** Today's festival pictures, or null on an ordinary day. */
    var festival: FestivalArt? = null
        set(value) { if (field?.theme?.id != value?.theme?.id) { field = value; invalidateSeats() } }
    var japaneseMimimo: CatSprites.Art? = null
        set(value) { if (field !== value) { field = value; invalidateSeats() } }
    var candidateTextSize = KeyboardTextSize.STANDARD
        set(value) {
            if (field == value) return
            field = value
            cancelTouch()
            layoutRow()
            scroll = scroll.coerceIn(0f, maxScroll)
            toastText = null
            accessibility.invalidateAll()
            invalidate()
        }
    val content get() = bar.content
    private val candidateRow get() = bar.row
    private val candidateTouch get() = region.candidateTarget(candidateRow)
    private val expandButton get() = bar.chevron

    private val density = resources.displayMetrics.density
    private val fontScale = resources.configuration.fontScale
    private val candidateFont get() = if (look.isGlyph) glyphEm else candidateTextSize.fontSize(region.fontSize, fontScale)
    /** Points to a pixel of the pixel type 字景 sets the candidates in: a whole number, near twice the grid, scaled by the person's text size. */
    private val glyphUnit get() = Math.round(candidateTextSize.fontSize(GLYPH_PIXELS * GLYPH_UNIT, fontScale) / GLYPH_PIXELS).coerceAtLeast(1).toFloat()
    private val glyphEm get() = GLYPH_PIXELS * glyphUnit
    private val statusFont get() = candidateTextSize.fontSize(region.statusFontSize, fontScale)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val selectedPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pawShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pill = PillPainter()
    private val glassPath = Path()
    private val rect = RectF()
    private val dst = RectF()
    private val fadePaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private var fadeLeft: Shader? = null
    private var fadeRight: Shader? = null
    private var fadeKey = 0f
    private val regularWidths = HashMap<String, Float>()
    private val selectedWidths = HashMap<String, Float>()
    private var widthCacheSize = 0f
    private var cachedTimeScale = -1f

    private class Caches(val width: Int, val height: Int, val shadow: Bitmap?, val glass: Bitmap?)
    private var caches: Caches? = null
    internal var lensBackdrop: Shader? = null
        set(value) {
            if (field === value) return
            field = value
            lensPaint = null
            caches = null
            invalidate()
        }
    private var lens: CandidateGlassLens? = null
    private var lensPaint: Paint? = null
    private var lensUnavailable = false

    private fun refractionPaint(canvas: Canvas): Paint? {
        val backdrop = lensBackdrop ?: return null
        if (Build.VERSION.SDK_INT < 33 || !canvas.isHardwareAccelerated || lensUnavailable || region.glass.width < region.glass.height) return null
        if (look.opaque || look.usesSteppedMaterial || look.shape != GlassShape.CAPSULE || look.effects != EffectsLevel.ULTRA) return null
        return lensPaint ?: try {
            val renderer = lens ?: CandidateGlassLens().also { lens = it }
            renderer.configure(backdrop, region.glass, density, look)
            renderer.paint.also { lensPaint = it }
        } catch (failure: IllegalArgumentException) {
            lensUnavailable = true
            android.util.Log.w("MeowpuutGlass", "Candidate refraction shader unavailable", failure)
            null
        }
    }

    // Row items in content coordinates (dp, before scrolling).
    private class Item(
        val kind: Int, val index: Int, val text: String, val shown: String,
        var x: Float, val width: Float, val japanese: Boolean, val continues: Boolean = false,
    )
    private var items: List<Item> = emptyList()
    private var contentWidth = 0f
    private var scroll
        get() = bar.scroll
        set(value) { bar.scroll = value }
    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop / density

    // Touch state.
    private var target = 0
    private var downX = 0f
    private var downY = 0f
    private var downScroll = 0f
    private var dragging = false
    private var pressedItem = -1
    private var pressedCat: Seat? = null
    private class PunctuationHold(val options: List<String>, val strip: AlternatesStrip, var selected: Int = 0)
    private var punctuationHold: PunctuationHold? = null
    private val openPunctuationHold = Runnable {
        val item = items.getOrNull(pressedItem)
        if (target != TARGET_ROW || dragging || item?.kind != 2 || previews == null) return@Runnable
        val options = KeyBuilder.punctuationAlternates(item.text)
        if (options.isEmpty()) return@Runnable
        val key = Box(downX - 17f, region.glass.top, 34f, region.glass.height)
        val strip = KeyBalloon.alternates(key, options.size, region.width, keepOnScreen = true)
        punctuationHold = PunctuationHold(options, strip)
        previews.showAlternates(strip, options, 0, candidateFont, fromBar = true)
        listener?.onPunctuationTick()
    }

    // Cats.
    private class CatEntity(val id: Cat) {
        val affection = CatAffection()
        var motion: CatMotion? = null
        var pressed = false
        var sleeping = false
        var sleepStart = 0L
        /** Sunk into the glass (a peekaboo): its seat finds it. */
        var hidden = false
    }
    private val actors = Cat.entries.map(::CatEntity)
    private fun actor(cat: Cat): CatEntity = actors[cat.ordinal]

    /** The light around 米米莫 in dark appearance: around his body, and around his paws on the glass. */
    private class Glow(val body: Bitmap, val bodyBox: RectF, val paws: Bitmap, val pawsBox: RectF)
    /** A cat in its place. [paws] outlines what of the sprite is in front of the glass. */
    private class Seat(
        val actor: CatEntity, val place: CatPlacement, val touch: Box, val bitmap: Bitmap,
        val paws: Path, val glow: Glow?, val themed: Boolean,
    )
    private var seats: List<Seat>? = null
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { color = Look.withAlpha(0xFFEDD6, 0.6f) }
    private var liftedCat: Seat? = null
    private var catDragX = 0f
    private var catDragY = 0f
    private var catLift = 0f
    private var catLiftStartedAt = -1L
    private var catLiftDuration = 120f
    private var catHeldAt = -1L
    private var catDragTimeScale = 1f
    private var catFall = 0f
    private var catDragAnimator: ValueAnimator? = null
    private var droppingCat = false
    private var presented = false
    /** The cat a finger has held without moving for [CAT_PURR_DELAY]: it purrs until the finger leaves. */
    private var purringCat: CatEntity? = null
    private val liftPressedCat = Runnable {
        pressedCat?.let { if (target == TARGET_CAT) startCatDrag(it) }
    }
    private val holdPressedCat = Runnable {
        val seat = pressedCat ?: return@Runnable
        if (target != TARGET_CAT || liftedCat != null || seat.actor.hidden || droppingCat) return@Runnable
        purringCat = seat.actor
        listener?.onHold(seat.actor.id)
    }
    private val sleepCat = Runnable {
        if (presented && fullEffects && motionEnabled && !content.composing && pressedCat == null) {
            actor(Cat.MIMIMO).apply { sleeping = true; sleepStart = SystemClock.uptimeMillis() }
            invalidate()
        }
    }

    /**
     * The pets core directs the cats (docs/PETS.md): it decides when they doze (25 s, 12 s at night) and a held
     * finger makes a cat purr. False (moments off) keeps the old behaviour: 米米莫 dozes after 16 s and a held cat is lifted.
     */
    var directed = false
        set(value) {
            if (field == value) return
            field = value
            scheduleCatSleep()
        }
    private var rippleStart = -1L
    private var rippleX = 0f
    private var rippleY = 0f
    private var rippleLine = false
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f }
    private var catsAnimating = false
    private var lean = 0f
    private var leanAnimator: ValueAnimator? = null
    private var easterEggAt = -1L
    private val expireEasterEgg = Runnable { easterEggAt = -1L; invalidate() }

    private class Particle(val cat: Cat, val kind: Int, val start: Long, val span: Float, val size: Float, val drift: Float, val color: Int)
    private val particles = ArrayList<Particle>()

    private class Toast(val text: String, val hearts: Int?, val emphasis: Boolean, val start: Long) {
        val duration get() = if (emphasis) 2400L else 1400L
    }
    private var toast: Toast? = null
    private val expireToast = Runnable { toast = null; invalidate() }

    private val accessibility = VirtualAccessibility(this, ::accessibleItems, ::activate)

    private val motionEnabled get() = look.effects != EffectsLevel.LIGHTWEIGHT && ValueAnimator.areAnimatorsEnabled()
    private val fullEffects get() = look.effects.hasFullEffects
    private val animatedToast get() = fullEffects && motionEnabled

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        selectedPaint.typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 500, false) else Typeface.DEFAULT
    }

    fun update(next: Content, retainScroll: Boolean = false) {
        if (next == content) return
        val wasComposing = content.composing
        val newRow = next.candidates != content.candidates || next.visible != content.visible ||
            next.predictions != content.predictions || next.punctuation != content.punctuation ||
            next.japaneseMarks != content.japaneseMarks || next.segmentLength != content.segmentLength ||
            next.undoLabel != content.undoLabel || next.clipSlip != content.clipSlip
        bar.content = next
        if (newRow && next.composing) {
            toast = null
            removeCallbacks(expireToast)
        }
        if (wasComposing != next.composing) {
            scheduleCatSleep()
            listener?.onComposing(next.composing)
            if (fullEffects) animateLean(if (next.composing) 1f else 0f)
            else if (next.composing) for (cat in actors) motion(cat.id, CatMotion.Event.LOOK)
        }
        if (newRow) {
            clearPunctuationHold()
            layoutRow()
            pressedItem = -1
            if (!retainScroll) {
                removeCallbacks(loadMore)
                scroll = 0f
                scroller.forceFinished(true)
            }
        }
        scroll = scroll.coerceIn(0f, maxScroll)
        if (accessibility.assistiveCached) accessibility.invalidateAll()
        glyph?.setCandidates(next.candidates.isNotEmpty() || next.predictions.isNotEmpty())
        invalidate()
    }

    // ---------------------------------------------------------------- layout

    private fun measureText(text: String, paint: Paint): Float {
        if (look.isGlyph) return glyphPainter.candidateWidth(text, GlyphKeyPainter.CandidateFace(GLYPH_PIXELS, glyphUnit.toDouble())).toFloat()
        val size = paint.textSize
        if (size != widthCacheSize || regularWidths.size + selectedWidths.size > 512) {
            regularWidths.clear(); selectedWidths.clear(); widthCacheSize = size
        }
        val map = if (paint === selectedPaint) selectedWidths else regularWidths
        return map.getOrPut(text) { paint.measureText(text) }
    }

    /** Drops offscreen bitmaps and width caches when the IME is trimmed in the background. */
    fun trimCaches() {
        caches = null
        regularWidths.clear()
        selectedWidths.clear()
        cachedTimeScale = -1f
    }

    /** The text cut in the middle to fit [avail] points; in 字景's pixel type it is measured in that type. */
    private fun ellipsizeFor(text: CharSequence, paint: TextPaint, avail: Float): CharSequence {
        if (!look.isGlyph) return TextUtils.ellipsize(text, paint, avail, TextUtils.TruncateAt.MIDDLE)
        val s = text.toString()
        if (measureText(s, paint) <= avail) return s
        for (keep in s.length - 1 downTo 1) {
            val cut = s.take((keep + 1) / 2) + "…" + s.takeLast(keep / 2)
            if (measureText(cut, paint) <= avail) return cut
        }
        return "…"
    }

    private fun layoutRow() {
        val font = candidateFont
        textPaint.textSize = font
        selectedPaint.textSize = font
        val list = ArrayList<Item>()
        var x = 4f
        val gap = 6f
        val mark = font * 0.45f + PAW_GAP
        val c = content
        val viewport = candidateRow.width
        val room = viewport - x
        val maxCell = min(region.maximumCandidateWidth(candidateRow), room.coerceAtLeast(MIN_CANDIDATE_WIDTH))
        c.clipSlip?.takeIf { !c.composing && c.candidates.isEmpty() }?.let { slip ->
            val lead = slipLead(font)
            val cap = min(maxCell, viewport * 0.62f)
            val shown = if (measureText(slip, textPaint) > cap - 22f - lead) ellipsizeFor(slip, textPaint, cap - 22f - lead).toString() else slip
            val width = (measureText(shown, textPaint) + 22f + lead).coerceIn(MIN_CANDIDATE_WIDTH, cap)
            list += Item(4, 0, slip, shown, x, width, false)
            x += width + gap
        }
        if (c.candidates.isNotEmpty()) {
            fun textWidth(text: String) = max(measureText(text, selectedPaint), measureText(text, textPaint))
            // Keep the inline emoji and the leading prefix on the first screen when they fit.
            val emoji = c.visible.getOrNull(1)?.let(c.candidates::getOrNull)?.takeIf { it.kind == CandidateKind.EMOJI }
            val emojiWidth = emoji?.let { (textWidth(it.text) + 24f).coerceIn(MIN_CANDIDATE_WIDTH, maxCell) } ?: 0f
            val emojiSpace = if (emoji != null && room >= MIN_CANDIDATE_WIDTH + gap + emojiWidth) emojiWidth + gap else 0f
            val prefixPosition = c.visible.indexOfFirst {
                val candidate = c.candidates.getOrNull(it)
                candidate?.kind == CandidateKind.CHINESE && candidate.continues(c.segmentLength)
            }
            val reservePrefix = prefixPosition in 1..2 && room >= emojiSpace + 2 * MIN_CANDIDATE_WIDTH + gap
            val prefixSpace = if (reservePrefix) {
                val prefix = c.candidates[c.visible[prefixPosition]]
                val desired = (textWidth(prefix.text + CONTINUATION_MARK) + 24f).coerceIn(MIN_CANDIDATE_WIDTH, maxCell)
                min(desired, room - emojiSpace - MIN_CANDIDATE_WIDTH - gap) + gap
            } else 0f
            val japanesePosition = if (c.japaneseMarks) c.visible.indexOfFirst { c.candidates.getOrNull(it)?.japanese == true } else -1
            val reserveJapanese = japanesePosition > 0 && room >= emojiSpace + prefixSpace + 2 * MIN_CANDIDATE_WIDTH + gap
            val japaneseSpace = if (reserveJapanese) {
                val japanese = c.candidates[c.visible[japanesePosition]]
                val desired = (textWidth(japanese.text) + 24f + mark).coerceIn(MIN_CANDIDATE_WIDTH, min(76f, maxCell))
                min(desired, room - emojiSpace - prefixSpace - MIN_CANDIDATE_WIDTH - gap) + gap
            } else 0f
            val firstCell = min(maxCell, (room - emojiSpace - prefixSpace - japaneseSpace).coerceAtLeast(MIN_CANDIDATE_WIDTH))
            var firstWidth = MIN_CANDIDATE_WIDTH
            for ((position, index) in c.visible.withIndex()) {
                val candidate = c.candidates.getOrNull(index) ?: continue
                val japanese = candidate.japanese && c.japaneseMarks
                val continues = candidate.continues(c.segmentLength)
                val suffix = if (continues) CONTINUATION_MARK else ""
                val cellLimit = when {
                    position == 0 -> firstCell
                    reservePrefix && position == prefixPosition ->
                        min(maxCell, (viewport - x - japaneseSpace).coerceAtLeast(MIN_CANDIDATE_WIDTH))
                    reserveJapanese && position == japanesePosition ->
                        min(maxCell, (room - firstWidth - gap - emojiSpace - prefixSpace).coerceAtLeast(MIN_CANDIDATE_WIDTH))
                    else -> maxCell
                }
                val labelRoom = (cellLimit - 24f - if (japanese) mark else 0f).coerceAtLeast(0f)
                val shown = if (textWidth(candidate.text + suffix) > labelRoom) {
                    val wider = if (measureText(candidate.text, selectedPaint) >= measureText(candidate.text, textPaint)) selectedPaint else textPaint
                    val availableForText = (labelRoom - textWidth(suffix)).coerceAtLeast(0f)
                    val label = ellipsizeFor(candidate.text, wider, availableForText).toString()
                    if (continues && (label.isEmpty() || label == "…")) {
                        ellipsizeFor(candidate.text, wider, labelRoom).toString()
                    } else label + suffix
                } else candidate.text + suffix
                val width = (textWidth(shown) + 24f + if (japanese) mark else 0f).coerceIn(MIN_CANDIDATE_WIDTH, cellLimit)
                if (position == 0) firstWidth = width
                list += Item(0, index, candidate.text, shown, x, width, japanese, continues)
                x += width + gap
            }
            if (reserveJapanese) {
                val japanese = list.firstOrNull { it.japanese }
                if (japanese != null && japanese.x + japanese.width > viewport + 0.01f) {
                    val insertAt = list.indexOfFirst {
                        (!reservePrefix || it.index != c.visible[prefixPosition]) &&
                            it.x + it.width + gap + japanese.width > viewport + 0.01f
                    }
                    if (insertAt >= 0) {
                        list.remove(japanese)
                        list.add(insertAt, japanese)
                        x = 4f
                        for (item in list) {
                            item.x = x
                            x += item.width + gap
                        }
                    }
                }
            }
        } else if (c.predictions.isNotEmpty()) {
            for ((i, text) in c.predictions.withIndex()) {
                val labelRoom = maxCell - 22f
                val shown = if (measureText(text, textPaint) > labelRoom) {
                    ellipsizeFor(text, textPaint, labelRoom).toString()
                } else text
                val width = (measureText(shown, textPaint) + 22f).coerceIn(MIN_CANDIDATE_WIDTH, maxCell)
                list += Item(1, i, text, shown, x, width, false)
                x += width + gap
            }
        } else {
            c.undoLabel?.let { undo ->
                val width = (measureText(undo, selectedPaint) + 22f).coerceIn(MIN_CANDIDATE_WIDTH, maxCell)
                list += Item(3, 0, undo, undo, x, width, false)
                x += width + gap
            }
            for ((i, mark2) in c.punctuation.withIndex()) {
                list += Item(2, i, mark2, mark2, x, 38f, false)
                x += 38f + 2f
            }
        }
        items = list
        contentWidth = list.lastOrNull()?.let { it.x + it.width } ?: 0f
    }

    /** Room the clipboard mark takes before the slip's text (none in 字景, whose slip is a bordered paper). */
    private fun slipLead(font: Float) = if (look.isGlyph) 0f else font * 0.8f + 5f

    private val maxScroll get() = max(0f, contentWidth - candidateRow.width)

    private val loadMore = Runnable {
        if (content.moreAvailable && !content.expanded) listener?.onNeedMore()
    }

    private fun maybeLoadMore() {
        if (content.moreAvailable && !content.expanded && scroll >= maxScroll - candidateRow.width / 2f) {
            removeCallbacks(loadMore)
            post(loadMore)
        }
    }

    private val firstScreen get() = scroll <= 0.01f

    private fun shows(item: Item): Boolean {
        val start = item.x - scroll
        val end = start + item.width
        return if (firstScreen) end <= candidateRow.width + 0.01f else start < candidateRow.width && end > 0f
    }

    private fun rowCenterY(): Float {
        val band = GlassOutline.contentBand(look.shape, region.glass.height)
        return region.glass.top + (band.first + band.second) / 2
    }

    // ---------------------------------------------------------------- drawing

    private fun ensureCaches(needsGlass: Boolean) {
        val w = (width).coerceAtLeast(1)
        val h = (height).coerceAtLeast(1)
        val current = caches
        if (current != null && current.width == w && current.height == h && (!needsGlass || current.glass != null)) return
        val shadow = if (fullEffects && !look.opaque && !look.usesSteppedMaterial) {
            // The keyboard does not clip its children, so the shadow can extend below the bar.
            val below = ((region.glass.bottom + GlassPainter.REACH) * density).toInt()
            val bitmap = Bitmap.createBitmap(w, max(h, below), Bitmap.Config.ARGB_8888)
            val shadowCanvas = Canvas(bitmap)
            shadowCanvas.scale(density, density)
            GlassPainter.shadow(shadowCanvas, look.shape, region.glass, look.dark)
            bitmap
        } else null
        glassPath.outline(look.shape, region.glass)
        val glass = if (needsGlass) paintGlass(w, h) else null
        caches = Caches(w, h, shadow, glass)
    }

    private fun paintGlass(w: Int, h: Int): Bitmap {
        val glass = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val glassCanvas = Canvas(glass)
        glassCanvas.scale(density, density)
        val black = if (look.edgeHuggingCats && look.resolvedCatArtwork == SkinCatArtwork.LEGACY && !look.opaque) {
            seats().firstOrNull { it.actor.id == Cat.MIMIMO }?.place?.frame
        } else null
        if (black != null) {
            val frost = sprites.classicFrost()
            glassCanvas.save()
            glassCanvas.clipPath(glassPath)
            // Keep the dark silhouette at the edge and leave the first word readable.
            bitmapPaint.alpha = if (look.dark) 215 else 100
            glassCanvas.drawBitmap(frost, null, rectOf(Box(black.left, black.top, black.width * 1.25f, black.height * 1.3f)), bitmapPaint)
            bitmapPaint.alpha = 255
            glassCanvas.restore()
        }
        if (look.usesSteppedMaterial) drawPixelPanel(glassCanvas, rectOf(region.glass), look.pixelSurface, look.pixelEdge, pressPaint)
        else GlassPainter.body(glassCanvas, look.shape, region.glass, look)
        return glass
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        caches = null
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val scrolling = scroller.computeScrollOffset()
        if (scrolling) {
            val previousScroll = scroll
            scroll = (scroller.currX / density).coerceIn(0f, maxScroll)
            if (scroll > previousScroll) maybeLoadMore()
        }
        if (look.isGlyph) {
            drawGlyphBar(canvas)
            if (scrolling) postInvalidateOnAnimation()
            return
        }
        val refraction = refractionPaint(canvas)
        ensureCaches(needsGlass = refraction == null)
        val now = SystemClock.uptimeMillis()
        val cache = caches ?: return
        cache.shadow?.let { shadow ->
            dst.set(0f, 0f, width.toFloat(), shadow.height.toFloat())
            canvas.drawBitmap(shadow, null, dst, bitmapPaint)
        }
        if (refraction == null) cache.glass?.let { glass ->
            dst.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawBitmap(glass, null, dst, bitmapPaint)
        }
        canvas.save()
        canvas.scale(density, density)
        if (refraction != null) canvas.drawPath(glassPath, refraction)
        // The cats are in front of the glass and under the candidates.
        val seats = seats()
        if (seats.isEmpty()) for (cat in actors) cat.motion = null
        if (look.supportsFestivals || look.overlay?.rightEnd != null || look.overlay?.leftEnd != null) drawFestivalEnd(canvas)
        if (fullEffects && !look.opaque && look.resolvedCatArtwork == SkinCatArtwork.SCULPTED) pawShadows(canvas, seats)
        catsAnimating = false
        drawRipple(canvas, now)
        for (seat in seats) if (seat.actor !== liftedCat?.actor) drawCat(canvas, seat, now)
        // A note from the cats takes the row's place while it shows.
        drawRow(canvas, 1f - toastAlpha(now))
        drawChevron(canvas)
        drawParticles(canvas, now)
        drawToast(canvas, now)
        drawEasterEgg(canvas, now)
        liftedCat?.let {
            updateCatLift(now)
            drawLiftedCat(canvas, it, now)
        }
        canvas.restore()
        if (scrolling) {
            postInvalidateOnAnimation()
        } else if (catLiftStartedAt >= 0L || catsAnimating || actors.any { val m = it.motion; m != null && !m.settled(now) } ||
            particles.isNotEmpty() || (animatedToast && toast != null) || (motionEnabled && easterEggAt >= 0L)) {
            postInvalidateOnAnimation()
        } else if (catHeldAt >= 0L && catDragAnimator == null && motionEnabled) {
            // Only the held paws animate here. Keep this loop below display
            // refresh rate and stop it as soon as the finger is released.
            postInvalidateDelayed(32L)
        }
    }

    private fun rectOf(box: Box): RectF = rect.apply { set(box.left, box.top, box.right, box.bottom) }

    /** The festival or skin ornament clusters at the bar's ends, over the glass and under the cats and candidates. */
    private fun drawFestivalEnd(canvas: Canvas) {
        leftOrnament?.let { canvas.drawBitmap(it.first, null, rectOf(it.second), bitmapPaint) }
        val ornament = festivalOrnament ?: return
        canvas.drawBitmap(ornament.first, null, rectOf(ornament.second), bitmapPaint)
    }

    private var festivalOrnament: Pair<Bitmap, Box>? = null
    private var leftOrnament: Pair<Bitmap, Box>? = null

    /** Today's festival cluster where the skin wears festivals, else the custom skin's own right end. */
    private fun ornamentPiece(): FestivalPiece? =
        (if (look.supportsFestivals) festival?.theme?.right else null) ?: look.overlay?.rightEnd?.piece()

    private fun ornamentBitmap(): Bitmap? {
        if (look.supportsFestivals && festival?.theme?.right != null) return festival?.right
        return look.overlay?.rightEnd?.let { sprites.endArt(it) }
    }

    /** A custom skin's picture of this pose when it has one, else the built-in sprite's geometry. */
    private fun skinSprite(cat: Cat, pose: CatPose): CatSprite =
        look.overlay?.sprites?.get("${cat.key}_edge_${pose.key}")?.sprite ?: pose.sprite(cat)

    private fun seats(): List<Seat> = seats ?: seatCats().also {
        seats = it
        val piece = ornamentPiece()
        val bitmap = ornamentBitmap()
        festivalOrnament = if (piece == null || bitmap == null) null else {
            val glass = region.glass
            Festivals.placeRight(piece, glass, GlassOutline.contentBand(look.shape, glass.height), region.width,
                expandButton.centerX + CandidateRegionLayout.ARROW_WIDTH / 2)?.let { box -> bitmap to box }
        }
        val leftPiece = look.overlay?.leftEnd
        val leftBmp = leftPiece?.let { sprites.leftEndArt(it) }
        leftOrnament = if (leftPiece == null || leftBmp == null || region.glass.width < 180f || look.opaque) null else {
            val glass = region.glass
            val band = GlassOutline.contentBand(look.shape, glass.height)
            val tallest = (band.second - band.first).coerceAtLeast(1f)
            val k = min(1f, tallest / leftPiece.height)
            val w = leftPiece.width * k
            val h = leftPiece.height * k
            val cy = glass.top + (band.first + band.second) / 2f
            leftBmp to Box(glass.left + 8f, cy - h / 2f, w, h)
        }
    }

    /** [rest]: how the time of day seats the cats (docs/PETS.md A5); null keeps the usual rotation. */
    fun newPresentation(rest: CatRest? = null) {
        presented = true
        scheduleCatSleep()
        catSchedule.newPresentation(rest?.arrangement)
        invalidateSeats()
        val asleep = rest?.mimimoAsleep == true
        for (cat in actors) if (!(asleep && cat.id == Cat.MIMIMO)) motion(cat.id, CatMotion.Event.WAKE)
        // Asleep on appearance; the first touch, commit or composition wakes him as it wakes a dozing cat.
        if (asleep) actor(Cat.MIMIMO).apply { sleeping = true; sleepStart = SystemClock.uptimeMillis() }
    }

    fun endPresentation() {
        presented = false
        scheduleCatSleep()
        cancelTouch()
        for (cat in actors) { cat.motion = null; cat.hidden = false }
        rippleStart = -1L
        particles.clear()
        leanAnimator?.cancel()
        removeCallbacks(expireToast)
        removeCallbacks(expireEasterEgg)
        toast = null
        easterEggAt = -1L
    }

    fun cancelTouch() {
        endTouch()
        resetCatDrag()
    }

    /** Typing or a commit stirs the cats: a sleeper wakes and the old doze timer restarts. */
    fun stirCats() = scheduleCatSleep()

    private fun scheduleCatSleep() {
        var woke = false
        for (cat in actors) if (cat.sleeping) {
            cat.sleeping = false
            woke = true
            // A cat the core put to sleep stretches when it wakes (0.3 s); the old doze simply opened its eyes.
            if (directed) motion(cat.id, CatMotion.Event.WAKE)
        }
        if (woke) {
            invalidate()
            listener?.onCatsWoke()
        }
        removeCallbacks(sleepCat)
        if (!directed && presented && fullEffects && motionEnabled && !content.composing && look.cats) {
            postDelayed(sleepCat, 16_000L)
        }
        listener?.onActivity()
    }

    private fun invalidateSeats() {
        seats = null
        accessibility.invalidateAll()
        invalidate()
    }

    private fun seatCats(): List<Seat> {
        if (!(region.cats && look.cats)) {
            setTargets(region.row, region.chevron)
            return emptyList()
        }
        val stage = region.stage(look.shape)
        val form = look.resolvedPetForm
        val artwork = look.resolvedCatArtwork
        val sculpted = artwork == SkinCatArtwork.SCULPTED
        // The scheduler only needs geometry; decode the sculpted image after seating.
        fun place(mimimo: CatSprites.Art?, momo: CatSprites.Art?) = catSchedule.seats(stage) { cat, pose ->
            val themed = if (cat == Cat.MIMIMO) mimimo else momo
            themed?.sprite ?: if (sculpted) skinSprite(cat, pose) else sprites.art(cat, pose, look.skin, form).sprite
        }
        val mimimo = if (sculpted) japaneseMimimo ?: festival?.mimimo else null
        val momo = if (sculpted) festival?.momo else null
        val chosen = if (look.edgeHuggingCats) {
            CatSeats.ends(stage, Cat.MIMIMO.classicSprite(), Cat.MOMO.classicSprite()).orEmpty()
        } else {
            val themed = place(mimimo, momo) ?: if (japaneseMimimo != null && momo != null) place(mimimo, null) else null
            themed ?: place(null, null).orEmpty()
        }
        val ornament = ornamentPiece()
        val fitted = stage.fitting(chosen, ornament) ?: stage.fitting(chosen) ?: stage
        setTargets(Box(fitted.row.left, region.row.top, fitted.row.width, region.row.height),
            region.chevron.offset(fitted.arrowTouch.left - stage.arrowTouch.left, 0f))
        return chosen.map { place ->
            val themed = if (place.cat == Cat.MIMIMO) mimimo else momo
            val themedArt = themed?.takeIf { it.sprite === place.sprite }
            val art = themedArt ?: if (sculpted || artwork == SkinCatArtwork.CENTENARY) sprites.art(place.sprite)
                else sprites.art(place.cat, CatPose.PEEK, look.skin, form)
            if (!sculpted) return@map Seat(actor(place.cat), place, fitted.petTarget(place), art.bitmap, Path(), null,
                artwork != SkinCatArtwork.CENTENARY)
            val frame = place.frame
            // In front of the glass: each whole paw (a round paw is about 0.7 as tall as it is wide),
            // so an edge that rises under a paw does not cut into it. Nothing else of the cut row: its
            // soft edge would show as a line on the glass. Same outline as CatSprite.pawOutlines on iOS.
            val paws = Path()
            for ((x, halfWidth, depth) in art.sprite.paws) {
                val cx = frame.left + x * frame.width
                val w = 2 * halfWidth * frame.width + 0.8f
                val bottom = place.anchorY + depth * frame.height + 0.4f
                val h = max(2 * depth * frame.height, 0.7f * w) + 0.4f
                paws.addOval(cx - w / 2, bottom - h, cx + w / 2, bottom, Path.Direction.CW)
            }
            val glow = if (fullEffects && !look.opaque && look.dark && place.cat == Cat.MIMIMO) glow(place, art.bitmap, paws) else null
            Seat(actor(place.cat), place, fitted.petTarget(place), art.bitmap, paws, glow, themedArt != null)
        }
    }

    private fun setTargets(row: Box, button: Box) {
        if (row == candidateRow && button == expandButton) return
        clearPunctuationHold()
        bar.row = row
        bar.chevron = button
        layoutRow()
        pressedItem = -1
        scroller.forceFinished(true)
        scroll = scroll.coerceIn(0f, maxScroll)
    }

    /**
     * 米米莫 is black: in dark appearance a warm light around his outline
     * keeps him readable. The sprite's alpha, blurred: once whole, for the
     * body above the glass, and once for the paws alone.
     */
    private fun glow(place: CatPlacement, bitmap: Bitmap, paws: Path): Glow {
        val frame = place.frame
        val dpPerPixel = frame.width / bitmap.width
        val blur = Paint().apply { maskFilter = BlurMaskFilter(GLOW_BLUR / dpPerPixel, BlurMaskFilter.Blur.NORMAL) }
        val offset = IntArray(2)
        fun box(blurred: Bitmap) = RectF(
            frame.left + offset[0] * dpPerPixel, frame.top + offset[1] * dpPerPixel,
            frame.left + (offset[0] + blurred.width) * dpPerPixel, frame.top + (offset[1] + blurred.height) * dpPerPixel,
        )
        val body = bitmap.extractAlpha(blur, offset)
        val bodyBox = box(body)
        val alone = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(alone)
        canvas.scale(1 / dpPerPixel, 1 / dpPerPixel)
        canvas.translate(-frame.left, -frame.top)
        canvas.clipPath(paws)
        canvas.drawBitmap(bitmap, null, RectF(frame.left, frame.top, frame.right, frame.bottom), bitmapPaint)
        val pawGlow = alone.extractAlpha(blur, offset)
        alone.recycle()
        return Glow(body, bodyBox, pawGlow, box(pawGlow))
    }

    /** Two translucent ovals seat the paws without baking a new glass bitmap at each wake. */
    private fun pawShadows(canvas: Canvas, seats: List<Seat>) {
        canvas.save()
        canvas.clipPath(glassPath)
        for (seat in seats) {
            if (seat.actor === liftedCat?.actor) continue
            for (paw in seat.place.paws) {
                pawShadowPaint.color = Look.withAlpha(Color.BLACK, if (look.dark) 0.17f else 0.10f)
                canvas.drawOval(paw.left, paw.bottom - 2f, paw.right, paw.bottom + 2f, pawShadowPaint)
                canvas.drawOval(paw.left + 1.5f, paw.bottom - 1f, paw.right - 1.5f, paw.bottom + 1.5f, pawShadowPaint)
            }
        }
        canvas.restore()
    }

    /**
     * A cat is in front of the glass with its paws on the top edge. Its
     * sprite is cut flat on the paw row, so the body is drawn only outside
     * the glass's outline, where a curved edge hides the cut, and the paws
     * are drawn again over the glass.
     */
    private fun drawCat(canvas: Canvas, seat: Seat, now: Long) {
        val place = seat.place
        val cat = seat.actor
        val motion = cat.motion
        val pose = motion?.pose(now)
        if (motion != null && pose == null) cat.motion = null
        // Sunk out of sight in the glass, or faded out: nothing to draw until it surfaces.
        if (cat.hidden && pose != null && (pose.drop >= 1f || pose.alpha <= 0f)) return
        // A lean toward the candidates while composing, plus the answer to a pet.
        val tilt = lean * (if (cat.id == Cat.MIMIMO) 2.5f else -2.5f) + (pose?.tilt ?: 0f)
        var wide = pose?.wide ?: 1f
        var tall = pose?.tall ?: 1f
        if (cat.pressed && motionEnabled) { wide *= 1.06f; tall *= 0.93f }
        val artwork = look.resolvedCatArtwork
        if (artwork != SkinCatArtwork.SCULPTED || look.edgeHuggingCats) {
            canvas.save()
            if (look.edgeHuggingCats || artwork == SkinCatArtwork.CENTENARY) {
                if (look.edgeHuggingCats && cat.id == Cat.MIMIMO) canvas.clipOutPath(glassPath)
                if (artwork != SkinCatArtwork.CENTENARY || motionEnabled) {
                    canvas.rotate(tilt, place.anchorX, place.anchorY)
                    canvas.scale(wide, tall, place.anchorX, place.anchorY)
                }
            }
            val pixelHop = if (artwork == SkinCatArtwork.PIXEL && cat.pressed && motionEnabled) 1f else 0f
            val fading = pose != null && pose.alpha < 1f
            if (fading) bitmapPaint.alpha = (255 * pose!!.alpha).roundToInt()
            drawSkinCat(canvas, seat, place.frame.offset(0f, -pixelHop), pose?.body, pose?.bodyAlpha ?: 1f)
            if (fading) bitmapPaint.alpha = 255
            canvas.restore()
            if (look.edgeHuggingCats && artwork == SkinCatArtwork.LEGACY && cat.id == Cat.MIMIMO) {
                val tail = sprites.classicTail()
                val height = 24f * region.scale
                canvas.drawBitmap(tail, null, rectOf(Box(place.frame.left + 4f, region.glass.bottom - height + 2f,
                    height * tail.width / tail.height, height)), bitmapPaint)
            }
            return
        }
        // A moment of the pets core changes the pose even when motion is off (Reduce Motion): then it only cross-fades.
        val poses = !seat.themed && (motionEnabled || motion?.isMoment == true || cat.sleeping)
        val body = if (!poses) null else if (cat.sleeping) CatBodyPose.SLEEP else pose?.body
        var sleepAlpha = 1f
        if (cat.sleeping && directed) {
            sleepAlpha = ((now - cat.sleepStart).toFloat() / SLEEP_FADE_MS).coerceIn(0f, 1f)
            if (sleepAlpha < 1f) catsAnimating = true
        }
        val bodyAlpha = if (body == null) 0f else if (cat.sleeping) sleepAlpha else pose?.bodyAlpha ?: 0f
        val whole = pose?.alpha ?: 1f
        val sink = if (pose == null || pose.drop <= 0f) 0f else pose.drop * (place.anchorY - place.visible.top + 3f)
        val customPose = body?.let { sprites.skinPose(cat.id, it) }
        if (customPose != null && bodyAlpha > 0f) {
            if (bodyAlpha < 1f) drawSeatedCat(canvas, seat, tilt, wide, tall, (1f - bodyAlpha) * whole, sink)
            drawCustomWholePose(canvas, seat, customPose, bodyAlpha * whole, tilt, wide, tall)
        } else {
            if (bodyAlpha < 1f) drawSeatedCat(canvas, seat, tilt, wide, tall, (1f - bodyAlpha) * whole, sink)
            if (body != null && bodyAlpha > 0f) drawStandingCat(canvas, seat, body, bodyAlpha * whole, tilt, wide, tall)
        }
    }

    private fun drawSkinCat(canvas: Canvas, seat: Seat, frame: Box, pose: CatBodyPose?, bodyOpacity: Float = 1f) {
        val artwork = look.resolvedCatArtwork
        val bitmap = if (artwork == SkinCatArtwork.CENTENARY) {
            if (seat.actor.sleeping) sprites.centenaryBody(seat.actor.id, CatBodyPose.SLEEP)
            else if (pose != null) sprites.centenaryBody(seat.actor.id, pose) else seat.bitmap
        } else if (artwork == SkinCatArtwork.LEGACY) sprites.classicCat(seat.actor.id,
            happy = pose == CatBodyPose.HAPPY || pose == CatBodyPose.GREET || pose == CatBodyPose.JUMP,
            blink = seat.actor.sleeping) else seat.bitmap
        bitmapPaint.isFilterBitmap = artwork != SkinCatArtwork.PIXEL
        val wholeAlpha = bitmapPaint.alpha
        val mix = if (artwork == SkinCatArtwork.CENTENARY && pose != null && !seat.actor.sleeping) bodyOpacity else 1f
        if (mix < 1f) {
            bitmapPaint.alpha = (wholeAlpha * (1f - mix)).roundToInt()
            canvas.drawBitmap(seat.bitmap, null, rectOf(frame), bitmapPaint)
            bitmapPaint.alpha = (wholeAlpha * mix).roundToInt()
        }
        canvas.drawBitmap(bitmap, null, rectOf(frame), bitmapPaint)
        bitmapPaint.alpha = wholeAlpha
        bitmapPaint.isFilterBitmap = true
    }

    /**
     * [sink] is how far the cat has gone down into the glass, in dp: the cut-out
     * of the glass (behind the bar) hides what is inside it, so it sinks into
     * the glass. Its paws in front of the glass stay only while it is hardly down.
     */
    private fun drawSeatedCat(canvas: Canvas, seat: Seat, tilt: Float, wide: Float, tall: Float, opacity: Float, sink: Float = 0f) {
        val place = seat.place
        val frame = rectOf(place.frame)
        val glow = seat.glow
        val pawsLeft = (1f - sink / SINK_PAWS_DP).coerceIn(0f, 1f)
        bitmapPaint.alpha = (255 * opacity).roundToInt()
        glowPaint.alpha = (153 * opacity).roundToInt()
        canvas.save()
        canvas.clipOutPath(glassPath)
        if (sink > 0f) canvas.translate(0f, sink)
        turn(canvas, place, tilt, wide, tall)
        if (glow != null) canvas.drawBitmap(glow.body, null, glow.bodyBox, glowPaint)
        canvas.drawBitmap(seat.bitmap, null, frame, bitmapPaint)
        canvas.restore()

        if (pawsLeft > 0f) {
            if (sink > 0f) {
                bitmapPaint.alpha = (255 * opacity * pawsLeft).roundToInt()
                glowPaint.alpha = (153 * opacity * pawsLeft).roundToInt()
            }
            if (glow != null) {
                canvas.save()
                canvas.clipPath(glassPath)
                if (sink > 0f) canvas.translate(0f, sink)
                turn(canvas, place, tilt, wide, tall)
                canvas.drawBitmap(glow.paws, null, glow.pawsBox, glowPaint)
                canvas.restore()
            }
            canvas.save()
            if (sink > 0f) canvas.translate(0f, sink)
            turn(canvas, place, tilt, wide, tall)
            canvas.clipPath(seat.paws)
            canvas.drawBitmap(seat.bitmap, null, frame, bitmapPaint)
            canvas.restore()
        }
        bitmapPaint.alpha = 255
        glowPaint.alpha = 153
    }

    private fun drawCustomWholePose(
        canvas: Canvas, seat: Seat, art: CatSprites.Art, opacity: Float, tilt: Float, wide: Float, tall: Float,
    ) {
        val place = seat.place
        val anchorYFrac = art.sprite.anchorY.coerceAtLeast(0.5f)
        val height = min(place.anchorY / anchorYFrac, art.sprite.height * region.scale)
        val width = height * art.sprite.width / art.sprite.height.coerceAtLeast(1f)
        val x = place.visible.centerX.coerceIn(width / 2f, max(width / 2f, region.width - width / 2f))
        val y = place.anchorY - height * anchorYFrac
        dst.set(x - width / 2f, y, x + width / 2f, y + height)
        canvas.save()
        canvas.rotate(tilt * 0.55f, x, place.anchorY)
        canvas.scale(wide, tall, x, place.anchorY)
        bitmapPaint.alpha = (255 * opacity).roundToInt()
        canvas.drawBitmap(art.bitmap, null, dst, bitmapPaint)
        bitmapPaint.alpha = 255
        canvas.restore()
    }

    private fun drawStandingCat(
        canvas: Canvas, seat: Seat, pose: CatBodyPose, opacity: Float, tilt: Float, wide: Float, tall: Float,
    ) {
        val place = seat.place
        val size = min(place.anchorY / 0.91f, 50f * region.scale)
        val x = place.visible.centerX.coerceIn(size / 2, max(size / 2, region.width - size / 2))
        val y = place.anchorY - size * 0.9f
        dst.set(x - size / 2, y, x + size / 2, y + size)
        canvas.save()
        canvas.rotate(tilt * 0.55f, x, place.anchorY)
        canvas.scale(wide, tall, x, place.anchorY)
        bitmapPaint.alpha = (255 * opacity).roundToInt()
        canvas.drawBitmap(sprites.body(seat.actor.id, pose), null, dst, bitmapPaint)
        bitmapPaint.alpha = 255
        canvas.restore()
    }

    private fun drawLiftedCat(canvas: Canvas, seat: Seat, now: Long) {
        val place = seat.place
        val scale = 1f - 0.10f * catLift
        val top = place.anchorY + (place.visible.top - place.anchorY) * scale + catDragY
        val tilt = (catDragX * 0.10f).coerceIn(-11f, 11f) * ((top - 1f) / 10f).coerceIn(0f, 1f) +
            catFall * if (seat.actor.id == Cat.MIMIMO) -9f else 9f
        val tiltedTop = top - place.visible.width * scale / 2 * abs(sin(tilt * PI / 180)).toFloat()
        val rise = catLiftRise(tiltedTop, place.anchorY + catDragY, firstKeyTop, catLift)
        canvas.save()
        canvas.translate(catDragX, catDragY - rise)
        if (motionEnabled) {
            canvas.rotate(tilt, place.anchorX, place.anchorY)
            canvas.scale(scale, scale, place.anchorX, place.anchorY)
        }
        val fade = 1f - 0.6f * ((catFall - 0.7f) / 0.3f).coerceIn(0f, 1f)
        val held = if (!motionEnabled) 0f else if (droppingCat) 1f else catLift
        val sculpted = look.resolvedCatArtwork == SkinCatArtwork.SCULPTED
        if (!sculpted) {
            bitmapPaint.alpha = (255f * fade).roundToInt()
            drawSkinCat(canvas, seat, place.frame, if (look.isCentenary) CatBodyPose.IDLE else CatBodyPose.GREET)
        } else if (held < 1f) {
            bitmapPaint.alpha = (255f * fade * (1f - held)).roundToInt()
            canvas.drawBitmap(seat.bitmap, null, rectOf(place.frame), bitmapPaint)
        }
        if (held > 0f && sculpted) {
            val customGrab = if (!seat.themed) sprites.skinGrab(seat.actor.id) else null
            if (customGrab != null) {
                val maxH = customGrab.sprite.height * region.scale
                val avail = (place.anchorY + catDragY - rise).coerceAtLeast(0f)
                val h = (avail / customGrab.sprite.anchorY.coerceAtLeast(0.2f)).coerceIn(min(24f, maxH), maxH)
                val w = h * customGrab.sprite.width / customGrab.sprite.height.coerceAtLeast(1f)
                val scruffY = place.anchorY - maxH * 0.12f
                val y = scruffY - h * customGrab.sprite.anchorY
                dst.set(place.anchorX - w * customGrab.sprite.anchorX, y, place.anchorX + w * (1f - customGrab.sprite.anchorX), y + h)
                bitmapPaint.alpha = (255f * fade * held).roundToInt()
                canvas.save()
                if (motionEnabled) canvas.rotate(tilt * 0.55f, place.anchorX, scruffY)
                canvas.drawBitmap(customGrab.bitmap, null, dst, bitmapPaint)
                canvas.restore()
            } else {
                val visualAnchor = place.anchorY + catDragY - rise
                val height = (visualAnchor / 0.88f).coerceIn(24f, 63f * region.scale)
                val half = height * 4f / 11f
                val y = place.anchorY - 0.88f * height
                dst.set(place.anchorX - half, y, place.anchorX + half, y + height)
                bitmapPaint.alpha = (255f * fade * held).roundToInt()
                val elapsed = (now - catHeldAt).coerceAtLeast(0L) / catDragTimeScale
                sprites.held(seat.actor.id).draw(canvas, dst, elapsed, held, bitmapPaint)
            }
        }
        bitmapPaint.alpha = 255
        canvas.restore()
    }

    private fun updateCatLift(now: Long) {
        if (catLiftStartedAt < 0L) return
        val elapsed = (now - catLiftStartedAt) / catLiftDuration
        val animate = motionEnabled
        catLift = if (animate) catLiftProgress(elapsed) else 0f
        if (!animate || elapsed >= 1f) catLiftStartedAt = -1L
    }

    /**
     * Turns and scales a cat about its paw anchor. Turned, one end of the
     * flat cut would rise above the glass's edge, so the cat sinks by that
     * much.
     */
    private fun turn(canvas: Canvas, place: CatPlacement, tilt: Float, wide: Float, tall: Float) {
        if (tilt != 0f) {
            canvas.translate(0f, place.reach * abs(sin(tilt * PI / 180)).toFloat())
            canvas.rotate(tilt, place.anchorX, place.anchorY)
        }
        if (wide != 1f || tall != 1f) canvas.scale(wide, tall, place.anchorX, place.anchorY)
    }

    /**
     * Candidates that run past either end of the row fade out there instead
     * of being cut through a character.
     */
    private fun fadeRowEnds(canvas: Canvas, top: Float, bottom: Float) {
        val row = candidateRow
        if (fadeKey != row.left + row.right * 31) {
            fadeKey = row.left + row.right * 31
            fadeLeft = LinearGradient(row.left, 0f, row.left + ROW_FADE, 0f, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            fadeRight = LinearGradient(row.right - ROW_FADE, 0f, row.right, 0f, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        }
        if (scroll > 0.5f) {
            fadePaint.shader = fadeLeft
            canvas.drawRect(row.left, top, row.left + ROW_FADE, bottom, fadePaint)
        }
        if (scroll < maxScroll - 0.5f) {
            fadePaint.shader = fadeRight
            canvas.drawRect(row.right - ROW_FADE, top, row.right, bottom, fadePaint)
        }
    }

    private var lastLabel: Pair<Double, Double>? = null
    private val glyphPainter by lazy { GlyphKeyPainter(GlyphPixelFonts(context)) }
    private val glyphSink by lazy { GlyphCanvasSink(density, gather = false) }

    /** The middle of the bar, in dp from the keyboard's top: where the land's own type gives way to the candidates. */
    internal fun glyphMid(): Float = rowCenterY()

    /**
     * 字景: the candidates stand on the land in the open style (no pill, no glass): pixel type with a paper halo, the chosen one a
     * block of ink. With high contrast the bar is a ruled sheet of paper and the land is gone.
     */
    private fun drawGlyphBar(canvas: Canvas) {
        val c = GlyphColors.of(look.dark)
        val chips = look.opaque
        val row = candidateRow
        val cy = rowCenterY().toDouble()
        val top = region.glass.top - 2
        val bottom = region.glass.bottom + 2
        glyphSink.begin(canvas)
        glyphSink.eclipse = if (chips) null else glyph?.eclipse(c, 0.0, 0.0)
        if (chips) glyphPainter.barSheet(glyphSink, c, region.width.toDouble(), region.glass.top.toDouble(), region.glass.height.toDouble())
        canvas.save()
        canvas.clipRect(row.left * density, top * density, row.right * density, bottom * density)
        val content = content
        val face = GlyphKeyPainter.CandidateFace(GLYPH_PIXELS, glyphUnit.toDouble())
        var labelAt: Pair<Double, Double>? = null
        if (content.status.isNotEmpty() && content.candidates.isEmpty() && content.predictions.isEmpty()) {
            val w = glyphPainter.mask(content.status, GlyphPixelType.EM, 400, false).w.toDouble()
            if (items.isEmpty() || contentWidth + w + 8f <= row.width) {
                glyphPainter.label(glyphSink, c, content.status, row.right - w - 8.0, cy, 1.0, 1.0, centre = false, halo = !chips)
                labelAt = (row.right - w - 8.0) to (row.right - 8.0)
            }
        }
        if (labelAt != lastLabel) {
            lastLabel = labelAt
            glyph?.setBarLabel(labelAt?.first, labelAt?.second ?: 0.0)
        }
        val edges = ArrayList<Double>(8)
        var sig = 0
        for ((n, item) in items.withIndex()) {
            if (!shows(item)) continue
            val selected = item.kind == 0 && item.index == content.highlighted
            val cap = CandidateCap((row.left + item.x - scroll).toDouble(), item.width.toDouble(), item.shown, selected, item.japanese, pressed = n == pressedItem)
            if (item.kind == 4) glyphPainter.slip(glyphSink, c, cap, face, cy, region.pillHeight.toDouble(), chips)
            else glyphPainter.candidate(glyphSink, c, cap, face, cy, region.pillHeight.toDouble(), chips)
            if (item.kind == 0) {          // the candidates themselves: a bird settles on the top of one
                val (l, r) = glyphPainter.candidateSpan(cap, face)
                edges += l; edges += r
                sig = sig * 31 + item.shown.hashCode()
            }
        }
        if (!chips) glyph?.setPerches(sig + edges.size, edges.toDoubleArray(), cy - 10.0)
        canvas.restore()
        if (content.canExpand || content.expanded) {
            glyphPainter.chevron(glyphSink, c, expandButton.centerX.toDouble(), cy, up = content.expanded, halo = !chips)
        }
    }

    private fun drawRow(canvas: Canvas, alpha: Float) {
        if (alpha <= 0.01f) return
        val row = candidateRow
        val cy = rowCenterY()
        val top = region.glass.top - 2
        val bottom = region.glass.bottom + 2
        // A layer only when something has to be faded: an overflowing row, or a note over it.
        val fade = !firstScreen && look.effects != EffectsLevel.LIGHTWEIGHT && maxScroll > 0.5f
        if (fade || alpha < 0.99f) {
            canvas.saveLayerAlpha(row.left, top, row.right, bottom, (alpha * 255).toInt())
        } else {
            canvas.save()
        }
        canvas.clipRect(row.left, top, row.right, bottom)
        val c = content
        val font = textPaint.textSize
        val baseline = cy - (textPaint.ascent() + textPaint.descent()) / 2
        if (items.isEmpty()) {
            if (c.status.isNotEmpty()) {
                textPaint.color = Look.withAlpha(look.candidateSecondary, 0.8f)
                textPaint.textSize = statusFont
                val w = textPaint.measureText(c.status)
                canvas.drawText(c.status, row.right - w - 8f, cy - (textPaint.ascent() + textPaint.descent()) / 2, textPaint)
                textPaint.textSize = font
            }
            canvas.restore()
            return
        }
        for ((n, item) in items.withIndex()) {
            if (!shows(item)) continue
            val x = row.left + item.x - scroll
            val pillTop = cy - region.pillHeight / 2
            val selected = item.kind == 0 && item.index == c.highlighted
            val pressed = n == pressedItem
            if (selected) {
                pill.draw(canvas, x, pillTop, item.width, region.pillHeight, look, shadow = fullEffects)
            }
            if (pressed || item.kind == 1 || item.kind == 3 || item.kind == 4) {
                pressPaint.color = when {
                    selected -> Look.withAlpha(Color.BLACK, 0.16f)
                    pressed -> Look.withAlpha(look.pill.second, if (look.dark) 0.32f else 0.24f)
                    item.kind == 3 || item.kind == 4 -> Look.withAlpha(look.pill.second, if (look.dark) 0.22f else 0.14f)
                    else -> Look.withAlpha(look.pill.second, if (look.dark) 0.14f else 0.10f)
                }
                rect.set(x, pillTop, x + item.width, pillTop + region.pillHeight)
                if (look.usesSteppedMaterial) canvas.drawRect(rect, pressPaint)
                else canvas.drawRoundRect(rect, region.pillHeight / 2, region.pillHeight / 2, pressPaint)
                if ((item.kind == 3 || item.kind == 4) && !look.usesSteppedMaterial) {
                    pressPaint.style = Paint.Style.STROKE
                    pressPaint.strokeWidth = 0.9f
                    pressPaint.color = Look.withAlpha(look.tint, if (look.dark) 0.56f else 0.42f)
                    rect.inset(0.45f, 0.45f)
                    canvas.drawRoundRect(rect, region.pillHeight / 2 - 0.45f, region.pillHeight / 2 - 0.45f, pressPaint)
                    pressPaint.style = Paint.Style.FILL
                }
            }
            val paint = if (selected || item.kind == 3) selectedPaint else textPaint
            paint.color = if (selected) look.pillText else if (item.kind == 2) look.candidateSecondary else look.candidateText
            if (pressed && motionEnabled) {
                canvas.save()
                canvas.scale(0.95f, 0.95f, x + item.width / 2, cy)
            }
            val textWidth = measureText(item.shown, paint)
            val markWidth = if (item.japanese) font * 0.45f + PAW_GAP else 0f
            val lead = if (item.kind == 4) slipLead(font) else 0f
            val tx = x + (item.width - textWidth - markWidth - lead) / 2 + lead
            canvas.drawText(item.shown, tx, baseline, paint)
            if (item.kind == 4) {
                glyphPaint.color = look.candidateSecondary
                Glyphs.clipboard(canvas, tx - lead + font * 0.4f, cy, font * 0.8f, glyphPaint)
            }
            if (item.japanese) {
                glyphPaint.color = if (selected) Look.withAlpha(look.pillText, 0.85f) else look.candidateSecondary
                Glyphs.paw(canvas, tx + textWidth + PAW_GAP + font * 0.225f, cy - font * 0.05f, font * 0.45f, glyphPaint)
            }
            if (pressed && motionEnabled) canvas.restore()
        }
        // Status label at the end of the idle row (punctuation shown on the left).
        if (c.candidates.isEmpty() && c.predictions.isEmpty() && c.status.isNotEmpty()) {
            textPaint.color = Look.withAlpha(look.candidateSecondary, 0.8f)
            textPaint.textSize = statusFont
            val w = textPaint.measureText(c.status)
            // The language label gives way when quick punctuation fills the row.
            if (contentWidth + w + 8f <= row.width) {
                canvas.drawText(c.status, row.right - w - 8f, cy - (textPaint.ascent() + textPaint.descent()) / 2, textPaint)
            }
            textPaint.textSize = font
        }
        if (fade) fadeRowEnds(canvas, top, bottom)
        canvas.restore()
    }

    private fun drawChevron(canvas: Canvas) {
        val c = content
        if (!c.canExpand && !c.expanded) return
        glyphPaint.color = look.candidateText
        glyphPaint.strokeWidth = 2f
        val box = expandButton
        Glyphs.chevron(canvas, box.centerX, rowCenterY(), CandidateRegionLayout.ARROW_WIDTH, up = c.expanded, paint = glyphPaint)
    }

    private fun drawParticles(canvas: Canvas, now: Long) {
        val iterator = particles.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()
            val t = (now - p.start) / p.span
            val box = seats().firstOrNull { it.place.cat == p.cat }?.place?.visible
            if (t >= 1f || box == null) { iterator.remove(); continue }
            // They leave from the side of the head that faces the candidates.
            val cx = if (p.cat == Cat.MIMIMO) box.left + box.width * 0.78f else box.left + box.width * 0.22f
            val ease = 1 - (1 - t) * (1 - t)
            val x = cx + (if (motionEnabled) p.drift * ease else 0f)
            val y = box.top + 6f - (if (motionEnabled) 15f * ease else 4f)
            glyphPaint.color = Look.withAlpha(p.color, 1 - t)
            val size = p.size * (0.4f + 0.6f * ease)
            canvas.save()
            canvas.rotate(p.drift * 1.4f * ease, x, y)
            when (p.kind) {
                0 -> Glyphs.heart(canvas, x, y, size, glyphPaint)
                1 -> Glyphs.paw(canvas, x, y, size, glyphPaint)
                2 -> Glyphs.note(canvas, x, y, size, glyphPaint)
                else -> Glyphs.sparkle(canvas, x, y, size, glyphPaint)
            }
            canvas.restore()
        }
    }

    private fun toastAlpha(now: Long): Float {
        val t = toast ?: return 0f
        val age = now - t.start
        if (age > t.duration) return 0f
        if (!animatedToast) return 1f
        return min(min(1f, age / 180f), min(1f, (t.duration - age) / 200f))
    }

    // The toast's text, shortened once rather than on every frame.
    private var toastText: String? = null
    private var toastTextFor: Toast? = null
    private var toastTextRoom = 0f

    private fun drawToast(canvas: Canvas, now: Long) {
        val t = toast ?: return
        val age = now - t.start
        if (age > t.duration) { toast = null; return }
        val fadeIn = min(1f, age / 180f)
        val alpha = toastAlpha(now)
        val font = candidateFont * 0.75f
        selectedPaint.textSize = font
        val textWidth = selectedPaint.measureText(t.text)
        val hearts = t.hearts
        val iconWidth = if (hearts != null) CatAffection.PETS_TO_SWITCH * 12f + 4f else 18f
        val w = min(candidateRow.width + 20f, textWidth + iconWidth + 30f)
        val h = 30f
        val cx = (candidateRow.left + expandButton.right) / 2
        val cy = rowCenterY()
        val scale = if (animatedToast) 0.86f + 0.14f * fadeIn else 1f
        canvas.save()
        canvas.scale(scale, scale, cx, cy)
        rect.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        pressPaint.color = Look.withAlpha(if (look.dark) Color.rgb(31, 30, 33) else Color.rgb(250, 242, 230), 0.92f * alpha)
        canvas.drawRoundRect(rect, h / 2, h / 2, pressPaint)
        pressPaint.color = Look.withAlpha(look.pill.second, (if (t.emphasis) 0.20f else 0.10f) * alpha)
        canvas.drawRoundRect(rect, h / 2, h / 2, pressPaint)
        var x = rect.left + 13f
        val pink = Color.rgb(237, 107, 128)
        if (hearts != null) {
            for (i in 0 until CatAffection.PETS_TO_SWITCH) {
                glyphPaint.color = Look.withAlpha(if (i < hearts) pink else look.candidateSecondary, alpha * if (i < hearts) 1f else 0.45f)
                Glyphs.heart(canvas, x + 5f, cy, 10f, glyphPaint)
                x += 12f
            }
            x += 4f
        } else {
            glyphPaint.color = Look.withAlpha(pink, alpha)
            Glyphs.heart(canvas, x + 6f, cy, 12f, glyphPaint)
            x += 18f
        }
        selectedPaint.color = Look.withAlpha(look.candidateText, alpha)
        val room = rect.right - 12f - x
        if (toastText == null || toastTextFor !== t || toastTextRoom != room) {
            toastText = TextUtils.ellipsize(t.text, selectedPaint, room, TextUtils.TruncateAt.END).toString()
            toastTextFor = t
            toastTextRoom = room
        }
        canvas.drawText(toastText!!, x, cy - (selectedPaint.ascent() + selectedPaint.descent()) / 2, selectedPaint)
        selectedPaint.textSize = candidateFont
        canvas.restore()
    }

    // ---------------------------------------------------------------- touch

    private fun itemAt(x: Float): Int {
        if (x < candidateRow.left - 4f || x >= candidateRow.right + 4f) return -1
        val contentX = x - candidateRow.left + scroll
        val first = items.firstOrNull()
        return items.indexOfFirst {
            val leftSlop = if (it === first) 6f else 3f
            shows(it) && contentX >= it.x - leftSlop && contentX < it.x + it.width + 3f
        }
    }

    internal fun claimsPoint(x: Float, y: Float): Boolean {
        val contentBottom = region.glass.top + GlassOutline.contentBand(look.shape, region.glass.height).second
        return y <= contentBottom || onExpandHit(x, y) || seats().any { it.touch.contains(x, y) }
    }

    private fun onExpandHit(x: Float, y: Float): Boolean =
        (content.canExpand || content.expanded) && region.expandTarget(expandButton).contains(x, y)

    private fun moveCandidateRow(x: Float) {
        scroll = (downScroll - (x - downX)).coerceIn(0f, maxScroll)
        if (x < downX) maybeLoadMore()
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (droppingCat) return true
        val x = event.x / density
        val y = event.y / density
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                clearPunctuationHold()
                scheduleCatSleep()
                if (liftedCat != null) resetCatDrag()
                pressedCat?.actor?.pressed = false
                removeCallbacks(holdPressedCat)
                removeCallbacks(liftPressedCat)
                endHold(interrupted = true)
                val cats = seats()
                scroller.forceFinished(true)
                velocity?.recycle()
                velocity = null
                downX = x
                downY = y
                downScroll = scroll
                dragging = false
                pressedItem = -1
                val onArrow = onExpandHit(x, y)
                val onRow = candidateTouch.contains(x, y)
                // Seating already keeps the picture clear of visible text and the arrow.
                val pet = cats.firstOrNull { it.touch.contains(x, y) }
                pressedCat = pet
                pet?.actor?.pressed = true
                // Held still for half a second, the cat purrs; a drag lifts it at once (docs/PETS.md A2).
                // With moments off, a held cat is lifted after 0.32 s as before.
                if (pet != null && onDismissKeyboard != null && !pet.actor.hidden) {
                    if (directed) postDelayed(holdPressedCat, CAT_PURR_DELAY) else postDelayed(liftPressedCat, CAT_HOLD_DELAY)
                }
                target = when {
                    pet != null -> TARGET_CAT
                    onArrow -> TARGET_CHEVRON
                    onRow -> TARGET_ROW
                    else -> TARGET_NONE
                }
                if (target == TARGET_ROW) {
                    velocity = VelocityTracker.obtain().also { it.addMovement(event) }
                    pressedItem = if (toastAlpha(SystemClock.uptimeMillis()) > 0f) -1 else itemAt(x)
                    if (previews != null && items.getOrNull(pressedItem)?.kind == 2) {
                        postDelayed(openPunctuationHold, KeyTouches.ALTERNATES_DELAY)
                    }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(event)
                val cat = pressedCat
                if (cat != null && onDismissKeyboard != null && !cat.actor.hidden) {
                    val dx = x - downX
                    val dy = y - downY
                    if (liftedCat != null || abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        if (liftedCat == null) startCatDrag(cat)
                        moveLiftedCat(cat, dx, dy)
                        invalidate()
                    }
                } else if (target == TARGET_ROW) {
                    punctuationHold?.let {
                        val index = it.strip.indexAt(x)
                        if (index != it.selected) {
                            it.selected = index
                            previews?.selectAlternate(index, fromBar = true)
                            listener?.onPunctuationTick()
                        }
                        return true
                    }
                    if (abs(x - downX) > touchSlop || abs(y - downY) > touchSlop) removeCallbacks(openPunctuationHold)
                    if (!dragging && abs(x - downX) > touchSlop) {
                        dragging = true
                        pressedItem = -1
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (dragging) moveCandidateRow(x)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                velocity?.addMovement(event)
                when (target) {
                    TARGET_ROW -> if (punctuationHold != null) {
                        punctuationHold?.let { listener?.onPunctuation(it.options[it.selected]) }
                    } else if (dragging || abs(x - downX) > touchSlop) {
                        moveCandidateRow(x)
                        velocity?.computeCurrentVelocity(1000)
                        val vx = -(velocity?.xVelocity ?: 0f)
                        scroller.fling((scroll * density).roundToInt(), 0, vx.roundToInt(), 0, 0, (maxScroll * density).roundToInt(), 0, 0)
                        postInvalidateOnAnimation()
                    } else if (toastAlpha(SystemClock.uptimeMillis()) > 0f) {
                        // The note hides the row: a tap only puts the row back.
                        toast = null
                    } else {
                        val pressed = items.getOrNull(pressedItem)
                        if (pressed != null && candidateTouch.contains(x, y) && seats().none { it.touch.contains(x, y) }) {
                            val stayedNearDown = abs(x - downX) <= touchSlop && abs(y - downY) <= touchSlop
                            if (itemAt(x) == pressedItem || (pressed.kind != 2 && stayedNearDown)) activateItem(pressed)
                        }
                    }
                    TARGET_CHEVRON -> if (onExpandHit(x, y)) listener?.onToggleExpanded()
                    TARGET_CAT -> {
                        val dx = x - downX
                        val dy = y - downY
                        val seat = liftedCat
                        if (seat != null) {
                            moveLiftedCat(seat, dx, dy)
                            val falls = !seat.place.supportedBy(firstKeyTop, dy)
                            finishCatDrag(falls = falls)
                        } else if (purringCat != null) {
                            // A purr is not a pet: it does not count toward the five that switch the script.
                            endHold(interrupted = false)
                        } else if (abs(dx) <= touchSlop && abs(dy) <= touchSlop) {
                            pressedCat?.let { if (it.actor.hidden) listener?.onSeat(it.actor.id) else listener?.onPet(it.actor.id) }
                        }
                    }
                }
                endTouch()
                return true
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && pressedCat == null) {
                    clearPunctuationHold()
                    return true
                }
                if (liftedCat != null) finishCatDrag(falls = false)
                endTouch()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun endTouch() {
        clearPunctuationHold()
        removeCallbacks(holdPressedCat)
        removeCallbacks(liftPressedCat)
        endHold(interrupted = false)
        velocity?.recycle()
        velocity = null
        pressedItem = -1
        pressedCat?.actor?.pressed = false
        pressedCat = null
        target = TARGET_NONE
        invalidate()
    }

    private fun clearPunctuationHold() {
        removeCallbacks(openPunctuationHold)
        if (punctuationHold != null) previews?.hideAlternates(fromBar = true)
        punctuationHold = null
    }

    private fun moveLiftedCat(seat: Seat, horizontal: Float, vertical: Float) {
        val x = seat.place.anchorX
        val y = seat.place.anchorY
        catDragX = horizontal.coerceIn(-x - 12f, region.width - x + 12f)
        catDragY = vertical.coerceIn(-y - 8f, region.height - y + 36f)
    }

    private fun startCatDrag(seat: Seat) {
        removeCallbacks(holdPressedCat)
        removeCallbacks(liftPressedCat)
        endHold(interrupted = true)
        liftedCat = seat
        seat.actor.motion = null
        if (motionEnabled) {
            catDragTimeScale = timeScale()
            catHeldAt = SystemClock.uptimeMillis()
            catLiftStartedAt = catHeldAt
            catLiftDuration = 120f * catDragTimeScale
        }
        translationZ = density
        parent?.requestDisallowInterceptTouchEvent(true)
        for (cat in actors) cat.affection.resetStreak()
        invalidate()
    }

    private fun finishCatDrag(falls: Boolean) {
        val seat = liftedCat ?: return
        if (!motionEnabled) {
            resetCatDrag()
            if (falls) onDismissKeyboard?.invoke(seat.actor.id)
            return
        }
        droppingCat = falls
        updateCatLift(SystemClock.uptimeMillis())
        catLiftStartedAt = -1L
        val fromLift = catLift
        val fromX = catDragX
        val fromY = catDragY
        val toX = if (falls) fromX else 0f
        val keyboardBottom = (parent as? View)?.height?.div(density) ?: (region.height + 48f)
        val toY = if (falls) max(fromY + 54f, keyboardBottom + seat.place.reach - seat.place.anchorY) else 0f
        catDragAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (falls) 250L else (160f + 0.23f * abs(fromX) + 0.2f * abs(fromY)).coerceAtMost(240f).toLong()
            interpolator = if (falls) AccelerateInterpolator(1.3f) else DecelerateInterpolator(1.3f)
            addUpdateListener {
                val fraction = it.animatedValue as Float
                catDragX = fromX + (toX - fromX) * fraction
                catDragY = fromY + (toY - fromY) * fraction
                catLift = fromLift * (1f - fraction)
                if (falls) catFall = fraction
                if (fraction == 1f) {
                    catDragAnimator = null
                    if (falls) {
                        onDismissKeyboard?.invoke(seat.actor.id)
                    } else {
                        val hadCustomGrab = look.resolvedCatArtwork == SkinCatArtwork.SCULPTED && !seat.themed && sprites.skinGrab(seat.actor.id) != null
                        resetCatDrag()
                        if (hadCustomGrab) moment(seat.actor.id, CatMotion.Event.FOUND, fade = false)
                    }
                }
                invalidate()
            }
            start()
        }
    }

    fun resetCatDrag() {
        catDragAnimator?.cancel()
        catDragAnimator = null
        droppingCat = false
        liftedCat = null
        catDragX = 0f
        catDragY = 0f
        catLift = 0f
        catLiftStartedAt = -1L
        catHeldAt = -1L
        catFall = 0f
        translationZ = 0f
        invalidate()
    }

    private fun activateItem(item: Item) {
        when (item.kind) {
            0 -> listener?.onCandidate(item.index)
            1 -> listener?.onPrediction(item.text)
            2 -> listener?.onPunctuation(item.text)
            3 -> listener?.onUndoClear()
            4 -> listener?.onClipSlip()
        }
    }

    // ---------------------------------------------------------------- cats

    private var lastCentenaryCheerAt = -1400L

    /** A short response when a candidate is committed. */
    fun committed() {
        scheduleCatSleep()
        if (look.isCentenary) {
            val now = SystemClock.uptimeMillis()
            if (now - lastCentenaryCheerAt < 1400L) return
            lastCentenaryCheerAt = now
            for (cat in actors) motion(cat.id, CatMotion.Event.CENTENARY_CHEER)
        } else for (cat in actors) motion(cat.id, CatMotion.Event.COMMIT)
    }

    /** A pet advances only the touched cat's streak. */
    fun pet(cat: Cat, simplified: Boolean, nextInputName: String? = null): CatAffection.Outcome {
        val now = SystemClock.uptimeMillis()
        scheduleCatSleep()
        val mimimo = cat == Cat.MIMIMO
        val tracker = actor(cat).affection
        for (other in actors) if (other.id != cat) other.affection.resetStreak()
        if (mimimo) {
            emit(Cat.MIMIMO, (1..3).random(), 10f, (4..10).random().toFloat(), Color.rgb(219, 168, 61))
        }
        val outcome = tracker.pet(now)
        motion(cat, if (outcome == CatAffection.Outcome.Happy) CatMotion.Event.CELEBRATE else CatMotion.Event.PET)
        when (outcome) {
            is CatAffection.Outcome.Pleased -> {
                if (!mimimo) emit(Cat.MOMO, 0, 8f + outcome.count * 1.5f, -(4..10).random().toFloat(), Color.rgb(242, 115, 140))
                if (outcome.count >= 2) {
                    val left = CatAffection.PETS_TO_SWITCH - outcome.count
                    val target = if (mimimo) nextInputName else if (simplified) "繁體中文" else "简体中文"
                    if (target != null) {
                        val text = Words(simplified)("再摸 $left 下，切換到$target", "再摸 $left 下，切换到$target")
                        showToast(Toast(text, outcome.count, false, now))
                    }
                }
            }
            CatAffection.Outcome.Happy -> {
                if (!mimimo) {
                    for (i in 0 until 3) emit(Cat.MOMO, if (i == 1) 3 else 0, 10f + i * 2, -(4 + i * 5).toFloat(), Color.rgb(242, 115, 140))
                    motion(Cat.MIMIMO)
                }
            }
            CatAffection.Outcome.Resting -> if (!mimimo) emit(Cat.MOMO, 0, 9f, -(4..10).random().toFloat(), Color.rgb(242, 115, 140))
        }
        invalidate()
        return outcome
    }

    // ---------------------------------------------------------------- moments

    /** Whether the cats sit behind the glass bar with its cut-out, so that sinking works. Classic and pixel skins fade instead. */
    private val glassSeated get() = look.resolvedCatArtwork == SkinCatArtwork.SCULPTED && !look.edgeHuggingCats

    /** The cats on screen as bits (MP_PETS_MIMIMO, MP_PETS_MOMO). */
    val petCats: Int get() = if (region.cats && look.cats) NativePets.MIMIMO or NativePets.MOMO else 0

    /** What the pets core needs to know of the bar: MPPetsAmbience.flags, without the assistive-technology bit. */
    fun petFlags(): Int {
        var flags = 0
        if (content.composing) flags = flags or NativePets.COMPOSING
        if (liftedCat != null || purringCat != null) flags = flags or NativePets.LIFTED
        if (droppingCat) flags = flags or NativePets.DISMISSING
        if (look.effects == EffectsLevel.LIGHTWEIGHT) flags = flags or NativePets.LIGHTWEIGHT
        if (!ValueAnimator.areAnimatorsEnabled()) flags = flags or NativePets.REDUCE_MOTION
        if (look.opaque) flags = flags or NativePets.REDUCE_TRANSPARENCY
        if (glassSeated) flags = flags or NativePets.GLASS_SEAT or NativePets.EDGES
        if (look.skin.supportsFestivals && festival != null) flags = flags or NativePets.FESTIVAL
        return flags
    }

    /** TalkBack, Switch Access or another assistive service is running. */
    val assistiveTechnology: Boolean get() = accessibility.assistive

    fun refreshAccessibilityState() {
        accessibility.refreshAssistive()
    }

    /** Whether the cat is sunk out of sight in the glass. */
    fun isHidden(cat: Cat) = actor(cat).hidden

    private fun hasCat(who: Int, cat: Cat) = who and (if (cat == Cat.MIMIMO) NativePets.MIMIMO else NativePets.MOMO) != 0

    /** One moment's motion on one cat; a cat without body poses (a festival outfit) hops instead of changing pose. */
    private fun moment(cat: Cat, event: CatMotion.Event, fade: Boolean, quick: Boolean = false, delay: Long = 0L) {
        val a = actor(cat)
        if (!(region.cats && look.cats) || a === liftedCat?.actor) return
        var shown = event
        if (!fade && (event == CatMotion.Event.HELLO || event == CatMotion.Event.DUET)) {
            val seat = seats().firstOrNull { it.actor === a }
            if (seat?.themed == true) shown = CatMotion.Event.HOP
        }
        a.motion = CatMotion(cat, SystemClock.uptimeMillis() + delay, timeScale(), shown, fade, quick, waits = delay > 0L)
        invalidate()
    }

    /** The first appearance of the day: 莫莫 waves and 米米莫 reaches a beat later. */
    fun playHello(who: Int, fade: Boolean) {
        if (hasCat(who, Cat.MOMO)) moment(Cat.MOMO, CatMotion.Event.HELLO, fade)
        if (hasCat(who, Cat.MIMIMO)) moment(Cat.MIMIMO, CatMotion.Event.HELLO, fade, delay = 150L)
    }

    /** A peekaboo: the cats in [who] sink into the glass (a fade where there is no glass seat) and a ripple spreads where they went. */
    fun sink(who: Int, fade: Boolean, ripple: Int) {
        val now = SystemClock.uptimeMillis()
        var other: Cat? = null
        for (cat in Cat.entries) {
            val a = actor(cat)
            if (!hasCat(who, cat)) { other = cat; continue }
            a.hidden = true
            moment(cat, CatMotion.Event.SINK, fade)
            val seat = seats().firstOrNull { it.actor === a } ?: continue
            if (!fade && ripple != NativePets.RIPPLE_NONE && fullEffects) {
                rippleStart = now
                rippleX = seat.place.anchorX
                rippleY = seat.place.anchorY + 2.5f
                rippleLine = ripple == NativePets.RIPPLE_LINE
            }
        }
        // The other cat leans, as if looking for it.
        if (other != null && !fade && fullEffects) animateLean(1f)
        invalidate()
    }

    /** Hiding cats come back up by themselves ([quick]: typing, within 0.18 s). */
    fun surface(fade: Boolean, quick: Boolean = false) {
        for (cat in Cat.entries) if (actor(cat).hidden) {
            actor(cat).hidden = false
            moment(cat, CatMotion.Event.SURFACE, fade, quick)
        }
        leanBack()
    }

    /** The hiding cats were found: they pop up and are happy. */
    fun found(who: Int, fade: Boolean) {
        for (cat in Cat.entries) if (hasCat(who, cat) || actor(cat).hidden) {
            actor(cat).hidden = false
            moment(cat, CatMotion.Event.FOUND, fade)
        }
        leanBack()
    }

    /** The cats in [who] fall asleep until a key or a touch (the pets core's doze). */
    fun doze(who: Int) {
        if (!(region.cats && look.cats)) return
        val now = SystemClock.uptimeMillis()
        for (cat in Cat.entries) if (hasCat(who, cat)) actor(cat).apply { sleeping = true; sleepStart = now }
        invalidate()
    }

    /** 莫莫 jumps and 米米莫 is happy, together. */
    fun playDuet(fade: Boolean) {
        moment(Cat.MOMO, CatMotion.Event.DUET, fade)
        moment(Cat.MIMIMO, CatMotion.Event.DUET, fade)
    }

    /** A cat held under a finger purrs: 米米莫 sleeps and 莫莫 is happy (momo_happy stands in for the missing momo_sleep). */
    fun purr(cat: Cat, fade: Boolean) = moment(cat, CatMotion.Event.HOLD, fade)

    /** The finger left (or began a drag): the purr fades. */
    fun endPurr(cat: Cat) {
        actor(cat).motion?.release(SystemClock.uptimeMillis())
        invalidate()
    }

    /** Typing or a touch elsewhere: every moment ends within 0.18 s. */
    fun yieldMoments(fade: Boolean) {
        val now = SystemClock.uptimeMillis()
        surface(fade, quick = true)
        for (a in actors) a.motion?.let { if (it.isMoment) it.cut(now) }
        rippleStart = -1L
        scheduleCatSleep()
        invalidate()
    }

    /** The bar is going away (or its look changed): nothing is left hiding. */
    fun cancelMoments() {
        for (a in actors) { a.hidden = false; if (a.motion?.isMoment == true) a.motion = null }
        rippleStart = -1L
        leanBack()
    }

    private fun leanBack() {
        if (lean != 0f && !content.composing) animateLean(0f)
    }

    private fun endHold(interrupted: Boolean) {
        val cat = purringCat ?: return
        purringCat = null
        listener?.onHoldEnd(cat.id, interrupted)
    }

    /** Two soft rings (a thin opaque line for Reduce Transparency) spreading over the glass where a cat sank, for 0.5 s. */
    private fun drawRipple(canvas: Canvas, now: Long) {
        if (rippleStart < 0L) return
        val age = (now - rippleStart).toFloat() / timeScale()
        if (age >= RIPPLE_MS) { rippleStart = -1L; return }
        canvas.save()
        canvas.clipPath(glassPath)
        for (ring in 0..1) {
            val u = ((age - ring * 120f) / (RIPPLE_MS - 120f)).coerceIn(0f, 1f)
            if (age < ring * 120f) continue
            val radius = 3f + 15f * u
            if (rippleLine) {
                ripplePaint.color = look.rippleLine
                ripplePaint.strokeWidth = 1f
            } else {
                ripplePaint.color = Look.withAlpha(look.rippleSoft, 0.55f * (1f - u))
                ripplePaint.strokeWidth = 1.2f
            }
            canvas.drawOval(rippleX - radius, rippleY - radius * 0.4f, rippleX + radius, rippleY + radius * 0.4f, ripplePaint)
        }
        canvas.restore()
        catsAnimating = true
    }

    fun playEasterEgg() {
        for (actor in actors) actor.affection.resetStreak()
        easterEggAt = SystemClock.uptimeMillis()
        removeCallbacks(expireEasterEgg)
        postDelayed(expireEasterEgg, EGG_DURATION)
        invalidate()
    }

    private fun drawEasterEgg(canvas: Canvas, now: Long) {
        if (easterEggAt < 0L) return
        val age = now - easterEggAt
        if (age >= EGG_DURATION) return
        val px = 1.5f
        val travel = if (motionEnabled) age / EGG_DURATION.toFloat() else 0.5f
        val x = candidateRow.left + (candidateRow.width - PIXEL_CAT[0].length * px).coerceAtLeast(0f) * travel
        val y = region.glass.top - PIXEL_CAT.size * px - if (motionEnabled && age / 90L % 2L == 0L) px else 0f
        glyphPaint.style = Paint.Style.FILL
        glyphPaint.color = 0xFFEF8D61.toInt()
        for ((r, row) in PIXEL_CAT.withIndex()) for ((c, point) in row.withIndex()) {
            if (point == '#') canvas.drawRect(x + c * px, y + r * px, x + (c + 1) * px, y + (r + 1) * px, glyphPaint)
        }
    }

    /** Shown after 莫莫 switches the script. */
    fun announce(text: String) {
        showToast(Toast(text, null, true, SystemClock.uptimeMillis()))
        announceForAccessibilityCompat(text)
    }

    private fun showToast(note: Toast) {
        toast = note
        removeCallbacks(expireToast)
        postDelayed(expireToast, note.duration)
        invalidate()
    }

    @Suppress("DEPRECATION")
    private fun announceForAccessibilityCompat(text: String) = announceForAccessibility(text)

    private fun motion(cat: Cat, event: CatMotion.Event = CatMotion.Event.PET) {
        val catActor = actor(cat)
        if (!motionEnabled || !(region.cats && look.cats) || catActor === liftedCat?.actor) return
        val reaction = if (look.isCentenary) when (event) {
            CatMotion.Event.PET -> CatMotion.Event.CENTENARY_BOW
            CatMotion.Event.CELEBRATE -> CatMotion.Event.CENTENARY_CHEER
            else -> event
        } else event
        catActor.motion = CatMotion(cat, SystemClock.uptimeMillis(), timeScale(), reaction)
        invalidate()
    }

    private fun emit(cat: Cat, kind: Int, size: Float, drift: Float, color: Int) {
        if (!fullEffects || !motionEnabled) return
        particles += Particle(cat, kind, SystemClock.uptimeMillis(), 900f * timeScale(), size, drift, color)
        while (particles.size > 8) particles.removeAt(0)
    }

    /** The system's animator duration scale (developer options); 1 when animations are off. */
    private fun timeScale(): Float {
        if (Build.VERSION.SDK_INT >= 33) {
            val scale = ValueAnimator.getDurationScale()
            return if (scale > 0f) scale else 1f
        }
        if (cachedTimeScale > 0f) return cachedTimeScale
        val scale = android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        cachedTimeScale = if (scale > 0f) scale else 1f
        return cachedTimeScale
    }

    private fun animateLean(to: Float) {
        leanAnimator?.cancel()
        if (!motionEnabled) { lean = to; return }
        leanAnimator = ValueAnimator.ofFloat(lean, to).apply {
            duration = 320
            addUpdateListener { lean = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(loadMore)
        endPresentation()
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------------- accessibility

    private fun accessibleItems(): List<VirtualAccessibility.Item> {
        val cats = seats()
        val out = ArrayList<VirtualAccessibility.Item>()
        val hit = candidateTouch
        for ((n, item) in items.withIndex()) {
            if (!shows(item)) continue
            val x = candidateRow.left + item.x - scroll
            val slop = if (item.kind == 2) 1f else 3f
            val left = max(x - slop, hit.left)
            val right = min(x + item.width + slop, hit.right)
            // Keep the cats available to TalkBack in the reclaimed space.
            var top = hit.top
            for (cat in cats) {
                val touch = cat.touch
                if (touch.left < right && touch.right > left && touch.bottom <= hit.centerY) top = max(top, touch.bottom)
            }
            val bounds = Rect((left * density).toInt(), (top * density).toInt(),
                (right * density).toInt(), (hit.bottom * density).toInt())
            val words = content.words
            val label = when (item.kind) {
                0 -> (if (item.japanese) words("日語候選：", "日语候选：") else words("候選：", "候选：")) + item.text +
                    if (item.continues) words("，保留後續讀音", "，保留后续读音") else ""
                1 -> words("聯想詞：", "联想词：") + item.text
                3 -> item.text
                4 -> if (content.clipSecret) words("剪貼簿，內容已隱藏", "剪贴板，内容已隐藏") else words("剪貼簿：", "剪贴板：") + item.text
                else -> words("標點：", "标点：") + item.text
            }
            out += VirtualAccessibility.Item(n, bounds, label, selected = item.kind == 0 && item.index == content.highlighted)
        }
        val dismiss = onDismissKeyboard?.let {
            VirtualAccessibility.LongClick(content.words("收起鍵盤", "收起键盘")) { it(null) }
        }
        for (seat in cats) {
            val b = seat.touch
            val bounds = Rect((b.left * density).toInt(), (b.top * density).toInt(), (b.right * density).toInt(), (b.bottom * density).toInt())
            val id = seat.actor.id
            val purr = if (directed && !seat.actor.hidden) listOf(
                VirtualAccessibility.LongClick(content.words("讓牠呼嚕", "让它呼噜")) { listener?.onHold(id) },
            ) else emptyList()
            out += if (seat.actor.id == Cat.MIMIMO) {
                VirtualAccessibility.Item(MIMIMO_ID, bounds, if (content.mimimoSwitches) content.words("摸摸米米莫，連續五下切換輸入方式", "摸摸米米莫，连续五下切换输入方式") else "摸摸米米莫", longClick = dismiss, custom = purr)
            } else {
                val label = if (content.japaneseMode) "摸摸莫莫" else content.words("摸摸莫莫，連續五下切換簡繁", "摸摸莫莫，连续五下切换简繁")
                VirtualAccessibility.Item(MOMO_ID, bounds, label, longClick = dismiss, custom = purr)
            }
        }
        if (content.canExpand || content.expanded) {
            val c = region.expandTarget(expandButton)
            out += VirtualAccessibility.Item(CHEVRON_ID, Rect((c.left * density).toInt(), (c.top * density).toInt(), (c.right * density).toInt(), (c.bottom * density).toInt()),
                when {
                    content.emoji -> content.words("收起表情符號", "收起表情符号")
                    content.expanded -> content.words("收合候選詞", "收起候选词")
                    else -> content.words("展開候選詞", "展开候选词")
                })
        }
        return out
    }

    private fun activate(id: Int) {
        when (id) {
            CHEVRON_ID -> listener?.onToggleExpanded()
            MIMIMO_ID -> if (actor(Cat.MIMIMO).hidden) listener?.onSeat(Cat.MIMIMO) else listener?.onPet(Cat.MIMIMO)
            MOMO_ID -> if (actor(Cat.MOMO).hidden) listener?.onSeat(Cat.MOMO) else listener?.onPet(Cat.MOMO)
            else -> items.getOrNull(id)?.let(::activateItem)
        }
    }

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = accessibility

    override fun dispatchHoverEvent(event: MotionEvent): Boolean = accessibility.onHover(event) || super.dispatchHoverEvent(event)

    companion object {
        /** The one-bit grid 字景 sets its candidates on: 12 pixels to the em, each a whole number of points. */
        const val GLYPH_PIXELS = GlyphPixelType.EM
        const val GLYPH_UNIT = 2f
        private const val TARGET_NONE = 0
        private const val TARGET_ROW = 1
        private const val TARGET_CHEVRON = 2
        private const val TARGET_CAT = 3
        private const val CHEVRON_ID = 10_000
        private const val MIMIMO_ID = 10_001
        private const val MOMO_ID = 10_002
        private const val ROW_FADE = 18f
        private const val MIN_CANDIDATE_WIDTH = 44f
        private const val CONTINUATION_MARK = " ›"
        private const val EGG_DURATION = 850L
        private const val RIPPLE_MS = 500f
        private const val CAT_HOLD_DELAY = 320L
        /** A finger held still on a cat this long makes it purr. */
        private const val CAT_PURR_DELAY = 500L
        private const val SLEEP_FADE_MS = 200f
        /** A sinking cat's paws in front of the glass fade out over its first dp. */
        private const val SINK_PAWS_DP = 4f
        private val PIXEL_CAT = listOf("..#...#..", ".#######.", "###.###.#", "#########", ".##...##.")
        /** Between a Japanese candidate's text and its paw mark (DESIGN.md §4). */
        const val PAW_GAP = 3f
        /** Blur of the light around 米米莫 in dark appearance, in dp. */
        private const val GLOW_BLUR = 2.2f
    }
}
