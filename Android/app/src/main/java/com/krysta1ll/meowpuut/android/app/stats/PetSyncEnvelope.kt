package com.krysta1ll.meowpuut.android.app.stats

import java.security.SecureRandom

/**
 * Route A cross-platform, zero-PII CRDT sync envelope (`MPSY` v1, fixed 3,072 bytes) and
 * Crockford Base32 anonymous pass code (`MEOW-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX`),
 * byte-for-byte compatible with `Engine/crates/meowpuut-pets/src/sync.rs`.
 */
sealed interface PetSyncDecodeResult {
    data class Ok(val envelope: PetSyncEnvelope) : PetSyncDecodeResult
    data object BadLength : PetSyncDecodeResult
    data object BadMagic : PetSyncDecodeResult
    data class BadVersion(val version: Int) : PetSyncDecodeResult
    data object BadChecksum : PetSyncDecodeResult
    data class BadState(val error: PetCodecResult) : PetSyncDecodeResult
}

class PetSyncEnvelope(
    val flags: Int = 0,
    val groupPrefix: ByteArray = ByteArray(8),
    val state: PetSyncState = PetSyncState(),
    val progress: PetAchievementProgress = PetAchievementProgress(),
    val unlockedBits: ByteArray = ByteArray(16),
) {
    fun hasBit(idx: Int): Boolean {
        if (idx !in 0 until MAX_UNLOCK_BITS || unlockedBits.size != 16) return false
        return (unlockedBits[idx / 8].toInt() and (1 shl (idx % 8))) != 0
    }

    fun unlockedApis(): List<String> =
        PetAchievementsCatalog.TABLE.mapIndexedNotNull { idx, def -> if (hasBit(idx)) def.api else null }

    fun withUnlocked(api: String): PetSyncEnvelope {
        val idx = PetAchievementsCatalog.INDEX_BY_API[api] ?: return this
        val bits = unlockedBits.copyOf(16)
        bits[idx / 8] = (bits[idx / 8].toInt() or (1 shl (idx % 8))).toByte()
        return PetSyncEnvelope(flags, groupPrefix.copyOf(8), state, progress, bits)
    }

    fun refreshed(): PetSyncEnvelope {
        val already = unlockedApis().toHashSet()
        val newly = PetAchievementsEvaluator.evaluate(state, progress, already)
        if (newly.isEmpty()) return this
        val bits = unlockedBits.copyOf(16)
        for (u in newly) {
            val idx = PetAchievementsCatalog.INDEX_BY_API[u.api] ?: continue
            bits[idx / 8] = (bits[idx / 8].toInt() or (1 shl (idx % 8))).toByte()
        }
        return PetSyncEnvelope(flags, groupPrefix.copyOf(8), state, progress, bits)
    }

    fun clearedForReset(): PetSyncEnvelope = PetSyncEnvelope(
        flags = flags,
        groupPrefix = groupPrefix.copyOf(8),
        state = state.cleared(),
        progress = PetAchievementProgress(),
        unlockedBits = ByteArray(16),
    )

    /** Commutative, associative, idempotent CRDT merge matching `sync.rs::SyncEnvelope::merge`. */
    fun merge(other: PetSyncEnvelope): PetSyncEnvelope {
        val mergedState = state.merged(other.state)
        val mergedFlags = (flags or other.flags) and 0xFFFF
        val mergedPrefix = if (groupPrefix.any { it.toInt() != 0 }) groupPrefix.copyOf(8) else other.groupPrefix.copyOf(8)
        val mergedProgress: PetAchievementProgress
        val mergedBits: ByteArray
        when {
            state.epoch > other.state.epoch -> {
                mergedProgress = progress
                mergedBits = unlockedBits.copyOf(16)
            }
            other.state.epoch > state.epoch -> {
                mergedProgress = other.progress
                mergedBits = other.unlockedBits.copyOf(16)
            }
            else -> {
                mergedProgress = progress.merged(other.progress)
                mergedBits = ByteArray(16) { i ->
                    ((unlockedBits.getOrElse(i) { 0 }.toInt()) or (other.unlockedBits.getOrElse(i) { 0 }.toInt())).toByte()
                }
            }
        }
        return PetSyncEnvelope(mergedFlags, mergedPrefix, mergedState, mergedProgress, mergedBits).refreshed()
    }

    fun toBytes(): ByteArray {
        val buf = ByteArray(SYNC_BYTES)
        MAGIC.copyInto(buf, 0)
        putU16(buf, 4, VERSION)
        putU16(buf, 6, flags)
        groupPrefix.copyInto(buf, 8, 0, minOf(8, groupPrefix.size))
        val stateBytes = state.toBytes()
        stateBytes.copyInto(buf, STATE_OFF)

        var p = PROG_OFF
        fun putU32(v: UInt) {
            for (i in 0 until 4) buf[p++] = ((v shr (i * 8)) and 0xFFu).toByte()
        }
        putU32(state.epoch)
        putU32(progress.lifetimeDays)
        putU32(progress.maxDayGraphemes)
        putU32(progress.peakDays1500)
        putU32(progress.snowPeaks4000)
        putU32((progress.places and PetAchievementPlace.ALL_MASK).toUInt())
        putU32(progress.festivals)
        putU32(progress.scrollShares)
        putU32(progress.outings)
        putU32(progress.purrs)
        putU32(progress.rides)
        putU32(progress.caught)
        putU32(progress.found)
        putU32(progress.roof)
        putU32(progress.nose)
        putU32(progress.moved)
        putU32(progress.cuddles)
        putU32(progress.outfits)
        putU64(buf, p, progress.steps)
        p += 8
        unlockedBits.copyInto(buf, p, 0, minOf(16, unlockedBits.size))

        val sum = fnv1a64(buf, 0, CHECKSUM_OFF)
        putU64(buf, CHECKSUM_OFF, sum)
        return buf
    }

    override fun equals(other: Any?): Boolean = other is PetSyncEnvelope &&
        flags == other.flags &&
        groupPrefix.contentEquals(other.groupPrefix) &&
        state == other.state &&
        progress == other.progress &&
        unlockedBits.contentEquals(other.unlockedBits)

    override fun hashCode(): Int {
        var r = flags
        r = 31 * r + groupPrefix.contentHashCode()
        r = 31 * r + state.hashCode()
        r = 31 * r + progress.hashCode()
        r = 31 * r + unlockedBits.contentHashCode()
        return r
    }

    companion object {
        const val SYNC_BYTES = 3_072
        const val MAX_UNLOCK_BITS = 128
        const val FLAG_PASS_ACTIVE = 1 shl 0
        const val FILE_EXTENSION = "mpsync"

        private val MAGIC = byteArrayOf('M'.code.toByte(), 'P'.code.toByte(), 'S'.code.toByte(), 'Y'.code.toByte())
        private const val VERSION = 1
        private const val STATE_OFF = 16
        private const val PROG_OFF = STATE_OFF + PetSyncState.STATE_BYTES // 2900
        private const val CHECKSUM_OFF = SYNC_BYTES - 8 // 3064

        fun fromBytes(bytes: ByteArray): PetSyncDecodeResult {
            if (bytes.size != SYNC_BYTES) return PetSyncDecodeResult.BadLength
            for (i in 0 until 4) if (bytes[i] != MAGIC[i]) return PetSyncDecodeResult.BadMagic
            val ver = getU16(bytes, 4)
            if (ver != VERSION) return PetSyncDecodeResult.BadVersion(ver)
            val expected = getU64(bytes, CHECKSUM_OFF)
            if (fnv1a64(bytes, 0, CHECKSUM_OFF) != expected) return PetSyncDecodeResult.BadChecksum

            val flags = getU16(bytes, 6)
            val prefix = bytes.copyOfRange(8, 16)
            val stateResult = PetSyncState.fromBytes(bytes, STATE_OFF, PetSyncState.STATE_BYTES)
            if (stateResult !is PetCodecResult.Ok) return PetSyncDecodeResult.BadState(stateResult)

            var p = PROG_OFF
            fun nextU32(): UInt {
                val v = getU32(bytes, p)
                p += 4
                return v
            }
            nextU32() // embedded epoch mirrors state.epoch
            val lifetimeDays = nextU32()
            val maxDayGraphemes = nextU32()
            val peakDays1500 = nextU32()
            val snowPeaks4000 = nextU32()
            val places = nextU32().toInt() and PetAchievementPlace.ALL_MASK
            val festivals = nextU32()
            val scrollShares = nextU32()
            val outings = nextU32()
            val purrs = nextU32()
            val rides = nextU32()
            val caught = nextU32()
            val found = nextU32()
            val roof = nextU32()
            val nose = nextU32()
            val moved = nextU32()
            val cuddles = nextU32()
            val outfits = nextU32()
            val steps = getU64(bytes, p)
            p += 8
            val unlockedBits = bytes.copyOfRange(p, p + 16)

            val progress = PetAchievementProgress(
                places = places, festivals = festivals, lifetimeDays = lifetimeDays,
                maxDayGraphemes = maxDayGraphemes, peakDays1500 = peakDays1500, snowPeaks4000 = snowPeaks4000,
                outings = outings, purrs = purrs, rides = rides, caught = caught, found = found, roof = roof,
                steps = steps, nose = nose, moved = moved, cuddles = cuddles, outfits = outfits, scrollShares = scrollShares,
            )
            return PetSyncDecodeResult.Ok(PetSyncEnvelope(flags, prefix, stateResult.state, progress, unlockedBits))
        }

        fun fnv1a64(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): ULong {
            var h = 0xcbf29ce484222325UL
            for (i in offset until offset + length) {
                h = h xor (bytes[i].toULong() and 0xFFUL)
                h *= 0x0100000001b3UL
            }
            return h
        }

        private fun putU16(b: ByteArray, at: Int, v: Int) {
            b[at] = (v and 0xFF).toByte()
            b[at + 1] = ((v shr 8) and 0xFF).toByte()
        }

        private fun putU64(b: ByteArray, at: Int, v: ULong) {
            for (i in 0 until 8) b[at + i] = ((v shr (i * 8)) and 0xFFUL).toByte()
        }

        private fun getU16(b: ByteArray, at: Int): Int =
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

        private fun getU32(b: ByteArray, at: Int): UInt =
            (b[at].toUInt() and 0xFFu) or
                ((b[at + 1].toUInt() and 0xFFu) shl 8) or
                ((b[at + 2].toUInt() and 0xFFu) shl 16) or
                ((b[at + 3].toUInt() and 0xFFu) shl 24)

        private fun getU64(b: ByteArray, at: Int): ULong {
            var v = 0UL
            for (i in 7 downTo 0) v = (v shl 8) or (b[at + i].toULong() and 0xFFUL)
            return v
        }
    }
}

/** Crockford Base32 anonymous pass code (`MEOW-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX`, 128-bit key + 1-byte FNV-1a checksum). */
object PetPassCode {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val rng = SecureRandom()

    fun generateKey(): ByteArray = ByteArray(16).also { rng.nextBytes(it) }

    fun encode(key: ByteArray): String {
        require(key.size == 16) { "Pass code key must be 16 bytes" }
        val raw = ByteArray(17)
        key.copyInto(raw, 0, 0, 16)
        raw[16] = (PetSyncEnvelope.fnv1a64(key) and 0xFFUL).toByte()

        val chars = CharArray(28)
        var bitBuf = 0
        var bits = 0
        var outIdx = 0
        for (b in raw) {
            bitBuf = (bitBuf shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                chars[outIdx++] = ALPHABET[(bitBuf ushr bits) and 0x1F]
            }
        }
        if (bits > 0) {
            chars[outIdx] = ALPHABET[(bitBuf shl (5 - bits)) and 0x1F]
        }
        val sb = StringBuilder(39).append("MEOW")
        for (chunk in 0 until 28 step 4) {
            sb.append('-').append(chars, chunk, 4)
        }
        return sb.toString()
    }

    fun decode(code: String): ByteArray? {
        val stripped = code.trim().uppercase()
        val body = if (stripped.startsWith("MEOW-")) stripped.substring(5) else stripped
        val vals = IntArray(28)
        var n = 0
        for (ch in body) {
            if (ch == '-' || ch == ' ') continue
            if (n >= 28) return null
            val norm = when (ch) {
                'O' -> '0'
                'I', 'L' -> '1'
                else -> ch
            }
            val pos = ALPHABET.indexOf(norm)
            if (pos < 0) return null
            vals[n++] = pos
        }
        if (n != 28) return null
        val raw = ByteArray(17)
        var bitBuf = 0
        var bits = 0
        var outIdx = 0
        for (v in vals) {
            bitBuf = (bitBuf shl 5) or v
            bits += 5
            while (bits >= 8 && outIdx < 17) {
                bits -= 8
                raw[outIdx++] = ((bitBuf ushr bits) and 0xFF).toByte()
            }
        }
        val key = raw.copyOf(16)
        val expected = (PetSyncEnvelope.fnv1a64(key) and 0xFFUL).toByte()
        return if (expected == raw[16]) key else null
    }

    fun groupPrefixOf(key: ByteArray): ByteArray {
        require(key.size == 16)
        val h = PetSyncEnvelope.fnv1a64(key)
        return ByteArray(8) { i -> ((h shr (i * 8)) and 0xFFUL).toByte() }
    }
}
