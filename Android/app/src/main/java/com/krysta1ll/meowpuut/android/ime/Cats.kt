package com.krysta1ll.meowpuut.android.ime

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.krysta1ll.meowpuut.android.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp

/** The cat pictures on the candidate bar (written by scripts/render_cats.py at 3 px per dp). */
class CatSprites(private val resources: Resources) {
    class Art(val sprite: CatSprite, val bitmap: Bitmap)

    private val arts = HashMap<Cat, Art>()

    /** The sprite a cat is seated by and its picture. A festival can return its own here. */
    fun art(cat: Cat): Art = arts.getOrPut(cat) {
        val id = if (cat == Cat.MIMIMO) R.drawable.cat_mimimo_edge_peek else R.drawable.cat_momo_edge_peek
        val options = BitmapFactory.Options().apply { inScaled = false }
        val bitmap = BitmapFactory.decodeResource(resources, id, options)
        // On most screens it is drawn smaller than its pixels.
        bitmap.setHasMipMap(true)
        Art(CatSpriteData.all.getValue("${cat.key}_edge_peek"), bitmap)
    }
}

/**
 * Consecutive pets on 莫莫 (same rules as iOS CatAffection.swift): five
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
class CatMotion(val cat: Cat, val start: Long, private val timeScale: Float = 1f) {
    /** Filled by [pose]; one instance per motion, so a frame allocates nothing. */
    class Pose { var wide = 1f; var tall = 1f; var tilt = 0f }

    private val span = if (cat == Cat.MIMIMO) 520f else 560f
    private val current = Pose()

    fun pose(now: Long): Pose? {
        val t = (now - start).toFloat() / timeScale
        if (t < 0 || t > span) return null
        if (cat == Cat.MIMIMO) {
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
