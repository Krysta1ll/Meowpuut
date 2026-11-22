package com.krysta1ll.meowpuut.android.ime.glyph

/** Which of the picture's bitmaps the render thread may draw into. */
internal object GlyphFrames {
    /**
     * The first of [size] pictures that is none of [published] (the last finished, which a view may take at any moment), [showing]
     * (the one on the screen) and [shownBefore] (the one the screen had before it). With four pictures there is always one.
     */
    fun free(size: Int, published: Int, showing: Int, shownBefore: Int): Int {
        for (i in 0 until size) if (i != published && i != showing && i != shownBefore) return i
        throw IllegalStateException("no free picture among $size")
    }
}
