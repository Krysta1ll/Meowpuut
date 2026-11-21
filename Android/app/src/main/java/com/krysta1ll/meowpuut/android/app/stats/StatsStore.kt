package com.krysta1ll.meowpuut.android.app.stats

import android.content.Context
import com.krysta1ll.meowpuut.android.ime.LocalCatAffinity
import com.krysta1ll.meowpuut.android.ime.Prefs
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors

/**
 * The app's side of the statistics: reads the one record the keyboard already keeps (filesDir/PetBook.bin) and the journey's distance
 * (the keyboard's settings), folds them into the [StatsLedger] (filesDir/PetStatsLedger.json, the app's own file) and answers
 * [JourneyStats]. The keyboard writes nothing for it and none of this is on the key path: it runs when the home or 行旅 page opens.
 *
 * The ledger is written only when a read added something, atomically. A ledger of a newer version is left as it is (an older build
 * after a newer one) and the numbers are answered from memory; a damaged one is rebuilt from the file.
 */
internal class StatsStore(private val context: Context, private val festivals: FestivalSource = FestivalSource { emptyList() }) {
    private val dir: File = context.filesDir
    private val book = File(dir, LocalCatAffinity.FILE)
    private val ledgerFile = File(dir, LEDGER)

    /** Reads, absorbs and answers. Blocks on the files: call it off the main thread (see [refreshAsync]). */
    fun refresh(): JourneyStats = synchronized(LOCK) {
        val stored = StatsLedger.parse(readBytes(ledgerFile)?.decodeToString())
        val ledger = stored.ledger ?: StatsLedger.empty()
        val before = ledger.toJson()
        PetBookFile.parse(readBytes(book))?.let(ledger::absorb)
        ledger.absorbDistance(Prefs(context).glyphDistance)
        val after = ledger.toJson()
        if (stored.writable && (stored.damaged || after != before)) write(ledgerFile, after.toByteArray())
        JourneyStats.of(ledger, festivals.occurrences())
    }

    private val io = lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "meowpuut-stats").apply { isDaemon = true } } }

    /** [refresh] on a thread of its own; [done] runs on the main thread. */
    fun refreshAsync(done: (JourneyStats) -> Unit) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        io.value.execute {
            val stats = try { refresh() } catch (_: RuntimeException) { null }
            if (stats != null) handler.post { done(stats) }
        }
    }

    fun shutdown() { if (io.isInitialized()) io.value.shutdown() }

    /**
     * "Clear the cats' records": the records and the statistics go together. The file is replaced by an empty state of a higher epoch
     * (so a running keyboard's older copy cannot bring anything back), the ledger starts over at that epoch, and the journey goes back
     * to its first place. The caller still asks the running keyboard to clear its own copy (Prefs.CLEAR_PETS).
     */
    fun forget() = synchronized(LOCK) {
        val seen = maxOf(StatsLedger.parse(readBytes(ledgerFile)?.decodeToString()).ledger?.epoch ?: 0L, PetBookFile.epochOf(readBytes(book)) ?: 0L)
        val next = seen + 1
        withFileLock { write(book, PetBookFile.cleared(next)) }
        val ledger = StatsLedger.empty()
        ledger.clear(next)
        write(ledgerFile, ledger.toJson().toByteArray())
        LocalCatAffinity.forgetLegacyCounters(context)
        Prefs(context).glyphDistance = 0L
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
