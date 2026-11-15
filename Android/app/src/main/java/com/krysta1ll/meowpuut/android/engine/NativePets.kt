package com.krysta1ll.meowpuut.android.engine

/**
 * JNI entry points over the pets part of crates/meowpuut-ffi (`mp_pets_*`, see
 * src/main/cpp/meowpuut_jni.c and the "Pets" block of meowpuut.h).
 *
 * Nothing here allocates per call: structs travel as primitives, and results
 * come back in a caller-owned [IntArray] or [ByteArray]. The handle is used on
 * one thread (the keyboard's main thread).
 */
object NativePets {
    // Triggers (MP_PETS_APPEAR ...); arg = graphemes for COMMIT and the cat bit for touches.
    const val APPEAR = 1
    const val COMMIT = 2
    const val IDLE = 3
    const val TAP = 4
    const val HOLD = 5
    const val SEAT = 6
    const val DROP = 7
    const val DISAPPEAR = 8

    // Cat bits (MP_PETS_MIMIMO, MP_PETS_MOMO).
    const val MIMIMO = 1
    const val MOMO = 2

    // Ambience flags (MPPetsAmbience.flags).
    const val COMPOSING = 1
    const val LIFTED = 2
    const val DISMISSING = 4
    const val ASSISTIVE = 8
    const val LIGHTWEIGHT = 16
    const val REDUCE_MOTION = 32
    const val REDUCE_TRANSPARENCY = 64
    const val GLASS_SEAT = 128
    const val EDGES = 256
    const val FESTIVAL = 512

    // Modes (MP_PETS_MODE_*).
    const val MODE_FULL = 0
    const val MODE_QUIET = 1
    const val MODE_OFF = 2

    // Moments (MPPetsPlan.moment).
    const val HELLO = 1
    const val PURR = 2
    const val PEEKABOO = 3
    const val FOUND = 4
    const val DOZE = 5
    const val DUET = 6
    const val FLOW = 7

    const val STYLE_FADE = 1
    const val RIPPLE_NONE = 0
    const val RIPPLE_SOFT = 1
    const val RIPPLE_LINE = 2

    // Outcomes (MP_PETS_COMPLETED ...).
    const val COMPLETED = 0
    const val INTERRUPTED = 1
    const val CANCELLED = 2

    // mp_pets_rest_pose results.
    const val REST_KEEP = 0
    const val REST_EDGE_REST = 1
    const val REST_EDGE_PEEK = 2
    const val REST_EDGE_LOUNGE = 3
    const val REST_SLEEP = 4

    /** [decide]'s `out` layout: token, moment, who, style, ripple, duration_ms, delay_ms. */
    const val PLAN_INTS = 7
    const val STATE_BYTES = 2884
    const val DELTA_BYTES = 84

    /** mp_pets_idle_delay_ms result meaning "do not arm the timer". */
    const val NEVER = 0xFFFFFFFFL

    /** False when the native library cannot be loaded: the cats then keep their old behaviour. */
    val available: Boolean = try {
        System.loadLibrary("meowpuut_jni")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    /** Always returns a handle; bad or empty data starts fresh. [status] (one int, may be null): 0 ok, 1 fresh, 2 corrupt, 3 unknown version. */
    @JvmStatic external fun fromBytes(data: ByteArray?, status: IntArray?): Long
    @JvmStatic external fun free(handle: Long)

    /** True and fills [out] ([PLAN_INTS] ints) when the cats do something. Times are unsigned 32-bit milliseconds. */
    @JvmStatic external fun decide(
        handle: Long, trigger: Int, arg: Int,
        flags: Int, quietMs: Int, mode: Int, cats: Int,
        monoMs: Long, day: Int, minute: Int, seed: Long, out: IntArray,
    ): Boolean

    /** The cat bits that earned a play. */
    @JvmStatic external fun resolve(handle: Long, token: Int, outcome: Int, monoMs: Long, day: Int, minute: Int): Int
    @JvmStatic external fun idleDelayMs(
        handle: Long, flags: Int, quietMs: Int, mode: Int, cats: Int, monoMs: Long, day: Int, minute: Int,
    ): Long
    @JvmStatic external fun restPose(pet: Int, flags: Int, mode: Int, cats: Int, minute: Int): Int

    /** Writes [STATE_BYTES] bytes into [out] (at least that long); returns the count. */
    @JvmStatic external fun toBytes(handle: Long, out: ByteArray): Int
    @JvmStatic external fun takeDelta(handle: Long, out: ByteArray): Int
    @JvmStatic external fun applyDelta(handle: Long, data: ByteArray): Boolean
    @JvmStatic external fun merge(handle: Long, data: ByteArray): Boolean
    @JvmStatic external fun importLegacy(
        handle: Long, graphemes: Long, mimimoPets: Long, mimimoFalls: Long, momoPets: Long, momoFalls: Long,
    ): Boolean
    @JvmStatic external fun clear(handle: Long)
}
