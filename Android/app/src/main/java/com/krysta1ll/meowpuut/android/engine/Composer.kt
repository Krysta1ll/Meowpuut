package com.krysta1ll.meowpuut.android.engine

import kotlin.math.min

/** What an action did: whether it was used, and text to insert into the editor. */
data class Edit(val handled: Boolean, val commit: String = "", val confirmedReading: String = "")

/**
 * One composition over an engine session. Pinyin and Zhuyin go through the
 * Rust core; Japanese (romaji to kana) is handled here. All calls happen on
 * the main thread.
 */
class Composer {
    internal var choiceMemory: ChoiceMemory? = null
    internal var customDictionary = emptyList<CustomDictionary.Entry>()
        set(value) {
            if (field == value) return
            field = value
            refreshChoices()
        }
    internal var allowCustomEntries = false
    var rememberChoices = false
        set(value) {
            if (field == value) return
            field = value
            refreshChoices()
        }
    var japaneseWords: JapaneseWords = JapaneseWords.EMPTY

    /**
     * Whether Zhuyin, Pinyin and nine-key may show Japanese. Off: no paw
     * candidates, no kana, no mixed sentence; the Japanese mode is unaffected.
     */
    var mixedJapanese = true
        set(value) {
            if (field == value) return
            field = value
            if (session != 0L && raw.isNotEmpty()) rebuild()
        }
    var symbolsAllowed = true
    var symbols: SymbolAssociations = SymbolAssociations.EMPTY
        set(value) {
            if (field === value) return
            field = value
            if (!symbolsAllowed) return
            val selection = candidates.getOrNull(highlighted)
            candidates = CandidateMerge.withSymbols(candidates, value, raw.length)
            appendZhuyinLiteral()
            highlighted = selection?.let(candidates::indexOf)?.coerceAtLeast(0) ?: 0
        }

    var mode: InputMode = InputMode.ZHUYIN
        private set

    var simplified = false
        set(value) {
            if (field == value) return
            field = value
            if (session != 0L) {
                NativeEngine.setSimplified(session, value)
                if (raw.isNotEmpty()) rebuild()
            }
        }

    private var session = 0L
    private var maxInput = 64
    private val raw = StringBuilder()
    private var coreTotal = 0
    private var coreFetched = 0

    /** Every choice for the current composition, in display order. */
    var candidates: List<Candidate> = emptyList()
        private set
    var nineKeySyllables: List<String> = emptyList()
        private set
    var highlighted = 0
    var preedit = ""
        private set

    val composing get() = raw.isNotEmpty()
    val input get() = raw.toString()
    val segmentLength get() = if (mode == InputMode.JAPANESE) 0 else raw.length
    val canSeparateNineKey get() = raw.lastOrNull()?.let { it in '2'..'9' || it in 'A'..'Z' } == true && raw.length < maxInput
    val moreAvailable get() = coreFetched < coreTotal

    /** Japanese needs no lexicon; Chinese waits for the engine session. */
    val ready get() = mode == InputMode.JAPANESE || session != 0L

    fun attach(mode: InputMode, session: Long) {
        // The previous session may already be freed: forget it before clearing.
        this.session = 0L
        clear()
        this.mode = mode
        this.session = if (mode == InputMode.JAPANESE) 0L else session
        if (this.session != 0L) {
            maxInput = NativeEngine.maxInputBytes().coerceAtLeast(8)
            NativeEngine.setSimplified(this.session, simplified)
        }
    }

    fun detach() {
        clear()
        session = 0L
    }

    fun type(code: Int): Edit {
        val c = code.toChar()
        return when (mode) {
            InputMode.PINYIN, InputMode.NINE_KEY -> typePinyin(c)
            InputMode.ZHUYIN -> typeZhuyin(c)
            InputMode.JAPANESE -> typeJapanese(c)
        }
    }

    private fun typePinyin(c: Char): Edit {
        if (session == 0L) return Edit(false)
        val nineKey = mode == InputMode.NINE_KEY
        val compositionKey = if (nineKey) c in '2'..'9' || c in 'A'..'Z' else c in 'a'..'z' || c in 'A'..'Z'
        val separator = c == '\'' && if (nineKey) canSeparateNineKey else raw.isNotEmpty() && raw.last() != '\''
        if (!compositionKey && !separator) return Edit(false)
        var pending = ""
        if (raw.length >= maxInput) {
            // The engine's limit: keep the letters typed so far, then start again.
            pending = rawLiteral()
            resetCore()
            if (separator) return Edit(true, pending)
        }
        val keyChar = if (nineKey) c else c.lowercaseChar()
        if (!NativeEngine.key(session, keyChar.code)) return Edit(pending.isNotEmpty(), pending)
        raw.append(c)
        pending += NativeEngine.takeCommitted(session)
        syncRaw()
        rebuild()
        return Edit(true, pending)
    }

    fun typePinyinTone(tone: Char): Edit {
        if (mode != InputMode.PINYIN || session == 0L || tone !in '1'..'5') return Edit(false)
        val last = raw.lastOrNull() ?: return Edit(false)
        val replacesTone = last in '1'..'5'
        if (last == tone) return Edit(true)
        if (replacesTone) {
            if (!NativeEngine.key(session, NativeEngine.KEY_BACKSPACE)) return Edit(false)
        } else if (raw.length >= maxInput || last.lowercaseChar() !in 'a'..'z') return Edit(false)
        if (!NativeEngine.key(session, tone.code)) {
            if (replacesTone) NativeEngine.key(session, last.code)
            return Edit(false)
        }
        if (replacesTone) raw.setLength(raw.length - 1)
        raw.append(tone)
        syncRaw()
        rebuild()
        return Edit(true)
    }

    /** Locks a Pinyin syllable at the start of the first unlocked nine-key digit span. */
    fun lockNineKeySyllable(syllable: String): Boolean {
        if (mode != InputMode.NINE_KEY || raw.isEmpty()) return false
        val next = NineKeySyllables.lock(syllable, raw.toString()) ?: return false
        if (next.length > maxInput) return false
        raw.setLength(0)
        raw.append(next)
        if (session != 0L) {
            replay()
            syncRaw()
        }
        rebuild()
        return true
    }

    private fun typeZhuyin(c: Char): Edit {
        if (session == 0L) return Edit(false)
        var pending = ""
        if (raw.length >= maxInput) {
            val tone = TONE_MARKS[c]
            if (tone != null) {
                // A tone completes the old reading instead of starting a new one.
                if (raw.last() in TONES) return Edit(true)
                val text = preedit + tone
                resetCore()
                return Edit(true, text)
            }
            pending = preedit
            resetCore()
        }
        if (!NativeEngine.key(session, c.code)) return Edit(pending.isNotEmpty(), pending)
        raw.append(c)
        pending += NativeEngine.takeCommitted(session)
        syncRaw()
        rebuild()
        return Edit(true, pending)
    }

    private fun typeJapanese(c: Char): Edit {
        if (!(c in 'a'..'z' || c in 'A'..'Z' || c == '-' || c == '\'')) return Edit(false)
        if (raw.length >= 80) return Edit(true)
        raw.append(c)
        rebuild()
        return Edit(true)
    }

    internal fun acceptsJapaneseReadingMark(mark: Char): Boolean {
        if (mark == '-' && composing) return true
        if (!allowCustomEntries) return false
        val prefix = input.lowercase() + mark
        return customDictionary.any { it.mode == InputMode.JAPANESE && it.reading.startsWith(prefix) }
    }

    /**
     * Space while composing. Pinyin and Japanese choose the highlighted
     * candidate; Zhuyin types the first tone unless the reading already ends
     * in a tone, in which case it chooses.
     */
    fun space(): Edit {
        if (raw.isEmpty()) return Edit(false)
        return when (mode) {
            // A reading with no candidates is typed as its symbols rather than left stuck.
            InputMode.ZHUYIN -> if (raw.last() in TONES) chooseHighlighted(confirm = true) else typeZhuyin(' ')
            InputMode.PINYIN, InputMode.NINE_KEY -> if (candidates.isNotEmpty()) chooseHighlighted(confirm = true) else Edit(true, takeLiteral() + " ")
            InputMode.JAPANESE -> chooseHighlighted()
        }
    }

    fun backspace(): Edit {
        if (raw.isEmpty()) return Edit(false)
        if (mode == InputMode.NINE_KEY) {
            val unlocked = NineKeySyllables.unlockLastOnBackspace(raw.toString())
            if (unlocked != null) {
                raw.setLength(0)
                raw.append(unlocked)
                if (session != 0L) {
                    replay()
                    syncRaw()
                }
                rebuild()
                return Edit(true)
            }
        }
        raw.deleteCharAt(raw.length - 1)
        if (mode != InputMode.JAPANESE && session != 0L) {
            if (raw.isEmpty()) {
                NativeEngine.clear(session)
            } else {
                // The core pops one key; replay only if it ever disagrees.
                NativeEngine.key(session, NativeEngine.KEY_BACKSPACE)
                NativeEngine.takeCommitted(session)
                if (NativeEngine.input(session) != coreText(raw)) replay()
            }
        }
        rebuild()
        return Edit(true)
    }

    fun chooseHighlighted(confirm: Boolean = false): Edit =
        choose(highlighted.coerceIn(0, (candidates.size - 1).coerceAtLeast(0)), confirm)

    fun choose(index: Int, confirm: Boolean = false): Edit {
        val c = candidates.getOrNull(index) ?: return if (raw.isEmpty()) Edit(false) else Edit(true, takeLiteral())
        if (mode == InputMode.ZHUYIN && c.kind == CandidateKind.LITERAL) return Edit(true, takeLiteral())
        if (c.coreIndex >= 0) {
            val reading = if (confirm && rememberChoices) ChoiceMemory.selectionReading(raw, c) else ""
            if (session == 0L || !NativeEngine.choose(session, c.coreIndex)) return Edit(false)
            val text = NativeEngine.takeCommitted(session)
            keepSuffix(NativeEngine.input(session))
            rebuild()
            return Edit(true, text, reading)
        }
        if (c.kind == CandidateKind.MIXED_PREFIX) {
            val rest = raw.substring(min(c.consumed, raw.length))
            resetCore()
            raw.append(rest)
            replay()
            rebuild()
        } else {
            resetCore()
        }
        return Edit(true, c.text)
    }

    /** The typed letters (or Zhuyin as written, ㄋㄧˇㄏㄠˇ) as they are; ends the composition. */
    fun takeLiteral(): String {
        val text = if (mode == InputMode.ZHUYIN && session != 0L) NativeEngine.inputText(session) else rawLiteral()
        resetCore()
        return text
    }

    private fun rawLiteral(): String =
        if (mode == InputMode.NINE_KEY) raw.filterNot { it == '\'' }.toString().lowercase() else raw.toString()

    fun clear() = resetCore()

    fun refreshChoices() {
        if (composing) rebuild()
    }

    /** Appends the next batch of the engine's candidates. */
    fun loadMore(batch: Int = 96): Boolean {
        if (session == 0L || coreFetched >= coreTotal) return false
        val nineKey = mode == InputMode.NINE_KEY
        val custom = CandidateMerge.customTexts(candidates)
        val seen = candidates.asSequence().filter { !nineKey || it.text in custom }
            .mapTo(HashSet(), Candidate::selection)
        while (coreFetched < coreTotal) {
            val start = coreFetched
            val added = fetchCore(start, batch).filter {
                val matchesCustom = it.text in custom
                (!matchesCustom || it.consumed in 1 until raw.length) &&
                    ((nineKey && !matchesCustom) || seen.add(it.text to it.consumed))
            }.map(CoreCandidate::choice)
            if (added.isNotEmpty()) {
                candidates = candidates + added
                appendZhuyinLiteral()
                return true
            }
            if (coreFetched <= start) break
        }
        return appendZhuyinLiteral()
    }

    fun predictions(context: String, limit: Int = 8): List<String> {
        if (session == 0L || mode == InputMode.JAPANESE || context.isBlank()) return emptyList()
        return NativeEngine.predict(session, context.takeLast(8), limit).filter { it.isNotEmpty() }
    }

    private fun resetCore() {
        raw.setLength(0)
        if (session != 0L) {
            NativeEngine.clear(session)
            NativeEngine.takeCommitted(session)
        }
        candidates = emptyList()
        nineKeySyllables = emptyList()
        preedit = ""
        highlighted = 0
        coreTotal = 0
        coreFetched = 0
    }

    private fun coreText(text: CharSequence) = if (mode == InputMode.PINYIN) text.toString().lowercase() else text.toString()

    private fun replay() {
        if (session == 0L) return
        NativeEngine.clear(session)
        for (ch in raw) NativeEngine.key(session, coreText(ch.toString())[0].code)
        NativeEngine.takeCommitted(session)
    }

    /** Keep the typed case for letters the engine still holds. */
    private fun keepSuffix(rest: String) {
        val typed = raw.toString()
        val suffix = if (rest.length <= typed.length && typed.takeLast(rest.length).lowercase() == rest.lowercase()) typed.takeLast(rest.length) else rest
        raw.setLength(0)
        raw.append(suffix)
    }

    private fun syncRaw() {
        val core = NativeEngine.input(session)
        if (core != coreText(raw)) keepSuffix(core)
    }

    private fun fetchCore(start: Int, count: Int): List<CoreCandidate> {
        val n = min(count, coreTotal - start)
        if (n <= 0) return emptyList()
        val texts = NativeEngine.candidates(session, start, n)
        val details = NativeEngine.candidateDetails(session, start, n)
        coreFetched = start + texts.size
        return texts.mapIndexedNotNull { i, text ->
            val consumed = details.getOrElse(i * 2) { 0 }
            val kind = details.getOrElse(i * 2 + 1) { 0 }
            // A session built while mixing was on can outlive the switch for a moment.
            if (kind == NativeEngine.KIND_JAPANESE && !mixedJapanese) null
            else CoreCandidate(text, start + i, consumed, kind == NativeEngine.KIND_EXACT, kind == NativeEngine.KIND_JAPANESE)
        }
    }

    private fun firstCoreBatch(preferred: Map<String, Int>): List<CoreCandidate> {
        coreTotal = NativeEngine.candidateCount(session)
        coreFetched = 0
        val first = fetchCore(0, FIRST_BATCH)
        if (coreFetched >= coreTotal || preferred.keys.all { text ->
                first.any { it.text == text && it.consumed == raw.length && !it.japanese }
            }) return first
        // A word picked from a later page can still move to the front (capped to avoid unbounded main-thread JNI churn).
        val extra = min(256, coreTotal) - coreFetched
        return if (extra > 0) first + fetchCore(coreFetched, extra) else first
    }

    /** The Japanese words for a Pinyin reading: none while mixed typing is off. */
    private fun mixedWords(reading: String): List<String> = if (mixedJapanese) japaneseWords.words(reading) else emptyList()

    private fun appendZhuyinLiteral(): Boolean {
        if (mode != InputMode.ZHUYIN || moreAvailable || raw.isEmpty() || session == 0L) return false
        val next = CandidateMerge.withZhuyinLiteral(candidates, NativeEngine.inputText(session), raw.length)
        if (next === candidates) return false
        candidates = next
        return true
    }

    private fun rebuild() {
        highlighted = 0
        if (raw.isEmpty()) {
            candidates = emptyList()
            nineKeySyllables = emptyList()
            preedit = ""
            coreTotal = 0
            coreFetched = 0
            return
        }
        nineKeySyllables = if (mode == InputMode.NINE_KEY) NineKeySyllables.leading(raw.toString()) else emptyList()
        if (session == 0L && mode != InputMode.JAPANESE) {
            candidates = emptyList()
            preedit = ""
            return
        }
        val preferred = if (rememberChoices && mode != InputMode.JAPANESE) {
            choiceMemory?.preferred(mode, simplified, raw.toString().trimEnd('\'')).orEmpty()
        } else emptyMap()
        val core = if (mode == InputMode.JAPANESE) emptyList() else firstCoreBatch(preferred)
        when (mode) {
            InputMode.PINYIN -> {
                preedit = raw.toString()
                val lower = raw.toString().lowercase()
                val reading = lower.replace("'", "")
                val mixing = mixedJapanese && reading.none { it in '1'..'5' }
                val japanese = if (mixing) mixedWords(reading) else emptyList()
                val word = japanese.firstOrNull()
                val whole = if (mixing && word == null && reading.length >= 2) NativeEngine.mixedPinyin(session, lower, simplified).ifEmpty { null } else null
                val prefix = if (mixing && reading.length >= 3) {
                    NativeEngine.mixedPinyinPrefix(session, lower, simplified)?.let { it[0] to (it[1].toIntOrNull() ?: 0) }
                } else null
                candidates = CandidateMerge.pinyin(lower, raw.toString(), core, word, whole, prefix, if (mixing) Romaji.reading(reading) else null, japanese.drop(1))
            }
            InputMode.ZHUYIN, InputMode.NINE_KEY -> {
                preedit = NativeEngine.preedit(session)
                val choices = core.map(CoreCandidate::choice)
                candidates = if (mode == InputMode.NINE_KEY) choices else choices.distinctBy(Candidate::selection)
            }
            InputMode.JAPANESE -> {
                val typed = raw.toString()
                // Leave a final single n pending in the editor, with kana available in the list.
                preedit = Romaji.reading(typed, editing = true)
                val reading = if (preedit.endsWith('n')) preedit.dropLast(1) + 'ん' else preedit
                val katakana = Romaji.katakana(reading)
                val list = ArrayList<Candidate>()
                japaneseWords.words(typed.lowercase()).forEach { list += Candidate(it, CandidateKind.JAPANESE_WORD, consumed = raw.length) }
                list += Candidate(reading, CandidateKind.KANA, consumed = raw.length)
                list += Candidate(katakana, CandidateKind.KANA, consumed = raw.length)
                if (typed.any { it.isLetter() } && typed == typed.uppercase()) {
                    // All capitals asks for katakana first.
                    list.removeAll { it.text == katakana }
                    list.add(0, Candidate(katakana, CandidateKind.KANA, consumed = raw.length))
                }
                candidates = list.distinctBy { it.text }
            }
        }
        candidates = ChoiceMemory.rank(candidates, raw.length, preferred)
        if (allowCustomEntries && customDictionary.isNotEmpty()) {
            val input = if (mode == InputMode.ZHUYIN) NativeEngine.inputText(session) else raw.toString()
            val matches = CustomDictionary.matches(customDictionary, mode, input)
            candidates = CandidateMerge.withCustom(candidates, matches, raw.length)
        }
        if (symbolsAllowed) candidates = CandidateMerge.withSymbols(candidates, symbols, raw.length)
        appendZhuyinLiteral()
    }

    companion object {
        private const val FIRST_BATCH = 48
        private const val TONES = " 6347"
        private val TONE_MARKS = mapOf('6' to "ˊ", '3' to "ˇ", '4' to "ˋ", '7' to "˙", ' ' to "ˉ")
    }
}
