package com.krysta1ll.meowpuut.android.ime

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import com.krysta1ll.meowpuut.android.engine.CandidateMerge
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
    private val composer = Composer()
    private var keyboard: KeyboardView? = null
    private var look = Look(false, GlassShape.CAPSULE, Palette.ROSE, GlassStrength.BALANCED, true)
    private var festivalArt: FestivalArt? = null
    /** The festival wardrobe (Festivals.kt), read once. */
    private val festivals by lazy {
        try { assets.open(Festivals.MANIFEST).use { Festivals.parse(it.readBytes().decodeToString()) } } catch (_: Exception) { Festivals.EMPTY }
    }

    private var english = false
    private var page = 0
    /** A number, phone or date field: the symbol page stays up. */
    private var numericField = false
    private val caps = Capitalization()
    private var shiftHeld = false
    private var expanded = false
    private var predictions: List<String> = emptyList()
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
        prefs.store.registerOnSharedPreferenceChangeListener(this)
        lexicons = Lexicons(this)
        sprites = CatSprites(resources)
        composer.simplified = prefs.simplified
        openLexicon()
    }

    override fun onDestroy() {
        prefs.store.unregisterOnSharedPreferenceChangeListener(this)
        composer.detach()
        lexicons.shutdown()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val view = KeyboardView(this, sprites)
        view.keys.listener = this
        view.bar.listener = this
        view.panel.listener = this
        keyboard = view
        applyLook()
        refresh()
        return view
    }

    override fun onEvaluateFullscreenMode() = false

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        // When input starts again the editor keeps what was being composed
        // as plain text, so the composition here must not continue it.
        composer.clear()
        expanded = false
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
        expanded = false
        keyboard?.previews?.clear()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        composer.clear()
        expanded = false
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
            Prefs.MODE -> {
                finishComposition()
                english = false
                page = 0
                // Keys typed while the new lexicon loads are queued, not sent to the old one.
                composer.detach()
                sessionMode = null
                openLexicon()
            }
            Prefs.SIMPLIFIED -> {
                composer.simplified = prefs.simplified
                predictions = emptyList()
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
            if (!lexiconFailed) for (code in queued) typeEngine(code, null, asTyped = true)
            refresh()
        }
        refresh()
    }

    private fun applyEdit(edit: Edit, wasComposing: Boolean, resetPanel: Boolean = false) {
        val ic = currentInputConnection
        if (ic != null) {
            ic.beginBatchEdit()
            if (edit.commit.isNotEmpty()) {
                ic.commitText(edit.commit, 1)
            } else if (wasComposing && !composer.composing) {
                ic.commitText("", 1)
            }
            if (composer.composing) ic.setComposingText(composer.preedit, 1)
            ic.endBatchEdit()
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
    }

    private fun commit(text: String) {
        if (text.isEmpty()) return
        currentInputConnection?.commitText(text, 1)
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
        if (english) {
            commit(c.toChar().toString())
            letterTyped()
            updateAutoCaps()
            return
        }
        if (!composer.ready) {
            if (lexiconFailed) {
                // Without a lexicon Pinyin letters are at least typed as they are.
                if (mode == InputMode.PINYIN) commit(c.toChar().toString())
                return
            }
            pending += c
            if (!asTyped) letterTyped()
            openLexicon()
            return
        }
        val wasComposing = composer.composing
        val edit = composer.type(c)
        if (!edit.handled) {
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
        if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed || english) return super.onKeyDown(keyCode, event)
        val composing = composer.composing
        val handled = when (keyCode) {
            KeyEvent.KEYCODE_SPACE -> composing.also { if (it) space() }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> composing.also { if (it) enter() }
            KeyEvent.KEYCODE_DEL -> composing.also { if (it) onDelete(false) }
            KeyEvent.KEYCODE_ESCAPE -> composing.also {
                if (it) {
                    composer.clear()
                    currentInputConnection?.commitText("", 1)
                    expanded = false
                    refresh()
                }
            }
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
        val all = composer.candidates
        return if (mode == InputMode.PINYIN) CandidateMerge.collapsed(all) else all.indices.toList()
    }

    private fun moveHighlight(delta: Int) {
        val visible = visibleCandidates()
        if (visible.isEmpty()) return
        val position = visible.indexOf(composer.highlighted).coerceAtLeast(0)
        composer.highlighted = visible[(position + delta + visible.size) % visible.size]
        refresh()
    }

    // ---------------------------------------------------------------- keys

    override fun onKeyDown(key: Key) {
        val view = keyboard ?: return
        if (prefs.haptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (prefs.sound) {
            val effect = when (key.action) {
                KeyAction.Delete -> AudioManager.FX_KEYPRESS_DELETE
                KeyAction.Space -> AudioManager.FX_KEYPRESS_SPACEBAR
                KeyAction.Return -> AudioManager.FX_KEYPRESS_RETURN
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
            (getSystemService(Context.AUDIO_SERVICE) as AudioManager).playSoundEffect(effect, -1f)
        }
    }

    override fun onKey(key: Key, text: String?) {
        when (val action = key.action) {
            is KeyAction.Engine -> typeEngine(action.code, text)
            is KeyAction.Text -> typeSymbol(text ?: action.text)
            KeyAction.Space -> {
                space()
                // As on Apple's keyboard, a space typed on a symbol page goes back to letters.
                if (page > 0 && !numericField) onPage(0)
            }
            KeyAction.Return -> enter()
            KeyAction.Globe -> switchKeyboard()
            KeyAction.ToggleEnglish -> toggleEnglish()
            else -> Unit
        }
    }

    override fun onPage(page: Int) {
        if (this.page == page) return
        this.page = page
        refresh()
    }

    override fun onTick() {
        if (prefs.haptics) keyboard?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun typeSymbol(text: String) {
        finishComposition()
        commit(text)
        if (page > 0 && !numericField && text in KeyBuilder.autoReturnMarks) page = 0
        predictions = emptyList()
        refresh()
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
        ic.commitText(if (english || before[0].code < 128) ". " else "。", 1)
        ic.endBatchEdit()
        updateAutoCaps()
        refresh()
        return true
    }

    private fun enter() {
        if (composer.composing) {
            applyEdit(composer.chooseHighlighted(), true)
            return
        }
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noAction = ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!noAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            sendKeyChar('\n')
        }
        predictions = emptyList()
        updateAutoCaps()
        refresh()
    }

    override fun onDelete(word: Boolean) {
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
        val wanted = english && info != null && (currentInputConnection?.getCursorCapsMode(info.inputType) ?: 0) != 0
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
        english = !english
        page = 0
        caps.reset()
        predictions = emptyList()
        updateAutoCaps()
        refresh()
    }

    private fun switchKeyboard() {
        finishComposition()
        if (Build.VERSION.SDK_INT >= 28) {
            switchToNextInputMethod(false)
        } else {
            val token = window?.window?.attributes?.token ?: return
            @Suppress("DEPRECATION")
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).switchToNextInputMethod(token, false)
        }
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
        val wasComposing = composer.composing
        // What is left of the reading gets new candidates: the panel starts at their top.
        applyEdit(composer.choose(index), wasComposing, resetPanel = true)
    }

    override fun onPrediction(text: String) {
        commit(text)
        updatePredictions()
        refresh()
    }

    override fun onPunctuation(mark: String) {
        commit(mark)
        predictions = emptyList()
        refresh()
    }

    override fun onToggleExpanded() {
        if (!composer.composing && !expanded) return
        expanded = !expanded
        if (expanded && composer.candidates.size < 120) composer.loadMore()
        refresh(resetPanel = true)
    }

    override fun onNeedMore() {
        if (composer.loadMore()) refresh()
    }

    override fun onPet(cat: Cat) {
        val view = keyboard ?: return
        val outcome = view.bar.pet(cat, prefs.simplified)
        if (outcome == CatAffection.Outcome.Happy) {
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
        val festival = Festivals.theme(java.time.LocalDate.now(), festivals, FestivalWardrobe(on = prefs.festivals))
        val festivalPill = festival?.let { Look.parseColor((if (dark) it.colors.dark else it.colors.light).lastOrNull()) }
        if (festival?.id != festivalArt?.theme?.id) festivalArt = festival?.let { FestivalArt.load(it, assets) }
        look = Look(dark, prefs.shape, prefs.palette, prefs.strength, prefs.cats, festivalPill, wantsMoreContrast(this))
        keyboard?.let {
            it.look = look
            it.bar.festival = festivalArt
            it.cats = prefs.cats
        }
        configureWindow()
    }

    private fun configureWindow() {
        val w = window?.window ?: return
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < 35) w.navigationBarColor = look.keyboardBottom
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
            InputMode.PINYIN -> if (composer.candidates.isNotEmpty()) t("選詞", "选词") else "原文＋空格"
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
            InputMode.ZHUYIN -> "注音 · $script"
        }
    }

    private fun refresh(resetPanel: Boolean = false) {
        val view = keyboard ?: return
        val (returnTitle, accent) = returnTitle()
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
            expanded = expanded && composer.composing,
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
        val visible = if (mode == InputMode.PINYIN && !english) CandidateMerge.collapsed(all) else all.indices.toList()
        val composing = composer.composing
        view.bar.update(CandidateBarView.Content(
            candidates = all,
            visible = visible,
            highlighted = composer.highlighted,
            predictions = if (composing) emptyList() else predictions,
            punctuation = if (!composing && predictions.isEmpty() && chineseActive && !loading) QUICK_MARKS else emptyList(),
            status = status(),
            expanded = expanded,
            canExpand = composing && all.isNotEmpty(),
            composing = composing,
            japaneseMarks = mode == InputMode.PINYIN,
            words = words,
        ))
        view.expanded = expanded && composing
        view.panel.words = words
        if (expanded && composing) view.panel.show(all, composer.highlighted, mode == InputMode.PINYIN, resetPanel)
    }

    companion object {
        private const val ZHUYIN_KEYS = "1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/-"
        private val QUICK_MARKS = listOf("，", "。", "？", "！", "、")
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
