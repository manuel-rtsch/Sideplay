package io.github.manuelrtsch.sideplay.playback

/**
 * Anything in the process that can hold the platform media session.
 *
 * Deliberately knows nothing about tracks, queues or media sources: only what the process needs
 * to keep two players from talking over each other.
 *
 * Main thread only, like the players that implement it.
 */
interface MediaSessionOwner {
    /** Stable identity, e.g. "sideplay". Unique within the process. */
    val sessionId: String

    /** Notification channel this owner publishes on. */
    val notificationChannelId: String

    /** User-visible name of [notificationChannelId], used when the channel is created. */
    val notificationChannelName: String

    /** Notification id this owner publishes on. */
    val notificationId: Int

    /** True while this owner is actually producing audio. */
    val isActive: Boolean

    /** Stops playback without clearing the queue, so the user can resume where they were. */
    fun pause()
}

/**
 * The process's media sessions. Only one owner is active at a time: activating one pauses the
 * others. Registration is explicit so an owner that never plays is still known (and can be
 * paused) the first time another takes over.
 *
 * A plain class rather than an object so tests can hold their own registry; [MediaSessions] is
 * the process-wide one.
 *
 * Main thread only.
 */
open class MediaSessionRegistry {
    private val owners = LinkedHashSet<MediaSessionOwner>()

    /** The owner currently allowed to play, or null. */
    var active: MediaSessionOwner? = null
        private set

    val registered: List<MediaSessionOwner> get() = owners.toList()

    fun register(owner: MediaSessionOwner) {
        owners += owner
    }

    fun unregister(owner: MediaSessionOwner) {
        owners -= owner
        if (active === owner) active = null
    }

    /**
     * Makes [owner] the active session. Every other owner that is playing is paused first, so
     * starting one provider stops the other rather than mixing them.
     */
    fun activate(owner: MediaSessionOwner) {
        if (active === owner) return
        // Claim first: a paused owner calls deactivate() from inside pause(), which must not
        // clear the owner that just took over.
        active = owner
        owners.toList()
            .filter { it !== owner && it.isActive }
            .forEach { it.pause() }
    }

    /** Clears [owner] if it is still the active session. */
    fun deactivate(owner: MediaSessionOwner) {
        if (active === owner) active = null
    }
}

/** The one registry for the process. Main thread only. */
object MediaSessions : MediaSessionRegistry()
