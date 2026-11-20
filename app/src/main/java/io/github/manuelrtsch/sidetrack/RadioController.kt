package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.data.MediaCatalog
import io.github.manuelrtsch.sidetrack.data.QueueRepository
import io.github.manuelrtsch.sidetrack.data.ServiceRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The two station screens: song radio from the current track, and artist radio from an artist.
 *
 * Both build a station Collection from a catalog request, keep it in their own state field and
 * adopt its saved state. Song radio additionally rewrites the tail of the queue, but only if the
 * user has not touched it while the request was in flight.
 */
internal class RadioController(
    private val store: UiStateStore,
    private val catalog: MediaCatalog,
    private val mediaKeys: MediaKeys,
    private val driver: PlaybackDriver,
    private val queue: QueueRepository,
    private val effects: PlaybackEffects,
    private val hints: SavedHints,
    private val session: ServiceRuntime?,
    private val online: () -> Boolean,
    private val scope: CoroutineScope
) {

    private val service: ServiceId get() = session?.service ?: ServiceId.Local

    private var radioJob: Job? = null
    private var artistRadioJob: Job? = null

    private fun playbackNotice(error: Exception): String = serviceMessage(error, online())

    /** Cancels both stations; called when the account changes. */
    fun reset() {
        radioJob?.cancel()
        artistRadioJob?.cancel()
    }

    /**
     * Builds a station from the current track, queues it behind the still-playing seed, and makes
     * it available to the Song radio screen. Revisiting the same seed is a no-op.
     */
    fun ensureRadio() {
        val seed = store.value.current as? Track
        if (seed == null) {
            store.update {
                it.copy(radio = null, radioSeedId = null, radioPending = false, radioError = "Nothing playing")
            }
            return
        }
        if (radioJob?.isActive == true && store.value.radioSeedId == seed.id) return
        if (store.value.radioSeedId == seed.id && store.value.radio != null) return
        loadRadio(seed)
    }

    fun retryRadio() {
        radioJob?.cancel()
        store.update { it.copy(radio = null, radioSeedId = null, radioError = null) }
        ensureRadio()
    }

    fun playRadio(index: Int) {
        val radio = store.value.radio ?: return
        driver.play(radio.items, index)
    }

    /**
     * Builds the artist's station for the Artist radio screen. Revisiting the same artist is a
     * no-op, and unlike song radio the station never touches the queue on its own: the user starts
     * it from the station screen, so opening the screen cannot interrupt what is playing.
     */
    fun ensureArtistRadio(artist: Artist) {
        val id = artist.id
        val snapshot = store.value
        if (artistRadioJob?.isActive == true && snapshot.artistRadioArtistId == id) return
        if (snapshot.artistRadioArtistId == id && snapshot.artistRadio != null) return
        loadArtistRadio(artist)
    }

    fun retryArtistRadio(artist: Artist) {
        artistRadioJob?.cancel()
        store.update { it.copy(artistRadio = null, artistRadioArtistId = null, artistRadioError = null) }
        ensureArtistRadio(artist)
    }

    fun playArtistRadio(index: Int) {
        val station = store.value.artistRadio ?: return
        driver.play(station.items, index)
    }

    /**
     * Adopts the library state of a station's backing playlist, unless the user already toggled
     * the tile on this screen. Song radio and artist radio store their station in different
     * fields but resolve the saved state the same way.
     */
    private fun SidetrackUiState.withStationSaved(
        station: Collection,
        saved: Boolean?
    ): SidetrackUiState {
        if (saved == null) return this
        val key = mediaKeys.collection(CollectionType.Playlist, station.id)
        if (hints.collectionTouched(key)) return this
        val keys = savedCollectionKeys.toMutableSet()
        if (saved) keys.add(key) else keys.remove(key)
        return copy(savedCollectionKeys = keys)
    }

    fun loadArtistRadio(artist: Artist) {
        artistRadioJob?.cancel()
        val id = artist.id
        store.update {
            it.copy(artistRadio = null, artistRadioArtistId = id, artistRadioPending = true, artistRadioError = null)
        }
        artistRadioJob = scope.launch {
            try {
                val result = catalog.radioStation(mediaKeys.artist(id), CATALOG_PAGE_SIZE)
                val tracks = result.tracks
                    .filter { it.playable }
                    .distinctBy { it.id }
                // A station the user navigated away from must not overwrite the next artist's.
                if (store.value.artistRadioArtistId != id) return@launch
                if (tracks.isEmpty()) {
                    store.update {
                        it.copy(artistRadioPending = false, artistRadioError = "No similar tracks found")
                    }
                    return@launch
                }
                val station = Collection(
                    type = CollectionType.Playlist,
                    id = result.playlistId ?: "artistradio:$id",
                    title = "${artist.name} radio",
                    subtitle = "Based on ${artist.name} · ${tracks.size} tracks",
                    artRes = artist.artRes,
                    items = tracks,
                    artUrl = artist.artUrl,
                    totalItems = tracks.size,
                    service = service,
                    saveable = result.playlistId != null
                )
                val saved = result.playlistId?.let {
                    runCatching { hints.savedCollection(mediaKeys.media(CollectionType.Playlist, it), mediaKeys.collection(CollectionType.Playlist, it)) }.getOrNull()
                }
                store.update {
                    it.copy(
                        artistRadio = station,
                        artistRadioPending = false,
                        artistRadioError = null
                    ).withStationSaved(station, saved)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (store.value.artistRadioArtistId == id) {
                    store.update {
                        it.copy(artistRadioPending = false, artistRadioError = playbackNotice(e))
                    }
                }
            }
        }
    }

    fun loadRadio(seed: Track) {
        radioJob?.cancel()
        // The station request can take seconds. Remember the queue it was started from so a late
        // response cannot replace edits the user made while it loaded.
        val queueAtRequest = queue.snapshot
        store.update {
            it.copy(radio = null, radioSeedId = seed.id, radioPending = true, radioError = null)
        }
        radioJob = scope.launch {
            try {
                val result = catalog.radioStation(mediaKeys.track(seed.id), CATALOG_PAGE_SIZE)
                val tracks = result.tracks
                    .filter { it.playable && it.id != seed.id }
                    .distinctBy { it.id }
                if (store.value.current?.id != seed.id) {
                    store.update {
                        it.copy(radioPending = false, radioError = "The track changed — try again")
                    }
                    return@launch
                }
                if (tracks.isEmpty()) {
                    store.update { it.copy(radioPending = false, radioError = "No similar tracks found") }
                    return@launch
                }
                val station = Collection(
                    type = CollectionType.Playlist,
                    id = result.playlistId ?: "radio:${seed.id}",
                    title = "Song radio",
                    subtitle = "Based on ${seed.title} · ${tracks.size} tracks",
                    artRes = seed.artRes,
                    items = tracks,
                    artUrl = seed.artUrl,
                    totalItems = tracks.size,
                    service = service,
                    saveable = result.playlistId != null
                )
                val saved = result.playlistId?.let {
                    runCatching { hints.savedCollection(mediaKeys.media(CollectionType.Playlist, it), mediaKeys.collection(CollectionType.Playlist, it)) }.getOrNull()
                }
                store.update {
                    it.copy(
                        radio = station,
                        radioSeedId = seed.id,
                        radioPending = false,
                        radioError = null
                    ).withStationSaved(station, saved)
                }
                // The seed keeps playing; the station becomes what follows it. Only do that if
                // the queue is still the one this request started from. A late response must not
                // overwrite a track the user added, removed, cleared or reordered meanwhile.
                val queueNow = queue.snapshot
                if (
                    queueNow.current?.id == seed.id &&
                    queueNow.upcoming == queueAtRequest.upcoming &&
                    queueNow.shuffle == queueAtRequest.shuffle
                ) {
                    effects.applyQueue(queue.play(listOf(seed) + tracks, 0), syncNowPlaying = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (store.value.radioSeedId == seed.id) {
                    store.update {
                        it.copy(
                            radioPending = false,
                            radioError = if (it.current?.id == seed.id) {
                                playbackNotice(e)
                            } else {
                                "The track changed — try again"
                            }
                        )
                    }
                }
            }
        }
    }
}
