import EventKit
import Foundation

/// The device's calendars through EventKit, which already include any Google,
/// Exchange or iCloud account added in Settings (docs/calendar.md). Reading
/// only: LibreLog never writes to a calendar. Access is asked for the first
/// time the user taps "Show calendar", never at launch.
///
/// EventKit calls are synchronous and can take a moment on a big calendar,
/// so every read here runs off the main actor.
final class DeviceCalendar: @unchecked Sendable {
    /// `EKEventStore` is thread-safe for reads; the lock only guards
    /// replacing it after access is granted.
    private var store = EKEventStore()
    private let lock = NSLock()

    enum Access: Equatable { case notAsked, granted, denied }

    var access: Access {
        switch EKEventStore.authorizationStatus(for: .event) {
        case .fullAccess: .granted
        case .notDetermined: .notAsked
        default: .denied // denied, restricted, or write-only (which can't read)
        }
    }

    /// Asks for read access; true once granted.
    func requestAccess() async -> Bool {
        let granted = (try? await currentStore().requestFullAccessToEvents()) ?? false
        if granted {
            // A store made before access was granted can keep returning nothing.
            lock.withLock { store = EKEventStore() }
        }
        return granted
    }

    /// Every event touching one local day, as the core expects them.
    func events(on day: String) async -> [CalendarEvent] {
        guard access == .granted, let start = Day.date(day),
              let end = Calendar.current.date(byAdding: .day, value: 1, to: start) else { return [] }
        let store = currentStore()
        return await Task.detached(priority: .userInitiated) {
            let predicate = store.predicateForEvents(withStart: start, end: end, calendars: nil)
            return store.events(matching: predicate).compactMap(Self.calendarEvent)
        }.value
    }

    /// Looks up each kept event this device can track. An empty result is
    /// returned without access, so nothing is ever taken for cancelled just
    /// because the calendar couldn't be read.
    func lookUp(_ kept: [KeptEvent]) async -> [CalendarLookup] {
        guard access == .granted else { return [] }
        let store = currentStore()
        return await Task.detached(priority: .utility) {
            kept.compactMap { k -> CalendarLookup? in
                guard let id = Self.parse(k.key) else { return nil }
                return CalendarLookup(key: k.key, event: Self.find(id, in: store).flatMap(Self.calendarEvent))
            }
        }.value
    }

    private func currentStore() -> EKEventStore {
        lock.withLock { store }
    }

    // MARK: Identity

    /// `apple:<external id>` for a one-off event, and
    /// `apple:<external id>@<original occurrence, unix seconds>` for one
    /// occurrence of a repeating event. The external identifier is the
    /// server's (Google's, Exchange's), so it is the same on every Apple
    /// device and survives edits; the occurrence date is when that
    /// occurrence was *originally* scheduled, which EventKit keeps even
    /// after it's moved.
    static func key(for event: EKEvent) -> String? {
        let external = event.calendarItemExternalIdentifier ?? event.calendarItemIdentifier
        guard !external.isEmpty else { return nil }
        if event.hasRecurrenceRules || event.isDetached, let occurrence = event.occurrenceDate {
            return "apple:\(external)@\(Int(occurrence.timeIntervalSince1970))"
        }
        return "apple:\(external)"
    }

    private struct Identity: Sendable {
        let external: String
        let occurrence: Date?
    }

    private static func parse(_ key: String) -> Identity? {
        guard key.hasPrefix("apple:") else { return nil }
        let rest = key.dropFirst("apple:".count)
        if let at = rest.lastIndex(of: "@"), let secs = Int(rest[rest.index(after: at)...]) {
            return Identity(external: String(rest[..<at]), occurrence: Date(timeIntervalSince1970: TimeInterval(secs)))
        }
        return Identity(external: String(rest), occurrence: nil)
    }

    /// One-off events are found by identifier wherever they've moved to. An
    /// occurrence of a repeating event is searched for within two months of
    /// where it was originally scheduled; one moved further reads as gone.
    private static func find(_ id: Identity, in store: EKEventStore) -> EKEvent? {
        guard let occurrence = id.occurrence else {
            let items = store.calendarItems(withExternalIdentifier: id.external).compactMap { $0 as? EKEvent }
            return items.first(where: { !$0.hasRecurrenceRules }) ?? items.first
        }
        let window: TimeInterval = 62 * 24 * 3600
        let predicate = store.predicateForEvents(withStart: occurrence - window, end: occurrence + window, calendars: nil)
        let secs = Int(occurrence.timeIntervalSince1970)
        return store.events(matching: predicate).first {
            ($0.calendarItemExternalIdentifier ?? $0.calendarItemIdentifier) == id.external
                && $0.occurrenceDate.map { Int($0.timeIntervalSince1970) } == secs
        }
    }

    // MARK: Conversion

    private static let minute: DateFormatter = {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .gregorian)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd'T'HH:mm"
        return f
    }()

    static func calendarEvent(_ e: EKEvent) -> CalendarEvent? {
        guard let key = key(for: e), let startDate = e.startDate else { return nil }
        let start: String
        var end: String?
        if e.isAllDay {
            start = Day.string(startDate)
            // EventKit ends an all-day event at 23:59:59 or at the next
            // midnight; either way a second earlier is its last day.
            let last = e.endDate.map { Day.string($0.addingTimeInterval(-1)) }
            end = (last ?? start) > start ? last : nil
        } else {
            start = minute.string(from: startDate)
            end = e.endDate.map { minute.string(from: $0) }
        }
        return CalendarEvent(
            key: key,
            title: e.title ?? "",
            start: start,
            end: end,
            location: e.location,
            calendar: e.calendar?.title,
            cancelled: e.status == .canceled
        )
    }
}
