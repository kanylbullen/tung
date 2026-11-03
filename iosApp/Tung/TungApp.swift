import BackgroundTasks
import SwiftUI
import TungUI
import UIKit

/// The iPhone app: one window with the ui module's Compose screen in it, and an
/// app delegate for what iOS delivers outside it (push, background refresh).
@main
struct TungApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ComposeScreen().ignoresSafeArea()
        }
        .onChange(of: scenePhase) { _, phase in
            // Leaving the front: ask iOS for a sync in a while, in case no push comes.
            if phase == .background { AppDelegate.scheduleRefresh() }
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

/// Background sync, the iPhone's counterpart of the phone's push and WorkManager
/// (Kotlin's `IosApp.kt` does the work):
/// - The server's silent push (APNs, `content-available`) wakes the app for one
///   sync when another device changed the log. It needs no notification
///   permission; iOS rations silent pushes, so it is not the only way.
/// - A background refresh task, scheduled whenever the app leaves the front,
///   syncs when iOS finds the time (not before 15 minutes; iOS decides when).
final class AppDelegate: NSObject, UIApplicationDelegate {
    static let refreshTask = "io.github.kanylbullen.tung.sync"

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // Must be registered before launch finishes (BGTaskScheduler's rule).
        BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.refreshTask, using: nil) { task in
            Self.handleRefresh(task as! BGAppRefreshTask)
        }
        application.registerForRemoteNotifications()
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        IosAppKt.onApnsToken(token: deviceToken)
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        // No push (the simulator, no network): the app syncs in front and on refresh.
        NSLog("Tung: no APNs token: %@", error.localizedDescription)
    }

    func application(
        _ application: UIApplication,
        didReceiveRemoteNotification userInfo: [AnyHashable: Any],
        fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void
    ) {
        IosAppKt.syncInBackground { ok in completionHandler(ok.boolValue ? .newData : .failed) }
    }

    /// The next refresh, no earlier than 15 minutes from now (the phone's periodic sync).
    static func scheduleRefresh() {
        let request = BGAppRefreshTaskRequest(identifier: refreshTask)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            NSLog("Tung: background refresh not scheduled: %@", error.localizedDescription)
        }
    }

    private static func handleRefresh(_ task: BGAppRefreshTask) {
        scheduleRefresh()
        var finished = false
        task.expirationHandler = {
            if !finished { finished = true; task.setTaskCompleted(success: false) }
        }
        IosAppKt.syncInBackground { ok in
            if !finished { finished = true; task.setTaskCompleted(success: ok.boolValue) }
        }
    }
}
