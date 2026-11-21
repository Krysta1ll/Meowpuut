package com.krysta1ll.meowpuut.android.app.stats

/**
 * `filesDir/PetBook.bin` as the app reads it: the pets core's persisted state (crates/meowpuut-pets, codec.rs, version 1), the one
 * file the keyboard keeps for the cats. Fixed length, little-endian, checksummed, only counters, bands and local day numbers, never
 * text. The app reads it with this plain parser rather than through the native library, so the statistics need no JNI and the JVM
 * tests can build and read real files. The layout:
 *
 *   0  magic 9C A7 · 2 version (1) · 3 reserved · 4 epoch u32 · 8 committed graphemes u64
 *   16 Mimimo pets, falls, plays (u64 each) · 40 Momo the same
 *   64 the day's own limits (not read here) · 80 400 marks of 7 bytes: day u32, size u8, who u8, part u8
 *   2880 FNV-1a 32 of bytes 0..2880
 *
 * A file this build cannot vouch for (length, magic, version, checksum, a field out of range) reads as nothing, exactly as the keyboard
 * starts afresh on such a file. The statistics only read it: the one thing the app writes to it is [cleared], when the person clears
 * the cats' records.
 */
internal object PetBookFile {
    const val STATE_BYTES = 2884
    const val BOOK_DAYS = 400
    private const val MARKS_AT = 80
    private const val MARK_BYTES = 7
    private const val VERSION = 1

    /** One cat's counters: pets (strokes), falls (drops off the bar) and rewarded shared moments. */
    class Contacts(val pets: Long, val falls: Long, val plays: Long)

    /** One local day of the paw book: [day] since 1970-01-01, [size] 0…4, [who] bits 1 Mimimo and 2 Momo, [part] bits of the five day parts. */
    class Mark(val day: Int, val size: Int, val who: Int, val part: Int)

    class Book(val epoch: Long, val graphemes: Long, val mimimo: Contacts, val momo: Contacts, val marks: List<Mark>)

    /** The file's records, or null when the bytes are not a state this build understands. */
    fun parse(bytes: ByteArray?): Book? {
        if (bytes == null || bytes.size != STATE_BYTES) return null
        if (bytes[0] != 0x9C.toByte() || bytes[1] != 0xA7.toByte() || bytes[2].toInt() != VERSION || bytes[3].toInt() != 0) return null
        if (u32(bytes, STATE_BYTES - 4) != fnv1a(bytes, STATE_BYTES - 4)) return null
        // The day's own limits: flags is 0 or 1 and the three bytes after the counts are reserved.
        if (bytes[72].toInt() and 0xFF > 1 || bytes[77].toInt() != 0 || bytes[78].toInt() != 0 || bytes[79].toInt() != 0) return null
        val marks = ArrayList<Mark>(BOOK_DAYS)
        for (slot in 0 until BOOK_DAYS) {
            val at = MARKS_AT + slot * MARK_BYTES
            val day = u32(bytes, at)
            val size = bytes[at + 4].toInt() and 0xFF
            val who = bytes[at + 5].toInt() and 0xFF
            val part = bytes[at + 6].toInt() and 0xFF
            if (day == 0L) {
                if (size != 0 || who != 0 || part != 0) return null
                continue
            }
            if (size > 4 || who > 3 || part > 0b1_1111 || day > Int.MAX_VALUE || day % BOOK_DAYS != slot.toLong()) return null
            marks += Mark(day.toInt(), size, who, part)
        }
        return Book(
            epoch = u32(bytes, 4), graphemes = u64(bytes, 8),
            mimimo = Contacts(u64(bytes, 16), u64(bytes, 24), u64(bytes, 32)),
            momo = Contacts(u64(bytes, 40), u64(bytes, 48), u64(bytes, 56)),
            marks = marks,
        )
    }

    /** The epoch of a file, without judging the rest of it (a damaged file still tells where the records were), or null. */
    fun epochOf(bytes: ByteArray?): Long? =
        if (bytes != null && bytes.size == STATE_BYTES && bytes[0] == 0x9C.toByte() && bytes[1] == 0xA7.toByte() && bytes[2].toInt() == VERSION) u32(bytes, 4) else null

    /**
     * An empty state at [epoch]: what "clear the cats' records" leaves in the file. The epoch is raised, so a copy the running keyboard
     * still holds (older epoch) loses to it when the two are merged, and nothing it wrote before can come back.
     */
    fun cleared(epoch: Long): ByteArray {
        val out = ByteArray(STATE_BYTES)
        out[0] = 0x9C.toByte(); out[1] = 0xA7.toByte(); out[2] = VERSION.toByte()
        putU32(out, 4, epoch)
        putU32(out, STATE_BYTES - 4, fnv1a(out, STATE_BYTES - 4))
        return out
    }

    /** The core's level rule (lib.rs `Snapshot::progress`): one point per 200 graphemes and per three contacts, at most 100. */
    fun level(graphemes: Long, contacts: Contacts): Int {
        val touches = contacts.pets + contacts.falls
        val total = graphemes / 200 + (if (touches < 0) Long.MAX_VALUE else touches) / 3
        return if (total < 0 || total > 100) 100 else total.toInt()
    }

    private fun u32(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)

    /** A u64 as a non-negative Long: a counter beyond what a Long holds saturates (the core's own counters saturate too). */
    private fun u64(b: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return if (v < 0) Long.MAX_VALUE else v
    }

    private fun putU32(b: ByteArray, at: Int, v: Long) {
        for (i in 0 until 4) b[at + i] = (v shr (8 * i)).toByte()
    }

    private fun fnv1a(b: ByteArray, length: Int): Long {
        var hash = 0x811C9DC5L
        for (i in 0 until length) {
            hash = hash xor (b[i].toLong() and 0xFF)
            hash = (hash * 0x01000193L) and 0xFFFFFFFFL
        }
        return hash
    }
}
