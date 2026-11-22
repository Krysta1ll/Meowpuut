package com.krysta1ll.meowpuut.android.ime

import kotlin.math.abs

// Which finger is on which key and what that types (docs/DESIGN.md §6), the
// same rules as iOS KeyboardKeysView.swift. Nothing here touches android.*:
// KeysView feeds it touches and timers, and the JVM unit tests drive it too.

/** What the keys ask the input method to do. */
interface KeyListener {
    /** Touch-down feedback (click and haptics) for any key. */
    fun onKeyDown(key: Key, fraction: Float)
    /** A key is typed; [text] is a chosen alternate, if any. */
    fun onKey(key: Key, text: String?)
    fun onDelete(word: Boolean)
    /** Upward swipe from Delete clears composition or the whole field. */
    fun onDeleteAll() {}
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
    /** A key with a long-press action was held (Return while composing Zhuyin); the tap is then not typed. */
    fun onKeyHold(key: Key) {}
    /** The items of the strip a held [Key.holdsStrip] key offers; the first is where the finger starts. */
    fun stripEntries(key: Key): List<KeyStrip.Entry> = emptyList()
    /** The finger let go on item [index] (never the first) of the strip. */
    fun onStripChoice(key: Key, index: Int) {}
}

class KeyTouches(private val host: Host) {
    /** Timers and what is drawn over the keys. Coordinates are the keys view's, in dp. */
    interface Host {
        fun post(delayMs: Long, action: Runnable)
        fun cancel(action: Runnable)
        /** A virtual accessibility tap has no pointer to keep the key lit. */
        fun pulseKeyPress(row: Int, column: Int)
        fun showBalloon(id: Int, key: Key, frame: Box)
        fun hideBalloon(id: Int)
        fun showAlternates(strip: AlternatesStrip, options: List<String>, selected: Int)
        fun selectAlternate(index: Int)
        fun hideAlternates()
        fun clearVisuals()
        fun redraw()
        /** A key went down (or was activated): its centre, in the keys view's own dp. Skins that react to typing listen here. */
        fun keyDown(key: Key, centerX: Float, centerY: Float) {}
    }

    var listener: KeyListener? = null
    var keySet: KeySet? = null
        private set
    var placements: List<List<KeyPlacement>> = emptyList()
        private set
    val touchGrid = KeyTouchGridSoA()
    /** Dead-zone strip immediately above `KeysView` (below the candidate glass) routed to Row 0. */
    var topTouchExtension = 0f
    private var width = 0f
    private var shapeEpoch = 0

    /** The space bar is moving the cursor: key labels fade. */
    var cursorMode = false
        private set

    /** What a finger is doing. SHIFT and PAGE may end on a character the finger slid to. */
    private enum class Mode { KEY, ALTERNATES, CURSOR, SHIFT, PAGE, DELETE, INERT }

    private class Pointer {
        var id: Int = 0
        var index: Int = -1
        var row: Int = 0
        var column: Int = 0
        lateinit var key: Key
        var frame: Box = Box.ZERO
        var hitBox: Box = Box.ZERO
        var downX: Float = 0f
        var downY: Float = 0f
        var x: Float = 0f
        var y: Float = 0f
        var downTimeMs: Long = 0L
        var interDownMs: Long = -1L
        var canSwipeTone: Boolean = false
        var tone: KeyAction.PinyinTone? = null
        var deleteSwipeArmed: Boolean = false
        var mode: Mode = Mode.INERT
        var committed: Boolean = false
        /** Shift: a letter was typed while it was held. */
        var typed: Boolean = false
        /** Shift or a page key: the finger is on the character key now in [key]. */
        var slid: Boolean = false
        /** Page key: the page to go back to after a slide. */
        var originPage: Int = 0
        var hold: Runnable? = null
        var options: List<String> = emptyList()
        var strip: AlternatesStrip? = null
        var selected: Int = 0
        /** The held alternates are the language key's strip: letting go chooses an item, away from it nothing. */
        var menu: Boolean = false
        var cursorAnchor: Float = 0f
        var cursorSteps: Int = 0
        var generation: Int = 0

        fun reset(
            id: Int, index: Int, row: Int, column: Int, key: Key, frame: Box, hitBox: Box,
            downX: Float, downY: Float, downTimeMs: Long, interDownMs: Long,
        ) {
            this.id = id
            this.index = index
            this.row = row
            this.column = column
            this.key = key
            this.frame = frame
            this.hitBox = hitBox
            this.downX = downX
            this.downY = downY
            this.x = downX
            this.y = downY
            this.downTimeMs = downTimeMs
            this.interDownMs = interDownMs
            canSwipeTone = key.swipeUpTone != null
            tone = null
            deleteSwipeArmed = false
            mode = Mode.KEY
            committed = false
            typed = false
            slid = false
            originPage = 0
            hold = null
            options = emptyList()
            strip = null
            selected = 0
            menu = false
            cursorAnchor = 0f
            cursorSteps = 0
            generation++
        }

        /** The key under this finger is drawn pressed. */
        val pressing
            get() = when (mode) {
                Mode.KEY -> !committed
                Mode.DELETE -> true
                Mode.SHIFT -> slid || key.action == KeyAction.Shift
                Mode.PAGE -> slid
                else -> false
            }
    }

    // Fixed-size preallocated chronological pointer table (zero allocations in touch hot loop).
    private val slots = Array(MAX_POINTERS) { Pointer() }
    private var slotCount = 0
    private val workIds = IntArray(MAX_POINTERS)
    private var lastDownTimeMs = -1L
    private var deleteRepeat: Runnable? = null
    private var deleteCount = 0

    val active get() = slotCount > 0

    private fun slotIndexOf(id: Int): Int {
        for (i in 0 until slotCount) if (slots[i].id == id) return i
        return -1
    }

    private fun pointerById(id: Int): Pointer? {
        val i = slotIndexOf(id)
        return if (i >= 0) slots[i] else null
    }

    private fun removeSlotAt(pos: Int): Pointer {
        val removed = slots[pos]
        for (i in pos until slotCount - 1) slots[i] = slots[i + 1]
        slotCount--
        slots[slotCount] = removed
        return removed
    }

    /**
     * Installs the keys and where they are. [sameShape]: only labels changed
     * (Shift, titles), so fingers that are down keep their keys.
     */
    fun layout(set: KeySet, placements: List<List<KeyPlacement>>, width: Float, sameShape: Boolean) {
        keySet = set
        this.placements = placements
        this.width = width
        touchGrid.rebuild(placements, set)
        if (!sameShape) {
            shapeEpoch++
            // A held key from the old layout no longer matches what is drawn.
            // Shift and page keys stay down so the finger can slide to a character.
            for (i in 0 until slotCount) {
                val p = slots[i]
                if (p.mode != Mode.SHIFT && p.mode != Mode.PAGE) {
                    retire(p)
                } else if (p.slid) {
                    host.hideBalloon(p.id)
                    p.slid = false
                }
            }
        } else {
            for (i in 0 until slotCount) {
                val p = slots[i]
                if (!p.pressing) continue
                val current = keyOf(p.row, p.column) ?: continue
                if (current.action == p.key.action && current != p.key) {
                    p.key = current
                    showPressed(p)
                }
            }
        }
    }

    fun pressedColumns(row: Int): Int {
        var columns = 0
        for (i in 0 until slotCount) {
            val p = slots[i]
            if (p.pressing && p.row == row) columns = columns or (1 shl p.column)
        }
        return columns
    }

    private fun keyOf(row: Int, column: Int): Key? = keySet?.rows?.getOrNull(row)?.getOrNull(column)

    private fun frameOf(row: Int, column: Int): Box? = placements.getOrNull(row)?.getOrNull(column)?.frame

    private fun hitBoxOf(row: Int, column: Int): Box? = placements.getOrNull(row)?.getOrNull(column)?.hitFrame

    private fun withinReleaseSlop(p: Pointer, x: Float, y: Float): Boolean {
        val s = KeyTouchGridSoA.UTILITY_RELEASE_SLOP_DP
        val b = p.hitBox
        return x >= b.left - s && x <= b.right + s && y >= b.top - s && y <= b.bottom + s
    }

    fun down(id: Int, x: Float, y: Float, eventTimeMs: Long = 0L) {
        val existing = slotIndexOf(id)
        if (existing >= 0) retire(removeSlotAt(existing))
        // The key is read before earlier fingers are typed: typing one can
        // change the page, and this finger aimed at what was on screen.
        val hitIndex = touchGrid.resolveDown(x, y, topTouchExtension)
        if (hitIndex < 0) return
        val row = touchGrid.rowOf(hitIndex)
        val column = touchGrid.columnOf(hitIndex)
        val key = keyOf(row, column) ?: return
        val frame = frameOf(row, column) ?: return
        val hitBox = hitBoxOf(row, column) ?: frame
        val epochBefore = shapeEpoch
        // Rollover: a new finger types whatever the earlier fingers still hold (zero heap allocation).
        val countBefore = slotCount
        for (i in 0 until countBefore) workIds[i] = slots[i].id
        var rolled = false
        for (i in 0 until countBefore) {
            val earlier = pointerById(workIds[i]) ?: continue
            if (commitEarly(earlier)) rolled = true
        }
        val effectiveIndex = if (shapeEpoch == epochBefore) hitIndex else -1
        val interDown = if (eventTimeMs > 0L && lastDownTimeMs > 0L) eventTimeMs - lastDownTimeMs else -1L
        if (eventTimeMs > 0L) lastDownTimeMs = eventTimeMs
        if (slotCount >= MAX_POINTERS) retire(removeSlotAt(0))
        val p = slots[slotCount]
        p.reset(id, effectiveIndex, row, column, key, frame, hitBox, x, maxOf(0f, y), eventTimeMs, interDown)
        slotCount++
        begin(p, forceRedraw = rolled)
    }

    private fun begin(p: Pointer, forceRedraw: Boolean = false) {
        val key = p.key
        if (!key.enabled) {
            p.mode = Mode.INERT
            if (forceRedraw) host.redraw()
            return
        }
        if (key.isCharacter) showPressed(p)
        host.redraw()
        feedback(key, p.frame.centerX, p.frame.centerY)
        when (val action = key.action) {
            is KeyAction.Engine, is KeyAction.Text, KeyAction.Segment -> scheduleAlternates(p)
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
            KeyAction.Return -> if (key.holdsRaw) scheduleHold(p, ALTERNATES_DELAY) { fireHold(p) }
            KeyAction.ToggleEnglish -> if (key.holdsStrip) scheduleHold(p, ALTERNATES_DELAY) { openStrip(p) }
            is KeyAction.PinyinTone, KeyAction.Globe, KeyAction.Emoji, KeyAction.ClearComposition -> Unit
        }
    }

    /** Types a held character or space early because another finger came down. */
    private fun commitEarly(p: Pointer): Boolean {
        if (p.committed || p.mode != Mode.KEY || !p.key.rollsOver) return false
        cancelHold(p)
        host.hideBalloon(p.id)
        p.mode = Mode.INERT
        commit(p, null)
        return true
    }

    fun move(id: Int, x: Float, y: Float, eventTimeMs: Long = 0L) {
        val p = pointerById(id) ?: return
        p.x = x
        p.y = y
        when (p.mode) {
            Mode.KEY -> {
                if (p.key.holdsRaw && !withinReleaseSlop(p, x, y)) cancelHold(p)
                // A finger may slide to another character key before lifting.
                if (p.committed || p.tone != null || !p.key.isCharacter) return
                if (recognizeTone(p, x, y)) {
                    host.hideBalloon(p.id)
                    host.redraw()
                    return
                }
                val fastTyping = eventTimeMs > 0L && p.downTimeMs > 0L && p.interDownMs >= 0L &&
                    touchGrid.isFastTypingBurst(p.interDownMs, eventTimeMs - p.downTimeMs)
                val nextIndex = touchGrid.resolveCharacterSlide(
                    p.index, p.downX, p.downY, x, y, keySet?.toleratesKeyDrift == true, fastTyping,
                )
                if (nextIndex < 0) return
                val row = touchGrid.rowOf(nextIndex)
                val column = touchGrid.columnOf(nextIndex)
                val next = keyOf(row, column) ?: return
                p.canSwipeTone = false
                p.index = nextIndex; p.row = row; p.column = column; p.key = next
                p.frame = frameOf(row, column) ?: p.frame
                p.hitBox = hitBoxOf(row, column) ?: p.hitBox
                showPressed(p)
                scheduleAlternates(p)
                host.redraw()
            }
            Mode.ALTERNATES -> {
                val strip = p.strip ?: return
                val index = if (p.menu && awayFromStrip(strip, x, y)) -1 else strip.indexAt(x)
                if (index != p.selected) {
                    p.selected = index
                    host.selectAlternate(index)
                    listener?.onTick()
                }
            }
            Mode.CURSOR -> moveCursor(p)
            Mode.SHIFT, Mode.PAGE -> slide(p, x, y)
            Mode.DELETE -> {
                if (!p.deleteSwipeArmed && touchGrid.isValidDeleteAllSwipe(p.downX, p.downY, x, y)) {
                    p.deleteSwipeArmed = true
                    stopDelete()
                    host.showBalloon(p.id, p.key.copy(label = "清空"), p.frame)
                    listener?.onTick()
                    host.redraw()
                } else if (p.deleteSwipeArmed && !touchGrid.shouldKeepDeleteAllSwipe(p.downX, p.downY, x, y)) {
                    p.deleteSwipeArmed = false
                    host.hideBalloon(p.id)
                    host.redraw()
                }
            }
            Mode.INERT -> Unit
        }
    }

    /** From Shift or a page key the finger may slide onto a character once past chord-slide hysteresis. */
    private fun slide(p: Pointer, x: Float, y: Float) {
        if (!touchGrid.allowsModifierSlide(p.downX, p.downY, x, y, p.slid)) return
        val nextIndex = touchGrid.resolveHit(x, maxOf(0f, y))
        val row = touchGrid.rowOf(nextIndex)
        val column = touchGrid.columnOf(nextIndex)
        val next = if (nextIndex >= 0) keyOf(row, column) else null
        if (next != null && next.isCharacter && next.enabled) {
            if (p.slid && row == p.row && column == p.column && next == p.key) return
            p.slid = true
            p.index = nextIndex; p.row = row; p.column = column; p.key = next
            p.frame = frameOf(p.row, p.column) ?: p.frame
            p.hitBox = hitBoxOf(p.row, p.column) ?: p.hitBox
            showPressed(p)
            host.redraw()
        } else if (p.slid) {
            host.hideBalloon(p.id)
            p.slid = false
            host.redraw()
        }
    }

    fun up(id: Int, x: Float, y: Float) {
        val slotPos = slotIndexOf(id)
        if (slotPos < 0) return
        val p = removeSlotAt(slotPos)
        p.x = x
        p.y = y
        cancelHold(p)
        host.hideBalloon(p.id)
        val mode = p.mode
        // Clear the released key before editor or engine callbacks can re-layout the keyboard.
        if (mode == Mode.ALTERNATES) host.hideAlternates()
        if (mode == Mode.CURSOR) cursorMode = false
        host.redraw()
        when (mode) {
            Mode.KEY -> if (!p.committed) {
                recognizeTone(p, x, y)
                // Characters and space are typed wherever the finger lifts;
                // Return, language toggle, emoji and retype forgive small edge lift drift.
                if (p.key.rollsOver || withinReleaseSlop(p, x, y)) commit(p, null)
            }
            Mode.ALTERNATES -> {
                if (p.menu) {
                    p.committed = true
                    if (p.selected > 0) listener?.onStripChoice(p.key, p.selected)
                } else if (!p.committed) commit(p, p.options.getOrNull(p.selected))
            }
            Mode.CURSOR -> listener?.onCursorEnd()
            Mode.DELETE -> {
                stopDelete()
                if (p.deleteSwipeArmed || touchGrid.isValidDeleteAllSwipe(p.downX, p.downY, x, y)) {
                    listener?.onTick()
                    listener?.onDeleteAll()
                }
            }
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
        p.generation++
    }

    private fun recognizeTone(p: Pointer, x: Float, y: Float): Boolean {
        val tone = p.key.swipeUpTone ?: return false
        if (p.tone != null) return true
        if (!p.canSwipeTone) return false
        val valid = if (p.index >= 0) {
            touchGrid.isValidToneSwipe(p.index, p.downX, p.downY, x, y)
        } else {
            val rise = p.downY - y
            rise >= KeyTouchGridSoA.TONE_SWIPE_MIN_RISE_DP && rise >= abs(x - p.downX) * KeyTouchGridSoA.TONE_SWIPE_SLOPE
        }
        if (!valid) return false
        p.tone = tone
        return true
    }

    private fun commit(p: Pointer, text: String?) {
        p.committed = true
        val key = p.key
        val tone = p.tone
        if (tone != null) {
            listener?.onKey(key.copy(action = tone, label = tone.mark), null)
            return
        }
        if (key.isCharacter) {
            if (p.mode == Mode.SHIFT) p.typed = true
            for (i in 0 until slotCount) if (slots[i].mode == Mode.SHIFT) slots[i].typed = true
        }
        listener?.onKey(key, text)
    }

    /** Stops whatever a finger was doing, without typing anything. */
    private fun retire(p: Pointer) {
        cancelHold(p)
        host.hideBalloon(p.id)
        val mode = p.mode
        p.mode = Mode.INERT
        p.generation++
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
        val n = slotCount
        for (i in 0 until n) workIds[i] = slots[i].id
        for (i in 0 until n) {
            val pos = slotIndexOf(workIds[i])
            if (pos >= 0) retire(removeSlotAt(pos))
        }
        slotCount = 0
        stopDelete()
        host.clearVisuals()
        host.redraw()
    }

    private fun showPressed(p: Pointer) {
        if (p.key.preview && p.key.isCharacter && p.tone == null) host.showBalloon(p.id, p.key, p.frame) else host.hideBalloon(p.id)
    }

    private fun scheduleHold(p: Pointer, delay: Long, action: () -> Unit) {
        cancelHold(p)
        val gen = p.generation
        val runnable = Runnable {
            p.hold = null
            if (p.generation == gen && slotIndexOf(p.id) >= 0 && !p.committed && p.mode == Mode.KEY) action()
        }
        p.hold = runnable
        host.post(delay, runnable)
    }

    private fun fireHold(p: Pointer) {
        p.committed = true
        listener?.onTick()
        listener?.onKeyHold(p.key)
    }

    /** A TalkBack long-press action: the same as holding the key. */
    fun hold(row: Int, column: Int) {
        val key = keyOf(row, column) ?: return
        if (!key.enabled || !key.holdsRaw) return
        host.pulseKeyPress(row, column)
        listener?.onTick()
        listener?.onKeyHold(key)
    }

    /** A TalkBack action invokes the same tone as an upward swipe. */
    fun activateTone(row: Int, column: Int) {
        val key = keyOf(row, column) ?: return
        val tone = key.swipeUpTone ?: return
        if (!key.enabled) return
        host.pulseKeyPress(row, column)
        listener?.onTick()
        listener?.onKey(key.copy(action = tone, label = tone.mark), null)
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
        // A stationary hold must still type the original mark even when the
        // strip is shifted inward on a very narrow screen.
        p.selected = 0
        host.showAlternates(strip, options, p.selected)
        listener?.onTick()
        host.redraw()
    }

    /** The language key was held: its strip opens over it (the alternates strip, with the way of typing now under the finger). */
    private fun openStrip(p: Pointer) {
        val entries = listener?.stripEntries(p.key).orEmpty()
        if (entries.size < 2 || width <= 0f) return
        val options = entries.map { it.label }
        val strip = KeyBalloon.alternates(p.frame, options.size, width)
        host.hideBalloon(p.id)
        p.mode = Mode.ALTERNATES
        p.menu = true
        p.options = options
        p.strip = strip
        p.selected = 0
        host.showAlternates(strip, options, 0)
        listener?.onTick()
        host.redraw()
    }

    /** Well off the strip sideways or above it: letting go there chooses nothing. */
    private fun awayFromStrip(strip: AlternatesStrip, x: Float, y: Float): Boolean =
        x < strip.box.left - STRIP_SLACK || x > strip.box.right + STRIP_SLACK || y < strip.box.top - STRIP_SLACK

    /** A TalkBack action on the language key: the same as sliding to item [index] of its strip. */
    fun chooseStrip(row: Int, column: Int, index: Int) {
        val key = keyOf(row, column) ?: return
        if (!key.enabled || !key.holdsStrip) return
        host.pulseKeyPress(row, column)
        listener?.onTick()
        listener?.onStripChoice(key, index)
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

    /** A TalkBack activation for clearing composition/field (same as swipe up on Delete). */
    fun deleteAll(row: Int, column: Int) {
        val key = keyOf(row, column) ?: return
        if (!key.enabled || key.action != KeyAction.Delete) return
        host.pulseKeyPress(row, column)
        listener?.onTick()
        listener?.onDeleteAll()
    }

    /** A TalkBack activation is a whole tap: press and release. */
    fun activate(row: Int, column: Int) {
        val key = keyOf(row, column) ?: return
        if (!key.enabled) return
        host.pulseKeyPress(row, column)
        val frame = frameOf(row, column)
        feedback(key, frame?.centerX ?: width / 2, frame?.centerY ?: Float.NaN)
        when (val action = key.action) {
            KeyAction.Shift -> { listener?.onShiftDown(); listener?.onShiftUp(false) }
            KeyAction.Delete -> listener?.onDelete(false)
            is KeyAction.Page -> listener?.onPage(action.page)
            else -> listener?.onKey(key, null)
        }
    }

    private fun feedback(key: Key, centerX: Float, centerY: Float) {
        val fraction = if (width > 0f) (centerX / width).coerceIn(0f, 1f) else 0.5f
        listener?.onKeyDown(key, fraction)
        host.keyDown(key, centerX, centerY)
    }

    companion object {
        const val MAX_POINTERS = 10
        const val ALTERNATES_DELAY = 450L
        const val CURSOR_DELAY = 400L
        const val CURSOR_STEP = 9f
        private const val STRIP_SLACK = 48f
    }
}
