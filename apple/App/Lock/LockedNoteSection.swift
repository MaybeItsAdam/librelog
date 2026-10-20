import SwiftUI

/// A day's locked note (docs/locked-notes.md): one tile, shown only while
/// locked notes are on. "Add a locked note" on a day without one, "Locked"
/// on a day with one. Tapping always asks for the password.
struct LockedNoteSection: View {
    @Environment(AppModel.self) private var app
    let date: String

    @State private var enabled = false
    @State private var hasNote = false
    @State private var opening = false

    var body: some View {
        // A real container, so the load still runs while nothing shows.
        VStack(spacing: 0) {
            if enabled {
                Button { opening = true } label: { tile }
                    .buttonStyle(.pressable)
                    .accessibilityLabel(hasNote ? "Locked note" : "Add a locked note")
                    .accessibilityHint("Asks for the lock password")
                    .padding(.vertical, 8)
            }
        }
        .task(id: "\(date)|\(app.revision)") { await load() }
        .sheet(isPresented: $opening) { LockedNoteSheet(date: date) }
    }

    private var tile: some View {
        HStack(spacing: 10) {
            Image(systemName: hasNote ? "lock.fill" : "lock")
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(hasNote ? Color.ink : Color.muted)
                .frame(width: 22)
            Text(hasNote ? "Locked" : "Add a locked note")
                .font(.system(size: 14, weight: hasNote ? .medium : .regular))
                .foregroundStyle(hasNote ? Color.ink : Color.muted)
            Spacer(minLength: 8)
            Image(systemName: "chevron.right")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(Color.dim)
        }
        .padding(.horizontal, 12)
        .frame(minHeight: 44)
        .background(hasNote ? Color.raised : Color.clear, in: RoundedRectangle(cornerRadius: Radius.control))
        .overlay(RoundedRectangle(cornerRadius: Radius.control).strokeBorder(Color.hairline))
        .contentShape(Rectangle())
    }

    private func load() async {
        let day = date
        let result: (Bool, Bool)? = try? await app.run { store in
            let status = try store.lockStatus()
            guard status.enabled else { return (false, false) }
            return (true, try store.hasLockedNote(journalId: nil, date: day))
        }
        guard let result else { return }
        (enabled, hasNote) = result
    }
}

/// Opening a locked note: the password every time, then a plain editor.
/// The text and password live only in this sheet's state; closing it saves
/// any change under the password used to open it and forgets both.
struct LockedNoteSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    let date: String

    @State private var password = ""
    @State private var wrong = false
    @State private var problem: String?
    @State private var busy = false
    /// Set once open: the note as it was, the text being edited, and the
    /// password that opened it.
    @State private var opened: (original: String, password: String)?
    @State private var text = ""
    @FocusState private var focus: Field?

    private enum Field { case password, note }

    var body: some View {
        NavigationStack {
            Group {
                if opened != nil { editor } else { unlock }
            }
            .background(Color.paper)
            .navigationTitle("Locked note")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                if opened != nil {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Done") { dismiss() }
                    }
                } else {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { dismiss() }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        if busy { ProgressView() } else {
                            Button("Open", action: open).disabled(password.isEmpty)
                        }
                    }
                }
            }
        }
        .tint(Color.primary)
        #if os(iOS)
        .presentationDetents(opened == nil ? [.medium] : [.large])
        #else
        .frame(minWidth: 460, minHeight: opened == nil ? 240 : 420)
        #endif
        .onDisappear(perform: close)
        // Leaving the app closes the note, so it never sits open behind the app switcher.
        .onChange(of: scenePhase) { _, phase in
            if phase == .background { dismiss() }
        }
    }

    private var unlock: some View {
        Form {
            Section {
                SecureField("Lock password", text: $password)
                    .focused($focus, equals: .password)
                    .submitLabel(.go)
                    .onSubmit(open)
                    .onChange(of: password) { _, new in if !new.isEmpty { wrong = false } }
                    .disabled(busy)
                    .listRowBackground(Color.raised)
            } header: {
                Text(Day.fullDate(date)).microLabel()
            } footer: {
                if wrong {
                    Text("That's not the lock password")
                        .font(.system(size: 13))
                        .foregroundStyle(Color.danger)
                } else if let problem {
                    Text(problem)
                        .font(.system(size: 13))
                        .foregroundStyle(Color.danger)
                }
            }
        }
        .formStyle(.grouped)
        .scrollContentBackground(.hidden)
    }

    private var editor: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Image(systemName: "lock.open").font(.system(size: 10, weight: .bold)).foregroundStyle(Color.muted)
                Text(Day.fullDate(date)).microLabel()
            }
            TextEditor(text: $text)
                .font(Typeface.body)
                .foregroundStyle(Color.ink)
                .scrollContentBackground(.hidden)
                .focused($focus, equals: .note)
                .padding(8)
                .background(Color.raised, in: RoundedRectangle(cornerRadius: Radius.card))
                .overlay(RoundedRectangle(cornerRadius: Radius.card).strokeBorder(Color.hairline))
            Text("Saved when you close it. Clear all the text to remove the note.")
                .font(.system(size: 12))
                .foregroundStyle(Color.muted)
        }
        .padding(20)
        .onAppear { focus = .note }
    }

    private func open() {
        guard !password.isEmpty, !busy else { return }
        let (day, pw) = (date, password)
        busy = true
        problem = nil
        Task {
            defer { busy = false }
            do {
                let note = try await app.run { try $0.lockedNote(journalId: nil, date: day, password: pw) } ?? ""
                password = ""
                text = note
                opened = (note, pw)
            } catch CoreError.Crypto(let message) where message == "wrong password" {
                password = ""
                wrong = true
                focus = .password
            } catch {
                password = ""
                problem = error.coreMessage
            }
        }
    }

    /// Saves a change under the opening password, then forgets the text and
    /// the password.
    private func close() {
        let day = date
        if let opened, text != opened.original {
            let (pw, note) = (opened.password, text)
            let empty = note.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            Task {
                await app.write { try $0.setLockedNote(journalId: nil, date: day, password: pw, text: empty ? "" : note) }
            }
        }
        opened = nil
        text = ""
        password = ""
    }
}
