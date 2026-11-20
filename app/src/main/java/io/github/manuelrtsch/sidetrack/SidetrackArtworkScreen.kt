package io.github.manuelrtsch.sidetrack

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import hrln.interactive.sidekit.design.components.SidekitIconImage
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.sdk.media.RemoteArtwork
import io.github.manuelrtsch.sidetrack.data.ArtworkAccess
import kotlinx.coroutines.CancellationException

/**
 * A generic, full-width view of one piece of artwork. The pill keeps the item shape and takes its
 * height from the source's aspect ratio, so a square cover reads as a square and a wider image
 * keeps its proportions instead of being stretched.
 */
@Composable
fun SidetrackArtworkScreen(
    @DrawableRes artRes: Int,
    artUrl: String?,
    label: String,
    onBack: () -> Unit,
    aspectRatio: Float = 1f
) {
    val colors = SidekitTheme.colors
    // A JSON null reaches us as the literal "null" through native catalogs. Treat it as absent
    // here too, so an old persisted item or a stray route cannot start a doomed fetch.
    val remoteUrl = artUrl.mediaTextOrNull()
    val painter = if (remoteUrl == null && artRes != 0) painterResource(artRes) else null
    val intrinsic = painter?.intrinsicSize
    val ratio = if (intrinsic != null && intrinsic.width.isFinite() && intrinsic.height.isFinite() &&
        intrinsic.width > 0f && intrinsic.height > 0f
    ) {
        intrinsic.width / intrinsic.height
    } else {
        aspectRatio
    }.coerceIn(0.25f, 4f)
    val listState = rememberLazyListState()
    // Null url and no local drawable is the app's "this item has no artwork" state. Do not ask
    // RemoteArtwork to fetch a fallback: a failed or invalid fetch is what used to reach the
    // SDK's cache trim and take the process down.
    val knownNoArtwork = remoteUrl == null && painter == null
    var loadFailed by remember(remoteUrl, artRes) { mutableStateOf(false) }
    val unavailable = knownNoArtwork || loadFailed

    // No title: the image is the page, and the 640 px panel cannot fit a full-width square plus
    // a header above the action bar.
    SidekitScaffold(title = null, actions = sidetrackBack("Back", onBack)) { padding ->
        if (unavailable) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                StatusScreen(
                    "No artwork available",
                    hint = "There is no cover art to show for this item."
                )
            }
        } else {
            SidekitList(padding, state = listState) {
                item(key = "artwork") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .listFade(listState, 0)
                            .aspectRatio(ratio)
                            .clip(SidekitTheme.shapes.item)
                            .background(colors.surfaceA2),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            remoteUrl != null -> RemoteArtworkContent(
                                remoteUrl,
                                label,
                                Modifier.fillMaxSize(),
                                onUnavailable = { loadFailed = true }
                            )

                            painter != null -> Image(
                                painter,
                                contentDescription = label,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )

                            else -> SidekitIconImage(SidekitIcon.Album, contentDescription = label)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Loads remote artwork and reports a null or failed fetch instead of leaving the placeholder up
 * forever. The artwork screen needs that signal to replace itself with [StatusScreen]; the SDK's
 * [RemoteArtworkImage] cannot expose it.
 */
@Composable
private fun RemoteArtworkContent(
    url: String,
    label: String,
    modifier: Modifier = Modifier,
    onUnavailable: () -> Unit
) {
    val context = LocalContext.current.applicationContext
    val density = LocalDensity.current
    val inspection = LocalInspectionMode.current
    val headers = ArtworkAccess.current()
    var image by remember(url, headers) { mutableStateOf<ImageBitmap?>(null) }

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val widthPx = with(density) { maxWidth.roundToPx() }
        val heightPx = with(density) { maxHeight.roundToPx() }
        LaunchedEffect(url, widthPx, heightPx, headers) {
            if (inspection || widthPx <= 0 || heightPx <= 0) return@LaunchedEffect
            val loaded: Bitmap? = try {
                RemoteArtwork.cached(context, url, widthPx, heightPx, headers)
                    ?: RemoteArtwork.load(context, url, widthPx, heightPx, headers)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (loaded == null) {
                onUnavailable()
            } else {
                image = loaded.asImageBitmap()
            }
        }
        val bitmap = image
        if (bitmap != null) {
            Image(
                bitmap,
                contentDescription = label,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            SidekitIconImage(SidekitIcon.Album, Modifier.align(Alignment.Center), contentDescription = label)
        }
    }
}
