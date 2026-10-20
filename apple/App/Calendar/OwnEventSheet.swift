import SwiftUI

/// "Add event" on a day, past or future (docs/calendar.md, "Events you add
/// yourself"): a title, all day or a start and optional end time, and an
/// optional place. Given an own event, it edits that one instead and offers
/// to take it off the day.
struct OwnEventSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let date: String
    let editing: KeptEvent?

    @State private var title: String
    @State private var place: String
    @State private var allDay: Bool
    @State private var start: Date
    @State private var hasEnd: Bool
    @State private var end: Date
    @State private var error: String?
    @State private var busy = false
    @FocusState private var titleFocused: Bool

    init(date: String, editing: KeptEvent? = nil) {
        self.date = date
        self.editing = editing
        let day = Day.date(date) ?? Date()
        let startTime = editing.flatMap { Self.time($0.start, on: day) }
        let endTime = editing.flatMap { e in e.end.flatMap { Self.time($0, on: day) } }
        let defaultStart = Calendar.current.date(bySettingHour: 9, minute: 0, second: 0, of: day) ?? day
        let s = startTime ?? defaultStart
        _title = State(initialValue: editing?.title ?? "")
        _place = State(initialValue: editing?.location ?? "")
        _allDay = State(initialValue: editing.map { $0.allDay || startTime == nil } ?? true)
        _start = State(initialValue: s)
        _hasEnd = State(initialValue: endTime != nil)
        _end = State(initialValue: endTime ?? s.addingTimeInterval(3600))
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Title", text: $title)
                        .font(Typeface.serif(17))
                        .focused($titleFocused)
                        .submitLabel(.done)
                        .onSubmit(save)
                        .listRowBackground(Color.raised)
                    TextField("Place (optional)", text: $place)
                        .font(.system(size: 15))
                        .listRowBackground(Color.raised)
                } header: {
                    Text(Day.fullDate(date)).microLabel()
                }

                Section {
                    Toggle("All day", isOn: $allDay.animation(Motion.animation(Motion.snappy, reduce: reduceMotion)))
                        .tint(Color.primary)
                        .listRowBackground(Color.raised)
                    if !allDay {
                        DatePicker("Starts", selection: $start, displayedComponents: .hourAndMinute)
                            .font(.system(size: 15))
                            .listRowBackground(Color.raised)
                        Toggle("End time", isOn: $hasEnd.animation(Motion.animation(Motion.snappy, reduce: reduceMotion)))
                            .tint(Color.primary)
                            .listRowBackground(Color.raised)
                        if hasEnd {
                            DatePicker("Ends", selection: $end, displayedComponents: .hourAndMinute)
                                .font(.system(size: 15))
                                .listRowBackground(Color.raised)
                        }
                    }
                } header: {
                    Text("Time").microLabel()
                }

                if let error {
                    Section {
                        Label(error, systemImage: "exclamationmark.triangle")
                            .font(.system(size: 13))
                            .foregroundStyle(Color.danger)
                            .padding(.vertical, 2)
                            .listRowBackground(Color.danger.opacity(0.1))
                    }
                }

                if let editing {
                    Section {
                        Button("Remove from this day", role: .destructive) { remove(editing) }
                            .foregroundStyle(Color.danger)
                            .frame(minHeight: 44)
                            .listRowBackground(Color.raised)
                    }
                }
            }
            .formStyle(.grouped)
            .scrollContentBackground(.hidden)
            .background(Color.paper)
            .navigationTitle(editing == nil ? "Add event" : "Edit event")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if busy {
                        ProgressView()
                    } else {
                        Button(editing == nil ? "Add" : "Save", action: save)
                            .disabled(trimmedTitle.isEmpty)
                    }
                }
            }
            .disabled(busy)
            .onChange(of: allDay) { error = nil }
            .onChange(of: hasEnd) { error = nil }
            .onChange(of: start) { error = nil }
            .onChange(of: end) { error = nil }
            .onAppear { if editing == nil { titleFocused = true } }
        }
        .tint(Color.primary)
        #if os(iOS)
        .presentationDetents([.medium, .large])
        #else
        .frame(minWidth: 420, minHeight: 360)
        #endif
    }

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var trimmedTitle: String { title.trimmingCharacters(in: .whitespacesAndNewlines) }

    private func save() {
        guard !trimmedTitle.isEmpty, !busy else { return }
        let startTime = allDay ? nil : Self.hhmm(start)
        let endTime = allDay || !hasEnd ? nil : Self.hhmm(end)
        if let s = startTime, let e = endTime, e <= s {
            error = String(localized: "The end needs to be after the start.")
            return
        }
        let trimmedPlace = place.trimmingCharacters(in: .whitespacesAndNewlines)
        let event = OwnEvent(title: trimmedTitle, startTime: startTime, endTime: endTime,
                             location: trimmedPlace.isEmpty ? nil : trimmedPlace)
        let (day, id) = (date, editing?.id)
        busy = true
        Task {
            defer { busy = false }
            do {
                _ = try await app.run { store in
                    if let id { try store.updateOwnEvent(id: id, event: event) }
                    else { try store.addOwnEvent(journalId: nil, date: day, event: event) }
                }
                app.changed()
                app.sync.schedulePush()
                dismiss()
            } catch {
                self.error = error.coreMessage
            }
        }
    }

    private func remove(_ event: KeptEvent) {
        let id = event.id
        busy = true
        Task {
            defer { busy = false }
            do {
                try await app.run { try $0.removeCalendarEvent(id: id) }
                app.changed()
                app.sync.schedulePush()
                dismiss()
            } catch {
                self.error = error.coreMessage
            }
        }
    }

    /// "09:30" for the core.
    private static func hhmm(_ date: Date) -> String {
        let c = Calendar.current.dateComponents([.hour, .minute], from: date)
        return String(format: "%02d:%02d", c.hour ?? 0, c.minute ?? 0)
    }

    /// The time of a core timestamp (`YYYY-MM-DDTHH:MM`) on `day`; nil for a bare date.
    private static func time(_ stamp: String, on day: Date) -> Date? {
        guard stamp.count >= 16 else { return nil }
        let parts = stamp.dropFirst(11).prefix(5).split(separator: ":")
        guard parts.count == 2, let h = Int(parts[0]), let m = Int(parts[1]) else { return nil }
        return Calendar.current.date(bySettingHour: h, minute: m, second: 0, of: day)
    }
}

extension Error {
    /// What the core said, without the enum dressing; anything else as is.
    var coreMessage: String {
        guard let e = self as? CoreError else { return localizedDescription }
        switch e {
        case .Database(let m), .NotFound(let m), .Invalid(let m), .Sync(let m), .Crypto(let m):
            return m.prefix(1).uppercased() + m.dropFirst() + (m.hasSuffix(".") ? "" : ".")
        }
    }
}
