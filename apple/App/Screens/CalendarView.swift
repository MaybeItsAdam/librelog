import SwiftUI

/// Month grid (SPEC.md §6.3). Days with entries are tinted by their rating;
/// tapping one shows that day's entries underneath.
struct CalendarView: View {
    @Environment(AppModel.self) private var app
    @State private var month = Day.today.prefix(7).description // "YYYY-MM"
    @State private var marks: [String: DayMark] = [:]
    @State private var selected: String?
    @State private var dayEntries: [EntrySummary] = []

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 0), count: 7)

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header
                weekdayRow
                grid
                if let selected {
                    SectionHeader(title: Day.title(selected), trailing: dayEntries.isEmpty ? nil : "\(dayEntries.count)")
                    if dayEntries.isEmpty {
                        Button("Write on this day") {
                            Task { await app.newEntry(on: selected) }
                        }
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(Color.primary)
                        .buttonStyle(.plain)
                        .frame(minHeight: 44)
                    }
                    ForEach(dayEntries, id: \.id) { e in
                        NavigationLink(value: Route.entry(e.id)) { EntryRow(entry: e, showDate: false) }
                            .buttonStyle(.plain)
                        Divider().overlay(Color.hairline)
                    }
                }
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 40)
            .frame(maxWidth: 720)
            .frame(maxWidth: .infinity)
        }
        .background(Color.paper)
        .navigationTitle("Calendar")
        .task(id: "\(month)|\(app.revision)") { await loadMonth() }
        .task(id: "\(selected ?? "")|\(app.revision)") { await loadDay() }
    }

    private var header: some View {
        HStack {
            Text(monthTitle).font(Typeface.headline).foregroundStyle(Color.ink)
            Spacer()
            Button { shift(-1) } label: { Image(systemName: "chevron.left").frame(width: 44, height: 44) }
                .accessibilityLabel("Previous month")
            Button { shift(1) } label: { Image(systemName: "chevron.right").frame(width: 44, height: 44) }
                .accessibilityLabel("Next month")
        }
        .buttonStyle(.plain)
        .foregroundStyle(Color.ink)
        .padding(.top, 16)
    }

    private var weekdayRow: some View {
        let symbols = Calendar.current.veryShortStandaloneWeekdaySymbols
        let first = Calendar.current.firstWeekday - 1
        let ordered = Array(symbols[first...] + symbols[..<first])
        return LazyVGrid(columns: columns, spacing: 0) {
            ForEach(Array(ordered.enumerated()), id: \.offset) { _, s in
                Text(s).microLabel().frame(maxWidth: .infinity).padding(.vertical, 8)
            }
        }
        .overlay(alignment: .bottom) { Rectangle().fill(Color.hairline).frame(height: 1) }
    }

    private var grid: some View {
        LazyVGrid(columns: columns, spacing: 0) {
            ForEach(Array(cells.enumerated()), id: \.offset) { _, day in
                if let day {
                    dayCell(day)
                } else {
                    Color.clear.frame(height: 48)
                }
            }
        }
    }

    private func dayCell(_ day: String) -> some View {
        let mark = marks[day]
        let isToday = day == Day.today
        let isSelected = day == selected
        return Button {
            selected = (selected == day) ? nil : day
        } label: {
            VStack(spacing: 3) {
                Text(String(Int(day.suffix(2)) ?? 0))
                    .font(Typeface.mono(14, relativeTo: .body))
                    .foregroundStyle(day > Day.today ? Color.dim : Color.ink)
                Circle()
                    .fill(mark.map { ratingColor($0.rating) } ?? .clear)
                    .frame(width: 6, height: 6)
            }
            .frame(maxWidth: .infinity, minHeight: 48)
            .background(isSelected ? Color.primary.opacity(0.1) : .clear, in: RoundedRectangle(cornerRadius: Radius.control))
            .overlay(RoundedRectangle(cornerRadius: Radius.control)
                .strokeBorder(isSelected ? Color.primary : isToday ? Color.hairline : .clear))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(Day.title(day)), \(mark?.entryCount ?? 0) entries")
    }

    /// No rating: ink dot. Rated: raspberry → amber → emerald.
    private func ratingColor(_ r: Double?) -> Color {
        guard let r else { return Color.muted }
        switch r {
        case ..<2: return .danger
        case ..<3.5: return .warning
        default: return .success
        }
    }

    private var monthTitle: String {
        guard let d = Day.date("\(month)-01") else { return month }
        return d.formatted(.dateTime.month(.wide).year())
    }

    /// Leading blanks for the first weekday, then each date in the month.
    private var cells: [String?] {
        guard let first = Day.date("\(month)-01"),
              let range = Calendar.current.range(of: .day, in: .month, for: first) else { return [] }
        let weekday = Calendar.current.component(.weekday, from: first)
        let blanks = (weekday - Calendar.current.firstWeekday + 7) % 7
        return Array(repeating: nil, count: blanks) + range.map { String(format: "%@-%02d", month, $0) }
    }

    private func shift(_ months: Int) {
        guard let d = Day.date("\(month)-01"),
              let n = Calendar.current.date(byAdding: .month, value: months, to: d) else { return }
        month = String(Day.string(n).prefix(7))
        selected = nil
    }

    private func loadMonth() async {
        let m = month
        if let list = try? await app.run({ try $0.month(yearMonth: m, journalId: nil) }) {
            marks = Dictionary(uniqueKeysWithValues: list.map { ($0.date, $0) })
        }
    }

    private func loadDay() async {
        guard let day = selected else { dayEntries = []; return }
        dayEntries = (try? await app.run { try $0.day(date: day, journalId: nil) }) ?? []
    }
}
