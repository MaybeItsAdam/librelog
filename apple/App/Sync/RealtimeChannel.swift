import Foundation

/// The private Realtime Broadcast channel `user:<uid>` (SPEC.md §12.5).
/// `push_ops` sends an `ops` event on it, and every other online device
/// pulls straight away. It carries no journal data, only "something new",
/// so a dropped connection only delays sync until the next foreground pull.
///
/// Speaks the Phoenix channel protocol Supabase Realtime uses: join with the
/// user's access token, heartbeat every 25 s, rejoin after a drop.
final class RealtimeChannel: @unchecked Sendable {
    private let topic: String
    private let client: SupabaseClient
    private let onPing: @Sendable () -> Void
    private let queue = DispatchQueue(label: "dailylog.realtime")
    private var task: URLSessionWebSocketTask?
    private var heartbeat: DispatchSourceTimer?
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
            guard let token = try? await client.accessToken() else { return }
            queue.async { self.open(token: token) }
        }
    }

    func close() {
        queue.async {
            self.closed = true
            self.heartbeat?.cancel()
            self.task?.cancel(with: .goingAway, reason: nil)
        }
    }

    private func open(token: String) {
        guard !closed else { return }
        var comps = URLComponents(url: Backend.url.appending(path: "realtime/v1/websocket"), resolvingAgainstBaseURL: false)!
        comps.scheme = "wss"
        comps.queryItems = [URLQueryItem(name: "apikey", value: Backend.publishableKey),
                            URLQueryItem(name: "vsn", value: "1.0.0")]
        let task = URLSession.shared.webSocketTask(with: comps.url!)
        self.task = task
        task.resume()

        send(topic: "realtime:\(topic)", event: "phx_join", payload: [
            "config": [
                "broadcast": ["self": false, "ack": false],
                "presence": ["key": ""],
                "postgres_changes": [] as [Any],
                "private": true,
            ],
            "access_token": token,
        ])
        startHeartbeat()
        receive()
    }

    private func receive() {
        task?.receive { [weak self] result in
            guard let self else { return }
            self.queue.async {
                switch result {
                case .success(.string(let text)):
                    self.retryDelay = 1
                    self.handle(text)
                    self.receive()
                case .success:
                    self.receive()
                case .failure:
                    self.reconnect()
                }
            }
        }
    }

    private func handle(_ text: String) {
        guard let msg = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any],
              let event = msg["event"] as? String else { return }
        if event == "broadcast",
           let payload = msg["payload"] as? [String: Any],
           payload["event"] as? String == "ops" {
            onPing()
        }
    }

    private func reconnect() {
        heartbeat?.cancel()
        guard !closed else { return }
        let delay = retryDelay
        retryDelay = min(retryDelay * 2, 60)
        queue.asyncAfter(deadline: .now() + delay) { [weak self] in
            // Fetch a fresh token: the old one may have expired while offline.
            self?.connect()
        }
    }

    private func startHeartbeat() {
        heartbeat?.cancel()
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + 25, repeating: 25)
        t.setEventHandler { [weak self] in
            self?.send(topic: "phoenix", event: "heartbeat", payload: [:])
        }
        t.resume()
        heartbeat = t
    }

    private func send(topic: String, event: String, payload: [String: Any]) {
        ref += 1
        let msg: [String: Any] = ["topic": topic, "event": event, "payload": payload, "ref": String(ref)]
        guard let data = try? JSONSerialization.data(withJSONObject: msg),
              let text = String(data: data, encoding: .utf8) else { return }
        task?.send(.string(text)) { _ in }
    }
}
