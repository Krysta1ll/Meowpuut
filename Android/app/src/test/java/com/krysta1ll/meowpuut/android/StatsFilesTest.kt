package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.app.stats.FestivalSource
import com.krysta1ll.meowpuut.android.app.stats.PetBookFile
import com.krysta1ll.meowpuut.android.app.stats.StatsFiles
import com.krysta1ll.meowpuut.android.app.stats.StatsLedger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The statistics' files on a real folder: what is read, what is written and when, and what clearing the cats' records leaves. */
class StatsFilesTest {
    private lateinit var dir: File
    private var forgotten = 0
    private lateinit var files: StatsFiles

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("stats").toFile()
        files = StatsFiles(dir, FestivalSource { emptyList() }, { forgotten++ })
    }

    @After
    fun tearDown() { dir.deleteRecursively() }

    private val book get() = File(dir, "PetBook.bin")
    private val ledger get() = File(dir, "PetStatsLedger.json")

    /** A state file of the pets core's layout: epoch, characters, one cat's pets, and marks (day, size, who, part). */
    private fun state(epoch: Long, graphemes: Long, pets: Long, marks: List<IntArray>): ByteArray {
        val b = ByteArray(PetBookFile.STATE_BYTES)
        b[0] = 0x9C.toByte(); b[1] = 0xA7.toByte(); b[2] = 1
        fun put(at: Int, v: Long, n: Int) { for (i in 0 until n) b[at + i] = (v shr (8 * i)).toByte() }
        put(4, epoch, 4); put(8, graphemes, 8); put(16, pets, 8)
        for (m in marks) { val at = 80 + (m[0] % 400) * 7; put(at, m[0].toLong(), 4); b[at + 4] = m[1].toByte(); b[at + 5] = m[2].toByte(); b[at + 6] = m[3].toByte() }
        var hash = 0x811C9DC5L
        for (i in 0 until PetBookFile.STATE_BYTES - 4) { hash = hash xor (b[i].toLong() and 0xFF); hash = (hash * 0x01000193L) and 0xFFFFFFFFL }
        put(PetBookFile.STATE_BYTES - 4, hash, 4)
        return b
    }

    private fun days(from: Int, to: Int, size: Int = 2) = (from until to).map { intArrayOf(it, size, 1, 1) }

    @Test
    fun withNothingThereNothingIsMadeAndThePageHasNothingToShow() {
        val stats = files.refresh()
        assertTrue(stats.isEmpty)
        assertFalse(stats.cleared)
        assertFalse(ledger.exists())                      // no ledger for no records
        assertFalse(book.exists())
    }

    @Test
    fun theLedgerIsWrittenWhenARunAddedSomethingAndOtherwiseLeftAlone() {
        book.writeBytes(state(0, 5_000, 12, days(20_000, 20_010)))
        val first = files.refresh()
        assertEquals(10, first.daysMet)
        assertTrue(ledger.exists())
        val written = ledger.readText()
        ledger.setLastModified(1_000_000L)
        val again = files.refresh()
        assertEquals(10, again.daysMet)
        assertEquals(written, ledger.readText())
        assertEquals("a read that adds nothing does not write", 1_000_000L, ledger.lastModified())
        assertFalse(File(dir, "PetStatsLedger.json.tmp").exists())
    }

    @Test
    fun daysThePawBookForgetsAreKeptAndNothingGoesDownWhenTheFileIsOlder() {
        book.writeBytes(state(0, 5_000, 12, days(20_000, 20_400)))
        assertEquals(400, files.refresh().daysMet)
        book.writeBytes(state(0, 9_000, 30, days(20_150, 20_550, 3)))        // the ring has moved on by 150 days
        val moved = files.refresh()
        assertEquals(550, moved.daysMet)
        assertEquals(9_000L, moved.graphemes)
        book.writeBytes(state(0, 100, 1, days(20_500, 20_501, 0)))            // an old copy of the file turns up
        val old = files.refresh()
        assertEquals(550, old.daysMet)
        assertEquals(9_000L, old.graphemes)
        assertEquals(30L, old.mimimo.pets)
    }

    @Test
    fun theJourneyIsWorkedOutFromTheCharactersTheCatsCounted() {
        assertEquals(0L, files.committedCharacters())
        assertEquals(0L, files.refresh().distance)
        book.writeBytes(state(0, 248_316, 12, days(20_000, 20_010)))
        assertEquals(248_316L, files.committedCharacters())
        assertEquals(248_316L * 8, files.refresh().distance)
        assertFalse("no distance is kept", ledger.readText().contains("distance"))
    }

    @Test
    fun aNewerLedgerIsNeverOverwrittenAndADamagedOneIsRebuilt() {
        book.writeBytes(state(0, 5_000, 12, days(20_000, 20_010)))
        ledger.writeText("{\"version\":2,\"something\":\"newer\"}")
        val newer = files.refresh()
        assertEquals(10, newer.daysMet)                                         // answered from the file, in memory
        assertEquals("{\"version\":2,\"something\":\"newer\"}", ledger.readText())
        ledger.writeText("not a ledger at all")
        assertEquals(10, files.refresh().daysMet)
        assertNotNull(StatsLedger.parse(ledger.readText()).ledger)
        // A book that is not one (too short, or too big) is no book.
        book.writeBytes(ByteArray(10))
        assertEquals(10, files.refresh().daysMet)
        book.writeBytes(ByteArray(5 * 1024 * 1024))
        assertEquals(10, files.refresh().daysMet)
    }

    @Test
    fun clearingTakesTheRecordsTheLedgerAndTheJourneyAndOnlyANewerEpochComesBack() {
        book.writeBytes(state(0, 248_316, 141, days(20_000, 20_300)))
        assertEquals(300, files.refresh().daysMet)

        files.forget()
        assertEquals(1, forgotten)                                              // the first version's counters go with it
        val tombstone = PetBookFile.parse(book.readBytes())!!
        assertEquals(1L, tombstone.epoch)
        assertEquals(0L, tombstone.graphemes)
        assertTrue(tombstone.marks.isEmpty())
        assertTrue(StatsLedger.parse(ledger.readText()).ledger!!.cleared)

        val cleared = files.refresh()
        assertTrue(cleared.isEmpty && cleared.cleared)
        assertEquals(1, cleared.stop)                                            // the cats are back at the first place
        assertEquals(0L, files.committedCharacters())

        // The keyboard that was running writes its last, old counts back before it has seen the clearing: from before it, so not read.
        book.writeBytes(state(0, 248_316, 141, days(20_000, 20_300)))
        val stale = files.refresh()
        assertTrue(stale.isEmpty && stale.cleared)

        // The keyboard has cleared itself (epoch 1 or more) and the person types again: the records start over, small.
        book.writeBytes(state(1, 40, 2, days(20_400, 20_401, 0)))
        val fresh = files.refresh()
        assertFalse(fresh.cleared)
        assertEquals(1, fresh.daysMet)
        assertEquals(40L, fresh.graphemes)
    }

    @Test
    fun clearingWithNothingYetStillRaisesTheEpoch() {
        files.forget()
        assertEquals(1L, PetBookFile.epochOf(book.readBytes()))
        files.forget()                                                          // and again: each clearing goes one higher, so no copy returns
        assertEquals(2L, PetBookFile.epochOf(book.readBytes()))
        assertEquals(2, forgotten)
    }
}
