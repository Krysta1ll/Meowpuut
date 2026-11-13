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
    private val bodies = HashMap<Int, Bitmap>()
    private val held = arrayOfNulls<HeldCatArt>(Cat.entries.size)

    fun art(cat: Cat, pose: CatPose, artwork: SkinCatArtwork): Art {
        if (artwork == SkinCatArtwork.SCULPTED) return art(pose.sprite(cat))
        val name = "${artwork.name}_${cat.key}"
        return arts.getOrPut(name) {
            val (id, sprite) = when {
                artwork == SkinCatArtwork.PIXEL -> {
                    val drawable = if (cat == Cat.MIMIMO) R.drawable.cat_pixel_mimimo else R.drawable.cat_pixel_momo
                    drawable to CatSprite(name, 44f, 44f, 0.5f, 0.92f, 0.07f, 0.035f, 0.965f, 1f, 0.18f, 0.82f,
                        listOf(floatArrayOf(0.32f, 0.12f, 0.075f), floatArrayOf(0.68f, 0.12f, 0.075f)))
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
    fun art(sprite: CatSprite): Art = arts.getOrPut(sprite.name) {
        val id = when (sprite.name) {
            "mimimo_edge_peek" -> R.drawable.cat_mimimo_edge_peek
            "mimimo_edge_rest" -> R.drawable.cat_mimimo_edge_rest
            "mimimo_edge_lounge" -> R.drawable.cat_mimimo_edge_lounge
            "momo_edge_peek" -> R.drawable.cat_momo_edge_peek
            "momo_edge_rest" -> R.drawable.cat_momo_edge_rest
            "momo_edge_lounge" -> R.drawable.cat_momo_edge_lounge
            else -> error("Unknown keyboard cat pose ${sprite.name}")
        }
        Art(sprite, decode(id))
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
                CatBodyPose.IDLE, CatBodyPose.SLEEP -> R.drawable.cat_momo_idle
                CatBodyPose.GREET -> R.drawable.cat_momo_wave
                CatBodyPose.HAPPY -> R.drawable.cat_momo_happy
                CatBodyPose.JUMP -> R.drawable.cat_momo_jump
            }
        }
        return bodies.getOrPut(id) { decode(id) }
    }

    fun held(cat: Cat): HeldCatArt = held[cat.ordinal] ?: HeldCatArt(
        cat, decode(if (cat == Cat.MIMIMO) R.drawable.cat_mimimo_side_hold else R.drawable.cat_momo_side_hold),
    ).also { held[cat.ordinal] = it }

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
 */
class CatMotion(
    val cat: Cat,
    val start: Long,
    private val timeScale: Float = 1f,
    private val event: Event = Event.PET,
) {
    enum class Event { PET, CELEBRATE, WAKE, LOOK, COMMIT }

    /** Filled by [pose]; one instance per motion, so a frame allocates nothing. */
    class Pose {
        var wide = 1f
        var tall = 1f
        var tilt = 0f
        var body: CatBodyPose? = null
        var bodyAlpha = 0f
    }

    private val span = when (event) {
        Event.PET, Event.CELEBRATE -> if (cat == Cat.MIMIMO) 520f else 560f
        Event.WAKE -> 330f
        Event.LOOK -> 250f
        Event.COMMIT -> 280f
    }
    private val current = Pose()

    fun pose(now: Long): Pose? {
        val t = (now - start).toFloat() / timeScale
        if (t < 0 || t > span) return null
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

    private companion object {
        // Segment lengths in ms and the values at their ends. Degrees are
        // clockwise; 莫莫 sits at the right end and leans toward the candidates.
        val DUCK_TIMES = floatArrayOf(80f, 260f, 180f)
        val DUCK_TALL = floatArrayOf(1f, 0.86f, 0.88f, 1f)
        val DUCK_WIDE = floatArrayOf(1f, 1.05f, 1.04f, 1f)
        val SHAKE_TIMES = floatArrayOf(80f, 100f, 100f, 90f, 150f)
        val SHAKE = floatArrayOf(0f, -5f, 4.5f, -3f, 1.5f, 0f)
        val LIFT_TIMES = floatArrayOf(170f, 220f, 170f)
        val LIFT = floatArrayOf(1f, 1.05f, 1.04f, 1f)
        val NUZZLE_TIMES = floatArrayOf(170f, 110f, 110f, 170f)
        val NUZZLE = floatArrayOf(0f, -6f, -3.5f, -6f, 0f)
    }
}
