package com.krysta1ll.meowpuut.android.app

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import com.krysta1ll.meowpuut.android.R
import com.krysta1ll.meowpuut.android.engine.CustomDictionary
import com.krysta1ll.meowpuut.android.engine.InputMode
import com.krysta1ll.meowpuut.android.ime.Prefs
import kotlin.math.roundToInt

internal class CustomDictionaryDialog(private val activity: Activity, private val prefs: Prefs) {
    fun show() {
        val entries = prefs.customDictionary
        val builder = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.dictionary_count, entries.size, CustomDictionary.MAX_ENTRIES))
            .setNegativeButton(android.R.string.cancel, null)
        if (entries.isEmpty()) {
            builder.setMessage(R.string.dictionary_empty)
        } else {
            val rows = entries.map { "${it.text}\n${activity.getString(modeLabel(it.mode))} · ${it.reading}" }
            builder.setItems(rows.toTypedArray()) { _, index -> edit(entries, index) }
        }
        if (entries.size < CustomDictionary.MAX_ENTRIES) {
            builder.setPositiveButton(R.string.dictionary_add) { _, _ -> edit(entries, null) }
        }
        builder.show()
    }

    private fun edit(entries: List<CustomDictionary.Entry>, index: Int?) {
        val existing = index?.let(entries::get)
        val modes = CustomDictionary.modes
        val mode = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, modes.map { activity.getString(modeLabel(it)) })
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(modes.indexOf(existing?.mode ?: prefs.mode).coerceAtLeast(0))
        }
        val reading = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            setSingleLine()
            setText(existing?.reading.orEmpty())
        }
        mode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                reading.error = null
                reading.setHint(readingHint(modes[position]))
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        val text = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            setSingleLine()
            setHint(R.string.dictionary_text)
            setText(existing?.text.orEmpty())
        }
        val padding = (24 * activity.resources.displayMetrics.density).roundToInt()
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(padding, 0, padding, 0)
            addView(mode)
            addView(reading)
            addView(text)
        }
        val builder = AlertDialog.Builder(activity)
            .setTitle(if (existing == null) R.string.dictionary_add else R.string.dictionary_edit)
            .setView(form)
            .setNegativeButton(android.R.string.cancel) { _, _ -> show() }
            .setPositiveButton(R.string.dictionary_save, null)
            .setOnCancelListener { show() }
        if (existing != null) builder.setNeutralButton(R.string.dictionary_delete, null)
        val dialog = builder.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val selectedMode = modes[mode.selectedItemPosition]
            val normalized = CustomDictionary.normalizeReading(selectedMode, reading.text.toString())
            val phrase = CustomDictionary.normalizeText(text.text.toString())
            if (normalized == null) {
                reading.error = activity.getString(R.string.dictionary_invalid_reading)
                return@setOnClickListener
            }
            if (phrase == null) {
                text.error = activity.getString(R.string.dictionary_invalid_text)
                return@setOnClickListener
            }
            val entry = CustomDictionary.Entry(selectedMode, normalized, phrase)
            if (entries.withIndex().any { it.index != index && it.value == entry }) {
                reading.error = activity.getString(R.string.dictionary_duplicate)
                return@setOnClickListener
            }
            prefs.customDictionary = if (index == null) entries + entry else entries.toMutableList().apply { set(index, entry) }
            dialog.dismiss()
            show()
        }
        if (index != null) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            AlertDialog.Builder(activity)
                .setMessage(R.string.dictionary_delete_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.dictionary_delete) { _, _ ->
                    prefs.customDictionary = entries.toMutableList().apply { removeAt(index) }
                    dialog.dismiss()
                    show()
                }
                .show()
        }
    }

    private fun modeLabel(mode: InputMode) = when (mode) {
        InputMode.PINYIN, InputMode.NINE_KEY -> R.string.dictionary_pinyin
        InputMode.ZHUYIN -> R.string.mode_zhuyin
        InputMode.JAPANESE -> R.string.mode_japanese
    }

    private fun readingHint(mode: InputMode) = when (mode) {
        InputMode.PINYIN, InputMode.NINE_KEY -> R.string.dictionary_reading_pinyin
        InputMode.ZHUYIN -> R.string.dictionary_reading_zhuyin
        InputMode.JAPANESE -> R.string.dictionary_reading_japanese
    }
}
