package io.github.manuelrtsch.sideplay

import android.content.Context
import android.content.SharedPreferences
import hrln.interactive.sidekit.design.input.T9Mode

/**
 * Sideplay's standalone settings, used when no compatible Sidekit host is installed.
 *
 * The theme store uses the same file for case colour and compact layout. The input mode lives
 * here because it belongs to Sideplay's own shell, not to the SDK's theme contract.
 */
internal class SideplayLocalSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var inputMode: T9Mode
        get() = T9Mode.entries.firstOrNull { it.name == prefs.getString(KEY_INPUT_MODE, null) } ?: T9Mode.MultiTap
        set(value) {
            prefs.edit().putString(KEY_INPUT_MODE, value.name).apply()
        }

    fun observeInputMode(onChange: () -> Unit): () -> Unit {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == KEY_INPUT_MODE) onChange()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    /**
     * Standalone Sideplay starts in the compact layout. Only writes the default when the choice
     * has never been saved, so a user who turned compact off keeps that decision.
     */
    fun applyStandaloneThemeDefaults() {
        if (prefs.contains(KEY_COMPACT_LAYOUT)) return
        prefs.edit().putBoolean(KEY_COMPACT_LAYOUT, true).apply()
    }

    companion object {
        const val FILE = "sideplay_settings"
        const val KEYPAD_FILE = "sideplay_keypad"
        private const val KEY_INPUT_MODE = "input_mode"

        // Must match the key SharedPreferencesSidekitThemeStore reads and writes.
        private const val KEY_COMPACT_LAYOUT = "compact_layout"
    }
}
