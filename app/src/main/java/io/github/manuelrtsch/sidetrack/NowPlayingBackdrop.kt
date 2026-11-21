package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.components.SidekitItemRow
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.sdk.media.RemoteArtwork
import io.github.manuelrtsch.sidetrack.data.ArtworkAccess
import io.github.manuelrtsch.sidetrack.playback.SleepTimerState
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

/*
 * The experimental song Now Playing layout: the cover fills the screen as a blurred backdrop, so
 * it no longer spends any of the 480 x 640 panel on itself and the title and artist can take the
 * room back.
 *
 * It is a separate layout rather than a variant of [NowPlayingScreen] on purpose. The
 * experiment is about what the screen becomes once the artwork stops being a tile: a poster of
 * type over the record. Episodes keep the existing screen, where the artwork still carries the
 * show and the notes need the height.
 *
 * The backdrop is decorative except for one gesture: tapping it opens the album, which is what
 * tapping the artwork did on the old layout. It is not a focus target — the keypad reaches the
 * album through Options — so a 480 dp tap area never lands in the D-pad order.
 */

/** How much the cover is softened behind the controls. Enough to read type over it, not a glass. */
private val BackdropBlur = 11.dp

/**
 * The Now Playing backdrop setting, shared by the screen and Appearance. It persists in
 * [SidetrackLocalSettings]; the listener keeps a second screen (or a second Now Playing instance)
 * in step when the pref changes elsewhere.
 */
@Stable
internal class NowPlayingBackdropSetting internal constructor(private val settings: SidetrackLocalSettings) {
    var enabled by mutableStateOf(settings.nowPlayingBackdrop)
        private set

    fun toggle() = set(!enabled)

    fun set(value: Boolean) {
        if (value == enabled) return
        enabled = value
        settings.nowPlayingBackdrop = value
    }

    internal fun observe(): () -> Unit = settings.observeNowPlayingBackdrop {
        enabled = settings.nowPlayingBackdrop
    }
}

@Composable
internal fun rememberNowPlayingBackdropSetting(): NowPlayingBackdropSetting {
    val context = LocalContext.current.applicationContext
    val setting = remember(context) { NowPlayingBackdropSetting(SidetrackLocalSettings(context)) }
    DisposableEffect(setting) {
        val stop = setting.observe()
        onDispose(stop)
    }
    return setting
}

/**
 * The cover, scaled to cover the panel's width (cropped at the long axis, never letterboxed) and
 * blurred. The cover is square and the panel is 480 x 640, so the square grows past the width and
 * the sides run off the edge. It runs down to the action bar's top edge, where the transport scrim
 * is solid black, so its bottom edge is never visible.
 *
 * The blurred image is its own composable taking only the primitives of the track. The screen
 * re-composes once a second while the sleep timer ticks (it reads the timer for the Options
 * label); keeping the art behind a stable boundary means that tick does not re-record the blur.
 *
 * The remote bitmap is awaited before the blur layer is composed. A [Modifier.blur] layer records
 * its content once: while the artwork loads, Sidekit's artwork image draws a placeholder icon
 * through a draw modifier, and that placeholder is what the layer keeps, so the screen showed a
 * blurred dot instead of a cover. Keying the layer on the bitmap also gives the next track a
 * fresh layer rather than the last track's.
 */
@Composable
internal fun ArtworkBackdrop(
    item: MediaItem,
    onOpenAlbum: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val dark = SidekitTheme.colors.dark
    val scrim = remember(dark) {
        Brush.verticalGradient(
            0f to dark.copy(alpha = 0.12f),
            0.45f to dark.copy(alpha = 0.24f),
            1f to dark.copy(alpha = 0.36f)
        )
    }
    BoxWithConstraints(modifier.clipToBounds()) {
        // The image runs down to the action bar's top edge, where the transport scrim is solid
        // black, so its bottom edge is never visible. Stopping higher left a hard line under a
        // half-black scrim on covers with a bright bottom.
        val spacing = SidekitTheme.spacing
        val actionBarTop = maxHeight - spacing.pagePadding - spacing.actionBarHeight
        val side = maxOf(maxWidth, actionBarTop)
        BackdropImage(item.artRes, item.artUrl, side, Modifier.align(Alignment.TopCenter))
        // A tap on the empty screen still opens the album, as tapping the cover did on the old
        // layout. It is pointer input rather than clickable so the 480 dp area never becomes a
        // focus target; the keypad reaches the album through Options.
        val open by rememberUpdatedState(onOpenAlbum)
        Box(
            Modifier
                .matchParentSize()
                .background(scrim)
                .then(
                    if (onOpenAlbum == null) {
                        Modifier
                    } else {
                        Modifier
                            .pointerInput(item.id) { detectTapGestures { open?.invoke() } }
                            .semantics {
                                onClick(label = "Open album") {
                                    open?.invoke()
                                    open != null
                                }
                            }
                    }
                )
        )
    }
}

@Composable
private fun BackdropImage(artRes: Int, artUrl: String?, side: Dp, modifier: Modifier = Modifier) {
    val remoteUrl = artUrl.mediaTextOrNull()
    Box(modifier.size(side)) {
        when {
            // The demo pack (and the screenshot tests) ship their covers as drawables, which are
            // available on the first frame, so they can go straight into the blur layer.
            remoteUrl != null -> RemoteBackdrop(remoteUrl, side)

            artRes != 0 -> Image(
                painterResource(artRes),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().blur(BackdropBlur),
                contentScale = ContentScale.Crop
            )
        }
    }
}

@Composable
private fun RemoteBackdrop(url: String, side: Dp) {
    val context = LocalContext.current.applicationContext
    // Ask for less than the drawn size: the blur removes the detail anyway, and a 640 px cover
    // would cost three times the bytes for a picture nobody sees sharply.
    val px = with(LocalDensity.current) { (side * 0.6f).roundToPx() }.coerceIn(96, 768)
    val bitmap by produceState<ImageBitmap?>(null, url, px) {
        value = try {
            RemoteArtwork.load(context, url, px, px, ArtworkAccess.current())?.asImageBitmap()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
    bitmap?.let { image ->
        key(image) {
            Image(
                image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().blur(BackdropBlur),
                contentScale = ContentScale.FillBounds,
                filterQuality = FilterQuality.Medium
            )
        }
    }
}

/**
 * The song screen's body over [ArtworkBackdrop]: a poster block of title and artist in the upper
 * half, then the sleep timer record, the seek bar and the transport pinned above the action bar.
 * The weighted spacers keep the title optically centred without hard-coding a height for the
 * 480 x 640 panel.
 */
@Composable
internal fun BackdroppedNowPlayingBody(
    playback: PlaybackUiState,
    current: Track,
    playbackPositionMs: StateFlow<Long>,
    sleepTimer: StateFlow<SleepTimerState>,
    padding: PaddingValues,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onArtist: () -> Unit
) {
    val colors = SidekitTheme.colors
    val typography = SidekitTheme.typography
    val artistAction = onArtist.takeIf { current.artistId.mediaIdOrNull() != null }
    Column(
        Modifier.fillMaxSize().padding(padding),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SidekitText(
                current.title,
                Modifier.fillMaxWidth(),
                style = typography.title,
                color = colors.light,
                maxLines = 3,
                textAlign = TextAlign.Center
            )
            if (current.creator.isNotBlank()) {
                SidekitText(
                    current.creator,
                    Modifier.fillMaxWidth().clickable(
                        enabled = artistAction != null,
                        onClickLabel = "Open artist",
                        role = Role.Button,
                        onClick = { artistAction?.invoke() }
                    ),
                    style = typography.body,
                    color = colors.light.copy(alpha = 0.82f),
                    textAlign = TextAlign.Center
                )
            }
        }
        Spacer(Modifier.weight(0.7f))
        NowPlayingSleepTimer(sleepTimer)
        Spacer(Modifier.height(6.dp))
        NowPlayingSeek(
            playbackPositionMs,
            current.durationMs,
            onSeek,
            buffering = playback.isBuffering
        )
        Spacer(Modifier.height(6.dp))
        SongTransportRow(
            playback,
            onPlayPause = onPlayPause,
            onPrevious = onPrevious,
            onNext = onNext,
            onShuffle = onShuffle,
            onRepeat = onRepeat
        )
    }
}
