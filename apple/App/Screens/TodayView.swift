import SwiftUI

/// The start screen (SPEC.md §6.1): the selected day's entries, a writing
/// prompt when it's empty, On This Day, and the streak.
struct TodayView: View {
    @Environment(AppModel.self) private var app
    @State private var entries: [EntrySummary] = []
    @State private var memories: [EntrySummary] = []
    @State private var stats: Stats?
    @State private var loaded = false

    var body: some View {
        @Bindable var app = app
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                dayHeader
                writeCard
                if !memories.isEmpty { onThisDay }
                if let stats { streakCard(stats) }
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 100)
            .frame(maxWidth: 720)
            .frame(maxWidth: .infinity)
        }
        .background(Color.paper)
        .overlay(alignment: .bottomTrailing) { newEntryButton }
        .gesture(daySwipe)
        .task(id: "\(app.selectedDate)|\(app.revision)") { await load() }
        #if os(iOS)
        .toolbar(.hidden, for: .navigationBar)
        #endif
    }

    private var dayHeader: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 4) {
                Text(app.selectedDate == Day.today ? Date().formatted(.dateTime.weekday(.wide)) : String(localized: "Journal"))
                    .microLabel()
                Text(Day.title(app.selectedDate))
                    .font(Typeface.title)
                    .foregroundStyle(Color.ink)
            }
            Spacer()
            HStack(spacing: 0) {
                dayButton("chevron.left", label: "Previous day") { app.selectedDate = Day.adding(-1, to: app.selectedDate) }
                if app.selectedDate != Day.today {
                    Button("Today") { app.selectedDate = Day.today }
                        .font(.system(size: 13, weight: .medium))
                        .buttonStyle(.plain)
                        .foregroundStyle(Color.primary)
                        .frame(minHeight: 44)
                        .padding(.horizontal, 6)
                }
                dayButton("chevron.right", label: "Next day") { app.selectedDate = Day.adding(1, to: app.selectedDate) }
                    .disabled(app.selectedDate >= Day.today)
            }
        }
        .padding(.top, 24)
        .padding(.bottom, 12)
    }

    private func dayButton(_ symbol: String, label: LocalizedStringKey, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(Color.ink)
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }

    @ViewBuilder
    private var writeCard: some View {
        if entries.isEmpty {
            if loaded {
                Button { Task { await app.newEntry() } } label: {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Write").microLabel()
                        Text(Prompts.today(app.selectedDate))
                            .font(Typeface.serif(19))
                            .foregroundStyle(Color.ink)
                            .multilineTextAlignment(.leading)
                        Text("Start writing")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(Color.primary)
                            .padding(.top, 4)
                    }
                    .card()
                }
                .buttonStyle(.plain)
            }
        } else {
            VStack(alignment: .leading, spacing: 0) {
                ForEach(entries, id: \.id) { e in
                    NavigationLink(value: Route.entry(e.id)) {
                        EntryRow(entry: e, showDate: false)
                    }
                    .buttonStyle(.plain)
                    if e.id != entries.last?.id { Divider().overlay(Color.hairline) }
                }
            }
            .padding(.horizontal, 16)
            .card(padding: 0)
        }
    }

    private var onThisDay: some View {
        VStack(alignment: .leading, spacing: 0) {
            SectionHeader(title: String(localized: "On this day"), trailing: "\(memories.count)")
            ForEach(memories.prefix(5), id: \.id) { e in
                NavigationLink(value: Route.entry(e.id)) {
                    VStack(alignment: .leading, spacing: 2) {
                        let years = Day.yearsAgo(e.date, from: app.selectedDate)
                        Text(years == 1 ? String(localized: "1 year ago") : String(localized: "\(years) years ago"))
                            .font(Typeface.mono(10))
                            .foregroundStyle(Color.primary)
                            .padding(.top, 10)
                        EntryRow(entry: e)
                    }
                }
                .buttonStyle(.plain)
                Divider().overlay(Color.hairline)
            }
        }
    }

    private func streakCard(_ s: Stats) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            SectionHeader(title: String(localized: "Streak"))
            HStack(spacing: 0) {
                metric("\(s.currentStreak)", label: String(localized: "days running"))
                Divider().overlay(Color.hairline)
                metric("\(s.longestStreak)", label: String(localized: "longest"))
                Divider().overlay(Color.hairline)
                metric(s.words.formatted(.number.notation(.compactName)), label: String(localized: "words"))
            }
            .frame(height: 64)
        }
    }

    private func metric(_ value: String, label: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value).font(Typeface.mono(20, relativeTo: .title3)).foregroundStyle(Color.ink)
            Text(label).font(.system(size: 11)).foregroundStyle(Color.muted)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.leading, 12)
    }

    private var newEntryButton: some View {
        Button { Task { await app.newEntry() } } label: {
            Image(systemName: "plus")
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 56, height: 56)
                .background(Color.primary, in: RoundedRectangle(cornerRadius: Radius.card))
        }
        .buttonStyle(.plain)
        .padding(20)
        .accessibilityLabel("New entry")
        .keyboardShortcut("n", modifiers: .command)
    }

    private var daySwipe: some Gesture {
        DragGesture(minimumDistance: 40).onEnded { g in
            guard abs(g.translation.width) > abs(g.translation.height) * 2 else { return }
            if g.translation.width < 0, app.selectedDate < Day.today {
                app.selectedDate = Day.adding(1, to: app.selectedDate)
            } else if g.translation.width > 0 {
                app.selectedDate = Day.adding(-1, to: app.selectedDate)
            }
        }
    }

    private func load() async {
        let date = app.selectedDate
        let today = Day.today
        let result = try? await app.run { store in
            (try store.day(date: date, journalId: nil),
             try store.onThisDay(date: date),
             try store.stats(today: today))
        }
        if let (e, m, s) = result {
            entries = e
            memories = m
            stats = s
        }
        loaded = true
    }
}

/// Rotating reflection prompts (SPEC.md §6.1). Picked by date, so a day
/// always shows the same one.
enum Prompts {
    static let all: [String] = [
        String(localized: "What's on your mind today?"),
        String(localized: "What made today different from yesterday?"),
        String(localized: "What are you looking forward to?"),
        String(localized: "Three things you're grateful for."),
        String(localized: "What took most of your energy today?"),
        String(localized: "Who did you spend time with?"),
        String(localized: "What would you tell yourself a year ago?"),
        String(localized: "What did you learn today?"),
        String(localized: "What's one small win from today?"),
        String(localized: "Where did the day go?"),
    ]

    static func today(_ date: String) -> String {
        let n = date.unicodeScalars.reduce(0) { ($0 &* 31) &+ Int($1.value) }
        return all[abs(n) % all.count]
    }
}
