import AuthenticationServices
import CoreImage.CIFilterBuiltins
import CryptoKit
import SwiftUI

/// Sync setup and status (SPEC.md §12). Everything here is optional: the
/// journal works fully without an account.
struct SyncSettingsView: View {
    @Environment(AppModel.self) private var app
    @State private var email = ""
    @State private var code = ""
    @State private var sentTo: String?
    @State private var busy = false
    @State private var message: String?
    @State private var nonce = ""

    var body: some View {
        let sync = app.sync
        Form {
            switch sync.phase {
            case .off: signIn
            case .needsKey: KeySetupView()
            case .on: status(sync)
            }
            if let message {
                Section { Text(message).font(.system(size: 13)).foregroundStyle(Color.danger) }
            }
            TransferSection()
            Section {
                Text("Entries are encrypted on this device before they're uploaded. The server only ever stores ciphertext; without your recovery phrase nobody, including us, can read them. Photos and other files stay on the device they were added on.")
                    .font(.system(size: 12))
                    .foregroundStyle(Color.muted)
            }
        }
        .formStyle(.grouped)
        .scrollContentBackground(.hidden)
        .background(Color.paper)
        .navigationTitle("Settings")
        .sheet(item: Binding(get: { sync.newRecoveryPhrase.map(Phrase.init) },
                             set: { sync.newRecoveryPhrase = $0?.words })) { p in
            RecoveryPhraseSheet(phrase: p.words)
        }
    }

    // MARK: Signed out

    @ViewBuilder
    private var signIn: some View {
        Section {
            SignInWithAppleButton(.continue) { request in
                nonce = Self.randomNonce()
                request.requestedScopes = [.email]
                request.nonce = Self.sha256(nonce)
            } onCompletion: { result in
                Task { await finishApple(result) }
            }
            .frame(height: 44)
            .signInWithAppleButtonStyle(.black)

            Button { Task { await signInWithGoogle() } } label: {
                Text("Continue with Google")
                    .font(.system(size: 17, weight: .medium))
                    .foregroundStyle(Color.ink)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .contentShape(Rectangle())
                    .overlay(RoundedRectangle(cornerRadius: Radius.control).strokeBorder(Color.inputBorder))
            }
            .buttonStyle(.plain)
            .disabled(busy)
        } header: {
            Text("Sync across your devices").microLabel()
        }

        Section {
            if let address = sentTo {
                Text("We sent a sign-in email to \(address). Open the link on this device, or type the code if the email has one.")
                    .font(.system(size: 13))
                    .foregroundStyle(Color.muted)
                TextField("6-digit code", text: $code)
                    .font(Typeface.mono(17, relativeTo: .body))
                    #if os(iOS)
                    .keyboardType(.numberPad)
                    .textContentType(.oneTimeCode)
                    #endif
                Button("Sign in") { Task { await verify() } }
                    .disabled(code.filter(\.isNumber).count < 6 || busy)
                Button("Use a different email") { sentTo = nil; code = "" }
                    .foregroundStyle(Color.muted)
            } else {
                TextField("Email", text: $email)
                    .textContentType(.emailAddress)
                    #if os(iOS)
                    .keyboardType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    #endif
                    .autocorrectionDisabled()
                Button("Email me a sign-in link") { Task { await sendEmail() } }
                    .disabled(!email.contains("@") || busy)
            }
        } header: {
            Text("Or with email").microLabel()
        }
    }

    // MARK: Signed in

    private func status(_ sync: SyncEngine) -> some View {
        Group {
            Section {
                LabeledContent("Account", value: sync.email ?? "—")
                LabeledContent("Status") {
                    if sync.isSyncing {
                        ProgressView().controlSize(.small)
                    } else if let problem = sync.problem {
                        StatusTag(text: problem == .notActive ? String(localized: "Inactive") : String(localized: "Problem"), hue: .danger)
                    } else {
                        StatusTag(text: String(localized: "Synced"), hue: .success)
                    }
                }
                if let last = sync.lastSynced {
                    LabeledContent("Last synced") {
                        Text(last, style: .relative).font(Typeface.mono(13)).foregroundStyle(Color.muted)
                    }
                }
                if sync.pending > 0 {
                    LabeledContent("Waiting to upload") { Text("\(sync.pending)").font(Typeface.mono(13)) }
                }
                if let problem = sync.problem {
                    Text(problem.localizedDescription).font(.system(size: 13)).foregroundStyle(Color.danger)
                }
                Button("Sync now") { Task { await sync.syncNow() } }
                    .disabled(sync.isSyncing)
            } header: {
                Text("Sync").microLabel()
            }

            Section {
                NavigationLink("Add another device") { PairingApproveView() }
                NavigationLink("Show recovery phrase") { RecoveryPhraseReveal() }
            } header: {
                Text("Devices").microLabel()
            }

            Section {
                Button("Sign out", role: .destructive) { Task { await sync.signOut() } }
            } footer: {
                Text("Signing out keeps everything on this device. It stops syncing until you sign in again.")
                    .font(.system(size: 12))
            }
        }
    }

    // MARK: Actions

    private func sendEmail() async {
        busy = true
        defer { busy = false }
        do {
            try await app.sync.sendSignInEmail(email)
            sentTo = email
            message = nil
        } catch {
            message = error.localizedDescription
        }
    }

    private func verify() async {
        busy = true
        defer { busy = false }
        do {
            try await app.sync.verify(code: code)
            message = nil
        } catch {
            message = error.localizedDescription
        }
    }

    private func signInWithGoogle() async {
        busy = true
        defer { busy = false }
        do {
            try await app.sync.signInWithGoogle()
            message = nil
        } catch {
            message = error.localizedDescription
        }
    }

    private func finishApple(_ result: Result<ASAuthorization, Error>) async {
        switch result {
        case .success(let auth):
            guard let cred = auth.credential as? ASAuthorizationAppleIDCredential,
                  let tokenData = cred.identityToken,
                  let token = String(data: tokenData, encoding: .utf8) else {
                message = String(localized: "Apple didn't return a sign-in token.")
                return
            }
            do {
                try await app.sync.signInWithApple(idToken: token, nonce: nonce)
                message = nil
            } catch {
                message = error.localizedDescription
            }
        case .failure(let error):
            if (error as? ASAuthorizationError)?.code != .canceled { message = error.localizedDescription }
        }
    }

    static func randomNonce() -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return Data(bytes).base64EncodedString()
    }

    static func sha256(_ s: String) -> String {
        SHA256.hash(data: Data(s.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}

private struct Phrase: Identifiable {
    let words: String
    var id: String { words }
}

/// Signed in on a device that doesn't have the journal key yet.
private struct KeySetupView: View {
    @Environment(AppModel.self) private var app
    @State private var phrase = ""
    @State private var pairing: PairingSession?
    @State private var error: String?
    @State private var busy = false

    var body: some View {
        Section {
            Text("This account already has a journal. To read it here, use another signed-in device, or type your 24-word recovery phrase.")
                .font(.system(size: 13))
                .foregroundStyle(Color.muted)
            if let pairing {
                VStack(spacing: 12) {
                    QRCodeView(text: pairing.qrPayload()).frame(width: 220, height: 220)
                    Text("On another device: Settings → Sync → Add another device, then scan this code.")
                        .font(.system(size: 12))
                        .foregroundStyle(Color.muted)
                        .multilineTextAlignment(.center)
                    Text(pairing.qrPayload())
                        .font(Typeface.mono(9))
                        .foregroundStyle(Color.dim)
                        .textSelection(.enabled)
                        .lineLimit(2)
                }
                .frame(maxWidth: .infinity)
            } else {
                Button("Pair with another device") { startPairing() }
            }
        } header: {
            Text("Unlock your journal").microLabel()
        }

        Section {
            TextEditor(text: $phrase)
                .font(Typeface.mono(14, relativeTo: .body))
                .frame(minHeight: 90)
                .autocorrectionDisabled()
                #if os(iOS)
                .textInputAutocapitalization(.never)
                #endif
            Button("Use recovery phrase") { Task { await usePhrase() } }
                .disabled(phrase.split(whereSeparator: \.isWhitespace).count < 24 || busy)
            if let error {
                Text(error).font(.system(size: 13)).foregroundStyle(Color.danger)
            }
        } header: {
            Text("Recovery phrase").microLabel()
        }
    }

    private func startPairing() {
        guard let session = try? PairingSession() else { return }
        pairing = session
        Task {
            do {
                try await app.sync.receiveKeyByPairing(session)
            } catch {
                self.error = error.localizedDescription
                pairing = nil
            }
        }
    }

    private func usePhrase() async {
        busy = true
        defer { busy = false }
        do {
            try await app.sync.useRecoveryPhrase(phrase)
        } catch {
            self.error = error.localizedDescription
        }
    }
}

/// On an existing device: answer a new device's pairing code.
private struct PairingApproveView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State private var code = ""
    @State private var state: String?
    @State private var scanning = false

    var body: some View {
        Form {
            Section {
                Text("On the new device, sign in to the same account and choose “Pair with another device”. Then scan its code here, or paste the text under it.")
                    .font(.system(size: 13))
                    .foregroundStyle(Color.muted)
                #if os(iOS)
                if QRScannerView.isAvailable {
                    Button("Scan code") { scanning = true }
                }
                #endif
                TextField("librelog-pair:…", text: $code, axis: .vertical)
                    .font(Typeface.mono(12))
                    .autocorrectionDisabled()
                Button("Send journal key") { Task { await approve(code) } }
                    .disabled(!code.hasPrefix("librelog-pair:"))
                if let state {
                    Text(state).font(.system(size: 13)).foregroundStyle(Color.muted)
                }
            }
        }
        .formStyle(.grouped)
        .scrollContentBackground(.hidden)
        .background(Color.paper)
        .navigationTitle("Add device")
        #if os(iOS)
        .sheet(isPresented: $scanning) {
            QRScannerView { scanned in
                scanning = false
                code = scanned
                Task { await approve(scanned) }
            }
            .ignoresSafeArea()
        }
        #endif
    }

    private func approve(_ qr: String) async {
        do {
            try await app.sync.approvePairing(qr: qr)
            state = String(localized: "Sent. The new device will finish setting up in a few seconds.")
        } catch {
            state = error.localizedDescription
        }
    }
}

private struct RecoveryPhraseReveal: View {
    @Environment(AppModel.self) private var app
    @State private var shown = false

    var body: some View {
        Form {
            Section {
                Text("Anyone with these words and your account can read your journal. Keep them somewhere safe and offline.")
                    .font(.system(size: 13))
                    .foregroundStyle(Color.muted)
                if shown, let phrase = try? app.store.recoveryPhrase() {
                    PhraseGrid(phrase: phrase)
                } else {
                    Button("Reveal") { shown = true }
                }
            }
        }
        .formStyle(.grouped)
        .scrollContentBackground(.hidden)
        .background(Color.paper)
        .navigationTitle("Recovery phrase")
    }
}

/// Shown once, right after sync creates the journal key.
private struct RecoveryPhraseSheet: View {
    let phrase: String
    @Environment(\.dismiss) private var dismiss
    @State private var confirmed = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("Write these 24 words down")
                        .font(Typeface.title)
                        .foregroundStyle(Color.ink)
                    Text("They're the only way to read your synced journal on a new device if you lose this one. Nobody can reset them for you, including us.")
                        .font(Typeface.serif(15))
                        .foregroundStyle(Color.muted)
                    PhraseGrid(phrase: phrase)
                    Toggle("I've written them down", isOn: $confirmed)
                    Button {
                        dismiss()
                    } label: {
                        Text("Done").frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(!confirmed)
                }
                .padding(24)
            }
            .background(Color.paper)
        }
        .interactiveDismissDisabled(!confirmed)
    }
}

private struct PhraseGrid: View {
    let phrase: String

    var body: some View {
        let words = phrase.split(separator: " ").map(String.init)
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), alignment: .leading), count: 3), spacing: 8) {
            ForEach(Array(words.enumerated()), id: \.offset) { i, w in
                HStack(spacing: 6) {
                    Text("\(i + 1)").font(Typeface.mono(10)).foregroundStyle(Color.dim).frame(width: 16, alignment: .trailing)
                    Text(w).font(Typeface.mono(14, relativeTo: .body)).foregroundStyle(Color.ink)
                }
            }
        }
        .padding(12)
        .background(Color.raised, in: RoundedRectangle(cornerRadius: Radius.card))
        .overlay(RoundedRectangle(cornerRadius: Radius.card).strokeBorder(Color.hairline))
        .textSelection(.enabled)
    }
}

struct QRCodeView: View {
    let text: String

    var body: some View {
        if let image = Self.render(text) {
            Image(decorative: image, scale: 1)
                .interpolation(.none)
                .resizable()
                .scaledToFit()
                .padding(12)
                .background(Color.white, in: RoundedRectangle(cornerRadius: Radius.card))
        }
    }

    static func render(_ text: String) -> CGImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let out = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)) else { return nil }
        return CIContext().createCGImage(out, from: out.extent)
    }
}
