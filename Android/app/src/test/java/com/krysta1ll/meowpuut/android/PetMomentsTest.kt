package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.ime.Cat
import com.krysta1ll.meowpuut.android.ime.CatArrangement
import com.krysta1ll.meowpuut.android.ime.CatBodyPose
import com.krysta1ll.meowpuut.android.ime.CatMotion
import com.krysta1ll.meowpuut.android.ime.CatPose
import com.krysta1ll.meowpuut.android.ime.CatRest
import com.krysta1ll.meowpuut.android.ime.CatSchedule
import com.krysta1ll.meowpuut.android.ime.CatSeats
import com.krysta1ll.meowpuut.android.ime.CatSpriteData
import com.krysta1ll.meowpuut.android.ime.CandidateRegionLayout
import com.krysta1ll.meowpuut.android.ime.GlassShape
import com.krysta1ll.meowpuut.android.ime.Idiom
import com.krysta1ll.meowpuut.android.ime.PetMoments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The cats' moments (docs/PETS.md A1-A6): their motions, their poses by time of day, and the promise about the key path. */
class PetMomentsTest {
    private fun motion(cat: Cat, event: CatMotion.Event, fade: Boolean = false, quick: Boolean = false, waits: Boolean = false, start: Long = 1000L) =
        CatMotion(cat, start, 1f, event, fade, quick, waits)

    @Test
    fun theSettingDefaultsToFullAndKnowsItsKeys() {
        assertEquals(PetMoments.FULL, PetMoments.of(null))
        assertEquals(PetMoments.FULL, PetMoments.of("nonsense"))
        assertEquals(PetMoments.QUIET, PetMoments.of("quiet"))
        assertEquals(PetMoments.OFF, PetMoments.of("off"))
        assertEquals(listOf(0, 1, 2), PetMoments.entries.map { it.mode })
    }

    @Test
    fun aMomentNeverRisesAboveTheRestingHeight() {
        val events = listOf(
            CatMotion.Event.HELLO, CatMotion.Event.HOLD, CatMotion.Event.SINK, CatMotion.Event.SURFACE,
            CatMotion.Event.FOUND, CatMotion.Event.DUET, CatMotion.Event.HOP,
        )
        for (cat in Cat.entries) for (event in events) for (fade in listOf(false, true)) {
            val motion = motion(cat, event, fade)
            var now = 1000L
            while (now < 6000L) {
                val pose = motion.pose(now) ?: break
                assertTrue("$cat $event $fade at $now: tall ${pose.tall}", pose.tall <= 1f)
                assertTrue(pose.drop in 0f..1f)
                assertTrue(pose.alpha in 0f..1f)
                assertTrue(pose.bodyAlpha in 0f..1f)
                if (fade) {
                    // Reduce Motion: the pose only cross-fades.
                    assertEquals(1f, pose.tall, 0f)
                    assertEquals(0f, pose.tilt, 0f)
                    assertEquals(0f, pose.drop, 0f)
                }
                now += 10L
            }
        }
    }

    @Test
    fun helloIsNineHundredMillisecondsAndMimimoWaitsABeat() {
        val momo = motion(Cat.MOMO, CatMotion.Event.HELLO)
        assertEquals(CatBodyPose.GREET, momo.pose(1400L)!!.body)
        assertNotNull(momo.pose(1890L))
        assertNull(momo.pose(1901L))
        val mimimo = motion(Cat.MIMIMO, CatMotion.Event.HELLO, waits = true, start = 1150L)
        val waiting = mimimo.pose(1000L)!!
        assertNull(waiting.body)
        assertEquals(1f, waiting.tall, 0f)
        assertEquals(CatBodyPose.GREET, mimimo.pose(1500L)!!.body)
        // Without waiting, a motion that has not started is not drawn.
        assertNull(motion(Cat.MIMIMO, CatMotion.Event.HELLO, start = 1150L).pose(1000L))
    }

    @Test
    fun aPurrBreathesEvery2600MillisecondsAndFadesOutAfterTheFingerLeaves() {
        val mimimo = motion(Cat.MIMIMO, CatMotion.Event.HOLD)
        val momo = motion(Cat.MOMO, CatMotion.Event.HOLD)
        assertEquals(CatBodyPose.SLEEP, mimimo.pose(1500L)!!.body)
        assertEquals(CatBodyPose.HAPPY, momo.pose(1500L)!!.body)
        // Lowest and highest of the breath, 1.3 s apart; the same again after a whole period.
        assertEquals(1f, mimimo.pose(1000L + 0L)!!.tall, 0.0001f)
        assertTrue(mimimo.pose(1000L + 1300L)!!.tall < 0.98f)
        assertEquals(mimimo.pose(1000L + 2600L)!!.tall, 1f, 0.0001f)
        // Four seconds at most.
        assertNotNull(mimimo.pose(1000L + 3990L))
        assertNull(mimimo.pose(1000L + 4010L))
        val held = motion(Cat.MIMIMO, CatMotion.Event.HOLD)
        held.release(2000L)
        assertNotNull(held.pose(2100L))
        assertNull(held.pose(2200L))
        // Reduce Motion: pose only, no breathing.
        assertEquals(1f, motion(Cat.MIMIMO, CatMotion.Event.HOLD, fade = true).pose(2300L)!!.tall, 0f)
    }

    @Test
    fun aSunkCatStaysOutOfSightUntilItSurfaces() {
        val sink = motion(Cat.MOMO, CatMotion.Event.SINK)
        assertEquals(0f, sink.pose(1000L)!!.drop, 0f)
        val half = sink.pose(1140L)!!.drop
        assertTrue(half > 0.3f && half < 0.7f)
        assertEquals(1f, sink.pose(1280L)!!.drop, 0f)
        assertEquals(1f, sink.pose(20_000L)!!.drop, 0f)
        assertFalse(sink.settled(1100L))
        assertTrue(sink.settled(1300L))
        val surface = motion(Cat.MOMO, CatMotion.Event.SURFACE)
        assertEquals(1f, surface.pose(1000L)!!.drop, 0f)
        assertEquals(0f, surface.pose(1280L)!!.drop, 0f)
        assertNull(surface.pose(1281L))
        // Typing: out within 0.18 s.
        assertNull(motion(Cat.MOMO, CatMotion.Event.SURFACE, quick = true).pose(1181L))
        // Fade version: no drop, 0.25 s.
        val fade = motion(Cat.MOMO, CatMotion.Event.SINK, fade = true)
        assertEquals(0f, fade.pose(1100L)!!.drop, 0f)
        assertEquals(0f, fade.pose(1250L)!!.alpha, 0f)
        assertEquals(0f, fade.pose(9000L)!!.alpha, 0f)
        assertTrue(fade.settled(1300L))
    }

    @Test
    fun foundPopsUpHappyForThreeHundredFiftyMilliseconds() {
        val found = motion(Cat.MIMIMO, CatMotion.Event.FOUND)
        assertEquals(1f, found.pose(1000L)!!.drop, 0f)
        assertEquals(0f, found.pose(1180L)!!.drop, 0f)
        assertEquals(CatBodyPose.HAPPY, found.pose(1300L)!!.body)
        assertNull(found.pose(1351L))
    }

    @Test
    fun aMomentGivesWayWithin150MillisecondsOfTyping() {
        val duet = motion(Cat.MOMO, CatMotion.Event.DUET)
        assertEquals(CatBodyPose.JUMP, duet.pose(1100L)!!.body)
        assertEquals(CatBodyPose.HAPPY, motion(Cat.MIMIMO, CatMotion.Event.DUET).pose(1100L)!!.body)
        duet.cut(1200L)
        assertTrue(duet.pose(1275L)!!.bodyAlpha < 0.6f)
        assertNull(duet.pose(1351L))
        // A sunk cat is not cut; it is surfaced instead.
        val sink = motion(Cat.MOMO, CatMotion.Event.SINK)
        sink.cut(1500L)
        assertNotNull(sink.pose(5000L))
    }

    @Test
    fun everydayMotionsStayAsTheyWere() {
        // The first version's reactions are unchanged: the same lengths and no moment fields.
        val pet = motion(Cat.MIMIMO, CatMotion.Event.PET)
        assertNotNull(pet.pose(1500L))
        assertNull(pet.pose(1530L))
        val pose = pet.pose(1100L)!!
        assertEquals(0f, pose.drop, 0f)
        assertEquals(1f, pose.alpha, 0f)
        assertFalse(pet.isMoment)
        assertTrue(motion(Cat.MOMO, CatMotion.Event.HOLD).isMoment)
    }

    @Test
    fun theTimeOfDaySeatsTheCatsAtTheirOwnEnds() {
        // rest_pose answers: 1 edge_rest, 2 edge_peek, 3 edge_lounge, 4 sleep.
        assertEquals(CatArrangement.REST, CatRest.of(1, 1)!!.arrangement)
        assertEquals(CatArrangement.ENDS, CatRest.of(2, 2)!!.arrangement)
        assertEquals(CatArrangement.LOUNGE, CatRest.of(3, 3)!!.arrangement)
        val lateNight = CatRest.of(4, 3)!!
        assertEquals(CatArrangement.MOMO_LOUNGE, lateNight.arrangement)
        assertTrue(lateNight.mimimoAsleep)
        assertFalse(CatRest.of(3, 3)!!.mimimoAsleep)
        // Keep (0), or poses with no arrangement of their own, change nothing.
        assertNull(CatRest.of(0, 0))
        assertNull(CatRest.of(1, 3))
        for (rest in listOf(CatRest.of(1, 1), CatRest.of(2, 2), CatRest.of(3, 3), CatRest.of(4, 3))) assertNull(rest!!.arrangement.together)
    }

    @Test
    fun theScheduleSeatsTheRestingArrangementOnAppearanceThenGoesOnRotating() {
        val region = CandidateRegionLayout(393f, Idiom.PHONE)
        val stage = region.stage(GlassShape.CAPSULE)
        fun sprite(cat: Cat, pose: CatPose) = pose.sprite(cat)
        val schedule = CatSchedule()
        schedule.newPresentation(CatArrangement.REST)
        val rest = schedule.seats(stage, ::sprite)!!
        assertEquals("mimimo_edge_rest", rest.first { it.cat == Cat.MIMIMO }.sprite.name)
        assertEquals("momo_edge_rest", rest.first { it.cat == Cat.MOMO }.sprite.name)
        // Every presentation without a request keeps the old rotation: the arrangement changes.
        schedule.newPresentation()
        val next = schedule.seats(stage, ::sprite)!!
        assertTrue(next.any { place -> rest.first { it.cat == place.cat }.sprite.name != place.sprite.name ||
            rest.first { it.cat == place.cat }.anchorX != place.anchorX })
        // The resting poses are the ones the glass seats.
        assertNotNull(CatSeats.place(stage, CatArrangement.LOUNGE, CatSpriteData.all.getValue("mimimo_edge_lounge"), CatSpriteData.all.getValue("momo_edge_lounge")))
    }

    /**
     * The key path (docs/PETS.md "Acceptance"): the key handlers themselves only increment a counter. Indirectly, a
     * key can still end a composition or commit text, and then the commit/composition callbacks reach the pets
     * (one core call or one timer re-arm per commit or per composition change, never per key press).
     */
    @Test
    fun theKeyPathOnlyIncrementsACounter() {
        val source = File("src/main/java/com/krysta1ll/meowpuut/android/ime/MeowpuutIME.kt").readText()
        val forbidden = listOf(
            "CatMoments", "moments.", "NativePets", "mp_pets_", "LocalCatAffinity", "catAffinity", "PetBook",
            "File(", "FileOutputStream", "RandomAccessFile", "SharedPreferences", ".flush(", "decide(", "idleDelay",
        )
        val keyPath = listOf(
            "override fun onKeyDown(key: Key, fraction: Float)",
            "override fun onKeyDown(keyCode: Int, event: KeyEvent)",
            "override fun onKey(key: Key, text: String?)",
            "override fun onDelete(word: Boolean)",
            "private fun typeEngine(",
            "private fun typeSymbol(",
        )
        for (signature in keyPath) {
            val body = functionBody(source, signature)
            for (word in forbidden) {
                // The only pets line a key handler may hold is the counter.
                val text = body.replace("moments.pulse.keys++", "")
                assertFalse("$signature mentions $word", text.contains(word))
            }
        }
        for (signature in listOf("override fun onKeyDown(key: Key, fraction: Float)", "override fun onKeyDown(keyCode: Int, event: KeyEvent)")) {
            assertTrue("$signature counts the key", functionBody(source, signature).contains("moments.pulse.keys++"))
        }
        // The key views and touches do not know the pets at all.
        val dir = File("src/main/java/com/krysta1ll/meowpuut/android/ime")
        for (name in listOf("KeysView.kt", "KeyTouches.kt", "Keys.kt", "KeyStates.kt", "KeyboardView.kt")) {
            val text = File(dir, name).readText()
            for (word in listOf("CatMoments", "NativePets", "LocalCatAffinity", "PetPulse")) {
                assertFalse("$name mentions $word", text.contains(word))
            }
        }
        // The pulse itself is one field and nothing else.
        val moments = File(dir, "CatMoments.kt").readText()
        val pulse = moments.substringAfter("internal class PetPulse {").substringBefore("\n}")
        assertEquals("\n    @JvmField var keys = 0", pulse)
    }

    /** No per-frame allocation in the cats' motions: frames reuse one Pose and the draw path creates no objects. */
    @Test
    fun theCatMotionFrameCreatesNoObjects() {
        val source = File("src/main/java/com/krysta1ll/meowpuut/android/ime/Cats.kt").readText()
        val moment = functionBody(source, "private fun momentPose(")
        for (word in listOf("Pose()", "CatMotion(", "floatArrayOf", "listOf(", "arrayOf(", "mutableListOf", "ArrayList")) {
            assertFalse("momentPose creates $word", moment.contains(word))
        }
        val bar = File("src/main/java/com/krysta1ll/meowpuut/android/ime/CandidateBarView.kt").readText()
        for (signature in listOf("private fun drawRipple(", "private fun drawSeatedCat(", "private fun drawCat(")) {
            val body = functionBody(bar, signature)
            for (word in listOf("Paint()", "Path()", "RectF(", "Rect(", "CatMotion(", "arrayOf(", "listOf(", "mutableListOf", "ArrayList", "Bitmap.create")) {
                assertFalse("$signature creates $word", body.contains(word))
            }
        }
    }

    /** No disk work on the main thread while typing: the file is read and written only inside the io executor (or in release()). */
    @Test
    fun thePetBookIsOnlyTouchedOffTheMainThread() {
        val text = File("src/main/java/com/krysta1ll/meowpuut/android/ime/LocalCatAffinity.kt").readText()
        val body = text.substringAfter("internal class LocalCatAffinity")
        for (signature in listOf("fun decide(", "fun resolve(", "fun idleDelayMs(", "fun restPose(", "private fun snapshot(", "fun flush(", "fun clear(")) {
            val fn = functionBody(body, signature)
            for (word in listOf("readFile(", "writeMerged(", "RandomAccessFile", "Files.move", "readBytes", "writeBytes", "lock(")) {
                assertFalse("$signature touches the disk with $word", fn.substringBefore("io.execute").contains(word))
            }
        }
        assertTrue(functionBody(body, "fun flush(").contains("io.execute"))
        assertTrue(body.substringAfter("init {").substringBefore("private fun opened").contains("io.execute"))
    }

    private fun functionBody(source: String, signature: String): String {
        val at = source.indexOf(signature)
        assertTrue("missing $signature", at >= 0)
        val open = source.indexOf('{', source.indexOf(')', at))
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
            i++
        }
        error("unbalanced $signature")
    }
}
