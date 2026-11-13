package io.github.manuelrtsch.sideplay.bridge

/**
 * Testable edge of the JNI library. Calls are blocking; `NativeSpotifyGateway` moves them to IO.
 */
interface SpotifyNative {
    val isAvailable: Boolean

    fun initialize(): Boolean
    fun setTmpDir(path: String)
    fun setCacheDir(path: String, sizeLimitBytes: Long)
    fun connect(accessToken: String): Boolean
    fun setAccessToken(accessToken: String): Boolean
    fun disconnect()
    fun createPlayer(bitrateKbps: Int, normalization: Boolean, gapless: Boolean): Boolean
    fun recreatePlayer(bitrateKbps: Int, normalization: Boolean, gapless: Boolean): Boolean
    fun preload(uri: String): Boolean
    fun load(uri: String, positionMs: Long, autoplay: Boolean): Boolean
    fun play(): Boolean
    fun pause(): Boolean
    fun stop(): Boolean
    fun seek(positionMs: Long): Boolean
    fun readAudio(timeoutMs: Long): ByteArray?
    fun drainAudio()
    fun audioFormat(): String?
    fun pollEvent(): String?

    fun catalogLibrary(filter: String, offset: Long, limit: Long): String?
    fun catalogCollection(kind: String, id: String, offset: Long, limit: Long): String?
    fun catalogArtist(id: String): String?
    fun catalogAlbumsBy(id: String, offset: Long, limit: Long): String?
    fun catalogTopTracks(id: String, offset: Long, limit: Long): String?
    fun catalogSearch(query: String, scopes: String, offset: Long, limit: Long): String?

    fun collectionContains(set: String, uris: String): String?
    fun collectionWrite(set: String, uris: String, removed: Boolean): Boolean
    fun playlistSaved(id: String): String?
    fun playlistSave(id: String, save: Boolean): Boolean
}
