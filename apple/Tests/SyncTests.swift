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
