package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.app.CatPhoto
import com.krysta1ll.meowpuut.android.app.DailyPhotoOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatPhotoStoreTest {
    @Test
    fun dailyOrderIsDeterministicPreservesAllPhotosAndRotatesLeadDaily() {
        val sample = (1..24).map { i ->
            CatPhoto(
                file = "photo-%04x.jpg".format(i * 7919),
                width = 1200,
                height = 1600,
                cats = when {
                    i % 3 == 0 -> listOf("mimimo", "momo")
                    i % 2 == 0 -> listOf("mimimo")
                    else -> listOf("momo")
                },
                month = if (i % 4 == 0) "2026-01" else null,
            )
        }

        val a = DailyPhotoOrder.shuffle(sample, CatPhoto::file, 2026, 11, 19)
        val b = DailyPhotoOrder.shuffle(sample.reversed(), CatPhoto::file, 2026, 11, 19)
        assertEquals(a, b)
        assertEquals(sample.size, a.size)
        assertEquals(sample.map { it.file }.toSet(), a.map { it.file }.toSet())

        // Cross-platform golden vector check matching Swift PhotoRotationTests.
        val goldenInput = listOf("a.jpg", "b.jpg", "c.jpg", "d.jpg", "e.jpg")
        val golden = DailyPhotoOrder.shuffle(goldenInput, { it }, 2026, 11, 19)
        assertEquals(listOf("c.jpg", "d.jpg", "a.jpg", "e.jpg", "b.jpg"), golden)

        // Lead photo never repeats on consecutive days across 365 days.
        var y = 2026
        var m = 1
        var d = 1
        var prevFirst: String? = null
        val distinctFirsts = HashSet<String>()
        repeat(365) {
            val rotated = DailyPhotoOrder.shuffle(sample, CatPhoto::file, y, m, d)
            if (prevFirst != null) {
                assertNotEquals("hero repeated on $y-$m-$d", prevFirst, rotated[0].file)
            }
            prevFirst = rotated[0].file
            distinctFirsts += rotated[0].file
            d += 1
            val daysInMonth = when (m) {
                2 -> 28
                4, 6, 9, 11 -> 30
                else -> 31
            }
            if (d > daysInMonth) {
                d = 1
                m += 1
                if (m > 12) {
                    m = 1
                    y += 1
                }
            }
        }
        assertTrue(distinctFirsts.size >= 20)
    }

    @Test
    fun safeFilenameAndManifestValidationRejectsTraversalAndInvalidEntries() {
        assertTrue(DailyPhotoOrder.isSafeFilename("photo-07382c07effd.jpg"))
        assertTrue(DailyPhotoOrder.isSafeFilename("CAT_1.JPEG"))
        assertFalse(DailyPhotoOrder.isSafeFilename("../secret.jpg"))
        assertFalse(DailyPhotoOrder.isSafeFilename("dir/photo.jpg"))
        assertFalse(DailyPhotoOrder.isSafeFilename("photo.png"))
        assertFalse(DailyPhotoOrder.isSafeFilename(""))

        val raw = """
            {
              "version": 1,
              "photos": [
                {"file": "valid-1.jpg", "width": 1000, "height": 1500, "cats": ["momo"], "month": "2026-01"},
                {"file": "../evil.jpg", "width": 1000, "height": 1500, "cats": ["mimimo"]},
                {"file": "valid-1.jpg", "width": 1000, "height": 1500, "cats": ["momo"]},
                {"file": "zero.jpg", "width": 0, "height": 100, "cats": ["momo"]}
              ]
            }
        """.trimIndent()
        val parsed = DailyPhotoOrder.parseManifest(raw)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.size)
        assertEquals("valid-1.jpg", parsed[0].file)
        assertEquals("2026 年 1 月", parsed[0].monthText)
        assertEquals("莫莫", parsed[0].title())

        assertNull(DailyPhotoOrder.parseManifest("""{"version": 2, "photos": []}"""))
    }
}
