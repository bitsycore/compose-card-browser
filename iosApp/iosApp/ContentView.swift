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

 The keyboard safe area is ignored because Compose insets its own text fields; the
 rest is left alone so the top bar sits under the status bar rather than behind it.
 */
struct ContentView: View {

	var body: some View {
		ComposeView()
			.ignoresSafeArea(.keyboard)
	}
}
