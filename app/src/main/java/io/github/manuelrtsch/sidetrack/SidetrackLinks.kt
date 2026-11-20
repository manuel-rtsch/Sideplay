package io.github.manuelrtsch.sidetrack

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.navigation
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A tap on the playback notification opens Sidetrack at Now Playing instead of the launcher.
 * The activity records the request here; the nav host consumes it once it is composed.
 */
object SidetrackLinks {
    const val ACTION_OPEN_NOW_PLAYING = "io.github.manuelrtsch.sidetrack.action.OPEN_NOW_PLAYING"

    private val pending = MutableStateFlow(false)

    fun handle(intent: Intent?): Boolean {
        if (intent?.action != ACTION_OPEN_NOW_PLAYING) return false
        pending.value = true
        return true
    }

    /** Test seam: a unit test must not leave a pending navigation for the next activity. */
    internal fun reset() {
        pending.value = false
    }

    @Composable
    fun Follow(nav: NavHostController) {
        val open by pending.collectAsStateWithLifecycle()
        LaunchedEffect(open) {
            if (!open) return@LaunchedEffect
            pending.value = false
            nav.navigate(NOW_PLAYING) { launchSingleTop = true }
        }
    }
}
