package com.krysta1ll.meowpuut.android.ime

import android.content.res.AssetManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Shader
import android.util.Log
import com.krysta1ll.meowpuut.android.R
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp

enum class Cat(val key: String) { MIMIMO("mimimo"), MOMO("momo") }
enum class Face(val key: String) { OPEN("open"), HAPPY("happy"), BLINK("blink") }

/**
 * The cat bitmaps (rendered by scripts/render_cats.py at 4x) and, during a
 * festival, the outfit layers that share their canvas.
 */
class CatSprites(private val resources: Resources, private val assets: AssetManager) {
    // Filled from a background thread by preload() and read while drawing.
    private val base = ConcurrentHashMap<String, Bitmap>()
    private val outfitLayers = HashMap<String, Bitmap>()
    var outfit: Outfit? = null
        private set

    private fun drawable(name: String): Bitmap = base.getOrPut(name) {
        val id = when (name) {
            "mimimo_open" -> R.drawable.cat_mimimo_open
            "mimimo_happy" -> R.drawable.cat_mimimo_happy
            "mimimo_blink" -> R.drawable.cat_mimimo_blink
            "momo_open" -> R.drawable.cat_momo_open
            "momo_happy" -> R.drawable.cat_momo_happy
            "momo_blink" -> R.drawable.cat_momo_blink
            "mimimo_tail" -> R.drawable.cat_mimimo_tail
            "mimimo_frost" -> R.drawable.cat_mimimo_frost
            else -> R.drawable.cat_momo_frost
        }
        val options = BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = BitmapFactory.decodeResource(resources, id, options)
        if (name.endsWith("_frost")) behindGlass(bitmap) else bitmap
    }

    /**
     * A frosted sprite as it is drawn behind the glass. The sprite ends where
     * the glass used to hide the body, and its blur thins out toward the
     * canvas edges. Here the row at [FROST_CUT] is repeated downward, so the
     * body goes on behind the glass ([FROST_EXTENT] times the sprite height),
     * and the right part fades out, so only the end of the glass darkens.
     */
    private fun behindGlass(sprite: Bitmap): Bitmap {
        val w = sprite.width
        val cut = (sprite.height * FROST_CUT).toInt()
        val height = (sprite.height * FROST_EXTENT).toInt()
        val out = Bitmap.createBitmap(w, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(sprite, Rect(0, 0, w, cut), Rect(0, 0, w, cut), null)
        canvas.drawBitmap(sprite, Rect(0, cut - 1, w, cut), Rect(0, cut, w, height), null)
        val fade = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            // A long, eased fade: only the part near the glass's left end stays dark.
            shader = LinearGradient(w * 0.36f, 0f, w * 0.98f, 0f,
                intArrayOf(Color.BLACK, 0xA6000000.toInt(), 0x40000000, Color.TRANSPARENT),
                floatArrayOf(0f, 0.35f, 0.7f, 1f), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, w.toFloat(), height.toFloat(), fade)
        sprite.recycle()
        return out
    }

    /** Decodes every sprite ahead of the first draw. Safe to call off the main thread. */
    fun preload() {
        for (cat in Cat.entries) {
            for (face in Face.entries) drawable("${cat.key}_${face.key}")
            drawable("${cat.key}_frost")
        }
        drawable("mimimo_tail")
    }

    fun face(cat: Cat, face: Face): Bitmap =
        outfitLayers["${cat.key}:frame:${face.key}"] ?: drawable("${cat.key}_${face.key}")

    /** The frosted sprite, [FROST_EXTENT] times as tall as the plain one (see [behindGlass]). */
    fun frost(cat: Cat): Bitmap = drawable("${cat.key}_frost")
    fun tail(): Bitmap = drawable("mimimo_tail")
    fun front(cat: Cat): Bitmap? = outfitLayers["${cat.key}:front"]
    fun back(cat: Cat): Bitmap? = outfitLayers["${cat.key}:back"]

    /** Loads the layers of [next] (or drops them for null). Returns whether anything changed. */
    fun dress(next: Outfit?): Boolean {
        if (next?.id == outfit?.id) return false
        outfitLayers.values.forEach { it.recycle() }
        outfitLayers.clear()
        outfit = next
        if (next == null) return true
        for (cat in Cat.entries) {
            val entry = next.cats[cat.key] ?: continue
            entry.front?.let { load(next, it)?.let { b -> outfitLayers["${cat.key}:front"] = b } }
            entry.back?.let { load(next, it)?.let { b -> outfitLayers["${cat.key}:back"] = b } }
            for ((frame, file) in entry.frames) load(next, file)?.let { outfitLayers["${cat.key}:frame:$frame"] = it }
        }
        return true
    }

    companion object {
        const val FROST_CUT = 0.84f
        const val FROST_EXTENT = 1.5f
    }

    private fun load(outfit: Outfit, file: String): Bitmap? = try {
        assets.open(outfit.asset(file)).use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) {
        Log.w("Meowpuut", "festival image missing: ${outfit.asset(file)}")
        null
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
 * One pet or nudge: hop and head shake (米米莫) or a slow nuzzle (莫莫).
 * [timeScale] is the system's animator duration scale when the motion began.
 */
class CatMotion(val cat: Cat, val start: Long, private val timeScale: Float = 1f) {
    /** Filled by [pose]; one instance per motion, so a frame allocates nothing. */
    class Pose { var scale = 1f; var squash = 1f; var tilt = 0f; var lift = 0f }

    private val span = if (cat == Cat.MIMIMO) 620f else 900f
    val duration: Long = (span * timeScale).toLong()
    private val current = Pose()

    fun pose(now: Long): Pose? {
        val t = (now - start).toFloat() / timeScale
        if (t < 0 || t > span) return null
        val mimimo = cat == Cat.MIMIMO
        current.squash = track(t, SQUASH_TIMES, SQUASH, spring = true)
        current.lift = track(t, if (mimimo) MIMIMO_LIFT_TIMES else MOMO_LIFT_TIMES, if (mimimo) MIMIMO_LIFT else MOMO_LIFT, spring = true)
        current.tilt = track(t, if (mimimo) MIMIMO_TILT_TIMES else MOMO_TILT_TIMES, if (mimimo) MIMIMO_TILT else MOMO_TILT, spring = false)
        current.scale = track(t, SCALE_TIMES, if (mimimo) MIMIMO_SCALE else MOMO_SCALE, spring = true)
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
        // Segment lengths in ms and the values at their ends.
        val SQUASH_TIMES = floatArrayOf(80f, 400f)
        val SQUASH = floatArrayOf(1f, 0.9f, 1f)
        val MIMIMO_LIFT_TIMES = floatArrayOf(80f, 120f, 380f)
        val MOMO_LIFT_TIMES = floatArrayOf(80f, 160f, 500f)
        val MIMIMO_LIFT = floatArrayOf(0f, 0f, -4f, 0f)
        val MOMO_LIFT = floatArrayOf(0f, 0f, -1.5f, 0f)
        val MIMIMO_TILT_TIMES = floatArrayOf(100f, 120f, 110f, 250f)
        val MOMO_TILT_TIMES = floatArrayOf(180f, 120f, 120f, 450f)
        val MIMIMO_TILT = floatArrayOf(0f, -6f, 5f, -2.5f, 0f)
        val MOMO_TILT = floatArrayOf(0f, 7f, 5f, 4f, 0f)
        val SCALE_TIMES = floatArrayOf(80f, 120f, 400f)
        val MIMIMO_SCALE = floatArrayOf(1f, 1f, 1.06f, 1f)
        val MOMO_SCALE = floatArrayOf(1f, 1f, 1.05f, 1f)
    }
}
