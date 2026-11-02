import SwiftUI
import TungUI

/// The iPhone app: one window with the ui module's Compose screen in it.
@main
struct TungApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeScreen().ignoresSafeArea()
        }
    }
}

/// `MainViewController()` from the ui module (Kotlin's `MainViewController.kt`) as a SwiftUI view.
struct ComposeScreen: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
