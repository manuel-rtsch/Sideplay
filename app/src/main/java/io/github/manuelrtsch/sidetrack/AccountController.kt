package io.github.manuelrtsch.sidetrack

import android.util.Log
import io.github.manuelrtsch.sidetrack.auth.CredentialPersistenceException
import io.github.manuelrtsch.sidetrack.data.AuthInput
import io.github.manuelrtsch.sidetrack.data.HistoryRepository
import io.github.manuelrtsch.sidetrack.data.QueueRepository
import io.github.manuelrtsch.sidetrack.data.ServiceRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Sign-in, sign-out and the reset that follows an account change.
 *
 * An account change is not only a flag: the queue, the library pages, both stations, the search
 * results and the optimistic save hints all belong to the old account and have to go together.
 * That reset lives here so no path can forget half of it.
 */
internal class AccountController(
    private val store: UiStateStore,
    private val session: ServiceRuntime?,
    private val openBrowser: (suspend (String) -> Unit)?,
    private val clearArtworkCache: () -> Unit,
    private val queue: QueueRepository,
    private val history: HistoryRepository,
    private val library: LibraryController,
    private val radio: RadioController,
    private val hints: SavedHints,
    private val scope: CoroutineScope
) {

    private var signInJob: Job? = null

    fun signOut() {
        session?.signOut()
        if (session == null) clearArtworkCache()
        queue.stop()
        store.update { SidetrackUiState(signedIn = false, recent = history.items()) }
    }

    fun signIn() {
        val openBrowser = openBrowser
        if (session == null || openBrowser == null) {
            store.update { it.say("Sign-in is unavailable in this build") }
            return
        }
        startSignIn(AuthInput.Browser(openBrowser))
    }

    /** A self-hosted flow: server URL, username and password instead of a browser. */
    fun signIn(serverUrl: String, username: String, password: String) {
        if (serverUrl.isBlank() || username.isBlank()) {
            store.update { it.say("Enter the server and username") }
            return
        }
        // The password stays out of this check: a service can have accounts without one (the
        // Jellyfin demo does). The provider knows which fields it needs and names the missing one,
        // so the form does not refuse a valid sign-in the provider would accept.
        startSignIn(AuthInput.Password(serverUrl.trim(), username.trim(), password))
    }

    fun startSignIn(input: AuthInput) {
        val session = session ?: run {
            store.update { it.say("Sign-in is unavailable in this build") }
            return
        }
        // A previous attempt can still be waiting on the loopback listener after the user
        // closed the browser. Supersede it so every press opens a fresh sign-in page instead of
        // being ignored until the old listener times out.
        signInJob?.cancel()
        store.update { it.copy(authPending = true, notice = null) }
        signInJob = scope.launch {
            try {
                session.signIn(input)
                store.update { it.copy(signedIn = true, authPending = false) }
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: CredentialPersistenceException) {
                // The session is live for this run; only keeping it for the next launch failed.
                store.update { it.copy(signedIn = true, authPending = false).say(e.message ?: "Sign-in failed") }
                load()
            } catch (e: Throwable) {
                // Sign-in runs on the main thread and touches the keystore, the browser and the
                // network. Anything that escapes here used to kill the process; report it instead.
                Log.e("SidetrackSignIn", "sign-in threw ${e::class.java.name}", e)
                store.update {
                    it.copy(authPending = false).say(e.message ?: "Sign-in failed")
                }
            }
        }
    }

    fun load() {
        if (!store.value.signedIn) return
        store.update {
            // Recently played is local-only: no first-party history endpoint, no Web API quota.
            it.copy(recent = history.items())
        }
        library.loadSections()
    }

    fun clear() {
        queue.stop()
        history.clear()
        library.reset()
        radio.reset()
        hints.clear()
        store.update {
            it.copy(
                recent = emptyList(),
                libraryItems = emptyList(),
                librarySections = emptyMap(),
                librarySectionHasMore = emptySet(),
                librarySectionsLoading = false,
                librarySectionsError = null,
                libraryError = null,
                searchError = null,
                collectionErrors = emptyMap(),
                artistErrors = emptyMap(),
                libraryHasMore = false,
                libraryMorePending = false,
                searchResults = SearchResults(),
                searchHasMore = emptySet(),
                searchMorePending = emptySet(),
                collections = emptyMap(),
                collectionMorePending = emptySet(),
                artistAlbumHasMore = emptyMap(),
                artistTrackHasMore = emptyMap(),
                artistMorePending = emptySet(),
                artists = emptyMap(),
                artistAlbums = emptyMap(),
                artistTracks = emptyMap(),
                savedTrackIds = emptySet(),
                savedCollectionKeys = emptySet(),
                followedArtistIds = emptySet(),
                nowPlayingNotice = null,
                radio = null,
                radioSeedId = null,
                radioPending = false,
                radioError = null,
                playlistChoices = emptyList(),
                playlistChoicesLoading = false,
                playlistChoicesLoaded = false,
                playlistChoicesHasMore = false,
                playlistChoicesError = null
            )
        }
    }
}
