import SwiftUI

@main
struct LibrelogApp: App {
    @State private var app = AppModel.launch()

    init() {
        #if os(iOS)
        // Screen titles in the brand serif (SPEC.md §14), set once for every
        // navigation bar rather than per screen.
        let ink = PlatformColor.dynamic(light: Palette.grape, dark: 0xf5f4f7)
        let bar = UINavigationBar.appearance()
        if let large = UIFont(name: "Arvo-Bold", size: 32) {
            bar.largeTitleTextAttributes = [.font: UIFontMetrics(forTextStyle: .largeTitle).scaledFont(for: large), .foregroundColor: ink]
        }
        if let inline = UIFont(name: "Arvo-Bold", size: 17) {
            bar.titleTextAttributes = [.font: UIFontMetrics(forTextStyle: .headline).scaledFont(for: inline), .foregroundColor: ink]
        }
        #endif
    }
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(app)
                .task { await app.sync.start() }
                .onOpenURL { url in Task { await app.sync.handle(url: url) } }
                .onChange(of: phase) { _, now in
                    if now == .active { Task { await app.sync.syncNow() } }
                }
        }
        #if os(macOS)
        .defaultSize(width: 1000, height: 720)
        .commands {
            CommandGroup(replacing: .newItem) {
                Button("New Entry") { Task { await app.newEntry() } }
                    .keyboardShortcut("n")
            }
        }
        #endif
    }
}
