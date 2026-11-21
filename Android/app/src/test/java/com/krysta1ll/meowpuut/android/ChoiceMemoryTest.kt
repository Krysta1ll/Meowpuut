package com.krysta1ll.meowpuut.android

import android.content.SharedPreferences
import com.krysta1ll.meowpuut.android.engine.ChoiceMemory
import com.krysta1ll.meowpuut.android.engine.InputMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each test failed on Prism 26A023. */
class ChoiceMemoryTest {
    @Test
    fun fullReadingStillLearnsANewWord() {
        val memory = ChoiceMemory(MemoryPrefs())
        val learned = listOf("是", "時", "事", "市", "式", "試")
        for (text in learned) repeat(2) { memory.remember(InputMode.PINYIN, false, "shi", text) }
        repeat(2) { memory.remember(InputMode.PINYIN, false, "shi", "室") }
        assertEquals(2, memory.preferred(InputMode.PINYIN, false, "shi")["室"])
        assertEquals(6, memory.preferred(InputMode.PINYIN, false, "shi").size)
    }

    @Test
    fun learnedWordsOutliveSentencesCommittedOnce() {
        val prefs = MemoryPrefs()
        val memory = ChoiceMemory(prefs)
        repeat(2) { memory.remember(InputMode.PINYIN, false, "nihao", "妳好") }
        // Every sentence taken with Space stores its whole reading once.
        for (i in 0 until 1000) memory.remember(InputMode.PINYIN, false, "juzi$i", "句子")
        assertEquals(mapOf("妳好" to 2), memory.preferred(InputMode.PINYIN, false, "nihao"))
        assertTrue(prefs.all.size <= 769)
    }

    @Test
    fun nineKeyPickWithLockedSyllableCountsForTheDigits() {
        val memory = ChoiceMemory(MemoryPrefs())
        memory.remember(InputMode.NINE_KEY, false, "WANG'", "王")
        memory.remember(InputMode.NINE_KEY, false, "9264", "王")
        assertEquals(mapOf("王" to 2), memory.preferred(InputMode.NINE_KEY, false, "9264"))
        assertEquals(mapOf("王" to 2), memory.preferred(InputMode.NINE_KEY, false, "WANG'"))
        // Pinyin keeps its letters.
        memory.remember(InputMode.PINYIN, false, "wang", "王")
        assertEquals(emptyMap<String, Int>(), memory.preferred(InputMode.PINYIN, false, "9264"))
    }
}

/** SharedPreferences in memory; apply() and commit() write at once. */
private class MemoryPrefs : SharedPreferences {
    private val values = LinkedHashMap<String, Any?>()
    override fun getAll(): Map<String, *> = LinkedHashMap(values)
    override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
    override fun getInt(key: String, defValue: Int) = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long) = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float) = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
    override fun contains(key: String) = key in values
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = LinkedHashMap<String, Any?>()
        private val removals = HashSet<String>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { changes[key] = values }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value }
        override fun putLong(key: String, value: Long) = apply { changes[key] = value }
        override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
        override fun remove(key: String) = apply { removals += key }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clear) values.clear()
            removals.forEach(values::remove)
            values.putAll(changes)
        }
    }
}
