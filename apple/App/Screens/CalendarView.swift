import SwiftUI

/// Month grid (SPEC.md §6.3). Days with entries are tinted by their rating;
/// tapping one shows that day's entries underneath.
struct CalendarView: View {
    @Environment(AppModel.self) private var app
    @State private var month = Day.today.prefix(7).description // "YYYY-MM"
    @State private var marks: [String: DayMark] = [:]
    @State private var selected: String?
    @State private var dayEntries: [EntrySummary] = []
    /// +1 when the last move was forward a month, -1 when back: the side the
    /// new month slides in from.
    @State private var direction = 1
    @Namespace private var selection
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

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
                        Text("No entries")
                            .font(.system(size: 14))
                            .foregroundStyle(Color.muted)
                            .frame(minHeight: 44)
                    }
                    ForEach(dayEntries, id: \.id) { e in
                        VStack(spacing: 0) {
                            NavigationLink(value: Route.entry(e.id)) { EntryRow(entry: e, showDate: false, onDay: selected) }
                                .buttonStyle(.pressable)
                            Divider().overlay(Color.hairline)
                        }
                        .transition(.opacity.combined(with: .move(edge: .top)))
                    }
                    DayCalendarSection(date: selected)
                        .padding(.top, 8)
                }
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 100) // clear of the new-entry button
            .frame(maxWidth: 720)
            .frame(maxWidth: .infinity)
        }
        .background(Color.paper)
        .overlay(alignment: .bottomTrailing) { NewEntryButton(date: selected) }
        .navigationTitle("Calendar")
        .sensoryFeedback(.selection, trigger: selected)
        .sensoryFeedback(.selection, trigger: month)
        .task(id: "\(month)|\(app.revision)") { await loadMonth() }
        .task(id: "\(selected ?? "")|\(app.revision)") { await loadDay() }
    }

    private var header: some View {
        HStack {
            Text(monthTitle).font(Typeface.headline).foregroundStyle(Color.ink)
                .contentTransition(.numericText(countsDown: direction < 0))
            Spacer()
            Button { shift(-1) } label: { Image(systemName: "chevron.left").frame(width: 44, height: 44) }
                .accessibilityLabel("Previous month")
            Button { shift(1) } label: { Image(systemName: "chevron.right").frame(width: 44, height: 44) }
                .accessibilityLabel("Next month")
        }
        .buttonStyle(.pressable(scale: 0.9))
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

    /// The month's days. A new month slides in from the side it came from;
    /// swiping across the grid moves a month too.
    private var grid: some View {
        ZStack(alignment: .top) {
            LazyVGrid(columns: columns, spacing: 0) {
                ForEach(Array(cells.enumerated()), id: \.offset) { i, day in
                    if let day {
                        dayCell(day, column: i % 7)
                    } else {
                        Color.clear.frame(height: 48)
                    }
                }
            }
            .id(month)
            .transition(monthTransition)
        }
        .clipped()
        .contentShape(Rectangle())
        .simultaneousGesture(
            DragGesture(minimumDistance: 24)
                .onEnded { g in
                    let dx = g.translation.width
                    guard abs(dx) > 60, abs(dx) > abs(g.translation.height) * 1.5 else { return }
                    shift(dx < 0 ? 1 : -1)
                }
        )
    }

    private var monthTransition: AnyTransition {
        guard !reduceMotion else { return .opacity }
        let incoming: Edge = direction > 0 ? .trailing : .leading
        let outgoing: Edge = direction > 0 ? .leading : .trailing
        return .asymmetric(insertion: .move(edge: incoming).combined(with: .opacity),
                           removal: .move(edge: outgoing).combined(with: .opacity))
    }

    private func dayCell(_ day: String, column: Int) -> some View {
        let mark = marks[day]
        let isToday = day == Day.today
        let isSelected = day == selected
        return Button {
            withMotion(Motion.snappy, reduce: reduceMotion) { selected = (selected == day) ? nil : day }
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
            .background {
                // One selection ring, which travels between days.
                if isSelected {
                    RoundedRectangle(cornerRadius: Radius.control)
                        .fill(Color.primary.opacity(0.1))
                        .overlay(RoundedRectangle(cornerRadius: Radius.control).strokeBorder(Color.primary))
                        .matchedGeometryEffect(id: "selection", in: selection)
                } else if isToday {
                    RoundedRectangle(cornerRadius: Radius.control).strokeBorder(Color.hairline)
                }
            }
            .background { band(mark, day: day, column: column) }
            .contentShape(Rectangle())
        }
        .buttonStyle(.pressable(scale: 0.92))
        .accessibilityLabel("\(Day.title(day)), \(mark?.entryCount ?? 0) entries")
    }

    /// Days covered by a multi-day entry share one band. Its ends are round,
    /// and it stays square where it carries on into the next or previous
    /// day, except at the edge of a week row, where it rounds off.
    @ViewBuilder
    private func band(_ mark: DayMark?, day: String, column: Int) -> some View {
        if let mark, mark.spanBefore || mark.spanAfter {
            let lastOfMonth = day == cells.last.flatMap { $0 }
            let joinsLeft = mark.spanBefore && column > 0 && !day.hasSuffix("-01")
            let joinsRight = mark.spanAfter && column < 6 && !lastOfMonth
            let r = Radius.control
            UnevenRoundedRectangle(topLeadingRadius: joinsLeft ? 0 : r, bottomLeadingRadius: joinsLeft ? 0 : r,
                                   bottomTrailingRadius: joinsRight ? 0 : r, topTrailingRadius: joinsRight ? 0 : r)
                .fill(Color.primary.opacity(0.12))
                .padding(.leading, joinsLeft ? 0 : 3)
                .padding(.trailing, joinsRight ? 0 : 3)
                .padding(.vertical, 6)
                .transition(.opacity)
        }
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
        direction = months > 0 ? 1 : -1
        withMotion(Motion.page, reduce: reduceMotion) {
            month = String(Day.string(n).prefix(7))
            selected = nil
        }
    }

    private func loadMonth() async {
        let m = month
        if let list = try? await app.run({ try $0.month(yearMonth: m, journalId: nil) }) {
            // Dots fade in rather than blinking on.
            withAnimation(Motion.gentle) {
                marks = Dictionary(uniqueKeysWithValues: list.map { ($0.date, $0) })
            }
        }
    }

    private func loadDay() async {
        guard let day = selected else { dayEntries = []; return }
        let list = (try? await app.run { try $0.day(date: day, journalId: nil) }) ?? []
        withMotion(Motion.snappy, reduce: reduceMotion) { dayEntries = list }
    }
}
