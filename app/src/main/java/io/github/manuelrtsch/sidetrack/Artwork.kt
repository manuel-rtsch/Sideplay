package io.github.manuelrtsch.sidetrack

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.components.SidekitIconImage
import hrln.interactive.sidekit.design.components.SidekitTile
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.sdk.media.RemoteArtworkImage
import io.github.manuelrtsch.sidetrack.data.ArtworkAccess

/**
 * Artwork shown beside text inside a 104 dp list tile. 80 dp leaves a 12 dp margin on every side
 * and gives the cover a little more presence than the two-line text block next to it.
 */
internal val ListArtworkSize = 80.dp

/** Square artwork: remote when the catalog has a URL, otherwise a local fixture or icon. */
@Composable
fun Cover(
    @DrawableRes artRes: Int,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = SidekitTheme.shapes.control,
    bordered: Boolean = false,
    contentDescription: String? = null,
    artUrl: String? = null,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    // When the caller already knows the square's size, request exactly that bitmap instead of
    // letting RemoteArtworkImage measure itself with a BoxWithConstraints subcomposition. Long
    // lists pay that subcomposition once per row, so the list rows pass their fixed size.
    requestSize: Dp? = null
) {
    val colors = SidekitTheme.colors
    val remoteUrl = artUrl.mediaTextOrNull()
    Box(
        modifier
            .clip(shape)
            .background(colors.surfaceA2)
            .then(if (bordered) Modifier.border(1.dp, colors.light, shape) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, onClick = onClick) else Modifier)
    ) {
        when {
            remoteUrl != null && requestSize != null -> RemoteArtworkImage(
                remoteUrl,
                width = requestSize,
                height = requestSize,
                modifier = Modifier.fillMaxSize(),
                placeholder = SidekitIcon.Album,
                contentDescription = contentDescription,
                // The signed-in service's artwork auth, when it needs one. Empty for Spotify and
                // Navidrome, whose URLs already carry their credentials.
                headers = ArtworkAccess.current()
            )

            remoteUrl != null -> RemoteArtworkImage(
                remoteUrl,
                Modifier.fillMaxSize(),
                placeholder = SidekitIcon.Album,
                contentDescription = contentDescription,
                headers = ArtworkAccess.current()
            )

            artRes != 0 -> Image(
                painter = painterResource(artRes),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            else -> SidekitIconImage(
                SidekitIcon.Album,
                Modifier.align(Alignment.Center),
                contentDescription = contentDescription
            )
        }
    }
}

@Composable
fun LibraryTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    SidekitTile(
        modifier,
        color = SidekitTheme.colors.successA1,
        onClick = onClick,
        onClickLabel = "Library"
    ) {
        SidekitIconImage(
            SidekitIcon.Album,
            size = SidekitTheme.spacing.tileIconSize,
            contentDescription = "Library"
        )
    }
}

@Composable
fun ArtworkTile(
    @DrawableRes artRes: Int,
    label: String,
    modifier: Modifier = Modifier,
    artUrl: String? = null,
    onClick: (() -> Unit)? = null
) {
    SidekitTile(modifier, onClick = onClick, onClickLabel = if (onClick != null) "View artwork" else null) {
        Cover(artRes, Modifier.fillMaxSize(), shape = SidekitTheme.shapes.item, contentDescription = label, artUrl = artUrl)
    }
}
