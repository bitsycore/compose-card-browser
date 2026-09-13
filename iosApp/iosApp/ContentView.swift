import ComposeApp
import SwiftUI
import UIKit

/*
 The app, in something SwiftUI can hold.
 */
struct ComposeView: UIViewControllerRepresentable {

	func makeUIViewController(context: Context) -> UIViewController {
		MainViewControllerKt.MainViewController()
	}

	func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

/*
 The root view.

 The whole safe area is ignored, and that is iOS's half of `enableEdgeToEdge()` in MainActivity --
 there is no edge-to-edge flag to set on iOS, only a layout to decline to shrink. Compose owns the
 insets from here: all ten screens use the Material 3 Scaffold defaults, and FullscreenCardViewer
 wants the whole screen and pads its own controls with `WindowInsets.safeDrawing`.

 Insetting here as well left a status bar's worth of empty space above the top bar on an iPhone SE:
 SwiftUI shrank the view, then Compose inset the content inside it again. `.ignoresSafeArea()`
 covers every region including the keyboard, which is the one this used to name.
 */
struct ContentView: View {

	var body: some View {
		ComposeView()
            .ignoresSafeArea()
	}
}
