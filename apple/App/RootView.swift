import SwiftUI

enum Route: Hashable {
    case entry(String)
}

enum Tab: String, CaseIterable, Identifiable {
    case today, calendar, timeline, search, settings
    var id: String { rawValue }

    var title: LocalizedStringKey {
        switch self {
        case .today: "Today"
        case .calendar: "Calendar"
        case .timeline: "Timeline"
        case .search: "Search"
        case .settings: "Settings"
        }
    }

    var symbol: String {
        switch self {
        case .today: "sun.max"
        case .calendar: "calendar"
        case .timeline: "list.bullet.below.rectangle"
        case .search: "magnifyingglass"
        case .settings: "gearshape"
        }
    }
}

/// Tabs on iPhone, a sidebar on iPad and Mac (SPEC.md §5).
struct RootView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var tab: Tab = .today
    @State private var path = NavigationPath()

    var body: some View {
        Group {
            #if os(macOS)
            sidebarLayout
            #else
            if sizeClass == .regular { sidebarLayout } else { tabLayout }
            #endif
        }
        .tint(Color.primary)
        .onChange(of: app.openEntryID) { _, id in
            guard let id else { return }
            path.append(Route.entry(id))
            app.openEntryID = nil
        }
        .alert("Something went wrong", isPresented: Binding(get: { app.lastError != nil }, set: { if !$0 { app.lastError = nil } })) {
            Button("OK") { app.lastError = nil }
        } message: {
            Text(app.lastError ?? "")
        }
    }

    private var tabLayout: some View {
        TabView(selection: $tab) {
            ForEach(Tab.allCases) { t in
                stack(for: t)
                    .tabItem { Label(t.title, systemImage: t.symbol) }
                    .tag(t)
            }
        }
    }

    private var sidebarLayout: some View {
        NavigationSplitView {
            List(Tab.allCases, selection: Binding(get: { tab }, set: { if let t = $0 { tab = t; path = NavigationPath() } })) { t in
                Label(t.title, systemImage: t.symbol).tag(t)
            }
            .navigationTitle("Librelog")
            .navigationSplitViewColumnWidth(min: 180, ideal: 210)
        } detail: {
            stack(for: tab)
        }
    }

    private func stack(for t: Tab) -> some View {
        NavigationStack(path: $path) {
            screen(t)
                .navigationDestination(for: Route.self) { route in
                    switch route {
                    case .entry(let id): EntryEditorView(entryID: id)
                    }
                }
        }
    }

    @ViewBuilder
    private func screen(_ t: Tab) -> some View {
        switch t {
        case .today: TodayView()
        case .calendar: CalendarView()
        case .timeline: TimelineView()
        case .search: SearchView()
        case .settings: SyncSettingsView()
        }
    }
}
