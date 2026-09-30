package com.example.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.IntSize

/**
 * Standardized Animation Tokens & Transitions for Amar Dukan.
 * Ensures consistent frame timing, predictable curves, and zero-jank screen transitions.
 */
object AnimationTokens {
    // Timing constants (ms)
    const val DURATION_SCREEN_ENTER = 280
    const val DURATION_SCREEN_EXIT = 220
    const val DURATION_MICRO = 140
    const val DURATION_ACCORDION = 240
    const val DURATION_DIALOG = 200

    // Standard Easing curves
    val StandardEasing = FastOutSlowInEasing
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

    // Standard Tween Specs
    fun <T> screenEnterSpec(): FiniteAnimationSpec<T> = tween(
        durationMillis = DURATION_SCREEN_ENTER,
        easing = FastOutSlowInEasing
    )

    fun <T> screenExitSpec(): FiniteAnimationSpec<T> = tween(
        durationMillis = DURATION_SCREEN_EXIT,
        easing = FastOutSlowInEasing
    )

    fun <T> microSpec(): FiniteAnimationSpec<T> = tween(
        durationMillis = DURATION_MICRO,
        easing = FastOutSlowInEasing
    )

    fun <T> accordionSpec(): FiniteAnimationSpec<T> = tween(
        durationMillis = DURATION_ACCORDION,
        easing = FastOutSlowInEasing
    )

    val contentSizeSpec: FiniteAnimationSpec<IntSize> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium
    )

    // Screen Transition Definitions for Navigation
    val ScreenEnterTransition: EnterTransition = slideInHorizontally(
        animationSpec = screenEnterSpec()
    ) { fullWidth -> (fullWidth / 4) } + fadeIn(
        animationSpec = tween(durationMillis = DURATION_SCREEN_ENTER, easing = LinearOutSlowInEasing)
    )

    val ScreenExitTransition: ExitTransition = slideOutHorizontally(
        animationSpec = screenExitSpec()
    ) { fullWidth -> -(fullWidth / 6) } + fadeOut(
        animationSpec = tween(durationMillis = DURATION_SCREEN_EXIT, easing = FastOutSlowInEasing)
    )

    val ScreenPopEnterTransition: EnterTransition = slideInHorizontally(
        animationSpec = screenEnterSpec()
    ) { fullWidth -> -(fullWidth / 6) } + fadeIn(
        animationSpec = tween(durationMillis = DURATION_SCREEN_ENTER, easing = LinearOutSlowInEasing)
    )

    val ScreenPopExitTransition: ExitTransition = slideOutHorizontally(
        animationSpec = screenExitSpec()
    ) { fullWidth -> (fullWidth / 4) } + fadeOut(
        animationSpec = tween(durationMillis = DURATION_SCREEN_EXIT, easing = FastOutSlowInEasing)
    )
}
