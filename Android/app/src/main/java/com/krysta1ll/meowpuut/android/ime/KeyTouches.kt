package com.krysta1ll.meowpuut.android.ime

// Which finger is on which key and what that types (docs/DESIGN.md §6), the
// same rules as iOS KeyboardKeysView.swift. Nothing here touches android.*:
// KeysView feeds it touches and timers, and the JVM unit tests drive it too.

/** What the keys ask the input method to do. */
interface KeyListener {
    /** Touch-down feedback (click and haptics) for any key. */
    fun onKeyDown(key: Key)
    /** A key is typed; [text] is a chosen alternate, if any. */
    fun onKey(key: Key, text: String?)
    fun onDelete(word: Boolean)
    fun onShiftDown()
    /** [typed]: a letter was typed while Shift was held. */
    fun onShiftUp(typed: Boolean)
    /** A page key went down, or the finger that pressed it lifted after typing a symbol. */
    fun onPage(page: Int)
    fun onCursorStart()
    fun onCursorMove(steps: Int)
    fun onCursorEnd()
    /** A held key changed what it does (alternates, cursor): a light tick. */
    fun onTick()
}

class KeyTouches(private val host: Host) {
    /** Timers and what is drawn over the keys. Coordinates are the keys view's, in dp. */
    interface Host {
        fun post(delayMs: Long, action: Runnable)
        fun cancel(action: Runnable)
        fun showBalloon(id: Int, key: Key, frame: Box)
        fun hideBalloon(id: Int)
        fun showAlternates(strip: AlternatesStrip, options: List<String>, selected: Int)
        fun selectAlternate(index: Int)
        fun hideAlternates()
        fun clearVisuals()
        fun redraw()
    }

    var listener: KeyListener? = null
    var keySet: KeySet? = null
        private set
    var placements: List<List<KeyPlacement>> = emptyList()
        private set
    private var width = 0f

    /** The space bar is moving the cursor: key labels fade. */
    var cursorMode = false
        private set

    /** What a finger is doing. SHIFT and PAGE may end on a character the finger slid to. */
    private enum class Mode { KEY, ALTERNATES, CURSOR, SHIFT, PAGE, DELETE, INERT }

    private class Pointer(val id: Int, var row: Int, var column: Int, var key: Key, var frame: Box, var x: Float) {
        var mode = Mode.KEY
        var committed = false
        /** Shift: a letter was typed while it was held. */
        var typed = false
        /** Shift or a page key: the finger is on the character key now in [key]. */
        var slid = false
        /** Page key: the page to go back to after a slide. */
        var originPage = 0
        var hold: Runnable? = null
        var options: List<String> = emptyList()
        var strip: AlternatesStrip? = null
        var selected = 0
        var cursorAnchor = 0f
        var cursorSteps = 0

        /** The key under this finger is drawn pressed. */
        val pressing
            get() = when (mode) {
                Mode.KEY -> !committed
                Mode.DELETE -> true
                Mode.SHIFT, Mode.PAGE -> slid
                else -> false
            }
    }

    private val pointers = LinkedHashMap<Int, Pointer>()
    private var deleteRepeat: Runnable? = null
    private var deleteCount = 0

    val active get() = pointers.isNotEmpty()

    /**
     * Installs the keys and where they are. [sameShape]: only labels changed
     * (Shift, titles), so fingers that are down keep their keys.
     */
    fun layout(set: KeySet, placements: List<List<KeyPlacement>>, width: Float, sameShape: Boolean) {
        keySet = set
        this.placements = placements
        this.width = width
        if (!sameShape) {
            // A held key from the old layout no longer matches what is drawn.
            // Shift and page keys stay down so the finger can slide to a character.
            for (p in pointers.values) {
                if (p.mode != Mode.SHIFT && p.mode != Mode.PAGE) {
                    retire(p)
                } else if (p.slid) {
                    host.hideBalloon(p.id)
                    p.slid = false
                }
            }
        } else {
            for (p in pointers.values) {
                if (!p.pressing) continue
                val current = keyOf(p.row, p.column) ?: continue
                if (current.action == p.key.action && current != p.key) {
                    p.key = current
                    showPressed(p)
                }
            }
        }
    }

    fun isPressed(row: Int, column: Int): Boolean {
        for (p in pointers.values) if (p.pressing && p.row == row && p.column == column) return true
        return false
    }

    private fun keyAt(x: Float, y: Float): Pair<Int, Int>? = KeyGrid.keyAt(x, y, placements)

    private fun keyOf(row: Int, column: Int): Key? = keySet?.rows?.getOrNull(row)?.getOrNull(column)

    private fun frameOf(row: Int, column: Int): Box? = placements.getOrNull(row)?.getOrNull(column)?.frame

    fun down(id: Int, x: Float, y: Float) {
        pointers.remove(id)?.let(::retire)
        // The key is read before earlier fingers are typed: typing one can
        // change the page, and this finger aimed at what was on screen.
        val (row, column) = keyAt(x, y) ?: return
        val key = keyOf(row, column) ?: return
        val frame = frameOf(row, column) ?: return
        // Rollover: a new finger types whatever the earlier fingers still hold.
        for (earlier in pointers.values.toList()) commitEarly(earlier)
        val p = Pointer(id, row, column, key, frame, x)
        pointers[id] = p
        begin(p)
        host.redraw()
    }

    private fun begin(p: Pointer) {
        val key = p.key
        if (!key.enabled) { p.mode = Mode.INERT; return }
        listener?.onKeyDown(key)
        when (val action = key.action) {
            is KeyAction.Engine, is KeyAction.Text -> {
                showPressed(p)
                scheduleAlternates(p)
            }
            KeyAction.Space -> scheduleHold(p, CURSOR_DELAY) { enterCursor(p) }
            KeyAction.Delete -> {
                p.mode = Mode.DELETE
                startDelete()
            }
            KeyAction.Shift -> {
                p.mode = Mode.SHIFT
                listener?.onShiftDown()
            }
            is KeyAction.Page -> {
                p.mode = Mode.PAGE
                p.originPage = keySet?.page ?: 0
                listener?.onPage(action.page)
            }
            KeyAction.Return, KeyAction.ToggleEnglish, KeyAction.Globe -> Unit
        }
    }

    /** Types a held character or space early because another finger came down. */
    private fun commitEarly(p: Pointer) {
        if (p.committed || p.mode != Mode.KEY || !p.key.rollsOver) return
        cancelHold(p)
        host.hideBalloon(p.id)
        commit(p, null)
        p.mode = Mode.INERT
    }

    fun move(id: Int, x: Float, y: Float) {
        val p = pointers[id] ?: return
        p.x = x
        when (p.mode) {
            Mode.KEY -> {
                // A finger may slide to another character key before lifting.
                if (p.committed || !p.key.isCharacter) return
                val (row, column) = keyAt(x, y) ?: return
                if (row == p.row && column == p.column) return
                val next = keyOf(row, column) ?: return
                if (!next.isCharacter || !next.enabled) return
                p.row = row; p.column = column; p.key = next
                p.frame = frameOf(row, column) ?: p.frame
                showPressed(p)
                scheduleAlternates(p)
                host.redraw()
            }
            Mode.ALTERNATES -> {
                val index = p.strip?.indexAt(x) ?: return
                if (index != p.selected) {
                    p.selected = index
                    host.selectAlternate(index)
                    listener?.onTick()
                }
            }
            Mode.CURSOR -> moveCursor(p)
            Mode.SHIFT, Mode.PAGE -> slide(p, x, y)
            Mode.DELETE, Mode.INERT -> Unit
        }
    }

    /** From Shift or a page key the finger may slide onto a character. */
    private fun slide(p: Pointer, x: Float, y: Float) {
        val hit = keyAt(x, y)
        val next = hit?.let { keyOf(it.first, it.second) }
        if (hit != null && next != null && next.isCharacter && next.enabled) {
            if (p.slid && hit.first == p.row && hit.second == p.column && next == p.key) return
            p.slid = true
            p.row = hit.first; p.column = hit.second; p.key = next
            p.frame = frameOf(p.row, p.column) ?: p.frame
            showPressed(p)
            host.redraw()
        } else if (p.slid) {
            host.hideBalloon(p.id)
            p.slid = false
            host.redraw()
        }
    }

    fun up(id: Int, x: Float, y: Float) {
        val p = pointers.remove(id) ?: return
        p.x = x
        cancelHold(p)
        host.hideBalloon(p.id)
        val mode = p.mode
        when (mode) {
            Mode.KEY -> if (!p.committed) {
                // Characters and space are typed wherever the finger lifts;
                // Return, the language key and the globe need it to stay on them.
                if (p.key.rollsOver || keyAt(x, y) == (p.row to p.column)) commit(p, null)
            }
            Mode.ALTERNATES -> {
                host.hideAlternates()
                if (!p.committed) commit(p, p.options.getOrNull(p.selected))
            }
            Mode.CURSOR -> {
                cursorMode = false
                listener?.onCursorEnd()
            }
            Mode.DELETE -> stopDelete()
            Mode.SHIFT -> {
                if (p.slid && !p.committed) commit(p, null)
                listener?.onShiftUp(p.typed)
            }
            Mode.PAGE -> if (p.slid && !p.committed) {
                commit(p, null)
                listener?.onPage(p.originPage)
            }
            Mode.INERT -> Unit
        }
        p.mode = Mode.INERT
        host.redraw()
    }

    private fun commit(p: Pointer, text: String?) {
        p.committed = true
        val key = p.key
        if (key.isCharacter) {
            if (p.mode == Mode.SHIFT) p.typed = true
            for (other in pointers.values) if (other.mode == Mode.SHIFT) other.typed = true
        }
        listener?.onKey(key, text)
    }

    /** Stops whatever a finger was doing, without typing anything. */
    private fun retire(p: Pointer) {
        cancelHold(p)
        host.hideBalloon(p.id)
        val mode = p.mode
        p.mode = Mode.INERT
        when (mode) {
            Mode.ALTERNATES -> host.hideAlternates()
            Mode.CURSOR -> {
                cursorMode = false
                listener?.onCursorEnd()
            }
            Mode.DELETE -> stopDelete()
            Mode.SHIFT -> listener?.onShiftUp(p.typed)
            else -> Unit
        }
    }

    /** Ends every touch without typing (the touch was taken away, or the view is going). */
    fun cancelAll() {
        for (p in pointers.values.toList()) retire(p)
        pointers.clear()
        stopDelete()
        host.clearVisuals()
        host.redraw()
    }

    private fun showPressed(p: Pointer) {
        if (p.key.preview && p.key.isCharacter) host.showBalloon(p.id, p.key, p.frame) else host.hideBalloon(p.id)
    }

    private fun scheduleHold(p: Pointer, delay: Long, action: () -> Unit) {
        cancelHold(p)
        val runnable = Runnable {
            p.hold = null
            if (pointers[p.id] === p && !p.committed && p.mode == Mode.KEY) action()
        }
        p.hold = runnable
        host.post(delay, runnable)
    }

    private fun cancelHold(p: Pointer) {
        p.hold?.let(host::cancel)
        p.hold = null
    }

    private fun scheduleAlternates(p: Pointer) {
        cancelHold(p)
        if (p.key.alternates.isNotEmpty()) scheduleHold(p, ALTERNATES_DELAY) { openAlternates(p) }
    }

    private fun openAlternates(p: Pointer) {
        val options = p.key.alternates
        if (options.isEmpty() || width <= 0f) return
        val strip = KeyBalloon.alternates(p.frame, options.size, width)
        host.hideBalloon(p.id)
        p.mode = Mode.ALTERNATES
        p.options = options
        p.strip = strip
        // The option over the key: the first one.
        p.selected = strip.indexAt(p.frame.centerX)
        host.showAlternates(strip, options, p.selected)
        listener?.onTick()
        host.redraw()
    }

    private fun enterCursor(p: Pointer) {
        p.mode = Mode.CURSOR
        // From where the finger is now, not where it came down.
        p.cursorAnchor = p.x
        p.cursorSteps = 0
        cursorMode = true
        listener?.onCursorStart()
        listener?.onTick()
        host.redraw()
    }

    private fun moveCursor(p: Pointer) {
        val steps = ((p.x - p.cursorAnchor) / CURSOR_STEP).toInt().coerceIn(-512, 512)
        if (steps != p.cursorSteps) {
            val delta = steps - p.cursorSteps
            p.cursorSteps = steps
            listener?.onCursorMove(delta)
        }
    }

    private fun startDelete() {
        stopDelete()
        deleteCount = 0
        val runnable = object : Runnable {
            override fun run() {
                deleteCount++
                when (DeleteRepeat.step(deleteCount)) {
                    DeleteRepeat.Step.CHARACTER -> listener?.onDelete(false)
                    DeleteRepeat.Step.WORD -> listener?.onDelete(true)
                    DeleteRepeat.Step.WAIT -> Unit
                }
                // Deleting can change the keys and end this repeat; then it must not come back.
                if (deleteRepeat === this) host.post(DeleteRepeat.INTERVAL_MS, this)
            }
        }
        // The repeat is in place before the first delete, which may already end it.
        deleteRepeat = runnable
        host.post(DeleteRepeat.INITIAL_DELAY_MS, runnable)
        listener?.onDelete(false)
    }

    private fun stopDelete() {
        deleteRepeat?.let(host::cancel)
        deleteRepeat = null
    }

    /** A TalkBack activation is a whole tap: press and release. */
    fun activate(row: Int, column: Int) {
        val key = keyOf(row, column) ?: return
        if (!key.enabled) return
        listener?.onKeyDown(key)
        when (val action = key.action) {
            KeyAction.Shift -> { listener?.onShiftDown(); listener?.onShiftUp(false) }
            KeyAction.Delete -> listener?.onDelete(false)
            is KeyAction.Page -> listener?.onPage(action.page)
            else -> listener?.onKey(key, null)
        }
    }

    companion object {
        const val ALTERNATES_DELAY = 450L
        const val CURSOR_DELAY = 400L
        const val CURSOR_STEP = 9f
    }
}
