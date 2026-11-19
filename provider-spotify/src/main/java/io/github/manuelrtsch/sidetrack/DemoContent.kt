package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.data.HistoryRepository
import io.github.manuelrtsch.sidetrack.data.QueueRepository
import io.github.manuelrtsch.sidetrack.data.SpotifyCatalog

/**
 * A fixed catalog for promo and demo builds.
 *
 * The `demo` build type (see the app's `build.gradle.kts`) serves a fictional pack instead of the
 * signed-in account's library, so recordings are reproducible and never show personal data. It is
 * injected into [io.github.manuelrtsch.sidetrack.data.SpotifySession] rather than looked up here,
 * because only the app knows whether this build carries a demo pack.
 */
interface DemoContent {
    /** Read side plus [io.github.manuelrtsch.sidetrack.data.SpotifyLibraryWriter]. */
    fun catalog(): SpotifyCatalog

    /** Restores the fixture's starting queue and history when they are missing. */
    fun seed(history: HistoryRepository, queue: QueueRepository)
}
