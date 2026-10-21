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
    /// Set right after this device creates the key, so the recovery phrase
    /// can be shown before the user moves on. Shown again on the next
    /// activation if it was never dismissed (the app quit, say).
    var newRecoveryPhrase: String? {
        didSet { if newRecoveryPhrase == nil { phraseUnconfirmed = false } }
    }

    @ObservationIgnored var onChange: (() -> Void)?

    private let store: Store
    private let client = SupabaseClient()
    private var realtime: RealtimeChannel?
    private var pushTask: Task<Void, Never>?
    private var syncAgain = false
    private var adoption: Task<Void, Never>?
    /// The user signed out, so calls still in flight failing for want of a
    /// session aren't news.
    private var signedOutHere = false

    /// Ops pulled since this device last uploaded a snapshot.
    private var pulledSinceSnapshot: Int {
        get { UserDefaults.standard.integer(forKey: "sync.pulledSinceSnapshot") }
        set { UserDefaults.standard.set(newValue, forKey: "sync.pulledSinceSnapshot") }
    }

    /// The account whose sync state (cursor, upload queue, server device id,
    /// journal key) this device holds. Kept through sign-out, so signing in
    /// to the same account again carries on where it left off, and signing
    /// in to a different one knows to start afresh.
    private var syncedUserID: String? {
        get { UserDefaults.standard.string(forKey: "sync.userID") }
        set { UserDefaults.standard.set(newValue, forKey: "sync.userID") }
    }

    /// The id this device registers with the server. The store's own id at
    /// first; replaced when the device moves to another account, because the
    /// server keeps each id to the account that registered it.
    private var deviceID: String {
        UserDefaults.standard.string(forKey: "sync.deviceID") ?? store.deviceId()
    }

    /// This device saved a key it made before the server confirmed it as the
    /// account's, so the key mustn't be used until `adoptAccount` has.
    private var keyUnconfirmed: Bool {
        get { UserDefaults.standard.bool(forKey: "sync.keyUnconfirmed") }
        set { UserDefaults.standard.set(newValue, forKey: "sync.keyUnconfirmed") }
    }

    /// This device made the key and the recovery phrase hasn't been shown.
    private var phraseUnconfirmed: Bool {
        get { UserDefaults.standard.bool(forKey: "sync.phraseUnconfirmed") }
        set { UserDefaults.standard.set(newValue, forKey: "sync.phraseUnconfirmed") }
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
        // Installs from before syncedUserID only kept a key while signed in.
        if syncedUserID == nil, Keychain.read(.journalKey) != nil { syncedUserID = session.userID }
        if syncedUserID == session.userID, !keyUnconfirmed, let key = Keychain.read(.journalKey) {
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
                // A lapsed account can't push, but can still pull for 30 days
                // (the server's grace period), so a refused push doesn't stop
                // this device catching up.
                var refused: SyncError?
                do {
                    try await push()
                } catch SyncError.notActive {
                    refused = .notActive
                }
                let merged = try await pull()
                problem = refused
                lastSynced = Date()
                if merged { onChange?() }
                if refused == nil { try await snapshotIfDue() }
            } catch SyncError.signedOut {
                await sessionEnded()
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
        let address = Self.normalisedEmail(address)
        try await client.sendSignInEmail(to: address)
        email = address
    }

    /// The address as typed, without the spaces or newline a paste can bring.
    nonisolated static func normalisedEmail(_ address: String) -> String {
        address.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    func verify(code: String) async throws {
        guard let email else { throw SyncError.signedOut }
        try await client.verify(email: email, code: code.filter(\.isNumber))
        await adoptAccount()
    }

    func signIn(email address: String, password: String) async throws {
        let address = Self.normalisedEmail(address)
        try await client.signIn(email: address, password: password)
        email = address
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

    /// Signs this device out: it leaves the account's device list and
    /// forgets the journal key, but keeps its place in the account's log, so
    /// signing in to the same account again only needs the key back.
    func signOut() async {
        await signOut(revoke: true)
    }

    private func signOut(revoke: Bool) async {
        signedOutHere = true
        stopSyncing()
        if revoke, await client.session != nil {
            // Best effort: a revoked device doesn't hold back compaction.
            let _: SupabaseClient.Empty? = try? await client.rpc("revoke_device", ["p_device_id": deviceID])
        }
        await client.signOut()
        Keychain.delete(.journalKey)
        keyUnconfirmed = false
        store.forgetJournalKey()
        phase = .off
        email = nil
        problem = nil
        newRecoveryPhrase = nil
    }

    /// The server ended the session (it was revoked, or the refresh token
    /// expired). Unlike signing out this keeps the journal key, so signing in
    /// to the same account again picks up without the recovery phrase.
    private func sessionEnded() async {
        stopSyncing()
        await client.forgetSession()
        store.forgetJournalKey()
        phase = .off
        problem = signedOutHere ? nil : .sessionEnded
    }

    private func stopSyncing() {
        pushTask?.cancel()
        realtime?.close()
        realtime = nil
        lastSynced = nil
    }

    /// Deletes the account and everything synced to it, then forgets it
    /// here. The journal on this device is untouched, and is queued to upload
    /// in full if the user signs in to a new account later.
    func deleteAccount() async throws {
        let _: SupabaseClient.Empty = try await client.rpc("delete_account", [:])
        await signOut(revoke: false)
        try forgetAccountState()
        syncedUserID = nil
    }

    /// Starts sync state afresh for another account: a new server device id,
    /// no cursor, and the whole journal queued to upload.
    private func forgetAccountState() throws {
        try store.resetSync()
        UserDefaults.standard.set(UUID().uuidString.lowercased(), forKey: "sync.deviceID")
        pulledSinceSnapshot = 0
    }

    // MARK: Journal key

    /// After sign-in: create the account's key if it has none, or use this
    /// device's stored key if it matches; otherwise ask for it. Runs one at a
    /// time: launch and a sign-in link can both get here at once, and two
    /// runs for a new account would each make a key.
    private func adoptAccount() async {
        signedOutHere = false
        let previous = adoption
        let run = Task {
            await previous?.value
            await adopt()
        }
        adoption = run
        await run.value
    }

    private func adopt() async {
        guard let session = await client.session else { return }
        // Already done by a run that was waiting ahead of this one.
        if phase == .on, syncedUserID == session.userID { return }
        problem = nil
        do {
            if let previous = syncedUserID, previous != session.userID {
                // Another account was synced here: its key and place in its
                // log mean nothing to this one.
                Keychain.delete(.journalKey)
                keyUnconfirmed = false
                phraseUnconfirmed = false
                store.forgetJournalKey()
                try forgetAccountState()
            }
            syncedUserID = session.userID
            email = session.email ?? email
            let reg = try await registerDevice(name: "")

            if let check = reg.first?.keyCheck {
                if let key = Keychain.read(.journalKey), (try? store.keyMatches(key: key, keyCheck: check)) == true {
                    try await activate(key: key)
                } else {
                    phase = .needsKey
                }
                return
            }

            // A brand-new account: this device makes the key. It's saved
            // before the server hears of it, so if the reply to set_key_check
            // is lost, the next try finds the same key here and the account
            // isn't left with a key check nobody holds the key for.
            let key: Data
            if let kept = Keychain.read(.journalKey) {
                key = kept
            } else {
                key = try store.generateJournalKey()
                keyUnconfirmed = true
                guard Keychain.write(.journalKey, key) else { throw Keychain.WriteError() }
                phraseUnconfirmed = true
            }
            try store.setJournalKey(key: key)
            let check = try store.keyCheck()
            let stored: String = try await client.rpc("set_key_check", ["p_key_check": check])
            if try store.keyMatches(key: key, keyCheck: stored) {
                try await activate(key: key)
            } else {
                // Another device set it first, a moment ago.
                Keychain.delete(.journalKey)
                keyUnconfirmed = false
                phraseUnconfirmed = false
                store.forgetJournalKey()
                phase = .needsKey
            }
        } catch SyncError.signedOut {
            await sessionEnded()
        } catch let e as SyncError {
            problem = e
        } catch {
            problem = .server(0, error.localizedDescription)
        }
    }

    /// Registers (or renames) this device. If its id still belongs to an
    /// account it was signed in to before this device kept track of which,
    /// starts afresh under a new id.
    private func registerDevice(name: String) async throws -> [Registration] {
        do {
            return try await client.rpc("register_device", ["p_device_id": deviceID, "p_name_enc": name])
        } catch SyncError.deviceTaken {
            try forgetAccountState()
            return try await client.rpc("register_device", ["p_device_id": deviceID, "p_name_enc": name])
        }
    }

    /// Restores the key from the 24 words. Throws if they're not this account's.
    func useRecoveryPhrase(_ phrase: String) async throws {
        let key = try store.keyFromRecoveryPhrase(phrase: phrase)
        let reg = try await registerDevice(name: "")
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
        guard Keychain.write(.journalKey, key) else {
            store.forgetJournalKey()
            throw Keychain.WriteError()
        }
        keyUnconfirmed = false
        // Taken from the key just set, with no suspension in between, so it
        // can't be another run's phrase.
        if phraseUnconfirmed { newRecoveryPhrase = try store.recoveryPhrase() }
        // Register again, now with the device's name sealed.
        let name = try store.sealDeviceName(name: DeviceInfo.name)
        _ = try await registerDevice(name: name)
        phase = .on
        if let user = await client.session?.userID { connectRealtime(userID: user) }
        await syncNow()
    }

    // MARK: The loop

    private func push() async throws {
        while let batch = try store.outgoingBatch(maxOps: 500) {
            let _: Int64 = try await client.rpc("push_ops", [
                "p_device_id": deviceID,
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
                    "p_device_id": deviceID,
                    "p_after": try store.syncCursor(),
                    "p_limit": 500,
                ])
            } catch SyncError.behindCompaction {
                try await loadSnapshot()
                merged = true
                continue
            }
            if rows.isEmpty { return merged }
            // The core skips its own ops by the store's device id, which the
            // server id stops matching once the device has changed accounts.
            let ownID = deviceID
            let result = try store.applyIncoming(rows: rows.map {
                IncomingOps(seq: $0.seq, deviceId: $0.deviceId == ownID ? store.deviceId() : $0.deviceId,
                            payload: $0.payload)
            })
            pulledSinceSnapshot += rows.count
            merged = merged || result.changed > 0
            if rows.count < 500 { return merged }
        }
    }

    private func loadSnapshot() async throws {
        let snaps: [SnapshotRow] = try await client.rpc("get_snapshot", ["p_device_id": deviceID])
        guard let snap = snaps.first else { throw SyncError.server(404, "no snapshot to catch up from") }
        _ = try store.applySnapshot(seq: snap.seq, payload: snap.payload)
        pulledSinceSnapshot = 0
    }

    private func snapshotIfDue() async throws {
        guard pulledSinceSnapshot >= Self.snapshotEvery else { return }
        let snap = try store.snapshot()
        let _: SupabaseClient.Empty = try await client.rpc("put_snapshot", [
            "p_device_id": deviceID, "p_seq": snap.seq, "p_payload": snap.payload,
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
