package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.data.HistoryRepository
import io.github.manuelrtsch.sidetrack.data.QueueRepository
import io.github.manuelrtsch.sidetrack.data.SpotifyCatalog

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
    /** Read side plus [io.github.manuelrtsch.sidetrack.data.SpotifyLibraryWriter]. */
    fun catalog(): SpotifyCatalog

    /** Restores the fixture's starting queue and history when they are missing. */
    fun seed(history: HistoryRepository, queue: QueueRepository)
}

internal object DemoContentSource {
    private const val PACKAGE = "io.github.manuelrtsch.sidetrack.demo."

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
            SidetrackDebug.log("demo content unavailable", it)
        }.getOrNull()
    }
}
