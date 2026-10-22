import Foundation
import Observation

/// The Supabase project the apps sync through (supabase/README.md). The
/// publishable key is public by design: it only identifies the project, and
/// row-level security plus the sync functions decide what any caller can do.
enum Backend {
    static let url = URL(string: "https://wzkhuilypzapqkrjxeku.supabase.co")!
    static let publishableKey = "sb_publishable_tNzC2ZUmrDOFk_QTBWYY7A_KDfU-8aY"
}

/// App-wide state. Owns the core `Store`; every core call runs off the main
/// actor via `run`, so typing and scrolling never wait on SQLite.
@MainActor
@Observable
final class AppModel {
    let store: Store
    let sync: SyncEngine
    let calendar = DeviceCalendar()
    /// Whether the device calendar can be read; asked on first use only.
    private(set) var calendarAccess: DeviceCalendar.Access
    private var calendarRefreshedAt: Date?

    /// Bumped after any write or merged pull, so screens reload.
    private(set) var revision = 0
    var selectedDate = Day.today
    var openEntryID: String?
    var lastError: String?
    /// Counts entries made here, so the UI can acknowledge each one.
    private(set) var created = 0

    init(store: Store) {
        self.store = store
        self.sync = SyncEngine(store: store)
        self.calendarAccess = calendar.access
        // Read once before the first frame, so the theme doesn't flash.
        if let a = try? Appearance.load(from: store) { AppearanceModel.shared.apply(a) }
        sync.onChange = { [weak self] in
            self?.changed()
            // Appearance is synced, so a pull may have changed it.
            Task { await self?.loadAppearance() }
        }
    }

    static func launch() -> AppModel {
        do {
            return AppModel(store: try Store.open(path: databasePath()))
        } catch {
            // The database is ours and lives in Application Support, so this
            // only fails if the disk is full or the file is corrupt.
            fatalError("Couldn't open the journal database: \(error)")
        }
    }

    private static func databasePath() -> String {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Librelog", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("journal.sqlite").path
    }

    /// Runs a core call off the main actor and returns its result.
    nonisolated func run<T: Sendable>(_ work: @escaping @Sendable (Store) throws -> T) async throws -> T {
        let store = self.store
        return try await Task.detached(priority: .userInitiated) { try work(store) }.value
    }

    /// Runs a write, reports failure, then refreshes screens and schedules a push.
    func write(_ work: @escaping @Sendable (Store) throws -> Void) async {
        do {
            try await run(work)
            changed()
            sync.schedulePush()
        } catch {
            lastError = error.localizedDescription
        }
    }

    func changed() {
        revision &+= 1
    }

    // MARK: Appearance (docs/appearance.md)

    func loadAppearance() async {
        if let a = try? await run({ try Appearance.load(from: $0) }) {
            AppearanceModel.shared.apply(a)
        }
    }

    /// Applies one appearance choice at once and saves it as a synced
    /// setting; the default is stored as unset.
    func setAppearance<V: RawRepresentable<String> & Sendable>(_ path: WritableKeyPath<Appearance, V>, _ value: V, key: String) {
        var a = AppearanceModel.shared.value
        a[keyPath: path] = value
        AppearanceModel.shared.apply(a)
        let stored: String? = Appearance()[keyPath: path] == value ? nil : value.rawValue
        Task { await write { try $0.setSetting(key: key, value: stored) } }
    }

    // MARK: Entries

    /// Deletes the entry if nothing was ever written in it, as the editor
    /// closes, and refreshes lists when it did.
    @discardableResult
    func discardIfEmpty(_ id: String) async -> Bool {
        guard (try? await run({ try $0.discardIfEmpty(id: id) })) == true else { return false }
        changed()
        sync.schedulePush()
        return true
    }

    // MARK: Photos (docs/photos.md)

    /// Fills in the iCloud identifier of photos added before iCloud had
    /// uploaded them, so other devices can find them in their own library.
    func resolvePhotoSourceRefs() async {
        guard PhotoLibrary.canRead,
              let waiting = try? await run({ try $0.photosAwaitingSourceRef(assetPrefix: "apple:") }),
              !waiting.isEmpty else { return }
        let refs = PhotoLibrary.cloudRefs(for: waiting.map { String($0.localAsset.dropFirst("apple:".count)) })
        let updates = waiting.compactMap { p in
            refs[String(p.localAsset.dropFirst("apple:".count))].map { (p.id, $0) }
        }
        guard !updates.isEmpty else { return }
        await write { store in
            for (id, ref) in updates { try store.setPhotoSourceRef(id: id, sourceRef: ref) }
        }
    }

    // MARK: Calendar (docs/calendar.md)

    func requestCalendarAccess() async {
        _ = await calendar.requestAccess()
        calendarAccess = calendar.access
        changed()
    }

    /// Brings every kept event this device can see up to date with the
    /// calendar: renamed, moved or cancelled since. Runs on launch and on
    /// coming to the foreground, at most every few minutes, and only once
    /// the user has granted access. Events kept on days more than 90 days
    /// back are left as they are.
    func refreshCalendar(force: Bool = false) async {
        calendarAccess = calendar.access
        guard calendarAccess == .granted else { return }
        if !force, let last = calendarRefreshedAt, Date().timeIntervalSince(last) < 300 { return }
        calendarRefreshedAt = Date()
        let since = Day.adding(-90, to: Day.today)
        guard let tracked = try? await run({ try $0.trackedCalendarEvents(keyPrefix: "apple:") }) else { return }
        let lookups = await calendar.lookUp(tracked.filter { $0.date >= since })
        guard !lookups.isEmpty else { return }
        do {
            if try await run({ try $0.refreshCalendarEvents(lookups: lookups) }) > 0 {
                changed()
                sync.schedulePush()
            }
        } catch {
            lastError = error.localizedDescription
        }
    }

    /// A new entry on `date`, or the selected day when nil, at the day's
    /// default time (`Day.defaultTime`).
    func newEntry(on date: String? = nil) async -> String? {
        let day = date ?? selectedDate
        return await newEntry(on: day, at: Day.defaultTime(for: day))
    }

    /// A new entry on `day` at `time`, or with no time of day when nil.
    func newEntry(on day: String, at time: String?) async -> String? {
        do {
            let entry = try await run { try $0.createEntry(journalId: nil, date: day, time: time) }
            changed()
            sync.schedulePush()
            openEntryID = entry.id
            created &+= 1
            return entry.id
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }
}

/// Local calendar dates as the core expects them: `YYYY-MM-DD`.
enum Day {
    private static let formatter: DateFormatter = {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .gregorian)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()

    static var today: String { string(Date()) }

    static var nowTime: String {
        let c = Calendar.current.dateComponents([.hour, .minute], from: Date())
        return String(format: "%02d:%02d", c.hour ?? 0, c.minute ?? 0)
    }

    /// The time a new entry on `day` starts with: now for today. A missed
    /// day written up later has none, since the moment has gone.
    static func defaultTime(for day: String, today: String = Day.today) -> String? {
        day == today ? nowTime : nil
    }

    static func string(_ date: Date) -> String {
        formatter.string(from: date)
    }

    static func date(_ s: String) -> Date? {
        formatter.date(from: s)
    }

    static func adding(_ days: Int, to s: String) -> String {
        guard let d = date(s), let n = Calendar.current.date(byAdding: .day, value: days, to: d) else { return s }
        return string(n)
    }

    /// "Saturday 3 October" (or "Today" / "Yesterday").
    static func title(_ s: String) -> String {
        if s == today { return String(localized: "Today") }
        if s == adding(-1, to: today) { return String(localized: "Yesterday") }
        guard let d = date(s) else { return s }
        return d.formatted(.dateTime.weekday(.wide).day().month(.wide))
    }

    /// "Today", "Yesterday", "Tomorrow", or the weekday: a day's name as
    /// the heading of a screen showing it.
    static func heading(_ s: String) -> String {
        if s == today { return String(localized: "Today") }
        if s == adding(-1, to: today) { return String(localized: "Yesterday") }
        if s == adding(1, to: today) { return String(localized: "Tomorrow") }
        guard let d = date(s) else { return s }
        return d.formatted(.dateTime.weekday(.wide))
    }

    /// "Sunday 18 October 2026"
    static func fullDate(_ s: String) -> String {
        guard let d = date(s) else { return s }
        return d.formatted(.dateTime.weekday(.wide).day().month(.wide).year())
    }

    /// "3 Oct 2025"
    static func short(_ s: String) -> String {
        guard let d = date(s) else { return s }
        let sameYear = Calendar.current.isDate(d, equalTo: Date(), toGranularity: .year)
        return sameYear
            ? d.formatted(.dateTime.day().month(.abbreviated))
            : d.formatted(.dateTime.day().month(.abbreviated).year())
    }

    /// "26 – 27 Aug", "29 Sep – 2 Oct 2025", "29 Dec 2025 – 2 Jan 2026"; just
    /// `short` when there's no end. The year shows when it isn't this one.
    static func range(_ start: String, _ end: String?) -> String {
        guard let end, end > start, let a = date(start), let b = date(end) else { return short(start) }
        let cal = Calendar.current
        let thisYear = cal.isDate(b, equalTo: Date(), toGranularity: .year)
        let last = thisYear
            ? b.formatted(.dateTime.day().month(.abbreviated))
            : b.formatted(.dateTime.day().month(.abbreviated).year())
        let first: String
        if !cal.isDate(a, equalTo: b, toGranularity: .year) {
            first = a.formatted(.dateTime.day().month(.abbreviated).year())
        } else if cal.isDate(a, equalTo: b, toGranularity: .month) {
            first = a.formatted(.dateTime.day())
        } else {
            first = a.formatted(.dateTime.day().month(.abbreviated))
        }
        return "\(first) – \(last)"
    }

    /// Days from `a` to `b` (positive when `b` is later).
    static func between(_ a: String, _ b: String) -> Int {
        guard let x = date(a), let y = date(b) else { return 0 }
        return Calendar.current.dateComponents([.day], from: x, to: y).day ?? 0
    }

    static func yearsAgo(_ s: String, from today: String = Day.today) -> Int {
        guard let a = date(s), let b = date(today) else { return 0 }
        return Calendar.current.dateComponents([.year], from: a, to: b).year ?? 0
    }
}
