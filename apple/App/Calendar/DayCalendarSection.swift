import SwiftUI

/// A day's calendar, in the editor and under a day in Calendar
/// (docs/calendar.md). Kept events are part of the journal, each with room
/// for a note; the calendar's other events that day are offered underneath
/// as suggestions, one tap to keep. Nothing is stored until then.
struct DayCalendarSection: View {
    @Environment(AppModel.self) private var app
    let date: String

    @State private var kept: [KeptEvent] = []
    @State private var suggestions: [CalendarEvent] = []
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if !kept.isEmpty || !suggestions.isEmpty {
                Text("Calendar").microLabel().padding(.top, 6).padding(.bottom, 4)
            }
            ForEach(kept, id: \.id) { event in
                KeptEventRow(event: event)
                    .transition(.opacity.combined(with: .move(edge: .top)))
            }
            ForEach(suggestions, id: \.key) { event in
                suggestionRow(event)
                    .transition(.opacity)
            }
            if app.calendarAccess == .notAsked {
                Button {
                    Task { await app.requestCalendarAccess() }
                } label: {
                    Label("Show this day's calendar", systemImage: "calendar.badge.plus")
                        .font(.system(size: 13))
                        .foregroundStyle(Color.muted)
                        .frame(minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .sensoryFeedback(.success, trigger: kept.count) { old, new in new > old }
        .task(id: "\(date)|\(app.revision)|\(app.calendarAccess == .granted)") { await load() }
    }

    private func suggestionRow(_ event: CalendarEvent) -> some View {
        Button {
            keep(event)
        } label: {
            HStack(spacing: 10) {
                Image(systemName: "plus")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(Color.primary)
                    .frame(width: 22, height: 22)
                    .overlay(RoundedRectangle(cornerRadius: Radius.control).strokeBorder(Color.hairline))
                Text(event.title.isEmpty ? String(localized: "Untitled event") : event.title)
                    .font(.system(size: 14))
                    .foregroundStyle(Color.muted)
                    .lineLimit(1)
                Spacer(minLength: 8)
                Text(EventTime.label(start: event.start, end: event.end))
                    .font(Typeface.mono(11))
                    .foregroundStyle(Color.dim)
            }
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.pressable)
        .accessibilityLabel("Add \(event.title) to the journal")
    }

    private func keep(_ event: CalendarEvent) {
        let day = date
        withMotion(Motion.snappy, reduce: reduceMotion) { suggestions.removeAll { $0.key == event.key } }
        Task {
            await app.write { _ = try $0.keepCalendarEvent(journalId: nil, date: day, event: event) }
        }
    }

    private func load() async {
        let day = date
        let events = await app.calendar.events(on: day)
        let granted = app.calendarAccess == .granted
        let result: CalendarDay? = try? await app.run { store in
            granted
                ? try store.calendarDay(journalId: nil, date: day, events: events)
                : CalendarDay(kept: try store.keptCalendarEvents(journalId: nil, date: day), suggestions: [])
        }
        guard let result else { return }
        withMotion(Motion.snappy, reduce: reduceMotion) {
            kept = result.kept
            suggestions = result.suggestions
        }
    }
}

/// One kept event: title, time, calendar, a quiet tag when it has moved or
/// been cancelled, and the user's note, saved as they type.
private struct KeptEventRow: View {
    @Environment(AppModel.self) private var app
    let event: KeptEvent
    @State private var note = ""
    @State private var saveTask: Task<Void, Never>?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Image(systemName: "calendar")
                    .font(.system(size: 12))
                    .foregroundStyle(event.state == .cancelled ? Color.dim : Color.primary)
                Text(event.title.isEmpty ? String(localized: "Untitled event") : event.title)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(event.state == .cancelled ? Color.muted : Color.ink)
                    .strikethrough(event.state == .cancelled, color: Color.muted)
                Spacer(minLength: 8)
                switch event.state {
                case .moved: StatusTag(text: String(localized: "Moved"), hue: .warning)
                case .cancelled: StatusTag(text: String(localized: "Cancelled"), hue: .danger)
                case .scheduled: EmptyView()
                }
                Menu {
                    Button("Remove from this day", role: .destructive) {
                        let id = event.id
                        Task { await app.write { try $0.removeCalendarEvent(id: id) } }
                    }
                } label: {
                    Image(systemName: "ellipsis")
                        .font(.system(size: 13))
                        .foregroundStyle(Color.muted)
                        .frame(width: 32, height: 32)
                        .contentShape(Rectangle())
                }
                .menuStyle(.button)
                .buttonStyle(.plain)
                .accessibilityLabel("More for \(event.title)")
            }
            Text(details)
                .font(Typeface.mono(11))
                .foregroundStyle(Color.muted)
            TextField("Note", text: $note, axis: .vertical)
                .font(.system(size: 14))
                .foregroundStyle(Color.ink)
                .textFieldStyle(.plain)
                .padding(.vertical, 6)
                .onChange(of: note) { scheduleSave() }
                .onSubmit { scheduleSave() }
        }
        .padding(.vertical, 8)
        .overlay(alignment: .bottom) { Rectangle().fill(Color.hairline).frame(height: 1) }
        .onAppear { note = event.note ?? "" }
        .onChange(of: event.note) { _, new in
            if saveTask == nil { note = new ?? "" }
        }
        .onDisappear { flush() }
    }

    /// "09:30–10:00 · Work · Room 2", with the new day first when it moved off this one.
    private var details: String {
        var parts: [String] = []
        if event.movedToAnotherDay {
            parts.append(String(localized: "Now \(Day.short(String(event.start.prefix(10))))"))
        }
        parts.append(event.allDay ? String(localized: "All day") : event.timeLabel)
        if let c = event.calendar { parts.append(c) }
        if let l = event.location { parts.append(l) }
        return parts.joined(separator: " · ")
    }

    private func scheduleSave() {
        saveTask?.cancel()
        saveTask = Task {
            try? await Task.sleep(for: .milliseconds(700))
            guard !Task.isCancelled else { return }
            await save()
        }
    }

    private func flush() {
        guard saveTask != nil else { return }
        saveTask?.cancel()
        Task { await save() }
    }

    private func save() async {
        saveTask = nil
        let (id, text) = (event.id, note)
        guard text.trimmingCharacters(in: .whitespacesAndNewlines) != (event.note ?? "") else { return }
        await app.write { try $0.setCalendarEventNote(id: id, note: text) }
    }
}

/// Times the way the core writes them: `YYYY-MM-DDTHH:MM`, or a bare date
/// for all day.
enum EventTime {
    static func label(start: String, end: String?) -> String {
        guard start.count == 16 else { return String(localized: "All day") }
        let from = String(start.suffix(5))
        if let end, end.count == 16, end.prefix(10) == start.prefix(10), end.suffix(5) != from {
            return "\(from)–\(end.suffix(5))"
        }
        return from
    }
}
