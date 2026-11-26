package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.app.stats.PetAchievementPlace
import com.krysta1ll.meowpuut.android.app.stats.PetAchievementProgress
import com.krysta1ll.meowpuut.android.app.stats.PetContacts
import com.krysta1ll.meowpuut.android.app.stats.PetMobileEvent
import com.krysta1ll.meowpuut.android.app.stats.PetPassCode
import com.krysta1ll.meowpuut.android.app.stats.PetPawBook
import com.krysta1ll.meowpuut.android.app.stats.PetSnapshot
import com.krysta1ll.meowpuut.android.app.stats.PetSyncDecodeResult
import com.krysta1ll.meowpuut.android.app.stats.PetSyncEnvelope
import com.krysta1ll.meowpuut.android.app.stats.PetSyncState
import com.krysta1ll.meowpuut.android.app.stats.PetSyncStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class PetSyncTest {
    private fun seededEnvelope(startSeed: ULong, epoch: UInt): PetSyncEnvelope {
        var seed = startSeed
        fun next(): ULong {
            seed = seed * 6364136223846793005UL + 1442695040888963407UL
            return seed
        }
        val flags = if ((next() and 1UL) == 0UL) 0 else PetSyncEnvelope.FLAG_PASS_ACTIVE
        val book = PetPawBook()
        val day = (20_000UL + (next() % 10UL)).toUInt()
        book.record(day, (next() % 5UL).toInt(), (1UL + (next() % 3UL)).toInt(), (1UL + (next() % 31UL)).toInt())
        if ((next() and 1UL) == 0UL) book.reach(day, (1UL + (next() % 12UL)).toInt())
        val state = PetSyncState(
            epoch = epoch,
            snapshot = PetSnapshot(
                committedGraphemes = next() % 2_000_000UL,
                mimimo = PetContacts(pets = next() % 500UL, plays = next() % 200UL),
                momo = PetContacts(pets = next() % 500UL, falls = next() % 100UL),
            ),
            book = book,
        )
        val progress = PetAchievementProgress(
            places = (next().toInt()) and PetAchievementPlace.ALL_MASK,
            festivals = (next() % 50UL).toUInt(),
            lifetimeDays = (next() % 500UL).toUInt(),
            maxDayGraphemes = (next() % 20_000UL).toUInt(),
            peakDays1500 = (next() % 40UL).toUInt(),
            snowPeaks4000 = (next() % 20UL).toUInt(),
            outings = (next() % 150UL).toUInt(),
            purrs = (next() % 150UL).toUInt(),
            rides = (next() % 80UL).toUInt(),
            caught = (next() % 40UL).toUInt(),
            found = (next() % 40UL).toUInt(),
            roof = (next() % 150UL).toUInt(),
            steps = next() % 200_000UL,
            nose = (next() % 80UL).toUInt(),
            scrollShares = (next() % 20UL).toUInt(),
        )
        return PetSyncEnvelope(flags = flags, state = state, progress = progress).refreshed()
    }

    @Test
    fun roundtripAndCrdtLawsHoldAcross100Seeds() {
        for (seed in 1UL..100UL) {
            val a = seededEnvelope(seed * 3UL, 1u)
            val b = seededEnvelope(seed * 3UL + 1UL, 1u)
            val c = seededEnvelope(seed * 3UL + 2UL, 1u)
            val bytes = a.toBytes()
            assertEquals(PetSyncEnvelope.SYNC_BYTES, bytes.size)
            assertEquals(PetSyncDecodeResult.Ok(a), PetSyncEnvelope.fromBytes(bytes))
            assertEquals("commutativity seed=$seed", a.merge(b), b.merge(a))
            assertEquals("associativity seed=$seed", a.merge(b).merge(c), a.merge(b.merge(c)))
            assertEquals("idempotence seed=$seed", a.merge(a), a)
        }
    }

    @Test
    fun corruptEnvelopeAndNewerEpochHandling() {
        val good = seededEnvelope(42UL, 1u).toBytes()
        val badSum = good.copyOf().also { it[100] = (it[100].toInt() xor 0xFF).toByte() }
        assertEquals(PetSyncDecodeResult.BadChecksum, PetSyncEnvelope.fromBytes(badSum))

        val badVer = good.copyOf().also { it[4] = 99; it[5] = 0 }
        assertEquals(PetSyncDecodeResult.BadVersion(99), PetSyncEnvelope.fromBytes(badVer))

        val badMagic = good.copyOf().also { it[0] = 0 }
        assertEquals(PetSyncDecodeResult.BadMagic, PetSyncEnvelope.fromBytes(badMagic))
        assertEquals(PetSyncDecodeResult.BadLength, PetSyncEnvelope.fromBytes(ByteArray(10)))

        val old = seededEnvelope(7UL, 1u)
        val cleared = old.clearedForReset()
        assertEquals(2u, cleared.state.epoch)
        val merged = old.merge(cleared)
        assertEquals(2u, merged.state.epoch)
        assertEquals(PetAchievementProgress(), merged.progress)
        assertArrayEquals(ByteArray(16), merged.unlockedBits)
        assertEquals(merged, cleared.merge(old))
    }

    @Test
    fun passCodeRoundtripTypoToleranceAndTamperRejection() {
        val key = byteArrayOf(
            0xA5.toByte(), 0x19, 0x7E, 0x04, 0xBC.toByte(), 0x33, 0x91.toByte(), 0xF0.toByte(),
            0x22, 0x48, 0x6D, 0x8F.toByte(), 0x11, 0x55, 0xAA.toByte(), 0xEE.toByte(),
        )
        val code = PetPassCode.encode(key)
        assertEquals("MEOW-MMCQ-W15W-6E8Z-08J8-DP7H-2NDA-XRTG", code)
        assertEquals("354ccb16dad8b20c", PetPassCode.groupPrefixOf(key).joinToString("") { "%02x".format(it) })
        assertArrayEquals(key, PetPassCode.decode(code))
        assertArrayEquals(key, PetPassCode.decode(code.lowercase()))
        val typos = code.replace('0', 'O').replace('1', 'l')
        assertArrayEquals(key, PetPassCode.decode(typos))
        val tampered = code.toCharArray().also { it[6] = if (it[6] == '0') '1' else '0' }.concatToString()
        assertNull(PetPassCode.decode(tampered))

        // Cross-platform 3,072-byte golden vector (matches Rust sync.rs & iOS Swift byte-for-byte).
        val book = PetPawBook().apply {
            record(20_500u, 4, 3, 17)
            reach(20_500u, 6)
        }
        val golden = PetSyncEnvelope(
            flags = PetSyncEnvelope.FLAG_PASS_ACTIVE,
            groupPrefix = PetPassCode.groupPrefixOf(key),
            state = PetSyncState(
                epoch = 3u,
                snapshot = PetSnapshot(committedGraphemes = 123_456UL, mimimo = PetContacts(pets = 100UL), momo = PetContacts(pets = 100UL)),
                book = book,
            ),
            progress = PetAchievementProgress(places = PetAchievementPlace.ALL_MASK),
        ).refreshed()
        val goldenBytes = golden.toBytes()
        assertEquals("a21b5298", goldenBytes.copyOfRange(2896, 2900).joinToString("") { "%02x".format(it) })
        assertEquals("3f0806ff1f3e1c000000030000000000", golden.unlockedBits.joinToString("") { "%02x".format(it) })
        assertEquals("49513e4ae8292e81", goldenBytes.copyOfRange(3064, 3072).joinToString("") { "%02x".format(it) })
    }

    @Test
    fun syncStorePersistsInteractionsAndMergesImportedEnvelope() {
        val dir = Files.createTempDirectory("meowpuut-sync-test").toFile()
        try {
            val store = PetSyncStore(dir)
            val pass = store.loadOrCreatePassCode()
            assertTrue(PetPassCode.decode(pass) != null)
            repeat(10) { store.record(PetMobileEvent.Pet(isMimimo = true)) }
            val afterPets = store.loadEnvelope()
            assertTrue("PET_MIMIMO_10" in afterPets.unlockedApis())

            val remote = PetSyncEnvelope(
                state = PetSyncState(snapshot = PetSnapshot(committedGraphemes = 1_500UL)),
                progress = PetAchievementProgress(places = PetAchievementPlace.ALL_MASK),
            ).refreshed()
            val imported = store.importBytes(remote.toBytes()) as PetSyncDecodeResult.Ok
            assertTrue("PET_MIMIMO_10" in imported.envelope.unlockedApis())
            assertTrue("CHARS_1K" in imported.envelope.unlockedApis())
            assertTrue("PLACES_12" in imported.envelope.unlockedApis())
        } finally {
            dir.deleteRecursively()
        }
    }
}
