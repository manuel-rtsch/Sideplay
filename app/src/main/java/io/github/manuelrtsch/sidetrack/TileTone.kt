package io.github.manuelrtsch.sidetrack

import androidx.compose.ui.graphics.Color

enum class TileTone {
    Neutral,
    Play,
    Library,
    On,
    Muted
    ;

    fun colour(colors: hrln.interactive.sidekit.design.theme.SidekitColors): Color = when (this) {
        Neutral -> colors.surfaceA3
        Play -> colors.emphasisA1
        Library -> colors.successA1
        On -> colors.surfaceA5
        Muted -> colors.surfaceA2
    }
}
