package com.bitsycore.cardbrowser.platform

import java.awt.Frame
import java.awt.GraphicsEnvironment
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The native title-bar call is really wired up.
 *
 * Not a test of what the title bar looks like -- nothing here can see it. It is a test that the
 * binding resolves: that `dwmapi` loads, that the declared signature matches what Windows expects,
 * and that Windows answers `S_OK` rather than an error code. Those are the three ways this breaks
 * silently, and all three leave an app that starts perfectly with a pale strip across the top.
 *
 * Skipped rather than failed off Windows and in a headless environment, because neither is a
 * defect: the whole file is about one platform's window decoration.
 */
class WindowChromeTest {

	@Test
	fun `windows accepts both a dark and a light title bar`() {
		if (!WindowChrome.isSupported || GraphicsEnvironment.isHeadless()) return

		val vFrame = Frame()
		try {
			// Displayable, not visible. The pointer the call needs comes from the native peer,
			// which `addNotify` creates; showing the window would flash it onto someone's desktop
			// for no extra coverage.
			vFrame.addNotify()

			assertTrue(WindowChrome.applyTitleBarTheme(vFrame, isDark = true), "dark was refused")
			assertTrue(WindowChrome.applyTitleBarTheme(vFrame, isDark = false), "light was refused")
		} finally {
			vFrame.dispose()
		}
	}
}
