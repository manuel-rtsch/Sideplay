package io.github.manuelrtsch.sidetrack.data

import org.json.JSONObject

/**
 * Reads an optional string without turning JSON `null` into the literal text `"null"`.
 *
 * Plex sends fields such as `authToken` and `accessToken` as explicit JSON `null` while a PIN is
 * unapproved or a server has no token. `JSONObject.optString` returns `"null"` for those, which is
 * not empty and would otherwise be sent as a header value.
 */
internal fun JSONObject.stringOrNull(name: String): String? = if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }
