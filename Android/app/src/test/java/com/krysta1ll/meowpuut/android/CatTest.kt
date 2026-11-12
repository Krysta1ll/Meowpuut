package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.ime.Box
import com.krysta1ll.meowpuut.android.ime.CandidateRegionLayout
import com.krysta1ll.meowpuut.android.ime.Cat
import com.krysta1ll.meowpuut.android.ime.CatPlacement
import com.krysta1ll.meowpuut.android.ime.CatSeats
import com.krysta1ll.meowpuut.android.ime.CatSpriteData
import com.krysta1ll.meowpuut.android.ime.CatStage
import com.krysta1ll.meowpuut.android.ime.GlassOutline
import com.krysta1ll.meowpuut.android.ime.GlassShape
import com.krysta1ll.meowpuut.android.ime.Idiom
import com.krysta1ll.meowpuut.android.ime.PathSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    fun pawsStayOffTheCandidatesAndTheArrow() {
        for (bar in bars) {
            val region = bar.region
            // What is drawn on the glass, worked out here from the layout.
            val band = GlassOutline.contentBand(bar.stage.shape, region.glass.height)
            val middle = region.glass.top + (band.first + band.second) / 2
            val candidates = Box(region.row.left, middle - region.pillHeight / 2, region.row.width, region.pillHeight)
            val arrow = Box(region.chevron.centerX - 6.5f, middle - 3.75f, 13f, 7.5f)
            for (place in bar.places!!) {
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
            val (left, right) = bar.places!!.map { it.touch(bar.stage) }
            for ((place, touch) in bar.places.zip(listOf(left, right))) {
                assertTrue("44 dp wide on ${bar.name}", touch.width >= 44f)
                assertEquals(0f, touch.top, 0f)
                assertEquals(place.anchorY, touch.bottom, 0.001f)
                assertTrue("inside the bar on ${bar.name}", touch.left >= 0f && touch.right <= bar.region.width && touch.bottom <= bar.region.height)
                assertTrue("covers the picture above the edge on ${bar.name}", touch.left <= place.visible.left && touch.right >= place.visible.right)
                // Above the candidates and the arrow: a tap on a cat picks neither.
                assertTrue(bar.name, !touch.intersects(bar.stage.row) && !touch.intersects(bar.stage.arrow))
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
