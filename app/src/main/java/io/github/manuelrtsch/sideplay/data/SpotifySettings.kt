package io.github.manuelrtsch.sideplay.data

import android.content.Context
import hrln.interactive.sidekit.sdk.crypto.EncryptedFileStore
import io.github.manuelrtsch.sideplay.AudioQuality

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
 * store already exists, needs no extra dependency and keeps all Sideplay state in one place.
 */
class SpotifySettingsStore(
    context: Context,
    // Legacy name kept so existing dev installs keep their audio-quality setting.
    name: String = "sidetracks_settings"
) {
    private val settings = EncryptedFileStore(context.applicationContext, name)

    @Synchronized
    fun read(): SpotifySettings {
        val value = settings.read()
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
        settings.write(
            org.json.JSONObject()
                .put(KEY_QUALITY, value.audioQuality.name)
                .put(KEY_NORMALIZATION, value.normalization)
                .put(KEY_GAPLESS, value.gapless)
                .put(KEY_AUTOPLAY, value.autoplay)
        )
    }

    private companion object {
        const val KEY_QUALITY = "audio_quality"
        const val KEY_NORMALIZATION = "normalization"
        const val KEY_GAPLESS = "gapless"
        const val KEY_AUTOPLAY = "autoplay"
    }
}
