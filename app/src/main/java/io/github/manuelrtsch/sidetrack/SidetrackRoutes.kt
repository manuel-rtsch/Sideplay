package io.github.manuelrtsch.sidetrack

import android.net.Uri
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument

const val SIDE_PLAY_ROOT = "sidetrack"
internal const val ROOT = SIDE_PLAY_ROOT
const val SIDE_PLAY_START = "sidetrack/home"
const val SIDE_PLAY_ONBOARDING = "sidetrack/welcome"
const val SIDE_PLAY_SIGN_IN = "sidetrack/sign-in"
const val SIDE_PLAY_SERVICE_PICKER = "sidetrack/service"
const val SIDE_PLAY_SEARCH = "sidetrack/search"
internal const val HOME = SIDE_PLAY_START
internal const val ONBOARDING = SIDE_PLAY_ONBOARDING
internal val HELP = "$ROOT/help"
internal val HISTORY = "$ROOT/history"
internal val LIBRARY = "$ROOT/library"
internal val COLLECTION = "$ROOT/collection/{key}"
internal val ARTIST = "$ROOT/artist/{key}"
internal val CREATE_PLAYLIST = "$ROOT/playlist/create"
internal val ADD_TO_PLAYLIST = "$ROOT/playlist/add/{key}"
internal val SEARCH = SIDE_PLAY_SEARCH
internal val SEARCH_RESULTS = "$ROOT/search/results"
internal val SEARCH_SCOPES = "$ROOT/search/scopes"
internal val NOW_PLAYING = "$ROOT/now-playing"
internal val QUEUE = "$ROOT/queue"
internal val RADIO = "$ROOT/song-radio"
internal val ARTIST_RADIO = "$ROOT/artist-radio/{key}"
internal val SLEEP_TIMER = "$ROOT/sleep-timer"
internal val SETTINGS = "$ROOT/settings"
internal val SERVICE_PICKER = SIDE_PLAY_SERVICE_PICKER
internal val AUDIO_QUALITY = "$ROOT/audio-quality"
internal val SIGN_IN = SIDE_PLAY_SIGN_IN
internal val APPEARANCE = "$ROOT/appearance"
internal val KEYBOARD = "$ROOT/keyboard"
internal val ABOUT = "$ROOT/about"
internal val ARTWORK = "$ROOT/artwork/{artRes}?url={url}&label={label}&ratio={ratio}"
internal val NOTES = "$ROOT/notes/{key}"

internal fun collectionRoute(key: MediaKey) = "$ROOT/collection/${Uri.encode(key.encode())}"
internal fun artistRoute(key: MediaKey) = "$ROOT/artist/${Uri.encode(key.encode())}"
internal fun addToPlaylistRoute(key: String) = "$ROOT/playlist/add/${Uri.encode(key)}"
internal fun artistRadioRoute(key: MediaKey) = "$ROOT/artist-radio/${Uri.encode(key.encode())}"
internal fun MediaKey.with(type: MediaType, id: String) = MediaKey(service, type, id)
internal fun artworkRoute(artRes: Int, artUrl: String?, label: String, ratio: Float = 1f): String {
    val safeLabel = label.mediaTextOrNull() ?: "Artwork"
    return "$ROOT/artwork/$artRes?url=${Uri.encode(artUrl.mediaTextOrNull().orEmpty())}&label=${Uri.encode(safeLabel)}&ratio=$ratio"
}
internal fun notesRoute(key: String) = "$ROOT/notes/${Uri.encode(key)}"

/**
 * Routes with a way back: either a Back action (or a named "Home"/"Library" back), or, for the
 * service picker, no action bar at all but a step behind it. The keypad's Back key uses the same
 * set, so it must not pop a screen that offers no way out: Home and Welcome leave the press to
 * the system.
 */
private val KEYPAD_BACK_ROUTES = setOf(
    HISTORY,
    LIBRARY,
    COLLECTION,
    CREATE_PLAYLIST,
    ADD_TO_PLAYLIST,
    ARTIST,
    SEARCH,
    SEARCH_RESULTS,
    SEARCH_SCOPES,
    NOW_PLAYING,
    QUEUE,
    RADIO,
    ARTIST_RADIO,
    SLEEP_TIMER,
    SETTINGS,
    SERVICE_PICKER,
    SIGN_IN,
    // Welcome itself has no left action on the first run; the picker's Help page (same body, Back
    // in the bar) does.
    HELP,
    AUDIO_QUALITY,
    APPEARANCE,
    KEYBOARD,
    ABOUT,
    ARTWORK,
    NOTES
)

internal fun keypadBackIsLeftAction(route: String?, canPop: Boolean): Boolean = canPop && route in KEYPAD_BACK_ROUTES

/** The keypad Back key is the touch/keypad equivalent of the screen's left action. */
internal fun NavHostController.keypadBack(): Boolean {
    if (!keypadBackIsLeftAction(currentDestination?.route, previousBackStackEntry != null)) {
        return false
    }
    return popBackStack()
}

internal val collectionArgs = listOf(navArgument("key") { type = NavType.StringType })
internal val artistArgs = listOf(navArgument("key") { type = NavType.StringType })
internal val addToPlaylistArgs = listOf(navArgument("key") { type = NavType.StringType })

internal fun MediaType.toCollectionType(): CollectionType? = when (this) {
    MediaType.Album -> CollectionType.Album
    MediaType.Playlist -> CollectionType.Playlist
    MediaType.Show -> CollectionType.Show
    else -> null
}
internal val notesArgs = listOf(
    navArgument("key") {
        type = NavType.StringType
        defaultValue = ""
    }
)
internal val artworkArgs = listOf(
    navArgument("artRes") { type = NavType.IntType },
    navArgument("url") {
        type = NavType.StringType
        defaultValue = ""
    },
    navArgument("label") {
        type = NavType.StringType
        defaultValue = "Artwork"
    },
    navArgument("ratio") {
        type = NavType.FloatType
        defaultValue = 1f
    }
)

internal fun NavHostController.openAddToPlaylist(track: Track) {
    navigate(addToPlaylistRoute(SidetrackPlaylistAdd.remember(track)))
}

internal fun NavHostController.openNowPlaying(vm: SidetrackViewModel) {
    vm.dismissNowPlayingNotice()
    if (!popBackStack(NOW_PLAYING, inclusive = false)) {
        navigate(NOW_PLAYING) { launchSingleTop = true }
    }
}

/** A tap on the already-playing item opens Now Playing instead of restarting or toggling it. */
internal fun NavHostController.openTrack(
    vm: SidetrackViewModel,
    item: MediaItem,
    play: () -> Unit
) {
    if (vm.state.value.current?.id == item.id) openNowPlaying(vm) else play()
}
