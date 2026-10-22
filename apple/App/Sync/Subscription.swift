import Foundation
import Observation
import StoreKit

/// What the server says about the account's sync subscription
/// (`rpc/sync_status`, docs/billing.md). Pure, so the screen's wording is
/// testable.
struct SubscriptionStatus: Equatable, Sendable {
    /// Paid through. Pushes need it in the future; pulls are allowed for
    /// 30 days after it (SPEC.md §12.5).
    var syncUntil: Date?
    /// "app_store", "play" or "stripe": where the paying subscription is.
    var store: String?
    /// The store's state for it: active, grace, billing_retry, expired, …
    var state: String?
    var autoRenew = false

    enum Access: Equatable {
        /// Never paid.
        case none
        case active(until: Date)
        /// Lapsed: uploads stop, downloads carry on until `pullsUntil`.
        case lapsed(since: Date, pullsUntil: Date)
        /// Lapsed more than 30 days ago.
        case ended(since: Date)
    }

    func access(now: Date = .now) -> Access {
        guard let until = syncUntil else { return .none }
        if until > now { return .active(until: until) }
        let pulls = until.addingTimeInterval(30 * 86_400)
        return pulls > now ? .lapsed(since: until, pullsUntil: pulls) : .ended(since: until)
    }

    var isActive: Bool {
        if case .active = access() { true } else { false }
    }

    /// The store the user manages this subscription in, named for people.
    var storeName: String? {
        switch store {
        case "app_store": String(localized: "the App Store")
        case "play": String(localized: "Google Play")
        case "stripe": String(localized: "the web")
        default: nil
        }
    }

    /// One line under the status tag.
    func summary(now: Date = .now) -> String {
        let day = { (d: Date) in d.formatted(date: .abbreviated, time: .omitted) }
        switch access(now: now) {
        case .none:
            return String(localized: "Sync is a yearly subscription. Everything else in LibreLog is free.")
        case .active(let until):
            if state == "grace" || state == "billing_retry" {
                return String(localized: "The last payment didn't go through. Sync carries on until \(day(until)) while \(storeName ?? String(localized: "the store")) retries it.")
            }
            if store != nil, autoRenew { return String(localized: "Renews \(day(until)).") }
            return String(localized: "Active until \(day(until)).")
        case .lapsed(let since, let pullsUntil):
            return String(localized: "Ended \(day(since)). Changes made here aren't uploaded, but this device can download changes until \(day(pullsUntil)).")
        case .ended(let since):
            return String(localized: "Ended \(day(since)).")
        }
    }

    /// PostgREST timestamps: `2027-10-22T09:15:00+00:00`, sometimes with
    /// fractional seconds.
    static func date(_ text: String?) -> Date? {
        guard let text else { return nil }
        let plain = ISO8601DateFormatter()
        if let d = plain.date(from: text) { return d }
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return fractional.date(from: text)
    }
}

/// The yearly sync subscription through StoreKit 2. Purchases carry the
/// Supabase user id as `appAccountToken`, so the App Store's notifications
/// name the account; each verified transaction is also handed straight to
/// the server (`verify-purchase`) so sync starts without waiting for them.
@MainActor
@Observable
final class SyncSubscription {
    /// App Store Connect: subscription group "LibreLog Sync", this product,
    /// 1 year (docs/billing.md).
    static let productID = "uk.co.maybeitssoftware.librelog.sync.yearly"

    private(set) var product: Product?
    private(set) var busy = false
    /// The last thing worth telling the user: a failure, or that a purchase
    /// is waiting for approval.
    private(set) var message: String?

    private let sync: SyncEngine
    private var updates: Task<Void, Never>?

    init(sync: SyncEngine) {
        self.sync = sync
    }

    /// Called at launch: listens for renewals, refunds and purchases made
    /// elsewhere (Ask to Buy approvals, another device), and reports any
    /// transaction an earlier run didn't get to the server.
    func start() {
        guard updates == nil else { return }
        updates = Task { [weak self] in
            for await result in Transaction.updates {
                await self?.handle(result)
            }
        }
        Task {
            await loadProduct()
            await reportUnfinished()
        }
    }

    func loadProduct() async {
        guard product == nil else { return }
        product = try? await Product.products(for: [Self.productID]).first
    }

    /// "£2.99 a year", from the store, never hard-coded.
    var priceText: String? {
        guard let product else { return nil }
        return String(localized: "\(product.displayPrice) a year")
    }

    func purchase() async {
        guard let product else { return }
        guard let user = await sync.userID, let token = UUID(uuidString: user) else {
            message = String(localized: "Sign in to subscribe.")
            return
        }
        busy = true
        defer { busy = false }
        do {
            switch try await product.purchase(options: [.appAccountToken(token)]) {
            case .success(let result):
                await handle(result)
            case .pending:
                message = String(localized: "The purchase is waiting for approval. Sync starts as soon as it's approved.")
            case .userCancelled:
                break
            @unknown default:
                break
            }
        } catch {
            message = error.localizedDescription
        }
    }

    /// Restore purchases: asks the App Store for this Apple Account's
    /// transactions and reports the current one to the server.
    func restore() async {
        busy = true
        defer { busy = false }
        do {
            try await AppStore.sync()
        } catch StoreKitError.userCancelled {
            return
        } catch {
            message = error.localizedDescription
            return
        }
        var found = false
        for await result in Transaction.currentEntitlements {
            guard case .verified(let t) = result, t.productID == Self.productID else { continue }
            found = true
            await handle(result)
        }
        if !found { message = String(localized: "This Apple Account has no LibreLog Sync subscription to restore.") }
        await sync.refreshSubscription()
    }

    /// Reports transactions that never reached the server: made while
    /// signed out, or while it couldn't be reached.
    func reportUnfinished() async {
        for await result in Transaction.unfinished {
            await handle(result)
        }
    }

    private func handle(_ result: VerificationResult<Transaction>) async {
        // StoreKit couldn't verify it on the device either; the server would
        // refuse it, and it mustn't switch anything on.
        guard case .verified(let transaction) = result, transaction.productID == Self.productID else { return }
        // Signed out: it stays unfinished and is reported after sign-in.
        guard await sync.userID != nil else { return }
        do {
            try await sync.reportPurchase(["store": "app_store", "signed_transaction": result.jwsRepresentation])
            await transaction.finish()
            message = nil
        } catch SyncError.server(let status, let reason) where (400..<500).contains(status) {
            // Sending it again won't change the answer.
            await transaction.finish()
            message = Self.explain(status: status, reason: reason)
        } catch {
            // Offline or the server is down: unfinished transactions come
            // back through Transaction.unfinished on the next launch.
            message = String(localized: "Couldn't confirm the purchase with the sync server yet. It'll be tried again; nothing more to pay.")
        }
    }

    nonisolated static func explain(status: Int, reason: String) -> String {
        switch status {
        case 403: String(localized: "This subscription was bought while signed in to a different LibreLog account. Sign in to that account to use it.")
        case 409: String(localized: "This subscription already pays for sync on a different LibreLog account.")
        default: String(localized: "The sync server couldn't confirm this purchase (\(reason)).")
        }
    }
}

/// Where the subscription screen links to, as App Review requires.
enum Legal {
    /// Apple's standard licence agreement, which the app uses as its terms.
    static let terms = URL(string: "https://www.apple.com/legal/internet-services/itunes/dev/stdeula/")!
    static let privacy = URL(string: "https://maybeitssoftware.co.uk/librelog/privacy")!
    /// Subscription settings in the user's Apple Account (the Mac has no
    /// in-app sheet for them).
    static let manageSubscriptions = URL(string: "https://apps.apple.com/account/subscriptions")!
}
