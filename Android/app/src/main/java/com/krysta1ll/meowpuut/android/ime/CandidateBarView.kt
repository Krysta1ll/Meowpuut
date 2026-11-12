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
import android.widget.OverScroller
import com.krysta1ll.meowpuut.android.engine.Candidate
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
class CandidateBarView(context: Context, private val sprites: CatSprites) : View(context) {
    interface Listener {
        fun onCandidate(index: Int)
        fun onPrediction(text: String)
        fun onPunctuation(mark: String)
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
        val words: Words = Words(false),
    )

    var listener: Listener? = null
    var region: CandidateRegionLayout = CandidateRegionLayout(393f, Idiom.PHONE)
        set(value) { field = value; caches = null; seats = null; layoutRow(); invalidate() }
    var look: Look = Look(false, GlassShape.CAPSULE, Palette.ROSE, GlassStrength.BALANCED, true)
        set(value) { if (field != value) { field = value; caches = null; seats = null; invalidate() } }
    var content = Content()
        private set

    private val density = resources.displayMetrics.density
    private val fontScale = min(1.15f, resources.configuration.fontScale.coerceAtLeast(0.85f))
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val selectedPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
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

    private class Caches(val shadow: Bitmap, val glass: Bitmap)
    private var caches: Caches? = null

    // Row items in content coordinates (dp, before scrolling).
    private class Item(val kind: Int, val index: Int, val text: String, val shown: String, val x: Float, val width: Float, val japanese: Boolean)
    private var items: List<Item> = emptyList()
    private var contentWidth = 0f
    private var scroll = 0f
    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop / density

    // Touch state.
    private var target = 0
    private var downX = 0f
    private var downScroll = 0f
    private var dragging = false
    private var pressedItem = -1

    // Cats.
    /** The light around 米米莫 in dark appearance: around his body, and around his paws on the glass. */
    private class Glow(val body: Bitmap, val bodyBox: RectF, val paws: Bitmap, val pawsBox: RectF)
    /** A cat in its place. [paws] outlines what of the sprite is in front of the glass. */
    private class Seat(val place: CatPlacement, val touch: Box, val bitmap: Bitmap, val paws: Path, val glow: Glow?)
    private var seats: List<Seat>? = null
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { color = Look.withAlpha(0xFFEDD6, 0.6f) }
    private val motions = HashMap<Cat, CatMotion>()
    private val pressedCats = HashSet<Cat>()
    private var lean = 0f
    private var leanAnimator: ValueAnimator? = null
    private val affection = CatAffection()

    private class Particle(val cat: Cat, val kind: Int, val start: Long, val span: Float, val size: Float, val drift: Float, val color: Int)
    private val particles = ArrayList<Particle>()

    private class Toast(val text: String, val hearts: Int?, val emphasis: Boolean, val start: Long) {
        val duration get() = if (emphasis) 2400L else 1400L
    }
    private var toast: Toast? = null

    private val accessibility = VirtualAccessibility(this, ::accessibleItems, ::activate)

    private val motionEnabled get() = ValueAnimator.areAnimatorsEnabled()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun update(next: Content) {
        val wasComposing = content.composing
        val newRow = next.candidates != content.candidates || next.predictions != content.predictions
        content = next
        if (wasComposing != next.composing) animateLean(if (next.composing) 1f else 0f)
        layoutRow()
        if (newRow) {
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
        val font = region.fontSize * fontScale
        textPaint.textSize = font
        textPaint.typeface = Typeface.DEFAULT
        selectedPaint.textSize = font
        selectedPaint.typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 500, false) else Typeface.DEFAULT
        val list = ArrayList<Item>()
        var x = 4f
        val gap = 6f
        val mark = font * 0.45f + PAW_GAP
        val c = content
        if (c.candidates.isNotEmpty()) {
            val maxText = region.maximumCandidateWidth - 24f
            for (index in c.visible) {
                val candidate = c.candidates.getOrNull(index) ?: continue
                val japanese = candidate.japanese && c.japaneseMarks
                val room = maxText - if (japanese) mark else 0f
                val shown = if (measureText(candidate.text, textPaint) > room) {
                    TextUtils.ellipsize(candidate.text, textPaint, room, TextUtils.TruncateAt.MIDDLE).toString()
                } else candidate.text
                val width = max(region.pillHeight, measureText(shown, selectedPaint) + 24f + if (japanese) mark else 0f)
                list += Item(0, index, candidate.text, shown, x, width, japanese)
                x += width + gap
            }
        } else if (c.predictions.isNotEmpty()) {
            for ((i, text) in c.predictions.withIndex()) {
                val width = max(region.pillHeight, measureText(text, textPaint) + 22f)
                list += Item(1, i, text, text, x, width, false)
                x += width + gap
            }
        } else if (c.punctuation.isNotEmpty()) {
            for ((i, mark2) in c.punctuation.withIndex()) {
                list += Item(2, i, mark2, mark2, x, 34f, false)
                x += 34f + 2f
            }
        }
        items = list
        contentWidth = x
    }

    private val maxScroll get() = max(0f, contentWidth - region.row.width)

    private fun rowCenterY(): Float {
        val band = GlassOutline.contentBand(look.shape, region.glass.height)
        return region.glass.top + (band.first + band.second) / 2
    }

    // ---------------------------------------------------------------- drawing

    private fun ensureCaches() {
        val w = (width).coerceAtLeast(1)
        val h = (height).coerceAtLeast(1)
        val current = caches
        if (current != null && current.glass.width == w && current.glass.height == h) return
        current?.shadow?.recycle(); current?.glass?.recycle()
        // The shadow goes on below the view, onto the keyboard (the keyboard
        // does not clip its children).
        val below = ((region.glass.bottom + GlassPainter.REACH) * density).toInt()
        // Canvas has a density of its own (an Int), so no apply { } here.
        val shadow = Bitmap.createBitmap(w, max(h, below), Bitmap.Config.ARGB_8888)
        val shadowCanvas = Canvas(shadow)
        shadowCanvas.scale(density, density)
        GlassPainter.shadow(shadowCanvas, look.shape, region.glass, look.dark)
        val glass = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val glassCanvas = Canvas(glass)
        glassCanvas.scale(density, density)
        GlassPainter.body(glassCanvas, look.shape, region.glass, look)
        glassPath.outline(look.shape, region.glass)
        pawShadows(glassCanvas)
        caches = Caches(shadow, glass)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        caches = null
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        ensureCaches()
        val now = SystemClock.uptimeMillis()
        val cache = caches ?: return
        dst.set(0f, 0f, width.toFloat(), cache.shadow.height.toFloat())
        canvas.drawBitmap(cache.shadow, null, dst, bitmapPaint)
        dst.bottom = height.toFloat()
        canvas.drawBitmap(cache.glass, null, dst, bitmapPaint)
        canvas.save()
        canvas.scale(density, density)
        // The cats are in front of the glass and under the candidates.
        for (seat in seats()) drawCat(canvas, seat, now)
        // A note from the cats takes the row's place while it shows.
        drawRow(canvas, 1f - toastAlpha(now))
        drawChevron(canvas)
        drawParticles(canvas, now)
        drawToast(canvas, now)
        canvas.restore()
        if (scroller.computeScrollOffset()) {
            scroll = (scroller.currX / density).coerceIn(0f, maxScroll)
            postInvalidateOnAnimation()
        } else if (motions.isNotEmpty() || particles.isNotEmpty() || toast != null) {
            postInvalidateOnAnimation()
        }
    }

    private fun rectOf(box: Box): RectF = rect.apply { set(box.left, box.top, box.right, box.bottom) }

    private fun seats(): List<Seat> = seats ?: seatCats().also { seats = it }

    private fun seatCats(): List<Seat> {
        if (!(region.cats && look.cats)) return emptyList()
        val stage = region.stage(look.shape)
        val mimimo = sprites.art(Cat.MIMIMO)
        val momo = sprites.art(Cat.MOMO)
        val places = CatSeats.ends(stage, mimimo.sprite, momo.sprite) ?: return emptyList()
        return places.map { place ->
            val art = if (place.cat == Cat.MIMIMO) mimimo else momo
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
            val glow = if (look.dark && place.cat == Cat.MIMIMO) glow(place, art.bitmap, paws) else null
            Seat(place, place.touch(stage), art.bitmap, paws, glow)
        }
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

    /** A soft shadow on the glass under each paw, painted into the cached glass. */
    private fun pawShadows(canvas: Canvas) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Look.withAlpha(Color.BLACK, if (look.dark) 0.35f else 0.20f)
        paint.maskFilter = BlurMaskFilter(1.5f, BlurMaskFilter.Blur.NORMAL)
        canvas.save()
        canvas.clipPath(glassPath)
        for (seat in seats()) {
            for (paw in seat.place.paws) {
                rect.set(paw.left + 1f, paw.bottom - 1.5f, paw.right - 1f, paw.bottom + 1.5f)
                canvas.drawOval(rect, paint)
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
        val cat = place.cat
        val motion = motions[cat]
        val pose = motion?.pose(now)
        if (motion != null && pose == null) motions.remove(cat)
        // A lean toward the candidates while composing, plus the answer to a pet.
        val tilt = lean * (if (cat == Cat.MIMIMO) 2.5f else -2.5f) + (pose?.tilt ?: 0f)
        var wide = pose?.wide ?: 1f
        var tall = pose?.tall ?: 1f
        if (cat in pressedCats && motionEnabled) { wide *= 1.06f; tall *= 0.93f }
        val frame = rectOf(place.frame)
        val glow = seat.glow

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
        val row = region.row
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
        val row = region.row
        val cy = rowCenterY()
        val top = region.glass.top - 2
        val bottom = region.glass.bottom + 2
        // A layer only when something has to be faded: an overflowing row, or a note over it.
        val overflow = maxScroll > 0.5f
        if (overflow || alpha < 0.99f) {
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
                textPaint.textSize = region.statusFontSize * fontScale
                val w = textPaint.measureText(c.status)
                canvas.drawText(c.status, row.right - w - 8f, cy - (textPaint.ascent() + textPaint.descent()) / 2, textPaint)
                textPaint.textSize = font
            }
            canvas.restore()
            return
        }
        for ((n, item) in items.withIndex()) {
            val x = row.left + item.x - scroll
            if (x > row.right || x + item.width < row.left) continue
            val pillTop = cy - region.pillHeight / 2
            val selected = item.kind == 0 && item.index == c.highlighted
            val pressed = n == pressedItem
            if (selected) {
                pill.draw(canvas, x, pillTop, item.width, region.pillHeight, look.pill, look.dark, shadow = true)
            } else if (pressed || item.kind == 1) {
                pressPaint.color = Look.withAlpha(look.pill.second, if (pressed) (if (look.dark) 0.30f else 0.22f) else (if (look.dark) 0.14f else 0.10f))
                rect.set(x, pillTop, x + item.width, pillTop + region.pillHeight)
                canvas.drawRoundRect(rect, region.pillHeight / 2, region.pillHeight / 2, pressPaint)
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
        if (!overflow && c.candidates.isEmpty() && c.predictions.isEmpty() && c.status.isNotEmpty()) {
            textPaint.color = Look.withAlpha(look.candidateSecondary, 0.8f)
            textPaint.textSize = region.statusFontSize * fontScale
            val w = textPaint.measureText(c.status)
            canvas.drawText(c.status, row.right - w - 8f, cy - (textPaint.ascent() + textPaint.descent()) / 2, textPaint)
            textPaint.textSize = font
        }
        if (overflow) fadeRowEnds(canvas, top, bottom)
        canvas.restore()
    }

    private fun drawChevron(canvas: Canvas) {
        val c = content
        if (!c.canExpand && !c.expanded) return
        glyphPaint.color = look.candidateText
        glyphPaint.strokeWidth = 2f
        val box = region.chevron
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
        val font = region.fontSize * 0.75f
        selectedPaint.textSize = font
        val textWidth = selectedPaint.measureText(t.text)
        val hearts = t.hearts
        val iconWidth = if (hearts != null) CatAffection.PETS_TO_SWITCH * 12f + 4f else 18f
        val w = min(region.row.width + 20f, textWidth + iconWidth + 30f)
        val h = 30f
        val cx = (region.row.left + region.chevron.right) / 2
        val cy = rowCenterY()
        val scale = if (motionEnabled) 0.86f + 0.14f * fadeIn else 1f
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
        selectedPaint.textSize = region.fontSize * fontScale
        canvas.restore()
    }

    // ---------------------------------------------------------------- touch

    private fun itemAt(x: Float): Int {
        val contentX = x - region.row.left + scroll
        return items.indexOfFirst { contentX >= it.x - 3 && contentX < it.x + it.width + 3 }
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
                downX = x
                downScroll = scroll
                dragging = false
                pressedItem = -1
                val chevron = region.chevron
                val onArrow = (content.canExpand || content.expanded) && x >= chevron.left - 6 && x <= chevron.right + 6
                val onRow = x >= region.row.left && x <= region.row.right
                // A cat answers on its picture above the glass; the expand
                // key and the candidates keep what is theirs.
                val candidatesTop = rowCenterY() - region.pillHeight / 2
                val pet = seats().firstOrNull { it.touch.contains(x, y) }
                    ?.takeUnless { (onArrow && y >= chevron.top) || (onRow && y >= candidatesTop) }
                target = when {
                    pet != null -> if (pet.place.cat == Cat.MIMIMO) TARGET_MIMIMO else TARGET_MOMO
                    onArrow -> TARGET_CHEVRON
                    onRow -> TARGET_ROW
                    else -> TARGET_NONE
                }
                when (target) {
                    TARGET_ROW -> pressedItem = itemAt(x)
                    TARGET_MIMIMO -> pressedCats += Cat.MIMIMO
                    TARGET_MOMO -> pressedCats += Cat.MOMO
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(event)
                if (target == TARGET_ROW) {
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
                    TARGET_ROW -> if (dragging) {
                        velocity?.computeCurrentVelocity(1000)
                        val vx = -(velocity?.xVelocity ?: 0f)
                        scroller.fling((scroll * density).roundToInt(), 0, vx.roundToInt(), 0, 0, (maxScroll * density).roundToInt(), 0, 0)
                        postInvalidateOnAnimation()
                    } else {
                        val n = itemAt(x)
                        if (n >= 0) activateItem(items[n])
                    }
                    TARGET_CHEVRON -> listener?.onToggleExpanded()
                    TARGET_MIMIMO -> listener?.onPet(Cat.MIMIMO)
                    TARGET_MOMO -> listener?.onPet(Cat.MOMO)
                }
                endTouch()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                endTouch()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun endTouch() {
        velocity?.recycle()
        velocity = null
        pressedItem = -1
        pressedCats.clear()
        target = TARGET_NONE
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

    /** Text was committed from a composition: 米米莫 ducks and shakes his head. */
    fun committed() = motion(Cat.MIMIMO)

    /** A pet from the keyboard; returns the outcome for 莫莫's script switch. */
    fun pet(cat: Cat, simplified: Boolean): CatAffection.Outcome? {
        val now = SystemClock.uptimeMillis()
        motion(cat)
        if (cat == Cat.MIMIMO) {
            emit(Cat.MIMIMO, listOf(1, 2, 3).random(), 10f, (4..10).random().toFloat(), Color.rgb(219, 168, 61))
            return null
        }
        val outcome = affection.pet(now)
        when (outcome) {
            is CatAffection.Outcome.Pleased -> {
                emit(Cat.MOMO, 0, 8f + outcome.count * 1.5f, -(4..10).random().toFloat(), Color.rgb(242, 115, 140))
                if (outcome.count >= 2) {
                    val left = CatAffection.PETS_TO_SWITCH - outcome.count
                    val text = if (simplified) "再摸 $left 下，切换为繁體" else "再摸 $left 下，切換為简体"
                    toast = Toast(text, outcome.count, false, now)
                }
            }
            CatAffection.Outcome.Happy -> {
                for (i in 0 until 3) emit(Cat.MOMO, if (i == 1) 3 else 0, 10f + i * 2, -(4 + i * 5).toFloat(), Color.rgb(242, 115, 140))
                motion(Cat.MIMIMO)
            }
            CatAffection.Outcome.Resting -> emit(Cat.MOMO, 0, 9f, -(4..10).random().toFloat(), Color.rgb(242, 115, 140))
        }
        invalidate()
        return outcome
    }

    /** Shown after 莫莫 switches the script. */
    fun announce(text: String) {
        toast = Toast(text, null, true, SystemClock.uptimeMillis())
        announceForAccessibilityCompat(text)
        invalidate()
    }

    @Suppress("DEPRECATION")
    private fun announceForAccessibilityCompat(text: String) = announceForAccessibility(text)

    private fun motion(cat: Cat) {
        if (!motionEnabled || !(region.cats && look.cats)) return
        motions[cat] = CatMotion(cat, SystemClock.uptimeMillis(), timeScale())
        invalidate()
    }

    private fun emit(cat: Cat, kind: Int, size: Float, drift: Float, color: Int) {
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
        leanAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------------- accessibility

    private fun accessibleItems(): List<VirtualAccessibility.Item> {
        val out = ArrayList<VirtualAccessibility.Item>()
        val cy = rowCenterY()
        for ((n, item) in items.withIndex()) {
            val x = region.row.left + item.x - scroll
            if (x + item.width < region.row.left || x > region.row.right) continue
            val bounds = Rect(((x) * density).toInt(), ((cy - region.pillHeight / 2) * density).toInt(),
                ((x + item.width) * density).toInt(), ((cy + region.pillHeight / 2) * density).toInt())
            val words = content.words
            val label = when (item.kind) {
                0 -> (if (item.japanese) words("日語候選：", "日语候选：") else words("候選：", "候选：")) + item.text
                1 -> words("聯想詞：", "联想词：") + item.text
                else -> words("標點：", "标点：") + item.text
            }
            out += VirtualAccessibility.Item(n, bounds, label, selected = item.kind == 0 && item.index == content.highlighted)
        }
        if (content.canExpand || content.expanded) {
            val c = region.chevron
            out += VirtualAccessibility.Item(CHEVRON_ID, Rect((c.left * density).toInt(), (c.top * density).toInt(), (c.right * density).toInt(), (c.bottom * density).toInt()),
                if (content.expanded) content.words("收合候選詞", "收起候选词") else content.words("展開候選詞", "展开候选词"))
        }
        for (seat in seats()) {
            val b = seat.touch
            val bounds = Rect((b.left * density).toInt(), (b.top * density).toInt(), (b.right * density).toInt(), (b.bottom * density).toInt())
            out += if (seat.place.cat == Cat.MIMIMO) VirtualAccessibility.Item(MIMIMO_ID, bounds, content.words("摸摸米米莫", "摸摸米米莫"))
            else VirtualAccessibility.Item(MOMO_ID, bounds, content.words("摸摸莫莫，連續五下切換簡繁", "摸摸莫莫，连续五下切换简繁"))
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
        private const val TARGET_MIMIMO = 3
        private const val TARGET_MOMO = 4
        private const val CHEVRON_ID = 10_000
        private const val MIMIMO_ID = 10_001
        private const val MOMO_ID = 10_002
        private const val ROW_FADE = 18f
        /** Between a Japanese candidate's text and its paw mark (DESIGN.md §4). */
        const val PAW_GAP = 3f
        /** Blur of the light around 米米莫 in dark appearance, in dp. */
        private const val GLOW_BLUR = 2.2f
    }
}
