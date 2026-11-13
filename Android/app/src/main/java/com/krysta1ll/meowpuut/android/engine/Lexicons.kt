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
    private var generation = 0

    @Volatile
    var japanese: JapaneseWords = JapaneseWords.EMPTY
        private set

    /**
     * Calls [ready] on the main thread with a session for [mode] (0 on failure
     * or for Japanese). The session handed out before is freed here, so the
     * caller detaches its composer first.
     */
    fun open(mode: InputMode, ready: (Long) -> Unit) {
        val token = ++generation
        if (mode == InputMode.JAPANESE) {
            // Japanese needs only the word list; the Chinese lexicon is given back.
            release()
            worker.execute { loadJapanese(); main.post { if (token == generation) ready(0L) } }
            return
        }
        val pinyin = mode.pinyinBased
        if (session != 0L && loadedMode == mode) {
            ready(session)
            return
        }
        worker.execute {
            loadJapanese()
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
                newSession = if (mode == InputMode.NINE_KEY) NativeEngine.nineKeySessionCreate(lex) else NativeEngine.sessionCreate(lex)
                var mixed = 0
                if (pinyin && newSession != 0L) {
                    // The engine keeps the readings it can use for mixed typing and refuses the rest.
                    for ((reading, text, weight) in japanese.best()) {
                        if (NativeEngine.addJapaneseWord(newSession, reading, text, weight)) mixed++
                    }
                }
                Log.i(TAG, "lexicon ${if (pinyin) "pinyin" else "zhuyin"} ready in ${SystemClock.elapsedRealtime() - started} ms, " +
                    "${NativeEngine.lexiconHeapBytes(lex) / 1024} KiB, word pairs ${if (pairs) "on" else "off"}, " +
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
                ready(newSession)
            }
        }
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
