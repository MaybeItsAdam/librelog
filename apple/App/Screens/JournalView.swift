import SwiftUI

/// The home screen: every entry, newest first, grouped by day. Today is
/// always the first group, so there's one place to look and one place to
/// write. On This Day sits under today as a single row that opens in place.
struct JournalView: View {
    @Environment(AppModel.self) private var app
    @State private var entries: [EntrySummary] = []
    @State private var memories: [EntrySummary] = []
    @State private var showMemories = false
    @State private var showSettings = false
    @State private var done = false
    @State private var loading = false
    /// True until shortly after the first page is in, while rows fade in.
    @State private var firstLoad = true
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private let pageSize: UInt32 = 50
    private nonisolated static let noFilter = TimelineFilter(journalId: nil, tagIds: [], minRating: nil, favoritesOnly: false)

    var body: some View {
        List {
            todaySection
            ForEach(earlierDays, id: \.date) { day in
                Section {
                    ForEach(day.entries, id: \.id) { e in
                        row(e)
                            .transition(.opacity.combined(with: .move(edge: .top)))
                            .onAppear { if e.id == entries.last?.id { Task { await loadMore() } } }
                    }
                } header: {
                    Text(Day.title(day.date)).microLabel()
                        .reveal(firstLoad, index: order[day.entries[0].id] ?? 0)
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(Color.paper)
        .navigationTitle("Journal")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button { showSettings = true } label: {
                    Label("Settings", systemImage: "gearshape")
                }
            }
        }
        .overlay(alignment: .bottomTrailing) { NewEntryButton(date: nil) }
        .sheet(isPresented: $showSettings) {
            NavigationStack {
                SyncSettingsView()
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button("Done") { showSettings = false }
                        }
                    }
            }
            #if os(macOS)
            .frame(minWidth: 460, minHeight: 520)
            #endif
        }
        .task(id: app.revision) { await reload() }
    }

    // MARK: Today

    private var todayEntries: [EntrySummary] {
        entries.filter { $0.date == Day.today }
    }

    @ViewBuilder
    private var todaySection: some View {
        Section {
            if todayEntries.isEmpty {
                Button { Task { await app.newEntry(on: Day.today) } } label: {
                    Label("New entry", systemImage: "square.and.pencil")
                        .font(.system(size: 15))
                        .foregroundStyle(Color.muted)
                        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.pressable)
                .listRowBackground(Color.paper)
                .listRowSeparatorTint(Color.hairline)
                .transition(.opacity)
            } else {
                ForEach(todayEntries, id: \.id) { e in
                    row(e).transition(.opacity.combined(with: .move(edge: .top)))
                }
            }
            if !memories.isEmpty { onThisDay }
        } header: {
            Text(Day.title(Day.today)).microLabel()
        }
    }

    @ViewBuilder
    private var onThisDay: some View {
        Button { withMotion(Motion.snappy, reduce: reduceMotion) { showMemories.toggle() } } label: {
            HStack {
                Text("On this day · \(memories.count)")
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(Color.ink)
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(Color.muted)
                    .rotationEffect(.degrees(showMemories ? 90 : 0))
            }
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.pressable)
        .listRowBackground(Color.paper)
        .listRowSeparatorTint(Color.hairline)
        .accessibilityHint(showMemories ? "Hides earlier years" : "Shows entries from this date in earlier years")

        if showMemories {
            ForEach(memories, id: \.id) { e in
                row(e).transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
    }

    // MARK: Earlier days

    private struct DayGroup {
        let date: String
        let entries: [EntrySummary]
    }

    /// Everything before today, grouped by day; entries arrive sorted, so
    /// consecutive runs of one date are one group.
    private var earlierDays: [DayGroup] {
        var out: [DayGroup] = []
        for e in entries where e.date != Day.today {
            if out.last?.date == e.date {
                out[out.count - 1] = DayGroup(date: e.date, entries: out[out.count - 1].entries + [e])
            } else {
                out.append(DayGroup(date: e.date, entries: [e]))
            }
        }
        return out
    }

    /// Each loaded entry's position, for staggering the first fade-in.
    private var order: [String: Int] {
        Dictionary(entries.enumerated().map { ($1.id, $0) }, uniquingKeysWith: { a, _ in a })
    }

    private func row(_ e: EntrySummary) -> some View {
        NavigationLink(value: Route.entry(e.id)) {
            EntryRow(entry: e, showDate: false)
        }
        .buttonStyle(.pressable)
        .reveal(firstLoad, index: order[e.id] ?? 0)
        .listRowBackground(Color.paper)
        .listRowSeparatorTint(Color.hairline)
    }

    // MARK: Loading

    private func reload() async {
        let size = pageSize
        let today = Day.today
        let result = try? await app.run { store in
            (try store.timeline(filter: Self.noFilter, after: nil, limit: size),
             try store.onThisDay(date: today))
        }
        guard let (page, m) = result else { return }
        // The first load fades in; later ones animate what changed.
        withMotion(Motion.snappy, reduce: reduceMotion || firstLoad) {
            entries = page
            memories = m
        }
        done = page.count < Int(pageSize)
        if firstLoad {
            try? await Task.sleep(for: .milliseconds(600))
            firstLoad = false
        }
    }

    private func loadMore() async {
        guard !done, !loading, let last = entries.last else { return }
        loading = true
        defer { loading = false }
        let size = pageSize
        let cursor = TimelineCursor(date: last.date, time: last.time, id: last.id)
        let page = (try? await app.run { try $0.timeline(filter: Self.noFilter, after: cursor, limit: size) }) ?? []
        entries += page
        done = page.count < Int(pageSize)
    }
}

/// The one primary action, shared by Journal and Calendar: a new entry on
/// `date`, or today when nil. ⌘N on keyboards.
struct NewEntryButton: View {
    @Environment(AppModel.self) private var app
    let date: String?

    var body: some View {
        Button { Task { await app.newEntry(on: date ?? Day.today) } } label: {
            Image(systemName: "plus")
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 56, height: 56)
                .background(Color.primary, in: RoundedRectangle(cornerRadius: Radius.card))
                .contentShape(RoundedRectangle(cornerRadius: Radius.card))
        }
        .buttonStyle(.pressable(scale: 0.92))
        .padding(20)
        .accessibilityLabel("New entry")
        .keyboardShortcut("n", modifiers: .command)
    }
}
