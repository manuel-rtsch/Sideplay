package io.github.manuelrtsch.sidetrack

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import hrln.interactive.sidekit.design.action.ActionMotion
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitCard
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.theme.SidekitTheme

/** Where Sidetrack's source lives; the About page's "Source code" action opens it in a browser. */
internal const val SOURCE_CODE_URL = "https://github.com/manuel-rtsch/Sidetrack"

/**
 * Hands [SOURCE_CODE_URL] to whatever browser the device has. A ROM without one leaves the press
 * with nothing to do — there is no notice bar on the About page and nothing to undo — so the
 * missing activity is swallowed rather than crashing the app the user is reading about it in.
 */
internal fun openSourceCode(context: Context) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, SOURCE_CODE_URL.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: ActivityNotFoundException) {
        // No browser installed.
    }
}

private data class Licence(val software: String, val licence: String)

/** The git-describe tail Gradle puts behind the tag, plus the build-type suffixes. */
private val buildSuffix = Regex("""-\d+-g[0-9a-f]+(?:-dirty)?$""")

/**
 * The tag part of the Gradle version name: `0.3.5-11-g55e317f-dirty-dev` becomes `0.3.5`.
 * The About page shows this, so the screenshot does not change with every build; the commit
 * count and hash are revealed only when the version card is clicked.
 */
internal fun plainVersionName(versionName: String): String = versionName
    .removeSuffix("-dev")
    .removeSuffix("-demo")
    .replace(buildSuffix, "")
    .removeSuffix("-dirty")
    .ifBlank { versionName }

/**
 * Which Spotify path this APK was built for. The native bridge needs a Rust toolchain and the
 * NDK at build time, so building without it is legitimate for local iteration — but such a build
 * falls back to the public Web API, which rate-limits the desktop client ID and stalls. The
 * version card names the engine so a tester's bug report does not have to be guessed at.
 */
internal val spotifyEngineLabel: String
    get() = if (BuildConfig.NATIVE_SPOTIFY) {
        "Native Spotify engine"
    } else {
        "Spotify Web API, no native bridge"
    }

private val licences = listOf(
    Licence("Sidetrack", "MIT"),
    Licence("Sidekit", "MIT"),
    Licence("librespot", "MIT"),
    Licence("Jellyfin SDK for Kotlin", "LGPL-3.0"),
    Licence("AndroidX / Jetpack Compose", "Apache 2.0"),
    Licence("Kotlin", "Apache 2.0")
)

/**
 * Version, author, the Sidekit SDK Sidetrack is built on, and the licences of everything it
 * ships. Reached from Settings rather than crowding the settings list itself.
 */
@Composable
fun SidetrackAboutScreen(onBack: () -> Unit, backLabel: String = "Settings") {
    val context = LocalContext.current
    val packageInfo = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0) }
    val versionName = packageInfo.versionName.orEmpty()
    val versionCode = packageInfo.longVersionCode
    val listState = rememberLazyListState()
    // The build number stays out of the screenshot: the plain tag is enough until it is asked
    // for, and a commit-count build number would change the image on every build.
    var showBuild by rememberSaveable { mutableStateOf(false) }

    SidekitScaffold(
        title = "About",
        actions = sidetrackBack(backLabel, onBack) + SideAction(
            "sourceCode",
            "Source code",
            tone = ActionTone.Muted,
            motion = ActionMotion.Up,
            onClick = { openSourceCode(context) }
        )
    ) { padding ->
        SidekitList(padding, state = listState) {
            item(key = "version") {
                SidekitCard(
                    Modifier.listFade(listState, 0).fillMaxWidth(),
                    onClick = { showBuild = true }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SidekitText("Version", style = SidekitTheme.typography.headline)
                        SidekitText(
                            "Sidetrack ${plainVersionName(versionName)}",
                            style = SidekitTheme.typography.label,
                            maxLines = Int.MAX_VALUE
                        )
                        if (showBuild) {
                            SidekitText(
                                "Build $versionCode",
                                style = SidekitTheme.typography.caption,
                                color = SidekitTheme.colors.surfaceA6,
                                maxLines = Int.MAX_VALUE
                            )
                            SidekitText(
                                spotifyEngineLabel,
                                style = SidekitTheme.typography.caption,
                                color = SidekitTheme.colors.surfaceA6,
                                maxLines = Int.MAX_VALUE
                            )
                        }
                    }
                }
            }
            item(key = "author") {
                SidekitCard(Modifier.listFade(listState, 1).fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SidekitText("Author", style = SidekitTheme.typography.headline)
                        SidekitText("Manuel Rottschäfer", style = SidekitTheme.typography.label, maxLines = Int.MAX_VALUE)
                        SidekitText(
                            "github.com/manuel-rtsch",
                            style = SidekitTheme.typography.caption,
                            color = SidekitTheme.colors.surfaceA6,
                            maxLines = Int.MAX_VALUE
                        )
                    }
                }
            }
            item(key = "sidekit") {
                SidekitCard(Modifier.listFade(listState, 2).fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SidekitText("Built with Sidekit", style = SidekitTheme.typography.headline)
                        SidekitText(
                            "Sidekit SDK ${BuildConfig.SIDEKIT_VERSION}",
                            style = SidekitTheme.typography.label,
                            maxLines = Int.MAX_VALUE
                        )
                        SidekitText(
                            "Built by Harlen Postill",
                            style = SidekitTheme.typography.label,
                            maxLines = Int.MAX_VALUE
                        )
                        SidekitText(
                            "github.com/HarlenPostill/Sidekit",
                            style = SidekitTheme.typography.caption,
                            color = SidekitTheme.colors.surfaceA6,
                            maxLines = Int.MAX_VALUE
                        )
                    }
                }
            }
            item(key = "licenses") {
                SidekitCard(Modifier.listFade(listState, 3).fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SidekitText("Licenses", style = SidekitTheme.typography.headline)
                        licences.forEach { entry ->
                            LicenceRow(entry)
                        }
                    }
                }
            }
        }
    }
}

/**
 * One table row: the software name on the left, its licence right-aligned and centred against the
 * name's height, so a wrapped name still keeps the licence on its vertical middle.
 */
@Composable
private fun LicenceRow(entry: Licence) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SidekitText(
            entry.software,
            Modifier.weight(1f),
            style = SidekitTheme.typography.label,
            maxLines = Int.MAX_VALUE
        )
        SidekitText(
            entry.licence,
            style = SidekitTheme.typography.label,
            color = SidekitTheme.colors.surfaceA6,
            textAlign = TextAlign.End,
            maxLines = Int.MAX_VALUE
        )
    }
}
