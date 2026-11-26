package com.krysta1ll.meowpuut.android.app.stats

/** Monotonic achievement progress counters mirroring `achievements.rs::Progress`. */
data class PetAchievementProgress(
    val places: Int = 0,
    val festivals: UInt = 0u,
    val lifetimeDays: UInt = 0u,
    val maxDayGraphemes: UInt = 0u,
    val peakDays1500: UInt = 0u,
    val snowPeaks4000: UInt = 0u,
    /** Unified cumulative meow-units (`max(real_chars, active_minutes * 6)`) for `CHARS_*` and `MASTER`. */
    val meowChars: ULong = 0UL,
    val outings: UInt = 0u,
    val purrs: UInt = 0u,
    val rides: UInt = 0u,
    val caught: UInt = 0u,
    val found: UInt = 0u,
    val roof: UInt = 0u,
    val steps: ULong = 0UL,
    val nose: UInt = 0u,
    val moved: UInt = 0u,
    val cuddles: UInt = 0u,
    val outfits: UInt = 0u,
    val scrollShares: UInt = 0u,
) {
    fun merged(other: PetAchievementProgress) = PetAchievementProgress(
        places = (places or other.places) and PetAchievementPlace.ALL_MASK,
        festivals = maxOf(festivals, other.festivals),
        lifetimeDays = maxOf(lifetimeDays, other.lifetimeDays),
        maxDayGraphemes = maxOf(maxDayGraphemes, other.maxDayGraphemes),
        peakDays1500 = maxOf(peakDays1500, other.peakDays1500),
        snowPeaks4000 = maxOf(snowPeaks4000, other.snowPeaks4000),
        meowChars = maxOf(meowChars, other.meowChars),
        outings = maxOf(outings, other.outings),
        purrs = maxOf(purrs, other.purrs),
        rides = maxOf(rides, other.rides),
        caught = maxOf(caught, other.caught),
        found = maxOf(found, other.found),
        roof = maxOf(roof, other.roof),
        steps = maxOf(steps, other.steps),
        nose = maxOf(nose, other.nose),
        moved = maxOf(moved, other.moved),
        cuddles = maxOf(cuddles, other.cuddles),
        outfits = maxOf(outfits, other.outfits),
        scrollShares = maxOf(scrollShares, other.scrollShares),
    )
}

data class PetAchievementUnlock(val api: String, val steam: Boolean)

data class PetAchievementStatus(
    val def: PetAchievementDef,
    val unlocked: Boolean,
    val current: ULong,
    val target: ULong,
) {
    val fraction: Float
        get() = when {
            unlocked -> 1f
            target == 0UL -> 0f
            else -> (current.toDouble() / target.toDouble()).toFloat().coerceIn(0f, 1f)
        }
}

/** Pure achievement evaluator aligned 1:1 with `Engine/crates/meowpuut-pets/src/achievements.rs`. */
object PetAchievementsEvaluator {
    private const val DAWN_BIT = 1 shl 0
    private const val LATE_NIGHT_BIT = 1 shl 4

    fun evaluate(
        state: PetSyncState,
        progress: PetAchievementProgress,
        already: Set<String> = emptySet(),
    ): List<PetAchievementUnlock> {
        val out = ArrayList<PetAchievementUnlock>()
        for (def in PetAchievementsCatalog.TABLE) {
            if (def.enabled && def.api !in already && isMet(def.condition, state, progress)) {
                out += PetAchievementUnlock(def.api, def.steam)
            }
        }
        return out
    }

    fun statuses(
        state: PetSyncState,
        progress: PetAchievementProgress,
        unlockedBits: ByteArray,
    ): List<PetAchievementStatus> = PetAchievementsCatalog.TABLE.mapIndexed { idx, def ->
        val bitSet = idx < 128 && unlockedBits.size == 16 &&
            ((unlockedBits[idx / 8].toInt() and (1 shl (idx % 8))) != 0)
        val unlocked = bitSet || (def.enabled && isMet(def.condition, state, progress))
        val (cur, target) = meter(def.condition, state, progress)
        PetAchievementStatus(def, unlocked, minOf(cur, target), target)
    }

    fun isMet(cond: PetAchievementCondition, state: PetSyncState, p: PetAchievementProgress): Boolean = when (cond) {
        is PetAchievementCondition.Chars -> effectiveChars(state, p) >= cond.count
        is PetAchievementCondition.Days -> days(state, p) >= cond.count.toULong()
        is PetAchievementCondition.PeakDay -> peakDayMet(state, p, cond.count)
        is PetAchievementCondition.PeakDays1500 -> countBandDays(state, PetSyncState.sizeBand(1_500u), p.peakDays1500) >= cond.count
        is PetAchievementCondition.SnowPeaks4000 -> countBandDays(state, PetSyncState.sizeBand(4_000u), p.snowPeaks4000) >= cond.count
        is PetAchievementCondition.Place -> cond.index in 0 until PetAchievementPlace.COUNT && (p.places and (1 shl cond.index)) != 0
        PetAchievementCondition.AllPlaces -> (p.places and PetAchievementPlace.ALL_MASK) == PetAchievementPlace.ALL_MASK
        is PetAchievementCondition.Festivals -> p.festivals >= cond.count
        is PetAchievementCondition.MimimoPets -> state.snapshot.mimimo.pets >= cond.count
        is PetAchievementCondition.MomoPets -> state.snapshot.momo.pets >= cond.count
        is PetAchievementCondition.BothPets -> state.snapshot.mimimo.pets >= cond.count && state.snapshot.momo.pets >= cond.count
        is PetAchievementCondition.BondLevel -> bondMet(state, cond.who, cond.level)
        is PetAchievementCondition.Plays -> PetContacts.saturatingAdd(state.snapshot.mimimo.plays, state.snapshot.momo.plays) >= cond.count
        is PetAchievementCondition.Falls -> PetContacts.saturatingAdd(state.snapshot.mimimo.falls, state.snapshot.momo.falls) >= cond.count
        is PetAchievementCondition.Outings -> p.outings >= cond.count
        is PetAchievementCondition.Purr -> p.purrs >= cond.count || (cond.count == 1u && (state.daily.purrsMimimo > 0 || state.daily.purrsMomo > 0))
        is PetAchievementCondition.Ride -> p.rides >= cond.count
        is PetAchievementCondition.Caught -> p.caught >= cond.count
        is PetAchievementCondition.Found -> p.found >= cond.count
        is PetAchievementCondition.Roof -> p.roof >= cond.count
        is PetAchievementCondition.Steps -> p.steps >= cond.count
        is PetAchievementCondition.Nose -> p.nose >= cond.count
        PetAchievementCondition.Moved -> p.moved >= 1u
        PetAchievementCondition.Cuddle -> p.cuddles >= 1u
        PetAchievementCondition.Outfit -> p.outfits >= 1u
        is PetAchievementCondition.Scroll -> p.scrollShares >= cond.count
        PetAchievementCondition.Master ->
            effectiveChars(state, p) >= 1_000_000UL &&
                days(state, p) >= 100UL &&
                (p.places and PetAchievementPlace.ALL_MASK) == PetAchievementPlace.ALL_MASK &&
                bondMet(state, 2, 100)
        PetAchievementCondition.DawnDay -> partSeen(state, DAWN_BIT)
        PetAchievementCondition.LateNightDay -> partSeen(state, LATE_NIGHT_BIT)
    }

    fun meter(cond: PetAchievementCondition, state: PetSyncState, p: PetAchievementProgress): Pair<ULong, ULong> = when (cond) {
        is PetAchievementCondition.Chars -> effectiveChars(state, p) to cond.count
        is PetAchievementCondition.Days -> days(state, p) to cond.count.toULong()
        is PetAchievementCondition.PeakDay -> {
            val cur = if (peakDayMet(state, p, cond.count)) cond.count else maxOf(p.maxDayGraphemes, state.daily.graphemes)
            cur.toULong() to cond.count.toULong()
        }
        is PetAchievementCondition.PeakDays1500 -> countBandDays(state, PetSyncState.sizeBand(1_500u), p.peakDays1500).toULong() to cond.count.toULong()
        is PetAchievementCondition.SnowPeaks4000 -> countBandDays(state, PetSyncState.sizeBand(4_000u), p.snowPeaks4000).toULong() to cond.count.toULong()
        is PetAchievementCondition.Place -> (if ((p.places and (1 shl cond.index)) != 0) 1UL else 0UL) to 1UL
        PetAchievementCondition.AllPlaces -> Integer.bitCount(p.places and PetAchievementPlace.ALL_MASK).toULong() to PetAchievementPlace.COUNT.toULong()
        is PetAchievementCondition.Festivals -> p.festivals.toULong() to cond.count.toULong()
        is PetAchievementCondition.MimimoPets -> state.snapshot.mimimo.pets to cond.count
        is PetAchievementCondition.MomoPets -> state.snapshot.momo.pets to cond.count
        is PetAchievementCondition.BothPets -> minOf(state.snapshot.mimimo.pets, state.snapshot.momo.pets) to cond.count
        is PetAchievementCondition.BondLevel -> {
            val m = state.snapshot.bondLevel(true).toULong()
            val o = state.snapshot.bondLevel(false).toULong()
            val cur = when (cond.who) {
                0 -> m
                1 -> o
                else -> minOf(m, o)
            }
            cur to cond.level.toULong()
        }
        is PetAchievementCondition.Plays -> PetContacts.saturatingAdd(state.snapshot.mimimo.plays, state.snapshot.momo.plays) to cond.count
        is PetAchievementCondition.Falls -> PetContacts.saturatingAdd(state.snapshot.mimimo.falls, state.snapshot.momo.falls) to cond.count
        is PetAchievementCondition.Outings -> p.outings.toULong() to cond.count.toULong()
        is PetAchievementCondition.Purr -> maxOf(p.purrs, if (state.daily.purrsMimimo > 0 || state.daily.purrsMomo > 0) 1u else 0u).toULong() to cond.count.toULong()
        is PetAchievementCondition.Ride -> p.rides.toULong() to cond.count.toULong()
        is PetAchievementCondition.Caught -> p.caught.toULong() to cond.count.toULong()
        is PetAchievementCondition.Found -> p.found.toULong() to cond.count.toULong()
        is PetAchievementCondition.Roof -> p.roof.toULong() to cond.count.toULong()
        is PetAchievementCondition.Steps -> p.steps to cond.count
        is PetAchievementCondition.Nose -> p.nose.toULong() to cond.count.toULong()
        PetAchievementCondition.Moved -> minOf(p.moved, 1u).toULong() to 1UL
        PetAchievementCondition.Cuddle -> minOf(p.cuddles, 1u).toULong() to 1UL
        PetAchievementCondition.Outfit -> minOf(p.outfits, 1u).toULong() to 1UL
        is PetAchievementCondition.Scroll -> p.scrollShares.toULong() to cond.count.toULong()
        PetAchievementCondition.Master -> {
            var parts = 0UL
            if (effectiveChars(state, p) >= 1_000_000UL) parts++
            if (days(state, p) >= 100UL) parts++
            if ((p.places and PetAchievementPlace.ALL_MASK) == PetAchievementPlace.ALL_MASK) parts++
            if (bondMet(state, 2, 100)) parts++
            parts to 4UL
        }
        PetAchievementCondition.DawnDay -> (if (partSeen(state, DAWN_BIT)) 1UL else 0UL) to 1UL
        PetAchievementCondition.LateNightDay -> (if (partSeen(state, LATE_NIGHT_BIT)) 1UL else 0UL) to 1UL
    }

    private fun effectiveChars(state: PetSyncState, p: PetAchievementProgress): ULong =
        maxOf(state.snapshot.committedGraphemes, p.meowChars)

    private fun days(state: PetSyncState, p: PetAchievementProgress): ULong =
        maxOf(state.book.count.toULong(), p.lifetimeDays.toULong())

    private fun peakDayMet(state: PetSyncState, p: PetAchievementProgress, threshold: UInt): Boolean {
        if (p.maxDayGraphemes >= threshold || state.daily.graphemes >= threshold) return true
        if (threshold > 4_000u) return false
        val band = PetSyncState.sizeBand(threshold)
        return state.book.slots.any { !it.isEmpty && it.size >= band }
    }

    private fun countBandDays(state: PetSyncState, minBand: Int, persisted: UInt): UInt {
        var inBook = 0u
        for (m in state.book.slots) if (!m.isEmpty && m.size >= minBand) inBook++
        return maxOf(inBook, persisted)
    }

    private fun partSeen(state: PetSyncState, bit: Int): Boolean =
        state.book.slots.any { !it.isEmpty && (it.part and bit) != 0 }

    private fun bondMet(state: PetSyncState, who: Int, level: Int): Boolean {
        val m = state.snapshot.bondLevel(true)
        val o = state.snapshot.bondLevel(false)
        return when (who) {
            0 -> m >= level
            1 -> o >= level
            else -> m >= level && o >= level
        }
    }
}
