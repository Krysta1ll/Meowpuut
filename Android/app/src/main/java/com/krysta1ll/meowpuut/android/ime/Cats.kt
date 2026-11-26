package com.krysta1ll.meowpuut.android.ime

import android.content.res.AssetManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.krysta1ll.meowpuut.android.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

internal fun catLiftProgress(elapsed: Float): Float {
    val time = elapsed.coerceIn(0f, 1f)
    return time * time * (3f - 2f * time)
}

/** Keep ears inside the IME and remove the lift offset before the drop boundary. */
internal fun catLiftRise(top: Float, pawY: Float, firstKeyTop: Float, amount: Float): Float {
    val room = (top - 1f).coerceAtLeast(0f)
    val seam = ((firstKeyTop - pawY) / 18f).coerceIn(0f, 1f)
    return min(room, 7f * amount * seam)
}

/**
 * Today's festival pictures (Festivals.kt): the dressed cats, seated by their
 * bust, and the right end's ornament cluster. All at 3 px per dp.
 */
class FestivalArt(val theme: FestivalTheme, val mimimo: CatSprites.Art?, val momo: CatSprites.Art?, val right: Bitmap?) {
    companion object {
        private fun bitmap(image: String, assets: AssetManager) = try {
            assets.open("festivals/$image.png").use { BitmapFactory.decodeStream(it) }?.apply { setHasMipMap(true) }
        } catch (_: Exception) { null }

        fun loadCat(sprite: FestivalSprite, assets: AssetManager): CatSprites.Art? {
            val shape = sprite.catSprite() ?: return null
            val picture = bitmap(sprite.image, assets) ?: return null
            return CatSprites.Art(shape, picture)
        }

        fun load(theme: FestivalTheme, assets: AssetManager): FestivalArt =
            FestivalArt(theme, loadCat(theme.mimimo, assets), loadCat(theme.momo, assets),
                theme.right?.let { bitmap(it.image, assets) })
    }
}

/** The cat pictures on the candidate bar (written by scripts/render_cats.py at 3 px per dp). */
class CatSprites(private val resources: Resources) {
    class Art(val sprite: CatSprite, val bitmap: Bitmap)

    private val arts = HashMap<String, Art>()
    private val resolvedDrawables = HashMap<String, Int>()
    /** A custom skin's decoded pictures, set once its files are verified (CustomSkinLoader); never read from disk here. */
    private var skinArts: Map<String, Art> = emptyMap()
    private var skinEnd: Pair<SkinEnd, Bitmap>? = null
    private var skinLeftEnd: Pair<SkinEnd, Bitmap>? = null
    private var skinSpaceMark: Pair<SkinEnd, Bitmap>? = null

    fun useSkin(
        sprites: Map<String, Art>,
        end: Pair<SkinEnd, Bitmap>?,
        leftEnd: Pair<SkinEnd, Bitmap>? = null,
        spaceMark: Pair<SkinEnd, Bitmap>? = null,
    ) {
        skinArts = sprites
        skinEnd = end
        skinLeftEnd = leftEnd
        skinSpaceMark = spaceMark
    }

    fun endArt(end: SkinEnd): Bitmap? = skinEnd?.takeIf { it.first == end }?.second
    fun leftEndArt(end: SkinEnd): Bitmap? = skinLeftEnd?.takeIf { it.first == end }?.second
    fun spaceMarkArt(mark: SkinEnd): Bitmap? = skinSpaceMark?.takeIf { it.first == mark }?.second
    fun skinGrab(cat: Cat): Art? = skinArts["${cat.key}_grab"]
    fun skinPose(cat: Cat, pose: CatBodyPose): Art? = when (pose) {
        CatBodyPose.HAPPY, CatBodyPose.GREET, CatBodyPose.JUMP -> skinArts["${cat.key}_happy"]
        CatBodyPose.SLEEP -> skinArts["${cat.key}_sleep"]
        CatBodyPose.IDLE -> null
    }
    private val bodies = HashMap<Int, Bitmap>()
    private val held = arrayOfNulls<HeldCatArt>(Cat.entries.size)

    fun hasAsset(name: String): Boolean = skinArts.containsKey(name) || drawableId(name) != 0

    fun art(cat: Cat, pose: CatPose, skin: KeyboardSkin, form: PetForm): Art {
        val sprite = PetAssetResolver.resolveSeatedSprite(
            form = form,
            skin = skin,
            cat = cat,
            pose = pose,
            customSprite = if (form == PetForm.THREE_D) skinArts[pose.sprite(cat).name]?.sprite else null,
            exists = ::hasAsset,
        )
        return art(sprite)
    }

    fun art(cat: Cat, pose: CatPose, artwork: SkinCatArtwork): Art {
        if (artwork == SkinCatArtwork.SCULPTED) return art(pose.sprite(cat))
        if (artwork == SkinCatArtwork.CENTENARY) return art(CentenaryCats.sprite(cat, pose))
        val name = "${artwork.name}_${cat.key}"
        return arts.getOrPut(name) {
            val (id, sprite) = when {
                artwork == SkinCatArtwork.PIXEL -> {
                    val drawable = if (cat == Cat.MIMIMO) R.drawable.cat_pixel_mimimo else R.drawable.cat_pixel_momo
                    drawable to PetAssetResolver.pixelSprite(name, cat)
                }
                else -> (if (cat == Cat.MIMIMO) R.drawable.cat_classic_mimimo_open else R.drawable.cat_classic_momo_open) to cat.classicSprite()
            }
            Art(sprite, decode(id))
        }
    }

    fun classicCat(cat: Cat, happy: Boolean, blink: Boolean): Bitmap {
        if (!happy && !blink) return art(cat, CatPose.PEEK, SkinCatArtwork.LEGACY).bitmap
        val id = if (cat == Cat.MIMIMO) {
            if (happy) R.drawable.cat_classic_mimimo_happy else R.drawable.cat_classic_mimimo_blink
        } else {
            if (happy) R.drawable.cat_classic_momo_happy else R.drawable.cat_classic_momo_blink
        }
        return bodies.getOrPut(id) { decode(id) }
    }

    fun classicFrost() = bodies.getOrPut(R.drawable.cat_classic_mimimo_frost) { decode(R.drawable.cat_classic_mimimo_frost) }
    fun classicTail() = bodies.getOrPut(R.drawable.cat_classic_mimimo_tail) { decode(R.drawable.cat_classic_mimimo_tail) }

    /** Decode only the poses the keyboard has actually shown. */
    fun art(sprite: CatSprite): Art = skinArts[sprite.name]?.takeIf { it.sprite === sprite } ?: arts.getOrPut(sprite.name) {
        val id = drawableId(sprite.name).takeIf { it != 0 }
            ?: error("Unknown keyboard cat pose ${sprite.name}")
        Art(sprite, decode(id))
    }

    private fun drawableId(name: String): Int = resolvedDrawables.getOrPut(name) {
        when (name) {
            "mimimo_edge_peek" -> R.drawable.cat_mimimo_edge_peek
            "mimimo_edge_rest" -> R.drawable.cat_mimimo_edge_rest
            "mimimo_edge_lounge" -> R.drawable.cat_mimimo_edge_lounge
            "momo_edge_peek" -> R.drawable.cat_momo_edge_peek
            "momo_edge_rest" -> R.drawable.cat_momo_edge_rest
            "momo_edge_lounge" -> R.drawable.cat_momo_edge_lounge
            "pixel_mimimo" -> R.drawable.cat_pixel_mimimo
            "pixel_momo" -> R.drawable.cat_pixel_momo
            "classic_mimimo_open", "LEGACY_mimimo" -> R.drawable.cat_classic_mimimo_open
            "classic_momo_open", "LEGACY_momo" -> R.drawable.cat_classic_momo_open
            "centenary_mimimo_idle" -> R.drawable.centenary_mimimo_idle
            "centenary_mimimo_bow" -> R.drawable.centenary_mimimo_bow
            "centenary_mimimo_cheer" -> R.drawable.centenary_mimimo_cheer
            "centenary_mimimo_doze" -> R.drawable.centenary_mimimo_doze
            "centenary_momo_idle" -> R.drawable.centenary_momo_idle
            "centenary_momo_bow" -> R.drawable.centenary_momo_bow
            "centenary_momo_cheer" -> R.drawable.centenary_momo_cheer
            "centenary_momo_doze" -> R.drawable.centenary_momo_doze
            else -> lookupDynamicDrawable(name)
        }
    }

    @Suppress("DiscouragedApi")
    private fun lookupDynamicDrawable(name: String): Int {
        val pkg = "com.krysta1ll.meowpuut.android"
        val direct = resources.getIdentifier(name, "drawable", pkg)
        if (direct != 0) return direct
        return resources.getIdentifier("cat_$name", "drawable", pkg)
    }

    fun body(cat: Cat, pose: CatBodyPose): Bitmap {
        val id = when (cat) {
            Cat.MIMIMO -> when (pose) {
                CatBodyPose.IDLE -> R.drawable.cat_mimimo_idle
                CatBodyPose.GREET -> R.drawable.cat_mimimo_press
                CatBodyPose.HAPPY, CatBodyPose.JUMP -> R.drawable.cat_mimimo_happy
                CatBodyPose.SLEEP -> R.drawable.cat_mimimo_sleep
            }
            Cat.MOMO -> when (pose) {
                CatBodyPose.IDLE -> R.drawable.cat_momo_idle
                // TODO(momo_sleep): the user's clay sleeping pose does not exist yet; momo_happy stands in for it.
                CatBodyPose.SLEEP -> R.drawable.cat_momo_happy
                CatBodyPose.GREET -> R.drawable.cat_momo_wave
                CatBodyPose.HAPPY -> R.drawable.cat_momo_happy
                CatBodyPose.JUMP -> R.drawable.cat_momo_jump
            }
        }
        return bodies.getOrPut(id) { decode(id) }
    }

    fun centenaryBody(cat: Cat, pose: CatBodyPose): Bitmap = art(CentenaryCats.sprite(cat, pose)).bitmap

    fun held(cat: Cat): HeldCatArt = held[cat.ordinal] ?: HeldCatArt(
        cat, decode(if (cat == Cat.MIMIMO) R.drawable.cat_mimimo_side_hold else R.drawable.cat_momo_side_hold),
    ).also { held[cat.ordinal] = it }

    fun clear() {
        arts.clear()
        bodies.clear()
        held.fill(null)
    }

    private fun decode(id: Int): Bitmap {
        val options = BitmapFactory.Options().apply { inScaled = false }
        // On most screens it is drawn smaller than its pixels.
        return BitmapFactory.decodeResource(resources, id, options).apply { setHasMipMap(true) }
    }
}

enum class CatBodyPose { IDLE, GREET, HAPPY, JUMP, SLEEP }

/** Four distinct fore/hind paw landmarks on each approved 192 × 264 side-hold image. */
class HeldCatArt(cat: Cat, private val bitmap: Bitmap) {
    private val paws = when (cat) {
        Cat.MIMIMO -> floatArrayOf(160f, 133f, 144f, 166f, 99f, 223f, 134f, 222f)
        Cat.MOMO -> floatArrayOf(33f, 128f, 42f, 167f, 54f, 222f, 88f, 222f)
    }
    private val weights = FloatArray(VERTEX_COUNT * 4)
    private val vertices = FloatArray(VERTEX_COUNT * 2)
    private val offsets = FloatArray(8)

    init {
        // Move the painted limbs continuously without duplicating a paw or
        // leaving a hole in the body. Every vertex's influence is fixed.
        for (i in 0 until VERTEX_COUNT) {
            val x = i % (COLUMNS + 1) * bitmap.width.toFloat() / COLUMNS
            val y = i / (COLUMNS + 1) * bitmap.height.toFloat() / ROWS
            for (paw in 0..3) {
                val dx = (x - paws[2 * paw]) / 24f
                val dy = (y - paws[2 * paw + 1]) / 21f
                val influence = (1f - dx * dx - dy * dy).coerceAtLeast(0f)
                weights[i * 4 + paw] = influence * influence
            }
        }
    }

    fun draw(canvas: Canvas, frame: RectF, elapsed: Float, amount: Float, paint: Paint) {
        val width = frame.width()
        val height = frame.height()
        val scaleX = width / bitmap.width
        val scaleY = height / bitmap.height
        for (paw in 0..3) {
            val phase = elapsed * PI / 170 + paw * PI / 2
            offsets[paw * 2] = 13f * cos(phase).toFloat() * amount * scaleX
            offsets[paw * 2 + 1] = 17f * sin(phase).toFloat() * amount * scaleY
        }
        for (i in 0 until VERTEX_COUNT) {
            var x = frame.left + i % (COLUMNS + 1) * width / COLUMNS
            var y = frame.top + i / (COLUMNS + 1) * height / ROWS
            for (paw in 0..3) {
                val influence = weights[i * 4 + paw]
                x += influence * offsets[2 * paw]
                y += influence * offsets[2 * paw + 1]
            }
            vertices[i * 2] = x
            vertices[i * 2 + 1] = y
        }
        canvas.drawBitmapMesh(bitmap, COLUMNS, ROWS, vertices, 0, null, 0, paint)
    }

    private companion object {
        const val COLUMNS = 16
        const val ROWS = 22
        const val VERTEX_COUNT = (COLUMNS + 1) * (ROWS + 1)
    }
}

/**
 * Consecutive pets on either cat (same rules as iOS CatAffection.swift): five
 * within 1.6 s switch the script, then a 1.2 s rest.
 */
class CatAffection {
    sealed interface Outcome {
        data class Pleased(val count: Int) : Outcome
        data object Happy : Outcome
        data object Resting : Outcome
    }

    var pets = 0
        private set
    private var lastPet = -1L
    private var restUntil = -1L

    fun resetStreak() {
        pets = 0
        lastPet = -1L
    }

    fun pet(now: Long): Outcome {
        if (restUntil >= 0 && now < restUntil) return Outcome.Resting
        restUntil = -1
        if (lastPet >= 0 && now - lastPet > WINDOW_MS) pets = 0
        lastPet = now
        pets++
        if (pets < PETS_TO_SWITCH) return Outcome.Pleased(pets)
        pets = 0
        lastPet = -1
        restUntil = now + REST_MS
        return Outcome.Happy
    }

    companion object {
        const val PETS_TO_SWITCH = 5
        const val WINDOW_MS = 1600L
        const val REST_MS = 1200L
    }
}

/**
 * A cat's answer to a pet: 米米莫 ducks and shakes his head, 莫莫 nuzzles.
 * The values turn and scale the sprite about its paw anchor. [timeScale] is
 * the system's animator duration scale when the motion began.
 *
 * The moments of docs/PETS.md (HELLO, HOLD, SINK, SURFACE, FOUND, DUET, HOP)
 * are played from a plan of the pets core. They never rise above the cat's
 * resting height: they squash, sink or change pose. A [fade] motion (Reduce
 * Motion, or a skin without a glass seat) only cross-fades the pose, in at most
 * 0.25 s. A motion allocates once; a frame allocates nothing.
 */
class CatMotion(
    val cat: Cat,
    val start: Long,
    private val timeScale: Float = 1f,
    private val event: Event = Event.PET,
    private val fade: Boolean = false,
    /** SURFACE after typing: out within 0.18 s. */
    private val quick: Boolean = false,
    /** Wait for [start] showing the cat as it is (the second cat of a greeting). */
    private val waits: Boolean = false,
) {
    enum class Event { PET, CELEBRATE, WAKE, LOOK, COMMIT, HELLO, HOLD, SINK, SURFACE, FOUND, DUET, HOP, CENTENARY_BOW, CENTENARY_CHEER }

    /** Filled by [pose]; one instance per motion, so a frame allocates nothing. */
    class Pose {
        var wide = 1f
        var tall = 1f
        var tilt = 0f
        var body: CatBodyPose? = null
        var bodyAlpha = 0f
        /** 0 at rest, 1 sunk out of sight into the glass. */
        var drop = 0f
        /** Opacity of the whole cat (a fade moment). */
        var alpha = 1f

        fun reset() {
            wide = 1f; tall = 1f; tilt = 0f; body = null; bodyAlpha = 0f; drop = 0f; alpha = 1f
        }
    }

    private val span = when (event) {
        Event.PET, Event.CELEBRATE -> if (cat == Cat.MIMIMO) 520f else 560f
        Event.WAKE -> 330f
        Event.LOOK -> 250f
        Event.COMMIT -> 280f
        Event.HELLO -> HELLO_MS
        Event.HOLD -> PURR_MS
        Event.SINK -> if (fade) FADE_MS else SINK_MS
        Event.SURFACE -> if (quick) YIELD_MS else if (fade) FADE_MS else SINK_MS
        Event.FOUND -> FOUND_MS
        Event.DUET -> DUET_MS
        Event.HOP -> HOP_MS
        Event.CENTENARY_BOW -> 560f
        Event.CENTENARY_CHEER -> 640f
    }
    private val current = Pose()
    private var releasedAt = -1L
    private var cutAt = -1L

    /** A moment of the pets core rather than an everyday reaction. */
    val isMoment: Boolean get() = event.isMoment

    /** A sunk cat stays out of sight until a SURFACE or FOUND motion replaces this one; it needs no frames while it waits. */
    fun settled(now: Long): Boolean = event == Event.SINK && (now - start).toFloat() / timeScale >= span

    /** The finger left a purring cat: its pose fades out. */
    fun release(now: Long) {
        if (event == Event.HOLD && releasedAt < 0L) releasedAt = now
    }

    /** Typing: the moment ends within 0.15 s. */
    fun cut(now: Long) {
        if (cutAt < 0L && event != Event.SINK && event != Event.SURFACE) cutAt = now
    }

    fun pose(now: Long): Pose? {
        val t = (now - start).toFloat() / timeScale
        if (t < 0f) {
            if (!waits) return null
            current.reset()
            return current
        }
        if (event.isMoment) return momentPose(t, now)
        if (t > span) return null
        current.body = when (event) {
            Event.WAKE -> CatBodyPose.IDLE
            Event.PET -> if (t < 150f) CatBodyPose.GREET else CatBodyPose.HAPPY
            Event.CELEBRATE -> if (cat == Cat.MOMO && t < 200f) CatBodyPose.JUMP else CatBodyPose.HAPPY
            else -> null
        }
        current.bodyAlpha = min(t / 66f, (span - t) / 110f).coerceIn(0f, 1f)
        if (event != Event.PET && event != Event.CELEBRATE) {
            val amount = sin(PI * t / span).toFloat()
            val direction = if (cat == Cat.MIMIMO) 1f else -1f
            current.wide = 1f
            current.tall = when (event) {
                Event.WAKE -> 1f - 0.045f * amount
                Event.COMMIT -> 1f - (if (cat == Cat.MIMIMO) 0.08f else 0.045f) * amount
                else -> 1f
            }
            current.tilt = direction * amount * (if (event == Event.LOOK) 2.4f else 1.2f)
        } else if (cat == Cat.MIMIMO) {
            current.tall = track(t, DUCK_TIMES, DUCK_TALL, spring = true)
            current.wide = track(t, DUCK_TIMES, DUCK_WIDE, spring = true)
            current.tilt = track(t, SHAKE_TIMES, SHAKE, spring = false)
        } else {
            current.tall = track(t, LIFT_TIMES, LIFT, spring = true)
            current.tilt = track(t, NUZZLE_TIMES, NUZZLE, spring = false)
        }
        return current
    }

    private val Event.isMoment get() = ordinal >= Event.HELLO.ordinal

    private fun momentPose(t: Float, now: Long): Pose? {
        val p = current
        p.reset()
        // The held purr ends with the finger; the rest end with their span (SINK never does).
        val end = if (event == Event.HOLD && releasedAt >= 0L) (releasedAt - start).toFloat() / timeScale + PURR_OUT_MS else span
        if (event != Event.SINK && t > end) return null
        var cutFactor = 1f
        if (cutAt >= 0L) {
            val c = (now - cutAt).toFloat() / timeScale
            if (c >= CUT_MS) return null
            cutFactor = 1f - c / CUT_MS
        }
        val fadeIn = if (fade) FADE_MS else 66f
        val inOut = min(t / fadeIn, (end - t) / (if (fade) FADE_MS else 110f)).coerceIn(0f, 1f) * cutFactor
        val direction = if (cat == Cat.MIMIMO) 1f else -1f
        when (event) {
            Event.HELLO -> {
                // Momo waves; Mimimo reaches a beat later (the second motion waits to start).
                p.body = CatBodyPose.GREET
                p.bodyAlpha = inOut
                if (!fade) {
                    p.tall = 1f - 0.03f * sin(PI * t / span).toFloat()
                    p.tilt = direction * 1.2f * sin(PI * t / span).toFloat()
                }
            }
            Event.HOLD -> {
                p.body = if (cat == Cat.MIMIMO) CatBodyPose.SLEEP else CatBodyPose.HAPPY
                p.bodyAlpha = inOut
                // Breathing every 2.6 s, only downward from the resting height.
                if (!fade) p.tall = 1f - 0.022f * (0.5f - 0.5f * cos(2.0 * PI * t / BREATH_MS).toFloat())
            }
            Event.SINK -> {
                val u = (t / span).coerceIn(0f, 1f)
                val e = u * u * (3f - 2f * u)
                if (fade) p.alpha = 1f - e else p.drop = e
            }
            Event.SURFACE -> {
                val u = (t / span).coerceIn(0f, 1f)
                val e = u * u * (3f - 2f * u)
                if (fade) p.alpha = e else p.drop = 1f - e
                p.alpha *= cutFactor.coerceAtLeast(0f)
            }
            Event.FOUND -> {
                val u = (t / YIELD_MS).coerceIn(0f, 1f)
                val e = u * u * (3f - 2f * u)
                if (fade) p.alpha = e else p.drop = 1f - e
                p.body = CatBodyPose.HAPPY
                p.bodyAlpha = if (fade) inOut * e else inOut
                if (!fade) p.tall = 1f - 0.04f * sin(PI * t / span).toFloat()
            }
            Event.DUET -> {
                p.body = if (cat == Cat.MOMO) CatBodyPose.JUMP else CatBodyPose.HAPPY
                p.bodyAlpha = inOut
                if (!fade) p.tilt = direction * 1.5f * sin(2.0 * PI * t / 300f).toFloat()
            }
            Event.HOP -> {
                // A cat without the poses squashes twice: it never rises above its seat.
                if (!fade) p.tall = 1f - 0.07f * abs(sin(2.0 * PI * t / span).toFloat()) * cutFactor
            }
            Event.CENTENARY_BOW -> {
                p.body = CatBodyPose.GREET
                p.bodyAlpha = inOut
                if (!fade) {
                    val bow = sin(PI * t / span).toFloat() * cutFactor
                    p.tilt = direction * 8f * bow
                    p.tall = 1f - 0.07f * bow
                }
            }
            Event.CENTENARY_CHEER -> {
                p.body = CatBodyPose.HAPPY
                p.bodyAlpha = inOut
                if (!fade) {
                    val beat = sin(2.0 * PI * t / span).toFloat() * cutFactor
                    p.tall = 1f - 0.055f * abs(beat)
                    p.tilt = direction * 2f * beat
                }
            }
            else -> Unit
        }
        return p
    }

    // Piecewise ease-in-out; the final segment of a sprung track overshoots slightly.
    private fun track(t: Float, durations: FloatArray, values: FloatArray, spring: Boolean): Float {
        var elapsed = 0f
        for (i in durations.indices) {
            val d = durations[i]
            if (t <= elapsed + d) {
                val u = ((t - elapsed) / d).coerceIn(0f, 1f)
                val last = i == durations.size - 1
                val eased = if (last && spring) {
                    (1 - exp(-6.0 * u) * cos(1.6 * PI * u)).toFloat()
                } else {
                    (u * u * (3 - 2 * u))
                }
                return values[i] + (values[i + 1] - values[i]) * eased
            }
            elapsed += d
        }
        return values.last()
    }

    companion object {
        const val HELLO_MS = 900f
        /** A purr lasts at most this long (4 s) and fades out 0.16 s after the finger leaves. */
        const val PURR_MS = 4000f
        const val PURR_OUT_MS = 160f
        const val BREATH_MS = 2600f
        const val SINK_MS = 280f
        const val FADE_MS = 250f
        const val FOUND_MS = 350f
        const val DUET_MS = 600f
        const val HOP_MS = 500f
        /** Any moment gives way to typing within this long. */
        const val YIELD_MS = 180f
        const val CUT_MS = 150f

        // Segment lengths in ms and the values at their ends. Degrees are
        // clockwise; 莫莫 sits at the right end and leans toward the candidates.
        private val DUCK_TIMES = floatArrayOf(80f, 260f, 180f)
        private val DUCK_TALL = floatArrayOf(1f, 0.86f, 0.88f, 1f)
        private val DUCK_WIDE = floatArrayOf(1f, 1.05f, 1.04f, 1f)
        private val SHAKE_TIMES = floatArrayOf(80f, 100f, 100f, 90f, 150f)
        private val SHAKE = floatArrayOf(0f, -5f, 4.5f, -3f, 1.5f, 0f)
        private val LIFT_TIMES = floatArrayOf(170f, 220f, 170f)
        private val LIFT = floatArrayOf(1f, 1.05f, 1.04f, 1f)
        private val NUZZLE_TIMES = floatArrayOf(170f, 110f, 110f, 170f)
        private val NUZZLE = floatArrayOf(0f, -6f, -3.5f, -6f, 0f)
    }
}
