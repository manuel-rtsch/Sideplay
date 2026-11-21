package io.github.manuelrtsch.sidetrack

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.navigation
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.sdk.media.RemoteArtwork
import io.github.manuelrtsch.sidetrack.data.AppSessions
import java.io.IOException

/**
 * Registers the Sidetrack screens. All screens share one account state holder.
 *
 * When [hostBacked] is true a compatible Sidekit launcher is installed and its configuration is
 * readable, so it owns appearance and keyboard settings; Sidetrack hides its own copies.
 */
fun NavGraphBuilder.sidetrackGraph(
    nav: NavHostController,
    startDestination: String = HOME,
    hostBacked: Boolean = false,
    onKeypadBackOverride: ((() -> Boolean)?) -> Unit = {}
) {
    navigation(startDestination = startDestination, route = ROOT) {
        libraryGraph(nav)
        playbackGraph(nav, onKeypadBackOverride)
        settingsGraph(nav, hostBacked)
    }
}

@Composable
internal fun MissingCollectionScreen(
    onBack: () -> Unit,
    title: String = "Sidetrack",
    loading: Boolean = false,
    loadingTitle: String = "Loading…",
    error: String? = null,
    errorTitle: String = "Couldn't load this",
    missingTitle: String = "This collection isn't available",
    onRetry: (() -> Unit)? = null
) {
    SidekitScaffold(title = title, actions = sidetrackBack("Back", onBack)) { padding ->
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.fillMaxSize().padding(padding),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            when {
                loading -> LoadingScreen(title = loadingTitle)

                error != null -> StatusScreen(
                    errorTitle,
                    hint = error,
                    actionLabel = "Try again",
                    onAction = onRetry
                )

                else -> StatusScreen(
                    missingTitle,
                    hint = "Go back and choose another item"
                )
            }
        }
    }
}

@Composable
internal fun NavBackStackEntry.sidetrackViewModel(nav: NavHostController): SidetrackViewModel {
    val parent = remember(this) { nav.getBackStackEntry(ROOT) }
    val context = LocalContext.current.applicationContext
    val localSettings = remember(context) { SidetrackLocalSettings(context) }
    val appSession = remember(context) { AppSessions.get(context) }
    // Observe the selected service so provisioning it recreates the ViewModel for its runtime.
    appSession.active.collectAsStateWithLifecycle()
    val runtime = appSession.runtime
    val openBrowser: suspend (String) -> Unit = remember(context) {
        { url ->
            // A ROM without a browser (a bare GSI, a stripped keypad build) has nothing to hand
            // the sign-in page to. Say so instead of surfacing ActivityNotFoundException.
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: ActivityNotFoundException) {
                throw IOException("No browser is installed to finish sign-in", e)
            }
        }
    }
    val vm = viewModel<SidetrackViewModel>(
        parent,
        key = "sidetrack-${runtime.service.value}",
        factory = remember(runtime, openBrowser, context) {
            viewModelFactory {
                initializer {
                    SidetrackViewModel(
                        catalog = runtime.catalog,
                        writer = runtime.writer,
                        queue = runtime.queue,
                        history = runtime.history,
                        episodeProgressRepository = runtime.episodeProgress,
                        episodeMetaRepository = runtime.episodeMeta,
                        session = runtime,
                        openBrowser = openBrowser,
                        clearArtworkCache = { RemoteArtwork.clear(context) },
                        loadSearchHistory = { localSettings.searchHistory },
                        saveSearchHistory = { localSettings.searchHistory = it }
                    )
                }
            }
        }
    )
    // The position tick is only useful while the nav host is on screen. The parent entry's
    // lifecycle tracks the whole graph, so one observer per destination is enough and the
    // boolean is idempotent when several destinations are composed during a transition.
    DisposableEffect(parent, vm) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> vm.setUiVisible(true)
                Lifecycle.Event.ON_STOP -> vm.setUiVisible(false)
                else -> Unit
            }
        }
        parent.lifecycle.addObserver(observer)
        onDispose { parent.lifecycle.removeObserver(observer) }
    }
    return vm
}
