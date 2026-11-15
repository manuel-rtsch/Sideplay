package io.github.manuelrtsch.sideplay

import io.github.manuelrtsch.sideplay.data.SpotifyCatalog
import io.github.manuelrtsch.sideplay.data.SpotifyHistoryRepository
import io.github.manuelrtsch.sideplay.data.SpotifyQueueRepository

/**
 * A fixed catalog for promo and demo builds.
 *
 * The `demo` build type (see `build.gradle.kts`) serves a fictional pack instead of the signed-in
 * account's library, so recordings are reproducible and never show personal data. Release builds
 * never contain an implementation and [DemoContentSource.current] stays null.
 *
 * The implementation is looked up by name because the fixture sources must not be referenceable
 * from `main`: a release build has to compile and ship without them.
 */
interface DemoContent {
    /** Read side plus [io.github.manuelrtsch.sideplay.data.SpotifyLibraryWriter]. */
    fun catalog(): SpotifyCatalog

    /** Restores the fixture's starting queue and history when they are missing. */
    fun seed(history: SpotifyHistoryRepository, queue: SpotifyQueueRepository)
}

internal object DemoContentSource {
    private const val PACKAGE = "io.github.manuelrtsch.sideplay.demo."

    /**
     * The demo pack, or null in every normal build. `-PdemoPack=fictional` selects the original
     * fictional backup pack; the default real pack shows real songs.
     */
    private val implementation: String
        get() = PACKAGE + if (BuildConfig.DEMO_PACK == "fictional") "FictionalPack" else "RealPack"

    val current: DemoContent? by lazy {
        if (!BuildConfig.DEMO_CONTENT) return@lazy null
        runCatching {
            val type = Class.forName(implementation)
            type.getDeclaredField("INSTANCE").get(null) as DemoContent
        }.onFailure {
            SideplayDebug.log("demo content unavailable", it)
        }.getOrNull()
    }
}
