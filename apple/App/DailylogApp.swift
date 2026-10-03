import SwiftUI

@main
struct DailylogApp: App {
    @State private var app = AppModel.launch()
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
