package com.bitsycore.cardbrowser.data

import com.bitsycore.cardbrowser.data.settings.BrowsingPreferences
import com.bitsycore.cardbrowser.data.settings.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The theme preference.
 *
 * Small, but the distinction it encodes is the whole reason it is not a `Boolean`: "follow the
 * system" is a third answer, and a boolean would have to freeze the platform's current setting at
 * the moment the preference was written.
 */
class ThemeModeTest {

	@Test
	fun `system follows the platform in both directions`() {
		assertTrue(ThemeMode.SYSTEM.isDark(isSystemDark = true))
		assertFalse(ThemeMode.SYSTEM.isDark(isSystemDark = false))
	}

	@Test
	fun `light and dark override the platform rather than tracking it`() {
		assertFalse(ThemeMode.LIGHT.isDark(isSystemDark = true))
		assertTrue(ThemeMode.DARK.isDark(isSystemDark = false))
	}

	@Test
	fun `the default follows the system`() {
		// A fresh install adopts the device's setting rather than picking a side for the user.
		assertEquals(ThemeMode.SYSTEM, BrowsingPreferences().themeMode)
	}

	@Test
	fun `every mode is offered and labelled`() {
		// The settings row renders `entries` directly, so a mode added without a label would show
		// up as a blank chip rather than not at all.
		assertEquals(3, ThemeMode.entries.size)
		assertTrue(ThemeMode.entries.all { it.label.isNotBlank() })
	}
}
