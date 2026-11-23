package com.krysta1ll.meowpuut.android.engine

import android.content.Context
import android.graphics.Paint
import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors

/**
 * Loads one engine lexicon at a time off the main thread (Pinyin and Zhuyin
 * each come from the shared mimo_base.bin) and owns the session built on it.
 */
class Lexicons(private val context: Context) {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "meowpuut-lexicon") }
    private val main = Handler(Looper.getMainLooper())
    private var lexicon = 0L
    private var session = 0L
    private var loadedMode: InputMode? = null
    private var loadedMixed = true
    private var generation = 0

    @Volatile
    var japanese: JapaneseWords = JapaneseWords.EMPTY
        private set

    // The English word list (mimo_en.bin), mapped in place from the APK like mimo_base.bin. Opened on first
    // use (warmEnglish does it off the main thread beforehand) and used on the main thread only.
    private val englishLock = Any()
    private var englishSource: NativeEnglishSource? = null
    private var englishFailed = false
    private var englishClosed = false

    /**
     * Calls [ready] on the main thread with a session for [mode] (0 on failure
     * or for Japanese). The session handed out before is freed here, so the
     * caller detaches its composer first.
     */
    fun open(mode: InputMode, mixedJapanese: Boolean = true, ready: (Long) -> Unit) {
        val token = ++generation
        if (mode == InputMode.JAPANESE) {
            // Japanese needs only the word list; the Chinese lexicon is given back.
            release()
            worker.execute { loadJapanese(); main.post { if (token == generation) ready(0L) } }
            return
        }
        val pinyin = mode.pinyinBased
        if (session != 0L && loadedMode == mode && loadedMixed == mixedJapanese) {
            ready(session)
            return
        }
        worker.execute {
            if (mixedJapanese) loadJapanese()
            val started = SystemClock.elapsedRealtime()
            val lex = try {
                context.assets.openFd("lexicon/mimo_base.bin").use { fd ->
                    NativeEngine.lexiconFromFd(fd.parcelFileDescriptor.fd, fd.startOffset, fd.length, pinyin)
                }
            } catch (e: Exception) {
                Log.e(TAG, "lexicon unavailable", e)
                0L
            }
            var newSession = 0L
            if (lex != 0L) {
                // Without the word-pair table the engine still works and ranks by word weight only.
                val pairs = try {
                    context.assets.openFd("lexicon/mimo_pairs.bin").use { fd ->
                        NativeEngine.attachPairsFromFd(lex, fd.parcelFileDescriptor.fd, fd.startOffset, fd.length)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "word-pair table unavailable", e)
                    false
                }
                val trigrams = try {
                    context.assets.openFd("lexicon/mimo_trigrams.bin").use { fd ->
                        NativeEngine.attachTrigramsFromFd(lex, fd.parcelFileDescriptor.fd, fd.startOffset, fd.length)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "trigram table unavailable", e)
                    false
                }
                val assoc = try {
                    context.assets.openFd("lexicon/mimo_assoc.bin").use { fd ->
                        NativeEngine.attachAssocFromFd(lex, fd.parcelFileDescriptor.fd, fd.startOffset, fd.length)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "assoc table unavailable", e)
                    false
                }
                // English words inside pinyin and nine-key Chinese (after the Chinese readings); Zhuyin takes none.
                val english = pinyin && try {
                    context.assets.openFd("lexicon/mimo_en.bin").use { fd ->
                        NativeEngine.attachEnglishFromFd(lex, fd.parcelFileDescriptor.fd, fd.startOffset, fd.length)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "English word list unavailable for pinyin", e)
                    false
                }
                newSession = if (mode == InputMode.NINE_KEY) NativeEngine.nineKeySessionCreate(lex) else NativeEngine.sessionCreate(lex)
                var mixed = 0
                if (pinyin && mixedJapanese && newSession != 0L) {
                    // The engine keeps the readings it can use for mixed typing and refuses the rest.
                    for ((reading, text, weight) in japanese.best()) {
                        if (NativeEngine.addJapaneseWord(newSession, reading, text, weight)) mixed++
                    }
                }
                Log.i(TAG, "lexicon ${if (pinyin) "pinyin" else "zhuyin"} ready in ${SystemClock.elapsedRealtime() - started} ms, " +
                    "${NativeEngine.lexiconHeapBytes(lex) / 1024} KiB, word pairs ${if (pairs) "on" else "off"}, trigrams ${if (trigrams) "on" else "off"}, assoc ${if (assoc) "on" else "off"}, English ${if (english) "on" else "off"}, " +
                    "$mixed Japanese words for mixed typing")
            }
            main.post {
                if (token != generation) {
                    if (newSession != 0L) NativeEngine.sessionDestroy(newSession)
                    if (lex != 0L) NativeEngine.lexiconDestroy(lex)
                    return@post
                }
                release()
                lexicon = lex
                session = newSession
                loadedMode = if (newSession != 0L) mode else null
                loadedMixed = mixedJapanese
                ready(newSession)
            }
        }
    }

    /** The English word list, or null when it is missing or damaged. Call on the main thread. */
    fun english(): EnglishSource? = synchronized(englishLock) {
        if (englishSource == null && !englishFailed && !englishClosed) {
            val started = SystemClock.elapsedRealtime()
            englishSource = try {
                context.assets.openFd("lexicon/mimo_en.bin").use { NativeEnglishSource.open(it) }
            } catch (e: Exception) {
                Log.w(TAG, "English word list unavailable", e)
                null
            }
            englishFailed = englishSource == null
            Log.i(TAG, "English word list ${englishSource?.let { "${it.wordCount} words" } ?: "unavailable"} " +
                "in ${SystemClock.elapsedRealtime() - started} ms")
        }
        englishSource
    }

    /** Opens the English list off the main thread, so the first suggestion does not wait for it. */
    fun warmEnglish() {
        if (worker.isShutdown) return
        worker.execute { english() }
    }

    /** Gives the English list back (memory pressure, or the keyboard going away); the next use maps it again. */
    fun releaseEnglish() = synchronized(englishLock) {
        englishSource?.close()
        englishSource = null
        englishFailed = false
    }

    /** Frees the lexicon (memory pressure, or the keyboard going away). */
    fun release() {
        if (session != 0L) NativeEngine.sessionDestroy(session)
        if (lexicon != 0L) NativeEngine.lexiconDestroy(lexicon)
        session = 0L
        lexicon = 0L
        loadedMode = null
    }

    fun shutdown() {
        generation++
        release()
        worker.shutdown()
        synchronized(englishLock) { englishClosed = true }
        releaseEnglish()
    }

    private fun loadJapanese() {
        if (japanese.size > 0) return
        japanese = try {
            context.assets.open("lexicon/native-japanese.tsv").use { JapaneseWords.parse(it.readBytes().decodeToString()) }
        } catch (e: Exception) {
            Log.w(TAG, "Japanese word list unavailable", e)
            JapaneseWords.EMPTY
        }
        Log.i(TAG, "mimo-jp: ${japanese.size} readings")
    }

    /** Optional associations follow the initial session; typing need not wait. */
    fun loadSymbols(ready: (SymbolAssociations) -> Unit) {
        worker.execute {
            val started = SystemClock.elapsedRealtime()
            val symbols = try {
                val paint = Paint()
                context.assets.open("lexicon/associations.json").bufferedReader().use { reader ->
                    SymbolAssociations.parse(reader.readText()) { value ->
                        val emoji = if (value.codePointCount(0, value.length) == 1 &&
                            !UCharacter.hasBinaryProperty(value.codePointAt(0), UProperty.EMOJI_PRESENTATION)
                        ) value + '\uFE0F' else value
                        emoji.takeIf(paint::hasGlyph)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "emoji association table unavailable", e)
                SymbolAssociations.EMPTY
            }
            Log.i(TAG, "emoji association table ready in ${SystemClock.elapsedRealtime() - started} ms")
            main.post { if (!worker.isShutdown) ready(symbols) }
        }
    }

    companion object {
        private const val TAG = "Meowpuut"
    }
}
