package com.bitsycore.cardbrowser.platform

/**
 * Opens a URL in whatever the platform uses for the web.
 *
 * Kept as an interface with a per-platform binding rather than folded into the Cardmarket code, so
 * that building a link and launching one stay separable: `CardmarketLinkBuilder` is pure and
 * testable, and this is the part that cannot be tested without a device.
 */
interface LinkOpener {

	/**
	 * Opens [url] outside the app.
	 *
	 * Returns false when the platform refused, so a screen can say so rather than appear to do
	 * nothing. Never throws: a missing browser is not worth a crash.
	 */
	fun open(url: String): Boolean
}
