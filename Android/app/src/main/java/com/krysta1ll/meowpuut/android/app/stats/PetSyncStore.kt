package com.krysta1ll.meowpuut.android.app.stats

import com.krysta1ll.meowpuut.android.ime.glyph.GlyphJourney
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphScenes
import java.io.File

/** Mobile interaction events recorded into the local Route A `PetSyncEnvelope`. */
sealed interface PetMobileEvent {
    data class Pet(val isMimimo: Boolean) : PetMobileEvent
    data class Play(val isMimimo: Boolean) : PetMobileEvent
    data class Fall(val isMimimo: Boolean) : PetMobileEvent
    data object Purr : PetMobileEvent
    data object Found : PetMobileEvent
    data object Outfit : PetMobileEvent
    data object ScrollShare : PetMobileEvent
    data object Nose : PetMobileEvent
    data class Steps(val count: ULong) : PetMobileEvent
}

/**
 * Pure-file persistence for Android Route A sync (`PetSync.mpsync` + `PetPassCode.txt`),
 * reconciling with `PetBook.bin` and `PetStatsLedger.json`, supporting `.mpsync` export/import
 * CRDT merge and epoch reset. Zero Android imports so JVM unit tests exercise the full flow.
 */
internal class PetSyncStore(
    private val dir: File,
    private val bookFileName: String = "PetBook.bin",
    private val ledgerFileName: String = "PetStatsLedger.json",
) {
    val envelopeFile: File get() = File(dir, ENVELOPE_FILE)
    val passCodeFile: File get() = File(dir, PASS_CODE_FILE)

    fun loadOrCreatePassCode(): String {
        val existing = readText(passCodeFile)?.let { PetPassCode.decode(it) }
        if (existing != null) return PetPassCode.encode(existing)
        val fresh = PetPassCode.encode(PetPassCode.generateKey())
        savePassCode(fresh)
        return fresh
    }

    fun savePassCode(code: String): Boolean {
        val key = PetPassCode.decode(code) ?: return false
        return writeBytes(passCodeFile, PetPassCode.encode(key).toByteArray(Charsets.UTF_8))
    }

    fun loadEnvelope(): PetSyncEnvelope {
        val bytes = readBytes(envelopeFile) ?: return PetSyncEnvelope()
        return (PetSyncEnvelope.fromBytes(bytes) as? PetSyncDecodeResult.Ok)?.envelope ?: PetSyncEnvelope()
    }

    fun saveEnvelope(envelope: PetSyncEnvelope): Boolean = writeBytes(envelopeFile, envelope.toBytes())

    /** Reconciles persisted `.mpsync` with `PetBook.bin` and `PetStatsLedger.json` (plus festivals count). */
    fun snapshot(festivalsMet: Int = 0): PetSyncEnvelope {
        val code = loadOrCreatePassCode()
        val prefix = PetPassCode.decode(code)?.let { PetPassCode.groupPrefixOf(it) } ?: ByteArray(8)
        val stored = loadEnvelope()
        val localState = readBytes(File(dir, bookFileName))
            ?.let { (PetSyncState.fromBytes(it) as? PetCodecResult.Ok)?.state }
            ?: PetSyncState()
        val ledger = StatsLedger.parse(readText(File(dir, ledgerFileName))).ledger
        val localEnvelope = buildLocalEnvelope(stored.flags, prefix, localState, ledger, festivalsMet)
        val merged = stored.merge(localEnvelope).let {
            PetSyncEnvelope(it.flags, prefix, it.state, it.progress, it.unlockedBits).refreshed()
        }
        saveEnvelope(merged)
        return merged
    }

    fun record(event: PetMobileEvent): PetSyncEnvelope {
        val cur = loadEnvelope()
        val s = cur.state.snapshot
        val p = cur.progress
        val nextSnap = when (event) {
            is PetMobileEvent.Pet -> if (event.isMimimo) s.copy(mimimo = s.mimimo.copy(pets = PetContacts.saturatingAdd(s.mimimo.pets, 1UL)))
            else s.copy(momo = s.momo.copy(pets = PetContacts.saturatingAdd(s.momo.pets, 1UL)))
            is PetMobileEvent.Play -> if (event.isMimimo) s.copy(mimimo = s.mimimo.copy(plays = PetContacts.saturatingAdd(s.mimimo.plays, 1UL)))
            else s.copy(momo = s.momo.copy(plays = PetContacts.saturatingAdd(s.momo.plays, 1UL)))
            is PetMobileEvent.Fall -> if (event.isMimimo) s.copy(mimimo = s.mimimo.copy(falls = PetContacts.saturatingAdd(s.mimimo.falls, 1UL)))
            else s.copy(momo = s.momo.copy(falls = PetContacts.saturatingAdd(s.momo.falls, 1UL)))
            else -> s
        }
        val nextProg = when (event) {
            PetMobileEvent.Purr -> p.copy(purrs = p.purrs + 1u)
            PetMobileEvent.Found -> p.copy(found = p.found + 1u)
            PetMobileEvent.Outfit -> p.copy(outfits = p.outfits + 1u)
            PetMobileEvent.ScrollShare -> p.copy(scrollShares = p.scrollShares + 1u)
            PetMobileEvent.Nose -> p.copy(nose = p.nose + 1u)
            is PetMobileEvent.Steps -> p.copy(steps = PetContacts.saturatingAdd(p.steps, event.count))
            else -> p
        }
        val next = PetSyncEnvelope(cur.flags, cur.groupPrefix, cur.state.copy(snapshot = nextSnap), nextProg, cur.unlockedBits).refreshed()
        saveEnvelope(next)
        return next
    }

    fun importBytes(bytes: ByteArray): PetSyncDecodeResult {
        val decoded = PetSyncEnvelope.fromBytes(bytes)
        if (decoded !is PetSyncDecodeResult.Ok) return decoded
        val merged = loadEnvelope().merge(decoded.envelope)
        saveEnvelope(merged)
        absorbIntoLedger(merged.state)
        return PetSyncDecodeResult.Ok(merged)
    }

    fun resetEpoch(): PetSyncEnvelope {
        val cleared = loadEnvelope().clearedForReset()
        saveEnvelope(cleared)
        return cleared
    }

    private fun buildLocalEnvelope(
        flags: Int,
        prefix: ByteArray,
        bookState: PetSyncState,
        ledger: StatsLedger?,
        festivalsMet: Int,
    ): PetSyncEnvelope {
        if (ledger == null || ledger.cleared) {
            return PetSyncEnvelope(flags, prefix, bookState).refreshed()
        }
        val epoch = maxOf(bookState.epoch, ledger.epoch.coerceAtLeast(0L).toUInt())
        val graphemes = maxOf(bookState.snapshot.committedGraphemes, ledger.graphemes.coerceAtLeast(0L).toULong())
        val snap = PetSnapshot(
            committedGraphemes = graphemes,
            mimimo = bookState.snapshot.mimimo.joined(PetContacts(ledger.mimimo.pets.toULong(), ledger.mimimo.falls.toULong(), ledger.mimimo.plays.toULong())),
            momo = bookState.snapshot.momo.joined(PetContacts(ledger.momo.pets.toULong(), ledger.momo.falls.toULong(), ledger.momo.plays.toULong())),
        )
        val book = bookState.book.copy()
        var peak1500 = 0u
        var snow4000 = 0u
        for ((d, entry) in ledger.dayMap) {
            if (d > 0) book.record(d.toUInt(), entry.size, entry.who, entry.part)
            if (entry.size >= 3) peak1500++
            if (entry.size >= 4) snow4000++
        }
        var placesMask = 0
        if (graphemes > 0UL) {
            val dist = GlyphJourney.distanceOf(minOf(graphemes, Long.MAX_VALUE.toULong()).toLong())
            val visits = JourneyStats.places(dist).visits
            for (i in 0 until minOf(visits.size, PetAchievementPlace.COUNT)) {
                if (visits[i] > 0 && GlyphScenes.ALL.getOrNull(i)?.enabled != false) {
                    placesMask = placesMask or (1 shl i)
                }
            }
        }
        val state = PetSyncState(epoch, snap, bookState.daily, book)
        val progress = PetAchievementProgress(
            places = placesMask and PetAchievementPlace.ALL_MASK,
            festivals = festivalsMet.coerceAtLeast(0).toUInt(),
            lifetimeDays = ledger.dayMap.size.toUInt(),
            peakDays1500 = peak1500,
            snowPeaks4000 = snow4000,
        )
        return PetSyncEnvelope(flags, prefix, state, progress).refreshed()
    }

    private fun absorbIntoLedger(state: PetSyncState) {
        val file = File(dir, ledgerFileName)
        val parsed = StatsLedger.parse(readText(file))
        if (!parsed.writable) return
        val ledger = parsed.ledger ?: StatsLedger.empty()
        val book = PetBookFile.Book(
            epoch = state.epoch.toLong(),
            graphemes = minOf(state.snapshot.committedGraphemes, Long.MAX_VALUE.toULong()).toLong(),
            mimimo = PetBookFile.Contacts(
                minOf(state.snapshot.mimimo.pets, Long.MAX_VALUE.toULong()).toLong(),
                minOf(state.snapshot.mimimo.falls, Long.MAX_VALUE.toULong()).toLong(),
                minOf(state.snapshot.mimimo.plays, Long.MAX_VALUE.toULong()).toLong(),
            ),
            momo = PetBookFile.Contacts(
                minOf(state.snapshot.momo.pets, Long.MAX_VALUE.toULong()).toLong(),
                minOf(state.snapshot.momo.falls, Long.MAX_VALUE.toULong()).toLong(),
                minOf(state.snapshot.momo.plays, Long.MAX_VALUE.toULong()).toLong(),
            ),
            marks = state.book.marks().map { PetBookFile.Mark(it.day.toInt(), it.size, it.who, it.part) },
        )
        if (ledger.absorb(book)) {
            writeBytes(file, ledger.toJson().toByteArray(Charsets.UTF_8))
        }
    }

    private fun readBytes(file: File): ByteArray? = try { if (file.isFile) file.readBytes() else null } catch (_: Exception) { null }
    private fun readText(file: File): String? = readBytes(file)?.toString(Charsets.UTF_8)
    private fun writeBytes(file: File, bytes: ByteArray): Boolean = try {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) { file.writeBytes(bytes); tmp.delete() }
        true
    } catch (_: Exception) { false }

    companion object {
        const val ENVELOPE_FILE = "PetSync.mpsync"
        const val PASS_CODE_FILE = "PetPassCode.txt"
    }
}
