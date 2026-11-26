package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.app.stats.PetAchievementPlace
import com.krysta1ll.meowpuut.android.app.stats.PetAchievementProgress
import com.krysta1ll.meowpuut.android.app.stats.PetAchievementsCatalog
import com.krysta1ll.meowpuut.android.app.stats.PetAchievementsEvaluator
import com.krysta1ll.meowpuut.android.app.stats.PetContacts
import com.krysta1ll.meowpuut.android.app.stats.PetDailyState
import com.krysta1ll.meowpuut.android.app.stats.PetPawBook
import com.krysta1ll.meowpuut.android.app.stats.PetSnapshot
import com.krysta1ll.meowpuut.android.app.stats.PetSyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PetAchievementsTest {
    private fun ids(state: PetSyncState, p: PetAchievementProgress) =
        PetAchievementsEvaluator.evaluate(state, p).map { it.api }

    private fun has(state: PetSyncState, p: PetAchievementProgress, api: String) =
        api in ids(state, p)

    private fun withChars(n: ULong) = PetSyncState(snapshot = PetSnapshot(committedGraphemes = n))

    private fun bookDays(n: Int, size: Int, part: Int): PetSyncState {
        val book = PetPawBook()
        for (d in 0 until n) book.record((20_000 + d).toUInt(), size, 2, part)
        return PetSyncState(book = book)
    }

    @Test
    fun catalogShapeBitIndicesAndComplianceRules() {
        val table = PetAchievementsCatalog.TABLE
        assertEquals(82, table.size)
        assertEquals(80, table.count { it.steam })
        assertEquals(2, table.count { !it.steam })
        assertEquals(table.size, table.map { it.api }.toSet().size)
        assertEquals("CHARS_2M", table[8].api)
        assertEquals("CHARS_3M", table[9].api)
        assertEquals("CHARS_4M", table[10].api)
        assertEquals("MASTER_MEOWPUUT", table[79].api)

        val bondBoth = table.first { it.api == "BOND_BOTH_MAX" }
        assertFalse(bondBoth.descHant.contains("【極難】"))
        assertFalse(bondBoth.descHans.contains("【极难】"))

        val deny = listOf(
            "國家", "国家", "地區", "地区", "區域", "国度", "中國", "中国", "台灣", "台湾", "大陸", "大陆",
            "country", "region", "China", "Taiwan",
            "中秋", "冬至", "春節", "春节", "元宵", "端午", "七夕", "清明", "重陽", "重阳", "聖誕", "圣诞", "新年", "除夕",
            "連續", "连续", "連勝", "每天", "天天", "限時", "限时", "打卡", "streak", "零作弊",
            "擺擺布", "摆摆布", "手買", "手买", "肚買", "肚买", "手咪", "肚咪", "買買木", "买买木", "坑坑",
        )
        for (d in table) {
            assertEquals(!d.api.startsWith("LOCAL_"), d.steam)
            for (text in listOf(d.api, d.zhHant, d.zhHans, d.descHant, d.descHans)) {
                assertTrue(text.isNotEmpty())
                for (w in deny) {
                    assertFalse("${d.api} contains forbidden word $w", text.contains(w))
                }
            }
        }
        assertTrue(table.any { it.api == "PLACE_HONG_KONG" && it.zhHant.contains("香港") })
        assertTrue(table.any { it.api == "PLACE_FUJI" && it.zhHant.contains("富士") })
    }

    @Test
    fun edgesPlacesBondsMeowCharsAndDisabledGuard() {
        val empty = PetAchievementProgress()
        assertTrue(ids(PetSyncState(), empty).isEmpty())

        for ((api, n) in listOf(
            "CHARS_1" to 1UL, "CHARS_100" to 100UL, "CHARS_1K" to 1_000UL,
            "CHARS_10K" to 10_000UL, "CHARS_1M" to 1_000_000UL,
            "CHARS_2M" to 2_000_000UL, "CHARS_3M" to 3_000_000UL, "CHARS_4M" to 4_000_000UL,
        )) {
            assertFalse(has(withChars(n - 1UL), empty, api))
            assertTrue(has(withChars(n), empty, api))
            assertFalse(has(PetSyncState(), PetAchievementProgress(meowChars = n - 1UL), api))
            assertTrue(has(PetSyncState(), PetAchievementProgress(meowChars = n), api))
        }

        // meowChars never spoofs single-day peak achievements.
        val onlyMeow = PetAchievementProgress(meowChars = 5_000_000UL)
        assertFalse(has(PetSyncState(), onlyMeow, "DAYS_PEAK_1500"))
        assertFalse(has(PetSyncState(), onlyMeow, "DAYS_PEAK_4000"))

        // MASTER_MEOWPUUT uses 1,000,000 effective chars.
        val masterState = PetSyncState(
            snapshot = PetSnapshot(
                mimimo = PetContacts(pets = 300UL),
                momo = PetContacts(pets = 300UL),
            ),
        )
        assertFalse(has(masterState, PetAchievementProgress(places = PetAchievementPlace.ALL_MASK, lifetimeDays = 100u, meowChars = 999_999UL), "MASTER_MEOWPUUT"))
        assertTrue(has(masterState, PetAchievementProgress(places = PetAchievementPlace.ALL_MASK, lifetimeDays = 100u, meowChars = 1_000_000UL), "MASTER_MEOWPUUT"))

        assertFalse(has(bookDays(99, 0, 1), empty, "DAYS_100"))
        assertTrue(has(bookDays(100, 0, 1), empty, "DAYS_100"))
        assertTrue(has(bookDays(1, 3, 1), empty, "DAYS_PEAK_1500"))
        assertFalse(has(bookDays(1, 3, 1), empty, "DAYS_PEAK_4000"))
        assertTrue(has(bookDays(1, 4, 1), empty, "DAYS_PEAK_4000"))
        assertFalse(has(bookDays(1, 4, 1), empty, "DAYS_PEAK_8000"))
        assertTrue(has(PetSyncState(), PetAchievementProgress(maxDayGraphemes = 8_000u), "DAYS_PEAK_8000"))

        for (i in 0 until PetAchievementPlace.COUNT) {
            val only = PetAchievementProgress(places = 1 shl i)
            assertEquals(1, ids(PetSyncState(), only).size)
        }
        assertTrue(has(PetSyncState(), PetAchievementProgress(places = PetAchievementPlace.ALL_MASK), "PLACES_12"))

        // Daily purr triggers CAT_PURR even without lifetime counter.
        val todayPurr = PetSyncState(daily = PetDailyState(purrsMomo = 1))
        assertTrue(has(todayPurr, empty, "CAT_PURR"))

        // Max state reaches all 81 enabled achievements; disabled CAT_CUDDLE never unlocks.
        val book = PetPawBook().apply { record(20_000u, 4, 3, 0b1_0001) }
        val maxState = PetSyncState(
            snapshot = PetSnapshot(
                committedGraphemes = ULong.MAX_VALUE,
                mimimo = PetContacts(5_000UL, 25UL, 500UL),
                momo = PetContacts(5_000UL, 25UL, 500UL),
            ),
            book = book,
        )
        val maxProg = PetAchievementProgress(
            places = PetAchievementPlace.ALL_MASK, festivals = 100u, lifetimeDays = 1_000u,
            maxDayGraphemes = 15_000u, peakDays1500 = 50u, snowPeaks4000 = 50u, meowChars = ULong.MAX_VALUE,
            outings = 100u, purrs = 100u, rides = 50u, caught = 25u, found = 25u, roof = 100u,
            steps = 1_000_000UL, nose = 50u, moved = 1u, cuddles = 1u, outfits = 1u, scrollShares = 10u,
        )
        val unlocked = ids(maxState, maxProg)
        assertFalse("CAT_CUDDLE" in unlocked)
        assertEquals(81, unlocked.size)
    }

    @Test
    fun idempotentAndStableTableOrder() {
        val state = bookDays(3, 4, 1).copy(snapshot = PetSnapshot(committedGraphemes = 12_000UL))
        val p = PetAchievementProgress(places = 0b11, festivals = 1u)
        val first = PetAchievementsEvaluator.evaluate(state, p)
        assertEquals(
            listOf(
                "CHARS_1", "CHARS_100", "CHARS_1K", "CHARS_10K",
                "DAYS_1", "DAYS_PEAK_1500", "DAYS_PEAK_4000",
                "PLACE_FIELD", "PLACE_SEA", "FEST_1", "LOCAL_DAWN",
            ),
            first.map { it.api },
        )
        assertTrue(PetAchievementsEvaluator.evaluate(state, p, first.map { it.api }.toSet()).isEmpty())
    }
}
