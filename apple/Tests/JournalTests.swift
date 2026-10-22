import XCTest
@testable import Librelog

/// New entries' default time and the day-preview menu.
final class JournalTests: XCTestCase {
    func testTodayStartsNowAndAMissedDayHasNoTime() {
        XCTAssertEqual(Day.defaultTime(for: "2026-10-22", today: "2026-10-22")?.count, 5)
        XCTAssertNil(Day.defaultTime(for: "2026-10-03", today: "2026-10-22"))
    }

    func testPreviewMenuOffersWhatIsntAlreadyChosen() {
        // The current preview can be turned off but not chosen again.
        XCTAssertFalse(DayPreview.actions(for: "a", preview: "a").useAsPreview)
        XCTAssertTrue(DayPreview.actions(for: "a", preview: "a").noPreview)
        // Another photo can take over, or the preview can go.
        XCTAssertTrue(DayPreview.actions(for: "b", preview: "a").useAsPreview)
        XCTAssertTrue(DayPreview.actions(for: "b", preview: "a").noPreview)
        // With no preview, any photo can become it; there's nothing to turn off.
        XCTAssertTrue(DayPreview.actions(for: "a", preview: nil).useAsPreview)
        XCTAssertFalse(DayPreview.actions(for: "a", preview: nil).noPreview)
    }
}
