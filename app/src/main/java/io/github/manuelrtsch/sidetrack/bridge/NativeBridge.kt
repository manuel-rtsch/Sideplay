package io.github.manuelrtsch.sidetrack.bridge

import hrln.interactive.sidekit.sdk.system.NativeLibraries
import io.github.manuelrtsch.sidetrack.SidetrackDebug
import io.github.manuelrtsch.sidetrack.triage.TriageLog

/**
 * Guarded entry point to `libsidetrack_spotify.so`. Never calls `System.loadLibrary` from an
 * initializer: a build or emulator without the ABI is a normal state, not a crash.
 */
object NativeBridge : SpotifyNative {
    const val LIBRARY = "sidetrack_spotify"

    override val isAvailable: Boolean get() = NativeLibraries.load(LIBRARY)

    override fun initialize(): Boolean = isAvailable && runCatching { nativeInit() }.getOrDefault(false)

    override fun setTmpDir(path: String) {
        if (isAvailable) runCatching { nativeSetTmpDir(path) }
    }

    override fun setCacheDir(path: String, sizeLimitBytes: Long) {
        if (isAvailable) runCatching { nativeSetCacheDir(path, sizeLimitBytes) }
    }

    override fun connect(accessToken: String): Boolean = isAvailable && runCatching { nativeConnect(accessToken) }.getOrDefault(false)

    override fun setAccessToken(accessToken: String): Boolean = isAvailable && runCatching { nativeSetAccessToken(accessToken) }.getOrDefault(false)

    override fun disconnect() {
        if (isAvailable) runCatching { nativeDisconnect() }
    }

    override fun hasPlayer(): Boolean = isAvailable && runCatching { nativeHasPlayer() }.getOrDefault(false)

    override fun createPlayer(bitrateKbps: Int, normalization: Boolean, gapless: Boolean): Boolean = isAvailable && runCatching { nativeCreatePlayer(bitrateKbps, normalization, gapless) }.getOrDefault(false)

    override fun recreatePlayer(bitrateKbps: Int, normalization: Boolean, gapless: Boolean): Boolean = isAvailable && runCatching { nativeRecreatePlayer(bitrateKbps, normalization, gapless) }.getOrDefault(false)

    override fun preload(uri: String): Boolean = isAvailable && runCatching { nativePreload(uri) }.getOrDefault(false)

    override fun load(uri: String, positionMs: Long, autoplay: Boolean): Boolean {
        if (!isAvailable) {
            SidetrackDebug.log("native.load unavailable")
            return false
        }
        val started = System.nanoTime()
        return runCatching { nativeLoad(uri, positionMs, autoplay) }
            .onFailure { SidetrackDebug.log("native.load threw", it) }
            .getOrDefault(false)
            .also {
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                SidetrackDebug.log("native.load returned ok=$it elapsedMs=$elapsedMs")
            }
    }

    override fun play(): Boolean = isAvailable && runCatching { nativePlay() }.getOrDefault(false)

    override fun pause(): Boolean = isAvailable && runCatching { nativePause() }.getOrDefault(false)

    override fun stop(): Boolean = isAvailable && runCatching { nativeStop() }.getOrDefault(false)

    override fun seek(positionMs: Long): Boolean = isAvailable && runCatching { nativeSeek(positionMs) }.getOrDefault(false)

    override fun readAudio(timeoutMs: Long): ByteArray? = if (isAvailable) runCatching { nativeReadAudio(timeoutMs) }.getOrNull() else null

    override fun drainAudio() {
        if (isAvailable) runCatching { nativeDrainAudio() }
    }

    override fun audioFormat(): String? = if (isAvailable) runCatching { nativeAudioFormat() }.getOrNull() else null

    override fun pollEvent(): String? = if (isAvailable) runCatching { nativePollEvent() }.getOrNull() else null

    override fun catalogLibrary(filter: String, offset: Long, limit: Long): String? =
        nativeCall("catalogLibrary filter=$filter offset=$offset") { nativeCatalogLibrary(filter, offset, limit) }

    override fun catalogCollection(kind: String, id: String, offset: Long, limit: Long): String? =
        nativeCall("catalogCollection kind=$kind id=$id offset=$offset") { nativeCatalogCollection(kind, id, offset, limit) }

    override fun catalogArtist(id: String): String? =
        nativeCall("catalogArtist id=$id") { nativeCatalogArtist(id) }

    override fun catalogAlbumsBy(id: String, offset: Long, limit: Long): String? =
        nativeCall("catalogAlbumsBy id=$id offset=$offset") { nativeCatalogAlbumsBy(id, offset, limit) }

    override fun catalogTopTracks(id: String, offset: Long, limit: Long): String? =
        nativeCall("catalogTopTracks id=$id offset=$offset") { nativeCatalogTopTracks(id, offset, limit) }

    override fun catalogSongRadio(id: String, limit: Long): String? =
        nativeCall("catalogSongRadio id=$id") { nativeCatalogSongRadio(id, limit) }

    override fun catalogArtistRadio(id: String, limit: Long): String? =
        nativeCall("catalogArtistRadio id=$id") { nativeCatalogArtistRadio(id, limit) }

    override fun catalogSearch(query: String, scopes: String, offset: Long, limit: Long): String? =
        nativeCall("catalogSearch scopes=$scopes offset=$offset") { nativeCatalogSearch(query, scopes, offset, limit) }

    override fun collectionContains(set: String, uris: String): String? =
        nativeCall("collectionContains set=$set") { nativeCollectionContains(set, uris) }

    override fun collectionWrite(set: String, uris: String, removed: Boolean): Boolean =
        nativeCall("collectionWrite set=$set removed=$removed", fallback = false) { nativeCollectionWrite(set, uris, removed) } ?: false

    override fun playlistSaved(id: String): String? =
        nativeCall("playlistSaved id=$id") { nativePlaylistSaved(id) }

    override fun playlistSave(id: String, save: Boolean): Boolean =
        nativeCall("playlistSave id=$id save=$save", fallback = false) { nativePlaylistSave(id, save) } ?: false

    override fun playlistItemsAdd(id: String, uris: String): String? =
        nativeCall("playlistItemsAdd id=$id") { nativePlaylistItemsAdd(id, uris) }

    override fun playlistItemsRemove(id: String, uris: String): Boolean =
        nativeCall("playlistItemsRemove id=$id", fallback = false) { nativePlaylistItemsRemove(id, uris) } ?: false

    /**
     * Catalog and library calls answer null/false on every failure, which hides a JNI exception or
     * a library that never loaded behind the same "no data" symptom. Triage needs those apart: the
     * log names the op, and a missing library is reported instead of silently falling back.
     */
    private inline fun <T> nativeCall(op: String, fallback: T? = null, block: () -> T?): T? {
        if (!isAvailable) {
            TriageLog.e("native", "$op: libsidetrack_spotify is not loaded")
            return fallback
        }
        return runCatching(block)
            .onFailure { TriageLog.e("native", "$op threw", it) }
            .getOrDefault(fallback)
    }

    private external fun nativeInit(): Boolean
    private external fun nativeSetTmpDir(path: String)
    private external fun nativeSetCacheDir(path: String, sizeLimitBytes: Long)
    private external fun nativeConnect(accessToken: String): Boolean
    private external fun nativeSetAccessToken(accessToken: String): Boolean
    private external fun nativeDisconnect()
    private external fun nativeHasPlayer(): Boolean
    private external fun nativeCreatePlayer(bitrateKbps: Int, normalization: Boolean, gapless: Boolean): Boolean
    private external fun nativeRecreatePlayer(bitrateKbps: Int, normalization: Boolean, gapless: Boolean): Boolean
    private external fun nativePreload(uri: String): Boolean
    private external fun nativeLoad(uri: String, positionMs: Long, autoplay: Boolean): Boolean
    private external fun nativePlay(): Boolean
    private external fun nativePause(): Boolean
    private external fun nativeStop(): Boolean
    private external fun nativeSeek(positionMs: Long): Boolean
    private external fun nativeReadAudio(timeoutMs: Long): ByteArray?
    private external fun nativeDrainAudio()
    private external fun nativeAudioFormat(): String?
    private external fun nativePollEvent(): String?
    private external fun nativeCatalogLibrary(filter: String, offset: Long, limit: Long): String?
    private external fun nativeCatalogCollection(kind: String, id: String, offset: Long, limit: Long): String?
    private external fun nativeCatalogArtist(id: String): String?
    private external fun nativeCatalogAlbumsBy(id: String, offset: Long, limit: Long): String?
    private external fun nativeCatalogTopTracks(id: String, offset: Long, limit: Long): String?
    private external fun nativeCatalogSongRadio(id: String, limit: Long): String?
    private external fun nativeCatalogArtistRadio(id: String, limit: Long): String?
    private external fun nativeCatalogSearch(query: String, scopes: String, offset: Long, limit: Long): String?
    private external fun nativeCollectionContains(set: String, uris: String): String?
    private external fun nativeCollectionWrite(set: String, uris: String, removed: Boolean): Boolean
    private external fun nativePlaylistSaved(id: String): String?
    private external fun nativePlaylistSave(id: String, save: Boolean): Boolean
    private external fun nativePlaylistItemsAdd(id: String, uris: String): String?
    private external fun nativePlaylistItemsRemove(id: String, uris: String): Boolean
}
