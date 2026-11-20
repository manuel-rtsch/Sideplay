package io.github.manuelrtsch.sidetrack.bridge

/**
 * Testable edge of the JNI library. Calls are blocking; `NativeSpotifyGateway` moves them to IO.
 */
interface SpotifyNative {
    val isAvailable: Boolean

    fun initialize(): Boolean
    fun setTmpDir(path: String)
    fun setCacheDir(path: String, sizeLimitBytes: Long)

    /**
     * Connects a librespot session. Returns null on success, otherwise the transport's own reason.
     *
     * The reason is developer-facing: it can name a host, a port or a TLS state. It exists because
     * an access-point failure happens before any request is made, so nothing else on the way up can
     * describe it — a bare boolean left a device-specific failure undiagnosable.
     */
    fun connect(accessToken: String): String?
    fun setAccessToken(accessToken: String): Boolean
    fun disconnect()

    /**
     * Whether a player is attached to the live session. A reconnect replaces the session and drops
     * the player, so this is the only reliable answer; a cached flag on the Kotlin side can be
     * stale.
     */
    fun hasPlayer(): Boolean
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

    /** Returns `{"items":[...],"total":n}`; `total` is omitted when the endpoint can't report it. */
    fun catalogLibrary(filter: String, offset: Long, limit: Long): String?
    fun catalogCollection(kind: String, id: String, offset: Long, limit: Long): String?
    fun catalogArtist(id: String): String?
    fun catalogAlbumsBy(id: String, offset: Long, limit: Long): String?
    fun catalogTopTracks(id: String, offset: Long, limit: Long): String?
    fun catalogSongRadio(id: String, limit: Long): String?
    fun catalogArtistRadio(id: String, limit: Long): String?
    fun catalogSearch(query: String, scopes: String, offset: Long, limit: Long): String?

    fun collectionContains(set: String, uris: String): String?
    fun collectionWrite(set: String, uris: String, removed: Boolean): Boolean
    fun playlistSaved(id: String): String?
    fun playlistSave(id: String, save: Boolean): Boolean

    /** Appends [uris] to a playlist. Returns the index the first item landed at, or null. */
    fun playlistItemsAdd(id: String, uris: String): String?

    /** Removes [uris] from a playlist. Returns `"ok"`, or `{"error": "..."}` on refusal. */
    fun playlistItemsRemove(id: String, uris: String): String?
}
