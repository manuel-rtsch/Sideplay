package io.github.manuelrtsch.sidetrack

/**
 * Builds the service-scoped keys and collection cache keys.
 *
 * Every key Sidetrack stores is namespaced by the active service, so the service has to travel
 * with the code that builds them. Keeping that in a small value object instead of four private
 * methods on the ViewModel lets the state reducers below stay pure and lets an extracted
 * collaborator build keys without reaching back for the ViewModel.
 */
internal class MediaKeys(private val service: ServiceId) {

    fun collection(type: CollectionType, id: String) = "${service.value}:${type.route}:$id"

    fun media(type: CollectionType, id: String) = MediaKey(service, type.mediaType, id)

    fun artist(id: String) = MediaKey(service, MediaType.Artist, id)

    fun track(id: String) = MediaKey(service, MediaType.Track, id)
}

/** The identity of a row inside a library section; the type prefix keeps ids from colliding. */
internal fun libraryItemId(item: Any): String? = when (item) {
    is Track -> item.id
    is Playlist -> item.id
    is Album -> item.id
    is Artist -> item.id
    is Show -> item.id
    else -> null
}

/** A row identity that stays unique across the item types a library section can hold. */
internal fun libraryItemKey(item: Any): String = when (item) {
    is Track -> "t${item.id}"
    is Playlist -> "p${item.id}"
    is Album -> "a${item.id}"
    is Artist -> "r${item.id}"
    is Show -> "s${item.id}"
    else -> item.toString()
}
