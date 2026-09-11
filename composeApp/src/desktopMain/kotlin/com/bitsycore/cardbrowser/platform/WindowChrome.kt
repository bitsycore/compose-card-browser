package com.bitsycore.cardbrowser.platform

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import java.awt.Window

/**
 * Paints the window's title bar to match the app's theme.
 *
 * ## Why this needs a native call
 *
 * A dark app under a light title bar is the one piece of chrome the app cannot draw itself, and it
 * is the one users notice: every other window on a dark Windows 11 desktop has a dark title bar,
 * and this one looked like it had been left out of the theme.
 *
 * Neither AWT nor Compose exposes it. On Windows it is `DwmSetWindowAttribute`, which means either
 * a native call or an undecorated window with the title bar drawn in Compose -- and the second
 * costs Aero Snap, the system window buttons and Windows 11's own rounded corners, which is a lot
 * to give up for a colour. So: JNA, one function, one attribute.
 *
 * ## What each platform gets
 *
 * - **Windows** follows the app, including when the user changes the theme while it is running.
 * - **macOS** follows the *system*, set once before AWT starts -- see [prepareForDarkTitleBars].
 *   Per-window switching is not something the JDK offers, so a user who picks a theme that
 *   disagrees with their system keeps the system's title bar.
 * - **Linux** is the window manager's business and nothing here touches it.
 *
 * Every failure is swallowed. A title bar is decoration: an app that will not start because a
 * cosmetic native call was refused is a far worse bug than a pale strip at the top of the window.
 */
object WindowChrome {

	/**
	 * Sets the appearance AWT will use, before it has a chance to read it.
	 *
	 * Call from `main` before any window exists. macOS reads this property once while initialising,
	 * so setting it later does nothing at all -- which is why this is separate from
	 * [applyTitleBarTheme] rather than being the first thing it does.
	 *
	 * `system` rather than a fixed appearance, so macOS follows the desktop. Nothing is set on the
	 * other platforms: on Windows the property is meaningless and on Linux it is the window
	 * manager's decision.
	 */
	fun prepareForDarkTitleBars() {
		if (!isMac) return
		runCatching { System.setProperty("apple.awt.application.appearance", "system") }
	}

	/**
	 * Asks Windows to draw [window]'s title bar dark, or light.
	 *
	 * Safe to call repeatedly and safe to call with the same value twice; on anything but Windows
	 * it does nothing. The window must already be displayable -- the pointer this needs does not
	 * exist before that -- so call it from an effect inside the window rather than around it.
	 *
	 * @return whether the platform accepted it. The app ignores this and should: there is nothing
	 *   useful to do about a title bar that stayed the wrong colour. It is returned so a test can
	 *   tell "Windows said no" from "this is not Windows", which are the same silence otherwise --
	 *   and a native binding that quietly stopped working is exactly the kind of thing that would
	 *   go unnoticed until someone looked at the window.
	 */
	fun applyTitleBarTheme(window: Window, isDark: Boolean): Boolean {
		if (!isWindows) return false
		return runCatching {
			val vHandle = Native.getWindowPointer(window) ?: return false
			val vValue = IntByReference(if (isDark) 1 else 0)
			// 20 on Windows 11 and on Windows 10 from build 18985. Older builds used 19 for the
			// same thing, and answer non-zero for 20 rather than ignoring it -- so the fallback is
			// driven by the return code rather than by trying to read a build number.
			val vResult = Dwmapi.INSTANCE.DwmSetWindowAttribute(vHandle, DARK_MODE, vValue, 4)
			if (vResult == 0) {
				true
			} else {
				Dwmapi.INSTANCE.DwmSetWindowAttribute(vHandle, DARK_MODE_BEFORE_18985, vValue, 4) == 0
			}
		}.getOrDefault(false)
	}

	/** Whether [applyTitleBarTheme] can do anything here, for a test that must not assert on Linux. */
	val isSupported: Boolean get() = isWindows

	/** The one function used, declared rather than pulled in from `jna-platform`'s Win32 bindings. */
	private interface Dwmapi : Library {

		fun DwmSetWindowAttribute(hwnd: Pointer, attribute: Int, value: IntByReference, size: Int): Int

		companion object {

			val INSTANCE: Dwmapi by lazy { Native.load("dwmapi", Dwmapi::class.java) }
		}
	}

	private const val DARK_MODE = 20

	private const val DARK_MODE_BEFORE_18985 = 19

	private val osName: String get() = System.getProperty("os.name").orEmpty().lowercase()

	private val isWindows: Boolean get() = osName.startsWith("windows")

	private val isMac: Boolean get() = osName.startsWith("mac")
}
