package com.krysta1ll.meowpuut.android.engine

import kotlin.math.min

/** What an action did: whether it was used, and text to insert into the editor. */
data class Edit(val handled: Boolean, val commit: String = "")

/**
 * One composition over an engine session. Pinyin and Zhuyin go through the
 * Rust core; Japanese (romaji to kana) is handled here. All calls happen on
 * the main thread.
 */
class Composer {
    var japaneseWords: JapaneseWords = JapaneseWords.EMPTY

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
    var highlighted = 0
    var preedit = ""
        private set

    val composing get() = raw.isNotEmpty()
    val input get() = raw.toString()
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
            InputMode.PINYIN -> typePinyin(c)
            InputMode.ZHUYIN -> typeZhuyin(c)
            InputMode.JAPANESE -> typeJapanese(c)
        }
    }

    private fun typePinyin(c: Char): Edit {
        if (session == 0L) return Edit(false)
        val letter = c in 'a'..'z' || c in 'A'..'Z'
        val separator = c == '\'' && raw.isNotEmpty() && raw.last() != '\''
        if (!letter && !separator) return Edit(false)
        var pending = ""
        if (raw.length >= maxInput) {
            // The engine's limit: keep the letters typed so far, then start again.
            pending = raw.toString()
            resetCore()
            if (separator) return Edit(true, pending)
        }
        if (!NativeEngine.key(session, c.lowercaseChar().code)) return Edit(pending.isNotEmpty(), pending)
        raw.append(c)
        pending += NativeEngine.takeCommitted(session)
        syncRaw()
        rebuild()
        return Edit(true, pending)
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

    /**
     * Space while composing. Pinyin and Japanese choose the highlighted
     * candidate; Zhuyin types the first tone unless the reading already ends
     * in a tone, in which case it chooses.
     */
    fun space(): Edit {
        if (raw.isEmpty()) return Edit(false)
        return when (mode) {
            // A reading with no candidates is typed as its symbols rather than left stuck.
            InputMode.ZHUYIN -> if (raw.last() in TONES) chooseHighlighted() else typeZhuyin(' ')
            InputMode.PINYIN -> if (candidates.isNotEmpty()) chooseHighlighted() else Edit(true, takeLiteral() + " ")
            InputMode.JAPANESE -> chooseHighlighted()
        }
    }

    fun backspace(): Edit {
        if (raw.isEmpty()) return Edit(false)
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

    fun chooseHighlighted(): Edit = choose(highlighted.coerceIn(0, (candidates.size - 1).coerceAtLeast(0)))

    fun choose(index: Int): Edit {
        val c = candidates.getOrNull(index) ?: return if (raw.isEmpty()) Edit(false) else Edit(true, takeLiteral())
        when (c.kind) {
            CandidateKind.CHINESE -> {
                if (session == 0L || !NativeEngine.choose(session, c.coreIndex)) return Edit(false)
                val text = NativeEngine.takeCommitted(session)
                val rest = NativeEngine.input(session)
                if (rest.isEmpty()) raw.setLength(0) else keepSuffix(rest)
                rebuild()
                return Edit(true, text)
            }
            CandidateKind.MIXED_PREFIX -> {
                val rest = raw.substring(min(c.consumed, raw.length))
                resetCore()
                raw.append(rest)
                replay()
                rebuild()
                return Edit(true, c.text)
            }
            else -> {
                resetCore()
                return Edit(true, c.text)
            }
        }
    }

    /** The typed letters (or Zhuyin symbols) as they are; ends the composition. */
    fun takeLiteral(): String {
        val text = if (mode == InputMode.ZHUYIN) preedit else raw.toString()
        resetCore()
        return text
    }

    fun clear() = resetCore()

    /** Appends the next batch of the engine's candidates (for the expanded panel). */
    fun loadMore(batch: Int = 96): Boolean {
        if (session == 0L || coreFetched >= coreTotal) return false
        val more = fetchCore(coreFetched, batch)
        val seen = candidates.mapTo(HashSet()) { it.text }
        val added = more.filter { seen.add(it.text) }.map { Candidate(it.text, CandidateKind.CHINESE, it.index, it.consumed) }
        candidates = candidates + added
        return added.isNotEmpty()
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
        return texts.mapIndexed { i, text ->
            val consumed = details.getOrElse(i * 2) { 0 }
            val kind = details.getOrElse(i * 2 + 1) { 0 }
            CoreCandidate(text, start + i, consumed, kind == NativeEngine.KIND_EXACT)
        }
    }

    private fun rebuild() {
        highlighted = 0
        if (raw.isEmpty()) {
            candidates = emptyList()
            preedit = ""
            coreTotal = 0
            coreFetched = 0
            return
        }
        when (mode) {
            InputMode.PINYIN -> {
                preedit = raw.toString()
                coreTotal = NativeEngine.candidateCount(session)
                coreFetched = 0
                val chinese = fetchCore(0, FIRST_BATCH)
                val lower = raw.toString().lowercase()
                val reading = lower.replace("'", "")
                val japanese = japaneseWords.words(reading)
                val word = japanese.firstOrNull()
                val whole = if (word == null && reading.length >= 2) NativeEngine.mixedPinyin(session, lower, simplified).ifEmpty { null } else null
                val prefix = if (reading.length >= 3) {
                    NativeEngine.mixedPinyinPrefix(session, lower, simplified)?.let { it[0] to (it[1].toIntOrNull() ?: 0) }
                } else null
                candidates = CandidateMerge.pinyin(lower, raw.toString(), chinese, word, whole, prefix, Romaji.reading(reading), japanese.drop(1))
            }
            InputMode.ZHUYIN -> {
                preedit = NativeEngine.preedit(session)
                coreTotal = NativeEngine.candidateCount(session)
                coreFetched = 0
                candidates = fetchCore(0, FIRST_BATCH).map { Candidate(it.text, CandidateKind.CHINESE, it.index, it.consumed) }
                    .distinctBy { it.text }
            }
            InputMode.JAPANESE -> {
                val reading = Romaji.reading(raw.toString())
                preedit = reading
                val katakana = Romaji.katakana(reading)
                val list = ArrayList<Candidate>()
                japaneseWords.words(raw.toString().lowercase()).forEach { list += Candidate(it, CandidateKind.JAPANESE_WORD, consumed = raw.length) }
                list += Candidate(reading, CandidateKind.KANA, consumed = raw.length)
                list += Candidate(katakana, CandidateKind.KANA, consumed = raw.length)
                val typed = raw.toString()
                if (typed.any { it.isLetter() } && typed == typed.uppercase()) {
                    // All capitals asks for katakana first.
                    list.removeAll { it.text == katakana }
                    list.add(0, Candidate(katakana, CandidateKind.KANA, consumed = raw.length))
                }
                candidates = list.distinctBy { it.text }
            }
        }
    }

    companion object {
        private const val FIRST_BATCH = 48
        private const val TONES = " 6347"
        private val TONE_MARKS = mapOf('6' to "ˊ", '3' to "ˇ", '4' to "ˋ", '7' to "˙", ' ' to "ˉ")
    }
}
