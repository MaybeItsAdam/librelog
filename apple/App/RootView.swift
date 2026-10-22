import SwiftUI

enum Route: Hashable {
    case entry(String)
}

/// The places in the app. The first four are the pages of the bottom bar on
/// phones; On This Day opens from the bar's left end. On iPad and Mac all
/// five are in the sidebar.
enum Tab: String, CaseIterable, Identifiable {
    case journal, calendar, search, settings, onThisDay
    var id: String { rawValue }

    /// The bottom bar's pages, in swipe order.
    static let pages: [Tab] = [.journal, .calendar, .search, .settings]
    /// The sidebar's places, settings last.
    static let sidebar: [Tab] = [.journal, .calendar, .search, .onThisDay, .settings]

    var title: LocalizedStringKey {
        switch self {
        case .journal: "Journal"
        case .calendar: "Calendar"
        case .search: "Search"
        case .settings: "Settings"
        case .onThisDay: "On This Day"
        }
    }

    var symbol: String {
        switch self {
        case .journal: "book"
        case .calendar: "calendar"
        case .search: "magnifyingglass"
        case .settings: "gearshape"
        case .onThisDay: "clock.arrow.circlepath"
        }
    }
}

/// A swipeable bottom bar on iPhone, a sidebar on iPad and Mac (SPEC.md §5).
struct RootView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var tab: Tab = .journal
    @State private var paths = TabPaths()

    var body: some View {
        Group {
            #if os(macOS)
            sidebarLayout
            #else
            if sizeClass == .regular { sidebarLayout } else { PagedTabs(tab: $tab, paths: $paths) }
            #endif
        }
        .tint(Color.primary)
        .preferredColorScheme(AppearanceModel.shared.value.colorScheme)
        .sensoryFeedback(.selection, trigger: tab)
        .sensoryFeedback(.success, trigger: app.created)
        .onChange(of: app.openEntryID) { _, id in
            guard let id else { return }
            paths[tab].append(Route.entry(id))
            app.openEntryID = nil
        }
        .alert("Something went wrong", isPresented: Binding(get: { app.lastError != nil }, set: { if !$0 { app.lastError = nil } })) {
            Button("OK") { app.lastError = nil }
        } message: {
            Text(app.lastError ?? "")
        }
    }

    private var sidebarLayout: some View {
        NavigationSplitView {
            List(selection: Binding(get: { tab }, set: { if let t = $0 { tab = t; paths[t] = NavigationPath() } })) {
                ForEach(Tab.sidebar) { t in
                    Label(t.title, systemImage: t.symbol).tag(t)
                }
            }
            .navigationTitle("LibreLog")
            .navigationSplitViewColumnWidth(min: 180, ideal: 210)
        } detail: {
            TabStack(tab: tab, path: $paths[tab])
        }
    }
}

/// Each place's own navigation path, so swiping between places keeps
/// whatever each one had open.
struct TabPaths {
    private var paths: [Tab: NavigationPath] = [:]

    subscript(t: Tab) -> NavigationPath {
        get { paths[t] ?? NavigationPath() }
        set { paths[t] = newValue }
    }
}

/// One place's navigation stack: its screen, and entries pushed on top.
struct TabStack: View {
    let tab: Tab
    @Binding var path: NavigationPath

    var body: some View {
        NavigationStack(path: $path) {
            screen
                #if os(iOS)
                // Large titles lay out off their inset inside the pager, and
                // each screen's own heading already names it.
                .navigationBarTitleDisplayMode(.inline)
                #endif
                .navigationDestination(for: Route.self) { route in
                    switch route {
                    case .entry(let id): EntryEditorView(entryID: id)
                    }
                }
        }
    }

    @ViewBuilder
    private var screen: some View {
        switch tab {
        case .journal: JournalView()
        case .calendar: CalendarView()
        case .search: SearchView()
        case .settings: SyncSettingsView()
        case .onThisDay: OnThisDayView()
        }
    }
}

#if os(iOS)
/// The phone layout: the bar's pages side by side, swiped between like a
/// book, with the bar's marker following the finger. Horizontal scrollers
/// inside a page (the calendar's months) win over the page swipe, as nested
/// scroll views do. While an entry is open the swipe and the bar step aside.
private struct PagedTabs: View {
    @Environment(AppModel.self) private var app
    @Binding var tab: Tab
    @Binding var paths: TabPaths
    @State private var page: Tab? = .journal
    /// Where the pages are, in pages: 1.5 is halfway from Calendar to Search.
    @State private var progress: CGFloat = 0
    @State private var keyboard = false
    @State private var showOnThisDay = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var pushed: Bool { !paths[tab].isEmpty }

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView(.horizontal) {
                    HStack(spacing: 0) {
                        ForEach(Tab.pages) { t in
                            TabStack(tab: t, path: $paths[t])
                                .containerRelativeFrame(.horizontal)
                                .id(t)
                        }
                    }
                    .scrollTargetLayout()
                    .onGeometryChange(for: CGRect.self) { $0.frame(in: .scrollView) } action: { frame in
                        let width = frame.width / CGFloat(Tab.pages.count)
                        if width > 0 { progress = -frame.minX / width }
                    }
                }
                .scrollTargetBehavior(.paging)
                .scrollPosition(id: $page)
                .scrollIndicators(.hidden)
                .scrollDisabled(pushed)
                .scrollBounceBehavior(.basedOnSize)
                // A scroll position set before the first layout isn't honoured.
                .onAppear { proxy.scrollTo(tab) }
            }

            if !pushed && !keyboard {
                BottomBar(progress: progress, select: select, openOnThisDay: { showOnThisDay = true })
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .background(Color.paper)
        .animation(Motion.animation(Motion.snappy, reduce: reduceMotion), value: pushed)
        .onChange(of: page) { _, now in if let now { tab = now } }
        .onChange(of: tab) { _, now in if page != now { page = now } }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)) { _ in keyboard = true }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in keyboard = false }
        .sheet(isPresented: $showOnThisDay) {
            NavigationStack {
                OnThisDayView()
                    .navigationDestination(for: Route.self) { route in
                        switch route {
                        case .entry(let id): EntryEditorView(entryID: id)
                        }
                    }
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button("Done") { showOnThisDay = false }
                        }
                    }
            }
            .tint(Color.primary)
        }
    }

    /// Tapping the current place again goes back to its top.
    private func select(_ t: Tab) {
        if t == tab {
            paths[t] = NavigationPath()
            return
        }
        withMotion(Motion.page, reduce: reduceMotion) { page = t }
    }
}

/// The phone's bottom bar: On This Day at the left end, then the four
/// pages, with an accent marker that slides with the swipe.
private struct BottomBar: View {
    @Environment(AppModel.self) private var app
    let progress: CGFloat
    let select: (Tab) -> Void
    let openOnThisDay: () -> Void
    @State private var memories = 0

    private var current: Int { Int(progress.rounded()) }

    var body: some View {
        HStack(spacing: 0) {
            Button(action: openOnThisDay) {
                Image(systemName: Tab.onThisDay.symbol)
                    .font(.system(size: 18))
                    .foregroundStyle(Color.ink)
                    .frame(width: 52, height: 52)
                    .overlay(alignment: .topTrailing) {
                        if memories > 0 {
                            RoundedRectangle(cornerRadius: 2)
                                .fill(Color.primary)
                                .frame(width: 6, height: 6)
                                .offset(x: -12, y: 12)
                        }
                    }
                    .contentShape(Rectangle())
            }
            .buttonStyle(.pressable(scale: 0.9))
            .accessibilityLabel(memories > 0 ? "On This Day, \(memories) entries" : "On This Day")

            Rectangle().fill(Color.hairline).frame(width: 1, height: 28)

            HStack(spacing: 0) {
                ForEach(Array(Tab.pages.enumerated()), id: \.element) { i, t in
                    Button { select(t) } label: {
                        VStack(spacing: 4) {
                            Image(systemName: t.symbol).font(.system(size: 17)).frame(height: 22)
                            Text(t.title)
                                .font(.system(size: 9, weight: .bold))
                                .textCase(.uppercase)
                                .kerning(1.2)
                                .lineLimit(1)
                        }
                        .foregroundStyle(i == current ? Color.primary : Color.muted)
                        .frame(maxWidth: .infinity, minHeight: 52)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.pressable(scale: 0.92))
                    .accessibilityAddTraits(i == current ? .isSelected : [])
                }
            }
            .overlay(alignment: .topLeading) {
                GeometryReader { g in
                    let w = g.size.width / CGFloat(Tab.pages.count)
                    Rectangle()
                        .fill(Color.primary)
                        .frame(width: w - 28, height: 2)
                        .offset(x: min(max(progress, 0), CGFloat(Tab.pages.count - 1)) * w + 14)
                }
                .allowsHitTesting(false)
            }
        }
        .padding(.horizontal, 4)
        .background(Color.raised.ignoresSafeArea(edges: .bottom))
        .overlay(alignment: .top) { Rectangle().fill(Color.hairline).frame(height: 1) }
        .task(id: app.revision) {
            let today = Day.today
            memories = (try? await app.run { try $0.onThisDay(date: today) })?.count ?? 0
        }
    }
}
#endif
