package com.krysta1ll.meowpuut.android.ime

import java.io.File

/**
 * What was copied lately, kept on this device only (nothing here touches android.*; the keyboard feeds it and the JVM tests drive it).
 *
 * Unpinned items live in memory: newest first, at most [MAX_ITEMS], gone after [LIFETIME_MS]; the same text copied again goes back
 * to the top. Pinned items never expire and are the only thing written to disk ([file], in the app's no-backup directory): the
 * file is never synced, never logged, never sent anywhere. Longer text than [MAX_CHARS] is kept cut to that. A sensitive clip
 * (a password, a card number) is never stored at all.
 */
class ClipboardShelf(private val file: File? = null, private val now: () -> Long = System::currentTimeMillis) {
    data class Item(val text: String, val time: Long, val pinned: Boolean)

    private val unpinned = ArrayList<Item>()       // newest first
    private val pinned = ArrayList<Item>()         // newest pin first

    init { load() }

    /** Pinned first, then the rest newest first; whatever has expired is dropped first. */
    fun items(): List<Item> {
        prune()
        return pinned + unpinned
    }

    val isEmpty: Boolean get() = items().isEmpty()

    /** Takes a copied [text]; false when it is not kept (empty, or [sensitive]). */
    fun add(text: String, sensitive: Boolean = false, time: Long = now()): Boolean {
        if (sensitive || text.isBlank()) return false
        val kept = text.take(MAX_CHARS)
        prune()
        val at = pinned.indexOfFirst { it.text == kept }
        if (at >= 0) {
            pinned[at] = pinned[at].copy(time = time)
            return true
        }
        unpinned.removeAll { it.text == kept }
        unpinned.add(0, Item(kept, time, false))
        while (unpinned.size > MAX_ITEMS) unpinned.removeAt(unpinned.lastIndex)
        return true
    }

    fun pin(text: String) {
        val at = unpinned.indexOfFirst { it.text == text }
        if (at < 0) return
        pinned.add(0, unpinned.removeAt(at).copy(pinned = true))
        save()
    }

    fun unpin(text: String) {
        val at = pinned.indexOfFirst { it.text == text }
        if (at < 0) return
        val item = pinned.removeAt(at).copy(pinned = false)
        unpinned.add(item)
        unpinned.sortByDescending { it.time }
        while (unpinned.size > MAX_ITEMS) unpinned.removeAt(unpinned.lastIndex)
        save()
    }

    fun remove(text: String) {
        val wasPinned = pinned.removeAll { it.text == text }
        unpinned.removeAll { it.text == text }
        if (wasPinned) save()
    }

    /** Forgets everything, the pinned file included. */
    fun clear() {
        unpinned.clear()
        pinned.clear()
        deleteFile(file)
    }

    private fun prune() {
        val limit = now() - LIFETIME_MS
        unpinned.removeAll { it.time <= limit }
    }

    private fun save() {
        val target = file ?: return
        if (pinned.isEmpty()) { deleteFile(target); return }
        try {
            val out = StringBuilder()
            for (item in pinned) out.append(item.time).append(' ').append(item.text.length).append('\n').append(item.text).append('\n')
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.writeText(out.toString(), Charsets.UTF_8)
            if (!temp.renameTo(target)) { target.delete(); temp.renameTo(target) }
        } catch (_: Exception) {
            // The pins stay in memory; the next change tries again.
        }
    }

    private fun load() {
        val source = file?.takeIf { it.isFile } ?: return
        try {
            val text = source.readText(Charsets.UTF_8)
            var at = 0
            while (at < text.length) {
                val eol = text.indexOf('\n', at)
                if (eol < 0) break
                val head = text.substring(at, eol).split(' ')
                val time = head.getOrNull(0)?.toLongOrNull()
                val length = head.getOrNull(1)?.toIntOrNull()
                if (time == null || length == null || length < 0 || length > MAX_CHARS || eol + 1 + length > text.length) break
                val body = text.substring(eol + 1, eol + 1 + length)
                if (body.isNotBlank() && pinned.none { it.text == body } && pinned.size < MAX_PINNED) pinned += Item(body, time, true)
                at = eol + 1 + length + 1
            }
        } catch (_: Exception) {
            pinned.clear()
        }
    }

    companion object {
        const val MAX_ITEMS = 20
        const val MAX_PINNED = 50
        const val MAX_CHARS = 5000
        const val LIFETIME_MS = 60L * 60 * 1000
        const val FILE_NAME = "clipboard-pinned.txt"

        /** Removes the pinned file without opening the shelf (the settings app does it when the switch goes off). */
        fun deleteFile(file: File?) {
            try { file?.delete(); file?.let { File(it.parentFile, it.name + ".tmp").delete() } } catch (_: Exception) { }
        }
    }
}

/**
 * The slip: the clip copied a moment ago, offered once as the first item of the suggestion row (see CandidateBarView). Pure state, so
 * the rule can be tested: a clip no older than [WINDOW_MS], offered once, never in a password field or one that is not to be learned
 * from, and gone when typing starts. A sensitive clip is shown as dots.
 */
class ClipboardSlip {
    class Clip(val text: String, val time: Long, val sensitive: Boolean) {
        /** What the slip shows: the text on one line, or dots when it is sensitive. */
        val shown: String get() = if (sensitive) MASK else text.trim().replace(Regex("\\s+"), " ")
    }

    private var clip: Clip? = null
    private var presented: Clip? = null
    private var seen: Pair<String, Long>? = null

    /** A clip was copied (true), or only seen again (false): it replaces the one before, and has not been offered. The same clip seen again (the keyboard reads the clipboard whenever it opens) is not new. */
    fun copied(text: String, time: Long, sensitive: Boolean): Boolean {
        if (text.isBlank() || seen == text to time) return false
        seen = text to time
        clip = Clip(text.take(ClipboardShelf.MAX_CHARS), time, sensitive)
        presented = null
        return true
    }

    /** The keyboard is up in a field: the slip is put forward if it may be. [fieldAllowed]: not a password, not a no-learning field. */
    fun present(now: Long, fieldAllowed: Boolean) {
        val c = clip ?: return
        if (!fieldAllowed || now - c.time > WINDOW_MS || now < c.time - WINDOW_MS) return
        presented = c
    }

    /** The slip to show now, or null. */
    fun visible(now: Long, composing: Boolean, fieldAllowed: Boolean): Clip? {
        val c = presented ?: return null
        if (composing || !fieldAllowed || now - c.time > WINDOW_MS) return null
        return c
    }

    /** Milliseconds until the slip on show runs out, or null when none is. */
    fun remaining(now: Long): Long? = presented?.let { (WINDOW_MS - (now - it.time)).coerceAtLeast(0) }

    /** Tapped, typing began, or the field was left: it is not offered again. */
    fun dismiss() {
        presented = null
        clip = null
    }

    /** Everything is forgotten (the clipboard was switched off). */
    fun forget() {
        dismiss()
        seen = null
    }

    val isPresented: Boolean get() = presented != null

    companion object {
        const val WINDOW_MS = 3L * 60 * 1000
        const val MASK = "••••••"
    }
}

/** Where the clipboard is used. */
object ClipboardRules {
    /** Not in a password field, and not where the editor asked not to be learned from. */
    fun fieldAllowed(inputType: Int, imeOptions: Int): Boolean {
        val textClass = 0x1
        val numberClass = 0x2
        val klass = inputType and 0xf
        val variation = inputType and 0xff0
        val password = when (klass) {
            textClass -> variation == 0x80 || variation == 0xe0 || variation == 0x90     // password, web password, visible password
            numberClass -> variation == 0x10                                             // number password
            else -> false
        }
        val noLearning = imeOptions and 0x1000000 != 0                                   // IME_FLAG_NO_PERSONALIZED_LEARNING
        return !password && !noLearning
    }

    const val SENSITIVE_EXTRA = "android.content.extra.IS_SENSITIVE"
}
