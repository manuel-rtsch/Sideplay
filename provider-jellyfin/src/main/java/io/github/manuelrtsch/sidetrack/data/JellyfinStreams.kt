package io.github.manuelrtsch.sidetrack.data

import java.util.UUID
import org.jellyfin.sdk.api.operations.AudioApi
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.DirectPlayProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.TranscodingProfile

/**
 * Which URL the player is handed for the source `PlaybackInfo` answered with.
 *
 * A `transcodingUrl` means the server refused the original file (a bitrate ceiling, an unsupported
 * codec, or a stream copy) and is the only URL that can serve it; it arrives either absolute or as
 * a path relative to the server root. When there is none the source is a direct play, and
 * [directUrl] — the static stream endpoint — is the one that works.
 *
 * Kept out of [SdkJellyfinApi.playback] so the branch that the 0.4.0 regression lived in can be
 * asserted offline: a `FakeJellyfinApi` stubs `playback()` wholesale, so nothing else covers it.
 */
internal fun playbackStreamUrl(transcodingUrl: String?, baseUrl: String, directUrl: String): String = when {
    transcodingUrl.isNullOrBlank() -> directUrl
    transcodingUrl.startsWith("http") -> transcodingUrl
    else -> baseUrl + transcodingUrl
}

/**
 * The URL for a source the server let us play directly.
 *
 * `/Audio/{id}/universal` is the endpoint a `MediaSourceInfo` points at only implicitly, and on
 * Jellyfin 12 it answers **500** with an HTML error page unless it is also told the container to
 * produce — so the player is handed a web page where it expects audio and fails with "none of the
 * available extractors could read the stream". `/Audio/{id}/stream?static=true` is what Jellyfin's
 * own clients use for a direct play: it serves the original file with byte ranges intact, which
 * Media3 needs to seek. A bitrate cap never reaches this path; the server answers such a request
 * with a `transcodingUrl` instead.
 */
internal fun directPlayStreamUrl(
    audio: AudioApi,
    itemId: UUID,
    container: String?,
    mediaSourceId: String?,
    deviceId: String?,
    playSessionId: String?
): String = audio.getAudioStreamUrl(
    itemId = itemId,
    container = container,
    static = true,
    mediaSourceId = mediaSourceId,
    deviceId = deviceId,
    playSessionId = playSessionId
)

/** The direct-play containers Media3/ExoPlayer handles, plus one audio-only transcode fallback. */
internal fun jellyfinMusicProfile(maxStreamingBitrate: Int?): DeviceProfile {
    val ceiling = maxStreamingBitrate ?: UNCAPPED_BITRATE
    return DeviceProfile(
        name = "Sidetrack",
        maxStreamingBitrate = ceiling,
        maxStaticBitrate = ceiling,
        maxStaticMusicBitrate = ceiling,
        musicStreamingTranscodingBitrate = maxStreamingBitrate,
        directPlayProfiles = listOf(
            DirectPlayProfile(container = "flac", audioCodec = "flac", type = DlnaProfileType.AUDIO),
            DirectPlayProfile(container = "mp3", audioCodec = "mp3", type = DlnaProfileType.AUDIO),
            DirectPlayProfile(container = "m4a", audioCodec = "aac", type = DlnaProfileType.AUDIO),
            DirectPlayProfile(container = "aac", audioCodec = "aac", type = DlnaProfileType.AUDIO),
            DirectPlayProfile(container = "ogg", audioCodec = "vorbis", type = DlnaProfileType.AUDIO),
            DirectPlayProfile(container = "opus", audioCodec = "opus", type = DlnaProfileType.AUDIO),
            DirectPlayProfile(container = "wav", audioCodec = "pcm_s16le", type = DlnaProfileType.AUDIO)
        ),
        transcodingProfiles = listOf(
            TranscodingProfile(
                container = "mp3",
                type = DlnaProfileType.AUDIO,
                videoCodec = "",
                audioCodec = "mp3",
                protocol = MediaStreamProtocol.HTTP,
                conditions = emptyList()
            )
        ),
        containerProfiles = emptyList(),
        codecProfiles = emptyList(),
        subtitleProfiles = emptyList()
    )
}

/** 1411 kbit/s is CD-quality FLAC: the "no cap" choice still states a ceiling (D4). */
private const val UNCAPPED_BITRATE = 1_411_200
