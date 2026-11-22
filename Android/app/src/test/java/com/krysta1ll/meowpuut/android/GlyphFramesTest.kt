package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.ime.glyph.GlyphFrames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The render thread never draws into a picture the screen may still be reading. It used to keep off the picture on show only while
 * the view was drawing it, and with keys coming fast the keyboard flashed bare ground or half a picture.
 */
class GlyphFramesTest {
    @Test
    fun thereIsAlwaysAPictureToDrawIntoThatTheScreenDoesNotHold() {
        for (published in -1..3) for (showing in -1..3) for (before in -1..3) {
            val free = GlyphFrames.free(4, published, showing, before)
            assertTrue("$published $showing $before -> $free", free in 0..3 && free != published && free != showing && free != before)
        }
    }

    @Test
    fun whateverTheScreenShowsOrShowedIsLeftAloneThroughARunOfPictures() {
        // The render thread finishes pictures faster than the view draws them: the view takes every third one.
        var published = -1
        var showing = -1
        var before = -1
        val drawnInto = ArrayList<Int>()
        repeat(60) { n ->
            val i = GlyphFrames.free(4, published, showing, before)
            assertTrue("picture $i is on the screen", i != showing && i != before)
            drawnInto += i
            published = i
            if (n % 3 == 2 && published != showing) { before = showing; showing = published }
        }
        assertEquals("the pictures are used in turn, none left idle", setOf(0, 1, 2, 3), drawnInto.toSet())
    }

    @Test(expected = IllegalStateException::class)
    fun threePicturesAreNotEnough() {
        GlyphFrames.free(3, 0, 1, 2)
    }
}
