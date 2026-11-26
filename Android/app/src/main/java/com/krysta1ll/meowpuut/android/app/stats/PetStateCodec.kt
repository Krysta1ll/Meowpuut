package com.krysta1ll.meowpuut.android.app.stats

/**
 * Pure-Kotlin mirror of `Engine/crates/meowpuut-pets` (`lib.rs`, `book.rs`, `codec.rs`).
 * Exact 2,884-byte little-endian binary layout (versions 1 and 2) with FNV-1a-32 checksum,
 * commutative/associative/idempotent CRDT [merge], and bond level calculation.
 */
sealed interface PetCodecResult {
    data class Ok(val state: PetSyncState) : PetCodecResult
    data object Corrupt : PetCodecResult
    data class UnsupportedVersion(val version: Int) : PetCodecResult
}

data class PetContacts(
    val pets: ULong = 0UL,
    val falls: ULong = 0UL,
    val plays: ULong = 0UL,
) {
    val totalContacts: ULong get() = saturatingAdd(pets, falls)

    fun joined(other: PetContacts) = PetContacts(
        pets = maxOf(pets, other.pets),
        falls = maxOf(falls, other.falls),
        plays = maxOf(plays, other.plays),
    )

    companion object {
        fun saturatingAdd(a: ULong, b: ULong): ULong =
            if (ULong.MAX_VALUE - a < b) ULong.MAX_VALUE else a + b
    }
}

data class PetSnapshot(
    val committedGraphemes: ULong = 0UL,
    val mimimo: PetContacts = PetContacts(),
    val momo: PetContacts = PetContacts(),
) {
    fun bondLevel(isMimimo: Boolean): Int {
        val c = if (isMimimo) mimimo else momo
        val raw = committedGraphemes / GRAPHEMES_PER_LEVEL + c.totalContacts / CONTACTS_PER_LEVEL
        return minOf(MAX_LEVEL.toULong(), raw).toInt()
    }

    fun joined(other: PetSnapshot) = PetSnapshot(
        committedGraphemes = maxOf(committedGraphemes, other.committedGraphemes),
        mimimo = mimimo.joined(other.mimimo),
        momo = momo.joined(other.momo),
    )

    companion object {
        const val GRAPHEMES_PER_LEVEL = 200UL
        const val CONTACTS_PER_LEVEL = 3UL
        const val MAX_LEVEL = 100
    }
}

data class PetDailyState(
    val day: UInt = 0u,
    val graphemes: UInt = 0u,
    val greeted: Boolean = false,
    val spontaneous: Int = 0,
    val purrsMimimo: Int = 0,
    val purrsMomo: Int = 0,
    val duets: Int = 0,
) {
    fun joined(other: PetDailyState): PetDailyState = when {
        day > other.day -> this
        other.day > day -> other
        else -> PetDailyState(
            day = day,
            graphemes = maxOf(graphemes, other.graphemes),
            greeted = greeted || other.greeted,
            spontaneous = maxOf(spontaneous, other.spontaneous),
            purrsMimimo = maxOf(purrsMimimo, other.purrsMimimo),
            purrsMomo = maxOf(purrsMomo, other.purrsMomo),
            duets = maxOf(duets, other.duets),
        )
    }
}

data class PetBookMark(
    val day: UInt = 0u,
    val size: Int = 0,
    val who: Int = 0,
    val part: Int = 0,
    val place: Int = 0,
) {
    val isEmpty: Boolean get() = day == 0u

    val isValid: Boolean
        get() = if (day == 0u) size == 0 && who == 0 && part == 0 && place == 0
        else size in 0..MAX_SIZE && who in 0..3 && part in 0..PART_BITS && place in 0..PLACE_CODE_MAX

    fun joined(other: PetBookMark): PetBookMark = when {
        day > other.day -> this
        other.day > day -> other
        else -> PetBookMark(
            day = day,
            size = maxOf(size, other.size),
            who = who or other.who,
            part = part or other.part,
            place = further(place, other.place),
        )
    }

    companion object {
        const val MAX_SIZE = 4
        const val PART_BITS = 0b1_1111
        const val PLACE_UNKNOWN = 0
        const val PLACE_CODE_MAX = 15

        /** Exact `ROAD_ORDER` from `book.rs`. */
        val ROAD_ORDER = intArrayOf(0, 2, 1, 3, 4, 5, 7, 8, 9, 10, 11, 13, 14, 15, 6, 12)
        private val ROAD_RANK = IntArray(16).apply {
            for (i in ROAD_ORDER.indices) this[ROAD_ORDER[i]] = i
        }

        fun further(a: Int, b: Int): Int {
            val ra = if (a in 0..PLACE_CODE_MAX) ROAD_RANK[a] else 0
            val rb = if (b in 0..PLACE_CODE_MAX) ROAD_RANK[b] else 0
            return if (rb > ra) b else a
        }
    }
}

class PetPawBook(val slots: Array<PetBookMark> = Array(BOOK_DAYS) { PetBookMark() }) {
    val count: Int get() = slots.count { !it.isEmpty }
    val isEmpty: Boolean get() = count == 0
    val hasPlaces: Boolean get() = slots.any { it.place != PetBookMark.PLACE_UNKNOWN }

    fun copy(): PetPawBook = PetPawBook(slots.copyOf())

    fun record(day: UInt, size: Int, who: Int, part: Int) {
        if (day == 0u) return
        val mark = PetBookMark(day, size.coerceIn(0, PetBookMark.MAX_SIZE), who and 3, part and PetBookMark.PART_BITS, 0)
        joinSlot(mark)
    }

    fun reach(day: UInt, placeCode: Int) {
        if (day == 0u || placeCode !in 1..PetBookMark.PLACE_CODE_MAX) return
        joinSlot(PetBookMark(day = day, place = placeCode))
    }

    private fun joinSlot(mark: PetBookMark) {
        val idx = (mark.day % BOOK_DAYS.toUInt()).toInt()
        slots[idx] = slots[idx].joined(mark)
    }

    fun get(day: UInt): PetBookMark? {
        if (day == 0u) return null
        val m = slots[(day % BOOK_DAYS.toUInt()).toInt()]
        return if (m.day == day) m else null
    }

    fun merge(other: PetPawBook) {
        for (i in 0 until BOOK_DAYS) slots[i] = slots[i].joined(other.slots[i])
    }

    fun marks(): List<PetBookMark> {
        var start = 0
        var latest = 0u
        for (i in 0 until BOOK_DAYS) {
            if (slots[i].day > latest) {
                latest = slots[i].day
                start = i
            }
        }
        val out = ArrayList<PetBookMark>(BOOK_DAYS)
        for (k in 1..BOOK_DAYS) {
            val m = slots[(start + k) % BOOK_DAYS]
            if (!m.isEmpty) out += m
        }
        return out
    }

    override fun equals(other: Any?): Boolean = other is PetPawBook && slots.contentEquals(other.slots)
    override fun hashCode(): Int = slots.contentHashCode()

    companion object {
        const val BOOK_DAYS = 400

        fun fromSlots(slots: Array<PetBookMark>): PetPawBook? {
            if (slots.size != BOOK_DAYS) return null
            for (i in 0 until BOOK_DAYS) {
                val m = slots[i]
                if (!m.isValid) return null
                if (m.day != 0u && (m.day % BOOK_DAYS.toUInt()).toInt() != i) return null
            }
            return PetPawBook(slots)
        }
    }
}

data class PetSyncState(
    val epoch: UInt = 0u,
    val snapshot: PetSnapshot = PetSnapshot(),
    val daily: PetDailyState = PetDailyState(),
    val book: PetPawBook = PetPawBook(),
) {
    val version: Int get() = if (book.hasPlaces) VERSION_2 else VERSION_1

    fun cleared(): PetSyncState {
        val nextEpoch = (epoch + 1u).let { if (it == 0u) 1u else it }
        return PetSyncState(epoch = nextEpoch)
    }

    fun merged(other: PetSyncState): PetSyncState = when {
        other.epoch > epoch -> other.copy(book = other.book.copy())
        other.epoch < epoch -> copy(book = book.copy())
        else -> {
            val nextBook = book.copy()
            nextBook.merge(other.book)
            PetSyncState(
                epoch = epoch,
                snapshot = snapshot.joined(other.snapshot),
                daily = daily.joined(other.daily),
                book = nextBook,
            )
        }
    }

    fun toBytes(): ByteArray {
        val out = ByteArray(STATE_BYTES)
        var p = 0
        fun putU8(v: Int) { out[p++] = (v and 0xFF).toByte() }
        fun putU32(v: UInt) {
            for (i in 0 until 4) out[p++] = ((v shr (i * 8)) and 0xFFu).toByte()
        }
        fun putU64(v: ULong) {
            for (i in 0 until 8) out[p++] = ((v shr (i * 8)) and 0xFFUL).toByte()
        }
        putU8(MAGIC_0); putU8(MAGIC_1); putU8(version); putU8(0)
        putU32(epoch)
        putU64(snapshot.committedGraphemes)
        for (c in listOf(snapshot.mimimo, snapshot.momo)) {
            putU64(c.pets); putU64(c.falls); putU64(c.plays)
        }
        putU32(daily.day); putU32(daily.graphemes)
        putU8(if (daily.greeted) 1 else 0); putU8(daily.spontaneous)
        putU8(daily.purrsMimimo); putU8(daily.purrsMomo); putU8(daily.duets)
        p += 3
        for (m in book.slots) {
            putU32(m.day); putU8(m.size); putU8(m.who or (m.place shl 4)); putU8(m.part)
        }
        putU32(fnv1a32(out, 0, STATE_BYTES - 4))
        return out
    }

    companion object {
        const val STATE_BYTES = 2884
        const val VERSION_1 = 1
        const val VERSION_2 = 2
        private const val MAGIC_0 = 0x9C
        private const val MAGIC_1 = 0xA7
        private const val MARKS_AT = 80
        private const val MARK_BYTES = 7

        fun sizeBand(graphemes: UInt): Int = when {
            graphemes <= 99u -> 0
            graphemes <= 499u -> 1
            graphemes <= 1499u -> 2
            graphemes <= 3999u -> 3
            else -> 4
        }

        fun fromBytes(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): PetCodecResult {
            if (length < 4 || offset < 0 || offset + length > bytes.size) return PetCodecResult.Corrupt
            if ((bytes[offset].toInt() and 0xFF) != MAGIC_0 || (bytes[offset + 1].toInt() and 0xFF) != MAGIC_1) {
                return PetCodecResult.Corrupt
            }
            val ver = bytes[offset + 2].toInt() and 0xFF
            if (ver != VERSION_1 && ver != VERSION_2) return PetCodecResult.UnsupportedVersion(ver)
            if (length != STATE_BYTES || bytes[offset + 3].toInt() != 0) return PetCodecResult.Corrupt
            val stored = getU32(bytes, offset + STATE_BYTES - 4)
            if (stored != fnv1a32(bytes, offset, STATE_BYTES - 4)) return PetCodecResult.Corrupt
            val flags = bytes[offset + 72].toInt() and 0xFF
            if (flags > 1 || bytes[offset + 77].toInt() != 0 || bytes[offset + 78].toInt() != 0 || bytes[offset + 79].toInt() != 0) {
                return PetCodecResult.Corrupt
            }
            val epoch = getU32(bytes, offset + 4)
            val graphemes = getU64(bytes, offset + 8)
            val mimimo = PetContacts(getU64(bytes, offset + 16), getU64(bytes, offset + 24), getU64(bytes, offset + 32))
            val momo = PetContacts(getU64(bytes, offset + 40), getU64(bytes, offset + 48), getU64(bytes, offset + 56))
            val daily = PetDailyState(
                day = getU32(bytes, offset + 64),
                graphemes = getU32(bytes, offset + 68),
                greeted = (flags and 1) != 0,
                spontaneous = bytes[offset + 73].toInt() and 0xFF,
                purrsMimimo = bytes[offset + 74].toInt() and 0xFF,
                purrsMomo = bytes[offset + 75].toInt() and 0xFF,
                duets = bytes[offset + 76].toInt() and 0xFF,
            )
            val slots = Array(PetPawBook.BOOK_DAYS) { i ->
                val at = offset + MARKS_AT + i * MARK_BYTES
                val day = getU32(bytes, at)
                val size = bytes[at + 4].toInt() and 0xFF
                val whoWhere = bytes[at + 5].toInt() and 0xFF
                val part = bytes[at + 6].toInt() and 0xFF
                val place = whoWhere shr 4
                if (ver == VERSION_1 && place != 0) return PetCodecResult.Corrupt
                PetBookMark(day, size, whoWhere and 0x0F, part, place)
            }
            val book = PetPawBook.fromSlots(slots) ?: return PetCodecResult.Corrupt
            return PetCodecResult.Ok(PetSyncState(epoch, PetSnapshot(graphemes, mimimo, momo), daily, book))
        }

        fun fnv1a32(b: ByteArray, offset: Int, length: Int): UInt {
            var h = 0x811C9DC5u
            for (i in offset until offset + length) {
                h = h xor (b[i].toUInt() and 0xFFu)
                h *= 0x01000193u
            }
            return h
        }

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
