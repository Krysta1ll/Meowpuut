package com.krysta1ll.meowpuut.android.ime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.krysta1ll.meowpuut.android.engine.NativePets
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The level formula of the first Android version, as plain counters. The pets
 * core (crates/meowpuut-pets) now owns the numbers; this stays as a readable
 * reference of the rule (one point per 200 graphemes and per three contacts)
 * that the unit tests pin.
 */
internal class CatAffinityCounters(
    initialGraphemes: Long = 0L,
    initialPets: (Cat) -> Long = { 0L },
    initialFalls: (Cat) -> Long = { 0L },
) {
    var graphemes = initialGraphemes.coerceAtLeast(0L)
        private set
    private val pets = Cat.entries.map { initialPets(it).coerceAtLeast(0L) }.toLongArray()
    private val falls = Cat.entries.map { initialFalls(it).coerceAtLeast(0L) }.toLongArray()

    fun addGraphemes(count: Int) { graphemes = add(graphemes, count.toLong().coerceAtLeast(0L)) }
    fun pet(cat: Cat) { pets[cat.ordinal] = add(pets(cat), 1) }
    fun fall(cat: Cat) { falls[cat.ordinal] = add(falls(cat), 1) }
    fun pets(cat: Cat) = pets[cat.ordinal]
    fun falls(cat: Cat) = falls[cat.ordinal]

    /** One point per 200 committed graphemes and per three contacts with this cat. */
    fun score(cat: Cat): Int =
        (graphemes / 200 + add(pets(cat), falls(cat)) / 3).coerceAtMost(100).toInt()

    private fun add(current: Long, delta: Long): Long = current + delta.coerceAtMost(Long.MAX_VALUE - current)
}

/** What the keyboard knows about itself when it asks the pets core (mp_pets MPPetsAmbience). Reused, never allocated per call. */
internal class PetAmbience {
    var flags = 0
    /** Milliseconds since the last key press; -1 (unsigned 32-bit max) when none. */
    var quietMs = -1
    var mode = NativePets.MODE_FULL
    var cats = NativePets.MIMIMO or NativePets.MOMO
}

/** The clock as the core wants it: monotonic milliseconds, the local day index and the local minute. */
internal class PetClock {
    var mono = 0L
    var day = 0
    var minute = 0

    fun sample(): PetClock {
        mono = SystemClock.elapsedRealtime()
        val now = System.currentTimeMillis()
        val local = now + TimeZone.getDefault().getOffset(now)
        day = Math.floorDiv(local, DAY_MS).toInt()
        minute = (Math.floorMod(local, DAY_MS) / 60_000L).toInt()
        return this
    }

    private companion object { const val DAY_MS = 86_400_000L }
}

/** A plan from the pets core. One instance is refilled by every [LocalCatAffinity.decide]. */
internal class PetPlan {
    var token = 0
    var moment = 0
    var who = 0
    var style = 0
    var ripple = 0
    var durationMs = 0
    var delayMs = 0
    val fade get() = style == NativePets.STYLE_FADE

    fun copyFrom(other: PetPlan) {
        token = other.token; moment = other.moment; who = other.who; style = other.style
        ripple = other.ripple; durationMs = other.durationMs; delayMs = other.delayMs
    }
}

/**
 * The pets core behind JNI, and its one file: filesDir/PetBook.bin (fixed size,
 * checksummed, only counters, bands and local day numbers; never text). The native
 * handle is used on the keyboard's main thread only, and only in memory. All disk
 * work (the first read, the merged write) runs on one background thread, so no
 * file is touched on the main thread while the user types (the one exception is
 * [release], when the keyboard service goes away). Without the native library
 * everything is a no-op and the cats keep their old behaviour.
 *
 * Writes are merged, not overwritten: under a file lock the unsent increments
 * are added to what is on disk, the live state is merged in, and the result is
 * renamed over the file, so two processes (or an old copy after "clear") cannot
 * undo each other. The merged bytes come back to the main thread and are merged
 * into the live handle.
 */
internal class LocalCatAffinity(context: Context) {
    private val dir = context.filesDir
    private val file = File(dir, FILE)
    private val legacy = context.getSharedPreferences(LEGACY, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val pendingWrite = Runnable(::flush)
    private val io = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "meowpuut-pets-io").apply { isDaemon = true } }
    private var dirty = false
    private var released = false
    private var handle = 0L
    private val out = IntArray(NativePets.PLAN_INTS)

    /** The last plan [decide] returned true for. */
    val plan = PetPlan()
    val ready get() = handle != 0L

    /** Called on the main thread once the book has been read (the first appearance may have come before). */
    var onReady: (() -> Unit)? = null

    /** A second listener for the same moment: the journey's start comes from the book too (see [committedCharacters]). */
    var onOpened: (() -> Unit)? = null

    init {
        if (NativePets.available) {
            io.execute {
                val bytes = readFile()
                handler.post { opened(bytes) }
            }
        }
    }

    private fun opened(bytes: ByteArray?) {
        if (released) return
        handle = NativePets.fromBytes(bytes, null)
        if (handle == 0L) return
        // The counters of the first Android version move over once; the core merges with max, so it is idempotent.
        if (!legacy.getBoolean(IMPORTED, false)) {
            NativePets.importLegacy(
                handle, legacy.getLong("committed_graphemes", 0L),
                legacy.getLong("pets_mimimo", 0L), legacy.getLong("falls_mimimo", legacy.getLong("throws_mimimo", 0L)),
                legacy.getLong("pets_momo", 0L), legacy.getLong("falls_momo", legacy.getLong("throws_momo", 0L)),
            )
            legacy.edit().putBoolean(IMPORTED, true).apply()
            scheduleWrite()
        }
        onReady?.invoke()
        onOpened?.invoke()
    }

    /**
     * How many characters have been committed in all, as the cats count them (the core's committed graphemes, header of its state): the
     * keyboard's 字景 journey starts from eight times this, so it is the same journey whatever skin was worn, and 行旅 reads the same number
     * from the file. 0 until the book has been read. One copy of the 2.9 KB state, when the world starts; never per key.
     */
    fun committedCharacters(): Long {
        if (handle == 0L) return 0L
        val state = ByteArray(NativePets.STATE_BYTES)
        if (NativePets.toBytes(handle, state) != NativePets.STATE_BYTES) return 0L
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (state[8 + i].toLong() and 0xFF)
        return if (v < 0) Long.MAX_VALUE else v
    }

    /** True and [plan] filled when the cats do something. Counting happens inside (commits, taps, drops). */
    fun decide(trigger: Int, arg: Int, ambience: PetAmbience, clock: PetClock, seed: Long): Boolean {
        if (handle == 0L) return false
        val planned = NativePets.decide(
            handle, trigger, arg, ambience.flags, ambience.quietMs, ambience.mode, ambience.cats,
            clock.mono, clock.day, clock.minute, seed, out,
        )
        // Idle only starts a doze (a state, not a record).
        if (trigger != NativePets.IDLE) scheduleWrite()
        if (!planned) return false
        plan.token = out[0]; plan.moment = out[1]; plan.who = out[2]; plan.style = out[3]
        plan.ripple = out[4]; plan.durationMs = out[5]; plan.delayMs = out[6]
        return true
    }

    /** Reports how a plan ended; the cat bits that earned a play. */
    fun resolve(token: Int, outcome: Int, clock: PetClock): Int {
        if (handle == 0L || token == 0) return 0
        val who = NativePets.resolve(handle, token, outcome, clock.mono, clock.day, clock.minute)
        if (who != 0) scheduleWrite()
        return who
    }

    /** Milliseconds until the single idle timer should fire (0: now), or [NativePets.NEVER]. */
    fun idleDelayMs(ambience: PetAmbience, clock: PetClock): Long =
        if (handle == 0L) NativePets.NEVER else NativePets.idleDelayMs(
            handle, ambience.flags, ambience.quietMs, ambience.mode, ambience.cats, clock.mono, clock.day, clock.minute,
        )

    /** MP_PETS_REST_* for a cat (1 Mimimo, 2 Momo) at the local minute of the day. */
    fun restPose(pet: Int, ambience: PetAmbience, minute: Int): Int =
        if (handle == 0L) NativePets.REST_KEEP else NativePets.restPose(pet, ambience.flags, ambience.mode, ambience.cats, minute)

    /** "Clear cat records": the core raises its epoch, so an older copy cannot bring anything back. */
    fun clear() {
        legacy.edit().remove("committed_graphemes").remove("pets_mimimo").remove("falls_mimimo").remove("throws_mimimo")
            .remove("pets_momo").remove("falls_momo").remove("throws_momo").putBoolean(IMPORTED, true).apply()
        if (handle == 0L) return
        NativePets.clear(handle)
        flush()
    }

    /** The keyboard service is going away: the last write is waited for (briefly), then the handle is freed. */
    fun release() {
        released = true
        onReady = null
        onOpened = null
        handler.removeCallbacks(pendingWrite)
        val snapshot = snapshot()
        if (snapshot != null) io.execute { writeMerged(snapshot) }
        io.shutdown()
        try { io.awaitTermination(1, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        if (handle != 0L) NativePets.free(handle)
        handle = 0L
    }

    /** Takes the unsent increments and the live state here (memory only), writes them on the io thread. */
    fun flush() {
        handler.removeCallbacks(pendingWrite)
        dirty = false
        if (released) return
        val snapshot = snapshot() ?: return
        io.execute {
            val merged = writeMerged(snapshot) ?: return@execute
            handler.post { if (handle != 0L) NativePets.merge(handle, merged) }
        }
    }

    private class Snapshot(val delta: ByteArray, val live: ByteArray, val hasDelta: Boolean)

    private fun snapshot(): Snapshot? {
        if (handle == 0L) return null
        val delta = ByteArray(NativePets.DELTA_BYTES)
        val live = ByteArray(NativePets.STATE_BYTES)
        val hasDelta = NativePets.takeDelta(handle, delta) == NativePets.DELTA_BYTES
        if (NativePets.toBytes(handle, live) != NativePets.STATE_BYTES) return null
        return Snapshot(delta, live, hasDelta)
    }

    /** On the io thread: file lock, merge with what is on disk, atomic rename. Returns the merged state. */
    private fun writeMerged(snapshot: Snapshot): ByteArray? {
        try {
            RandomAccessFile(File(dir, LOCK), "rw").use { lockFile ->
                lockFile.channel.lock().use {
                    val onDisk = NativePets.fromBytes(readFile(), null)
                    if (onDisk == 0L) return null
                    try {
                        if (snapshot.hasDelta) NativePets.applyDelta(onDisk, snapshot.delta)
                        NativePets.merge(onDisk, snapshot.live)
                        val disk = ByteArray(NativePets.STATE_BYTES)
                        if (NativePets.toBytes(onDisk, disk) != NativePets.STATE_BYTES) return null
                        val temporary = File(dir, "$FILE.tmp")
                        temporary.writeBytes(disk)
                        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
                        return disk
                    } finally {
                        NativePets.free(onDisk)
                    }
                }
            }
        } catch (_: IOException) {
        } catch (_: OverlappingFileLockException) {
        }
        return null
    }

    private fun readFile(): ByteArray? = try {
        if (file.isFile && file.length() in 1..MAX_FILE) file.readBytes() else null
    } catch (_: IOException) {
        null
    }

    private fun scheduleWrite() {
        if (dirty || released) return
        dirty = true
        handler.postDelayed(pendingWrite, 5_000L)
    }

    companion object {
        const val FILE = "PetBook.bin"
        private const val LOCK = "PetBook.lock"
        private const val LEGACY = "cat_affinity"
        private const val IMPORTED = "pets_core_imported"
        private const val MAX_FILE = 64L * 1024

        /** The app deletes the file when the keyboard is not running; a running keyboard clears itself on the request. */
        fun deleteFile(context: Context) {
            File(context.filesDir, FILE).delete()
            forgetLegacyCounters(context)
        }

        /** The counters of the first Android version are gone for good (and are not imported again). */
        fun forgetLegacyCounters(context: Context) {
            context.getSharedPreferences(LEGACY, Context.MODE_PRIVATE).edit().clear().putBoolean(IMPORTED, true).apply()
        }
    }
}
