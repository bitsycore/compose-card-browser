package com.bitsycore.toploader.ui.theme

import androidx.compose.runtime.Composable

/**
 * Nothing yet.
 *
 * iOS carries the same problem -- the status bar style follows the view controller, not the app's
 * own theme -- but the fix needs a `UIViewController` to override `preferredStatusBarStyle` on, and
 * that lives in the Swift shell, which has never been linked or run. Left empty rather than guessed.
 */
@Composable
actual fun SyncSystemBarAppearance(isDark: Boolean) = Unit
