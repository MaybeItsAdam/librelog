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
    case notActive          // PT402: no paid-through date
    case unknownDevice      // PT404 from a device-scoped call
    case behindCompaction   // PT409: load the snapshot first
    case overQuota          // PT413
    case server(Int, String)
    case offline

    var errorDescription: String? {
        switch self {
        case .signedOut: String(localized: "Signed out")
        case .notActive: String(localized: "Sync isn't active for this account")
        case .unknownDevice: String(localized: "This device was removed from sync")
        case .behindCompaction: String(localized: "Catching up from a snapshot")
        case .overQuota: String(localized: "Sync storage is full")
        case .server(let code, let message): "\(code): \(message)"
        case .offline: String(localized: "Offline")
        }
    }
}

/// Supabase Auth and the `dailylog` RPCs over plain URLSession. No SDK: the
/// surface is a handful of POSTs, and owning it keeps the app small.
actor SupabaseClient {
    private let url: URL
    private let key: String
    private let http: URLSession
    private(set) var session: Session?

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
        comps.queryItems = [URLQueryItem(name: "redirect_to", value: "dailylog://auth")]
        _ = try await post(comps.url!, body: ["email": email, "create_user": true], auth: false)
    }

    func verify(email: String, code: String) async throws {
        let data = try await post(url.appending(path: "auth/v1/verify"),
                                  body: ["type": "email", "email": email, "token": code], auth: false)
        try store(sessionJSON: data)
    }

    /// Native Sign in with Apple: exchange the identity token.
    func signInWithApple(idToken: String, nonce: String) async throws {
        let data = try await post(tokenURL("id_token"),
                                  body: ["provider": "apple", "id_token": idToken, "nonce": nonce], auth: false)
        try store(sessionJSON: data)
    }

    /// The `dailylog://auth#access_token=…` link from the sign-in email.
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
        try save(Session(accessToken: access, refreshToken: refresh,
                         expiresAt: Date().addingTimeInterval(expiresIn),
                         userID: claims["sub"] as? String ?? "",
                         email: claims["email"] as? String))
    }

    func signOut() async {
        if let s = session {
            var req = URLRequest(url: url.appending(path: "auth/v1/logout"))
            req.httpMethod = "POST"
            req.setValue(key, forHTTPHeaderField: "apikey")
            req.setValue("Bearer \(s.accessToken)", forHTTPHeaderField: "Authorization")
            _ = try? await http.data(for: req)
        }
        session = nil
        Keychain.delete(.session)
    }

    /// A current access token, refreshing first if it's about to expire.
    func accessToken() async throws -> String {
        guard var s = session else { throw SyncError.signedOut }
        if s.needsRefresh {
            let data = try await post(tokenURL("refresh_token"), body: ["refresh_token": s.refreshToken], auth: false)
            try store(sessionJSON: data)
            s = session!
        }
        return s.accessToken
    }

    // MARK: RPC

    /// Calls `dailylog.<name>` and decodes its JSON result.
    func rpc<T: Decodable>(_ name: String, _ args: [String: any Sendable], as: T.Type = T.self) async throws -> T {
        let data = try await post(url.appending(path: "rest/v1/rpc/\(name)"), body: args, auth: true)
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

    private func post(_ endpoint: URL, body: [String: any Sendable], auth: Bool) async throws -> Data {
        var req = URLRequest(url: endpoint)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue(key, forHTTPHeaderField: "apikey")
        if auth {
            req.setValue("Bearer \(try await accessToken())", forHTTPHeaderField: "Authorization")
            req.setValue("dailylog", forHTTPHeaderField: "Content-Profile")
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
            case 401 where auth: throw SyncError.signedOut
            case 402: throw SyncError.notActive
            case 404 where message.contains("unknown device"): throw SyncError.unknownDevice
            case 409: throw SyncError.behindCompaction
            case 413: throw SyncError.overQuota
            default: throw SyncError.server(status, message)
            }
        }
        return data
    }

    private func store(sessionJSON data: Data) throws {
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let access = json["access_token"] as? String,
              let refresh = json["refresh_token"] as? String else {
            throw SyncError.server(500, "unexpected auth response")
        }
        let user = json["user"] as? [String: Any]
        let expiresIn = json["expires_in"] as? Double ?? 3600
        try save(Session(accessToken: access, refreshToken: refresh,
                         expiresAt: Date().addingTimeInterval(expiresIn),
                         userID: user?["id"] as? String ?? (Self.jwtClaims(access)["sub"] as? String ?? ""),
                         email: user?["email"] as? String))
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
