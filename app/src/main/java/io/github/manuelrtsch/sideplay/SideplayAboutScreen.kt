package io.github.manuelrtsch.sideplay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.components.SidekitCard
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.theme.SidekitTheme

private data class Licence(val software: String, val licence: String)

private val licences = listOf(
    Licence("Sideplay", "MIT"),
    Licence("Sidekit", "MIT"),
    Licence("librespot", "MIT"),
    Licence("AndroidX / Jetpack Compose", "Apache 2.0"),
    Licence("Kotlin", "Apache 2.0")
)

/**
 * Version, author, the Sidekit SDK Sideplay is built on, and the licences of everything it
 * ships. Reached from Settings rather than crowding the settings list itself.
 */
@Composable
fun SideplayAboutScreen(onBack: () -> Unit, backLabel: String = "Settings") {
    val context = LocalContext.current
    val packageInfo = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0) }
    val versionName = packageInfo.versionName.orEmpty()
    val versionCode = packageInfo.longVersionCode
    val listState = rememberLazyListState()

    SidekitScaffold(
        title = "About",
        actions = spotifyBack(backLabel, onBack)
    ) { padding ->
        SidekitList(padding, state = listState) {
            item(key = "version") {
                SidekitCard(Modifier.listFade(listState, 0).fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SidekitText("Version", style = SidekitTheme.typography.headline)
                        SidekitText(
                            "Sideplay $versionName ($versionCode)",
                            style = SidekitTheme.typography.label,
                            maxLines = Int.MAX_VALUE
                        )
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
