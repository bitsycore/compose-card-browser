package com.bitsycore.toploader.ui.theme

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Drives the status and navigation bar icons off the scheme the app is actually painting.
 *
 * A `SideEffect` rather than a `LaunchedEffect`: `enableEdgeToEdge()` runs in `onCreate`, and on a
 * configuration change it runs again with its own idea of the mode. Applying this on every
 * successful composition means the app's answer is the last one written either way.
 */
@Composable
actual fun SyncSystemBarAppearance(isDark: Boolean) {
	val vView = LocalView.current
	if (vView.isInEditMode) return
	SideEffect {
		val vWindow = vView.context.activity()?.window ?: return@SideEffect
		WindowCompat.getInsetsController(vWindow, vView).apply {
			// Light *bars* means dark icons, which is what a light app wants.
			isAppearanceLightStatusBars = !isDark
			isAppearanceLightNavigationBars = !isDark
		}
	}
}

/** The activity behind a view's context, which may be wrapped by the theme or by Compose. */
private tailrec fun android.content.Context.activity(): Activity? = when (this) {
	is Activity -> this
	is ContextWrapper -> baseContext.activity()
	else -> null
}
