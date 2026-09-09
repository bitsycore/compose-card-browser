package com.bitsycore.cardbrowser.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

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
 * @param useDarkTheme defaults to whatever the system is set to, so the app follows the platform
 *   rather than insisting on its own mode
 */
@Composable
fun CardBrowserTheme(
	useDarkTheme: Boolean = isSystemInDarkTheme(),
	content: @Composable () -> Unit,
) {
	MaterialTheme(
		colorScheme = if (useDarkTheme) DARK_COLORS else LIGHT_COLORS,
		content = content,
	)
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
