import SwiftUI

/// On This Day (SPEC.md §6.8): today's date in every earlier year, newest
/// year first, each under a "N years ago" eyebrow.
struct OnThisDayView: View {
    @Environment(AppModel.self) private var app
    @State private var entries: [EntrySummary] = []
    @State private var loaded = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        List {
            ForEach(years, id: \.ago) { year in
                Section {
                    ForEach(year.entries, id: \.id) { e in
                        NavigationLink(value: Route.entry(e.id)) {
                            EntryRow(entry: e, showDate: false)
                        }
                        .buttonStyle(.pressable)
                        .listRowBackground(Color.paper)
                        .listRowSeparatorTint(Color.hairline)
                    }
                } header: {
                    Text(year.ago == 1 ? String(localized: "A year ago") : String(localized: "\(year.ago) years ago"))
                        .microLabel()
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(Color.paper)
        .overlay {
            if loaded && entries.isEmpty {
                ContentUnavailableView {
                    Label("Nothing on this day yet", systemImage: "clock.arrow.circlepath")
                } description: {
                    Text("Entries written on \(Date().formatted(.dateTime.day().month(.wide))) in earlier years will show up here.")
                }
                .foregroundStyle(Color.muted)
            }
        }
        .navigationTitle("On This Day")
        .task(id: app.revision) {
            let today = Day.today
            let list = (try? await app.run { try $0.onThisDay(date: today) }) ?? []
            withMotion(Motion.snappy, reduce: reduceMotion || !loaded) {
                entries = list
                loaded = true
            }
        }
    }

    private struct Year {
        let ago: Int
        let entries: [EntrySummary]
    }

    private var years: [Year] {
        var out: [Year] = []
        for e in entries {
            let ago = Day.yearsAgo(e.date)
            if out.last?.ago == ago {
                out[out.count - 1] = Year(ago: ago, entries: out[out.count - 1].entries + [e])
            } else {
                out.append(Year(ago: ago, entries: [e]))
            }
        }
        return out
    }
}
