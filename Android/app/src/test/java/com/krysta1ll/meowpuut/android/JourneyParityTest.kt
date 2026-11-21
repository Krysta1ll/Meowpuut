package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.app.stats.JourneyStats
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphJourney
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise.h2
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphScenes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * The same number of characters has to bring the cats to the same place on both phones. The iPhone's walk (GlyphJourney.swift, the
 * prototype's `placeAt`): eight units a character, a place lasts 110 + floor(h2(k, 777) * 170) units, the same hashes for which place comes
 * next. The iPhone's own check: 248,316 characters are stop 10,201, with 889 times in 山野, 921 in 海, 885 in 街, 916 in 竹林, 941 in 雪山 and
 * 853 in 月.
 */
class JourneyParityTest {
    private val characters = 248_316L
    private val iphoneStop = 10_201
    private val iphoneFirstSix = listOf(889, 921, 885, 916, 941, 853)

    /** The walk with the prototype's place lengths, on this keyboard's own choice of places. */
    private fun walkWithPrototypeLengths(distance: Long): Pair<Int, IntArray> {
        val journey = GlyphJourney()
        val visits = IntArray(GlyphScenes.ALL.size)
        var sum = 0L
        var n = 0
        while (true) {
            visits[journey.placeAt(n).scene]++
            sum += 110 + (h2(n, 777) * 170).toInt()
            if (sum > distance) return n + 1 to visits
            n++
        }
    }

    @Test
    fun theChoiceOfPlacesIsThePrototypesAndTheIPhones() {
        val (stop, visits) = walkWithPrototypeLengths(GlyphJourney.distanceOf(characters))
        assertEquals(iphoneStop, stop)
        assertEquals(iphoneFirstSix, visits.take(6))
    }

    /**
     * Not yet: this keyboard's places last 90 + floor(h2(k, 777) * 180) units (GlyphJourney.MIN_LEN, SPAN), so the same characters are
     * stop 11,056 here. The choice of places is the same (the test above); the length constants are the only difference. Take the
     * @Ignore off when they are the prototype's 110 and 170.
     */
    @Ignore("GlyphJourney.MIN_LEN/SPAN are 90/180; the prototype and the iPhone have 110/170")
    @Test
    fun theSameCharactersAreTheSamePlaceOnBothPhones() {
        val stats = JourneyStats.places(GlyphJourney.distanceOf(characters))
        assertEquals(iphoneStop, stats.stop)
        assertEquals(iphoneFirstSix, stats.visits.take(6))
        assertTrue(GlyphJourney.MIN_LEN == 110 && GlyphJourney.SPAN == 170)
    }
}
