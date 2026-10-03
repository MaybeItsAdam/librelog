import XCTest
@testable import Dailylog

/// The editor must save exactly what it loaded: blocks → attributed string →
/// blocks is the identity, marks and all.
final class BlockTextTests: XCTestCase {
    func block(_ kind: BlockKind, _ text: String, _ marks: [Mark] = [], indent: UInt8 = 0) -> Block {
        Block(kind: kind, text: text, marks: marks, indent: indent)
    }

    func testRoundTripsEveryKindAndMark() {
        let doc = [
            block(.heading(level: 2), "Morning"),
            block(.paragraph, "Ran 5k 😀 by the canal", [
                Mark(start: 0, end: 3, kind: .bold),
                Mark(start: 7, end: 9, kind: .italic),
                Mark(start: 13, end: 22, kind: .link(url: "https://example.com")),
            ]),
            block(.bullet, "eggs"),
            block(.bullet, "nested", indent: 1),
            block(.numbered, "first"),
            block(.numbered, "second"),
            block(.check(checked: true), "done"),
            block(.check(checked: false), "todo"),
            block(.quote, "a quote"),
            block(.code, "let x = 1"),
            block(.paragraph, ""),
        ]
        let attributed = BlockText.attributed(doc)
        XCTAssertEqual(BlockText.blocks(attributed), doc)
    }

    func testNumberedMarkersCountUp() {
        let s = BlockText.attributed([block(.numbered, "a"), block(.numbered, "b"), block(.paragraph, "x"), block(.numbered, "c")])
        let lines = s.string.split(separator: "\n").map(String.init)
        XCTAssertTrue(lines[0].hasPrefix("1."))
        XCTAssertTrue(lines[1].hasPrefix("2."))
        XCTAssertTrue(lines[3].hasPrefix("1."))
    }

    func testEmptyDocumentIsOneEmptyParagraph() {
        XCTAssertEqual(BlockText.blocks(BlockText.attributed([])), [block(.paragraph, "")])
    }
}
