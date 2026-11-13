package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.ime.Box
import com.krysta1ll.meowpuut.android.ime.CandidateRegionLayout
import com.krysta1ll.meowpuut.android.ime.Cat
import com.krysta1ll.meowpuut.android.ime.CatAffinityCounters
import com.krysta1ll.meowpuut.android.ime.CatArrangement
import com.krysta1ll.meowpuut.android.ime.CatBodyPose
import com.krysta1ll.meowpuut.android.ime.CatMotion
import com.krysta1ll.meowpuut.android.ime.CatPlacement
import com.krysta1ll.meowpuut.android.ime.CatPose
import com.krysta1ll.meowpuut.android.ime.CatSchedule
import com.krysta1ll.meowpuut.android.ime.CatSeats
import com.krysta1ll.meowpuut.android.ime.CatSpriteData
import com.krysta1ll.meowpuut.android.ime.CatStage
import com.krysta1ll.meowpuut.android.ime.Festivals
import com.krysta1ll.meowpuut.android.ime.GlassOutline
import com.krysta1ll.meowpuut.android.ime.GlassShape
import com.krysta1ll.meowpuut.android.ime.Idiom
import com.krysta1ll.meowpuut.android.ime.KeyMetrics
import com.krysta1ll.meowpuut.android.ime.PathSink
import com.krysta1ll.meowpuut.android.ime.TextUnits
import com.krysta1ll.meowpuut.android.ime.catLiftProgress
import com.krysta1ll.meowpuut.android.ime.catLiftRise
import com.krysta1ll.meowpuut.android.ime.classicSprite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.sin

/** 米米莫 and 莫莫 on the glass's top edge, on every bar the keyboard lays out. */
class CatTest {
    private class Bar(val name: String, val region: CandidateRegionLayout, val stage: CatStage, val places: List<CatPlacement>?)

    private val bars: List<Bar> = buildList {
        val widths = (320..440).map { Idiom.PHONE to it.toFloat() } +
            listOf(568f, 640f, 667f, 736f, 812f, 852f, 896f, 932f).map { Idiom.PHONE_LANDSCAPE to it } +
            listOf(600f, 744f, 768f, 820f, 834f, 1024f, 1180f, 1366f).map { Idiom.TABLET to it }
        for ((idiom, width) in widths) {
            for (shape in GlassShape.entries) {
                val region = CandidateRegionLayout(width, idiom, rim = GlassOutline.contentBand(shape, 1f).first)
                val stage = region.stage(shape)
                val places = CatSeats.ends(stage, CatSpriteData.all.getValue("mimimo_edge_peek"), CatSpriteData.all.getValue("momo_edge_peek"))
                add(Bar("$idiom $width $shape", region, stage, places))
            }
        }
    }

    @Test
    fun liftEasesUpAndMatchesThePawsToTheSafeSeam() {
        assertEquals(0f, catLiftProgress(-0.1f), 0f)
        assertTrue(catLiftProgress(0.25f) in 0f..0.25f)
        assertEquals(1f, catLiftProgress(1.1f), 0f)
        assertEquals(7f, catLiftRise(16f, 42f, 86f, 1f), 0.001f)
        assertEquals(2f, catLiftRise(3f, 42f, 86f, 1f), 0.001f)
        assertEquals(3.5f, catLiftRise(16f, 77f, 86f, 1f), 0.001f)
        assertEquals(0f, catLiftRise(16f, 86f, 86f, 1f), 0f)
        assertEquals(0f, catLiftRise(16f, 87f, 86f, 1f), 0f)
    }

    @Test
    fun affinityCountsCommittedGraphemesAndEachCatsContacts() {
        assertEquals(5, TextUnits.graphemeCount("中e\u0301👩‍💻🇭🇰\r\n"))
        assertEquals(3, TextUnits.graphemeCount("cat"))
        assertEquals(0, TextUnits.graphemeCount(""))
        val counts = CatAffinityCounters(199)
        counts.addGraphemes(1)
        counts.pet(Cat.MIMIMO)
        counts.pet(Cat.MIMIMO)
        counts.fall(Cat.MIMIMO)
        counts.pet(Cat.MOMO)
        assertEquals(2, counts.score(Cat.MIMIMO))
        assertEquals(1, counts.score(Cat.MOMO))
        assertEquals(2L, counts.pets(Cat.MIMIMO))
        assertEquals(1L, counts.falls(Cat.MIMIMO))
        assertEquals(0L, counts.falls(Cat.MOMO))
    }

    @Test
    fun affinityClampsRestoredCountersAndSaturatesAtTheMaximum() {
        val counts = CatAffinityCounters(
            Long.MAX_VALUE,
            { if (it == Cat.MIMIMO) Long.MAX_VALUE else -1L },
            { Long.MAX_VALUE },
        )
        counts.addGraphemes(1)
        counts.pet(Cat.MIMIMO)
        counts.fall(Cat.MIMIMO)
        assertEquals(Long.MAX_VALUE, counts.graphemes)
        assertEquals(Long.MAX_VALUE, counts.pets(Cat.MIMIMO))
        assertEquals(Long.MAX_VALUE, counts.falls(Cat.MIMIMO))
        assertEquals(0L, counts.pets(Cat.MOMO))
        assertEquals(100, counts.score(Cat.MIMIMO))
    }

    @Test
    fun everydayCatMotionsSettleWithoutALoop() {
        for (cat in Cat.entries) for (event in listOf(CatMotion.Event.WAKE, CatMotion.Event.LOOK, CatMotion.Event.COMMIT)) {
            val motion = CatMotion(cat, 100L, event = event)
            assertEquals(1f, checkNotNull(motion.pose(100L)).tall, 0.001f)
            val active = checkNotNull(motion.pose(220L))
            assertTrue(active.tall in 0.9f..1f)
            assertTrue(kotlin.math.abs(active.tilt) in 0.1f..2.4f)
            assertNull(motion.pose(500L))
        }
    }

    @Test
    fun availableBodyArtFollowsWakePetAndCelebration() {
        val wake = CatMotion(Cat.MIMIMO, 100L, event = CatMotion.Event.WAKE)
        assertEquals(0f, checkNotNull(wake.pose(100L)).bodyAlpha, 0f)
        assertEquals(CatBodyPose.IDLE, wake.pose(200L)?.body)
        val pet = CatMotion(Cat.MIMIMO, 100L)
        assertEquals(CatBodyPose.GREET, pet.pose(200L)?.body)
        assertEquals(CatBodyPose.HAPPY, pet.pose(280L)?.body)
        val celebrate = CatMotion(Cat.MOMO, 100L, event = CatMotion.Event.CELEBRATE)
        assertEquals(CatBodyPose.JUMP, celebrate.pose(200L)?.body)
        assertEquals(CatBodyPose.HAPPY, celebrate.pose(350L)?.body)
        assertNull(celebrate.pose(700L))
    }

    @Test
    fun catsCanLandAboveTheFirstKeyRowAndFallBelowIt() {
        for (bar in bars) for (place in checkNotNull(bar.places)) {
            val glass = bar.stage.glass
            val firstKeyTop = bar.region.height + KeyMetrics.of(bar.region.idiom, bar.region.width, 4).topPadding
            fun supported(y: Float) = place.supportedBy(firstKeyTop, y - place.anchorY)
            assertTrue(supported(place.anchorY))
            assertTrue(supported(glass.top - 21f))
            assertTrue(supported(glass.bottom))
            assertTrue(supported(firstKeyTop - 0.1f))
            assertTrue(supported(firstKeyTop))
            assertFalse(supported(firstKeyTop + 0.1f))
            assertFalse(supported(firstKeyTop + 30f))
        }
    }

    @Test
    fun fittingArrangementsKeepBothCatsTouchable() {
        for (bar in bars) {
            val fitting = CatArrangement.entries.mapNotNull { arrangement ->
                CatSeats.place(bar.stage, arrangement, arrangement.mimimo.sprite(Cat.MIMIMO), arrangement.momo.sprite(Cat.MOMO))
            }
            assertTrue("multiple arrangements on ${bar.name}", fitting.size >= 2)
            for (pair in fitting) {
                val fittedStage = checkNotNull(bar.stage.fitting(pair))
                val (first, second) = pair.map { it.touch(bar.stage) }
                assertFalse("cat targets intersect on ${bar.name}", first.intersects(second))
                for (place in pair) {
                    val hit = fittedStage.petTarget(place)
                    assertTrue("reachable face on ${bar.name}", hit.contains(place.visible.centerX, (place.visible.top + place.anchorY) / 2))
                    assertFalse("target stays outside text on ${bar.name}", hit.intersects(fittedStage.row))
                    assertFalse("target stays outside expand control on ${bar.name}", hit.intersects(fittedStage.arrowTouch))
                    assertTrue("visible cat inside ${bar.name}", place.visible.left >= 0f && place.visible.right <= bar.stage.width)
                    assertFalse("paws leave text and arrow clear on ${bar.name}", place.covers(fittedStage))
                }
            }
        }
    }

    @Test
    fun candidateRoomFollowsTheCatsInsteadOfReservingBothSides() {
        val stage = CandidateRegionLayout(393f, Idiom.PHONE).stage(GlassShape.CAPSULE)
        fun seats(arrangement: CatArrangement) = checkNotNull(CatSeats.place(
            stage, arrangement, arrangement.mimimo.sprite(Cat.MIMIMO), arrangement.momo.sprite(Cat.MOMO),
        ))
        val bare = checkNotNull(stage.fitting(emptyList()))
        assertEquals(stage.row, bare.row)
        assertEquals(stage.arrow, bare.arrow)

        val ends = checkNotNull(stage.fitting(seats(CatArrangement.ENDS)))
        assertEquals(stage.glass.left + 12f, ends.row.left, 0.001f)
        assertEquals(stage.glass.right - 6f, ends.arrowTouch.right, 0.001f)
        assertEquals(44f, ends.arrowTouch.width, 0f)
        assertTrue(ends.arrowTouch.height >= 44f)
        val left = checkNotNull(stage.fitting(seats(CatArrangement.LEFT)))
        val right = checkNotNull(stage.fitting(seats(CatArrangement.RIGHT)))
        assertTrue(right.row.left <= left.row.left)
        assertTrue(right.arrow.left <= left.arrow.left)

        val asymmetric = checkNotNull(stage.fitting(seats(CatArrangement.MIMIMO_LOUNGE)))
        assertTrue(asymmetric.row.width >= 90f)

        // The settings card is narrower than the keyboard. A shallow
        // overlap at the bottom of the right cat's hit area can share its
        // horizontal space with the arrow while the face is still tappable.
        val preview = CandidateRegionLayout(379.43f, Idiom.PHONE).stage(GlassShape.CAPSULE)
        val pair = checkNotNull(CatSeats.ends(preview, CatPose.PEEK.sprite(Cat.MIMIMO), CatPose.PEEK.sprite(Cat.MOMO)))
        val previewFit = checkNotNull(preview.fitting(pair))
        val petTarget = previewFit.petTarget(pair.last())
        assertEquals(preview.glass.left + 12f * preview.unit, previewFit.row.left, 0.001f)
        assertEquals(preview.glass.right - 6f * preview.unit, previewFit.arrowTouch.right, 0.001f)
        assertEquals(previewFit.arrowTouch.top, petTarget.bottom, 0.001f)
        assertTrue(petTarget.height >= pair.last().touch(preview).height - 8f)
    }

    @Test
    fun classicCloudKeepsTheCandidateWidthOfOtherSkins() {
        val shape = GlassShape.CLOUD
        val rim = GlassOutline.contentBand(shape, 1f).first
        for ((width, idiom) in listOf(260f to Idiom.PHONE, 568f to Idiom.PHONE_LANDSCAPE, 600f to Idiom.TABLET)) {
            val stage = CandidateRegionLayout(width, idiom, rim = rim).stage(shape)
            val classic = checkNotNull(CatSeats.ends(stage, Cat.MIMIMO.classicSprite(), Cat.MOMO.classicSprite()))
            val current = checkNotNull(CatSeats.ends(stage, CatPose.PEEK.sprite(Cat.MIMIMO), CatPose.PEEK.sprite(Cat.MOMO)))
            val fitted = checkNotNull(stage.fitting(classic))

            assertEquals(checkNotNull(stage.fitting(current)).row.width, fitted.row.width, 1f)
            assertEquals(44f, fitted.arrowTouch.width, 0f)
            for (cat in classic) {
                assertFalse(fitted.petTarget(cat).intersects(fitted.row))
                assertFalse(fitted.petTarget(cat).intersects(fitted.arrowTouch))
            }
        }
    }

    @Test
    fun aPawAtTheUpperPillEdgeDoesNotIndentTheFirstCandidate() {
        for (width in listOf(360f, 393f, 411f)) {
            for (shape in listOf(GlassShape.WAVE, GlassShape.CLOUD)) {
                val region = CandidateRegionLayout(width, Idiom.PHONE, rim = GlassOutline.contentBand(shape, 1f).first)
                val stage = region.stage(shape)
                val pair = checkNotNull(CatSeats.place(stage, CatArrangement.MIMIMO_LOUNGE,
                    CatPose.LOUNGE.sprite(Cat.MIMIMO), CatPose.PEEK.sprite(Cat.MOMO)))
                val fitted = checkNotNull(stage.fitting(pair))

                assertTrue(pair.first().paws.any { it.intersects(fitted.row) })
                assertEquals("$width dp $shape", region.glass.left + 12f * stage.unit, fitted.row.left, 0.001f)
                for (cat in pair) {
                    assertFalse(cat.covers(fitted))
                    assertFalse(fitted.petTarget(cat).intersects(fitted.row))
                    assertFalse(fitted.petTarget(cat).intersects(fitted.arrowTouch))
                }
            }
        }
    }

    @Test
    fun arrangementMovesOncePerPresentationAndSkipsMatchingFestivalPoses() {
        val stage = CandidateRegionLayout(393f, Idiom.PHONE).stage(GlassShape.CAPSULE)
        val schedule = CatSchedule()
        fun coordinates() = checkNotNull(schedule.seats(stage) { cat, _ -> CatPose.PEEK.sprite(cat) })
            .associate { it.cat to it.anchorX }
        schedule.newPresentation()
        val initial = coordinates()
        assertEquals(initial, coordinates())
        repeat(CatArrangement.entries.size) {
            val prior = coordinates()
            schedule.newPresentation()
            val new = coordinates()
            assertFalse("identical positions on adjacent presentations", prior == new)
            assertEquals(new, coordinates())
        }
    }

    @Test
    fun japaneseOutfitFitsWithOrdinaryMomo() {
        val themes = Festivals.parse(File("src/main/assets/festivals/festivals.json").readText()).themes
        val outfit = themes.single { it.id == "tanabata" }.mimimo
        assertTrue(File("src/main/assets/festivals/${outfit.image}.png").isFile)
        val dressedMimimo = checkNotNull(outfit.catSprite())
        val ordinaryMomo = CatSpriteData.all.getValue("momo_edge_peek")
        for (shape in GlassShape.entries) {
            val region = CandidateRegionLayout(393f, Idiom.PHONE, rim = GlassOutline.contentBand(shape, 1f).first)
            assertNotNull("Japanese outfit fits $shape", CatSeats.ends(region.stage(shape), dressedMimimo, ordinaryMomo))
        }
    }

    @Test
    fun bothCatsFitEveryBar() {
        for (bar in bars) {
            val places = bar.places
            assertNotNull("the cats fit ${bar.name}", places)
            assertEquals(listOf(Cat.MIMIMO, Cat.MOMO), places!!.map { it.cat })
            val (left, right) = places
            for (place in places) {
                val box = place.visible
                assertTrue("${place.cat} inside the candidate region of ${bar.name}: $box",
                    box.left >= 0f && box.top >= 0f && box.right <= bar.region.width && box.bottom <= bar.region.height)
                assertTrue("${place.cat} is large enough to read on ${bar.name}", place.scale >= CatSeats.SMALLEST && place.scale <= 1f)
                // Never stretched.
                assertEquals(place.sprite.width / place.sprite.height, place.frame.width / place.frame.height, 0.0001f)
            }
            // The same size for the two, and for the three shapes of one bar.
            assertEquals(bar.name, (bar.region.glass.top - 1.5f) / ((0.84f - 0.0885f) * 64f), left.scale, 0.0001f)
            assertEquals(bar.name, left.scale, right.scale, 0.0001f)
            // 米米莫 at the left end, 莫莫 at the right end, apart.
            assertTrue(bar.name, left.visible.right < right.visible.left)
            assertTrue(bar.name, left.anchorX < bar.region.glass.left + bar.region.glass.width * 0.4f)
            assertTrue(bar.name, right.anchorX > bar.region.glass.left + bar.region.glass.width * 0.6f)
        }
    }

    @Test
    fun pawsRestOnTheEdgeAndTheGlassHidesTheCut() {
        for (bar in bars) {
            val glass = bar.region.glass
            for (place in bar.places!!) {
                val sprite = place.sprite
                val from = place.frame.left + sprite.cutLeft * place.frame.width
                val to = place.frame.left + sprite.cutRight * place.frame.width
                var lowest = 0f
                val steps = ((to - from) * 4).toInt() + 1
                for (i in 0..steps) {
                    val x = from + (to - from) * i / steps
                    val edge = glass.top + GlassOutline.top(bar.stage.shape, x - glass.left, glass.width, glass.height)
                    // The body ends on the anchor row: the edge is never below it, so no gap shows.
                    assertTrue("${place.cat} on ${bar.name}: edge $edge under the cut ${place.anchorY} at $x", edge <= place.anchorY + 0.001f)
                    // And the edge hides little of the cat.
                    assertTrue("${place.cat} on ${bar.name}", place.anchorY - edge <= 4 * bar.stage.unit + 0.001f)
                    lowest = maxOf(lowest, edge)
                }
                assertEquals("${place.cat} sits on the edge of ${bar.name}", lowest, place.anchorY, 0.05f)
                assertEquals(place.frame.left + sprite.anchorX * place.frame.width, place.anchorX, 0.001f)
                assertEquals(place.frame.top + sprite.anchorY * place.frame.height, place.anchorY, 0.001f)
            }
        }
    }

    @Test
    fun pawsStayOffCandidateTextAndTheArrow() {
        for (bar in bars) {
            val region = bar.region
            val places = checkNotNull(bar.places)
            val fitted = checkNotNull(bar.stage.fitting(places))
            val candidates = fitted.candidateTextBand
            val arrow = fitted.arrow
            for (place in places) {
                assertEquals(2, place.sprite.paws.size)
                for ((x, halfWidth, depth) in place.sprite.paws) {
                    val paw = Box(place.frame.left + (x - halfWidth) * place.frame.width, place.anchorY,
                        2 * halfWidth * place.frame.width, depth * place.frame.height)
                    assertTrue("${place.cat}'s paw on the candidates of ${bar.name}: $paw, $candidates", !paw.intersects(candidates))
                    assertTrue("${place.cat}'s paw on the arrow of ${bar.name}: $paw, $arrow", !paw.intersects(arrow))
                    // A paw is on the glass, not beside it.
                    assertTrue(bar.name, paw.left > region.glass.left && paw.right < region.glass.right && paw.bottom < region.glass.bottom)
                }
            }
        }
    }

    @Test
    fun tapTargetsAreTheCatsAndNothingElse() {
        for (bar in bars) {
            val places = checkNotNull(bar.places)
            val fitted = checkNotNull(bar.stage.fitting(places))
            val (left, right) = places.map(fitted::petTarget)
            for ((place, touch) in places.zip(listOf(left, right))) {
                assertTrue("44 dp wide on ${bar.name}", touch.width >= 44f)
                assertEquals(0f, touch.top, 0f)
                assertTrue(touch.bottom <= place.anchorY && touch.bottom >= place.anchorY - 8f)
                assertTrue("inside the bar on ${bar.name}", touch.left >= 0f && touch.right <= bar.region.width && touch.bottom <= bar.region.height)
                assertTrue("covers the picture above the edge on ${bar.name}", touch.left <= place.visible.left && touch.right >= place.visible.right)
                // Above the candidates and the arrow: a tap on a cat picks neither.
                assertTrue(bar.name, !touch.intersects(fitted.row) && !touch.intersects(fitted.arrowTouch))
            }
            assertTrue("the two targets are apart on ${bar.name}", !left.intersects(right))
        }
    }

    @Test
    fun edgeIsTheOutlineThatIsDrawn() {
        for (shape in GlassShape.entries) {
            for (box in listOf(Box(15.6f, 34f, 371.4f, 46f), Box(13f, 25f, 833f, 36f), Box(18f, 38.7f, 795f, 53f))) {
                val points = ArrayList<Pair<Float, Float>>()
                GlassOutline.trace(shape, box, 0f, object : PathSink {
                    var arcs = 0
                    override fun moveTo(x: Float, y: Float) { points += x to y }
                    override fun lineTo(x: Float, y: Float) { if (arcs == 0) points += x to y }
                    override fun arc(cx: Float, cy: Float, radius: Float, startDegrees: Float, sweepDegrees: Float) {
                        arcs++
                        assertEquals(GlassOutline.endRadius(shape, box.height), radius, 0.001f)
                        // Points on the upper quarter of each round end.
                        for (degrees in listOf(10, 30, 45, 60, 80)) {
                            val angle = Math.toRadians((if (arcs == 1) -degrees else 180 + degrees).toDouble())
                            points += (cx + radius * cos(angle)).toFloat() to (cy + radius * sin(angle)).toFloat()
                        }
                    }
                    override fun close() = Unit
                })
                assertTrue(points.size > 10)
                for ((x, y) in points) {
                    assertEquals("$shape at $x", y - box.top, GlassOutline.top(shape, x - box.left, box.width, box.height), 0.01f)
                }
            }
        }
    }

    @Test
    fun everySpriteHasItsPicture() {
        assertEquals(8, CatSpriteData.all.size)
        for ((name, sprite) in CatSpriteData.all) {
            assertEquals(name, sprite.name)
            val file = File("src/main/res/drawable-nodpi/cat_$name.png")
            assertTrue("${file.path} is missing", file.isFile)
            // A PNG starts with its signature and the IHDR chunk: width and height at bytes 16 and 20.
            val header = ByteBuffer.wrap(file.inputStream().use { it.readNBytes(24) })
            assertEquals("$name is 3 px per dp wide", 3 * sprite.width.toInt(), header.getInt(16))
            assertEquals("$name is 3 px per dp high", 3 * sprite.height.toInt(), header.getInt(20))
        }
    }
}
