package com.bitsycore.toploader.ui.theme

import androidx.compose.runtime.Composable

/** Nothing: a desktop window has no system bars. Its title bar is `WindowChrome`'s job. */
@Composable
actual fun SyncSystemBarAppearance(isDark: Boolean) = Unit
