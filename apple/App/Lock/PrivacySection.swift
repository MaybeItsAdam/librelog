import SwiftUI

/// Settings → Privacy (docs/locked-notes.md): the locked-notes switch, and
/// once there's a lock password, changing it or resetting everything.
struct PrivacySection: View {
    @Environment(AppModel.self) private var app
    @State private var status = LockStatus(enabled: false, hasPassword: false)
    @State private var passwordSheet: PasswordMode?
    @State private var confirmReset = false
    @State private var problem: String?

    var body: some View {
        Section {
            Toggle("Locked notes", isOn: Binding(get: { status.enabled }, set: { setEnabled($0) }))
                .tint(Color.primary)
                .listRowBackground(Color.raised)
                .task(id: app.revision) { await load() }
                .sheet(item: $passwordSheet) { LockPasswordSheet(mode: $0) }
                .confirmationDialog("Reset locked notes?", isPresented: $confirmReset, titleVisibility: .visible) {
                    Button("Delete every locked note", role: .destructive, action: reset)
                } message: {
                    Text("Every locked note, on every day, is deleted along with the lock password. There's no way to get them back. The rest of the journal isn't touched.")
                }
            if status.hasPassword {
                Button("Change password") { passwordSheet = .change }
                    .foregroundStyle(Color.ink)
                    .listRowBackground(Color.raised)
                Button("Reset locked notes", role: .destructive) { confirmReset = true }
                    .foregroundStyle(Color.danger)
                    .listRowBackground(Color.raised)
            }
            if let problem {
                Text(problem)
                    .font(.system(size: 13))
                    .foregroundStyle(Color.danger)
                    .listRowBackground(Color.raised)
            }
        } header: {
            Text("Privacy").microLabel()
        } footer: {
            Text("A locked note sits on a day and opens only with the lock password, every time. Forget the password and the notes can't be recovered.")
                .font(.system(size: 12))
                .foregroundStyle(Color.muted)
        }
    }

    private func setEnabled(_ on: Bool) {
        problem = nil
        if on && !status.hasPassword {
            passwordSheet = .set
            return
        }
        status.enabled = on
        Task {
            do {
                try await app.run { try $0.setLockEnabled(enabled: on) }
                app.changed()
                app.sync.schedulePush()
            } catch {
                problem = error.coreMessage
                await load()
            }
        }
    }

    private func reset() {
        Task {
            do {
                try await app.run { try $0.resetLock() }
                app.changed()
                app.sync.schedulePush()
            } catch {
                problem = error.coreMessage
            }
        }
    }

    private func load() async {
        if let s = try? await app.run({ try $0.lockStatus() }) { status = s }
    }
}

enum PasswordMode: String, Identifiable {
    /// The first password, which also turns locked notes on.
    case set
    /// Current password, then the new one twice; re-seals every note.
    case change
    var id: String { rawValue }
}

/// Setting or changing the lock password: at least four characters,
/// entered twice.
struct LockPasswordSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let mode: PasswordMode

    @State private var current = ""
    @State private var new = ""
    @State private var again = ""
    @State private var problem: String?
    @State private var busy = false
    @FocusState private var focus: Field?

    private enum Field { case current, new, again }

    var body: some View {
        NavigationStack {
            Form {
                if mode == .change {
                    Section {
                        SecureField("Current password", text: $current)
                            .focused($focus, equals: .current)
                            .onSubmit { focus = .new }
                            .listRowBackground(Color.raised)
                    } header: {
                        Text("Current").microLabel()
                    }
                }
                Section {
                    SecureField("New password", text: $new)
                        .focused($focus, equals: .new)
                        .onSubmit { focus = .again }
                        .listRowBackground(Color.raised)
                    SecureField("New password again", text: $again)
                        .focused($focus, equals: .again)
                        .onSubmit(save)
                        .listRowBackground(Color.raised)
                } header: {
                    Text(mode == .set ? "Lock password" : "New").microLabel()
                } footer: {
                    VStack(alignment: .leading, spacing: 6) {
                        if let problem {
                            Text(problem)
                                .font(.system(size: 13))
                                .foregroundStyle(Color.danger)
                        }
                        Text(mode == .set
                             ? "At least four characters. It opens every locked note, on every device you sync. If you forget it, there's no way to recover them."
                             : "Every locked note is re-sealed under the new password.")
                            .font(.system(size: 12))
                            .foregroundStyle(Color.muted)
                    }
                }
            }
            .formStyle(.grouped)
            .scrollContentBackground(.hidden)
            .background(Color.paper)
            .navigationTitle(mode == .set ? "Set lock password" : "Change password")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if busy { ProgressView() } else {
                        Button("Save", action: save).disabled(new.isEmpty || again.isEmpty || (mode == .change && current.isEmpty))
                    }
                }
            }
            .disabled(busy)
            .onAppear { focus = mode == .change ? .current : .new }
        }
        .tint(Color.primary)
        #if os(iOS)
        .presentationDetents([.medium, .large])
        #else
        .frame(minWidth: 420, minHeight: mode == .change ? 360 : 280)
        #endif
    }

    private func save() {
        guard !busy else { return }
        guard new.count >= 4 else {
            problem = String(localized: "The password needs at least four characters.")
            return
        }
        guard new == again else {
            problem = String(localized: "The two new passwords don't match.")
            again = ""
            focus = .again
            return
        }
        let (old, pw) = (mode == .change ? current : nil, new)
        busy = true
        problem = nil
        Task {
            defer { busy = false }
            do {
                try await app.run { try $0.setLockPassword(current: old, newPassword: pw) }
                app.changed()
                app.sync.schedulePush()
                dismiss()
            } catch CoreError.Crypto(let message) where message == "wrong password" {
                problem = String(localized: "That's not the lock password")
                current = ""
                focus = .current
            } catch {
                problem = error.coreMessage
            }
        }
    }
}
