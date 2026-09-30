package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Shared state for tracking dynamic collapsing header measurements and scroll offsets.
 */
@Stable
class CollapsingHeaderState(
    initialHeightOffset: Float = 0f
) {
    var headerHeightPx by mutableFloatStateOf(0f)
        internal set

    var heightOffset by mutableFloatStateOf(initialHeightOffset)
        internal set

    val isCollapsed: Boolean
        get() = headerHeightPx > 0f && heightOffset <= -headerHeightPx + 1f

    val isExpanded: Boolean
        get() = heightOffset >= -1f

    val currentHeaderHeightPx: Float
        get() = (headerHeightPx + heightOffset).coerceAtLeast(0f)

    suspend fun expand(animationDurationMs: Int = 200) {
        if (heightOffset < 0f) {
            Animatable(heightOffset).animateTo(
                targetValue = 0f,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy)
            ) {
                heightOffset = value
            }
        }
    }

    suspend fun collapse(animationDurationMs: Int = 200) {
        if (headerHeightPx > 0f && heightOffset > -headerHeightPx) {
            Animatable(heightOffset).animateTo(
                targetValue = -headerHeightPx,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy)
            ) {
                heightOffset = value
            }
        }
    }

    fun snapTo(offset: Float) {
        heightOffset = offset.coerceIn(-headerHeightPx, 0f)
    }
}

@Composable
fun rememberCollapsingHeaderState(): CollapsingHeaderState {
    return remember { CollapsingHeaderState() }
}

@Composable
fun rememberCollapsingHeaderConnection(
    state: CollapsingHeaderState,
    enabled: Boolean = true
): NestedScrollConnection {
    return remember(state, enabled) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!enabled || state.headerHeightPx <= 0f) return Offset.Zero
                val delta = available.y
                val prevOffset = state.heightOffset
                val newOffset = (prevOffset + delta).coerceIn(-state.headerHeightPx, 0f)
                val consumed = newOffset - prevOffset
                if (consumed != 0f) {
                    state.heightOffset = newOffset
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (!enabled || state.headerHeightPx <= 0f) return Offset.Zero
                if (available.y > 0f) {
                    val prevOffset = state.heightOffset
                    val newOffset = (prevOffset + available.y).coerceIn(-state.headerHeightPx, 0f)
                    val headerConsumed = newOffset - prevOffset
                    if (headerConsumed != 0f) {
                        state.heightOffset = newOffset
                        return Offset(0f, headerConsumed)
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!enabled || state.headerHeightPx <= 0f) return Velocity.Zero
                val current = state.heightOffset
                if (current > -state.headerHeightPx && current < 0f) {
                    val target = when {
                        available.y < -300f -> -state.headerHeightPx // Scrolling down -> collapse
                        available.y > 300f -> 0f                      // Scrolling up -> expand
                        current > -state.headerHeightPx / 2f -> 0f    // Closer to top -> expand
                        else -> -state.headerHeightPx                 // Closer to bottom -> collapse
                    }
                    Animatable(current).animateTo(
                        targetValue = target,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy)
                    ) {
                        state.heightOffset = value
                    }
                    return Velocity(0f, available.y)
                }
                return Velocity.Zero
            }
        }
    }
}

/**
 * Reusable collapsing/hiding header layout shared across screens.
 *
 * Smoothly slides the header out of view as the user scrolls DOWN through the list,
 * and slides it back into view as soon as they scroll UP, even slightly.
 *
 * The content container below starts exactly where the header currently ends (frame-by-frame)
 * using layout offset placement, ensuring zero double-reservation of space and zero blank gap.
 */
@Composable
fun CollapsingHeaderLayout(
    modifier: Modifier = Modifier,
    state: CollapsingHeaderState = rememberCollapsingHeaderState(),
    enabled: Boolean = true,
    header: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    val nestedScrollConnection = rememberCollapsingHeaderConnection(state = state, enabled = enabled)

    // Keep offset clamped when dynamic header height changes
    LaunchedEffect(state.headerHeightPx) {
        if (state.headerHeightPx > 0f && state.heightOffset < -state.headerHeightPx) {
            state.heightOffset = -state.headerHeightPx
        }
    }

    Layout(
        content = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { size ->
                        val newH = size.height.toFloat()
                        if (state.headerHeightPx != newH) {
                            state.headerHeightPx = newH
                        }
                    }
                    .graphicsLayer {
                        translationY = if (enabled) state.heightOffset else 0f
                    }
            ) {
                header()
            }
            Box(
                modifier = Modifier.fillMaxWidth()
            ) {
                content()
            }
        },
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .nestedScroll(nestedScrollConnection)
    ) { measurables, constraints ->
        val headerPlaceable = measurables[0].measure(constraints.copy(minHeight = 0))
        val headerHeight = headerPlaceable.height

        val currentHeaderOffset = if (enabled) state.heightOffset.roundToInt() else 0
        val currentHeaderBottom = (headerHeight + currentHeaderOffset).coerceAtLeast(0)

        val contentMaxHeight = if (constraints.hasBoundedHeight) {
            (constraints.maxHeight - currentHeaderBottom).coerceAtLeast(0)
        } else {
            constraints.maxHeight
        }
        val contentConstraints = constraints.copy(
            minHeight = contentMaxHeight,
            maxHeight = contentMaxHeight
        )
        val contentPlaceable = measurables[1].measure(contentConstraints)

        layout(constraints.maxWidth, constraints.maxHeight) {
            headerPlaceable.placeRelative(0, currentHeaderOffset)
            contentPlaceable.placeRelative(0, currentHeaderBottom)
        }
    }
}
