package io.github.manuelrtsch.sideplay.bridge

import hrln.interactive.sidekit.sdk.system.NativeLibraries
import io.github.manuelrtsch.sideplay.SideplayDebug

/**
 * Guarded entry point to `libsideplay_spotify.so`. Never calls `System.loadLibrary` from an
 * initializer: a build or emulator without the ABI is a normal state, not a crash.
 */
object NativeBridge : SpotifyNative {
    const val LIBRARY = "sideplay_spotify"

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

    override fun createPlayer(bitrateKbps: Int): Boolean = isAvailable && runCatching { nativeCreatePlayer(bitrateKbps) }.getOrDefault(false)

    override fun load(uri: String, positionMs: Long, autoplay: Boolean): Boolean {
        if (!isAvailable) {
            SideplayDebug.log("native.load unavailable")
            return false
        }
        val started = System.nanoTime()
        return runCatching { nativeLoad(uri, positionMs, autoplay) }
            .onFailure { SideplayDebug.log("native.load threw", it) }
            .getOrDefault(false)
            .also {
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                SideplayDebug.log("native.load returned ok=$it elapsedMs=$elapsedMs")
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

    override fun catalogLibrary(filter: String, offset: Long, limit: Long): String? = if (isAvailable) runCatching { nativeCatalogLibrary(filter, offset, limit) }.getOrNull() else null

    override fun catalogCollection(kind: String, id: String, offset: Long, limit: Long): String? = if (isAvailable) runCatching { nativeCatalogCollection(kind, id, offset, limit) }.getOrNull() else null

    override fun catalogArtist(id: String): String? = if (isAvailable) runCatching { nativeCatalogArtist(id) }.getOrNull() else null

    override fun catalogAlbumsBy(id: String, offset: Long, limit: Long): String? = if (isAvailable) runCatching { nativeCatalogAlbumsBy(id, offset, limit) }.getOrNull() else null

    override fun catalogTopTracks(id: String, offset: Long, limit: Long): String? = if (isAvailable) runCatching { nativeCatalogTopTracks(id, offset, limit) }.getOrNull() else null

    override fun catalogSearch(query: String, scopes: String, offset: Long, limit: Long): String? = if (isAvailable) runCatching { nativeCatalogSearch(query, scopes, offset, limit) }.getOrNull() else null

    override fun collectionContains(set: String, uris: String): String? = if (isAvailable) runCatching { nativeCollectionContains(set, uris) }.getOrNull() else null

    override fun collectionWrite(set: String, uris: String, removed: Boolean): Boolean = isAvailable && runCatching { nativeCollectionWrite(set, uris, removed) }.getOrDefault(false)

    override fun playlistSaved(id: String): String? = if (isAvailable) runCatching { nativePlaylistSaved(id) }.getOrNull() else null

    override fun playlistSave(id: String, save: Boolean): Boolean = isAvailable && runCatching { nativePlaylistSave(id, save) }.getOrDefault(false)

    private external fun nativeInit(): Boolean
    private external fun nativeSetTmpDir(path: String)
    private external fun nativeSetCacheDir(path: String, sizeLimitBytes: Long)
    private external fun nativeConnect(accessToken: String): Boolean
    private external fun nativeSetAccessToken(accessToken: String): Boolean
    private external fun nativeDisconnect()
    private external fun nativeCreatePlayer(bitrateKbps: Int): Boolean
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
    private external fun nativeCatalogSearch(query: String, scopes: String, offset: Long, limit: Long): String?
    private external fun nativeCollectionContains(set: String, uris: String): String?
    private external fun nativeCollectionWrite(set: String, uris: String, removed: Boolean): Boolean
    private external fun nativePlaylistSaved(id: String): String?
    private external fun nativePlaylistSave(id: String, save: Boolean): Boolean
}
