package com.krysta1ll.meowpuut.android.app.stats

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.TreeMap

/**
 * 行旅's own archive of the cats' records, kept by the app (filesDir/PetStatsLedger.json). The paw book in PetBook.bin remembers 400
 * days; the ledger remembers every day the app has seen, so a count of days never goes down when the ring moves on. The same idea as
 * the iOS PetStatsLedger (docs: the statistics framework), and the same rules:
 *
 *  - everything in it only grows while the records are kept. Absorbing one read of the file is a join (the larger of each counter,
 *    a day's size band and cat and day-part bits combined as the paw book combines them), so reading the same file twice, or an
 *    older copy of it, changes nothing;
 *  - the records' epoch decides what is from before a clearing. A read with a higher epoch starts the ledger over; one with a lower
 *    epoch is a copy from before the clearing and is not read;
 *  - nothing here is text: counters, bands, bits and local day numbers.
 *
 * Android adds one number the paw book does not hold, [distance]: how far the 字景 journey has got (committed characters, whichever
 * skin is worn). It is read from the keyboard's settings and only ever raised here too.
 *
 * Pure Kotlin: no Android class, so the JVM tests cover all of it.
 */
internal class StatsLedger private constructor(
    /** The epoch of the records this ledger holds. */
    var epoch: Long = 0L,
) {
    /** One cat's totals. [level] is the keyboard's own rule (0…100). */
    class Cat(val level: Int = 0, val pets: Long = 0, val falls: Long = 0, val plays: Long = 0) {
        fun joined(o: Cat) = Cat(maxOf(level, o.level), maxOf(pets, o.pets), maxOf(falls, o.falls), maxOf(plays, o.plays))
        override fun equals(other: Any?) = other is Cat && level == other.level && pets == other.pets && falls == other.falls && plays == other.plays
        override fun hashCode() = ((level * 31 + pets.hashCode()) * 31 + falls.hashCode()) * 31 + plays.hashCode()
    }

    /** One day as the paw book keeps it: a size band 0…4, who bits (1 Mimimo, 2 Momo), day-part bits. */
    data class Day(val size: Int, val who: Int, val part: Int) {
        fun joined(o: Day) = Day(maxOf(size, o.size), who or o.who, part or o.part)
    }

    var graphemes = 0L
        private set
    var distance = 0L
        private set
    var mimimo = Cat()
        private set
    var momo = Cat()
        private set
    /** True from the moment the records were cleared until something is recorded again: the page says so, calmly. */
    var cleared = false
        private set
    private val days = TreeMap<Int, Day>()

    /** Day number (days since 1970-01-01, local) to that day, oldest first. */
    val dayMap: java.util.NavigableMap<Int, Day> get() = days

    val isEmpty: Boolean get() = graphemes == 0L && distance == 0L && days.isEmpty() && mimimo == Cat() && momo == Cat()

    /** Brings one read of the paw book in. True when the ledger changed. */
    fun absorb(book: PetBookFile.Book): Boolean {
        val before = fingerprint()
        if (book.epoch > epoch) startOver(book.epoch)
        else if (book.epoch < epoch) return false
        graphemes = maxOf(graphemes, book.graphemes)
        mimimo = mimimo.joined(cat(book, book.mimimo))
        momo = momo.joined(cat(book, book.momo))
        for (m in book.marks) {
            val day = Day(m.size.coerceIn(0, 4), m.who and 3, m.part and 0b1_1111)
            days[m.day] = days[m.day]?.joined(day) ?: day
        }
        val changed = fingerprint() != before
        if (changed && !isEmpty) cleared = false
        return changed
    }

    private fun cat(book: PetBookFile.Book, c: PetBookFile.Contacts) =
        Cat(PetBookFile.level(book.graphemes, c), c.pets, c.falls, c.plays)

    /** The journey's distance as the keyboard keeps it. True when it grew. */
    fun absorbDistance(value: Long): Boolean {
        if (value <= distance) return false
        distance = value
        cleared = false
        return true
    }

    /** The records were cleared: everything goes, and only a read at [toEpoch] or later is believed again. */
    fun clear(toEpoch: Long) {
        startOver(maxOf(epoch, toEpoch))
        cleared = true
    }

    private fun startOver(newEpoch: Long) {
        epoch = newEpoch
        graphemes = 0L; distance = 0L
        mimimo = Cat(); momo = Cat()
        days.clear()
    }

    private fun fingerprint(): Long {
        var h = epoch * 31 + graphemes
        h = h * 31 + distance
        h = h * 31 + mimimo.hashCode()
        h = h * 31 + momo.hashCode()
        h = h * 31 + days.size
        for ((d, v) in days) h = h * 31 + d * 7 + v.size * 5 + v.who * 3 + v.part
        return h
    }

    // ---- the stored form --------------------------------------------------------------------------------------

    /**
     * JSON, version 1. A day is one number, day << 24 | size << 16 | who << 8 | part (as on iOS), so ten years of days stay under
     * 50 KB. Counters, bands and day numbers only; never text.
     */
    fun toJson(): String {
        val root = JSONObject()
        root.put("version", VERSION)
        root.put("epoch", epoch)
        root.put("graphemes", graphemes)
        root.put("distance", distance)
        root.put("cleared", cleared)
        for ((key, c) in listOf("mimimo" to mimimo, "momo" to momo)) {
            root.put(key, JSONObject().put("level", c.level).put("pets", c.pets).put("falls", c.falls).put("plays", c.plays))
        }
        val packed = JSONArray()
        for ((d, v) in days) packed.put((d.toLong() shl 24) or (v.size.toLong() shl 16) or (v.who.toLong() shl 8) or v.part.toLong())
        root.put("days", packed)
        return root.toString()
    }

    /** What reading the stored file gave. [ledger] is null when there is none usable; [writable] is false for a newer version (left alone). */
    class Stored(val ledger: StatsLedger?, val writable: Boolean = true, val damaged: Boolean = false)

    companion object {
        const val VERSION = 1

        fun empty() = StatsLedger()

        fun parse(text: String?): Stored {
            if (text.isNullOrBlank()) return Stored(null)
            val root = try { JSONObject(text) } catch (_: JSONException) { return Stored(null, damaged = true) }
            val version = root.optInt("version", -1)
            if (version > VERSION) return Stored(null, writable = false)
            if (version != VERSION) return Stored(null, damaged = true)
            return try { Stored(read(root)) } catch (_: Exception) { Stored(null, damaged = true) }
        }

        private fun read(root: JSONObject): StatsLedger {
            val ledger = StatsLedger(root.getLong("epoch"))
            ledger.graphemes = root.getLong("graphemes").also { require(it >= 0) }
            ledger.distance = root.optLong("distance", 0L).also { require(it >= 0) }
            ledger.cleared = root.optBoolean("cleared", false)
            ledger.mimimo = cat(root.getJSONObject("mimimo"))
            ledger.momo = cat(root.getJSONObject("momo"))
            val packed = root.getJSONArray("days")
            for (i in 0 until packed.length()) {
                val v = packed.getLong(i)
                val day = (v shr 24).toInt()
                val size = ((v shr 16) and 0xFF).toInt()
                val who = ((v shr 8) and 0xFF).toInt()
                val part = (v and 0xFF).toInt()
                require(day > 0 && size <= 4 && who <= 3 && part <= 0b1_1111)
                ledger.days[day] = Day(size, who, part)
            }
            return ledger
        }

        private fun cat(o: JSONObject): Cat {
            val level = o.getInt("level")
            require(level in 0..100)
            val c = Cat(level, o.getLong("pets"), o.getLong("falls"), o.getLong("plays"))
            require(c.pets >= 0 && c.falls >= 0 && c.plays >= 0)
            return c
        }
    }
}
