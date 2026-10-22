import XCTest
@testable import Librelog

/// The small pure decisions sync makes about what the server and the user
/// sent it.
final class SyncTests: XCTestCase {
    /// Auth answers a dead refresh token with 400 `invalid_grant`; that, and
    /// any other client error, ends the session. Anything that might pass on
    /// its own keeps it.
    func testRefreshFailuresThatEndTheSession() {
        for status in [400, 401, 403, 404, 422] {
            XCTAssertTrue(SupabaseClient.refreshRejected(status: status), "\(status)")
        }
        for status in [0, 200, 408, 429, 500, 502, 503] {
            XCTAssertFalse(SupabaseClient.refreshRejected(status: status), "\(status)")
        }
    }

    func testEmailLosesSurroundingWhitespaceAndNewlines() {
        XCTAssertEqual(SyncEngine.normalisedEmail("  me@example.com \n"), "me@example.com")
        XCTAssertEqual(SyncEngine.normalisedEmail("\tme@example.com\r\n"), "me@example.com")
        XCTAssertEqual(SyncEngine.normalisedEmail("me@example.com"), "me@example.com")
    }
}

/// What the subscription section says about the account (docs/billing.md).
final class SubscriptionStatusTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)
    private let day: TimeInterval = 86_400

    func testAccessFollowsThePaidThroughDateAndThe30DayPullWindow() {
        XCTAssertEqual(SubscriptionStatus().access(now: now), .none)
        let later = now.addingTimeInterval(10 * day)
        XCTAssertEqual(SubscriptionStatus(syncUntil: later).access(now: now), .active(until: later))
        let earlier = now.addingTimeInterval(-10 * day)
        XCTAssertEqual(SubscriptionStatus(syncUntil: earlier).access(now: now),
                       .lapsed(since: earlier, pullsUntil: earlier.addingTimeInterval(30 * day)))
        let long = now.addingTimeInterval(-31 * day)
        XCTAssertEqual(SubscriptionStatus(syncUntil: long).access(now: now), .ended(since: long))
    }

    func testSummaryNamesRenewalsGraceAndLapses() {
        let later = now.addingTimeInterval(10 * day)
        XCTAssertTrue(SubscriptionStatus(syncUntil: later, store: "app_store", state: "active", autoRenew: true)
            .summary(now: now).hasPrefix("Renews "))
        XCTAssertTrue(SubscriptionStatus(syncUntil: later, store: "app_store", state: "active", autoRenew: false)
            .summary(now: now).hasPrefix("Active until "))
        XCTAssertTrue(SubscriptionStatus(syncUntil: later).summary(now: now).hasPrefix("Active until "),
                      "a hand grant has no store and doesn't renew")
        XCTAssertTrue(SubscriptionStatus(syncUntil: later, store: "play", state: "grace", autoRenew: true)
            .summary(now: now).contains("Google Play retries"))
        XCTAssertTrue(SubscriptionStatus(syncUntil: now.addingTimeInterval(-day)).summary(now: now).contains("can download"))
    }

    func testPostgrestTimestampsParse() {
        XCTAssertEqual(SubscriptionStatus.date("1970-01-01T00:00:10+00:00"), Date(timeIntervalSince1970: 10))
        XCTAssertEqual(SubscriptionStatus.date("1970-01-01T01:00:10+01:00"), Date(timeIntervalSince1970: 10))
        XCTAssertEqual(SubscriptionStatus.date("1970-01-01T00:00:10.5+00:00"), Date(timeIntervalSince1970: 10.5))
        XCTAssertNil(SubscriptionStatus.date(nil))
        XCTAssertNil(SubscriptionStatus.date("soon"))
    }

    func testRefusalsAreExplained() {
        XCTAssertTrue(SyncSubscription.explain(status: 403, reason: "").contains("different LibreLog account"))
        XCTAssertTrue(SyncSubscription.explain(status: 409, reason: "").contains("already pays"))
    }
}
