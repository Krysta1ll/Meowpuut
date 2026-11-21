package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.ime.glyph.GlyphJourney
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphScenes
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphSecrets
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphSecrets.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlyphJourneyTest {
    @Test
    fun sixPlacesAndOneRareOne() {
        assertEquals(listOf("山野", "海", "街", "竹林", "雪山", "月"), GlyphScenes.ALL.map { it.name })
        assertEquals(1, GlyphScenes.ALL.count { it.rare })
        assertEquals(5, GlyphScenes.RARE)
        assertEquals(listOf(0, 1, 2, 3, 4), GlyphScenes.COMMON)
        for (scene in GlyphScenes.ALL) {
            assertTrue(scene.walk in 0 until (scene.front.size + scene.back.size))
            for (layer in scene.front + scene.back) layer.marks?.forEach { assertTrue(GlyphScenes.STAMPS.containsKey(it)) }
        }
    }

    @Test
    fun noPlaceFollowsItselfAndTheRareOneComesOnlyAfterTheFirstTwo() {
        val journey = GlyphJourney()
        var before = -1
        for (k in 0 until 4000) {
            val place = journey.placeAt(k)
            assertNotEquals("place $k repeats", before, place.scene)
            if (k <= 1) assertNotEquals("the rare place is not among the first two", GlyphScenes.RARE, place.scene)
            assertTrue(place.len in GlyphJourney.MIN_LEN until GlyphJourney.MIN_LEN + GlyphJourney.SPAN)
            before = place.scene
        }
    }

    @Test
    fun aboutOnePlaceInTenIsTheRareOne() {
        val journey = GlyphJourney()
        val n = 30000
        val rare = (0 until n).count { journey.placeAt(it).scene == GlyphScenes.RARE }
        val share = rare / n.toDouble()
        assertTrue("share was $share", share in 0.08..0.105)
        // and never two in a row
        for (k in 1 until 3000) assertFalse(journey.placeAt(k).scene == GlyphScenes.RARE && journey.placeAt(k - 1).scene == GlyphScenes.RARE)
    }

    @Test
    fun theSameJourneyEverywhere() {
        val a = GlyphJourney()
        val b = GlyphJourney()
        b.placeAt(500)
        for (k in 0 until 100) assertEquals(a.placeAt(k).scene, b.placeAt(k).scene)
    }

    @Test
    fun theDistanceFindsItsPlaceForwardAndBack() {
        val journey = GlyphJourney()
        var from = 0L
        for (n in 0 until 40) {
            val len = journey.placeAt(n).len
            assertEquals(n to from, journey.at(from))
            assertEquals(n to from, journey.at(from + len - 1))
            from += len
        }
        // going back to an earlier distance starts the walk again
        assertEquals(0 to 0L, journey.at(0))
        assertEquals(1 to journey.placeAt(0).len.toLong(), journey.at(journey.placeAt(0).len.toLong()))
    }

    @Test
    fun theFogThickensTowardABorderAndThinsOutOfOne() {
        val journey = GlyphJourney()
        val len0 = journey.placeAt(0).len.toLong()
        assertEquals("the first place begins clear", 1.0, journey.fogAt(0), 0.0)
        assertEquals(1.0, journey.fogAt(len0 / 2), 0.0)
        assertEquals((GlyphJourney.FOG - 1).toDouble() / GlyphJourney.FOG, journey.fogAt(len0 - GlyphJourney.FOG + 1), 1e-9)
        assertEquals(1.0 / GlyphJourney.FOG, journey.fogAt(len0 - 1), 1e-9)
        // across the border: thin again on the far side, then clearing
        assertEquals(1.0 / GlyphJourney.FOG, journey.fogAt(len0), 1e-9)
        assertEquals(1.0, journey.fogAt(len0 + GlyphJourney.FOG), 0.0)
        for (d in 0L until len0 * 3) assertTrue(journey.fogAt(d) in 0.0..1.0)
    }

    // ---- the words that do something ------------------------------------------------------------------------

    @Test
    fun theGuestComesWhenItsNameIsTypedInAnyCase() {
        assertEquals(listOf(Trigger.GUEST), GlyphSecrets().feed("claude", 0, false))
        assertEquals(listOf(Trigger.GUEST), GlyphSecrets().feed("Claude", 0, false))
        val split = GlyphSecrets()
        assertTrue(split.feed("cla", 0, false).isEmpty())
        assertEquals(listOf(Trigger.GUEST), split.feed("ude", 100, false))
    }

    @Test
    fun theGlitchComesFromThreeWords() {
        for (word in listOf("glitch", "bug", "404", "Debug")) assertEquals(word, listOf(Trigger.GLITCH), GlyphSecrets().feed(word, 0, false))
    }

    @Test
    fun goodnightAndTheMeowAreHeardInChinese() {
        assertEquals(listOf(Trigger.GOODNIGHT), GlyphSecrets().feed("晚安", 0, false))
        assertEquals(listOf(Trigger.MEOW), GlyphSecrets().feed("喵", 0, false))
        assertEquals(listOf(Trigger.GOODNIGHT, Trigger.MEOW), GlyphSecrets().feed("晚安喵", 0, false))
    }

    @Test
    fun theFishNeedsAJapaneseWord() {
        assertEquals(listOf(Trigger.FISH), GlyphSecrets().feed("寿司", 0, true))
        assertTrue(GlyphSecrets().feed("寿司", 0, false).isEmpty())
        assertTrue(GlyphSecrets().feed("壽司", 0, true).isEmpty())
    }

    @Test
    fun aWordDoesNotFireAgainWithTheNextLetter() {
        val secrets = GlyphSecrets()
        assertEquals(listOf(Trigger.GUEST), secrets.feed("claude", 0, false))
        assertEquals(0, secrets.held)
        assertTrue(secrets.feed("s", 10, false).isEmpty())
    }

    @Test
    fun aPauseMakesItForgetWhatWasTyped() {
        val secrets = GlyphSecrets()
        assertTrue(secrets.feed("cla", 0, false).isEmpty())
        // thirty-one seconds later the rest does not complete it
        assertTrue(secrets.feed("ude", 31_000, false).isEmpty())
        // but within the half minute it does
        val quick = GlyphSecrets()
        quick.feed("cla", 0, false)
        assertEquals(listOf(Trigger.GUEST), quick.feed("ude", 29_000, false))
    }

    @Test
    fun onlyAFewCharactersAreEverHeldAndClearForgetsThem() {
        val secrets = GlyphSecrets()
        secrets.feed("abcdefghijklmnopqrstuvwxyz", 0, false)
        assertTrue(secrets.held <= GlyphSecrets.MAX_TAIL)
        secrets.feed("x", 1, false)
        secrets.clear()
        assertEquals(0, secrets.held)
    }
}
