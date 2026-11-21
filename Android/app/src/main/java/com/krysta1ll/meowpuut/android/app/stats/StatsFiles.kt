package com.krysta1ll.meowpuut.android.app.stats

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The statistics' two files and what is done to them, without anything of Android in it (the JVM tests run it on a folder):
 * PetBook.bin, which the keyboard keeps and this only reads (and replaces with an empty state when the records are cleared), and
 * PetStatsLedger.json, which is this app's own.
 *
 * The ledger is written only when a read added something, atomically. A ledger of a newer version is left as it is (an older build
 * after a newer one) and the numbers are answered from memory; a damaged one is rebuilt from the file.
 */
internal class StatsFiles(
    private val dir: File,
    private val festivals: FestivalSource,
    /** The journey's distance as the keyboard keeps it, now. */
    private val distance: () -> Long,
    /** What else goes when the records are cleared: the journey's distance, the first version's counters. */
    private val alsoForget: () -> Unit = {},
    private val bookName: String = "PetBook.bin",
) {
    private val book = File(dir, bookName)
    private val ledgerFile = File(dir, LEDGER)

    /** Reads, absorbs and answers. Blocks on the files. */
    fun refresh(): JourneyStats = synchronized(LOCK) {
        val stored = StatsLedger.parse(readBytes(ledgerFile)?.decodeToString())
        val ledger = stored.ledger ?: StatsLedger.empty()
        val before = ledger.toJson()
        PetBookFile.parse(readBytes(book))?.let(ledger::absorb)
        ledger.absorbDistance(distance())
        val after = ledger.toJson()
        if (stored.writable && (stored.damaged || after != before)) write(ledgerFile, after.toByteArray())
        JourneyStats.of(ledger, festivals.occurrences())
    }

    /**
     * "Clear the cats' records": the records and the statistics go together. The file is replaced by an empty state of a higher epoch
     * (so a running keyboard's older copy cannot bring anything back), the ledger starts over at that epoch, and the journey goes back
     * to its first place. The caller still asks the running keyboard to clear its own copy.
     */
    fun forget() = synchronized(LOCK) {
        val seen = maxOf(StatsLedger.parse(readBytes(ledgerFile)?.decodeToString()).ledger?.epoch ?: 0L, PetBookFile.epochOf(readBytes(book)) ?: 0L)
        val next = seen + 1
        withFileLock { write(book, PetBookFile.cleared(next)) }
        val ledger = StatsLedger.empty()
        ledger.clear(next)
        write(ledgerFile, ledger.toJson().toByteArray())
        alsoForget()
    }

    private fun withFileLock(body: () -> Unit) {
        try {
            RandomAccessFile(File(dir, LOCK_FILE), "rw").use { lock -> lock.channel.lock().use { body() } }
        } catch (_: OverlappingFileLockException) {
            body()   // the keyboard in this very process holds it: what it writes next is merged under the higher epoch anyway
        } catch (_: IOException) {
            body()
        }
    }

    private fun readBytes(file: File): ByteArray? = try {
        if (file.isFile && file.length() in 1..MAX_FILE) file.readBytes() else null
    } catch (_: IOException) {
        null
    }

    private fun write(file: File, bytes: ByteArray) {
        try {
            val temporary = File(dir, file.name + ".tmp")
            temporary.writeBytes(bytes)
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: IOException) {
        }
    }

    companion object {
        const val LEDGER = "PetStatsLedger.json"
        private const val LOCK_FILE = "PetBook.lock"
        private const val MAX_FILE = 4L * 1024 * 1024
        private val LOCK = Any()
    }
}
