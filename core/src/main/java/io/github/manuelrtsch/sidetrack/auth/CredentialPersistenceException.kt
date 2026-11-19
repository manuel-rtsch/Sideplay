package io.github.manuelrtsch.sidetrack.auth

/**
 * The session was obtained from Spotify but could not be stored on this device, so it will not
 * survive the next launch. The message is user-facing: the sign-in itself succeeded.
 */
class CredentialPersistenceException(message: String, cause: Throwable? = null) : Exception(message, cause)
