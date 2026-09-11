package com.bitsycore.cardbrowser.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Expressive's speed without its overshoot.
 *
 * ## Why this exists rather than one of the two built-in schemes
 *
 * Neither ships settled motion. Read out of material3 1.12.0-alpha03's own tokens rather than
 * assumed:
 *
 * | `MotionScheme.expressive()` | damping | stiffness |
 * | --- | --- | --- |
 * | default spatial | **0.8** | 380 |
 * | fast spatial | **0.6** | 800 |
 * | slow spatial | **0.8** | 200 |
 * | default / fast / slow effects | 1.0 | 1600 / 3800 / 800 |
 *
 * A damping ratio below 1.0 is underdamped: the animation travels past where it is going and comes
 * back. That is the bounce. It is in the three **spatial** springs and nowhere else -- the effects
 * springs are already critically damped, which makes sense, since they drive alpha and colour and
 * there is nothing there to overshoot.
 *
 * `MotionScheme.standard()` is not the answer either: its default spatial is 0.9 and 700, so it
 * bounces too and is merely quicker about it. Switching schemes would have made the wobble smaller
 * rather than removed it, and cost the expressive pace on the way.
 *
 * So all six are restated at `DampingRatioNoBouncy`, which is 1.0 and cannot overshoot by
 * construction, keeping expressive's own stiffnesses. The three effects specs come out byte for
 * byte the same as expressive's -- they are here so that all six read from one table rather than
 * leaving the next person to work out which half was changed.
 */
private object NoBounceMotionScheme : MotionScheme {

	override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = settled(DEFAULT_SPATIAL_STIFFNESS)

	override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = settled(FAST_SPATIAL_STIFFNESS)

	override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = settled(SLOW_SPATIAL_STIFFNESS)

	override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = settled(DEFAULT_EFFECTS_STIFFNESS)

	override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = settled(FAST_EFFECTS_STIFFNESS)

	override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = settled(SLOW_EFFECTS_STIFFNESS)

	/**
	 * One critically damped spring at [stiffness].
	 *
	 * No `visibilityThreshold`, which is what the built-in schemes do too: the threshold belongs to
	 * the type being animated, and a scheme hands the same spec to every one of them.
	 */
	private fun <T> settled(stiffness: Float): FiniteAnimationSpec<T> =
		spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = stiffness)

	// Expressive's own stiffnesses, so the pace is unchanged. Read out of
	// `ExpressiveMotionTokens` in material3 1.12.0-alpha03 rather than guessed. Restated rather
	// than derived, because `MotionScheme` hands back a `FiniteAnimationSpec` -- there is no way
	// to reach into one, keep its stiffness and replace its damping.
	private const val DEFAULT_SPATIAL_STIFFNESS = 380f
	private const val FAST_SPATIAL_STIFFNESS = 800f
	private const val SLOW_SPATIAL_STIFFNESS = 200f
	private const val DEFAULT_EFFECTS_STIFFNESS = 1600f
	private const val FAST_EFFECTS_STIFFNESS = 3800f
	private const val SLOW_EFFECTS_STIFFNESS = 800f
}

/**
 * The app's colours, in both modes.
 *
 * Material 3's own defaults, tinted toward the game's palette rather than replaced: the standard
 * scheme already gets contrast ratios right, and hand-picking twenty roles is a good way to ship a
 * theme that fails in dark mode. Card art supplies all the colour a browser needs; the chrome
 * around it stays quiet on purpose so it does not compete with the cards.
 */

private val ACCENT = Color(0xFF7A5CD6)
private val ACCENT_DARK = Color(0xFFBFA8FF)

private val LIGHT_COLORS = lightColorScheme(
	primary = ACCENT,
	onPrimary = Color.White,
	secondary = Color(0xFF5B6273),
)

private val DARK_COLORS = darkColorScheme(
	primary = ACCENT_DARK,
	onPrimary = Color(0xFF231544),
	secondary = Color(0xFFC1C6D6),
)

/**
 * Wraps content in the app's theme.
 *
 * ## Expressive, and why
 *
 * `MaterialExpressiveTheme` rather than plain `MaterialTheme`. The project is on material3
 * 1.12.0-alpha03 -- bumped from 1.9.0 for the wavy progress indicators -- and was using exactly one
 * component out of that, while every other component's motion still ran on the standard scheme.
 * Supplying a scheme here is what makes one set of durations apply to the whole app rather than to
 * the one screen that opted in by hand.
 *
 * The scheme is [NoBounceMotionScheme], not `MotionScheme.expressive()` -- see there for why.
 *
 * Shapes and typography are deliberately left at their defaults, for the same reason the colour
 * scheme is barely touched: card art supplies all the character a browser needs, and hand-picking
 * a shape scale is a good way to ship something that looks arbitrary.
 *
 * @param useDarkTheme defaults to whatever the system is set to, so the app follows the platform
 *   rather than insisting on its own mode
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CardBrowserTheme(
	useDarkTheme: Boolean = isSystemInDarkTheme(),
	content: @Composable () -> Unit,
) {
	MaterialExpressiveTheme(
		colorScheme = if (useDarkTheme) DARK_COLORS else LIGHT_COLORS,
		motionScheme = NoBounceMotionScheme,
	) {
		// The app's floor, and it was missing.
		//
		// Every screen paints its own background through its `Scaffold`, which looks complete right
		// up until something does not cover the whole window -- and then whatever is behind shows
		// through, which is the platform's window background rather than anything this theme chose.
		// It is white on both Android and desktop, so the dark theme flashed white in three places
		// at once: the uncovered strip while a screen slides in, the area around a container
		// transform growing out of a row, and the corners of any transition that scales.
		//
		// One `Surface` at the root fixes all of them, because none of those gaps was ever a
		// transition bug -- they were the absence of a background to see.
		Surface(
			modifier = Modifier.fillMaxSize(),
			color = MaterialTheme.colorScheme.background,
			content = content,
		)
	}
}

/**
 * Whether the dark scheme is the one currently in force.
 *
 * Derived from the scheme rather than from `isSystemInDarkTheme()`, and that distinction matters
 * now that the theme is a user preference: someone running the app in Light on a dark phone would
 * otherwise have every "is it dark?" decision answered backwards. Asking the colours that are
 * actually painting is the only answer that survives the override.
 */
@Composable
fun isDarkTheme(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f
