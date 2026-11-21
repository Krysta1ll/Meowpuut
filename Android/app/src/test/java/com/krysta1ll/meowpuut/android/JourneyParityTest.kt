package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.app.stats.JourneyStats
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphJourney
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise.h2
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphScenes
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The same number of characters has to bring the cats to the same place on every phone, and the keyboard has to walk the road the browser
 * prototype walks. The reference (src/test/resources/glyph-journey-parity.json) is written by scripts/gen_glyph_journey_parity.js, which runs
 * the prototype's own `placeAt`; the iPhone walks the same rule (GlyphJourney.swift). The rule: eight units a character, the first place is
 * always the sea, a place lasts 1200 + floor(h2(k, 777) * 800) units, the others come by the same hashes, a common place never follows
 * itself and the rare ones come only from the third place on and never twice running.
 */
class JourneyParityTest {
    private val reference = JSONObject(javaClass.getResourceAsStream("/glyph-journey-parity.json")!!.bufferedReader().readText())
    private val names = GlyphScenes.ALL.map { it.name }
    private val characters = reference.getJSONObject("tally").getLong("characters")
    private val units = reference.getJSONObject("tally").getLong("units")
    private val stop = reference.getJSONObject("tally").getInt("stop")
    private val visits: Map<String, Int> = reference.getJSONObject("tally").getJSONObject("visits").let { o -> o.keys().asSequence().associateWith { o.getInt(it) } }

    /** The walk with the prototype's place lengths, on this keyboard's own choice of places. */
    private fun walkWithPrototypeLengths(distance: Long): Pair<Int, Map<String, Int>> {
        val journey = GlyphJourney()
        val seen = HashMap<String, Int>()
        var sum = 0L
        var n = 0
        while (true) {
            seen.merge(names[journey.placeAt(n).scene], 1, Int::plus)
            sum += GlyphJourney.MIN_LEN + (h2(n, 777) * GlyphJourney.SPAN).toInt()
            if (sum > distance) return n + 1 to seen
            n++
        }
    }

    @Test
    fun theScenesAreTheSameAndInTheSameOrderAsThePrototypes() {
        val scenes = reference.getJSONArray("scenes")
        assertEquals(scenes.length(), names.size)
        for (i in names.indices) assertEquals(scenes.getString(i), names[i])
    }

    @Test
    fun everyPlaceOfTheRoadIsTheSceneThePrototypeWalksTo() {
        val journey = GlyphJourney()
        val places = reference.getJSONArray("places")
        for (k in 0 until places.length()) assertEquals("place $k", places.getJSONObject(k).getString("scene"), names[journey.placeAt(k).scene])
    }

    @Test
    fun theFirstPlaceIsTheSeaAndTheSecondIsNotAndAFreshJourneyIsAtTheSea() {
        val sea = names.indexOf("海")
        assertTrue(sea >= 0)
        val journey = GlyphJourney()
        assertEquals(sea, journey.placeAt(0).scene)
        assertEquals(sea, GlyphJourney.START)
        assertNotEquals(sea, journey.placeAt(1).scene)                       // no place follows itself
        val fresh = JourneyStats.places(GlyphJourney.distanceOf(0))
        assertEquals(sea, fresh.here)
        assertEquals(1, fresh.stop)
        // Every other place is as it was: the first place's own length still comes from its hash.
        assertTrue(journey.placeAt(0).len in GlyphJourney.MIN_LEN until GlyphJourney.MIN_LEN + GlyphJourney.SPAN)
    }

    @Test
    fun theChoiceOfPlacesWithThePrototypesLengthsIsThePrototypesWalk() {
        val (n, seen) = walkWithPrototypeLengths(GlyphJourney.distanceOf(characters))
        assertEquals(units, GlyphJourney.distanceOf(characters))
        assertEquals(stop, n)
        for ((name, count) in visits) assertEquals(name, count, seen[name] ?: 0)
        assertEquals(1_237, n)
        assertEquals(114, seen["海"])
        // The first five places and how long each lasts, as the prototype has them.
        val places = reference.getJSONArray("places")
        assertEquals(listOf("海" to 1685, "沙漠" to 1704, "紅葉" to 1866, "火山" to 1719, "月" to 1335), (0 until 5).map { places.getJSONObject(it).let { p -> p.getString("scene") to p.getInt("len") } })
    }

    /** The same characters are the same stop, with the same visits to each place, and every place lasts what it lasts in the prototype. */
    @Test
    fun theSameCharactersAreTheSamePlaceOnEveryPhone() {
        val stats = JourneyStats.places(GlyphJourney.distanceOf(characters))
        assertEquals(stop, stats.stop)
        for ((name, count) in visits) assertEquals(name, count, stats.visits[names.indexOf(name)])
        val journey = GlyphJourney()
        val places = reference.getJSONArray("places")
        for (k in 0 until places.length()) assertEquals("length of place $k", places.getJSONObject(k).getInt("len"), journey.placeAt(k).len)
    }
}
