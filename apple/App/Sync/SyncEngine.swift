import Foundation
import Observation

/// Drives text sync (SPEC.md §12.5): sign-in, journal-key setup, and the
/// push → pull → snapshot loop. The core seals and merges; this only moves
/// sealed strings between it and Supabase.
@MainActor
@Observable
final class SyncEngine {
    enum Phase: Equatable {
        /// Not signed in. The journal works fully; nothing leaves the device.
        case off
        /// Signed in, but this device doesn't hold the account's journal key.
        case needsKey
        /// Signed in with the key: syncing.
        case on
    }

    private(set) var phase: Phase = .off
    private(set) var email: String?
    private(set) var isSyncing = false
    private(set) var lastSynced: Date?
    private(set) var problem: SyncError?
    private(set) var pending: UInt32 = 0
    /// Set once, right after the first device creates the key, so the
    /// recovery phrase can be shown before the user moves on.
    var newRecoveryPhrase: String?

    @ObservationIgnored var onChange: (() -> Void)?

    private let store: Store
    private let client = SupabaseClient()
    private var realtime: RealtimeChannel?
    private var pushTask: Task<Void, Never>?
    private var syncAgain = false

    /// Ops pulled since this device last uploaded a snapshot.
    private var pulledSinceSnapshot: Int {
        get { UserDefaults.standard.integer(forKey: "sync.pulledSinceSnapshot") }
        set { UserDefaults.standard.set(newValue, forKey: "sync.pulledSinceSnapshot") }
    }

    private static let snapshotEvery = 5_000

    init(store: Store) {
        self.store = store
    }

    // MARK: Lifecycle

    /// Called at launch and on returning to the foreground.
    func start() async {
        guard let session = await client.session else {
            phase = .off
            return
        }
        email = session.email
        if let key = Keychain.read(.journalKey) {
            try? store.setJournalKey(key: key)
            phase = .on
            connectRealtime(userID: session.userID)
            await syncNow()
        } else {
            await adoptAccount()
        }
    }

    func schedulePush() {
        guard phase == .on else { return }
        pending = (try? store.pendingCount()) ?? pending
        pushTask?.cancel()
        pushTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(1))
            guard !Task.isCancelled else { return }
            await self?.syncNow()
        }
    }

    func syncNow() async {
        guard phase == .on else { return }
        if isSyncing {
            syncAgain = true
            return
        }
        isSyncing = true
        defer { isSyncing = false }
        repeat {
            syncAgain = false
            do {
                try await push()
                let merged = try await pull()
                problem = nil
                lastSynced = Date()
                if merged { onChange?() }
                try await snapshotIfDue()
            } catch SyncError.signedOut {
                await signOut()
                return
            } catch let e as SyncError {
                problem = e
                return
            } catch {
                problem = .server(0, error.localizedDescription)
                return
            }
        } while syncAgain
        pending = (try? store.pendingCount()) ?? 0
    }

    // MARK: Sign-in

    func sendSignInEmail(_ address: String) async throws {
        try await client.sendSignInEmail(to: address.trimmingCharacters(in: .whitespaces))
        email = address
    }

    func verify(code: String) async throws {
        guard let email else { throw SyncError.signedOut }
        try await client.verify(email: email, code: code.filter(\.isNumber))
        await adoptAccount()
    }

    func signInWithApple(idToken: String, nonce: String) async throws {
        try await client.signInWithApple(idToken: idToken, nonce: nonce)
        await adoptAccount()
    }

    /// Sign in with Google in the system's browser sheet, through Supabase's
    /// web flow. Returns quietly if the sheet is closed.
    func signInWithGoogle() async throws {
        let verifier = PKCE.verifier()
        let start = await client.authorizeURL(provider: "google", redirect: WebSignIn.callback,
                                              challenge: PKCE.challenge(verifier))
        guard let callback = try await WebSignIn.run(start) else { return }
        let items = URLComponents(url: callback, resolvingAgainstBaseURL: false)?.queryItems ?? []
        if let error = items.first(where: { $0.name == "error_description" })?.value {
            throw SyncError.server(400, error)
        }
        guard let code = items.first(where: { $0.name == "code" })?.value else { throw SyncError.signedOut }
        try await client.exchange(code: code, verifier: verifier)
        await adoptAccount()
    }

    func handle(url: URL) async {
        guard url.scheme == "librelog", url.host == "auth" else { return }
        do {
            try await client.handleRedirect(url)
            await adoptAccount()
        } catch let e as SyncError {
            problem = e
        } catch {
            problem = .server(0, error.localizedDescription)
        }
    }

    func signOut() async {
        realtime?.close()
        realtime = nil
        await client.signOut()
        Keychain.delete(.journalKey)
        store.forgetJournalKey()
        phase = .off
        email = nil
    }

    /// Deletes the account and everything synced to it, then forgets it
    /// here. The journal on this device is untouched, and is queued to upload
    /// in full if the user signs in to a new account later.
    func deleteAccount() async throws {
        let _: SupabaseClient.Empty = try await client.rpc("delete_account", [:])
        await signOut()
        try store.resetSync()
    }

    // MARK: Journal key

    /// After sign-in: create the account's key if it has none, or use this
    /// device's stored key if it matches; otherwise ask for it.
    private func adoptAccount() async {
        do {
            let reg: [Registration] = try await client.rpc("register_device",
                ["p_device_id": store.deviceId(), "p_name_enc": ""])
            email = await client.session?.email ?? email

            if let check = reg.first?.keyCheck {
                if let key = Keychain.read(.journalKey), (try? store.keyMatches(key: key, keyCheck: check)) == true {
                    try await activate(key: key)
                } else {
                    phase = .needsKey
                }
                return
            }

            // A brand-new account: this device makes the key.
            let key = try store.generateJournalKey()
            try store.setJournalKey(key: key)
            let stored: String = try await client.rpc("set_key_check", ["p_key_check": try store.keyCheck()])
            if try store.keyMatches(key: key, keyCheck: stored) {
                newRecoveryPhrase = try store.recoveryPhrase()
                try await activate(key: key)
            } else {
                // Another device set it first, a moment ago.
                store.forgetJournalKey()
                phase = .needsKey
            }
        } catch let e as SyncError {
            problem = e
            if e == .signedOut { phase = .off }
        } catch {
            problem = .server(0, error.localizedDescription)
        }
    }

    /// Restores the key from the 24 words. Throws if they're not this account's.
    func useRecoveryPhrase(_ phrase: String) async throws {
        let key = try store.keyFromRecoveryPhrase(phrase: phrase)
        let reg: [Registration] = try await client.rpc("register_device",
            ["p_device_id": store.deviceId(), "p_name_enc": ""])
        guard let check = reg.first?.keyCheck, try store.keyMatches(key: key, keyCheck: check) else {
            throw RecoveryError.wrongPhrase
        }
        try await activate(key: key)
    }

    enum RecoveryError: LocalizedError {
        case wrongPhrase
        var errorDescription: String? {
            String(localized: "Those words don't match this account's journal key.")
        }
    }

    /// New device: shows a QR code and waits for an existing device to answer.
    func receiveKeyByPairing(_ session: PairingSession) async throws {
        let _: String = try await client.rpc("pairing_open", ["p_id": session.id()])
        for _ in 0..<120 { // ten minutes, like the server's expiry
            try await Task.sleep(for: .seconds(5))
            let payload: String? = try await client.rpc("pairing_take", ["p_id": session.id()])
            if let payload {
                let key = try session.receive(payload: payload)
                try await activate(key: key)
                return
            }
        }
        throw SyncError.server(408, String(localized: "No device answered the pairing code."))
    }

    /// Existing device: answers a scanned QR code with the sealed key.
    func approvePairing(qr: String) async throws {
        let code = try parsePairingQr(qr: qr)
        let payload = try store.sealKeyForPairing(publicKey: code.publicKey)
        let _: SupabaseClient.Empty = try await client.rpc("pairing_fulfil", ["p_id": code.id, "p_payload": payload])
    }

    private func activate(key: Data) async throws {
        try store.setJournalKey(key: key)
        Keychain.write(.journalKey, key)
        // Register again, now with the device's name sealed.
        let name = try store.sealDeviceName(name: DeviceInfo.name)
        let _: [Registration] = try await client.rpc("register_device",
            ["p_device_id": store.deviceId(), "p_name_enc": name])
        phase = .on
        if let user = await client.session?.userID { connectRealtime(userID: user) }
        await syncNow()
    }

    // MARK: The loop

    private func push() async throws {
        while let batch = try store.outgoingBatch(maxOps: 500) {
            let _: Int64 = try await client.rpc("push_ops", [
                "p_device_id": store.deviceId(),
                "p_batch_id": batch.batchId,
                "p_payloads": [batch.payload],
            ])
            try store.pushSucceeded(batchId: batch.batchId)
            pending = (try? store.pendingCount()) ?? 0
        }
    }

    /// Returns true if anything merged.
    private func pull() async throws -> Bool {
        var merged = false
        while true {
            let rows: [PulledRow]
            do {
                rows = try await client.rpc("pull_ops", [
                    "p_device_id": store.deviceId(),
                    "p_after": try store.syncCursor(),
                    "p_limit": 500,
                ])
            } catch SyncError.behindCompaction {
                try await loadSnapshot()
                merged = true
                continue
            }
            if rows.isEmpty { return merged }
            let result = try store.applyIncoming(rows: rows.map {
                IncomingOps(seq: $0.seq, deviceId: $0.deviceId, payload: $0.payload)
            })
            pulledSinceSnapshot += rows.count
            merged = merged || result.changed > 0
            if rows.count < 500 { return merged }
        }
    }

    private func loadSnapshot() async throws {
        let snaps: [SnapshotRow] = try await client.rpc("get_snapshot", ["p_device_id": store.deviceId()])
        guard let snap = snaps.first else { throw SyncError.server(404, "no snapshot to catch up from") }
        _ = try store.applySnapshot(seq: snap.seq, payload: snap.payload)
        pulledSinceSnapshot = 0
    }

    private func snapshotIfDue() async throws {
        guard pulledSinceSnapshot >= Self.snapshotEvery else { return }
        let snap = try store.snapshot()
        let _: SupabaseClient.Empty = try await client.rpc("put_snapshot", [
            "p_device_id": store.deviceId(), "p_seq": snap.seq, "p_payload": snap.payload,
        ])
        pulledSinceSnapshot = 0
    }

    private func connectRealtime(userID: String) {
        guard realtime == nil else { return }
        realtime = RealtimeChannel(topic: "user:\(userID)", client: client) { [weak self] in
            Task { @MainActor in await self?.syncNow() }
        }
        realtime?.connect()
    }
}

// MARK: Wire types

private struct Registration: Decodable {
    let lastSeq: Int64
    let syncUntil: String?
    let keyCheck: String?
    let quotaBytes: Int64
    let bytesUsed: Int64
}

private struct PulledRow: Decodable {
    let seq: Int64
    let deviceId: String
    let payload: String
}

private struct SnapshotRow: Decodable {
    let seq: Int64
    let payload: String
}

enum DeviceInfo {
    static var name: String {
        #if os(macOS)
        Host.current().localizedName ?? "Mac"
        #else
        // iOS 16+ returns the model ("iPhone") without a special entitlement.
        UIDevice.current.model
        #endif
    }
}

#if canImport(UIKit)
import UIKit
#endif
