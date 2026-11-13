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
import kotlin.math.PI
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
        fun onPet(cat: Cat)
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
        val composing: Boolean = false,
        val japaneseMarks: Boolean = true,
        val emoji: Boolean = false,
        val japaneseMode: Boolean = false,
        val words: Words = Words(false),
    )

    var listener: Listener? = null
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
            val catsChanged = field.cats != value.cats
            val skinChanged = field.skin != value.skin
            field = value
            if (effectsChanged || skinChanged) {
                cancelTouch()
                leanAnimator?.cancel()
                lean = 0f
                for (cat in actors) cat.motion = null
                particles.clear()
            }
            if (effectsChanged || catsChanged || skinChanged) scheduleCatSleep()
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
    private val candidateFont get() = candidateTextSize.fontSize(region.fontSize, fontScale)
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
    private val widthCache = HashMap<String, Float>()
    private var widthCacheSize = 0f

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
    private class Item(val kind: Int, val index: Int, val text: String, val shown: String, var x: Float, val width: Float, val japanese: Boolean)
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
    private val liftPressedCat = Runnable {
        pressedCat?.let { if (target == TARGET_CAT) startCatDrag(it) }
    }
    private val sleepCat = Runnable {
        if (presented && fullEffects && motionEnabled && !content.composing && pressedCat == null) {
            actor(Cat.MIMIMO).sleeping = true
            invalidate()
        }
    }
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

    fun update(next: Content) {
        if (next == content) return
        val wasComposing = content.composing
        val newRow = next.candidates != content.candidates || next.visible != content.visible ||
            next.predictions != content.predictions || next.punctuation != content.punctuation ||
            next.japaneseMarks != content.japaneseMarks
        bar.content = next
        if (newRow && next.composing) {
            toast = null
            removeCallbacks(expireToast)
        }
        if (wasComposing != next.composing) {
            scheduleCatSleep()
            if (fullEffects) animateLean(if (next.composing) 1f else 0f)
            else if (next.composing) for (cat in actors) motion(cat.id, CatMotion.Event.LOOK)
        }
        if (newRow) {
            clearPunctuationHold()
            layoutRow()
            pressedItem = -1
            scroll = 0f
            scroller.forceFinished(true)
        } else {
            scroll = scroll.coerceIn(0f, maxScroll)
        }
        accessibility.invalidateAll()
        invalidate()
    }

    // ---------------------------------------------------------------- layout

    private fun measureText(text: String, paint: Paint): Float {
        val size = paint.textSize
        if (size != widthCacheSize || widthCache.size > 512) { widthCache.clear(); widthCacheSize = size }
        val key = if (paint === selectedPaint) "\u0001" + text else text
        return widthCache.getOrPut(key) { paint.measureText(text) }
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
        if (c.candidates.isNotEmpty()) {
            fun textWidth(text: String) = max(measureText(text, selectedPaint), measureText(text, textPaint))
            // Reserve an associated emoji and one Japanese reading when
            // they fit beside the highest-ranked word on this screen.
            val emoji = c.visible.getOrNull(1)?.let(c.candidates::getOrNull)?.takeIf { it.kind == CandidateKind.EMOJI }
            val emojiWidth = emoji?.let { (textWidth(it.text) + 24f).coerceIn(MIN_CANDIDATE_WIDTH, maxCell) } ?: 0f
            val emojiSpace = if (emoji != null && room >= MIN_CANDIDATE_WIDTH + gap + emojiWidth) emojiWidth + gap else 0f
            val japanesePosition = if (c.japaneseMarks) c.visible.indexOfFirst { c.candidates.getOrNull(it)?.japanese == true } else -1
            val reserveJapanese = japanesePosition > 0 && room >= emojiSpace + 2 * MIN_CANDIDATE_WIDTH + gap
            val japaneseSpace = if (reserveJapanese) {
                val japanese = c.candidates[c.visible[japanesePosition]]
                val desired = (textWidth(japanese.text) + 24f + mark).coerceIn(MIN_CANDIDATE_WIDTH, min(76f, maxCell))
                min(desired, room - emojiSpace - MIN_CANDIDATE_WIDTH - gap) + gap
            } else 0f
            val firstCell = min(maxCell, (room - emojiSpace - japaneseSpace).coerceAtLeast(MIN_CANDIDATE_WIDTH))
            var firstWidth = MIN_CANDIDATE_WIDTH
            for ((position, index) in c.visible.withIndex()) {
                val candidate = c.candidates.getOrNull(index) ?: continue
                val japanese = candidate.japanese && c.japaneseMarks
                val cellLimit = when {
                    position == 0 -> firstCell
                    reserveJapanese && position == japanesePosition ->
                        min(maxCell, (room - firstWidth - gap - emojiSpace).coerceAtLeast(MIN_CANDIDATE_WIDTH))
                    else -> maxCell
                }
                val labelRoom = (cellLimit - 24f - if (japanese) mark else 0f).coerceAtLeast(0f)
                val shown = if (textWidth(candidate.text) > labelRoom) {
                    val wider = if (measureText(candidate.text, selectedPaint) >= measureText(candidate.text, textPaint)) selectedPaint else textPaint
                    TextUtils.ellipsize(candidate.text, wider, labelRoom, TextUtils.TruncateAt.MIDDLE).toString()
                } else candidate.text
                val width = (textWidth(shown) + 24f + if (japanese) mark else 0f).coerceIn(MIN_CANDIDATE_WIDTH, cellLimit)
                if (position == 0) firstWidth = width
                list += Item(0, index, candidate.text, shown, x, width, japanese)
                x += width + gap
            }
            if (reserveJapanese) {
                val japanese = list.firstOrNull { it.japanese }
                if (japanese != null && japanese.x + japanese.width > viewport + 0.01f) {
                    val insertAt = list.indexOfFirst { it.x + it.width + gap + japanese.width > viewport + 0.01f }
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
                    TextUtils.ellipsize(text, textPaint, labelRoom, TextUtils.TruncateAt.MIDDLE).toString()
                } else text
                val width = (measureText(shown, textPaint) + 22f).coerceIn(MIN_CANDIDATE_WIDTH, maxCell)
                list += Item(1, i, text, shown, x, width, false)
                x += width + gap
            }
        } else if (c.punctuation.isNotEmpty()) {
            for ((i, mark2) in c.punctuation.withIndex()) {
                list += Item(2, i, mark2, mark2, x, 38f, false)
                x += 38f + 2f
            }
        }
        items = list
        contentWidth = list.lastOrNull()?.let { it.x + it.width } ?: 0f
    }

    private val maxScroll get() = max(0f, contentWidth - candidateRow.width)

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
        val black = if (look.edgeHuggingCats && look.skin.catArtwork == SkinCatArtwork.LEGACY && !look.opaque) {
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
        if (look.skin.supportsFestivals) drawFestivalEnd(canvas)
        if (fullEffects && !look.opaque && look.skin.catArtwork == SkinCatArtwork.SCULPTED) pawShadows(canvas, seats)
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
        if (scroller.computeScrollOffset()) {
            scroll = (scroller.currX / density).coerceIn(0f, maxScroll)
            postInvalidateOnAnimation()
        } else if (catLiftStartedAt >= 0L || actors.any { it.motion != null } || particles.isNotEmpty() || (animatedToast && toast != null) ||
            (motionEnabled && easterEggAt >= 0L)) {
            postInvalidateOnAnimation()
        } else if (catHeldAt >= 0L && catDragAnimator == null && motionEnabled) {
            // Only the held paws animate here. Keep this loop below display
            // refresh rate and stop it as soon as the finger is released.
            postInvalidateDelayed(32L)
        }
    }

    private fun rectOf(box: Box): RectF = rect.apply { set(box.left, box.top, box.right, box.bottom) }

    /** The festival's ornament cluster at the bar's right end, over the glass and under the cats and candidates. */
    private fun drawFestivalEnd(canvas: Canvas) {
        val ornament = festivalOrnament ?: return
        canvas.drawBitmap(ornament.first, null, rectOf(ornament.second), bitmapPaint)
    }

    private var festivalOrnament: Pair<Bitmap, Box>? = null

    private fun seats(): List<Seat> = seats ?: seatCats().also {
        seats = it
        val f = festival
        val piece = f?.theme?.right
        val bitmap = f?.right
        festivalOrnament = if (piece == null || bitmap == null) null else {
            val glass = region.glass
            Festivals.placeRight(piece, glass, GlassOutline.contentBand(look.shape, glass.height), region.width,
                expandButton.centerX + CandidateRegionLayout.ARROW_WIDTH / 2)?.let { box -> bitmap to box }
        }
    }

    fun newPresentation() {
        presented = true
        scheduleCatSleep()
        catSchedule.newPresentation()
        invalidateSeats()
        for (cat in actors) motion(cat.id, CatMotion.Event.WAKE)
    }

    fun endPresentation() {
        presented = false
        scheduleCatSleep()
        cancelTouch()
        for (cat in actors) cat.motion = null
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

    private fun scheduleCatSleep() {
        val cat = actor(Cat.MIMIMO)
        if (cat.sleeping) {
            cat.sleeping = false
            invalidate()
        }
        removeCallbacks(sleepCat)
        if (presented && fullEffects && motionEnabled && !content.composing && look.cats) {
            postDelayed(sleepCat, 16_000L)
        }
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
        val artwork = look.skin.catArtwork
        val sculpted = artwork == SkinCatArtwork.SCULPTED
        // The scheduler only needs geometry; decode the sculpted image after seating.
        fun place(mimimo: CatSprites.Art?, momo: CatSprites.Art?) = catSchedule.seats(stage) { cat, pose ->
            val themed = if (cat == Cat.MIMIMO) mimimo else momo
            themed?.sprite ?: if (sculpted) pose.sprite(cat) else sprites.art(cat, pose, artwork).sprite
        }
        val mimimo = if (sculpted) japaneseMimimo ?: festival?.mimimo else null
        val momo = if (sculpted) festival?.momo else null
        val chosen = if (look.edgeHuggingCats) {
            CatSeats.ends(stage, Cat.MIMIMO.classicSprite(), Cat.MOMO.classicSprite()).orEmpty()
        } else {
            val themed = place(mimimo, momo) ?: if (japaneseMimimo != null && momo != null) place(mimimo, null) else null
            themed ?: place(null, null).orEmpty()
        }
        val ornament = if (look.skin.supportsFestivals) festival?.theme?.right else null
        val fitted = stage.fitting(chosen, ornament) ?: stage.fitting(chosen) ?: stage
        setTargets(Box(fitted.row.left, region.row.top, fitted.row.width, region.row.height),
            region.chevron.offset(fitted.arrowTouch.left - stage.arrowTouch.left, 0f))
        return chosen.map { place ->
            val themed = if (place.cat == Cat.MIMIMO) mimimo else momo
            val themedArt = themed?.takeIf { it.sprite === place.sprite }
            val art = themedArt ?: if (sculpted) sprites.art(place.sprite) else sprites.art(place.cat, CatPose.PEEK, artwork)
            if (!sculpted) return@map Seat(actor(place.cat), place, fitted.petTarget(place), art.bitmap, Path(), null, true)
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
        // A lean toward the candidates while composing, plus the answer to a pet.
        val tilt = lean * (if (cat.id == Cat.MIMIMO) 2.5f else -2.5f) + (pose?.tilt ?: 0f)
        var wide = pose?.wide ?: 1f
        var tall = pose?.tall ?: 1f
        if (cat.pressed && motionEnabled) { wide *= 1.06f; tall *= 0.93f }
        val artwork = look.skin.catArtwork
        if (artwork != SkinCatArtwork.SCULPTED || look.edgeHuggingCats) {
            canvas.save()
            if (look.edgeHuggingCats) {
                if (cat.id == Cat.MIMIMO) canvas.clipOutPath(glassPath)
                canvas.rotate(tilt, place.anchorX, place.anchorY)
                canvas.scale(wide, tall, place.anchorX, place.anchorY)
            }
            val pixelHop = if (artwork == SkinCatArtwork.PIXEL && cat.pressed && motionEnabled) 1f else 0f
            drawSkinCat(canvas, seat, place.frame.offset(0f, -pixelHop), pose?.body)
            canvas.restore()
            if (look.edgeHuggingCats && artwork == SkinCatArtwork.LEGACY && cat.id == Cat.MIMIMO) {
                val tail = sprites.classicTail()
                val height = 24f * region.scale
                canvas.drawBitmap(tail, null, rectOf(Box(place.frame.left + 4f, region.glass.bottom - height + 2f,
                    height * tail.width / tail.height, height)), bitmapPaint)
            }
            return
        }
        val body = if (seat.themed || !motionEnabled) null else if (cat.sleeping) CatBodyPose.SLEEP else pose?.body
        val bodyAlpha = if (body == null) 0f else if (cat.sleeping) 1f else pose?.bodyAlpha ?: 0f
        if (bodyAlpha < 1f) drawSeatedCat(canvas, seat, tilt, wide, tall, 1f - bodyAlpha)
        if (body != null && bodyAlpha > 0f) drawStandingCat(canvas, seat, body, bodyAlpha, tilt, wide, tall)
    }

    private fun drawSkinCat(canvas: Canvas, seat: Seat, frame: Box, pose: CatBodyPose?) {
        val artwork = look.skin.catArtwork
        val bitmap = if (artwork == SkinCatArtwork.LEGACY) sprites.classicCat(seat.actor.id,
            happy = pose == CatBodyPose.HAPPY || pose == CatBodyPose.GREET || pose == CatBodyPose.JUMP,
            blink = seat.actor.sleeping) else seat.bitmap
        bitmapPaint.isFilterBitmap = artwork != SkinCatArtwork.PIXEL
        canvas.drawBitmap(bitmap, null, rectOf(frame), bitmapPaint)
        bitmapPaint.isFilterBitmap = true
    }

    private fun drawSeatedCat(canvas: Canvas, seat: Seat, tilt: Float, wide: Float, tall: Float, opacity: Float) {
        val place = seat.place
        val frame = rectOf(place.frame)
        val glow = seat.glow
        bitmapPaint.alpha = (255 * opacity).roundToInt()
        glowPaint.alpha = (153 * opacity).roundToInt()
        canvas.save()
        canvas.clipOutPath(glassPath)
        turn(canvas, place, tilt, wide, tall)
        if (glow != null) canvas.drawBitmap(glow.body, null, glow.bodyBox, glowPaint)
        canvas.drawBitmap(seat.bitmap, null, frame, bitmapPaint)
        canvas.restore()

        if (glow != null) {
            canvas.save()
            canvas.clipPath(glassPath)
            turn(canvas, place, tilt, wide, tall)
            canvas.drawBitmap(glow.paws, null, glow.pawsBox, glowPaint)
            canvas.restore()
        }
        canvas.save()
        turn(canvas, place, tilt, wide, tall)
        canvas.clipPath(seat.paws)
        canvas.drawBitmap(seat.bitmap, null, frame, bitmapPaint)
        canvas.restore()
        bitmapPaint.alpha = 255
        glowPaint.alpha = 153
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
        val sculpted = look.skin.catArtwork == SkinCatArtwork.SCULPTED
        if (!sculpted) {
            bitmapPaint.alpha = (255f * fade).roundToInt()
            drawSkinCat(canvas, seat, place.frame, CatBodyPose.GREET)
        } else if (held < 1f) {
            bitmapPaint.alpha = (255f * fade * (1f - held)).roundToInt()
            canvas.drawBitmap(seat.bitmap, null, rectOf(place.frame), bitmapPaint)
        }
        if (held > 0f && sculpted) {
            val visualAnchor = place.anchorY + catDragY - rise
            val height = (visualAnchor / 0.88f).coerceIn(24f, 63f * region.scale)
            val half = height * 4f / 11f
            val y = place.anchorY - 0.88f * height
            dst.set(place.anchorX - half, y, place.anchorX + half, y + height)
            bitmapPaint.alpha = (255f * fade * held).roundToInt()
            val elapsed = (now - catHeldAt).coerceAtLeast(0L) / catDragTimeScale
            sprites.held(seat.actor.id).draw(canvas, dst, elapsed, held, bitmapPaint)
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
            if (pressed || item.kind == 1) {
                pressPaint.color = when {
                    selected -> Look.withAlpha(Color.BLACK, 0.16f)
                    pressed -> Look.withAlpha(look.pill.second, if (look.dark) 0.30f else 0.22f)
                    else -> Look.withAlpha(look.pill.second, if (look.dark) 0.14f else 0.10f)
                }
                rect.set(x, pillTop, x + item.width, pillTop + region.pillHeight)
                if (look.usesSteppedMaterial) canvas.drawRect(rect, pressPaint)
                else canvas.drawRoundRect(rect, region.pillHeight / 2, region.pillHeight / 2, pressPaint)
            }
            val paint = if (selected) selectedPaint else textPaint
            paint.color = if (selected) Color.WHITE else if (item.kind == 2) look.candidateSecondary else look.candidateText
            if (pressed && motionEnabled) {
                canvas.save()
                canvas.scale(0.95f, 0.95f, x + item.width / 2, cy)
            }
            val textWidth = measureText(item.shown, paint)
            val markWidth = if (item.japanese) font * 0.45f + PAW_GAP else 0f
            val tx = x + (item.width - textWidth - markWidth) / 2
            canvas.drawText(item.shown, tx, baseline, paint)
            if (item.japanese) {
                glyphPaint.color = if (selected) Look.withAlpha(Color.WHITE, 0.85f) else look.candidateSecondary
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
        if (x < candidateRow.left || x >= candidateRow.right) return -1
        val contentX = x - candidateRow.left + scroll
        return items.indexOfFirst {
            val slop = if (it.kind == 2) 1f else 3f
            shows(it) && contentX >= it.x - slop && contentX < it.x + it.width + slop
        }
    }

    private fun onExpandHit(x: Float, y: Float): Boolean =
        (content.canExpand || content.expanded) && region.expandTarget(expandButton).contains(x, y)

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
                removeCallbacks(liftPressedCat)
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
                if (pet != null && onDismissKeyboard != null) postDelayed(liftPressedCat, CAT_HOLD_DELAY)
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
                if (cat != null && onDismissKeyboard != null) {
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
                    if (dragging) {
                        scroll = (downScroll - (x - downX)).coerceIn(0f, maxScroll)
                        invalidate()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                velocity?.addMovement(event)
                when (target) {
                    TARGET_ROW -> if (punctuationHold != null) {
                        punctuationHold?.let { listener?.onPunctuation(it.options[it.selected]) }
                    } else if (dragging) {
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
                        } else if (abs(dx) <= touchSlop && abs(dy) <= touchSlop) {
                            pressedCat?.let { listener?.onPet(it.actor.id) }
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
        removeCallbacks(liftPressedCat)
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
        removeCallbacks(liftPressedCat)
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
                    if (falls) onDismissKeyboard?.invoke(seat.actor.id) else resetCatDrag()
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
        }
    }

    // ---------------------------------------------------------------- cats

    /** A short response when a candidate is committed. */
    fun committed() {
        scheduleCatSleep()
        for (cat in actors) motion(cat.id, CatMotion.Event.COMMIT)
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
        catActor.motion = CatMotion(cat, SystemClock.uptimeMillis(), timeScale(), event)
        invalidate()
    }

    private fun emit(cat: Cat, kind: Int, size: Float, drift: Float, color: Int) {
        if (!fullEffects || !motionEnabled) return
        particles += Particle(cat, kind, SystemClock.uptimeMillis(), 900f * timeScale(), size, drift, color)
        while (particles.size > 8) particles.removeAt(0)
    }

    /** The system's animator duration scale (developer options); 1 when animations are off. */
    private fun timeScale(): Float {
        val scale = if (Build.VERSION.SDK_INT >= 33) ValueAnimator.getDurationScale()
        else android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        return if (scale > 0f) scale else 1f
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
                0 -> (if (item.japanese) words("日語候選：", "日语候选：") else words("候選：", "候选：")) + item.text
                1 -> words("聯想詞：", "联想词：") + item.text
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
            out += if (seat.actor.id == Cat.MIMIMO) {
                VirtualAccessibility.Item(MIMIMO_ID, bounds, content.words("摸摸米米莫，連續五下切換輸入方式", "摸摸米米莫，连续五下切换输入方式"), longClick = dismiss)
            } else {
                val label = if (content.japaneseMode) "摸摸莫莫" else content.words("摸摸莫莫，連續五下切換簡繁", "摸摸莫莫，连续五下切换简繁")
                VirtualAccessibility.Item(MOMO_ID, bounds, label, longClick = dismiss)
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
            MIMIMO_ID -> listener?.onPet(Cat.MIMIMO)
            MOMO_ID -> listener?.onPet(Cat.MOMO)
            else -> items.getOrNull(id)?.let(::activateItem)
        }
    }

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = accessibility

    override fun dispatchHoverEvent(event: MotionEvent): Boolean = accessibility.onHover(event) || super.dispatchHoverEvent(event)

    companion object {
        private const val TARGET_NONE = 0
        private const val TARGET_ROW = 1
        private const val TARGET_CHEVRON = 2
        private const val TARGET_CAT = 3
        private const val CHEVRON_ID = 10_000
        private const val MIMIMO_ID = 10_001
        private const val MOMO_ID = 10_002
        private const val ROW_FADE = 18f
        private const val MIN_CANDIDATE_WIDTH = 44f
        private const val EGG_DURATION = 850L
        private const val CAT_HOLD_DELAY = 320L
        private val PIXEL_CAT = listOf("..#...#..", ".#######.", "###.###.#", "#########", ".##...##.")
        /** Between a Japanese candidate's text and its paw mark (DESIGN.md §4). */
        const val PAW_GAP = 3f
        /** Blur of the light around 米米莫 in dark appearance, in dp. */
        private const val GLOW_BLUR = 2.2f
    }
}
