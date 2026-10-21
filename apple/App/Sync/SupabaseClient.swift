import Foundation

/// A signed-in Supabase Auth session.
struct Session: Codable, Sendable {
    var accessToken: String
    var refreshToken: String
    var expiresAt: Date
    var userID: String
    var email: String?

    var needsRefresh: Bool { expiresAt.timeIntervalSinceNow < 60 }
}

/// What a sync RPC can fail with. The database raises `PTxxx` codes, which
/// PostgREST returns as HTTP status `xxx` (supabase/README.md).
enum SyncError: Error, LocalizedError, Equatable {
    case signedOut
    /// The session was ended by the server (revoked, or the refresh token
    /// expired). Shown on the sign-in screen; nothing on the device is lost.
    case sessionEnded
    /// PT400 from `register_device`: this device id is still registered to
    /// an account the device was signed in to before.
    case deviceTaken
    case notActive          // PT402: no paid-through date
    case unknownDevice      // PT404 from a device-scoped call
    case behindCompaction   // PT409: load the snapshot first
    case overQuota          // PT413
    case server(Int, String)
    case offline

    var errorDescription: String? {
        switch self {
        case .signedOut: String(localized: "Signed out")
        case .sessionEnded: String(localized: "You were signed out. Sign in again to carry on syncing; nothing on this device was lost.")
        case .deviceTaken: String(localized: "This device is still linked to another account")
        case .notActive: String(localized: "Sync isn't active for this account")
        case .unknownDevice: String(localized: "This device was removed from sync")
        case .behindCompaction: String(localized: "Catching up from a snapshot")
        case .overQuota: String(localized: "Sync storage is full")
        case .server(let code, let message): "\(code): \(message)"
        case .offline: String(localized: "Offline")
        }
    }
}

/// Supabase Auth and the `librelog` RPCs over plain URLSession. No SDK: the
/// surface is a handful of POSTs, and owning it keeps the app small.
actor SupabaseClient {
    private let url: URL
    private let key: String
    private let http: URLSession
    private(set) var session: Session?
    /// Bumped whenever the session is replaced or dropped, so a refresh that
    /// was in flight at the time can't write the old session back.
    private var generation = 0
    private var refreshing: (generation: Int, task: Task<Session, Error>)?

    init(url: URL = Backend.url, key: String = Backend.publishableKey) {
        self.url = url
        self.key = key
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 30
        config.waitsForConnectivity = false
        self.http = URLSession(configuration: config)
        if let data = Keychain.read(.session) {
            session = try? JSONDecoder().decode(Session.self, from: data)
        }
    }

    // MARK: Auth

    /// Emails a sign-in message. With the default template it's a link back
    /// into the app; with the custom template it's a 6-digit code.
    func sendSignInEmail(to email: String) async throws {
        var comps = URLComponents(url: url.appending(path: "auth/v1/otp"), resolvingAgainstBaseURL: false)!
        comps.queryItems = [URLQueryItem(name: "redirect_to", value: "librelog://auth")]
        _ = try await post(comps.url!, body: ["email": email, "create_user": true], auth: false)
    }

    func verify(email: String, code: String) async throws {
        let data = try await post(url.appending(path: "auth/v1/verify"),
                                  body: ["type": "email", "email": email, "token": code], auth: false)
        try store(sessionJSON: data)
    }

    /// Email and password. The apps offer no way to set a password, so in
    /// practice this is for accounts given one by an admin (the App Review
    /// account, scripts/review-account.sh); everyone else uses a code or a
    /// provider.
    func signIn(email: String, password: String) async throws {
        let data = try await post(tokenURL("password"), body: ["email": email, "password": password], auth: false)
        try store(sessionJSON: data)
    }

    /// Native Sign in with Apple: exchange the identity token.
    func signInWithApple(idToken: String, nonce: String) async throws {
        let data = try await post(tokenURL("id_token"),
                                  body: ["provider": "apple", "id_token": idToken, "nonce": nonce], auth: false)
        try store(sessionJSON: data)
    }

    /// Where to send the browser to sign in with `provider` through Supabase's
    /// web flow (PKCE): it comes back to `redirect` with `?code=…`, which
    /// `exchange(code:verifier:)` turns into a session.
    func authorizeURL(provider: String, redirect: String, challenge: String) -> URL {
        var c = URLComponents(url: url.appending(path: "auth/v1/authorize"), resolvingAgainstBaseURL: false)!
        c.queryItems = [
            URLQueryItem(name: "provider", value: provider),
            URLQueryItem(name: "redirect_to", value: redirect),
            URLQueryItem(name: "code_challenge", value: challenge),
            URLQueryItem(name: "code_challenge_method", value: "s256"),
        ]
        return c.url!
    }

    /// The second half of the PKCE web flow: the code from the redirect, and
    /// the verifier whose hash went into `authorizeURL`.
    func exchange(code: String, verifier: String) async throws {
        let data = try await post(tokenURL("pkce"), body: ["auth_code": code, "code_verifier": verifier], auth: false)
        try store(sessionJSON: data)
    }

    /// The `librelog://auth#access_token=…` link from the sign-in email.
    func handleRedirect(_ redirect: URL) async throws {
        guard let fragment = redirect.fragment ?? URLComponents(url: redirect, resolvingAgainstBaseURL: false)?.query else {
            throw SyncError.signedOut
        }
        var params: [String: String] = [:]
        for pair in fragment.split(separator: "&") {
            let kv = pair.split(separator: "=", maxSplits: 1).map { String($0).removingPercentEncoding ?? String($0) }
            if kv.count == 2 { params[kv[0]] = kv[1] }
        }
        if let error = params["error_description"] { throw SyncError.server(400, error) }
        guard let access = params["access_token"], let refresh = params["refresh_token"] else {
            throw SyncError.signedOut
        }
        let expiresIn = Double(params["expires_in"] ?? "3600") ?? 3600
        let claims = Self.jwtClaims(access)
        try begin(Session(accessToken: access, refreshToken: refresh,
                          expiresAt: Date().addingTimeInterval(expiresIn),
                          userID: claims["sub"] as? String ?? "",
                          email: claims["email"] as? String))
    }

    /// Ends this device's session only. Supabase's default scope is global,
    /// which would sign the user out on every other device too.
    func signOut() async {
        let ended = session
        forgetSession()
        guard let ended else { return }
        var c = URLComponents(url: url.appending(path: "auth/v1/logout"), resolvingAgainstBaseURL: false)!
        c.queryItems = [URLQueryItem(name: "scope", value: "local")]
        var req = URLRequest(url: c.url!)
        req.httpMethod = "POST"
        req.setValue(key, forHTTPHeaderField: "apikey")
        req.setValue("Bearer \(ended.accessToken)", forHTTPHeaderField: "Authorization")
        _ = try? await http.data(for: req)
    }

    /// Drops the session here without telling the server, for when the
    /// server has already ended it.
    func forgetSession() {
        generation += 1
        session = nil
        Keychain.delete(.session)
    }

    /// A current access token, refreshing first if it has less than
    /// `validFor` seconds left. Throws `.signedOut` if the server won't
    /// refresh the session any more, having dropped it here.
    func accessToken(validFor: TimeInterval = 60) async throws -> String {
        guard let s = session else { throw SyncError.signedOut }
        if s.expiresAt.timeIntervalSinceNow >= validFor { return s.accessToken }
        return try await refresh().accessToken
    }

    /// Refreshes the session, once however many callers ask at the same time.
    private func refresh() async throws -> Session {
        if let refreshing, refreshing.generation == generation { return try await refreshing.task.value }
        guard let s = session else { throw SyncError.signedOut }
        let gen = generation
        let task = Task<Session, Error> {
            defer { if refreshing?.generation == gen { refreshing = nil } }
            let data: Data
            do {
                data = try await post(tokenURL("refresh_token"), body: ["refresh_token": s.refreshToken], auth: false)
            } catch SyncError.server(let status, _) where Self.refreshRejected(status: status) {
                // The refresh token was revoked, reused or has expired: this
                // session is over, and retrying would only fail again.
                if gen == generation { forgetSession() }
                throw SyncError.signedOut
            }
            // Signed out, or in again, while this was in flight.
            guard gen == generation else { throw SyncError.signedOut }
            let fresh = try Self.session(from: data)
            try save(fresh)
            return fresh
        }
        refreshing = (gen, task)
        return try await task.value
    }

    /// True if a failed refresh means the session is gone for good (Auth
    /// answers 400 `invalid_grant` or 401), rather than a passing problem
    /// worth retrying later.
    static func refreshRejected(status: Int) -> Bool {
        (400..<500).contains(status) && status != 408 && status != 429
    }

    // MARK: RPC

    /// Calls `librelog.<name>` and decodes its JSON result.
    func rpc<T: Decodable>(_ name: String, _ args: [String: any Sendable], as: T.Type = T.self) async throws -> T {
        let endpoint = url.appending(path: "rest/v1/rpc/\(name)")
        let data: Data
        do {
            data = try await post(endpoint, body: args, auth: true)
        } catch Unauthorised.token(let rejected) {
            // The token can be revoked or expire early (a clock out of step,
            // say): refresh once and try again before giving up on the session.
            if session?.accessToken == rejected { _ = try await refresh() }
            do {
                data = try await post(endpoint, body: args, auth: true)
            } catch Unauthorised.token {
                throw SyncError.signedOut
            }
        }
        if T.self == Empty.self { return Empty() as! T }
        return try JSONDecoder.supabase.decode(T.self, from: data)
    }

    struct Empty: Decodable {}

    // MARK: Plumbing

    private func tokenURL(_ grant: String) -> URL {
        var c = URLComponents(url: url.appending(path: "auth/v1/token"), resolvingAgainstBaseURL: false)!
        c.queryItems = [URLQueryItem(name: "grant_type", value: grant)]
        return c.url!
    }

    /// A 401 from an authenticated call, with the token it rejected.
    private enum Unauthorised: Error { case token(String) }

    private func post(_ endpoint: URL, body: [String: any Sendable], auth: Bool) async throws -> Data {
        var req = URLRequest(url: endpoint)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue(key, forHTTPHeaderField: "apikey")
        var token = ""
        if auth {
            token = try await accessToken()
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            req.setValue("librelog", forHTTPHeaderField: "Content-Profile")
        }
        req.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, response): (Data, URLResponse)
        do {
            (data, response) = try await http.data(for: req)
        } catch let e as URLError where [.notConnectedToInternet, .networkConnectionLost, .timedOut, .cannotFindHost].contains(e.code) {
            throw SyncError.offline
        }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            let message = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])
                .flatMap { $0["message"] as? String ?? $0["msg"] as? String ?? $0["error_description"] as? String }
                ?? String(decoding: data, as: UTF8.self)
            switch status {
            case 401 where auth: throw Unauthorised.token(token)
            case 400 where message.contains("device belongs to another account"): throw SyncError.deviceTaken
            case 402: throw SyncError.notActive
            case 404 where message.contains("unknown device"): throw SyncError.unknownDevice
            case 409: throw SyncError.behindCompaction
            case 413: throw SyncError.overQuota
            default: throw SyncError.server(status, message)
            }
        }
        return data
    }

    /// A session from a sign-in, replacing any other.
    private func store(sessionJSON data: Data) throws {
        try begin(Self.session(from: data))
    }

    private static func session(from data: Data) throws -> Session {
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let access = json["access_token"] as? String,
              let refresh = json["refresh_token"] as? String else {
            throw SyncError.server(500, "unexpected auth response")
        }
        let user = json["user"] as? [String: Any]
        let expiresIn = json["expires_in"] as? Double ?? 3600
        return Session(accessToken: access, refreshToken: refresh,
                       expiresAt: Date().addingTimeInterval(expiresIn),
                       userID: user?["id"] as? String ?? (jwtClaims(access)["sub"] as? String ?? ""),
                       email: user?["email"] as? String)
    }

    private func begin(_ s: Session) throws {
        generation += 1
        try save(s)
    }

    private func save(_ s: Session) throws {
        session = s
        Keychain.write(.session, try JSONEncoder().encode(s))
    }

    static func jwtClaims(_ jwt: String) -> [String: Any] {
        let parts = jwt.split(separator: ".")
        guard parts.count >= 2 else { return [:] }
        var b64 = String(parts[1]).replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        b64 += String(repeating: "=", count: (4 - b64.count % 4) % 4)
        guard let data = Data(base64Encoded: b64),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [:] }
        return obj
    }
}

extension JSONDecoder {
    static let supabase: JSONDecoder = {
        let d = JSONDecoder()
        d.keyDecodingStrategy = .convertFromSnakeCase
        return d
    }()
}
