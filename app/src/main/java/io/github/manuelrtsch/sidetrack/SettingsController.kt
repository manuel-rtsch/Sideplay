package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.data.HistoryEntry
import io.github.manuelrtsch.sidetrack.data.HistoryRepository
import io.github.manuelrtsch.sidetrack.data.QueueRepository
import io.github.manuelrtsch.sidetrack.data.ServiceRuntime
import io.github.manuelrtsch.sidetrack.data.cacheSizeBytes
import io.github.manuelrtsch.sidetrack.data.clearAudioCache
import io.github.manuelrtsch.sidetrack.data.setAudioQuality
import io.github.manuelrtsch.sidetrack.data.setGapless
import io.github.manuelrtsch.sidetrack.data.setNormalization

/**
 * Playback preferences, cache housekeeping and the Clear history / Undo pair.
 *
 * These all write through the same screen state and the same active runtime, and Clear history
 * has to keep the removed entries around until the Undo offer expires. Keeping them together
 * means the ViewModel only maps screens to destinations.
 */
internal class SettingsController(
    private val store: UiStateStore,
    private val session: ServiceRuntime?,
    private val history: HistoryRepository,
    private val queue: QueueRepository,
    private val effects: PlaybackEffects,
    private val clearArtworkCache: () -> Unit
) {

    private var clearedHistory: List<HistoryEntry> = emptyList()

    fun setAudioQuality(quality: AudioQuality) {
        if (quality == store.value.audioQuality) return
        store.update { it.copy(audioQuality = quality) }
        session?.setAudioQuality(quality)
    }

    fun toggleNormalization() {
        val enabled = !store.value.normalization
        store.update { it.copy(normalization = enabled) }
        session?.setNormalization(enabled)
    }

    fun toggleGapless() {
        val enabled = !store.value.gapless
        store.update { it.copy(gapless = enabled) }
        session?.setGapless(enabled)
    }

    fun toggleAutoplay() {
        val enabled = !store.value.autoplay
        store.update { it.copy(autoplay = enabled) }
        session?.setAutoplay(enabled)
    }

    fun clearHistory(): Boolean {
        clearedHistory = history.clear()
        val snapshot = queue.clearHistory()
        effects.applyQueue(snapshot, syncNowPlaying = false)
        val cleared = clearedHistory.isNotEmpty()
        // A real clear is confirmed by the Undo offer and the dropping count; an empty list would
        // otherwise look like a dead key.
        store.update {
            if (cleared) it.copy(recent = emptyList()) else it.say("Nothing to clear")
        }
        return cleared
    }

    /** Puts the last cleared history back, timestamps included, so Undo restores the real order. */
    fun undoClearHistory() {
        if (clearedHistory.isEmpty()) return
        history.restoreAll(clearedHistory)
        clearedHistory = emptyList()
        store.update { it.copy(recent = history.items(), notice = null) }
    }

    fun clearCache() {
        clearArtworkCache()
        session?.clearAudioCache()
        refreshCacheSize()
    }

    fun refreshCacheSize() {
        store.update { it.copy(cacheSizeBytes = session?.cacheSizeBytes() ?: 0L) }
    }
}
