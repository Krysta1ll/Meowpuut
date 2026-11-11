package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.engine.InputMode
import com.krysta1ll.meowpuut.android.ime.AlternatesStrip
import com.krysta1ll.meowpuut.android.ime.Box
import com.krysta1ll.meowpuut.android.ime.DeleteRepeat
import com.krysta1ll.meowpuut.android.ime.Idiom
import com.krysta1ll.meowpuut.android.ime.Key
import com.krysta1ll.meowpuut.android.ime.KeyAction
import com.krysta1ll.meowpuut.android.ime.KeyBuilder
import com.krysta1ll.meowpuut.android.ime.KeyContext
import com.krysta1ll.meowpuut.android.ime.KeyGrid
import com.krysta1ll.meowpuut.android.ime.KeyListener
import com.krysta1ll.meowpuut.android.ime.KeyMetrics
import com.krysta1ll.meowpuut.android.ime.KeyPlacement
import com.krysta1ll.meowpuut.android.ime.KeySet
import com.krysta1ll.meowpuut.android.ime.KeyTouches
import com.krysta1ll.meowpuut.android.ime.ShiftState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fingers on the keys, scripted: the same code path as real touches. */
class TouchTest {
    /** Timers on a clock the test moves, and a record of what is drawn over the keys. */
    private class FakeHost : KeyTouches.Host {
        var now = 0L
        private val timers = ArrayList<Pair<Long, Runnable>>()
        val balloons = HashMap<Int, String>()
        var strip: AlternatesStrip? = null
        var options: List<String> = emptyList()
        var selected = -1

        override fun post(delayMs: Long, action: Runnable) { timers += (now + delayMs) to action }
        override fun cancel(action: Runnable) { timers.removeAll { it.second === action } }
        override fun showBalloon(id: Int, key: Key, frame: Box) { balloons[id] = key.label }
        override fun hideBalloon(id: Int) { balloons.remove(id) }
        override fun showAlternates(strip: AlternatesStrip, options: List<String>, selected: Int) {
            this.strip = strip; this.options = options; this.selected = selected
        }
        override fun selectAlternate(index: Int) { selected = index }
        override fun hideAlternates() { strip = null }
        override fun clearVisuals() { balloons.clear(); strip = null }
        override fun redraw() = Unit

        fun advance(ms: Long) {
            val end = now + ms
            while (true) {
                val next = timers.filter { it.first <= end }.minByOrNull { it.first } ?: break
                timers.remove(next)
                now = next.first
                next.second.run()
            }
            now = end
        }

        val pendingTimers get() = timers.size
    }

    /** A keyboard that answers like the input method: pages, Shift labels, the return to letters. */
    private class Board(val english: Boolean = false, val mode: InputMode = InputMode.PINYIN) : KeyListener {
        val host = FakeHost()
        val touches = KeyTouches(host)
        val events = ArrayList<String>()
        val width = 393f
        var page = 0
        var shift = ShiftState.OFF
        /** Called on every delete; a test can change the keys from here. */
        var onDeleted: () -> Unit = {}
        lateinit var set: KeySet
        lateinit var rows: List<List<KeyPlacement>>

        init {
            touches.listener = this
            install(sameShape = false)
        }

        fun install(sameShape: Boolean, expanded: Boolean = false) {
            set = KeyBuilder.build(KeyContext(mode, english, page, shift, false, false, "空格", "換行", false, true, expanded))
            rows = KeyGrid.place(set.specs, width, set.referenceColumns, KeyMetrics.of(Idiom.PHONE, width, set.rows.size))
            touches.layout(set, rows, width, sameShape)
        }

        fun frame(match: (Key) -> Boolean): Box {
            for ((r, row) in set.rows.withIndex()) for ((c, key) in row.withIndex()) if (match(key)) return rows[r][c].frame
            throw AssertionError("no such key")
        }

        fun frame(label: String) = frame { it.isCharacter && it.label == label }
        fun frame(action: KeyAction) = frame { it.action == action }
        fun pageKey() = frame { it.action is KeyAction.Page }

        fun down(finger: Int, at: Box, dx: Float = 0f) = touches.down(finger, at.centerX + dx, at.centerY)
        fun move(finger: Int, to: Box, dx: Float = 0f) = touches.move(finger, to.centerX + dx, to.centerY)
        fun up(finger: Int, at: Box, dx: Float = 0f) = touches.up(finger, at.centerX + dx, at.centerY)
        fun tap(at: Box) { down(0, at); up(0, at) }

        val typed get() = events.filter { it.startsWith("key:") }.map { it.removePrefix("key:") }

        override fun onKeyDown(key: Key) = Unit

        override fun onKey(key: Key, text: String?) {
            val shown = text ?: key.label.ifEmpty { key.action.toString() }
            events += "key:" + if (key.action == KeyAction.Space) "space" else shown
            if (key.action is KeyAction.Text && page > 0 && shown in KeyBuilder.autoReturnMarks) {
                page = 0
                install(sameShape = false)
            }
        }

        override fun onDelete(word: Boolean) {
            events += if (word) "delete-word" else "delete"
            onDeleted()
        }

        override fun onShiftDown() {
            events += "shift-down"
            shift = ShiftState.ONCE
            install(sameShape = true)
        }

        override fun onShiftUp(typed: Boolean) {
            events += "shift-up:$typed"
            shift = ShiftState.OFF
            install(sameShape = true)
        }

        override fun onPage(page: Int) {
            events += "page:$page"
            this.page = page
            install(sameShape = false)
        }

        override fun onCursorStart() { events += "cursor-start" }
        override fun onCursorMove(steps: Int) { events += "cursor:$steps" }
        override fun onCursorEnd() { events += "cursor-end" }
        override fun onTick() = Unit
    }

    @Test
    fun aKeyIsTypedWhenTheFingerLifts() {
        val b = Board()
        val q = b.frame("q")
        b.down(0, q)
        assertEquals("the balloon is up with the touch", "q", b.host.balloons[0])
        assertTrue(b.typed.isEmpty())
        assertTrue(b.touches.isPressed(0, 0))
        b.up(0, q)
        assertEquals(listOf("q"), b.typed)
        assertTrue(b.host.balloons.isEmpty())
        assertTrue(!b.touches.active)
    }

    @Test
    fun aSecondFingerTypesTheFirstKeyAtOnce() {
        val b = Board()
        b.down(0, b.frame("n"))
        b.down(1, b.frame("i"))
        assertEquals("rollover", listOf("n"), b.typed)
        assertNull(b.host.balloons[0])
        assertEquals("i", b.host.balloons[1])
        b.up(0, b.frame("n"))
        assertEquals(listOf("n"), b.typed)
        b.up(1, b.frame("i"))
        assertEquals(listOf("n", "i"), b.typed)
    }

    @Test
    fun overlappingFingersLoseNoKeyAndKeepTheirOrder() {
        val b = Board()
        val word = "meowpuut"
        // Each finger lands before the one before it has lifted.
        for ((i, c) in word.withIndex()) {
            b.down(i, b.frame(c.toString()))
            if (i > 0) b.up(i - 1, b.frame(word[i - 1].toString()))
        }
        b.up(word.length - 1, b.frame("t"))
        assertEquals(word.map { it.toString() }, b.typed)
        assertTrue(!b.touches.active)
        assertEquals(0, b.host.pendingTimers)
    }

    @Test
    fun spaceRollsOverToo() {
        val b = Board()
        b.down(0, b.frame(KeyAction.Space))
        b.down(1, b.frame("a"))
        b.up(1, b.frame("a"))
        b.up(0, b.frame(KeyAction.Space))
        assertEquals(listOf("space", "a"), b.typed)
        // The cursor timer of the space bar went with it.
        b.host.advance(1000)
        assertTrue(b.events.none { it.startsWith("cursor") })
    }

    @Test
    fun slidingToAnotherLetterTypesTheLastOne() {
        val b = Board()
        b.down(0, b.frame("q"))
        b.move(0, b.frame("w"))
        assertEquals("w", b.host.balloons[0])
        b.move(0, b.frame("e"))
        b.up(0, b.frame("e"))
        assertEquals(listOf("e"), b.typed)
        // Sliding onto Shift or Return does not leave the letter.
        b.down(0, b.frame("z"))
        b.move(0, b.frame(KeyAction.Shift))
        b.up(0, b.frame(KeyAction.Shift))
        assertEquals(listOf("e", "z"), b.typed)
    }

    @Test
    fun returnNeedsTheFingerToStayOnIt() {
        val b = Board()
        val enter = b.frame(KeyAction.Return)
        b.down(0, enter)
        b.move(0, b.frame("m"))
        b.up(0, b.frame("m"))
        assertTrue(b.typed.isEmpty())
        b.tap(enter)
        assertEquals(1, b.typed.size)
        // A second finger does not type Return early.
        b.down(0, enter)
        b.down(1, b.frame("a"))
        assertEquals(1, b.typed.size)
        b.up(1, b.frame("a"))
        b.up(0, enter)
        assertEquals(3, b.typed.size)
    }

    @Test
    fun aQuickSpaceIsTypedEvenIfTheThumbDrifts() {
        val b = Board()
        val space = b.frame(KeyAction.Space)
        b.down(0, space)
        b.host.advance(120)
        b.move(0, space, dx = 40f)
        b.up(0, space, dx = 40f)
        assertEquals(listOf("space"), b.typed)
        assertTrue(b.events.none { it.startsWith("cursor") })
    }

    @Test
    fun aHeldSpaceMovesTheCursorAndTypesNothing() {
        val b = Board()
        val space = b.frame(KeyAction.Space)
        b.down(0, space)
        // The thumb drifts while it waits; the cursor starts from where it is then.
        b.move(0, space, dx = 6f)
        b.host.advance(KeyTouches.CURSOR_DELAY)
        assertTrue(b.touches.cursorMode)
        assertEquals(listOf("cursor-start"), b.events)
        b.move(0, space, dx = 6f + 28f)
        b.move(0, space, dx = 6f + 30f)
        b.move(0, space, dx = 6f - 9f)
        b.up(0, space, dx = 6f - 9f)
        assertEquals(listOf("cursor-start", "cursor:3", "cursor:-4", "cursor-end"), b.events)
        assertTrue(!b.touches.cursorMode)
    }

    @Test
    fun slidingFromShiftTypesOneCapital() {
        val b = Board(english = true)
        b.down(0, b.frame(KeyAction.Shift))
        assertEquals(listOf("shift-down"), b.events)
        // The letters turned to capitals under the finger; the keys kept their places.
        b.move(0, b.frame("A"))
        assertEquals("A", b.host.balloons[0])
        b.up(0, b.frame("A"))
        assertEquals(listOf("shift-down", "key:A", "shift-up:true"), b.events)
    }

    @Test
    fun shiftHeldWhileAnotherFingerTypes() {
        val b = Board(english = true)
        b.down(0, b.frame(KeyAction.Shift))
        b.tapWith(1, "A")
        b.tapWith(1, "B")
        b.up(0, b.frame(KeyAction.Shift))
        assertEquals(listOf("shift-down", "key:A", "key:B", "shift-up:true"), b.events)
        // A plain tap on Shift reports that nothing was typed.
        b.tap(b.frame(KeyAction.Shift))
        assertEquals(listOf("shift-down", "shift-up:false"), b.events.takeLast(2))
    }

    private fun Board.tapWith(finger: Int, label: String) {
        down(finger, frame(label))
        up(finger, frame(label))
    }

    @Test
    fun aPageKeyActsOnTouchDown() {
        val b = Board()
        val key = b.pageKey()
        b.down(0, key)
        assertEquals(listOf("page:1"), b.events)
        b.up(0, key)
        assertEquals(listOf("page:1"), b.events)
        assertEquals(1, b.set.page)
    }

    @Test
    fun slidingFromThePageKeyTypesASymbolAndComesBack() {
        val b = Board()
        val key = b.pageKey()
        b.down(0, key)
        // The symbols are up; the finger goes to "1" without lifting.
        b.move(0, b.frame("1"))
        assertEquals("1", b.host.balloons[0])
        b.up(0, b.frame("1"))
        assertEquals(listOf("page:1", "key:1", "page:0"), b.events)
        assertEquals(0, b.set.page)
        assertTrue(b.host.balloons.isEmpty())
    }

    @Test
    fun aMarkThatGoesBackToLettersKeepsTheNextFingersSymbol() {
        val b = Board()
        b.tap(b.pageKey())
        val comma = b.frame("，")
        val question = b.frame("？")
        b.down(0, comma)
        // The second finger aims at "？" while the symbols are still up.
        b.down(1, question)
        assertEquals(listOf("，"), b.typed)
        assertEquals("the page went back to letters", 0, b.set.page)
        b.up(0, comma)
        b.up(1, question)
        assertEquals(listOf("，", "？"), b.typed)
    }

    @Test
    fun heldDeleteRepeatsAndStopsWithTheFinger() {
        val b = Board()
        val delete = b.frame(KeyAction.Delete)
        b.down(0, delete)
        assertEquals(listOf("delete"), b.events)
        b.host.advance(DeleteRepeat.INITIAL_DELAY_MS - 1)
        assertEquals(1, b.events.size)
        b.host.advance(1)
        assertEquals(2, b.events.size)
        b.host.advance(1000)
        assertEquals("about twelve a second", 2 + 12, b.events.size)
        // Past 2.5 s it deletes words, one every third tick.
        b.host.advance(1500)
        assertTrue(b.events.contains("delete-word"))
        val words = b.events.count { it == "delete-word" }
        b.host.advance(DeleteRepeat.INTERVAL_MS * 3)
        assertEquals(words + 1, b.events.count { it == "delete-word" })
        b.up(0, delete)
        val total = b.events.size
        b.host.advance(5000)
        assertEquals(total, b.events.size)
        assertEquals(0, b.host.pendingTimers)
    }

    @Test
    fun deleteStopsWhenDeletingChangesTheKeys() {
        // With the candidate panel open the bottom row has its own delete key;
        // deleting the last letter closes the panel and that key goes away.
        val b = Board()
        b.install(sameShape = false, expanded = true)
        val delete = b.frame { it.action == KeyAction.Delete }
        var left = 3
        b.onDeleted = { if (--left == 0) b.install(sameShape = false, expanded = false) }
        b.down(0, delete)
        b.host.advance(10_000)
        assertEquals("nothing is deleted after the keys changed", 3, b.events.size)
        assertEquals(0, b.host.pendingTimers)
        b.up(0, delete)
        assertEquals(3, b.events.size)
        // The same when the very first delete changes them.
        left = 1
        b.install(sameShape = false, expanded = true)
        b.down(0, delete)
        b.host.advance(10_000)
        assertEquals(4, b.events.size)
        assertEquals(0, b.host.pendingTimers)
    }

    @Test
    fun aHeldLetterOffersItsAlternates() {
        val b = Board(english = true)
        val e = b.frame("e")
        b.down(0, e)
        b.host.advance(KeyTouches.ALTERNATES_DELAY)
        assertNotNull(b.host.strip)
        assertEquals(listOf("è", "é", "ê", "ë", "ē", "ė", "ę"), b.host.options)
        assertEquals("the first one sits over the key", 0, b.host.selected)
        assertNull(b.host.balloons[0])
        // One cell to the right is the second option.
        b.touches.move(0, e.centerX + b.host.strip!!.cell, e.centerY)
        assertEquals(1, b.host.selected)
        b.up(0, e, dx = b.host.strip!!.cell)
        assertEquals(listOf("é"), b.typed)
        assertNull(b.host.strip)

        // On the right half the strip grows to the left and the order is mirrored.
        val o = b.frame("o")
        b.down(0, o)
        b.host.advance(KeyTouches.ALTERNATES_DELAY)
        assertEquals(0, b.host.selected)
        assertTrue(b.host.strip!!.mirrored)
        b.touches.move(0, o.centerX - b.host.strip!!.cell, o.centerY)
        assertEquals(1, b.host.selected)
        b.touches.up(0, o.centerX - b.host.strip!!.cell, o.centerY)
        assertEquals(listOf("é", "ö"), b.typed)
    }

    @Test
    fun aQuickTapNeverOpensAlternates() {
        val b = Board(english = true)
        b.tap(b.frame("e"))
        b.host.advance(2000)
        assertNull(b.host.strip)
        assertEquals(listOf("e"), b.typed)
        // Chinese letters have none at all.
        val pinyin = Board()
        pinyin.down(0, pinyin.frame("e"))
        pinyin.host.advance(2000)
        assertNull(pinyin.host.strip)
        pinyin.up(0, pinyin.frame("e"))
        assertEquals(listOf("e"), pinyin.typed)
    }

    @Test
    fun aCancelledTouchTypesNothing() {
        val b = Board(english = true)
        b.down(0, b.frame(KeyAction.Shift))
        b.down(1, b.frame("A"))
        b.down(2, b.frame(KeyAction.Delete))
        val before = b.events.size
        b.touches.cancelAll()
        // Shift is let go; "A" was typed when the third finger landed, nothing after.
        assertEquals(listOf("shift-up:true"), b.events.drop(before))
        assertTrue(!b.touches.active)
        assertTrue(b.host.balloons.isEmpty())
        b.host.advance(5000)
        assertEquals(before + 1, b.events.size)
        assertEquals(0, b.host.pendingTimers)
    }

    @Test
    fun touchesInTheGapsStillFindAKey() {
        val b = Board()
        val q = b.frame("q")
        val a = b.frame("a")
        // Between the first two rows, nearer the second: "a".
        b.touches.down(0, a.centerX, (q.bottom + a.top) / 2 + 0.5f)
        b.touches.up(0, a.centerX, (q.bottom + a.top) / 2 + 0.5f)
        // Left of "a", where the second row is indented.
        b.touches.down(0, 1f, a.centerY)
        b.touches.up(0, 1f, a.centerY)
        assertEquals(listOf("a", "a"), b.typed)
    }
}
