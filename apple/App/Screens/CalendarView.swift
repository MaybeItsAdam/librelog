import SwiftUI

/// Month grid (SPEC.md §6.3). Days with entries are tinted by their rating;
/// tapping one shows that day's entries underneath.
struct CalendarView: View {
    @Environment(AppModel.self) private var app
    /// The month showing, "YYYY-MM"; the pager's scroll position.
    @State private var month: String? = Self.thisMonth
    @State private var selected: String?
    @State private var dayEntries: [EntrySummary] = []
    @State private var choosingMonth = false
    @Namespace private var selection
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private static var thisMonth: String { String(Day.today.prefix(7)) }

    /// Every month the pager can reach: a century back, ten years ahead.
    private static let months: [String] = {
        let cal = Calendar.current
        guard let now = Day.date(thisMonth + "-01") else { return [thisMonth] }
        return (-1200...120).compactMap { cal.date(byAdding: .month, value: $0, to: now).map { String(Day.string($0).prefix(7)) } }
    }()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header
                weekdayRow
                pager
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
                    DayPhotosSection(date: selected)
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
        .onChange(of: month) { _, _ in
            withMotion(Motion.snappy, reduce: reduceMotion) { selected = nil }
        }
        .task(id: "\(selected ?? "")|\(app.revision)") { await loadDay() }
    }

    private var shown: String { month ?? Self.thisMonth }

    private var header: some View {
        HStack {
            Button { choosingMonth = true } label: {
                HStack(spacing: 6) {
                    Text(Self.title(shown))
                        .font(Typeface.serif(19, bold: true, relativeTo: .headline))
                        .foregroundStyle(Color.ink)
                        .contentTransition(.numericText())
                        .animation(Motion.animation(Motion.snappy, reduce: reduceMotion), value: shown)
                    Image(systemName: "chevron.down")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(Color.muted)
                }
                .frame(minHeight: 44)
                .contentShape(Rectangle())
            }
            .accessibilityHint("Choose a month and year")
            .popover(isPresented: $choosingMonth) {
                MonthChooser(month: shown) { jump(to: $0) }
                    .presentationDetents([.height(380)])
            }
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
        return HStack(spacing: 0) {
            ForEach(Array(ordered.enumerated()), id: \.offset) { _, s in
                Text(s).microLabel().frame(maxWidth: .infinity).padding(.vertical, 8)
            }
        }
        .overlay(alignment: .bottom) { Rectangle().fill(Color.hairline).frame(height: 1) }
    }

    /// Months side by side in a paging scroller: the grid follows the
    /// finger, springs to the next or previous month, and can be caught
    /// mid-flight. Inside the grid this swipe wins over the app's page swipe.
    private var pager: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal) {
                LazyHStack(spacing: 0) {
                    ForEach(Self.months, id: \.self) { m in
                        MonthGrid(month: m, selected: $selected, selection: selection)
                            .containerRelativeFrame(.horizontal)
                    }
                }
                .scrollTargetLayout()
            }
            .scrollTargetBehavior(.paging)
            .scrollPosition(id: $month)
            .scrollIndicators(.hidden)
            .frame(height: MonthGrid.height)
            // A scroll position set before the first layout isn't honoured.
            .onAppear { proxy.scrollTo(shown) }
        }
    }

    static func title(_ month: String) -> String {
        guard let d = Day.date("\(month)-01") else { return month }
        return d.formatted(.dateTime.month(.wide).year())
    }

    private func shift(_ months: Int) {
        guard let d = Day.date("\(shown)-01"),
              let n = Calendar.current.date(byAdding: .month, value: months, to: d) else { return }
        jump(to: String(Day.string(n).prefix(7)))
    }

    private func jump(to target: String) {
        guard Self.months.contains(target), target != month else { return }
        withMotion(Motion.page, reduce: reduceMotion) { month = target }
    }

    private func loadDay() async {
        guard let day = selected else { dayEntries = []; return }
        let list = (try? await app.run { try $0.day(date: day, journalId: nil) }) ?? []
        withMotion(Motion.snappy, reduce: reduceMotion) { dayEntries = list }
    }
}

/// One month's days, always six rows tall so months of five weeks don't
/// make the page jump. Loads its own marks.
private struct MonthGrid: View {
    @Environment(AppModel.self) private var app
    let month: String
    @Binding var selected: String?
    let selection: Namespace.ID
    @State private var marks: [String: DayMark] = [:]
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    static let rowHeight: CGFloat = 48
    static let height = rowHeight * 6

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 0), count: 7)

    var body: some View {
        let cells = self.cells
        LazyVGrid(columns: columns, spacing: 0) {
            ForEach(Array(cells.enumerated()), id: \.offset) { i, day in
                if let day {
                    dayCell(day, column: i % 7, last: cells.compactMap { $0 }.last)
                } else {
                    Color.clear.frame(height: Self.rowHeight)
                }
            }
        }
        .frame(height: Self.height, alignment: .top)
        .task(id: "\(month)|\(app.revision)") { await load() }
    }

    private func dayCell(_ day: String, column: Int, last: String?) -> some View {
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
            .frame(maxWidth: .infinity, minHeight: Self.rowHeight)
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
            .background { band(mark, day: day, column: column, last: last) }
            .contentShape(Rectangle())
        }
        .buttonStyle(.pressable(scale: 0.92))
        .accessibilityLabel("\(Day.title(day)), \(mark?.entryCount ?? 0) entries")
    }

    /// Days covered by a multi-day entry share one band. Its ends are round,
    /// and it stays square where it carries on into the next or previous
    /// day, except at the edge of a week row, where it rounds off.
    @ViewBuilder
    private func band(_ mark: DayMark?, day: String, column: Int, last: String?) -> some View {
        if let mark, mark.spanBefore || mark.spanAfter {
            let lastOfMonth = day == last
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

    /// Leading blanks for the first weekday, each date in the month, then
    /// blanks to fill six weeks.
    private var cells: [String?] {
        guard let first = Day.date("\(month)-01"),
              let range = Calendar.current.range(of: .day, in: .month, for: first) else { return [] }
        let weekday = Calendar.current.component(.weekday, from: first)
        let blanks = (weekday - Calendar.current.firstWeekday + 7) % 7
        let days: [String?] = Array(repeating: nil, count: blanks) + range.map { String(format: "%@-%02d", month, $0) }
        return days + Array(repeating: nil, count: max(0, 42 - days.count))
    }

    private func load() async {
        let m = month
        if let list = try? await app.run({ try $0.month(yearMonth: m, journalId: nil) }) {
            // Dots fade in rather than blinking on.
            withAnimation(Motion.gentle) {
                marks = Dictionary(uniqueKeysWithValues: list.map { ($0.date, $0) })
            }
        }
    }
}

/// Jump straight to a month: a year stepper over a grid of the twelve months.
private struct MonthChooser: View {
    let month: String
    let choose: (String) -> Void
    @State private var year: Int
    @Environment(\.dismiss) private var dismiss

    init(month: String, choose: @escaping (String) -> Void) {
        self.month = month
        self.choose = choose
        _year = State(initialValue: Int(month.prefix(4)) ?? 2026)
    }

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 6), count: 3)
    private var thisYear: Int { Int(Day.today.prefix(4)) ?? 2026 }

    var body: some View {
        VStack(spacing: 12) {
            HStack {
                Button { year -= 1 } label: { Image(systemName: "chevron.left").frame(width: 44, height: 44) }
                    .disabled(year <= thisYear - 100)
                    .accessibilityLabel("Previous year")
                Spacer()
                Text(String(year))
                    .font(Typeface.mono(17, relativeTo: .headline))
                    .foregroundStyle(Color.ink)
                    .contentTransition(.numericText())
                    .animation(Motion.snappy, value: year)
                Spacer()
                Button { year += 1 } label: { Image(systemName: "chevron.right").frame(width: 44, height: 44) }
                    .disabled(year >= thisYear + 10)
                    .accessibilityLabel("Next year")
            }
            .buttonStyle(.pressable(scale: 0.9))
            .foregroundStyle(Color.ink)

            LazyVGrid(columns: columns, spacing: 6) {
                ForEach(1...12, id: \.self) { m in
                    let value = String(format: "%04d-%02d", year, m)
                    let current = value == month
                    Button {
                        choose(value)
                        dismiss()
                    } label: {
                        Text(Calendar.current.shortMonthSymbols[m - 1])
                            .font(.system(size: 14, weight: current ? .semibold : .regular))
                            .foregroundStyle(current ? Color.primary : Color.ink)
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .background(current ? Color.primary.opacity(0.1) : Color.raised, in: RoundedRectangle(cornerRadius: Radius.control))
                            .overlay(RoundedRectangle(cornerRadius: Radius.control).strokeBorder(current ? Color.primary : Color.hairline))
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.pressable)
                }
            }
            Button("This month") {
                choose(String(Day.today.prefix(7)))
                dismiss()
            }
            .font(.system(size: 14, weight: .medium))
            .foregroundStyle(Color.primary)
            .frame(minHeight: 44)
        }
        .padding(16)
        .frame(minWidth: 300)
        .background(Color.paper)
    }
}
