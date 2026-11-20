package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The compact-rows setting has to change the measured row, not just the artwork or the type: the
 * point is five rows where four fit. This reads the composed row's bounds so a Sidekit tile that
 * keeps its own fixed height cannot pass unnoticed.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompactRowsTest {
    @get:Rule val compose = createComposeRule()

    private val track = MockSpotifyDataSource.albums.first().tracks.first()

    private fun rowHeight(compact: Boolean): Float {
        compose.setContent {
            SidekitPreviewFrame {
                CompositionLocalProvider(LocalCompactRows provides compact) {
                    val rows = rememberTrackRows(
                        SavedUiState(),
                        onSave = {},
                        onEnsureSaved = {},
                        onAddToQueue = {}
                    )
                    LazyColumn {
                        item(key = track.id) {
                            TrackRow(
                                track.id,
                                track,
                                rows,
                                Modifier.testTag("row"),
                                onClick = {}
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag("row").getBoundsInRoot()
        return (bounds.bottom - bounds.top).value
    }

    @Test
    fun `the default row keeps its four-to-a-screen height`() {
        assertEquals(104f, rowHeight(compact = false), 0.5f)
    }

    @Test
    fun `a compact row leaves room for a fifth`() {
        assertEquals(82f, rowHeight(compact = true), 0.5f)
    }
}
