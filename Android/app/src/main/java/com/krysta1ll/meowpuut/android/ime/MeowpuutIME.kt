package com.krysta1ll.meowpuut.android.ime

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Paint
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams
import android.view.Window
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.krysta1ll.meowpuut.android.engine.CandidateMerge
import com.krysta1ll.meowpuut.android.engine.Candidate
import com.krysta1ll.meowpuut.android.engine.CandidateKind
import com.krysta1ll.meowpuut.android.engine.ChoiceMemory
import com.krysta1ll.meowpuut.android.engine.Composer
import com.krysta1ll.meowpuut.android.engine.Edit
import com.krysta1ll.meowpuut.android.engine.InputMode
import com.krysta1ll.meowpuut.android.engine.Lexicons
import kotlin.math.abs

/**
 * The input method. Keys and candidates arrive from the views; the
 * composer turns them into text for the editor.
 */
class MeowpuutIME : InputMethodService(),
    KeyListener, CandidateBarView.Listener, ExpandedPanelView.Listener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var prefs: Prefs
    private lateinit var lexicons: Lexicons
    private lateinit var sprites: CatSprites
    private lateinit var keySounds: KeySounds
    private lateinit var keyHaptics: KeyHaptics
    private lateinit var catAffinity: LocalCatAffinity
    private lateinit var choiceMemory: ChoiceMemory
    private val composer = Composer()
    private val catSchedule = CatSchedule()
    private var keyboardWindowVisible = false
    private var keyboard: KeyboardView? = null
    private var keyboardWindow: KeyboardWindowView? = null
    private var look = Look(false, GlassShape.CAPSULE, Palette.ROSE, GlassStrength.BALANCED, true)
    private var festivalArt: FestivalArt? = null
    /** The festival wardrobe (Festivals.kt), read once. */
    private val festivals by lazy {
        try { assets.open(Festivals.MANIFEST).use { Festivals.parse(it.readBytes().decodeToString()) } } catch (_: Exception) { Festivals.EMPTY }
    }
    private val japaneseMimimo by lazy {
        festivals.themes.firstOrNull { it.id == "tanabata" }?.mimimo?.let { FestivalArt.loadCat(it, assets) }
    }
    private val emojiChoices by lazy {
        val paint = Paint()
        EMOJI.filter(paint::hasGlyph).ifEmpty { listOf("☺", "♥") }.map { Candidate(it, CandidateKind.LITERAL) }
    }

    private var english = false
    private var page = 0
    /** A number, phone or date field: the symbol page stays up. */
    private var numericField = false
    private val caps = Capitalization()
    private var shiftHeld = false
    private var expanded = false
    private var emojiOpen = false
    private var predictions: List<String> = emptyList()
    private var editorAllowsLearning = false
    private val pending = ArrayList<Int>()
    private var loading = false
    private var lexiconFailed = false
    private var opening: InputMode? = null
    private var sessionMode: InputMode? = null
    private var lastSpaceAt = 0L
    private var globe = false
    private var keyContext: KeyContext? = null
    private var keySet: KeySet? = null

    /** Shift as the keys show it: a held Shift counts even after its one letter is used up. */
    private val shift get() = if (caps.state == ShiftState.OFF && shiftHeld) ShiftState.ONCE else caps.state

    private val words get() = Words(prefs.simplified)
    private val mode get() = prefs.mode
    private val chineseActive get() = !english && mode != InputMode.JAPANESE

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        choiceMemory = ChoiceMemory(this)
        composer.choiceMemory = choiceMemory
        catAffinity = LocalCatAffinity(this)
        keySounds = KeySounds(this)
        keySounds.setVolume(prefs.keySoundVolume)
        keySounds.update(prefs.keySound)
        keyHaptics = KeyHaptics(this).apply { update(prefs.haptics, prefs.hapticStrength) }
        prefs.store.registerOnSharedPreferenceChangeListener(this)
        lexicons = Lexicons(this)
        sprites = CatSprites(resources)
        composer.simplified = prefs.simplified
        openLexicon()
        lexicons.loadSymbols {
            composer.symbols = it
            if (composer.composing) refresh()
        }
    }

    override fun onDestroy() {
        prefs.store.unregisterOnSharedPreferenceChangeListener(this)
        catAffinity.flush()
        keySounds.release()
        composer.detach()
        lexicons.shutdown()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val view = KeyboardView(this, sprites, catSchedule)
        view.keys.listener = this
        view.bar.listener = this
        view.bar.onDismissKeyboard = { cat ->
            if (cat != null) catAffinity.fall(cat)
            requestHideSelf(0)
        }
        view.panel.listener = this
        keyboard = view
        val host = KeyboardWindowView(this, view, prefs)
        keyboardWindow = host
        view.setKeyboardHaptics(prefs.haptics)
        applyLook()
        refresh()
        return host
    }

    override fun onEvaluateFullscreenMode() = false

    override fun onConfigureWindow(win: Window, isFullscreen: Boolean, isCandidatesOnly: Boolean) {
        super.onConfigureWindow(win, isFullscreen, isCandidatesOnly)
        if (::prefs.isInitialized && prefs.floatingKeyboard) sizeWindow(win)
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val host = keyboardWindow ?: return
        if (!host.keyboard.floating || !isInputViewShown || !host.isLaidOut) return
        // The editor stays full height. Only the card intercepts touch.
        outInsets.contentTopInsets = host.rootView.height
        outInsets.visibleTopInsets = outInsets.contentTopInsets
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        host.setTouchableRegion(outInsets.touchableRegion)
    }

    override fun onWindowShown() {
        super.onWindowShown()
        if (!keyboardWindowVisible) {
            keyboardWindowVisible = true
            keyboard?.bar?.newPresentation()
        }
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        keyboardWindowVisible = false
        keyboard?.bar?.endPresentation()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        if (!restarting) {
            // Buffered keys belong to the editor that was active at key-down.
            pending.clear()
            lastSpaceAt = 0
        }
        // When input starts again the editor keeps what was being composed
        // as plain text, so the composition here must not continue it.
        composer.clear()
        val inputType = attribute?.inputType ?: InputType.TYPE_NULL
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        editorAllowsLearning = allowsChoiceMemory(inputType, attribute?.imeOptions ?: 0)
        composer.rememberChoices = prefs.rememberChoices && editorAllowsLearning
        composer.symbolsAllowed = when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> variation != InputType.TYPE_TEXT_VARIATION_PASSWORD &&
                variation != InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD &&
                variation != InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation != InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> true
        }
        expanded = false
        emojiOpen = false
        openLexicon()
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!restarting) {
            predictions = emptyList()
            page = 0
            english = false
            val inputClass = info.inputType and InputType.TYPE_MASK_CLASS
            val variation = info.inputType and InputType.TYPE_MASK_VARIATION
            numericField = inputClass == InputType.TYPE_CLASS_NUMBER || inputClass == InputType.TYPE_CLASS_PHONE ||
                inputClass == InputType.TYPE_CLASS_DATETIME
            if (numericField) {
                english = true
                page = 1
            } else if (inputClass == InputType.TYPE_CLASS_TEXT && variation in LATIN_VARIATIONS) {
                english = true
            }
        }
        caps.reset()
        shiftHeld = false
        globe = needsGlobe()
        updateAutoCaps()
        configureWindow()
        applyLook()
        refresh()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // Keep what was typed, as when the iOS keyboard goes away.
        if (composer.composing) finishComposition()
        catAffinity.flush()
        expanded = false
        emojiOpen = false
        keyboard?.previews?.clear()
        keyboard?.bar?.cancelTouch()
        if (finishingInput) pending.clear()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        catAffinity.flush()
        pending.clear()
        lastSpaceAt = 0
        composer.clear()
        editorAllowsLearning = false
        composer.rememberChoices = false
        expanded = false
        emojiOpen = false
        super.onFinishInput()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (composer.composing) {
            // The editor reports where the composing text is. A cursor outside
            // it means the user moved it away. Updates without a composing
            // span are from edits made before this composition and say nothing.
            if (candidatesStart in 0..candidatesEnd) {
                val inside = newSelStart in candidatesStart..candidatesEnd && newSelEnd in candidatesStart..candidatesEnd
                if (!inside) {
                    composer.clear()
                    currentInputConnection?.finishComposingText()
                    expanded = false
                    refresh()
                }
            }
        } else if (english) {
            updateAutoCaps()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLook()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // In the background the lexicon is given back; the next field loads it again.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND && !isInputViewShown) {
            composer.detach()
            sessionMode = null
            lexicons.release()
        }
    }

    override fun onSharedPreferenceChanged(store: SharedPreferences?, key: String?) {
        when (key) {
            Prefs.FLOATING_X, Prefs.FLOATING_Y -> return
            Prefs.MODE -> {
                finishComposition()
                pending.clear()
                english = false
                page = 0
                emojiOpen = false
                expanded = false
                predictions = emptyList()
                caps.reset()
                shiftHeld = false
                // Keys typed while the new lexicon loads are queued, not sent to the old one.
                composer.detach()
                sessionMode = null
                openLexicon()
            }
            Prefs.SIMPLIFIED -> {
                composer.simplified = prefs.simplified
                predictions = emptyList()
            }
            Prefs.ENGLISH_AUTO_CAPS -> {
                updateAutoCaps()
                return
            }
            Prefs.REMEMBER_CHOICES ->
                composer.rememberChoices = prefs.rememberChoices && editorAllowsLearning
            Prefs.CLEAR_CHOICE_MEMORY -> composer.refreshChoices()
            Prefs.HAPTICS, Prefs.HAPTIC_STRENGTH -> {
                keyboard?.setKeyboardHaptics(prefs.haptics)
                keyHaptics.update(prefs.haptics, prefs.hapticStrength)
                return
            }
            Prefs.KEY_SOUND -> {
                keySounds.update(prefs.keySound)
                return
            }
            Prefs.KEY_SOUND_VOLUME -> {
                keySounds.setVolume(prefs.keySoundVolume)
                return
            }
            else -> applyLook()
        }
        refresh()
    }

    // ---------------------------------------------------------------- engine

    /** Loads the lexicon for the current mode unless it is loaded or on its way. */
    private fun openLexicon() {
        val target = mode
        if (opening == target || (sessionMode == target && composer.ready)) return
        // Opening frees the session of the mode before; the composer lets go of it first.
        if (sessionMode != target) composer.detach()
        opening = target
        loading = target != InputMode.JAPANESE
        lexiconFailed = false
        lexicons.open(target) { session ->
            opening = null
            loading = false
            lexiconFailed = target != InputMode.JAPANESE && session == 0L
            composer.japaneseWords = lexicons.japanese
            composer.attach(target, session)
            sessionMode = target
            // Keys typed while it loaded; they already carry their case.
            val queued = pending.toList()
            pending.clear()
            if (!lexiconFailed && currentInputConnection != null) {
                for (code in queued) typeEngine(code, null, asTyped = true)
            }
            refresh()
        }
        refresh()
    }

    private fun applyEdit(edit: Edit, wasComposing: Boolean, resetPanel: Boolean = false) {
        val ic = currentInputConnection
        var committed = false
        if (ic != null) {
            if (composer.composing && edit.commit.isNotEmpty()) {
                // A partial selection replaces the old reading and installs its remainder.
                ic.beginBatchEdit()
                try {
                    committed = commit(edit.commit, ic)
                    ic.setComposingText(composer.preedit, 1)
                } finally {
                    ic.endBatchEdit()
                }
            } else if (edit.commit.isNotEmpty()) {
                committed = commit(edit.commit, ic)
            } else if (composer.composing) {
                ic.setComposingText(composer.preedit, 1)
            } else if (wasComposing) {
                ic.commitText("", 1)
            }
        }
        if (committed && edit.confirmedReading.isNotEmpty() && composer.rememberChoices) {
            choiceMemory.remember(composer.mode, composer.simplified, edit.confirmedReading, edit.commit)
        }
        if (!composer.composing) {
            expanded = false
            if (edit.commit.isNotEmpty()) {
                updatePredictions()
                if (wasComposing) keyboard?.bar?.committed()
            }
        }
        refresh(resetPanel)
    }

    /** Chooses the highlighted candidate until nothing is left composing. */
    private fun finishComposition() {
        if (!composer.composing) return
        var guard = 0
        while (composer.composing && guard++ < 32) {
            val edit = composer.chooseHighlighted()
            if (!edit.handled) {
                applyEdit(Edit(true, composer.takeLiteral()), true)
                break
            }
            applyEdit(edit, true)
        }
        if (composer.composing) applyEdit(Edit(true, composer.takeLiteral()), true)
    }

    private fun commit(text: String, connection: InputConnection? = currentInputConnection): Boolean {
        if (text.isEmpty() || connection?.commitText(text, 1) != true) return false
        catAffinity.committed(text)
        return true
    }

    private fun updatePredictions() {
        predictions = if (chineseActive) {
            val before = currentInputConnection?.getTextBeforeCursor(8, 0)?.toString().orEmpty()
            composer.predictions(before)
        } else emptyList()
    }

    /**
     * A letter or Zhuyin key. [asTyped]: the code already has its case (a
     * hardware key, or a key replayed after the lexicon loaded).
     */
    private fun typeEngine(code: Int, alternate: String?, asTyped: Boolean = false) {
        if (alternate != null) {
            finishComposition()
            commit(alternate)
            letterTyped()
            return
        }
        // Shift raises letters in every letters layout: English text, a
        // reading kept as typed in Pinyin (names, abbreviations) and katakana
        // first in Japanese. Zhuyin keys have no case.
        val upper = !asTyped && shift != ShiftState.OFF && (english || mode != InputMode.ZHUYIN)
        val c = if (upper) code.toChar().uppercaseChar().code else code
        val nineKeySeparator = !english && mode == InputMode.NINE_KEY && c == '\''.code
        if (nineKeySeparator) {
            val followsDigit = pending.lastOrNull()?.toChar()?.let { it in '2'..'9' } ?: composer.canSeparateNineKey
            if (!followsDigit) return
        }
        if (english) {
            commit(c.toChar().toString())
            letterTyped()
            updateAutoCaps()
            return
        }
        if (!composer.ready) {
            if (lexiconFailed) {
                // Without a lexicon Pinyin letters are at least typed as they are.
                if (mode.pinyinBased && !nineKeySeparator) commit(c.toChar().toString())
                return
            }
            if (currentInputConnection == null) return
            val wasEmpty = pending.isEmpty()
            pending += c
            if (!asTyped) letterTyped()
            openLexicon()
            if (wasEmpty && mode == InputMode.NINE_KEY) refresh()
            return
        }
        val wasComposing = composer.composing
        val edit = composer.type(c)
        if (!edit.handled) {
            if (nineKeySeparator) return
            finishComposition()
            commit(c.toChar().toString())
            return
        }
        predictions = emptyList()
        applyEdit(edit, wasComposing)
        if (!asTyped) letterTyped()
    }

    // ---------------------------------------------------------------- hardware keys

    private val consumedKeys = HashSet<Int>()

    /**
     * A physical keyboard types into the same composition: letters (and the
     * Zhuyin keys) compose, Space and Enter choose, digits pick a candidate
     * in Pinyin (Shift+digit in Zhuyin), Esc cancels.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (emojiOpen && keyCode == KeyEvent.KEYCODE_ESCAPE) {
            setEmoji(false)
            consumedKeys += keyCode
            return true
        }
        if (emojiOpen && keyCode != KeyEvent.KEYCODE_DEL &&
            (event.unicodeChar != 0 || keyCode == KeyEvent.KEYCODE_ENTER)) setEmoji(false)
        if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed || english) return super.onKeyDown(keyCode, event)
        val composing = composer.composing
        val handled = when (keyCode) {
            KeyEvent.KEYCODE_SPACE -> composing.also { if (it) space() }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> composing.also { if (it) enter() }
            KeyEvent.KEYCODE_DEL -> (composing || pending.isNotEmpty()).also { if (it) onDelete(false) }
            KeyEvent.KEYCODE_ESCAPE -> clearComposition()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> composing.also { if (it) moveHighlight(if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1) }
            else -> typeHardware(event)
        }
        if (handled) consumedKeys += keyCode
        return handled || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        consumedKeys.remove(keyCode) || super.onKeyUp(keyCode, event)

    private fun typeHardware(event: KeyEvent): Boolean {
        val unicode = event.getUnicodeChar(event.metaState)
        if (unicode == 0) return false
        val c = unicode.toChar()
        if (composer.composing) {
            val digit = when {
                mode == InputMode.PINYIN && c in '1'..'9' -> c - '1'
                mode == InputMode.ZHUYIN && event.isShiftPressed -> "!@#$%^&*(".indexOf(c)
                else -> -1
            }
            if (digit >= 0) {
                visibleCandidates().getOrNull(digit)?.let { onCandidate(it) }
                return true
            }
        }
        val accepted = when (mode) {
            InputMode.PINYIN -> c.isLetter() && c.code < 128 || (c == '\'' && composer.composing)
            InputMode.NINE_KEY -> c in '2'..'9' || c == '\''
            InputMode.JAPANESE -> c.isLetter() && c.code < 128 || (c == '-' && composer.composing)
            InputMode.ZHUYIN -> c in ZHUYIN_KEYS || (c in " 6347" && composer.composing)
        }
        if (!accepted) {
            if (composer.composing) finishComposition()
            return false
        }
        typeEngine(c.code, null, asTyped = true)
        return true
    }

    private fun visibleCandidates(): List<Int> {
        return CandidateMerge.collapsed(composer.candidates, limitJapanese = mode.pinyinBased)
    }

    private fun moveHighlight(delta: Int) {
        val visible = visibleCandidates()
        if (visible.isEmpty()) return
        val position = visible.indexOf(composer.highlighted).coerceAtLeast(0)
        composer.highlighted = visible[(position + delta + visible.size) % visible.size]
        refresh()
    }

    // ---------------------------------------------------------------- keys

    override fun onKeyDown(key: Key, fraction: Float) {
        val view = keyboard ?: return
        keyHaptics.play(view, HapticFeedbackConstants.KEYBOARD_TAP)
        val effect = when (key.action) {
            KeyAction.Delete -> AudioManager.FX_KEYPRESS_DELETE
            KeyAction.Space -> AudioManager.FX_KEYPRESS_SPACEBAR
            KeyAction.Return -> AudioManager.FX_KEYPRESS_RETURN
            else -> AudioManager.FX_KEYPRESS_STANDARD
        }
        keySounds.play(effect, fraction)
    }

    override fun onKey(key: Key, text: String?) {
        if (key.action != KeyAction.Emoji && emojiOpen) setEmoji(false)
        when (val action = key.action) {
            is KeyAction.Engine -> typeEngine(action.code, text)
            is KeyAction.Text -> typeSymbol(text ?: action.text)
            KeyAction.Segment -> typeEngine('\''.code, text)
            KeyAction.ClearComposition -> clearComposition()
            KeyAction.Space -> {
                space()
                // As on Apple's keyboard, a space typed on a symbol page goes back to letters.
                if (page > 0 && !numericField) onPage(0)
            }
            KeyAction.Return -> enter()
            KeyAction.Globe -> getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            KeyAction.ToggleEnglish -> toggleEnglish()
            KeyAction.Emoji -> setEmoji(!emojiOpen)
            else -> Unit
        }
    }

    override fun onPage(page: Int) {
        if (emojiOpen) setEmoji(false)
        if (this.page == page) return
        this.page = page
        refresh()
    }

    override fun onTick() {
        keyboard?.let { keyHaptics.play(it, HapticFeedbackConstants.CLOCK_TICK) }
    }

    private fun typeSymbol(text: String) {
        if (text == "ー" && !english && mode == InputMode.JAPANESE && composer.composing) {
            page = 0
            typeEngine('-'.code, null, asTyped = true)
            return
        }
        finishComposition()
        commit(text)
        if (mode == InputMode.NINE_KEY) lastSpaceAt = 0
        if (page in 1..2 && !numericField && text in KeyBuilder.autoReturnMarks) page = 0
        predictions = emptyList()
        refresh()
    }

    private fun clearComposition(): Boolean {
        val wasComposing = composer.composing
        if (!wasComposing && pending.isEmpty()) return false
        pending.clear()
        composer.clear()
        if (wasComposing) currentInputConnection?.commitText("", 1)
        expanded = false
        predictions = emptyList()
        refresh()
        return true
    }

    private fun space() {
        if (composer.composing) {
            applyEdit(composer.space(), true)
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastSpaceAt < 320 && doubleSpacePeriod()) {
            lastSpaceAt = 0
            return
        }
        commit(" ")
        lastSpaceAt = now
        predictions = emptyList()
        updateAutoCaps()
        refresh()
    }

    /** Two quick spaces after a letter or digit type a full stop. */
    private fun doubleSpacePeriod(): Boolean {
        val ic = currentInputConnection ?: return false
        val before = ic.getTextBeforeCursor(2, 0) ?: return false
        if (before.length < 2 || before[1] != ' ' || !before[0].isLetterOrDigit()) return false
        ic.beginBatchEdit()
        ic.deleteSurroundingText(1, 0)
        commit(if (english || before[0].code < 128) ". " else "。", ic)
        ic.endBatchEdit()
        updateAutoCaps()
        refresh()
        return true
    }

    private fun enter() {
        if (composer.composing) {
            applyEdit(composer.chooseHighlighted(confirm = true), true)
            return
        }
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val options = info?.imeOptions ?: 0
        val action = options and EditorInfo.IME_MASK_ACTION
        val noAction = options and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        val inputType = info?.inputType ?: InputType.TYPE_NULL
        val multiline = inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
            inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        val shiftBefore = shift
        if (!noAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else if (multiline && ic.commitText("\n", 1)) {
            // The next word starts a line. There is no need to block on a
            // host query while the key is lifting; selection updates reconcile it.
            if (english && prefs.englishAutoCaps) {
                val capsFlags = InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                    InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                caps.applyAutomatic(inputType and capsFlags != 0)
            }
        } else {
            sendKeyChar('\n')
        }
        lastSpaceAt = 0
        if (predictions.isEmpty() && shiftBefore == shift) return
        predictions = emptyList()
        refresh()
    }

    override fun onDelete(word: Boolean) {
        if (pending.isNotEmpty()) {
            if (word) pending.clear() else pending.removeAt(pending.lastIndex)
            return
        }
        if (composer.composing) {
            applyEdit(composer.backspace(), true)
            return
        }
        if (predictions.isNotEmpty()) {
            predictions = emptyList()
            refresh()
        }
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
            return
        }
        val before = ic.getTextBeforeCursor(if (word) 48 else 16, 0)
        if (before.isNullOrEmpty()) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            return
        }
        val count = if (word) TextUnits.wordLength(before) else TextUnits.lastGrapheme(before)
        ic.deleteSurroundingText(count.coerceAtLeast(1), 0)
        if (english) updateAutoCaps()
    }

    override fun onShiftDown() {
        caps.tap(SystemClock.uptimeMillis())
        shiftHeld = true
        refresh()
    }

    override fun onShiftUp(typed: Boolean) {
        shiftHeld = false
        // Shift held while typing applies only to those letters.
        if (typed && caps.state != ShiftState.LOCKED) caps.reset()
        refresh()
    }

    /** A letter was typed: a one-letter Shift is used up. */
    private fun letterTyped() {
        val before = shift
        caps.letterTyped()
        if (shift != before) refresh()
    }

    /** The capital an editor asks for at the start of a sentence, in English only. */
    private fun updateAutoCaps() {
        val before = shift
        val info = currentInputEditorInfo
        val wanted = english && prefs.englishAutoCaps && info != null &&
            (currentInputConnection?.getCursorCapsMode(info.inputType) ?: 0) != 0
        caps.applyAutomatic(wanted)
        if (shift != before) refresh()
    }

    override fun onCursorStart() {
        finishComposition()
        predictions = emptyList()
        refresh()
    }

    override fun onCursorMove(steps: Int) {
        val code = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(abs(steps)) { sendDownUpKeyEvents(code) }
    }

    override fun onCursorEnd() = Unit

    private fun toggleEnglish() {
        finishComposition()
        pending.clear()
        english = !english
        page = 0
        caps.reset()
        predictions = emptyList()
        updateAutoCaps()
        refresh()
    }

    private fun needsGlobe(): Boolean = if (Build.VERSION.SDK_INT >= 28) {
        shouldOfferSwitchingToNextInputMethod()
    } else {
        val token = window?.window?.attributes?.token
        @Suppress("DEPRECATION")
        token != null && (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).shouldOfferSwitchingToNextInputMethod(token)
    }

    // ---------------------------------------------------------------- candidates

    override fun onCandidate(index: Int) {
        if (emojiOpen) {
            emojiChoices.getOrNull(index)?.let {
                onTick()
                keySounds.play(AudioManager.FX_KEYPRESS_STANDARD, pick = true)
                commit(it.text)
            }
            return
        }
        val wasComposing = composer.composing
        val edit = composer.choose(index, confirm = true)
        if (edit.handled) {
            onTick()
            keySounds.play(AudioManager.FX_KEYPRESS_STANDARD, pick = true)
        }
        // What is left of the reading gets new candidates: the panel starts at their top.
        applyEdit(edit, wasComposing, resetPanel = true)
    }

    override fun onPrediction(text: String) {
        onTick()
        commit(text)
        updatePredictions()
        refresh()
    }

    override fun onPunctuation(mark: String) {
        typeSymbol(mark)
    }

    override fun onPunctuationTick() = onTick()

    override fun onToggleExpanded() {
        if (emojiOpen) {
            setEmoji(false)
            return
        }
        if (!composer.composing && !expanded) return
        expanded = !expanded
        if (expanded && composer.candidates.size < 120) composer.loadMore()
        refresh(resetPanel = true)
    }

    override fun onNeedMore() {
        if (composer.loadMore()) refresh()
    }

    private fun setEmoji(open: Boolean) {
        if (emojiOpen == open) return
        if (open) {
            if (pending.isNotEmpty()) {
                keyboard?.bar?.announce(words("詞庫載入中，請稍候", "词库载入中，请稍候"))
                return
            }
            finishComposition()
        }
        emojiOpen = open
        expanded = false
        predictions = emptyList()
        refresh(resetPanel = true)
    }

    override fun onPet(cat: Cat) {
        val view = keyboard ?: return
        catAffinity.pet(cat)
        if (cat == Cat.MOMO && mode == InputMode.JAPANESE) {
            view.bar.playEasterEgg()
            return
        }
        val nextMode = mode.next()
        val name = when (nextMode) {
            InputMode.ZHUYIN -> "注音"
            InputMode.PINYIN -> "拼音"
            InputMode.NINE_KEY -> words("九鍵拼音", "九键拼音")
            InputMode.JAPANESE -> words("日語", "日语")
        }
        val outcome = view.bar.pet(cat, prefs.simplified, name.takeUnless { numericField })
        if (outcome != CatAffection.Outcome.Happy) return
        if (cat == Cat.MIMIMO) {
            if (numericField) return
            if (opening != null || pending.isNotEmpty()) {
                view.bar.announce(words("詞庫載入中，請稍候", "词库载入中，请稍候"))
                return
            }
            prefs.mode = nextMode
            view.bar.announce(words("已切換到$name", "已切换到$name"))
        } else {
            prefs.simplified = !prefs.simplified
            val simplified = prefs.simplified
            var text = if (simplified) "已切换为简体中文" else "已切換為繁體中文"
            if (!chineseActive) text += if (simplified) "（中文输入生效）" else "（中文輸入生效）"
            view.bar.announce(text)
        }
    }

    // ---------------------------------------------------------------- views

    private fun applyLook() {
        val dark = when (prefs.tone) {
            Tone.SYSTEM -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            Tone.LIGHT -> false
            Tone.DARK -> true
        }
        // A festival day: the dressed cats, the right end's ornaments and the capsule's colour.
        val skin = prefs.skin
        val wardrobe = FestivalWardrobe(on = prefs.festivals)
        // FestivalOverride: debug builds only; the release build's always returns null.
        val today = java.time.LocalDate.now()
        festivalDay = today
        val festival = if (skin.supportsFestivals) {
            FestivalOverride.theme(this, festivals, wardrobe) ?: Festivals.theme(today, festivals, wardrobe)
        } else null
        val festivalPill = festival?.let { Look.parseColor((if (dark) it.colors.dark else it.colors.light).lastOrNull()) }
        if (festival?.id != festivalArt?.theme?.id) festivalArt = festival?.let { FestivalArt.load(it, assets) }
        look = Look(dark, skin.shape(prefs.shape), prefs.palette, prefs.strength, prefs.cats, festivalPill,
            wantsMoreContrast(this), prefs.effects, skin)
        keyboard?.let {
            it.look = look
            it.bar.festival = festivalArt
            it.bar.candidateTextSize = prefs.candidateTextSize
            it.panel.candidateTextSize = prefs.candidateTextSize
            it.cats = prefs.cats
            it.keyHeight = prefs.keyboardHeight
            it.setDockedPanel(prefs.panelHeightDelta, prefs.panelBottomGap)
            it.keys.showKeyPopups = prefs.keyPopups
            it.keys.keyTextSize = prefs.keyTextSize
        }
        keyboardWindow?.configure(look)
        configureWindow()
    }

    private fun sizeWindow(win: Window) {
        val height = if (prefs.floatingKeyboard) LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT
        if (win.attributes.height != height) win.setLayout(LayoutParams.MATCH_PARENT, height)
        if (prefs.floatingKeyboard) win.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun configureWindow() {
        val w = window?.window ?: return
        sizeWindow(w)
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < 35) w.navigationBarColor =
            if (prefs.floatingKeyboard) android.graphics.Color.TRANSPARENT else look.keyboardBottom
        // The keyboard paints its own background under the navigation bar;
        // the system's contrast scrim would lay a pale band over it.
        if (Build.VERSION.SDK_INT >= 29) w.isNavigationBarContrastEnforced = false
        if (Build.VERSION.SDK_INT >= 30) {
            w.insetsController?.setSystemBarsAppearance(
                if (look.dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        }
    }

    private fun spaceTitle(): String {
        val t = words
        if (!composer.composing) return if (english) "space" else "空格"
        return when (mode) {
            InputMode.PINYIN, InputMode.NINE_KEY -> if (composer.candidates.isNotEmpty()) t("選詞", "选词") else "原文＋空格"
            InputMode.JAPANESE -> t("選詞", "选词")
            InputMode.ZHUYIN -> if (composer.input.lastOrNull()?.let { it in " 6347" } == true) t("選詞", "选词") else t("一聲", "一声")
        }
    }

    private fun returnTitle(): Pair<String, Boolean> {
        val t = words
        if (composer.composing) return t("確認", "确认") to false
        val info = currentInputEditorInfo
        val options = info?.imeOptions ?: 0
        if (options and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return t("換行", "换行") to false
        return when (options and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEARCH -> t("搜尋", "搜索") to true
            EditorInfo.IME_ACTION_SEND -> t("傳送", "发送") to true
            EditorInfo.IME_ACTION_GO -> "前往" to true
            EditorInfo.IME_ACTION_DONE -> "完成" to true
            EditorInfo.IME_ACTION_NEXT -> "下一步" to true
            EditorInfo.IME_ACTION_PREVIOUS -> "上一步" to false
            else -> t("換行", "换行") to false
        }
    }

    private fun status(): String {
        if (loading) return words("詞庫載入中", "词库载入中")
        if (lexiconFailed && !english) return words("詞庫無法載入", "词库无法载入")
        if (english) return "English"
        val script = if (prefs.simplified) "简体" else "繁體"
        return when (mode) {
            InputMode.JAPANESE -> "日本語"
            InputMode.PINYIN -> "拼音 · $script"
            InputMode.NINE_KEY -> words("九鍵 · $script", "九键 · $script")
            InputMode.ZHUYIN -> "注音 · $script"
        }
    }

    /** The day the festival theme was last looked up for. */
    private var festivalDay: java.time.LocalDate? = null

    private fun refresh(resetPanel: Boolean = false) {
        val view = keyboard ?: return
        // A keyboard left up past midnight takes the new day's theme, between words only.
        if (composer.input.isEmpty() && festivalDay != java.time.LocalDate.now()) applyLook()
        val (returnTitle, accent) = returnTitle()
        val showPanel = emojiOpen || (expanded && composer.composing)
        val context = KeyContext(
            mode = mode,
            english = english,
            page = page,
            shift = shift,
            simplified = prefs.simplified,
            needsGlobe = globe,
            spaceTitle = spaceTitle(),
            returnTitle = returnTitle,
            returnAccent = accent,
            returnEnabled = true,
            expanded = showPanel,
            emoji = emojiOpen,
            composing = mode == InputMode.NINE_KEY && (composer.composing || pending.isNotEmpty()),
        )
        // Most keystrokes change nothing about the keys; the last set is kept.
        val set = keySet?.takeIf { context == keyContext } ?: KeyBuilder.build(context).also {
            keySet = it
            keyContext = context
        }
        // Zhuyin's five rows set the height for its symbol pages too.
        view.setRows(set.rows.size, if (mode == InputMode.ZHUYIN && !english) 5 else 4)
        view.keys.setKeys(set, view.metrics(), view.idiomFor())

        val all = composer.candidates
        val visible = if (english) all.indices.toList() else visibleCandidates()
        val composing = composer.composing
        view.bar.japaneseMimimo = if (mode == InputMode.JAPANESE && !english && prefs.cats) japaneseMimimo else null
        view.bar.update(CandidateBarView.Content(
            candidates = if (emojiOpen) emptyList() else all,
            visible = if (emojiOpen) emptyList() else visible,
            highlighted = composer.highlighted,
            predictions = if (composing || emojiOpen) emptyList() else predictions,
            punctuation = if (!emojiOpen && !composing && predictions.isEmpty() && chineseActive && !loading) QUICK_MARKS else emptyList(),
            status = if (emojiOpen) words("表情符號", "表情符号") else status(),
            expanded = showPanel,
            canExpand = showPanel || (composing && all.isNotEmpty()),
            composing = composing,
            japaneseMarks = mode.pinyinBased,
            emoji = emojiOpen,
            japaneseMode = mode == InputMode.JAPANESE,
            words = words,
        ))
        view.expanded = showPanel
        view.panel.words = words
        if (emojiOpen) view.panel.show(emojiChoices, -1, false, resetPanel, emoji = true)
        else if (showPanel) view.panel.show(all, composer.highlighted, mode.pinyinBased, resetPanel)
    }

    companion object {
        internal fun allowsChoiceMemory(inputType: Int, imeOptions: Int): Boolean {
            if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT ||
                imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
            ) return false
            return inputType and InputType.TYPE_MASK_VARIATION !in LATIN_VARIATIONS
        }

        private const val ZHUYIN_KEYS = "1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/-"
        private val QUICK_MARKS = listOf("，", "。", "？", "！", "、")
        private val EMOJI = listOf(
            "😀", "😄", "😁", "😂", "😊", "😍", "😘", "😋", "😎", "🤔", "🙄", "😅",
            "😢", "😭", "😡", "😴", "👋", "👍", "👎", "👌", "✌️", "👏", "🙏", "💪",
            "🐱", "😺", "🐶", "🐻", "🐼", "🐰", "🐾", "🦊", "🌸", "🌈", "☀️", "🌙",
            "🍎", "🍓", "🍜", "🍙", "🍣", "🍰", "☕", "🍵", "🎂", "🎉", "🎁", "🎵",
            "❤️", "💕", "💖", "✨", "⭐", "🔥", "✅", "❌", "❗", "❓", "💯", "💤",
        )
        private val LATIN_VARIATIONS = setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI,
        )
    }
}
