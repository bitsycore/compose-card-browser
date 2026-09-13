package com.bitsycore.cardbrowser.ui.theme

import androidx.compose.runtime.Composable

/**
 * Tells the platform's own bars which way round the app's colours are.
 *
 * The same trap [isDarkTheme] records, one layer out. `enableEdgeToEdge()` with no arguments picks
 * its bar style from the *system* configuration, so an app running in Light on a phone set to Dark
 * drew light status-bar icons over a white background: invisible, and nothing in the app could see
 * it because nothing in the app had been asked.
 *
 * The theme is the only place that knows which scheme actually won, so it is the place that says so.
 * Platforms with no such control implement this as nothing.
 *
 * @param isDark whether the dark scheme is painting -- not whether the OS is in dark mode
 */
@Composable
expect fun SyncSystemBarAppearance(isDark: Boolean)
