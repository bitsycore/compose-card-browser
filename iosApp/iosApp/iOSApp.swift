import ComposeApp
import SwiftUI

/*
 The iOS shell, and there is deliberately almost nothing in it.

 Everything the app is lives in `composeApp` and arrives here as one UIViewController
 (see `MainViewController.kt`). What the shell owns is what only an app can own:
 the launch and the Info.plist.
 */
@main
struct iOSApp: App {

	init() {
		// Before the first screen asks Koin for anything. MainViewController() does
		// this too and it is idempotent, so neither side has to know which ran first.
		MainViewControllerKt.startCardBrowser()
	}

	var body: some Scene {
		WindowGroup {
			ContentView()
		}
	}
}
