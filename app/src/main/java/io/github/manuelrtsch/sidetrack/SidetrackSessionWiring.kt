package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.data.ServiceRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Connects the ViewModel to the signed-in service runtime: account and settings mirrors, the
 * transport handlers the media session calls back into, and the recovery notices that have to
 * reach the user.
 *
 * It exists so the ViewModel stays a facade: the wiring only translates runtime events into the
 * store and the controllers it is given.
 */
internal class SidetrackSessionWiring(
    private val scope: CoroutineScope,
    private val session: ServiceRuntime,
    private val store: UiStateStore,
    private val clock: PlaybackClock,
    private val driver: PlaybackDriver,
    private val account: AccountController,
    private val library: LibraryController,
    private val onNotice: (String) -> Unit
) {
    private val nextHandler = { driver.next() }
    private val previousHandler = { driver.previous() }
    private val seekHandler = { position: Long -> driver.seekTo(position) }

    fun install() {
        val runtime = session
        scope.launch {
            runtime.signedIn.collect { signed ->
                store.update { state -> state.copy(signedIn = signed) }
                if (signed) account.load() else account.clear()
            }
        }
        scope.launch {
            runtime.playback.buffering.collect { buffering ->
                if (!buffering) clock.anchorCurrent()
                store.update { state -> state.copy(isBuffering = buffering) }
            }
        }
        scope.launch {
            runtime.settings.collect { settings ->
                store.update { state ->
                    state.copy(
                        audioQuality = settings.audioQuality,
                        normalization = settings.normalization,
                        gapless = settings.gapless,
                        autoplay = settings.autoplay
                    )
                }
            }
        }
        runtime.playback.onNext = nextHandler
        runtime.playback.onPrevious = previousHandler
        runtime.playback.onSeek = seekHandler
        // A play command from a headset or the notification can arrive before anything is
        // loaded: a cold process restores the queue but not the native player, so resuming
        // would only deliver silence. Restore already anchored the saved offset here.
        runtime.playback.onResumeWithoutSource = {
            val item = store.value.current
            if (item != null && item.service != ServiceId.Local) {
                val resume = clock.value
                store.update { state -> state.copy(isPlaying = true) }
                driver.startRemote(item, resume, resuming = resume > 0L)
            }
        }
        // Playback upkeep (a stall that had to reconnect) has to reach the user: from the
        // outside it looked like the player was running while nothing was audible.
        scope.launch {
            runtime.playback.notice.collect { text ->
                if (text != null) {
                    onNotice(text)
                    runtime.playback.emitNotice(null)
                }
            }
        }
        // Starting offline leaves the library empty. Retry as soon as a network appears, so
        // the user does not have to restart the app or hunt for a refresh action.
        runtime.network?.let { network ->
            scope.launch {
                var wasOnline = network.online.value
                network.online.collect { online ->
                    if (online && !wasOnline && store.value.signedIn) {
                        account.load()
                        // Cache-first: this refetches the open category when the offline attempt
                        // left it empty or failed, and leaves a list that loaded fine alone.
                        library.ensure(store.value.filter)
                    }
                    wasOnline = online
                }
            }
        }
    }

    fun detach() {
        session?.playback?.let {
            if (it.onNext === nextHandler) it.onNext = null
            if (it.onPrevious === previousHandler) it.onPrevious = null
            if (it.onSeek === seekHandler) it.onSeek = null
        }
    }
}
