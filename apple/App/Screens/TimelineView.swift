import SwiftUI

/// Every entry, newest first, loaded a page at a time (SPEC.md §6.4).
struct TimelineView: View {
    @Environment(AppModel.self) private var app
    @State private var entries: [EntrySummary] = []
    @State private var filter = TimelineFilter(journalId: nil, tagIds: [], minRating: nil, favoritesOnly: false)
    @State private var done = false
    @State private var loading = false

    private let pageSize: UInt32 = 50

    var body: some View {
        List {
            ForEach(sections, id: \.month) { section in
                Section {
                    ForEach(section.entries, id: \.id) { e in
                        NavigationLink(value: Route.entry(e.id)) { EntryRow(entry: e) }
                            .listRowBackground(Color.paper)
                            .listRowSeparatorTint(Color.hairline)
                            .onAppear { if e.id == entries.last?.id { Task { await loadMore() } } }
                    }
                } header: {
                    Text(section.title).microLabel()
                }
            }
            if entries.isEmpty, done {
                ContentUnavailableView("No entries yet", systemImage: "book.closed",
                                       description: Text("Entries you write appear here, newest first."))
                    .listRowBackground(Color.paper)
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(Color.paper)
        .navigationTitle("Timeline")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Toggle(isOn: $filter.favoritesOnly) {
                    Label("Favourites", systemImage: filter.favoritesOnly ? "star.fill" : "star")
                }
                .toggleStyle(.button)
            }
        }
        .task(id: "\(filter.favoritesOnly)|\(app.revision)") { await reload() }
    }

    private struct MonthSection {
        let month: String
        let title: String
        let entries: [EntrySummary]
    }

    private var sections: [MonthSection] {
        var out: [MonthSection] = []
        for e in entries {
            let m = String(e.date.prefix(7))
            if out.last?.month == m {
                out[out.count - 1] = MonthSection(month: m, title: out[out.count - 1].title, entries: out[out.count - 1].entries + [e])
            } else {
                let title = Day.date("\(m)-01")?.formatted(.dateTime.month(.wide).year()) ?? m
                out.append(MonthSection(month: m, title: title, entries: [e]))
            }
        }
        return out
    }

    private func reload() async {
        let f = filter
        let size = pageSize
        let page = (try? await app.run { try $0.timeline(filter: f, after: nil, limit: size) }) ?? []
        entries = page
        done = page.count < Int(pageSize)
    }

    private func loadMore() async {
        guard !done, !loading, let last = entries.last else { return }
        loading = true
        defer { loading = false }
        let f = filter
        let size = pageSize
        let cursor = TimelineCursor(date: last.date, time: last.time, id: last.id)
        let page = (try? await app.run { try $0.timeline(filter: f, after: cursor, limit: size) }) ?? []
        entries += page
        done = page.count < Int(pageSize)
    }
}
