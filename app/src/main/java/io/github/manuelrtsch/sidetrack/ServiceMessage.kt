package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.data.ServiceException
import io.github.manuelrtsch.sidetrack.data.ServiceFailure

/**
 * The one line the user sees when a service call fails.
 *
 * It lives outside the ViewModel because every path that talks to a service needs it — library
 * paging, search, radio, saves and playback — and none of them should phrase a failure their own
 * way.
 */
internal fun serviceMessage(error: Exception, online: Boolean): String = when ((error as? ServiceException)?.failure) {
    ServiceFailure.Unavailable -> "Playback unavailable in this build"

    ServiceFailure.NotConnected -> "Playback isn't ready — try again"

    ServiceFailure.AuthenticationExpired -> "Session expired — sign in again"

    ServiceFailure.Network -> if (!online) "No internet connection" else "Couldn't reach the service"

    ServiceFailure.RateLimited -> "The service is rate-limiting — try again"

    ServiceFailure.TrackUnavailable -> "This track isn't available"

    ServiceFailure.NotAllowed -> "The service won't allow that"

    // Keep the status subtitle to one line; the full exception is already logged by callers.
    ServiceFailure.Unknown, null -> "Something went wrong — try again"
}
