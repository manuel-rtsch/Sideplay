package io.github.manuelrtsch.sideplay.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks whether the device currently has a usable network. Playback and catalog failures are
 * mapped to [SpotifyFailure.Network] when this says there is no connection, so an abrupt signal
 * loss is not reported as an expired Spotify session.
 */
class NetworkMonitor(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val _online = MutableStateFlow(isCurrentlyOnline())
    val online: StateFlow<Boolean> = _online.asStateFlow()

    val isOnline: Boolean get() = _online.value

    init {
        runCatching {
            connectivity?.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        _online.value = isCurrentlyOnline()
                    }

                    override fun onLost(network: Network) {
                        _online.value = isCurrentlyOnline()
                    }

                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities
                    ) {
                        _online.value = isCurrentlyOnline()
                    }

                    override fun onUnavailable() {
                        _online.value = false
                    }
                }
            )
        }
    }

    private fun isCurrentlyOnline(): Boolean {
        val manager = connectivity ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
