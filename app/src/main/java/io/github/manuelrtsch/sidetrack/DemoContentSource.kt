package io.github.manuelrtsch.sidetrack

/**
 * The demo pack this build carries, or null in every normal build.
 *
 * The fixture sources must not be referenceable from `main` — a release build has to compile and
 * ship without them — so the implementation is looked up by name once. The app passes the result
 * to [io.github.manuelrtsch.sidetrack.data.SpotifySession]; the provider never looks it up itself.
 */
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
