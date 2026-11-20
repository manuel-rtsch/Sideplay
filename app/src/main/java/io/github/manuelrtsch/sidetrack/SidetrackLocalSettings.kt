package io.github.manuelrtsch.sidetrack

import android.content.Context
import android.content.SharedPreferences
import hrln.interactive.sidekit.design.input.T9Mode

/**
 * Sidetrack's standalone settings, used when no compatible Sidekit host is installed.
 *
 * The theme store uses the same file for case colour and compact layout. The input mode lives
 * here because it belongs to Sidetrack's own shell, not to the SDK's theme contract.
 */
internal class SidetrackLocalSettings(context: Context) {
    private val context = context.applicationContext
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var inputMode: T9Mode
        get() = T9Mode.entries.firstOrNull { it.name == prefs.getString(KEY_INPUT_MODE, null) } ?: T9Mode.MultiTap
        set(value) {
            prefs.edit().putString(KEY_INPUT_MODE, value.name).apply()
        }

    fun observeInputMode(onChange: () -> Unit): () -> Unit = observe(KEY_INPUT_MODE, onChange)

    /**
     * Experimental: the song Now Playing screen draws its artwork behind the controls. Off by
     * default, because it changes how one screen reads rather than how the app looks overall.
     */
    var artworkBackdrop: Boolean
        get() = prefs.getBoolean(KEY_ARTWORK_BACKDROP, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ARTWORK_BACKDROP, value).apply()
        }

    fun observeArtworkBackdrop(onChange: () -> Unit): () -> Unit = observe(KEY_ARTWORK_BACKDROP, onChange)

    /**
     * Shrinks the media rows by about a fifth so five fit where four used to. Off by default: the
     * four-row layout is the one the app was designed around.
     */
    var compactRows: Boolean
        get() = prefs.getBoolean(KEY_COMPACT_ROWS, false)
        set(value) {
            prefs.edit().putBoolean(KEY_COMPACT_ROWS, value).apply()
        }

    fun observeCompactRows(onChange: () -> Unit): () -> Unit = observe(KEY_COMPACT_ROWS, onChange)

    private fun observe(key: String, onChange: () -> Unit): () -> Unit {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed == null || changed == key) onChange()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    /**
     * The welcome screen is shown exactly once per install. The key is absent on a fresh install
     * and on any install upgrading from a release older than 0.3.4, so those users see it on their
     * next launch; installs that already finished it stay on Home.
     */
    fun hasSeenOnboarding(): Boolean = prefs.getBoolean(KEY_ONBOARDING_SEEN, false)

    fun needsOnboarding(): Boolean = !hasSeenOnboarding()

    fun markOnboardingSeen() {
        prefs.edit()
            .putBoolean(KEY_ONBOARDING_SEEN, true)
            .apply()
    }

    /**
     * Standalone Sidetrack starts in the compact layout. Only writes the default when the choice
     * has never been saved, so a user who turned compact off keeps that decision.
     */
    fun applyStandaloneThemeDefaults() {
        if (prefs.contains(KEY_COMPACT_LAYOUT)) return
        prefs.edit().putBoolean(KEY_COMPACT_LAYOUT, true).apply()
    }

    companion object {
        const val FILE = "sidetrack_settings"
        const val KEYPAD_FILE = "sidetrack_keypad"
        private const val KEY_INPUT_MODE = "input_mode"
        private const val KEY_ARTWORK_BACKDROP = "artwork_backdrop"
        private const val KEY_COMPACT_ROWS = "compact_rows"
        private const val KEY_ONBOARDING_SEEN = "onboarding_seen"

        // Must match the key SharedPreferencesSidekitThemeStore reads and writes.
        private const val KEY_COMPACT_LAYOUT = "compact_layout"
    }
}
