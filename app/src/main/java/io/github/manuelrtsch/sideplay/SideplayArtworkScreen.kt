package io.github.manuelrtsch.sideplay

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import hrln.interactive.sidekit.design.components.SidekitIconImage
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.sdk.media.RemoteArtworkImage

/**
 * A generic, full-width view of one piece of artwork. The pill keeps the item shape and takes its
 * height from the source's aspect ratio, so a square cover reads as a square and a wider image
 * keeps its proportions instead of being stretched.
 */
@Composable
fun SideplayArtworkScreen(
    @DrawableRes artRes: Int,
    artUrl: String?,
    label: String,
    onBack: () -> Unit,
    aspectRatio: Float = 1f
) {
    val colors = SidekitTheme.colors
    val painter = if (artUrl == null && artRes != 0) painterResource(artRes) else null
    val intrinsic = painter?.intrinsicSize
    val ratio = if (intrinsic != null && intrinsic.width.isFinite() && intrinsic.height.isFinite() &&
        intrinsic.width > 0f && intrinsic.height > 0f
    ) {
        intrinsic.width / intrinsic.height
    } else {
        aspectRatio
    }.coerceIn(0.25f, 4f)
    val listState = rememberLazyListState()

    // No title: the image is the page, and the 640 px panel cannot fit a full-width square plus
    // a header above the action bar.
    SidekitScaffold(title = null, actions = spotifyBack("Back", onBack)) { padding ->
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
                        artUrl != null -> RemoteArtworkImage(
                            artUrl,
                            Modifier.fillMaxSize(),
                            placeholder = SidekitIcon.Album,
                            contentDescription = label
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
