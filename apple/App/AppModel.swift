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
        sync.onChange = { [weak self] in self?.changed() }
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

    func newEntry(on date: String? = nil) async -> String? {
        let day = date ?? selectedDate
        let time = day == Day.today ? Day.nowTime : nil
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

    /// "3 Oct 2025"
    static func short(_ s: String) -> String {
        guard let d = date(s) else { return s }
        let sameYear = Calendar.current.isDate(d, equalTo: Date(), toGranularity: .year)
        return sameYear
            ? d.formatted(.dateTime.day().month(.abbreviated))
            : d.formatted(.dateTime.day().month(.abbreviated).year())
    }

    static func yearsAgo(_ s: String, from today: String = Day.today) -> Int {
        guard let a = date(s), let b = date(today) else { return 0 }
        return Calendar.current.dateComponents([.year], from: a, to: b).year ?? 0
    }
}
