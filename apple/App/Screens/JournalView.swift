import SwiftUI

/// The home screen: every entry, newest first, grouped by day. Today is
/// always the first group, so there's one place to look and one place to
/// write. A day with photos shows its preview down the left (DayPreview).
/// On This Day has its own button in the bottom bar.
struct JournalView: View {
    @Environment(AppModel.self) private var app
    @State private var entries: [EntrySummary] = []
    @State private var done = false
    @State private var loading = false
    /// Each loaded day's preview photo, by date.
    @State private var previews: [String: DayPhoto] = [:]
    @State private var addingPhoto = false
    /// True until shortly after the first page is in, while rows fade in.
    @State private var firstLoad = true
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private let pageSize: UInt32 = 50
    private nonisolated static let noFilter = TimelineFilter(journalId: nil, tagIds: [], minRating: nil, favoritesOnly: false)

    var body: some View {
        List {
            heading
            todayRule
            todaySection
            ForEach(earlierDays, id: \.date) { day in
                Section {
                    ForEach(day.entries, id: \.id) { e in
                        row(e, first: e.id == day.entries[0].id)
                            .transition(.opacity.combined(with: .move(edge: .top)))
                            .onAppear { if e.id == entries.last?.id { Task { await loadMore() } } }
                    }
                } header: {
                    Text(Day.title(day.date)).microLabel()
                        .reveal(firstLoad, index: order[day.entries[0].id] ?? 0)
                        .contextMenu { newEntryOn(day.date) }
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(Color.paper)
        .navigationTitle("Journal")
        #if os(iOS)
        // The day itself is the heading; a bar above it is dead space.
        .toolbar(.hidden, for: .navigationBar)
        #endif
        .overlay(alignment: .bottomTrailing) { NewEntryButton(date: nil) }
        .sheet(isPresented: $addingPhoto) { PhotoChooser(date: Day.today) }
        .task(id: app.revision) { await reload() }
    }

    // MARK: Today

    /// The day, large ("Today"), and its full date underneath.
    private var heading: some View {
        TimelineView(.everyMinute) { _ in
            let today = Day.today
            VStack(alignment: .leading, spacing: 2) {
                Text(Day.heading(today))
                    .font(Typeface.serif(34, bold: true, relativeTo: .largeTitle))
                    .foregroundStyle(Color.ink)
                Text(Day.fullDate(today))
                    .font(.system(size: 15))
                    .foregroundStyle(Color.muted)
            }
            .padding(.top, 12)
            .padding(.bottom, 4)
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isHeader)
        }
        .listRowBackground(Color.paper)
        .listRowSeparator(.hidden)
    }

    /// The rule under today's heading, carrying an unworded add-photo
    /// button: sitting between the heading and today's entries, it can
    /// only mean today.
    private var todayRule: some View {
        HStack(spacing: 0) {
            rule.frame(width: 12)
            Button { addingPhoto = true } label: {
                Image(systemName: "photo.badge.plus")
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(Color.muted)
                    .padding(.horizontal, 10)
                    .frame(minWidth: 44, minHeight: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.pressable)
            .accessibilityLabel("Add photo to today")
            .help("Add photo to today")
            rule
        }
        .listRowBackground(Color.paper)
        .listRowSeparator(.hidden)
        .listRowInsets(EdgeInsets(top: 0, leading: 20, bottom: 0, trailing: 20))
    }

    private var rule: some View {
        Rectangle().fill(Color.hairline).frame(height: 1)
    }

    private var todayEntries: [EntrySummary] {
        entries.filter { $0.date == Day.today }
    }

    @ViewBuilder
    private var todaySection: some View {
        Section {
            if todayEntries.isEmpty {
                Button { Task { await app.newEntry(on: Day.today) } } label: {
                    HStack(alignment: .center, spacing: 12) {
                        if let photo = previews[Day.today] {
                            DayThumb(photo: photo).padding(.vertical, 8)
                        }
                        Label("New entry", systemImage: "square.and.pencil")
                            .font(.system(size: 15))
                            .foregroundStyle(Color.muted)
                    }
                    .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.pressable)
                .listRowBackground(Color.paper)
                .listRowSeparatorTint(Color.hairline)
                .transition(.opacity)
            } else {
                ForEach(todayEntries, id: \.id) { e in
                    row(e, first: e.id == todayEntries.first?.id).transition(.opacity.combined(with: .move(edge: .top)))
                }
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

    /// An entry, with its day's preview photo on the left of the day's
    /// first row. The day's later rows start at the margin, like any other.
    private func row(_ e: EntrySummary, first: Bool) -> some View {
        NavigationLink(value: Route.entry(e.id)) {
            HStack(alignment: .top, spacing: 12) {
                if first, let photo = previews[e.date] {
                    DayThumb(photo: photo, side: Self.thumbSide).padding(.top, 12)
                }
                EntryRow(entry: e, showDate: false)
            }
        }
        .buttonStyle(.pressable)
        .reveal(firstLoad, index: order[e.id] ?? 0)
        .listRowBackground(Color.paper)
        .listRowSeparatorTint(Color.hairline)
        .contextMenu { newEntryOn(e.date) }
    }

    private static let thumbSide: CGFloat = 52

    /// A missed day is written up from its own rows: no time of day, since
    /// the moment has gone (Day.defaultTime).
    @ViewBuilder
    private func newEntryOn(_ date: String) -> some View {
        Button { Task { await app.newEntry(on: date) } } label: {
            Label("New entry on \(Day.title(date))", systemImage: "square.and.pencil")
        }
    }

    // MARK: Loading

    private func reload() async {
        let size = pageSize
        guard let page = try? await app.run({ store in
            try store.timeline(filter: Self.noFilter, after: nil, limit: size)
        }) else { return }
        let shown = await DayPreview.load(app, dates: Self.days(in: page) + [Day.today])
        // The first load fades in; later ones animate what changed.
        withMotion(Motion.snappy, reduce: reduceMotion || firstLoad) {
            entries = page
            previews = shown
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
        let more = await DayPreview.load(app, dates: Self.days(in: page))
        entries += page
        previews.merge(more) { _, new in new }
        done = page.count < Int(pageSize)
    }

    private static func days(in page: [EntrySummary]) -> [String] {
        var seen = Set<String>()
        return page.map(\.date).filter { seen.insert($0).inserted }
    }
}

/// The one primary action, shared by Journal and Calendar: a new entry on
/// `date`, or today when nil. ⌘N on keyboards. Held (or right-clicked), it
/// offers a new entry on any other day, for one that was missed.
struct NewEntryButton: View {
    @Environment(AppModel.self) private var app
    let date: String?
    @State private var choosingDay = false

    var body: some View {
        Button { Task { await app.newEntry(on: date ?? Day.today) } } label: {
            Image(systemName: "plus")
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(Color.onPrimary)
                .frame(width: 56, height: 56)
                .background(Color.primary, in: RoundedRectangle(cornerRadius: Radius.card))
                .contentShape(RoundedRectangle(cornerRadius: Radius.card))
        }
        .buttonStyle(.pressable(scale: 0.92))
        .padding(20)
        .accessibilityLabel(date.map { $0 == Day.today ? "New entry" : "New entry on \(Day.title($0))" } ?? "New entry")
        .accessibilityAction(named: "New entry on another day") { choosingDay = true }
        .keyboardShortcut("n", modifiers: .command)
        .contextMenu {
            Button { choosingDay = true } label: {
                Label("New entry on another day…", systemImage: "calendar.badge.plus")
            }
        }
        .sheet(isPresented: $choosingDay) { NewEntrySheet(date: date ?? Day.today) }
    }
}

/// "New entry on another day…": any day, with or without a time. A past
/// day starts untimed (Day.defaultTime); today starts at now.
struct NewEntrySheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State private var day: Date
    @State private var timed: Bool
    @State private var time = Date()

    init(date: String) {
        _day = State(initialValue: Day.date(date) ?? Date())
        _timed = State(initialValue: Day.defaultTime(for: date) != nil)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    DatePicker("Day", selection: $day, displayedComponents: .date)
                        .datePickerStyle(.graphical)
                        .listRowBackground(Color.raised)
                } header: {
                    Text("Day").microLabel()
                }
                Section {
                    Toggle("Time", isOn: $timed)
                        .tint(Color.primary)
                        .listRowBackground(Color.raised)
                    if timed {
                        DatePicker("At", selection: $time, displayedComponents: .hourAndMinute)
                            .listRowBackground(Color.raised)
                    }
                } footer: {
                    Text("Without a time, the entry covers the whole day.")
                        .font(.system(size: 12))
                        .foregroundStyle(Color.muted)
                }
            }
            .formStyle(.grouped)
            .scrollContentBackground(.hidden)
            .background(Color.paper)
            .navigationTitle("New entry")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .onChange(of: day) { _, d in timed = Day.defaultTime(for: Day.string(d)) != nil }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Create", action: create)
                }
            }
        }
        .tint(Color.primary)
        #if os(iOS)
        .presentationDetents([.large])
        #else
        .frame(minWidth: 380, minHeight: 520)
        #endif
    }

    private func create() {
        let c = Calendar.current.dateComponents([.hour, .minute], from: time)
        let at = timed ? String(format: "%02d:%02d", c.hour ?? 0, c.minute ?? 0) : nil
        let date = Day.string(day)
        dismiss()
        Task { await app.newEntry(on: date, at: at) }
    }
}
