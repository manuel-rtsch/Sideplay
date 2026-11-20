package io.github.manuelrtsch.sidetrack.data

import android.content.Context
import hrln.interactive.sidekit.sdk.media.RemoteArtwork
import java.io.File

/** The on-disk artwork and audio caches, kept behind the session's public cache surface. */
internal class SpotifyCacheFiles(
    private val appContext: Context?,
    private val audioCacheDir: File?
) {
    /** librespot re-fetches anything it still needs. */
    fun clearAudioCache() {
        audioCacheDir?.deleteRecursively()
    }

    /** Artwork and audio bytes currently held on disk. */
    fun sizeBytes(): Long {
        val artwork = appContext?.let { File(it.cacheDir, "artwork") }
        return listOfNotNull(artwork, audioCacheDir).sumOf { it.sizeBytes() }
    }

    /** Sign-out and the Settings action both drop artwork and audio; credentials are separate. */
    fun clear() {
        appContext?.let(RemoteArtwork::clear)
        clearAudioCache()
    }

    private fun File.sizeBytes(): Long = walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
