import SwiftUI

/// Full-text search with operators (SPEC.md §6.11). Results update as you type.
struct SearchView: View {
    @Environment(AppModel.self) private var app
    @State private var query = ""
    @State private var hits: [SearchHit] = []

    var body: some View {
        List {
            if query.isEmpty {
                Section {
                    ForEach(Self.examples, id: \.0) { example, meaning in
                        Button { query = example } label: {
                            HStack {
                                Text(example).font(Typeface.mono(13)).foregroundStyle(Color.ink)
                                Spacer()
                                Text(meaning).font(.system(size: 12)).foregroundStyle(Color.muted)
                            }
                            .frame(minHeight: 36)
                        }
                        .buttonStyle(.plain)
                        .listRowBackground(Color.paper)
                    }
                } header: {
                    Text("Try").microLabel()
                }
            } else if hits.isEmpty {
                ContentUnavailableView.search(text: query).listRowBackground(Color.paper)
            } else {
                ForEach(hits, id: \.entry.id) { hit in
                    NavigationLink(value: Route.entry(hit.entry.id)) {
                        EntryRow(entry: hit.entry, snippet: hit.snippet)
                    }
                    .listRowBackground(Color.paper)
                    .listRowSeparatorTint(Color.hairline)
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(Color.paper)
        .navigationTitle("Search")
        .searchable(text: $query, prompt: "Search your journal")
        .task(id: "\(query)|\(app.revision)") {
            // Debounce typing; the query itself is ~1 ms.
            try? await Task.sleep(for: .milliseconds(120))
            guard !Task.isCancelled else { return }
            let q = query
            hits = (try? await app.run { try $0.search(query: q, limit: 100) }) ?? []
        }
    }

    static let examples: [(String, String)] = [
        ("tag:run", String(localized: "tagged run")),
        ("rating:>=4", String(localized: "good days")),
        ("\"long walk\"", String(localized: "exact phrase")),
        ("coffee -work", String(localized: "without a word")),
        ("after:2026-01-01", String(localized: "this year")),
        ("is:favorite", String(localized: "starred")),
    ]
}
