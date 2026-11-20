package io.github.manuelrtsch.sidetrack.data

import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One HTTP call, kept as a seam so the catalog and auth tests need no real server. */
fun interface NavidromeTransport {
    suspend fun get(url: String): JSONObject
}

/** The real transport; all parsing stays in [NavidromeApi]. */
class HttpNavidromeTransport : NavidromeTransport {
    override suspend fun get(url: String): JSONObject = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
        }
        try {
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                Log.w("SidetrackNavidrome", "HTTP $status ${url.substringBefore('?')}")
                throw ServiceException(ServiceFailure.Network, "Navidrome returned HTTP $status")
            }
            runCatching { JSONObject(text) }
                .getOrElse { throw ServiceException(ServiceFailure.Unknown, "Navidrome returned an unreadable response", it) }
        } catch (e: ServiceException) {
            throw e
        } catch (e: IOException) {
            throw ServiceException(ServiceFailure.Network, "Couldn't reach Navidrome", e)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000
    }
}
