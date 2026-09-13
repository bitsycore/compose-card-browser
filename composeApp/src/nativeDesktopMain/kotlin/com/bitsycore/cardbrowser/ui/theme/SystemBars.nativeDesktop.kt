package com.bitsycore.cardbrowser.ui.theme

import androidx.compose.runtime.Composable

/** Nothing, for the same reason as the JVM desktop: an SDL window has no system bars. */
@Composable
actual fun SyncSystemBarAppearance(isDark: Boolean) = Unit
