package io.github.manuelrtsch.sidetrack.data

import android.content.Context
import android.util.Log
import io.github.manuelrtsch.sidetrack.AudioQuality
import io.github.manuelrtsch.sidetrack.storage.GuardedEncryptedStore
import io.github.manuelrtsch.sidetrack.storage.JsonStore
import org.json.JSONObject

/** User preferences that survive process death and apply before the native player is created. */
data class SpotifySettings(
    val audioQuality: AudioQuality = AudioQuality.High,
    /** Apply Spotify's per-track loudness data so tracks play back at a similar level. */
    val normalization: Boolean = false,
    /** Keep librespot's sink open between tracks so adjacent tracks join without a gap. */
    val gapless: Boolean = true,
    /** Keep playing similar tracks when the queue runs out. */
    val autoplay: Boolean = true
) {
    val bitrateKbps: Int get() = audioQuality.bitrateKbps
}

/**
 * Settings live in the same encrypted, atomic store as credentials. They are not secrets, but the
 * store already exists, needs no extra dependency and keeps all Sidetrack state in one place.
 *
 * Nothing here throws: settings are read while the session is constructed and written from UI
 * callbacks, so a keystore that lost its key must degrade to defaults rather than crash.
 */
class SpotifySettingsStore(
    context: Context,
    // Legacy name kept so existing dev installs keep their audio-quality setting.
    name: String = "sidetracks_settings",
    private val store: JsonStore = GuardedEncryptedStore(context, name)
) {

    @Synchronized
    fun read(): SpotifySettings {
        val value = runCatching { store.read() }.getOrDefault(JSONObject())
        val quality = AudioQuality.entries
            .firstOrNull { it.name == value.optString(KEY_QUALITY) }
            ?: AudioQuality.High
        return SpotifySettings(
            audioQuality = quality,
            normalization = value.optBoolean(KEY_NORMALIZATION, false),
            gapless = value.optBoolean(KEY_GAPLESS, true),
            autoplay = value.optBoolean(KEY_AUTOPLAY, true)
        )
    }

    @Synchronized
    fun write(value: SpotifySettings) {
        val payload = JSONObject()
            .put(KEY_QUALITY, value.audioQuality.name)
            .put(KEY_NORMALIZATION, value.normalization)
            .put(KEY_GAPLESS, value.gapless)
            .put(KEY_AUTOPLAY, value.autoplay)
        // The change already applies in memory; a dropped write only costs the next launch.
        runCatching { store.write(payload) }
            .onFailure { Log.w(TAG, "Could not persist the audio settings", it) }
    }

    private companion object {
        const val TAG = "SidetrackSettings"
        const val KEY_QUALITY = "audio_quality"
        const val KEY_NORMALIZATION = "normalization"
        const val KEY_GAPLESS = "gapless"
        const val KEY_AUTOPLAY = "autoplay"
    }
}
