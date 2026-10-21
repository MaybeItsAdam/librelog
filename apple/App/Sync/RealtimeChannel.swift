import Foundation

/// The private Realtime Broadcast channel `user:<uid>` (SPEC.md §12.5).
/// `push_ops` sends an `ops` event on it, and every other online device
/// pulls straight away. It carries no journal data, only "something new",
/// so a dropped connection only delays sync until the next foreground pull.
///
/// Speaks the Phoenix channel protocol Supabase Realtime uses: join with the
/// user's access token, heartbeat every 25 s, hand the server each refreshed
/// token before the old one expires, and rejoin after a drop or when the
/// server closes the channel.
final class RealtimeChannel: @unchecked Sendable {
    private let topic: String
    private let client: SupabaseClient
    private let onPing: @Sendable () -> Void
    private let queue = DispatchQueue(label: "librelog.realtime")
    private var task: URLSessionWebSocketTask?
    private var heartbeat: DispatchSourceTimer?
    private var tokenTimer: DispatchSourceTimer?
    private var token = ""
    private var joinRef = ""
    private var ref = 0
    private var closed = false
    private var retryDelay: TimeInterval = 1

    init(topic: String, client: SupabaseClient, onPing: @escaping @Sendable () -> Void) {
        self.topic = topic
        self.client = client
        self.onPing = onPing
    }

    func connect() {
        Task {
            do {
                let token = try await client.accessToken()
                queue.async { self.open(token: token) }
            } catch SyncError.signedOut {
                return // nothing to join with; sync notices on its next call
            } catch {
                queue.async { self.reconnect() } // offline: try again later
            }
        }
    }

    func close() {
        queue.async {
            self.closed = true
            self.stopTimers()
            self.task?.cancel(with: .goingAway, reason: nil)
            self.task = nil
        }
    }

    private var channel: String { "realtime:\(topic)" }

    private func open(token: String) {
        guard !closed else { return }
        var comps = URLComponents(url: Backend.url.appending(path: "realtime/v1/websocket"), resolvingAgainstBaseURL: false)!
        comps.scheme = "wss"
        comps.queryItems = [URLQueryItem(name: "apikey", value: Backend.publishableKey),
                            URLQueryItem(name: "vsn", value: "1.0.0")]
        let task = URLSession.shared.webSocketTask(with: comps.url!)
        self.task = task
        self.token = token
        task.resume()

        joinRef = send(topic: channel, event: "phx_join", payload: [
            "config": [
                "broadcast": ["self": false, "ack": false],
                "presence": ["key": ""],
                "postgres_changes": [] as [Any],
                "private": true,
            ],
            "access_token": token,
        ])
        startTimers()
        receive(on: task)
    }

    private func receive(on task: URLSessionWebSocketTask) {
        task.receive { [weak self] result in
            guard let self else { return }
            self.queue.async {
                // A socket already replaced by a reconnect: let it go quietly.
                guard task === self.task else { return }
                switch result {
                case .success(.string(let text)):
                    self.handle(text)
                    if task === self.task { self.receive(on: task) }
                case .success:
                    self.receive(on: task)
                case .failure:
                    self.reconnect()
                }
            }
        }
    }

    private func handle(_ text: String) {
        guard let msg = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any],
              let event = msg["event"] as? String,
              msg["topic"] as? String == channel else { return }
        let payload = msg["payload"] as? [String: Any]
        switch event {
        case "broadcast" where payload?["event"] as? String == "ops":
            onPing()
        case "phx_reply" where msg["ref"] as? String == joinRef:
            if payload?["status"] as? String == "ok" {
                retryDelay = 1
            } else {
                reconnect() // refused, e.g. an expired token: rejoin with a fresh one
            }
        case "phx_close", "phx_error":
            // The server dropped the channel (its token expired, or it
            // restarted). The socket may stay open, but nothing more arrives.
            reconnect()
        default:
            break
        }
    }

    private func reconnect() {
        stopTimers()
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
        guard !closed else { return }
        let delay = retryDelay
        retryDelay = min(retryDelay * 2, 60)
        queue.asyncAfter(deadline: .now() + delay) { [weak self] in
            // Fetch a fresh token: the old one may have expired while offline.
            self?.connect()
        }
    }

    private func startTimers() {
        stopTimers()
        let beat = DispatchSource.makeTimerSource(queue: queue)
        beat.schedule(deadline: .now() + 25, repeating: 25)
        beat.setEventHandler { [weak self] in
            self?.send(topic: "phoenix", event: "heartbeat", payload: [:])
        }
        beat.resume()
        heartbeat = beat

        // Realtime closes the channel when the token it joined with expires,
        // so pass each new one on before that. Tokens last an hour; asking for
        // one with three minutes left refreshes in good time.
        let refresh = DispatchSource.makeTimerSource(queue: queue)
        refresh.schedule(deadline: .now() + 60, repeating: 60)
        refresh.setEventHandler { [weak self] in self?.renewToken() }
        refresh.resume()
        tokenTimer = refresh
    }

    private func stopTimers() {
        heartbeat?.cancel()
        heartbeat = nil
        tokenTimer?.cancel()
        tokenTimer = nil
    }

    private func renewToken() {
        Task {
            guard let fresh = try? await client.accessToken(validFor: 180) else { return }
            queue.async {
                guard !self.closed, self.task != nil, fresh != self.token else { return }
                self.token = fresh
                self.send(topic: self.channel, event: "access_token", payload: ["access_token": fresh])
            }
        }
    }

    @discardableResult
    private func send(topic: String, event: String, payload: [String: Any]) -> String {
        ref += 1
        let msg: [String: Any] = ["topic": topic, "event": event, "payload": payload, "ref": String(ref)]
        guard let data = try? JSONSerialization.data(withJSONObject: msg),
              let text = String(data: data, encoding: .utf8) else { return String(ref) }
        task?.send(.string(text)) { _ in }
        return String(ref)
    }
}
