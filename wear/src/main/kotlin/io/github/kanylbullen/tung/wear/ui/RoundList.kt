package io.github.kanylbullen.tung.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListAnchorType
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition

/** The screen's diameter in dp: the shorter side, which on a round watch is both. */
@Composable
fun screenDiameter(): Dp {
    val configuration = LocalConfiguration.current
    return minOf(configuration.screenWidthDp, configuration.screenHeightDp).dp
}

/**
 * The padding of a list on this screen, as Horologist's responsive padding
 * computes it: 5.2 % of the width on each side and, on a round screen, enough
 * at the top and bottom that a full-width chip there keeps its rounded ends on
 * the circle (and below [TimeText]). The first and last items can therefore
 * be scrolled to the edge without being cut, and every item to the centre.
 */
@Composable
fun rememberListPadding(): PaddingValues {
    val configuration = LocalConfiguration.current
    val diameter = screenDiameter().value
    val round = configuration.isScreenRound
    return remember(diameter, round) {
        val side = diameter * RoundGeometry.LIST_SIDE_FRACTION
        val vertical = if (round) RoundGeometry.chipTopInset(diameter, diameter - 2 * side) else 24f
        PaddingValues(start = side.dp, end = side.dp, top = vertical.dp, bottom = vertical.dp + 8.dp)
    }
}

/** A list that starts at its first item (not centred on the second), as [RoundList] lays it out. */
@Composable
fun rememberRoundListState(): ScalingLazyListState =
    rememberScalingLazyListState(initialCenterItemIndex = 0, initialCenterItemScrollOffset = 0)

/**
 * A screen that is a list, for a round watch: [TimeText] at the top (the
 * padding keeps every item below it), a vignette, a [PositionIndicator] while
 * it scrolls, and the bezel or crown scrolling it (wear-compose 1.4's
 * ScalingLazyColumn handles rotary input by default once it has focus, which
 * it asks for itself).
 */
@Composable
fun RoundList(
    state: ScalingLazyListState = rememberRoundListState(),
    content: ScalingLazyListScope.() -> Unit,
) {
    Scaffold(
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        positionIndicator = { PositionIndicator(scalingLazyListState = state) },
    ) {
        ScalingLazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = rememberListPadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Top),
            anchorType = ScalingLazyListAnchorType.ItemStart,
            autoCentering = null,
            content = content,
        )
    }
}

/**
 * Body text in a list: centred, with more inset than a chip
 * ([RoundGeometry.TEXT_SIDE_FRACTION] more on each side), so that a block of
 * several lines at the top or bottom of the list stays on the circle.
 */
@Composable
fun ListText(text: String, color: Color = TungColors.muted, style: TextStyle = MaterialTheme.typography.caption1) {
    val inset = screenDiameter() * RoundGeometry.TEXT_SIDE_FRACTION
    Text(
        text = text,
        color = color,
        textAlign = TextAlign.Center,
        style = style,
        modifier = Modifier.fillMaxWidth().padding(horizontal = inset),
    )
}
